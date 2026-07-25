package org.holio.game

import kotlin.math.hypot
import kotlin.random.Random

/**
 * Holds all game state and advances the simulation. Rendering lives in
 * [Renderer]; player input is fed in each frame via [inputX]/[inputY].
 *
 * The player shares the [Hole] physics with a handful of AI opponents. Every
 * hole can swallow props, and a hole that is meaningfully bigger than another
 * can swallow it too — the loser respawns small and hands over some score.
 */
class GameWorld {

    enum class State { PLAYING, GAME_OVER }

    /** Square map, in pixels. Large enough to feel open on a phone screen. */
    val worldSize = 2800f

    /** The player's hole. */
    val hole = Hole(worldSize / 2f, worldSize / 2f, 40f, "You", 0xFF80D8FF.toInt(), isPlayer = true)

    /** Every hole in play (player first, then opponents), for logic and drawing. */
    val holes = ArrayList<Hole>()
    private val opponents = ArrayList<Ai>()

    val props = ArrayList<Prop>()

    var timeLeftMs = DEFAULT_ROUND_MILLIS
        private set
    var state = State.PLAYING
        private set

    /** Round length in ms; set from Settings before [restart] to change it. */
    var roundMillis = DEFAULT_ROUND_MILLIS

    /** Joystick input in the range [-1, 1] on each axis, set by the view. */
    var inputX = 0f
    var inputY = 0f

    /** Camera top-left in world space, following the hole and clamped to bounds. */
    var camX = 0f
        private set
    var camY = 0f
        private set

    var viewportW = 0f
        private set
    var viewportH = 0f
        private set

    /** Player score, surfaced for the HUD / game-over screen. */
    val score: Int get() = hole.score

    private val respawnRng = Random(MAP_SEED xor 0x5151L)

    /** When true the round uses [generateMap]; otherwise it clones [osmTemplates]. */
    private var proceduralLevel = true
    /** Immutable prop templates for a loaded real-world level, cloned each round. */
    private var osmTemplates: List<Prop> = emptyList()

    init {
        populateProps()
        buildOpponents()
        rebuildHoleList()
    }

    fun setViewport(w: Float, h: Float) {
        viewportW = w
        viewportH = h
        updateCamera()
    }

    /** Use the original offline procedural field for the next round. */
    fun useProceduralLevel() {
        proceduralLevel = true
        osmTemplates = emptyList()
    }

    /** Use a real-world level: [templates] are cloned fresh on each round. */
    fun useOsmLevel(templates: List<Prop>) {
        proceduralLevel = false
        osmTemplates = templates
    }

    /** Start a brand-new round: same level, fresh holes, scores and timer. */
    fun restart() {
        timeLeftMs = roundMillis
        state = State.PLAYING
        inputX = 0f
        inputY = 0f
        hole.reset()
        hole.placeAt(worldSize / 2f, worldSize / 2f)
        populateProps()
        buildOpponents()
        rebuildHoleList()
        updateCamera()
    }

    /** Fill [props] from the current level (procedural map or cloned OSM data). */
    private fun populateProps() {
        if (proceduralLevel) {
            generateMap()
        } else {
            props.clear()
            for (t in osmTemplates) {
                props.add(Prop(t.x, t.y, t.radius, t.type, t.rotationDeg))
            }
        }
    }

    fun update(dtSeconds: Float) {
        if (state != State.PLAYING) return

        // Player movement from joystick input.
        val speed = hole.speed()
        hole.move(inputX * speed * dtSeconds, inputY * speed * dtSeconds, worldSize)

        updateAi(dtSeconds)
        swallowProps(dtSeconds)
        resolveHoleEating()

        // Count down the round timer.
        timeLeftMs -= (dtSeconds * 1000f).toLong()
        if (timeLeftMs <= 0L) {
            timeLeftMs = 0L
            state = State.GAME_OVER
        }

        // Auto-finish once there is nothing left on the map to swallow.
        if (state == State.PLAYING && props.none { !it.removed }) {
            state = State.GAME_OVER
        }

        updateCamera()
    }

