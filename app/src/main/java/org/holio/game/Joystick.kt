package org.holio.game

import kotlin.math.hypot

/**
 * A floating on-screen joystick. It appears where the finger first touches and
 * reports a direction/magnitude in the range [-1, 1] per axis.
 */
class Joystick {

    var active = false
        private set
    var originX = 0f
        private set
    var originY = 0f
        private set
    var thumbX = 0f
        private set
    var thumbY = 0f
        private set

    /** Normalized output, [-1, 1]. */
    var valueX = 0f
        private set
    var valueY = 0f
        private set

    fun press(x: Float, y: Float) {
        active = true
        originX = x
        originY = y
        thumbX = x
        thumbY = y
        valueX = 0f
        valueY = 0f
    }

    fun drag(x: Float, y: Float) {
        if (!active) return
        val dx = x - originX
        val dy = y - originY
        val len = hypot(dx, dy)
        if (len <= 0.0001f) {
            thumbX = originX
            thumbY = originY
            valueX = 0f
            valueY = 0f
            return
        }
        val clamped = len.coerceAtMost(MAX_RADIUS)
        val nx = dx / len
        val ny = dy / len
        thumbX = originX + nx * clamped
        thumbY = originY + ny * clamped
        val mag = clamped / MAX_RADIUS
        valueX = nx * mag
        valueY = ny * mag
    }

    fun release() {
        active = false
        valueX = 0f
        valueY = 0f
    }

    companion object {
        const val MAX_RADIUS = 150f
    }
}
