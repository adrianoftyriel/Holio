package org.holio.game

import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * The game surface: owns the world, renderer, input and the loop thread.
 * Wires the [SurfaceHolder] lifecycle to starting/stopping the game loop.
 */
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    private val world = GameWorld()
    private val renderer = Renderer()
    private val joystick = Joystick()

    private var thread: GameThread? = null

    // Track which pointer owns the joystick so a second finger can't hijack it.
    private var joyPointerId = -1

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        world.setViewport(width.toFloat(), height.toFloat())
        startLoop()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        world.setViewport(width.toFloat(), height.toFloat())
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

    /** Called by [GameThread]. */
    fun update(dt: Float) {
        world.inputX = joystick.valueX
        world.inputY = joystick.valueY
        world.update(dt)
    }

    /** Called by [GameThread]. */
    fun render(canvas: Canvas) {
        renderer.draw(canvas, world, joystick)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // On the game-over screen, any tap starts a fresh round.
        if (world.state == GameWorld.State.GAME_OVER) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                world.restart()
                joystick.release()
                joyPointerId = -1
            }
            return true
        }

        when (event.actionMasked) {
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

    fun pause() {
        stopLoop()
    }

    fun resume() {
        // The loop restarts on surfaceCreated; if the surface is already valid,
        // start it here too.
        if (holder.surface.isValid) startLoop()
    }
}
