package org.holio.game

import kotlin.math.hypot
import kotlin.random.Random

/**
 * Holds all game state and advances the simulation. Rendering lives in
 * [Renderer]; input is fed in each frame via [inputX]/[inputY].
 */
class GameWorld {

    enum class State { PLAYING, GAME_OVER }

    /** Square map, in pixels. Large enough to feel open on a phone screen. */
    val worldSize = 2800f

    val hole = Hole(worldSize / 2f, worldSize / 2f, 40f)
    val props = ArrayList<Prop>()

    var score = 0
        private set
    var timeLeftMs = ROUND_MILLIS
        private set
    var state = State.PLAYING
        private set

    /** Joystick input in the range [-1, 1] on each axis, set by the view. */
    var inputX = 0f
    var inputY = 0f

    /** Camera top-left in world space, following the hole and clamped to bounds. */
    var camX = 0f
        private set
    var camY = 0f
        private set

    /** Viewport size in pixels, updated when the surface size is known. */
    var viewportW = 0f
        private set
    var viewportH = 0f
        private set

    init {
        generateMap()
    }

    fun setViewport(w: Float, h: Float) {
        viewportW = w
        viewportH = h
        updateCamera()
    }

    /** Start a brand-new round: same map, fresh hole, score and timer. */
    fun restart() {
        score = 0
        timeLeftMs = ROUND_MILLIS
        state = State.PLAYING
        inputX = 0f
        inputY = 0f
        hole.reset()
        hole.placeAt(worldSize / 2f, worldSize / 2f)
        generateMap()
        updateCamera()
    }

    fun update(dtSeconds: Float) {
        if (state != State.PLAYING) return

        // Move the hole from joystick input.
        val speed = hole.speed()
        hole.move(inputX * speed * dtSeconds, inputY * speed * dtSeconds, worldSize)

        // Swallow logic + falling-in animation.
        val holeR = hole.radius
        for (prop in props) {
            if (prop.removed) continue

            if (!prop.beingSwallowed) {
                // A prop starts falling in once the hole is bigger than it and
                // the prop's centre has entered the mouth.
                if (holeR > prop.radius) {
                    val dist = hypot(prop.x - hole.x, prop.y - hole.y)
                    if (dist < holeR) {
                        prop.swallowT = 0.0001f
                    }
                }
            }

            if (prop.beingSwallowed) {
                prop.swallowT += dtSeconds / SWALLOW_SECONDS
                val t = prop.swallowT.coerceAtMost(1f)
                // Ease the prop toward the hole centre while it shrinks.
                prop.drawX = prop.x + (hole.x - prop.x) * t
                prop.drawY = prop.y + (hole.y - prop.y) * t
                prop.drawScale = 1f - t
                if (prop.swallowT >= 1f) {
                    prop.removed = true
                    score += prop.points
                    hole.grow(prop.area)
                }
            }
        }

        // Count down the round timer.
        timeLeftMs -= (dtSeconds * 1000f).toLong()
        if (timeLeftMs <= 0L) {
            timeLeftMs = 0L
            state = State.GAME_OVER
        }

        updateCamera()
    }

    /** Whole seconds remaining, rounded up (so it hits 0 exactly at the end). */
    fun secondsLeft(): Int = ((timeLeftMs + 999L) / 1000L).toInt()

    private fun updateCamera() {
        camX = clampCamera(hole.x - viewportW / 2f, worldSize, viewportW)
        camY = clampCamera(hole.y - viewportH / 2f, worldSize, viewportH)
    }

    private fun clampCamera(desired: Float, world: Float, viewport: Float): Float {
        if (viewport >= world) return (world - viewport) / 2f // center a small world
        return desired.coerceIn(0f, world - viewport)
    }

    /**
     * Deterministically scatter props over a jittered grid so the "single simple
     * map" is the same every round. The centre is kept clear for the hole.
     */
    private fun generateMap() {
        props.clear()
        val rng = Random(MAP_SEED)

        val cell = 150f
        val margin = 120f
        val center = worldSize / 2f
        val clearRadius = 260f

        var gx = margin
        while (gx < worldSize - margin) {
            var gy = margin
            while (gy < worldSize - margin) {
                // Jitter the position within the cell.
                val px = gx + rng.nextFloat() * (cell * 0.7f)
                val py = gy + rng.nextFloat() * (cell * 0.7f)

                // Keep the hole's starting area clear.
                if (hypot(px - center, py - center) > clearRadius && rng.nextFloat() < 0.78f) {
                    val type = pickType(rng)
                    val r = type.minRadius + rng.nextFloat() * (type.maxRadius - type.minRadius)
                    val rot = rng.nextFloat() * 360f
                    props.add(Prop(px, py, r, type, rot))
                }
                gy += cell
            }
            gx += cell
        }
    }

    /** Weighted pick: lots of bushes/trees, some cars, a few houses. */
    private fun pickType(rng: Random): PropType {
        val roll = rng.nextFloat()
        return when {
            roll < 0.42f -> PropType.BUSH
            roll < 0.74f -> PropType.TREE
            roll < 0.92f -> PropType.CAR
            else -> PropType.HOUSE
        }
    }

    companion object {
        private const val ROUND_MILLIS = 120_000L
        private const val SWALLOW_SECONDS = 0.35f
        private const val MAP_SEED = 20260725L
    }
}
