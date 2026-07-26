package org.holio.game

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.text.InputType
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.EditText
import kotlin.math.min

/**
 * The game surface: owns the world, renderer, input and the loop thread, and
 * drives the top-level screen flow (menu → levels / multiplayer → game) plus
 * the in-game pause overlay.
 */
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    private enum class Screen { MENU, LEVELS, LOADING, SETTINGS, MP_MENU, MP_HOST_LOBBY, MP_JOIN, MP_WAIT, GAME }
    private enum class Mode { SINGLE, HOST, CLIENT }

    private val world = GameWorld()
    private val renderer = Renderer()
    private val joystick = Joystick()
    private val settings = Settings(context)
    private val osmLoader = OsmLevelLoader()

    private var thread: GameThread? = null

    @Volatile private var screen = Screen.MENU
    @Volatile private var mode = Mode.SINGLE
    @Volatile private var pauseOpen = false

    // Level-loading state.
    @Volatile private var loadingTitle = ""
    @Volatile private var levelMessage: String? = null
    @Volatile private var loadGeneration = 0

    // Multiplayer state.
    private var server: GameServer? = null
    private var client: GameClient? = null
    @Volatile private var mpBots = 3
    @Volatile private var hostPlayerNames: List<String> = emptyList()
    @Volatile private var hostAddress = ""
    @Volatile private var foundHosts: List<GameClient.Found> = emptyList()
    @Volatile private var mpMessage: String? = null
    @Volatile private var waitingLine = ""

    private var joyPointerId = -1

    // --- Button rects. ---
    private val rSingle = RectF()
    private val rMulti = RectF()
    private val rSettings = RectF()
    private val rUpdate = RectF()
    private val rLevels = Array(Level.ALL.size) { RectF() }
    private val rLevelBack = RectF()
    private val rDurations = Array(Settings.DURATIONS.size) { RectF() }
    private val rBack = RectF()
    private val rGear = RectF()
    private val rResume = RectF()
    private val rRestart = RectF()
    private val rEnd = RectF()
    private val rMpHost = RectF()
    private val rMpJoin = RectF()
    private val rMpBack = RectF()
    private val rBotsMinus = RectF()
    private val rBotsPlus = RectF()
    private val rHostStart = RectF()
    private val rHostBack = RectF()
    private val rFound = Array(4) { RectF() }
    private val rEnterIp = RectF()
    private val rJoinBack = RectF()

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
        client?.scene?.setViewport(w.toFloat(), h.toFloat())
        layoutUi(w.toFloat(), h.toFloat())
    }

    private fun layoutUi(w: Float, h: Float) {
        val bw = min(w * 0.42f, 560f).coerceAtLeast(340f)
        val left = w / 2f - bw / 2f
        val bh = 100f
        val gap = 24f

        // Main menu: four buttons.
        var top = h * 0.33f
        for (r in arrayOf(rSingle, rMulti, rSettings, rUpdate)) {
            r.set(left, top, left + bw, top + 92f); top += 92f + 18f
        }

        // Multiplayer menu: three buttons.
        var mt = h * 0.40f
        for (r in arrayOf(rMpHost, rMpJoin, rMpBack)) {
            r.set(left, mt, left + bw, mt + bh); mt += bh + gap
        }

        // Level picker: a 2-column grid so many levels fit on screen.
        val cols = 2
        val cw = min(w * 0.40f, 470f)
        val ch = 104f
        val cgx = 36f
        val cgy = 18f
        val gridW = cols * cw + (cols - 1) * cgx
        val gx0 = w / 2f - gridW / 2f
        val gy0 = h * 0.17f
        for (i in rLevels.indices) {
            val col = i % cols
            val row = i / cols
            val lx = gx0 + col * (cw + cgx)
            val ly = gy0 + row * (ch + cgy)
            rLevels[i].set(lx, ly, lx + cw, ly + ch)
        }
        val rows = (rLevels.size + cols - 1) / cols
        val backY = gy0 + rows * (ch + cgy) + 6f
        rLevelBack.set(w / 2f - 160f, backY, w / 2f + 160f, backY + 82f)

        val lleft = w / 2f - min(w * 0.5f, 620f).coerceAtLeast(360f) / 2f
        val lw = min(w * 0.5f, 620f).coerceAtLeast(360f)

        // Settings screen.
        val n = rDurations.size
        val dw = min(w * 0.22f, 260f)
        val dgap = 28f
        val totalW = dw * n + dgap * (n - 1)
        val sx = w / 2f - totalW / 2f
        val dy = h * 0.42f
        for (i in 0 until n) rDurations[i].set(sx + i * (dw + dgap), dy, sx + i * (dw + dgap) + dw, dy + 132f)
        val backW = min(w * 0.34f, 320f)
        rBack.set(w / 2f - backW / 2f, dy + 132f + 56f, w / 2f + backW / 2f, dy + 132f + 56f + bh)

        // Host lobby: bot stepper, Start, Cancel.
        val sy = h * 0.58f
        rBotsMinus.set(w / 2f - 250f, sy, w / 2f - 160f, sy + 90f)
        rBotsPlus.set(w / 2f + 160f, sy, w / 2f + 250f, sy + 90f)
        rHostStart.set(left, h * 0.72f, left + bw, h * 0.72f + bh)
        rHostBack.set(left, h * 0.72f + bh + gap, left + bw, h * 0.72f + bh + gap + bh)

        // Join screen: discovered hosts, Enter IP, Back.
        var jt = h * 0.28f
        for (r in rFound) { r.set(lleft, jt, lleft + lw, jt + 84f); jt += 84f + 14f }
        rEnterIp.set(left, jt + 8f, left + bw, jt + 8f + 88f)
        rJoinBack.set(left, jt + 8f + 88f + 18f, left + bw, jt + 8f + 88f + 18f + 88f)

        // In-game gear + pause overlay.
        rGear.set(24f, 24f, 24f + 96f, 24f + 96f)
        var pt = h * 0.34f
        for (r in arrayOf(rResume, rRestart, rEnd)) { r.set(left, pt, left + bw, pt + bh); pt += bh + gap }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopLoop()
    }

    private fun startLoop() {
        if (thread?.running == true) return
        thread = GameThread(holder, this).apply { running = true; start() }
    }

    private fun stopLoop() {
        val t = thread ?: return
        t.running = false
        var retry = true
        while (retry) {
            try { t.join(); retry = false } catch (ignored: InterruptedException) {}
        }
        thread = null
    }

    fun update(dt: Float) {
        if (screen == Screen.GAME && !pauseOpen && mode != Mode.CLIENT) {
            world.inputX = joystick.valueX
            world.inputY = joystick.valueY
            world.update(dt)
        }
    }

    fun render(canvas: Canvas) {
        when (screen) {
            Screen.MENU -> renderer.drawMenu(canvas, world, rSingle, rMulti, rSettings, rUpdate)
            Screen.LEVELS -> renderer.drawLevelSelect(canvas, Level.ALL, rLevels, rLevelBack, levelMessage)
            Screen.LOADING -> renderer.drawLoading(canvas, loadingTitle)
            Screen.SETTINGS -> renderer.drawSettingsScreen(canvas, settings.roundSeconds, rDurations, rBack)
            Screen.MP_MENU -> renderer.drawMpMenu(canvas, rMpHost, rMpJoin, rMpBack)
            Screen.MP_HOST_LOBBY -> renderer.drawHostLobby(
                canvas, hostAddress, hostPlayerNames, mpBots,
                rBotsMinus, rBotsPlus, rHostStart, rHostBack,
            )
            Screen.MP_JOIN -> renderer.drawJoinScreen(canvas, foundHosts, rFound, rEnterIp, rJoinBack, mpMessage)
            Screen.MP_WAIT -> renderer.drawWaiting(canvas, "Connecting…", waitingLine)
            Screen.GAME -> {
                if (mode == Mode.CLIENT) {
                    val sc = client?.scene
                    if (sc != null) synchronized(sc) { renderer.drawGame(canvas, sc, joystick, rGear) }
                } else {
                    renderer.drawGame(canvas, world, joystick, rGear)
                }
                if (pauseOpen) renderer.drawPauseOverlay(canvas, rResume, rRestart, rEnd, mp = mode != Mode.SINGLE)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return when (screen) {
            Screen.MENU -> onMenuTouch(event)
            Screen.LEVELS -> onLevelsTouch(event)
            Screen.LOADING, Screen.MP_WAIT -> true
            Screen.SETTINGS -> onSettingsTouch(event)
            Screen.MP_MENU -> onMpMenuTouch(event)
            Screen.MP_HOST_LOBBY -> onHostLobbyTouch(event)
            Screen.MP_JOIN -> onJoinTouch(event)
            Screen.GAME -> onGameTouch(event)
        }
    }

    private fun onMenuTouch(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val x = event.x; val y = event.y
            when {
                rSingle.contains(x, y) -> { levelMessage = null; screen = Screen.LEVELS }
                rMulti.contains(x, y) -> { mpMessage = null; screen = Screen.MP_MENU }
                rSettings.contains(x, y) -> screen = Screen.SETTINGS
                rUpdate.contains(x, y) -> onUpdateClick?.invoke()
            }
        }
        return true
    }

    private fun onLevelsTouch(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val x = event.x; val y = event.y
            for (i in rLevels.indices) if (rLevels[i].contains(x, y)) { selectLevel(Level.ALL[i]); return true }
            if (rLevelBack.contains(x, y)) screen = Screen.MENU
        }
        return true
    }

    private fun onSettingsTouch(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val x = event.x; val y = event.y
            for (i in rDurations.indices) if (rDurations[i].contains(x, y)) {
                settings.roundSeconds = Settings.DURATIONS[i]; return true
            }
            if (rBack.contains(x, y)) screen = Screen.MENU
        }
        return true
    }

    private fun onMpMenuTouch(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val x = event.x; val y = event.y
            when {
                rMpHost.contains(x, y) -> hostGame()
                rMpJoin.contains(x, y) -> openJoin()
                rMpBack.contains(x, y) -> screen = Screen.MENU
            }
        }
        return true
    }

    private fun onHostLobbyTouch(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val x = event.x; val y = event.y
            when {
                rBotsMinus.contains(x, y) -> mpBots = (mpBots - 1).coerceAtLeast(0)
                rBotsPlus.contains(x, y) -> mpBots = (mpBots + 1).coerceAtMost(7)
                rHostStart.contains(x, y) -> startHostMatch()
                rHostBack.contains(x, y) -> { server?.stop(); server = null; screen = Screen.MP_MENU }
            }
        }
        return true
    }

    private fun onJoinTouch(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val x = event.x; val y = event.y
            val hosts = foundHosts
            for (i in rFound.indices) {
                if (i < hosts.size && rFound[i].contains(x, y)) { connectTo(hosts[i].host, hosts[i].port); return true }
            }
            when {
                rEnterIp.contains(x, y) -> showIpDialog()
                rJoinBack.contains(x, y) -> { teardownClient(); screen = Screen.MP_MENU }
            }
        }
        return true
    }

    private fun onGameTouch(event: MotionEvent): Boolean {
        val action = event.actionMasked

        if (pauseOpen) {
            if (action == MotionEvent.ACTION_DOWN) {
                val x = event.x; val y = event.y
                when {
                    rResume.contains(x, y) -> pauseOpen = false
                    mode == Mode.SINGLE && rRestart.contains(x, y) -> startGame()
                    rEnd.contains(x, y) -> if (mode == Mode.SINGLE) goToMenu() else leaveMultiplayer()
                }
            }
            return true
        }

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            val idx = event.actionIndex
            if (rGear.contains(event.getX(idx), event.getY(idx))) { openPause(); return true }
        }

        if (currentState() == GameWorld.State.GAME_OVER) {
            if (action == MotionEvent.ACTION_DOWN && mode == Mode.SINGLE) startGame()
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
                if (event.getPointerId(idx) == joyPointerId) { joystick.release(); joyPointerId = -1 }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { joystick.release(); joyPointerId = -1 }
        }
        if (mode == Mode.CLIENT) client?.sendInput(joystick.valueX, joystick.valueY)
        return true
    }

    // ---- Single player -------------------------------------------------------

    private fun selectLevel(level: Level) {
        levelMessage = null
        if (level is Level.Osm) startOsmLoad(level) else { world.useProceduralLevel(); startGame() }
    }

    private fun startOsmLoad(level: Level.Osm) {
        loadingTitle = level.title
        screen = Screen.LOADING
        val gen = ++loadGeneration
        Thread {
            try {
                val result = osmLoader.load(level, world.worldSize)
                post {
                    if (gen != loadGeneration || screen != Screen.LOADING) return@post
                    if (result.props.isEmpty()) {
                        levelMessage = "No map data found for ${level.title}."; screen = Screen.LEVELS
                    } else {
                        world.useOsmLevel(result.props); startGame()
                    }
                }
            } catch (e: Exception) {
                post {
                    if (gen == loadGeneration && screen == Screen.LOADING) {
                        levelMessage = "Couldn't load ${level.title}: ${e.message ?: "network error"}"
                        screen = Screen.LEVELS
                    }
                }
            }
        }.start()
    }

    private fun startGame() {
        mode = Mode.SINGLE
        world.configureSinglePlayer()
        world.roundMillis = settings.roundMillis
        world.restart()
        resetInput()
        pauseOpen = false
        screen = Screen.GAME
    }

    // ---- Multiplayer ---------------------------------------------------------

    private fun hostGame() {
        val srv = GameServer(context, world) { names -> hostPlayerNames = names }
        if (srv.start()) {
            server = srv
            hostPlayerNames = emptyList()
            hostAddress = "${GameServer.localIp()}:${GameServer.PORT}"
            mpBots = 3
            screen = Screen.MP_HOST_LOBBY
        } else {
            mpMessage = "Couldn't start host (port in use?)."
            screen = Screen.MP_MENU
        }
    }

    private fun startHostMatch() {
        server?.beginMatch(mpBots, settings.roundMillis, "Host")
        mode = Mode.HOST
        resetInput()
        pauseOpen = false
        screen = Screen.GAME
    }

    private fun openJoin() {
        mpMessage = null
        foundHosts = emptyList()
        val c = GameClient(context)
        c.scene.setViewport(width.toFloat(), height.toFloat())
        c.startDiscovery { f -> foundHosts = (foundHosts + f).distinctBy { it.host }.take(rFound.size) }
        client = c
        screen = Screen.MP_JOIN
    }

    private fun connectTo(host: String, port: Int) {
        val c = client ?: return
        mpMessage = null
        c.stopDiscovery()
        waitingLine = "$host:$port"
        screen = Screen.MP_WAIT
        c.connect(
            host, port,
            onStarted = { post { mode = Mode.CLIENT; resetInput(); pauseOpen = false; screen = Screen.GAME } },
            onError = { msg -> post { if (screen == Screen.MP_WAIT) { mpMessage = "Couldn't connect: $msg"; screen = Screen.MP_JOIN } } },
            onDisconnected = { post { if (screen == Screen.GAME) { teardownClient(); mode = Mode.SINGLE; screen = Screen.MENU } } },
        )
    }

    private fun showIpDialog() {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            hint = "192.168.0.10"
        }
        AlertDialog.Builder(context)
            .setTitle("Enter host IP")
            .setView(input)
            .setPositiveButton("Connect") { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) connectTo(ip, GameServer.PORT)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun leaveMultiplayer() {
        server?.stop(); server = null
        teardownClient()
        mode = Mode.SINGLE
        pauseOpen = false
        resetInput()
        screen = Screen.MENU
    }

    private fun teardownClient() {
        client?.stopDiscovery()
        client?.stop()
        client = null
    }

    private fun currentState(): GameWorld.State =
        if (mode == Mode.CLIENT) client?.scene?.state ?: GameWorld.State.PLAYING else world.state

    // ---- Shared --------------------------------------------------------------

    private fun openPause() { pauseOpen = true; resetInput() }

    private fun goToMenu() { pauseOpen = false; resetInput(); screen = Screen.MENU }

    private fun resetInput() {
        joystick.release()
        joyPointerId = -1
        world.inputX = 0f
        world.inputY = 0f
        if (mode == Mode.CLIENT) client?.sendInput(0f, 0f)
    }

    fun pause() { stopLoop() }

    fun resume() { if (holder.surface.isValid) startLoop() }
}
