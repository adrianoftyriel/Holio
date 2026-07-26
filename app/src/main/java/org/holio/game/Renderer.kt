package org.holio.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws the world in a 2:1 isometric projection and renders the menu, settings
 * and pause screens.
 *
 * The simulation lives entirely in the flat top-down world plane (see
 * [GameWorld]); this class projects those world coordinates onto the screen as
 * an isometric diamond. A [zoom] factor keeps things zoomed in early and eases
 * out as the player's hole grows. The ground and holes are drawn as flat,
 * projected shapes; props stand up as billboards off their projected ground
 * point so the scene reads with depth.
 */
class Renderer {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val roof = Path()
    private val ground = Path()
    private val tilePath = Path()
    private val rect = RectF()

    /** Radial-gradient paint for hole pits and the screen vignette. */
    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val vignette = Paint()
    private var vigW = 0
    private var vigH = 0

    /** Props collected and depth-sorted once per frame (near ones drawn last). */
    private val depthSorted = ArrayList<Prop>(128)
    private val byDepth = Comparator<Prop> { a, b ->
        (a.drawX + a.drawY).compareTo(b.drawX + b.drawY)
    }

    /** Camera offset and zoom applied to the iso projection, per frame. */
    private var offX = 0f
    private var offY = 0f
    private var zoom = START_ZOOM

    private val grassColor = 0xFF7CB342.toInt()
    private val grassAlt = 0xFF6E9E36.toInt()
    private val borderColor = 0xFF33691E.toInt()
    private val backdrop = 0xFF20301A.toInt()

    // ---- Public entry points -------------------------------------------------

