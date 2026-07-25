package org.holio.game

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Multiplayer host. Runs a TCP server on [PORT], advertises it on the LAN via
 * NSD (mDNS), accepts client connections, routes their input into the
 * authoritative [world], and broadcasts world snapshots ~20×/second.
 *
 * All socket writes happen on background threads (never the UI thread).
 */
class GameServer(
    private val context: Context,
    private val world: GameWorld,
    /** Called (on a background thread) whenever the joined-player list changes. */
    private val onLobbyChanged: (List<String>) -> Unit,
) {

    private inner class ClientConn(val id: Int, val socket: Socket) {
        private val out = socket.getOutputStream().bufferedWriter()

        @Volatile var name = "Player"
        @Volatile var holeIndex = -1

        fun send(line: String) {
            try {
                synchronized(out) {
                    out.write(line)
                    out.write("\n")
                    out.flush()
                }
            } catch (ignored: Exception) {
            }
        }

        fun close() = try { socket.close() } catch (ignored: Exception) {}
    }

    private val clients = CopyOnWriteArrayList<ClientConn>()
    private var serverSocket: ServerSocket? = null

    @Volatile private var running = false
    @Volatile private var started = false
    private var nextId = 1

    private var nsd: NsdManager? = null
    private var regListener: NsdManager.RegistrationListener? = null

    private var reported = BooleanArray(0)

    /** Bind the socket + start accepting and advertising. Returns success. */
    fun start(): Boolean {
        return try {
            val ss = ServerSocket(PORT)
            serverSocket = ss
            running = true
            Thread { acceptLoop(ss) }.start()
            registerNsd()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            try {
                val sock = ss.accept()
                if (started) { try { sock.close() } catch (ignored: Exception) {}; continue }
                val conn = ClientConn(nextId++, sock)
                clients.add(conn)
                Thread { readLoop(conn) }.start()
                onLobbyChanged(connectedNames())
            } catch (e: Exception) {
                if (!running) break
            }
        }
    }

    private fun readLoop(conn: ClientConn) {
        try {
            val reader = conn.socket.getInputStream().bufferedReader()
            while (running) {
                val line = reader.readLine() ?: break
                handle(conn, line)
            }
        } catch (ignored: Exception) {
        } finally {
            clients.remove(conn)
            conn.close()
            // Stop the abandoned hole from drifting on the host's last input.
            world.setRemoteInput(conn.id, 0f, 0f)
            onLobbyChanged(connectedNames())
        }
    }

    private fun handle(conn: ClientConn, line: String) {
        val o = try { JSONObject(line) } catch (e: Exception) { return }
        when (o.optString("t")) {
            "join" -> {
                conn.name = o.optString("name", "Player").take(10)
                onLobbyChanged(connectedNames())
            }
            "input" -> world.setRemoteInput(
                conn.id, o.optDouble("x", 0.0).toFloat(), o.optDouble("y", 0.0).toFloat()
            )
        }
    }

    fun connectedNames(): List<String> = clients.map { it.name }

    /**
     * Lock in the roster, build the world, and start streaming. Called on the
     * UI thread; the actual network sends happen on the broadcast thread.
     */
    fun beginMatch(bots: Int, roundMillis: Long, hostName: String) {
        val roster = clients.toList()
        val remotes = roster.map { GameWorld.RemoteInfo(it.id, it.name) }
        world.configureHostMatch(remotes, bots)
        world.roundMillis = roundMillis
        world.restart()
        reported = BooleanArray(world.props.size)
        roster.forEachIndexed { k, c -> c.holeIndex = 1 + k }
        started = true
        Thread { broadcastLoop(hostName, roster) }.start()
    }

    private fun broadcastLoop(hostName: String, roster: List<ClientConn>) {
        // First, hand each client the level + its own hole index.
        val start = buildStart(hostName)
        for (c in roster) {
            start.put("me", c.holeIndex)
            c.send(start.toString())
        }
        // Then stream state until torn down.
        while (running && started) {
            val line = buildState().toString()
            for (c in clients) c.send(line)
            try { Thread.sleep(BROADCAST_MS) } catch (e: InterruptedException) { break }
        }
    }

    private fun buildStart(hostName: String): JSONObject {
        val o = JSONObject()
        o.put("t", "start")
        o.put("world", world.worldSize.toDouble())
        val hs = JSONArray()
        world.holes.forEachIndexed { i, h ->
            hs.put(JSONObject().put("n", if (i == 0) hostName else h.name).put("c", h.rimColor))
        }
        o.put("holes", hs)
        val ps = JSONArray()
        for (p in world.props) {
            val a = JSONArray()
            a.put(p.x.toDouble()); a.put(p.y.toDouble()); a.put(p.radius.toDouble())
            a.put(p.type.ordinal); a.put(p.rotationDeg.toDouble())
            ps.put(a)
        }
        o.put("props", ps)
        return o
    }

    private fun buildState(): JSONObject {
        val o = JSONObject()
        o.put("t", "state")
        o.put("secs", world.secondsLeft())
        o.put("over", world.state == GameWorld.State.GAME_OVER)
        val h = JSONArray()
        for (hole in world.holes) {
            val a = JSONArray()
            a.put(hole.x.toDouble()); a.put(hole.y.toDouble())
            a.put(hole.radius.toDouble()); a.put(hole.score)
            h.put(a)
        }
        o.put("h", h)
        // Report newly-removed props since the last snapshot (TCP keeps order).
        val gone = JSONArray()
        val props = world.props
        val n = minOf(reported.size, props.size)
        for (i in 0 until n) {
            if (props[i].removed && !reported[i]) {
                reported[i] = true
                gone.put(i)
            }
        }
        o.put("gone", gone)
        return o
    }

    fun stop() {
        running = false
        started = false
        unregisterNsd()
        for (c in clients) c.close()
        clients.clear()
        try { serverSocket?.close() } catch (ignored: Exception) {}
        serverSocket = null
    }

    // ---- NSD advertising -----------------------------------------------------

    private fun registerNsd() {
        try {
            val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
            val info = NsdServiceInfo().apply {
                serviceName = "Holio"
                serviceType = SERVICE_TYPE
                port = PORT
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(s: NsdServiceInfo) {}
                override fun onRegistrationFailed(s: NsdServiceInfo, err: Int) {}
                override fun onServiceUnregistered(s: NsdServiceInfo) {}
                override fun onUnregistrationFailed(s: NsdServiceInfo, err: Int) {}
            }
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            nsd = manager
            regListener = listener
        } catch (ignored: Exception) {
            // Advertising is best-effort; players can still join by IP.
        }
    }

    private fun unregisterNsd() {
        try {
            regListener?.let { nsd?.unregisterService(it) }
        } catch (ignored: Exception) {
        }
        regListener = null
        nsd = null
    }

    companion object {
        const val PORT = 47624
        const val SERVICE_TYPE = "_holio._tcp."
        private const val BROADCAST_MS = 50L

        /** Best-effort LAN IPv4 address to show for manual joins. */
        fun localIp(): String {
            try {
                for (nif in NetworkInterface.getNetworkInterfaces()) {
                    if (!nif.isUp || nif.isLoopback) continue
                    for (addr in nif.inetAddresses) {
                        if (addr is Inet4Address && addr.isSiteLocalAddress) {
                            return addr.hostAddress ?: continue
                        }
                    }
                }
            } catch (ignored: Exception) {
            }
            return "?"
        }
    }
}
