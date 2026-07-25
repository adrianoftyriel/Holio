package org.holio.game

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue

/**
 * Multiplayer client. Discovers hosts via NSD (mDNS) or connects to a typed-in
 * IP, then mirrors the host's snapshots into [scene] and streams the local
 * joystick input back. All socket I/O runs on background threads; callbacks are
 * invoked on background threads, so the caller must hop to the UI thread.
 */
class GameClient(private val context: Context) {

    data class Found(val name: String, val host: String, val port: Int)

    /** The scene the host drives; render it (synchronized on it) as the client. */
    val scene = ClientScene()

    @Volatile private var running = false
    private var socket: Socket? = null
    private val outbox = LinkedBlockingQueue<String>()

    private var nsd: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    // ---- Connecting ----------------------------------------------------------

    fun connect(
        host: String,
        port: Int,
        onStarted: () -> Unit,
        onError: (String) -> Unit,
        onDisconnected: () -> Unit,
    ) {
        Thread {
            try {
                val sock = Socket()
                sock.connect(InetSocketAddress(host, port), 8000)
                socket = sock
                running = true

                val writer = sock.getOutputStream().bufferedWriter()
                Thread {
                    try {
                        while (running) {
                            val line = outbox.take()
                            writer.write(line); writer.write("\n"); writer.flush()
                        }
                    } catch (ignored: Exception) {
                    }
                }.start()

                send(JSONObject().put("t", "join").put("name", playerName()).toString())

                val reader = sock.getInputStream().bufferedReader()
                while (running) {
                    val line = reader.readLine() ?: break
                    val o = try { JSONObject(line) } catch (e: Exception) { continue }
                    when (o.optString("t")) {
                        "start" -> { scene.applyStart(o); onStarted() }
                        "state" -> scene.applyState(o)
                    }
                }
            } catch (e: Exception) {
                onError(e.message ?: "connection failed")
            } finally {
                stop()
                onDisconnected()
            }
        }.start()
    }

    /** Enqueue the latest joystick input (safe to call from the UI thread). */
    fun sendInput(x: Float, y: Float) {
        if (running) send(JSONObject().put("t", "input").put("x", x.toDouble()).put("y", y.toDouble()).toString())
    }

    private fun send(line: String) {
        outbox.offer(line)
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (ignored: Exception) {}
        socket = null
    }

    // ---- Discovery -----------------------------------------------------------

    fun startDiscovery(onFound: (Found) -> Unit) {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("holio-discovery").apply {
                setReferenceCounted(true)
                acquire()
            }
            val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onServiceLost(service: NsdServiceInfo) {}
                override fun onServiceFound(service: NsdServiceInfo) {
                    if (service.serviceType.contains("holio")) {
                        @Suppress("DEPRECATION")
                        manager.resolveService(service, resolveListener(onFound))
                    }
                }
            }
            manager.discoverServices(GameServer.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            nsd = manager
            discoveryListener = listener
        } catch (ignored: Exception) {
            // Discovery is best-effort; the manual-IP path still works.
        }
    }

    private fun resolveListener(onFound: (Found) -> Unit) = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            val addr = serviceInfo.host?.hostAddress ?: return
            onFound(Found(serviceInfo.serviceName ?: "Holio host", addr, serviceInfo.port))
        }
    }

    fun stopDiscovery() {
        try { discoveryListener?.let { nsd?.stopServiceDiscovery(it) } } catch (ignored: Exception) {}
        try { multicastLock?.release() } catch (ignored: Exception) {}
        discoveryListener = null
        nsd = null
        multicastLock = null
    }

    private fun playerName(): String = Build.MODEL?.take(10) ?: "Player"
}