    /** Draw the playing field (with HUD, joystick and the settings gear). */
    fun drawGame(canvas: Canvas, world: Scene, joy: Joystick, gear: RectF) {
        computeCamera(world)
        canvas.drawColor(backdrop)
        drawGround(canvas, world)
        drawHoles(canvas, world)
        drawProps(canvas, world)
        drawHoleLabels(canvas, world)

        // Soft vignette to frame the scene.
        ensureVignette(canvas.width, canvas.height)
        canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), vignette)

        drawHud(canvas, world)
        drawJoystick(canvas, joy)
        drawGear(canvas, gear)
        if (world.state == GameWorld.State.GAME_OVER) {
            drawGameOver(canvas, world)
        }
    }

    /** Draw the main menu over a dimmed isometric backdrop. */
    fun drawMenu(canvas: Canvas, world: Scene, single: RectF, multi: RectF, settings: RectF, update: RectF) {
        computeCamera(world)
        canvas.drawColor(backdrop)
        drawGround(canvas, world)
        drawHoles(canvas, world)
        drawProps(canvas, world)

        fill.color = 0xB0000000.toInt()
        canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), fill)

        val cx = canvas.width / 2f
        text.setShadowLayer(10f, 0f, 4f, 0xCC000000.toInt())
        drawCenteredText(canvas, "HOLIO", cx, single.top - 132f, 120f, Color.WHITE)
        drawCenteredText(canvas, "Ad-free • roll and swallow", cx, single.top - 58f, 38f, 0xFFB2FF59.toInt())
        text.clearShadowLayer()

        drawButton(canvas, single, "Single Player", primary = true)
        drawButton(canvas, multi, "Local Multiplayer", primary = false)
        drawButton(canvas, settings, "Settings", primary = false)
        drawButton(canvas, update, "Update", primary = false)
    }

    /** Draw the multiplayer entry screen (Host / Join). */
    fun drawMpMenu(canvas: Canvas, host: RectF, join: RectF, back: RectF) {
        canvas.drawColor(0xFF16240E.toInt())
        val cx = canvas.width / 2f
        drawCenteredText(canvas, "Local Multiplayer", cx, host.top - 96f, 82f, Color.WHITE)
        drawCenteredText(canvas, "Play others on the same Wi-Fi", cx, host.top - 36f, 32f, 0xFFB2FF59.toInt())
        drawButton(canvas, host, "Host Game", primary = true)
        drawButton(canvas, join, "Join Game", primary = false)
        drawButton(canvas, back, "Back", primary = false)
    }

    /** Draw the host lobby: IP, joined players, bot count, Start / Back. */
    fun drawHostLobby(
        canvas: Canvas,
        ip: String,
        names: List<String>,
        bots: Int,
        minus: RectF,
        plus: RectF,
        start: RectF,
        back: RectF,
    ) {
        canvas.drawColor(0xFF16240E.toInt())
        val cx = canvas.width / 2f
        val h = canvas.height.toFloat()
        drawCenteredText(canvas, "Host Lobby", cx, h * 0.09f, 74f, Color.WHITE)
        drawCenteredText(canvas, "Others join at  $ip  ·  or auto-discover", cx, h * 0.17f, 30f, 0xFFB2FF59.toInt())

        val players = ArrayList<String>().apply { add("You (host)"); addAll(names) }
        var y = h * 0.27f
        drawCenteredText(canvas, "Players (${players.size})", cx, y, 34f, Color.WHITE)
        y += 44f
        for (p in players) {
            drawCenteredText(canvas, p, cx, y, 30f, 0xFFDDDDDD.toInt())
            y += 38f
        }

        // Bot count stepper.
        drawButton(canvas, minus, "–", primary = false)
        drawButton(canvas, plus, "+", primary = false)
        drawCenteredText(canvas, "Bots: $bots", cx, minus.centerY(), 44f, Color.WHITE)

        drawButton(canvas, start, "Start", primary = true)
        drawButton(canvas, back, "Cancel", primary = false)
    }

    /** Draw the join screen: discovered hosts, manual IP entry, Back. */
    fun drawJoinScreen(
        canvas: Canvas,
        found: List<GameClient.Found>,
        foundRects: Array<RectF>,
        enterIp: RectF,
        back: RectF,
        message: String?,
    ) {
        canvas.drawColor(0xFF16240E.toInt())
        val cx = canvas.width / 2f
        drawCenteredText(canvas, "Join a Game", cx, foundRects[0].top - 92f, 78f, Color.WHITE)
        drawCenteredText(canvas, "Discovered hosts on this Wi-Fi:", cx, foundRects[0].top - 34f, 30f, 0xFFB2FF59.toInt())

        for (i in foundRects.indices) {
            val r = foundRects[i]
            if (i < found.size) {
                drawButton(canvas, r, "${found[i].name}  ·  ${found[i].host}", primary = false)
            } else if (i == 0 && found.isEmpty()) {
                drawCenteredText(canvas, "searching…", cx, r.centerY(), 34f, 0x99FFFFFF.toInt())
            }
        }

        drawButton(canvas, enterIp, "Enter IP…", primary = true)
        drawButton(canvas, back, "Back", primary = false)
        if (message != null) {
            drawCenteredText(canvas, message, cx, back.bottom + 46f, 30f, 0xFFFFCC80.toInt())
        }
    }

    /** Draw a simple centered status screen (e.g. connecting / waiting). */
    fun drawWaiting(canvas: Canvas, line1: String, line2: String) {
        canvas.drawColor(backdrop)
        val cx = canvas.width / 2f
        val cy = canvas.height / 2f
        drawCenteredText(canvas, line1, cx, cy - 30f, 66f, Color.WHITE)
        drawCenteredText(canvas, line2, cx, cy + 44f, 34f, 0xFFB2FF59.toInt())
    }

    /** Draw the main-menu Settings screen (round-length picker). */
    fun drawSettingsScreen(canvas: Canvas, roundSeconds: Int, durations: Array<RectF>, back: RectF) {
        canvas.drawColor(0xFF16240E.toInt())

        val cx = canvas.width / 2f
        drawCenteredText(canvas, "Settings", cx, durations[0].top - 150f, 100f, Color.WHITE)
        drawCenteredText(canvas, "Round length", cx, durations[0].top - 60f, 46f, 0xFFB2FF59.toInt())

        for (i in Settings.DURATIONS.indices) {
            val secs = Settings.DURATIONS[i]
            drawButton(canvas, durations[i], formatTime(secs), primary = false, selected = secs == roundSeconds)
        }
        drawButton(canvas, back, "Back", primary = true)
    }

    /** Draw the level picker (Classic + real-world OSM levels). */
    fun drawLevelSelect(
        canvas: Canvas,
        levels: List<Level>,
        rects: Array<RectF>,
        back: RectF,
        message: String?,
    ) {
        canvas.drawColor(0xFF16240E.toInt())
        val cx = canvas.width / 2f
        drawCenteredText(canvas, "Choose a place", cx, rects[0].top - 92f, 84f, Color.WHITE)

        for (i in levels.indices) drawLevelButton(canvas, rects[i], levels[i])
        drawButton(canvas, back, "Back", primary = true)

        if (message != null) {
            drawCenteredText(canvas, message, cx, back.bottom + 46f, 30f, 0xFFFFCC80.toInt())
        }
    }

    private fun drawLevelButton(canvas: Canvas, r: RectF, level: Level) {
        fill.color = 0xCC12351E.toInt()
        canvas.drawRoundRect(r, 22f, 22f, fill)
        stroke.color = 0x66FFFFFF
        stroke.strokeWidth = 3f
        canvas.drawRoundRect(r, 22f, 22f, stroke)

        drawCenteredText(canvas, level.title, r.centerX(), r.centerY() - 16f, 42f, Color.WHITE)
        drawCenteredText(canvas, level.subtitle, r.centerX(), r.centerY() + 26f, 27f, 0xFFB2FF59.toInt())
    }

    /** Draw the "fetching real map data" screen shown while a level loads. */
    fun drawLoading(canvas: Canvas, title: String) {
        canvas.drawColor(backdrop)
        val cx = canvas.width / 2f
        val cy = canvas.height / 2f
        drawCenteredText(canvas, "Loading…", cx, cy - 46f, 80f, Color.WHITE)
        drawCenteredText(canvas, title, cx, cy + 36f, 52f, 0xFFB2FF59.toInt())
        drawCenteredText(canvas, "fetching real map data from OpenStreetMap", cx, cy + 108f, 30f, 0x99FFFFFF.toInt())
    }

    /**
     * Draw the in-game pause / settings overlay. In multiplayer ([mp]) the
     * shared round can't be paused or restarted, so only Resume + Leave show.
     */
    fun drawPauseOverlay(canvas: Canvas, resume: RectF, restart: RectF, end: RectF, mp: Boolean = false) {
        fill.color = 0xC0000000.toInt()
        canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), fill)

        val cx = canvas.width / 2f
        drawCenteredText(canvas, if (mp) "Menu" else "Paused", cx, resume.top - 70f, 96f, Color.WHITE)

        drawButton(canvas, resume, "Resume", primary = true)
        if (!mp) drawButton(canvas, restart, "Restart Level", primary = false)
        drawButton(canvas, end, if (mp) "Leave Game" else "End Level", primary = false)
    }

    // ---- Isometric projection ------------------------------------------------

    private fun projX(x: Float, y: Float) = (x - y) * ISO_X * zoom + offX
    private fun projY(x: Float, y: Float) = (x + y) * ISO_Y * zoom + offY

    /** Ease the zoom toward a target set by the player's size, then centre. */
    private fun computeCamera(world: Scene) {
        val player = world.hole
        val targetZoom = (START_ZOOM * (player.baseRadius / player.radius).pow(0.5f))
            .coerceIn(MIN_ZOOM, START_ZOOM)
        zoom += (targetZoom - zoom) * ZOOM_LERP

        val w = world.worldSize
        val vw = world.viewportW
        val vh = world.viewportH
        val zx = ISO_X * zoom
        val zy = ISO_Y * zoom
        val hx = (player.x - player.y) * zx
        val hy = (player.x + player.y) * zy

        val minSX = -w * zx
        val maxSX = w * zx
        val maxSY = w * (2f * zy)
        val margin = 180f

        val loX = vw - (maxSX + margin)
        val hiX = margin - minSX
        offX = if (loX > hiX) (loX + hiX) / 2f else (vw / 2f - hx).coerceIn(loX, hiX)

        val loY = vh - (maxSY + margin)
        val hiY = margin
        offY = if (loY > hiY) (loY + hiY) / 2f else (vh / 2f - hy).coerceIn(loY, hiY)
    }

    // ---- World drawing -------------------------------------------------------

    private fun drawGround(canvas: Canvas, world: Scene) {
        val w = world.worldSize

        // Base grass diamond.
        ground.reset()
        ground.moveTo(projX(0f, 0f), projY(0f, 0f))
        ground.lineTo(projX(w, 0f), projY(w, 0f))
        ground.lineTo(projX(w, w), projY(w, w))
        ground.lineTo(projX(0f, w), projY(0f, w))
        ground.close()
        fill.color = grassColor
        canvas.drawPath(ground, fill)

        // A "mowed lawn" checkerboard of darker tiles, culled to the viewport.
        fill.color = grassAlt
        val tile = 300f
        var i = 0
        var gx = 0f
        while (gx < w) {
            val gx2 = (gx + tile).coerceAtMost(w)
            var j = 0
            var gy = 0f
            while (gy < w) {
                val gy2 = (gy + tile).coerceAtMost(w)
                if ((i + j) and 1 == 1) {
                    val ccx = projX((gx + gx2) / 2f, (gy + gy2) / 2f)
                    val ccy = projY((gx + gx2) / 2f, (gy + gy2) / 2f)
                    if (ccx > -420f && ccx < world.viewportW + 420f && ccy > -420f && ccy < world.viewportH + 420f) {
                        tilePath.reset()
                        tilePath.moveTo(projX(gx, gy), projY(gx, gy))
                        tilePath.lineTo(projX(gx2, gy), projY(gx2, gy))
                        tilePath.lineTo(projX(gx2, gy2), projY(gx2, gy2))
                        tilePath.lineTo(projX(gx, gy2), projY(gx, gy2))
                        tilePath.close()
                        canvas.drawPath(tilePath, fill)
                    }
                }
                gy = gy2; j++
            }
            gx = gx2; i++
        }

        // Raised border along the diamond edges.
        stroke.color = borderColor
        stroke.strokeWidth = 12f
        canvas.drawPath(ground, stroke)
    }

    private fun drawHoles(canvas: Canvas, world: Scene) {
        for (h in world.holes) drawHolePit(canvas, h)
    }

    private fun drawHolePit(canvas: Canvas, h: Hole) {
        val cx = projX(h.x, h.y)
        val cy = projY(h.x, h.y)
        val rx = h.radius * ISO_X * zoom * SQRT2
        val ry = h.radius * ISO_Y * zoom * SQRT2

        // Soft outer shadow so the pit sits in the ground.
        fill.color = 0x22000000
        canvas.drawOval(cx - rx - 8f, cy - ry - 5f, cx + rx + 8f, cy + ry + 5f, fill)

        // The pit itself: a radial gradient from black to a hint of grey at the rim.
        val rad = maxOf(rx, ry).coerceAtLeast(1f)
        holePaint.shader = RadialGradient(
            cx, cy, rad,
            intArrayOf(0xFF000000.toInt(), 0xFF0A0A0A.toInt(), 0xFF1C1C1C.toInt()),
            floatArrayOf(0f, 0.72f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, holePaint)
        holePaint.shader = null

        // Coloured rim so holes are told apart at a glance.
        stroke.color = h.rimColor
        stroke.strokeWidth = (4f * zoom).coerceIn(3f, 10f)
        canvas.drawOval(cx - rx + 2f, cy - ry + 2f, cx + rx - 2f, cy + ry - 2f, stroke)
        // A faint top highlight for a touch of volume.
        stroke.color = 0x33FFFFFF
        stroke.strokeWidth = 2f
        canvas.drawOval(cx - rx * 0.7f, cy - ry - 1f, cx + rx * 0.7f, cy - ry * 0.2f, stroke)
    }

    /** Opponent name tags, drawn after props so they aren't hidden behind trees. */
    private fun drawHoleLabels(canvas: Canvas, world: Scene) {
        for (h in world.holes) {
            if (h.isPlayer) continue
            val cx = projX(h.x, h.y)
            val ry = h.radius * ISO_Y * zoom * SQRT2
            text.setShadowLayer(5f, 0f, 2f, 0xCC000000.toInt())
            drawCenteredText(canvas, h.name, cx, projY(h.x, h.y) - ry - 20f, 32f, h.rimColor)
            text.clearShadowLayer()
        }
    }

    private fun drawProps(canvas: Canvas, world: Scene) {
        depthSorted.clear()
        for (prop in world.props) {
            if (prop.removed || prop.drawScale <= 0.03f) continue
            depthSorted.add(prop)
        }
        depthSorted.sortWith(byDepth)

        for (prop in depthSorted) {
            val gx = projX(prop.drawX, prop.drawY)
            val gy = projY(prop.drawX, prop.drawY)
            if (gx < -240f || gx > world.viewportW + 240f ||
                gy < -320f || gy > world.viewportH + 160f
            ) continue
            drawProp(canvas, prop, gx, gy)
        }
    }

    private fun drawProp(canvas: Canvas, prop: Prop, gx: Float, gy: Float) {
        val s = prop.drawScale
        val r = prop.radius * s * zoom
        val h = prop.radius * prop.type.heightFactor * s * zoom
        val body = prop.type.bodyColor
        val accent = prop.type.accentColor

        shadow.color = 0x33000000
        canvas.drawOval(gx - r * 0.95f, gy - r * 0.5f, gx + r * 0.95f, gy + r * 0.5f, shadow)

        // Outline width scales with size so props stay crisp at any zoom.
        val ow = (2.2f * zoom * s).coerceIn(1.5f, 5f)

        when (prop.type) {
            PropType.BUSH -> {
                fill.color = body
                canvas.drawCircle(gx, gy - r * 0.5f, r, fill)
                stroke.color = darken(body, 0.6f); stroke.strokeWidth = ow
                canvas.drawCircle(gx, gy - r * 0.5f, r, stroke)
                fill.color = 0xFF9CCC65.toInt() // sun-side highlight
                canvas.drawCircle(gx - r * 0.32f, gy - r * 0.72f, r * 0.45f, fill)
            }
            PropType.TREE -> {
                val trunkW = r * 0.38f
                fill.color = accent
                rect.set(gx - trunkW, gy - h * 0.6f, gx + trunkW, gy)
                canvas.drawRect(rect, fill)
                stroke.color = darken(accent, 0.7f); stroke.strokeWidth = ow
                canvas.drawRect(rect, stroke)
                fill.color = body
                canvas.drawCircle(gx, gy - h * 0.72f, r, fill)
                stroke.color = darken(body, 0.6f); stroke.strokeWidth = ow
                canvas.drawCircle(gx, gy - h * 0.72f, r, stroke)
                fill.color = 0xFF66BB6A.toInt()
                canvas.drawCircle(gx - r * 0.28f, gy - h * 0.8f, r * 0.5f, fill)
            }
            PropType.CAR -> {
                val bw = r * 1.35f
                fill.color = body
                rect.set(gx - bw, gy - h * 0.55f, gx + bw, gy)
                canvas.drawRoundRect(rect, r * 0.35f, r * 0.35f, fill)
                stroke.color = darken(body, 0.6f); stroke.strokeWidth = ow
                canvas.drawRoundRect(rect, r * 0.35f, r * 0.35f, stroke)
                fill.color = accent
                rect.set(gx - bw * 0.6f, gy - h * 1.15f, gx + bw * 0.6f, gy - h * 0.5f)
                canvas.drawRoundRect(rect, r * 0.3f, r * 0.3f, fill)
                fill.color = 0x9990CAF9.toInt() // windshield glint
                rect.set(gx - bw * 0.42f, gy - h * 1.02f, gx + bw * 0.42f, gy - h * 0.62f)
                canvas.drawRoundRect(rect, r * 0.2f, r * 0.2f, fill)
            }
            PropType.HOUSE -> {
                val bw = r * 0.9f
                val wallTop = gy - h * 0.6f
                fill.color = body
                rect.set(gx - bw, wallTop, gx + bw, gy)
                canvas.drawRect(rect, fill)
                // Shade the left face for a hint of 3D.
                fill.color = darken(body, 0.82f)
                rect.set(gx - bw, wallTop, gx - bw * 0.2f, gy)
                canvas.drawRect(rect, fill)
                stroke.color = darken(body, 0.55f); stroke.strokeWidth = ow
                rect.set(gx - bw, wallTop, gx + bw, gy)
                canvas.drawRect(rect, stroke)
                // Pitched roof.
                fill.color = accent
                roof.reset()
                roof.moveTo(gx - bw * 1.15f, wallTop)
                roof.lineTo(gx + bw * 1.15f, wallTop)
                roof.lineTo(gx, gy - h)
                roof.close()
                canvas.drawPath(roof, fill)
                stroke.color = darken(accent, 0.6f); stroke.strokeWidth = ow
                canvas.drawPath(roof, stroke)
                // Door.
                fill.color = darken(body, 0.5f)
                rect.set(gx - bw * 0.24f, gy - h * 0.28f, gx + bw * 0.24f, gy)
                canvas.drawRect(rect, fill)
            }
        }
    }

    // ---- HUD & controls ------------------------------------------------------

    private fun drawHud(canvas: Canvas, world: Scene) {
        val w = canvas.width.toFloat()

        // Timer, top-centre.
        val secs = world.secondsLeft()
        text.setShadowLayer(6f, 0f, 3f, 0x99000000.toInt())
        drawCenteredText(
            canvas, formatTime(secs), w / 2f, 52f, 56f,
            if (secs <= 10) 0xFFFF5252.toInt() else Color.WHITE
        )

        // Scoreboard, top-right: ranked name + score, player highlighted.
        text.textAlign = Paint.Align.RIGHT
        text.textSize = 34f
        var y = 46f
        for ((i, h) in world.standings().withIndex()) {
            text.color = if (h.isPlayer) 0xFFB2FF59.toInt() else Color.WHITE
            canvas.drawText("${i + 1}. ${h.name}  ${h.score}", w - 24f, y, text)
            y += 42f
        }
        text.clearShadowLayer()
    }

    private fun drawJoystick(canvas: Canvas, joy: Joystick) {
        if (!joy.active) return
        fill.color = 0x33FFFFFF
        canvas.drawCircle(joy.originX, joy.originY, Joystick.MAX_RADIUS, fill)
        fill.color = 0x88FFFFFF.toInt()
        canvas.drawCircle(joy.thumbX, joy.thumbY, Joystick.MAX_RADIUS * 0.45f, fill)
    }

    private fun drawGear(canvas: Canvas, r: RectF) {
        if (r.isEmpty) return
        fill.color = 0xCC12351E.toInt()
        canvas.drawRoundRect(r, 18f, 18f, fill)
        stroke.color = 0x88FFFFFF.toInt()
        stroke.strokeWidth = 3f
        canvas.drawRoundRect(r, 18f, 18f, stroke)

        val cx = r.centerX()
        val cy = r.centerY()
        val ring = r.width() * 0.22f
        val tooth = r.width() * 0.14f
        stroke.color = Color.WHITE
        stroke.strokeWidth = r.width() * 0.12f
        canvas.drawCircle(cx, cy, ring, stroke)
        var a = 0
        while (a < 360) {
            val rad = Math.toRadians(a.toDouble())
            val dx = cos(rad).toFloat()
            val dy = sin(rad).toFloat()
            canvas.drawLine(
                cx + dx * ring, cy + dy * ring,
                cx + dx * (ring + tooth), cy + dy * (ring + tooth), stroke
            )
            a += 60
        }
    }

    private fun drawGameOver(canvas: Canvas, world: Scene) {
        fill.color = 0xC0000000.toInt()
        canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), fill)

        val standings = world.standings()
        val playerRank = standings.indexOfFirst { it.isPlayer }.coerceAtLeast(0)
        val cx = canvas.width / 2f
        var y = canvas.height * 0.20f

        val heading = if (playerRank == 0) "You Win!" else "You came ${ordinal(playerRank + 1)}"
        drawCenteredText(canvas, heading, cx, y, 88f, if (playerRank == 0) 0xFFB2FF59.toInt() else Color.WHITE)
        y += 92f

        for ((i, h) in standings.withIndex()) {
            val color = if (h.isPlayer) 0xFFB2FF59.toInt() else Color.WHITE
            drawCenteredText(canvas, "${i + 1}.  ${h.name}  —  ${h.score}", cx, y, 46f, color)
            y += 58f
        }

        y += 20f
        drawCenteredText(canvas, "Tap to play again", cx, y, 42f, 0x99FFFFFF.toInt())
    }

    // ---- Shared UI helpers ---------------------------------------------------

    private fun drawButton(
        canvas: Canvas,
        r: RectF,
        label: String,
        primary: Boolean,
        selected: Boolean = false,
    ) {
        fill.color = when {
            selected -> 0xFF43A047.toInt()
            primary -> 0xFF2E7D32.toInt()
            else -> 0xCC12351E.toInt()
        }
        canvas.drawRoundRect(r, 22f, 22f, fill)
        stroke.color = if (primary || selected) 0xFFB2FF59.toInt() else 0x66FFFFFF
        stroke.strokeWidth = 3f
        canvas.drawRoundRect(r, 22f, 22f, stroke)

        drawCenteredText(canvas, label, r.centerX(), r.centerY(), 44f, Color.WHITE)
    }

    private fun drawCenteredText(canvas: Canvas, s: String, cx: Float, cy: Float, size: Float, color: Int) {
        text.textAlign = Paint.Align.CENTER
        text.textSize = size
        text.color = color
        val fm = text.fontMetrics
        canvas.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2f, text)
    }

    private fun formatTime(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

    private fun ordinal(n: Int): String = when (n) {
        1 -> "1st"
        2 -> "2nd"
        3 -> "3rd"
        else -> "${n}th"
    }

    private fun ensureVignette(w: Int, h: Int) {
        if (w == vigW && h == vigH && vignette.shader != null) return
        vigW = w
        vigH = h
        val r = hypot(w / 2f, h / 2f).coerceAtLeast(1f)
        vignette.shader = RadialGradient(
            w / 2f, h / 2f, r,
            intArrayOf(0x00000000, 0x00000000, 0x55000000),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
    }

    private fun darken(color: Int, f: Float): Int {
        val a = (color ushr 24) and 0xFF
        val r = (((color ushr 16) and 0xFF) * f).toInt().coerceIn(0, 255)
        val g = (((color ushr 8) and 0xFF) * f).toInt().coerceIn(0, 255)
        val b = ((color and 0xFF) * f).toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    companion object {
        /** Half-width and quarter-height of a world unit in the 2:1 iso diamond. */
        private const val ISO_X = 0.5f
        private const val ISO_Y = 0.25f
        private val SQRT2 = sqrt(2f)

        /** Zoom eases between these as the player's hole grows. */
        private const val START_ZOOM = 3.0f
        private const val MIN_ZOOM = 0.85f
        private const val ZOOM_LERP = 0.06f
    }
}
