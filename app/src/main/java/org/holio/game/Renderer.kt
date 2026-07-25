package org.holio.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Draws the world in a top-down 2D style. Props are drawn first and the hole
 * on top, so a prop sinking toward the hole centre visually disappears into it.
 */
class Renderer {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val roof = Path()
    private val rect = RectF()

    private val grassColor = 0xFF7CB342.toInt()
    private val grassLine = 0xFF8BC34A.toInt()
    private val borderColor = 0xFF33691E.toInt()

    fun draw(canvas: Canvas, world: GameWorld, joy: Joystick, updateButton: RectF) {
        canvas.drawColor(grassColor)

        canvas.save()
        canvas.translate(-world.camX, -world.camY)

        drawGround(canvas, world)
        drawProps(canvas, world)
        drawHole(canvas, world)

        canvas.restore()

        drawHud(canvas, world)
        drawJoystick(canvas, joy)
        if (world.state == GameWorld.State.GAME_OVER) {
            drawGameOver(canvas, world)
        }
        // Drawn last so it stays tappable on top of the game-over overlay.
        drawUpdateButton(canvas, updateButton)
    }

    private fun drawGround(canvas: Canvas, world: GameWorld) {
        val size = world.worldSize
        // A subtle grid so movement is readable across the open field.
        stroke.color = grassLine
        stroke.strokeWidth = 2f
        var g = 0f
        while (g <= size) {
            canvas.drawLine(g, 0f, g, size, stroke)
            canvas.drawLine(0f, g, size, g, stroke)
            g += 140f
        }
        // Map border.
        stroke.color = borderColor
        stroke.strokeWidth = 14f
        canvas.drawRect(7f, 7f, size - 7f, size - 7f, stroke)
    }

    private fun drawProps(canvas: Canvas, world: GameWorld) {
        // Only draw what's near the viewport.
        val left = world.camX - 60f
        val top = world.camY - 60f
        val right = world.camX + world.viewportW + 60f
        val bottom = world.camY + world.viewportH + 60f

        for (prop in world.props) {
            if (prop.removed) continue
            val px = prop.drawX
            val py = prop.drawY
            if (px < left || px > right || py < top || py > bottom) continue
            drawProp(canvas, prop)
        }
    }

    private fun drawProp(canvas: Canvas, prop: Prop) {
        val r = prop.radius * prop.drawScale
        if (r <= 0.5f) return
        val cx = prop.drawX
        val cy = prop.drawY

        // Ground shadow, offset down-right for a hint of depth.
        canvas.drawOval(cx - r + 3f, cy - r * 0.5f + 4f, cx + r + 3f, cy + r + 4f, shadow)

        when (prop.type) {
            PropType.BUSH -> {
                fill.color = prop.type.bodyColor
                canvas.drawCircle(cx, cy, r, fill)
                fill.color = prop.type.accentColor
                canvas.drawCircle(cx - r * 0.3f, cy - r * 0.3f, r * 0.45f, fill)
            }
            PropType.TREE -> {
                fill.color = prop.type.accentColor // trunk
                canvas.drawCircle(cx, cy, r * 0.35f, fill)
                fill.color = prop.type.bodyColor // canopy
                canvas.drawCircle(cx, cy, r, fill)
                fill.color = 0xFF43A047.toInt()
                canvas.drawCircle(cx - r * 0.25f, cy - r * 0.25f, r * 0.5f, fill)
            }
            PropType.CAR -> {
                canvas.save()
                canvas.rotate(prop.rotationDeg, cx, cy)
                fill.color = prop.type.bodyColor
                rect.set(cx - r, cy - r * 0.55f, cx + r, cy + r * 0.55f)
                canvas.drawRoundRect(rect, r * 0.3f, r * 0.3f, fill)
                fill.color = prop.type.accentColor // windows / roof
                rect.set(cx - r * 0.4f, cy - r * 0.4f, cx + r * 0.5f, cy + r * 0.4f)
                canvas.drawRoundRect(rect, r * 0.2f, r * 0.2f, fill)
                canvas.restore()
            }
            PropType.HOUSE -> {
                canvas.save()
                canvas.rotate(prop.rotationDeg, cx, cy)
                fill.color = prop.type.bodyColor // walls
                rect.set(cx - r * 0.8f, cy - r * 0.8f, cx + r * 0.8f, cy + r * 0.8f)
                canvas.drawRect(rect, fill)
                // Roof as a diagonal band for a top-down "pitched" look.
                fill.color = prop.type.accentColor
                roof.reset()
                roof.moveTo(cx - r * 0.8f, cy - r * 0.8f)
                roof.lineTo(cx + r * 0.8f, cy - r * 0.8f)
                roof.lineTo(cx, cy)
                roof.close()
                canvas.drawPath(roof, fill)
                canvas.restore()
            }
        }
    }

