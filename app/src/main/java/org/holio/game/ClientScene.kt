package org.holio.game

import org.json.JSONObject

/**
 * A [Scene] populated from the host's network snapshots on a multiplayer
 * client. The client never simulates — it just mirrors what the host sends and
 * draws it. All mutation happens under this object's monitor; callers that read
 * it for rendering must synchronize on the same instance.
 */
class ClientScene : Scene {

    override var worldSize = 2800f
        private set
    override var viewportW = 0f
        private set
    override var viewportH = 0f
        private set

    override val holes = ArrayList<Hole>()
    override val props = ArrayList<Prop>()

    override var state = GameWorld.State.PLAYING
        private set

    private var myIndex = 0
    private var secs = 0
    private val placeholder = Hole(1400f, 1400f, 40f)

    override val hole: Hole
        get() = holes.getOrNull(myIndex) ?: holes.firstOrNull() ?: placeholder

    override fun secondsLeft(): Int = secs
    override fun standings(): List<Hole> = holes.sortedByDescending { it.score }

    fun setViewport(w: Float, h: Float) {
        viewportW = w
        viewportH = h
    }

    /** Build the round from the host's "start" message. */
    fun applyStart(msg: JSONObject) = synchronized(this) {
        worldSize = msg.optDouble("world", 2800.0).toFloat()
        myIndex = msg.optInt("me", 0)
        state = GameWorld.State.PLAYING
        secs = 0

        holes.clear()
        val metas = msg.getJSONArray("holes")
        for (i in 0 until metas.length()) {
            val m = metas.getJSONObject(i)
            holes.add(
                Hole(
                    0f, 0f, 40f,
                    m.optString("n", "P"),
                    m.optInt("c", 0xFFFFFFFF.toInt()),
                    isPlayer = (i == myIndex),
                )
            )
        }

        props.clear()
        val ps = msg.getJSONArray("props")
        val types = PropType.values()
        for (i in 0 until ps.length()) {
            val a = ps.getJSONArray(i)
            val type = types[a.getInt(3).coerceIn(0, types.size - 1)]
            props.add(Prop(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat(), type, a.getDouble(4).toFloat()))
        }
    }

    /** Apply a per-tick "state" message. */
    fun applyState(msg: JSONObject) = synchronized(this) {
        secs = msg.optInt("secs", secs)
        state = if (msg.optBoolean("over", false)) GameWorld.State.GAME_OVER else GameWorld.State.PLAYING

        val h = msg.getJSONArray("h")
        val n = minOf(h.length(), holes.size)
        for (i in 0 until n) {
            val a = h.getJSONArray(i)
            holes[i].setNet(
                a.getDouble(0).toFloat(),
                a.getDouble(1).toFloat(),
                a.getDouble(2).toFloat(),
                a.getInt(3),
            )
        }
        val gone = msg.optJSONArray("gone") ?: return@synchronized
        for (i in 0 until gone.length()) {
            val idx = gone.getInt(i)
            if (idx in props.indices) props[idx].removed = true
        }
    }
}
