package org.holio.game

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A hole on the map — either the player's or an AI opponent's. It has a
 * position, a radius that grows as it swallows things, and a score. Larger
 * holes move a little slower, like hole.io.
 */
class Hole(
    startX: Float,
    startY: Float,
    val baseRadius: Float,
    /** Label shown on the scoreboard and (for opponents) floating above the pit. */
    val name: String = "You",
    /** Colour of the pit's rim ring, used to tell holes apart. */
    val rimColor: Int = 0xFF80D8FF.toInt(),
    val isPlayer: Boolean = true,
) {

    var x: Float = startX
        private set
    var y: Float = startY
        private set
    var radius: Float = baseRadius
        private set

    /** Running score for this hole (points from swallowed props + steals). */
    var score: Int = 0

    private var area: Float = (PI * baseRadius * baseRadius).toFloat()

    /** Current mouth area in px² (kept in sync with [radius]). */
    val mass: Float get() = area

    /** Move the hole, keeping its whole mouth inside the world bounds. */
    fun move(dx: Float, dy: Float, worldSize: Float) {
        x = (x + dx).coerceIn(radius, worldSize - radius)
        y = (y + dy).coerceIn(radius, worldSize - radius)
    }

    /** Grow by absorbing a fraction of a swallowed thing's area. */
    fun grow(swallowedArea: Float) {
        area += swallowedArea * GROWTH
        radius = sqrt(area / PI.toFloat())
    }

    /** Pixels-per-second travel speed, easing down slightly as the hole grows. */
    fun speed(): Float {
        val factor = (baseRadius / radius).pow(0.22f).coerceIn(0.55f, 1f)
        return BASE_SPEED * factor
    }

    /** Full reset: back to base size, no score, at the origin. */
    fun reset() {
        x = 0f
        y = 0f
        score = 0
        resetSize()
    }

    /** Shrink back to the starting radius without touching score or position. */
    fun resetSize() {
        radius = baseRadius
        area = (PI * baseRadius * baseRadius).toFloat()
    }

    fun placeAt(px: Float, py: Float) {
        x = px
        y = py
    }

    companion object {
        /** Fraction of a swallowed thing's area added to the hole. */
        private const val GROWTH = 0.55f

        /** Base travel speed in pixels per second at the starting radius. */
        private const val BASE_SPEED = 620f
    }
}
