package org.holio.game

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The player-controlled hole. It has a position on the map and a radius that
 * grows as it swallows props. Larger holes move a little slower, like hole.io.
 */
class Hole(startX: Float, startY: Float, val baseRadius: Float) {

    var x: Float = startX
        private set
    var y: Float = startY
        private set
    var radius: Float = baseRadius
        private set

    private var area: Float = (PI * baseRadius * baseRadius).toFloat()

    /** Move the hole, keeping its whole mouth inside the world bounds. */
    fun move(dx: Float, dy: Float, worldSize: Float) {
        x = (x + dx).coerceIn(radius, worldSize - radius)
        y = (y + dy).coerceIn(radius, worldSize - radius)
    }

    /** Grow by absorbing a fraction of the swallowed prop's area. */
    fun grow(propArea: Float) {
        area += propArea * GROWTH
        radius = sqrt(area / PI.toFloat())
    }

    /** Pixels-per-second travel speed, easing down slightly as the hole grows. */
    fun speed(): Float {
        val factor = (baseRadius / radius).pow(0.22f).coerceIn(0.55f, 1f)
        return BASE_SPEED * factor
    }

    fun reset() {
        x = 0f
        y = 0f
        radius = baseRadius
        area = (PI * baseRadius * baseRadius).toFloat()
    }

    fun placeAt(px: Float, py: Float) {
        x = px
        y = py
    }

    companion object {
        /** Fraction of a swallowed prop's area added to the hole. */
        private const val GROWTH = 0.55f

        /** Base travel speed in pixels per second at the starting radius. */
        private const val BASE_SPEED = 620f
    }
}