    /** Whole seconds remaining, rounded up (so it hits 0 exactly at the end). */
    fun secondsLeft(): Int = ((timeLeftMs + 999L) / 1000L).toInt()

    /** Holes ranked by score, highest first — for the scoreboard and results. */
    fun standings(): List<Hole> = holes.sortedByDescending { it.score }

    // ---- Swallowing ----------------------------------------------------------

    private fun swallowProps(dtSeconds: Float) {
        for (prop in props) {
            if (prop.removed) continue

            if (!prop.beingSwallowed) {
                // The first hole big enough, whose mouth covers the prop's
                // centre, claims it.
                for (h in holes) {
                    if (h.radius > prop.radius && hypot(prop.x - h.x, prop.y - h.y) < h.radius) {
                        prop.swallower = h
                        prop.swallowT = 0.0001f
                        break
                    }
                }
            }

            if (prop.beingSwallowed) {
                val h = prop.swallower ?: hole
                prop.swallowT += dtSeconds / SWALLOW_SECONDS
                val t = prop.swallowT.coerceAtMost(1f)
                // Ease the prop toward its swallower while it shrinks.
                prop.drawX = prop.x + (h.x - prop.x) * t
                prop.drawY = prop.y + (h.y - prop.y) * t
                prop.drawScale = 1f - t
                if (prop.swallowT >= 1f) {
                    prop.removed = true
                    h.score += prop.points
                    h.grow(prop.area)
                }
            }
        }
    }

    /** A hole clearly bigger than another, overlapping its centre, eats it. */
    private fun resolveHoleEating() {
        if (holes.size < 2) return
        val eaten = HashSet<Hole>()
        for (a in holes) {
            if (a in eaten) continue
            for (b in holes) {
                if (a === b || b in eaten) continue
                if (a.radius <= b.radius * EAT_MARGIN) continue
                if (hypot(a.x - b.x, a.y - b.y) < a.radius - b.radius * 0.4f) {
                    val steal = (b.score * STEAL_FRACTION).toInt()
                    a.grow(b.mass * HOLE_ABSORB)
                    a.score += steal
                    respawn(b, b.score - steal)
                    eaten.add(b)
                }
            }
        }
    }

    private fun respawn(h: Hole, keepScore: Int) {
        h.resetSize()
        h.score = keepScore.coerceAtLeast(0)
        val (nx, ny) = findRespawnSpot(h)
        h.placeAt(nx, ny)
        // Make the matching AI re-pick a target from its new position.
        opponents.firstOrNull { it.hole === h }?.let { it.retargetIn = 0f }
    }

    /** Pick the sampled point that is furthest from any other (bigger) hole. */
    private fun findRespawnSpot(self: Hole): Pair<Float, Float> {
        val m = 220f
        var best = self.baseRadius + m to self.baseRadius + m
        var bestClearance = -1f
        repeat(12) {
            val x = m + respawnRng.nextFloat() * (worldSize - 2f * m)
            val y = m + respawnRng.nextFloat() * (worldSize - 2f * m)
            var nearest = Float.MAX_VALUE
            for (other in holes) {
                if (other === self) continue
                nearest = minOf(nearest, hypot(x - other.x, y - other.y))
            }
            if (nearest > bestClearance) {
                bestClearance = nearest
                best = x to y
            }
        }
        return best
    }

    // ---- AI ------------------------------------------------------------------

    private class Ai(val hole: Hole, val rng: Random) {
        var targetX = hole.x
        var targetY = hole.y
        var retargetIn = 0f
    }

    private fun updateAi(dtSeconds: Float) {
        for (ai in opponents) {
            val h = ai.hole
            ai.retargetIn -= dtSeconds
            val reached = hypot(ai.targetX - h.x, ai.targetY - h.y) < h.radius * 0.6f
            if (ai.retargetIn <= 0f || reached) chooseTarget(ai)

            val dx = ai.targetX - h.x
            val dy = ai.targetY - h.y
            val len = hypot(dx, dy)
            if (len > 1f) {
                val sp = h.speed()
                h.move(dx / len * sp * dtSeconds, dy / len * sp * dtSeconds, worldSize)
            }
        }
    }