    private fun drawHole(canvas: Canvas, world: GameWorld) {
        val h = world.hole
        // Soft rim for depth, then the black pit.
        fill.color = 0x22000000
        canvas.drawCircle(h.x, h.y, h.radius + 8f, fill)
        fill.color = Color.BLACK
        canvas.drawCircle(h.x, h.y, h.radius, fill)
        // A faint inner highlight ring so the edge reads clearly on dark props.
        stroke.color = 0x33FFFFFF
        stroke.strokeWidth = 3f
        canvas.drawCircle(h.x, h.y, h.radius - 2f, stroke)
    }

    private fun drawHud(canvas: Canvas, world: GameWorld) {
        val pad = 28f
        text.textSize = 54f
        text.color = Color.WHITE
        text.setShadowLayer(6f, 0f, 3f, 0x99000000.toInt())

        // Score, top-left.
        text.textAlign = Paint.Align.LEFT
        canvas.drawText("Score: ${world.score}", pad, pad + 50f, text)

        // Timer, top-right.
        val secs = world.secondsLeft()
        val mm = secs / 60
        val ss = secs % 60
        val timeStr = "%d:%02d".format(mm, ss)
        text.textAlign = Paint.Align.RIGHT
        text.color = if (secs <= 10) 0xFFFF5252.toInt() else Color.WHITE
        canvas.drawText(timeStr, canvas.width - pad, pad + 50f, text)

        text.clearShadowLayer()
    }

    private fun drawJoystick(canvas: Canvas, joy: Joystick) {
        if (!joy.active) return
        fill.color = 0x33FFFFFF
        canvas.drawCircle(joy.originX, joy.originY, Joystick.MAX_RADIUS, fill)
        fill.color = 0x88FFFFFF.toInt()
        canvas.drawCircle(joy.thumbX, joy.thumbY, Joystick.MAX_RADIUS * 0.45f, fill)
    }

    private fun drawUpdateButton(canvas: Canvas, r: RectF) {
        if (r.isEmpty) return
        // Rounded button with a small download glyph (arrow into a tray) and label.
        fill.color = 0xCC1B5E20.toInt()
        canvas.drawRoundRect(r, 18f, 18f, fill)
        stroke.color = 0xFFB2FF59.toInt()
        stroke.strokeWidth = 3f
        canvas.drawRoundRect(r, 18f, 18f, stroke)

        // Download arrow on the left side of the button.
        val gx = r.left + 34f
        val cy = r.centerY()
        stroke.color = Color.WHITE
        stroke.strokeWidth = 5f
        canvas.drawLine(gx, cy - 16f, gx, cy + 8f, stroke)
        canvas.drawLine(gx - 10f, cy - 2f, gx, cy + 10f, stroke)
        canvas.drawLine(gx + 10f, cy - 2f, gx, cy + 10f, stroke)
        canvas.drawLine(gx - 12f, cy + 18f, gx + 12f, cy + 18f, stroke)

        text.color = Color.WHITE
        text.textAlign = Paint.Align.LEFT
        text.textSize = 38f
        canvas.drawText("UPDATE", gx + 24f, cy + 14f, text)
    }

    private fun drawGameOver(canvas: Canvas, world: GameWorld) {
        fill.color = 0xB0000000.toInt()
        canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), fill)

        val cx = canvas.width / 2f
        val cy = canvas.height / 2f
        text.color = Color.WHITE
        text.textAlign = Paint.Align.CENTER

        text.textSize = 88f
        canvas.drawText("Time's Up!", cx, cy - 70f, text)

        text.textSize = 60f
        canvas.drawText("Score: ${world.score}", cx, cy + 10f, text)

        text.textSize = 44f
        text.color = 0xFFB2FF59.toInt()
        canvas.drawText("Tap to play again", cx, cy + 120f, text)
    }
}
