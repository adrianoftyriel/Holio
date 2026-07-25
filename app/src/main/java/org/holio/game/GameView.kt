package org.holio.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.min

/**
 * The game surface: owns the world, renderer, input and the loop thread, and
 * drives the top-level screen flow (main menu → game) plus the in-game pause
 * overlay. Wires the [SurfaceHolder] lifecycle to starting/stopping the loop.
 */
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    /** Top-level screens the surface can show. */
    private enum class Screen { MENU, SETTINGS, GAME }

    private val world = GameWorld()
    private val renderer = Renderer()
    private val joystick = Joystick()
    private val settings = Settings(context)

    private var thread: GameThread? = null
    private var screen = Screen.MENU

    /** While true the game is frozen behind the in-game settings overlay. */
    private var pauseOpen = false

    // Track which pointer owns the joystick so a second finger can't hijack it.
    private var joyPointerId = -1

    // --- Screen-space button rects, recomputed on size changes. ---
    // Main menu.
    private val rSingle = RectF()
    private val rSettings = RectF()
    private val rUpdate = RectF()
    // Settings screen.
    private val rDurations = Array(Settings.DURATIONS.size) { RectF() }
    private val rBack = RectF()
    // In-game.
    private val rGear = RectF()
    // Pause overlay.
    private val rResume = RectF()
    private val rRestart = RectF()
    private val rEnd = RectF()

    /** Invoked (on the UI thread) when the menu's Update button is tapped. */
    var onUpdateClick: (() -> Unit)? = null

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        onSizeKnown(width, height)
        startLoop()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        onSizeKnown(width, height)
    }

    private fun onSizeKnown(w: Int, h: Int) {
        world.setViewport(w.toFloat(), h.toFloat())
        layoutUi(w.toFloat(), h.toFloat())
    }

    /** Lay out every screen's buttons for the current surface size. */
    private fun layoutUi(w: Float, h: Float) {
        // Menu: a centred column of three buttons.
        val bw = min(w * 0.42f, 560f).coerceAtLeast(340f)
        val bh = 104f
        val gap = 26f
        val left = w / 2f - bw / 2f
        var top = h * 0.40f
        rSingle.set(left, top, left + bw, top + bh); top += bh + gap
        rSettings.set(left, top, left + bw, top + bh); top += bh + gap
        rUpdate.set(left, top, left + bw, top + bh)

        // Settings screen: a row of duration options above a Back button.
        val n = rDurations.size
        val dw = min(w * 0.22f, 260f)
        val dgap = 28f
        val totalW = dw * n + dgap * (n - 1)
        val startX = w / 2f - totalW / 2f
        val dy = h * 0.42f
        val dh = 132f
        for (i in 0 until n) {
            val x = startX + i * (dw + dgap)
            rDurations[i].set(x, dy, x + dw, dy + dh)
        }
        val backW = min(w * 0.34f, 320f)
        val backTop = dy + dh + 60f
        rBack.set(w / 2f - backW / 2f, backTop, w / 2f + backW / 2f, backTop + bh)

        // In-game gear, top-left.
        val gs = 96f
        rGear.set(24f, 24f, 24f + gs, 24f + gs)

        // Pause overlay: centred column of three buttons.
        var pt = h * 0.34f
        rResume.set(left, pt, left + bw, pt + bh); pt += bh + gap
        rRestart.set(left, pt, left + bw, pt + bh); pt += bh + gap
        rEnd.set(left, pt, left + bw, pt + bh)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopLoop()
    }

    private fun startLoop() {
        if (thread?.running == true) return
        thread = GameThread(holder, this).apply {
            running = true
            start()
        }
    }

    private fun stopLoop() {
        val t = thread ?: return
        t.running = false
        var retry = true
        while (retry) {
            try {
                t.join()
                retry = false
            } catch (ignored: InterruptedException) {
            }
        }
        thread = null
    }

    /** Called by [GameThread]. Only advances the simulation while actually playing. */
    fun update(dt: Float) {
        if (screen == Screen.GAME && !pauseOpen) {
            world.inputX = joystick.valueX
            world.inputY = joystick.valueY
            world.update(dt)
        }
    }

    /** Called by [GameThread]. */
    fun render(canvas: Canvas) {
        when (screen) {
            Screen.MENU -> renderer.drawMenu(canvas, world, rSingle, rSettings, rUpdate)
            Screen.SETTINGS -> renderer.drawSettingsScreen(canvas, settings.roundSeconds, rDurations, rBack)
            Screen.GAME -> {
                renderer.drawGame(canvas, world, joystick, rGear)
                if (pauseOpen) renderer.drawPauseOverlay(canvas, rResume, rRestart, rEnd)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return when (screen) {
            Screen.MENU -> onMenuTouch(event)
            Screen.SETTINGS -> onSettingsTouch(event)
            Screen.GAME -> onGameTouch(event)
        }
    }

    private fun onMenuTouch(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val x = event.x
            val y = event.y
            when {
                rSingle.contains(x, y) -> startGame()
                rSettings.contains(x, y) -> screen = Screen.SETTINGS
                rUpdate.contains(x, y) -> onUpdateClick?.invoke()
            }
        }
        return true
    }

    private fun onSettingsTouch(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val x = event.x
            val y = event.y
            for (i in rDurations.indices) {
                if (rDurations[i].contains(x, y)) {
                    settings.roundSeconds = Settings.DURATIONS[i]
                    return true
                }
            }
            if (rBack.contains(x, y)) screen = Screen.MENU
        }
        return true
    }

    private fun onGameTouch(event: MotionEvent): Boolean {
        val action = event.actionMasked

        // Pause overlay swallows all input while it's open.
        if (pauseOpen) {
            if (action == MotionEvent.ACTION_DOWN) {
                val x = event.x
                val y = event.y
                when {
                    rResume.contains(x, y) -> pauseOpen = false
                    rRestart.contains(x, y) -> startGame()
                    rEnd.contains(x, y) -> goToMenu()
                }
            }
            return true
        }

        // The gear opens the pause menu from anywhere (even on game-over).
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            val idx = event.actionIndex
            if (rGear.contains(event.getX(idx), event.getY(idx))) {
                openPause()
                return true
            }
        }

        // On the game-over screen, any other tap starts a fresh round.
        if (world.state == GameWorld.State.GAME_OVER) {
            if (action == MotionEvent.ACTION_DOWN) startGame()
            return true
        }

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (joyPointerId == -1) {
                    val idx = event.actionIndex
                    joyPointerId = event.getPointerId(idx)
                    joystick.press(event.getX(idx), event.getY(idx))
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (joyPointerId != -1) {
                    val idx = event.findPointerIndex(joyPointerId)
                    if (idx != -1) joystick.drag(event.getX(idx), event.getY(idx))
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                if (event.getPointerId(idx) == joyPointerId) {
                    joystick.release()
                    joyPointerId = -1
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                joystick.release()
                joyPointerId = -1
            }
        }
        return true
    }

    /** Start (or restart) a round with the currently chosen round length. */
    private fun startGame() {
        world.roundMillis = settings.roundMillis
        world.restart()
        resetInput()
        pauseOpen = false
        screen = Screen.GAME
    }

    private fun openPause() {
        pauseOpen = true
        resetInput()
    }

    private fun goToMenu() {
        pauseOpen = false
        resetInput()
        screen = Screen.MENU
    }

    private fun resetInput() {
        joystick.release()
        joyPointerId = -1
        world.inputX = 0f
        world.inputY = 0f
    }

    fun pause() {
        stopLoop()
    }

    fun resume() {
        // The loop restarts on surfaceCreated; if the surface is already valid,
        // start it here too.
        if (holder.surface.isValid) startLoop()
    }
}
