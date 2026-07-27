package org.holio.game

import kotlin.math.hypot

/**
 * Shared 2:1 isometric projection constants and input mapping.
 *
 * The world is drawn isometrically (see [Renderer]), so a world-space move maps
 * to a rotated/squashed screen move. [screenToWorld] inverts that: it turns the
 * on-screen direction the player pushed the joystick into the world-space
 * direction that makes the hole travel that way on screen — otherwise "up" on
 * the stick sends the hole diagonally along the iso grid.
 */
object Iso {
    /** Half-width and quarter-height of a world unit in the diamond. */
    const val X = 0.5f
    const val Y = 0.25f

    /**
     * Map a screen-space input vector to a world-space movement direction,
     * preserving the input's magnitude (a half-push still moves at half speed).
     */
    fun screenToWorld(sx: Float, sy: Float): Pair<Float, Float> {
        val m = hypot(sx, sy)
        if (m < 1e-4f) return 0f to 0f
        // Invert screen = ((wx-wy)*X, (wx+wy)*Y) for the world direction.
        val a = sx / X
        val b = sy / Y
        val wx = a + b
        val wy = b - a
        val wl = hypot(wx, wy)
        return (wx / wl * m) to (wy / wl * m)
    }
}
