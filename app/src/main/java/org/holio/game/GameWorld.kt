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
class GameWorld : Scene {

    enum class State { PLAYING, GAME_OVER }

    /** A remote human's hole plus the input last received for it over the network. */
    class NetPlayer(val id: Int, val hole: Hole) {
        @Volatile var inX = 0f
        @Volatile var inY = 0f
    }

    /** Metadata needed to create a remote human's hole at match setup. */
    class RemoteInfo(val id: Int, val name: String)

    /** Square map, in pixels. Big and open — the camera zooms to keep it readable. */
    override val worldSize = 6000f

    /** The local player's hole (host or single-player). */
    override val hole = Hole(worldSize / 2f, worldSize / 2f, 40f, "You", 0xFF80D8FF.toInt(), isPlayer = true)

    /** Every hole in play (player, then remote humans, then bots). */
    override val holes = ArrayList<Hole>()
    private val opponents = ArrayList<Ai>()

    /** Remote human players (multiplayer host only). */
    val netPlayers = ArrayList<NetPlayer>()

    override val props = ArrayList<Prop>()

    var timeLeftMs = DEFAULT_ROUND_MILLIS
        private set
    override var state = State.PLAYING
        private set

    /** Largest prop radius in the current level (for the size / progress bar). */
    override var biggestPropRadius = 60f
        private set

    /** Round length in ms; set from Settings before [restart] to change it. */
    var roundMillis = DEFAULT_ROUND_MILLIS

    /** Number of AI bots to spawn (host-configurable; 3 preserves single-player). */
    var botCount = 3
    private var remoteInfos: List<RemoteInfo> = emptyList()

    /** Joystick input in the range [-1, 1] on each axis, set by the view. */
    var inputX = 0f
    var inputY = 0f

    /** Camera top-left in world space, following the hole and clamped to bounds. */
    var camX = 0f
        private set
    var camY = 0f
        private set

    override var viewportW = 0f
        private set
    override var viewportH = 0f
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
        buildContenders()
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

    /** Reset to a solo game: three bots, no remote humans. */
    fun configureSinglePlayer() {
        remoteInfos = emptyList()
        botCount = 3
    }

    /** Configure a host match: one hole per remote human, plus [bots] AI. */
    fun configureHostMatch(remotes: List<RemoteInfo>, bots: Int) {
        remoteInfos = remotes
        botCount = bots
    }

    /** Route a remote human's latest joystick input to their hole. */
    fun setRemoteInput(id: Int, x: Float, y: Float) {
        for (np in netPlayers) {
            if (np.id == id) {
                np.inX = x
                np.inY = y
                return
            }
        }
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
        buildContenders()
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
        biggestPropRadius = props.maxOfOrNull { it.radius } ?: 60f
    }

    fun update(dtSeconds: Float) {
        if (state != State.PLAYING) return

        // Player movement. Joystick input is screen-space, so map it through the
        // isometric projection: pushing "up" on screen moves the hole up on screen.
        val speed = hole.speed()
        val (mx, my) = Iso.screenToWorld(inputX, inputY)
        hole.move(mx * speed * dtSeconds, my * speed * dtSeconds, worldSize)

        // Remote human players move from their last received (screen-space) input.
        for (np in netPlayers) {
            val sp = np.hole.speed()
            val (rx, ry) = Iso.screenToWorld(np.inX, np.inY)
            np.hole.move(rx * sp * dtSeconds, ry * sp * dtSeconds, worldSize)
        }

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
    override fun secondsLeft(): Int = ((timeLeftMs + 999L) / 1000L).toInt()

    /** Holes ranked by score, highest first — for the scoreboard and results. */
    override fun standings(): List<Hole> = holes.sortedByDescending { it.score }

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
        for (np in netPlayers) holes.add(np.hole)
        for (ai in opponents) holes.add(ai.hole)
    }

    /**
     * Fresh contenders: one hole per remote human (network-driven), then
     * [botCount] AI bots, each placed at a distinct spawn point off-centre.
     */
    private fun buildContenders() {
        opponents.clear()
        netPlayers.clear()
        val spawns = spawnPoints()
        var si = 0
        var ci = 0

        for (info in remoteInfos) {
            val pos = spawns[si % spawns.size]; si++
            val color = PALETTE[ci % PALETTE.size]; ci++
            val h = Hole(pos.first, pos.second, hole.baseRadius, info.name, color, isPlayer = false)
            netPlayers.add(NetPlayer(info.id, h))
        }
        for (b in 0 until botCount) {
            val pos = spawns[si % spawns.size]; si++
            val color = PALETTE[ci % PALETTE.size]; ci++
            val name = BOT_NAMES[b % BOT_NAMES.size]
            val h = Hole(pos.first, pos.second, hole.baseRadius, name, color, isPlayer = false)
            opponents.add(Ai(h, Random(MAP_SEED + 101L * (b + 1))))
        }
    }

    /** Distinct spawn points around the map (corners then edge midpoints). */
    private fun spawnPoints(): List<Pair<Float, Float>> {
        val m = 460f
        val far = worldSize - m
        val mid = worldSize / 2f
        return listOf(
            m to m, far to far, far to m, m to far,
            mid to m, mid to far, m to mid, far to mid,
        )
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

        val cell = 190f
        val margin = 140f
        val center = worldSize / 2f
        val clearRadius = 320f

        var gx = margin
        while (gx < worldSize - margin) {
            var gy = margin
            while (gy < worldSize - margin) {
                val px = gx + rng.nextFloat() * (cell * 0.7f)
                val py = gy + rng.nextFloat() * (cell * 0.7f)

                if (hypot(px - center, py - center) > clearRadius && rng.nextFloat() < 0.78f) {
                    val type = pickType(rng)
                    var r = type.minRadius + rng.nextFloat() * (type.maxRadius - type.minRadius)
                    // Occasionally a much larger specimen for size variety.
                    if (rng.nextFloat() < 0.15f) r *= 1.4f + rng.nextFloat() * 0.6f
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
        private const val AI_VISION = 1500f
        private const val RETARGET_SECONDS = 0.35f

        /** Colours and names cycled through for remote players and bots. */
        private val PALETTE = intArrayOf(
            0xFFEF5350.toInt(), 0xFFAB47BC.toInt(), 0xFFFFB300.toInt(), 0xFF26C6DA.toInt(),
            0xFFEC407A.toInt(), 0xFF9CCC65.toInt(), 0xFFFF7043.toInt(), 0xFF5C6BC0.toInt(),
        )
        private val BOT_NAMES = arrayOf("Rex", "Vi", "Gus", "Mo", "Zoe", "Ada", "Kai", "Pip")

        /** A hole must be this many times larger to swallow another hole. */
        private const val EAT_MARGIN = 1.25f
        /** Fraction of an eaten hole's area the eater absorbs. */
        private const val HOLE_ABSORB = 0.5f
        /** Fraction of an eaten hole's score the eater steals. */
        private const val STEAL_FRACTION = 0.5f
    }
}
