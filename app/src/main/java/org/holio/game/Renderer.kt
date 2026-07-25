package org.holio.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws the world in a 2:1 isometric projection and renders the menu, settings
 * and pause screens.
 *
 * The simulation lives entirely in the flat top-down world plane (see
 * [GameWorld]); this class projects those world coordinates onto the screen as
 * an isometric diamond. The ground and the hole are drawn as flat, projected
 * shapes; props are stood up as billboards off their projected ground point so
 * the scene reads with depth.
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
    private val rect = RectF()

    /** Props collected and depth-sorted once per frame (near ones drawn last). */
    private val depthSorted = ArrayList<Prop>(128)
    private val byDepth = Comparator<Prop> { a, b ->
        (a.drawX + a.drawY).compareTo(b.drawX + b.drawY)
    }

    /** Camera offset applied after the iso projection, recomputed each frame. */
    private var offX = 0f
    private var offY = 0f

    private val grassColor = 0xFF7CB342.toInt()
    private val grassLine = 0xFF8BC34A.toInt()
    private val borderColor = 0xFF33691E.toInt()
    private val backdrop = 0xFF20301A.toInt()

    // ---- Public entry points -------------------------------------------------

    /** Draw the playing field (with HUD, joystick and the settings gear). */
    fun drawGame(canvas: Canvas, world: GameWorld, joy: Joystick, gear: RectF) {
        computeCamera(world)
        canvas.drawColor(backdrop)
        drawGround(canvas, world)
        drawHole(canvas, world)
        drawProps(canvas, world)

        drawHud(canvas, world, gear.right + 22f)
        drawJoystick(canvas, joy)
        drawGear(canvas, gear)
        if (world.state == GameWorld.State.GAME_OVER) {
            drawGameOver(canvas, world)
        }
    }

    /** Draw the main menu over a dimmed isometric backdrop. */
    fun drawMenu(canvas: Canvas, world: GameWorld, single: RectF, settings: RectF, update: RectF) {
        computeCamera(world)
        canvas.drawColor(backdrop)
        drawGround(canvas, world)
        drawHole(canvas, world)
        drawProps(canvas, world)

        // Dim the field so the menu reads clearly on top.
        fill.color = 0xB0000000.toInt()
        canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), fill)

        val cx = canvas.width / 2f
        text.setShadowLayer(10f, 0f, 4f, 0xCC000000.toInt())
        drawCenteredText(canvas, "HOLIO", cx, single.top - 150f, 132f, Color.WHITE)
        drawCenteredText(canvas, "Ad-free • single player", cx, single.top - 66f, 40f, 0xFFB2FF59.toInt())
        text.clearShadowLayer()

        drawButton(canvas, single, "Single Player", primary = true)
        drawButton(canvas, settings, "Settings", primary = false)
        drawButton(canvas, update, "Update", primary = false)
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

    /** Draw the in-game pause / settings overlay on top of the frozen game. */
    fun drawPauseOverlay(canvas: Canvas, resume: RectF, restart: RectF, end: RectF) {
        fill.color = 0xC0000000.toInt()
        canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), fill)

        val cx = canvas.width / 2f
        drawCenteredText(canvas, "Paused", cx, resume.top - 70f, 96f, Color.WHITE)

        drawButton(canvas, resume, "Resume", primary = true)
        drawButton(canvas, restart, "Restart Level", primary = false)
        drawButton(canvas, end, "End Level", primary = false)
    }

    // ---- Isometric projection ------------------------------------------------

    private fun projX(x: Float, y: Float) = (x - y) * ISO_X + offX
    private fun projY(x: Float, y: Float) = (x + y) * ISO_Y + offY

    /** Centre the camera on the hole, clamped so we don't scroll far off-map. */
    private fun computeCamera(world: GameWorld) {
        val w = world.worldSize
        val vw = world.viewportW
        val vh = world.viewportH
        val hx = (world.hole.x - world.hole.y) * ISO_X
        val hy = (world.hole.x + world.hole.y) * ISO_Y

        // Diamond extent in pre-offset projected space.
        val minSX = -w * ISO_X
        val maxSX = w * ISO_X
        val maxSY = w * (2f * ISO_Y)
        val margin = 180f

        val loX = vw - (maxSX + margin)
        val hiX = margin - minSX
        offX = if (loX > hiX) (loX + hiX) / 2f else (vw / 2f - hx).coerceIn(loX, hiX)

        val loY = vh - (maxSY + margin)
        val hiY = margin
        offY = if (loY > hiY) (loY + hiY) / 2f else (vh / 2f - hy).coerceIn(loY, hiY)
    }

    // ---- World drawing -------------------------------------------------------

    private fun drawGround(canvas: Canvas, world: GameWorld) {
        val w = world.worldSize

        ground.reset()
        ground.moveTo(projX(0f, 0f), projY(0f, 0f))
        ground.lineTo(projX(w, 0f), projY(w, 0f))
        ground.lineTo(projX(w, w), projY(w, w))
        ground.lineTo(projX(0f, w), projY(0f, w))
        ground.close()
        fill.color = grassColor
        canvas.drawPath(ground, fill)

        // Grid lines along the two world axes.
        stroke.color = grassLine
        stroke.strokeWidth = 2f
        var g = 0f
        while (g <= w) {
            canvas.drawLine(projX(g, 0f), projY(g, 0f), projX(g, w), projY(g, w), stroke)
            canvas.drawLine(projX(0f, g), projY(0f, g), projX(w, g), projY(w, g), stroke)
            g += 140f
        }

        // Raised border along the diamond edges.
        stroke.color = borderColor
        stroke.strokeWidth = 12f
        canvas.drawPath(ground, stroke)
    }

    private fun drawHole(canvas: Canvas, world: GameWorld) {
        val h = world.hole
        val cx = projX(h.x, h.y)
        val cy = projY(h.x, h.y)
        // A ground circle of radius R projects to an ellipse with these half-axes.
        val rx = h.radius * ISO_X * SQRT2
        val ry = h.radius * ISO_Y * SQRT2

        fill.color = 0x22000000
        canvas.drawOval(cx - rx - 7f, cy - ry - 4f, cx + rx + 7f, cy + ry + 4f, fill)
        fill.color = Color.BLACK
        canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, fill)
        stroke.color = 0x33FFFFFF
        stroke.strokeWidth = 3f
        canvas.drawOval(cx - rx + 3f, cy - ry + 3f, cx + rx - 3f, cy + ry - 3f, stroke)
    }

    private fun drawProps(canvas: Canvas, world: GameWorld) {
        depthSorted.clear()
        for (prop in world.props) {
            if (prop.removed || prop.drawScale <= 0.03f) continue
            depthSorted.add(prop)
        }
        depthSorted.sortWith(byDepth)

        for (prop in depthSorted) {
            val gx = projX(prop.drawX, prop.drawY)
            val gy = projY(prop.drawX, prop.drawY)
            // Cull anything comfortably off-screen (props can be tall, so give
            // extra headroom above).
            if (gx < -240f || gx > world.viewportW + 240f ||
                gy < -300f || gy > world.viewportH + 160f
            ) continue
            drawProp(canvas, prop, gx, gy)
        }
    }

    private fun drawProp(canvas: Canvas, prop: Prop, gx: Float, gy: Float) {
        val s = prop.drawScale
        val r = prop.radius * s
        val h = prop.radius * prop.type.heightFactor * s
        val body = prop.type.bodyColor
        val accent = prop.type.accentColor

        // Flat ground shadow at the projected footprint.
        shadow.color = 0x33000000
        canvas.drawOval(gx - r * 0.95f, gy - r * 0.5f, gx + r * 0.95f, gy + r * 0.5f, shadow)

        when (prop.type) {
            PropType.BUSH -> {
                fill.color = body
                canvas.drawCircle(gx, gy - r * 0.5f, r, fill)
                fill.color = accent
                canvas.drawCircle(gx - r * 0.3f, gy - r * 0.75f, r * 0.5f, fill)
            }
            PropType.TREE -> {
                val trunkW = r * 0.38f
                fill.color = accent // brown trunk
                rect.set(gx - trunkW, gy - h * 0.6f, gx + trunkW, gy)
                canvas.drawRect(rect, fill)
                fill.color = body // canopy
                canvas.drawCircle(gx, gy - h * 0.72f, r, fill)
                fill.color = 0xFF43A047.toInt()
                canvas.drawCircle(gx - r * 0.28f, gy - h * 0.8f, r * 0.55f, fill)
            }
            PropType.CAR -> {
                val bw = r * 1.35f
                // Lower body.
                fill.color = body
                rect.set(gx - bw, gy - h * 0.55f, gx + bw, gy)
                canvas.drawRoundRect(rect, r * 0.35f, r * 0.35f, fill)
                // Cabin.
                fill.color = accent
                rect.set(gx - bw * 0.6f, gy - h * 1.15f, gx + bw * 0.6f, gy - h * 0.5f)
                canvas.drawRoundRect(rect, r * 0.3f, r * 0.3f, fill)
            }
            PropType.HOUSE -> {
                val bw = r * 0.9f
                val wallTop = gy - h * 0.6f
                // Front wall.
                fill.color = body
                rect.set(gx - bw, wallTop, gx + bw, gy)
                canvas.drawRect(rect, fill)
                // Pitched roof.
                fill.color = accent
                roof.reset()
                roof.moveTo(gx - bw * 1.15f, wallTop)
                roof.lineTo(gx + bw * 1.15f, wallTop)
                roof.lineTo(gx, gy - h)
                roof.close()
                canvas.drawPath(roof, fill)
                // Door.
                fill.color = darken(body, 0.6f)
                rect.set(gx - bw * 0.24f, gy - h * 0.28f, gx + bw * 0.24f, gy)
                canvas.drawRect(rect, fill)
            }
        }
    }

    // ---- HUD & controls ------------------------------------------------------

    private fun drawHud(canvas: Canvas, world: GameWorld, scoreLeft: Float) {
        text.textSize = 54f
        text.textAlign = Paint.Align.LEFT
        text.color = Color.WHITE
        text.setShadowLayer(6f, 0f, 3f, 0x99000000.toInt())

        canvas.drawText("Score: ${world.score}", scoreLeft, 78f, text)

        val secs = world.secondsLeft()
        text.textAlign = Paint.Align.RIGHT
        text.color = if (secs <= 10) 0xFFFF5252.toInt() else Color.WHITE
        canvas.drawText(formatTime(secs), canvas.width - 28f, 78f, text)

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

        // A simple cog: a ring with short radial teeth.
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

    private fun drawGameOver(canvas: Canvas, world: GameWorld) {
        fill.color = 0xB0000000.toInt()
        canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), fill)

        val cx = canvas.width / 2f
        val cy = canvas.height / 2f
        drawCenteredText(canvas, "Time's Up!", cx, cy - 90f, 88f, Color.WHITE)
        drawCenteredText(canvas, "Score: ${world.score}", cx, cy - 10f, 60f, Color.WHITE)
        drawCenteredText(canvas, "Tap to play again", cx, cy + 90f, 44f, 0xFFB2FF59.toInt())
        drawCenteredText(canvas, "or use the gear to end or restart", cx, cy + 150f, 34f, 0x99FFFFFF.toInt())
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
    }
}