    private fun chooseTarget(ai: Ai) {
        val h = ai.hole
        ai.retargetIn = RETARGET_SECONDS

        var bestD = Float.MAX_VALUE
        var bx = h.x
        var by = h.y
        var found = false

        // Prefer the nearest prop we can actually eat, ignoring others' prey.
        for (prop in props) {
            if (prop.removed) continue
            if (prop.beingSwallowed && prop.swallower !== h) continue
            if (h.radius <= prop.radius) continue
            val d = hypot(prop.x - h.x, prop.y - h.y)
            if (d < AI_VISION && d < bestD) {
                bestD = d; bx = prop.x; by = prop.y; found = true
            }
        }

        // Hunt a clearly smaller hole if one is closer than any prop.
        for (other in holes) {
            if (other === h || h.radius <= other.radius * EAT_MARGIN) continue
            val d = hypot(other.x - h.x, other.y - h.y)
            if (d < AI_VISION && d < bestD) {
                bestD = d; bx = other.x; by = other.y; found = true
            }
        }

        if (found) {
            ai.targetX = bx
            ai.targetY = by
        } else {
            // Wander toward a random nearby point for a while.
            val m = 200f
            ai.targetX = (h.x + (ai.rng.nextFloat() - 0.5f) * 1600f).coerceIn(m, worldSize - m)
            ai.targetY = (h.y + (ai.rng.nextFloat() - 0.5f) * 1600f).coerceIn(m, worldSize - m)
            ai.retargetIn = RETARGET_SECONDS * 3f
        }
    }

    // ---- Setup ---------------------------------------------------------------

    private fun rebuildHoleList() {
        holes.clear()
        holes.add(hole)
        for (ai in opponents) holes.add(ai.hole)
    }

    /** Fresh opponents at the map corners, each with its own colour and brain. */
    private fun buildOpponents() {
        opponents.clear()
        val m = 460f
        val far = worldSize - m
        val defs = listOf(
            Triple("Rex", 0xFFEF5350.toInt(), m to m),
            Triple("Vi", 0xFFAB47BC.toInt(), far to m),
            Triple("Gus", 0xFFFFB300.toInt(), m to far),
        )
        for ((i, def) in defs.withIndex()) {
            val (name, color, pos) = def
            val h = Hole(pos.first, pos.second, hole.baseRadius, name, color, isPlayer = false)
            opponents.add(Ai(h, Random(MAP_SEED + 101L * (i + 1))))
        }
    }

    // ---- Camera & map --------------------------------------------------------

    private fun updateCamera() {
        camX = clampCamera(hole.x - viewportW / 2f, worldSize, viewportW)
        camY = clampCamera(hole.y - viewportH / 2f, worldSize, viewportH)
    }

    private fun clampCamera(desired: Float, world: Float, viewport: Float): Float {
        if (viewport >= world) return (world - viewport) / 2f
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
                val px = gx + rng.nextFloat() * (cell * 0.7f)
                val py = gy + rng.nextFloat() * (cell * 0.7f)

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
        private const val DEFAULT_ROUND_MILLIS = 120_000L
        private const val SWALLOW_SECONDS = 0.35f
        private const val MAP_SEED = 20260725L

        /** How far an AI "sees" props/holes when choosing a target. */
        private const val AI_VISION = 1000f
        private const val RETARGET_SECONDS = 0.35f

        /** A hole must be this many times larger to swallow another hole. */
        private const val EAT_MARGIN = 1.25f
        /** Fraction of an eaten hole's area the eater absorbs. */
        private const val HOLE_ABSORB = 0.5f
        /** Fraction of an eaten hole's score the eater steals. */
        private const val STEAL_FRACTION = 0.5f
    }
}
