package com.phonerelay.phonebridge

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

/**
 * Zero-latency local Wi-Fi P2P engine. All payloads (UDP beacons and HTTP responses) are
 * end-to-end encrypted with the pair-code key, so the LAN transport needs no separate auth
 * and is unreadable to other devices on the same network.
 */
object LanBridge {
    private const val TAG = "LanBridge"
    const val UDP_PORT = 8889
    const val HTTP_PORT = 8888

    @Volatile var lastDiscoveredHostIp: String? = null
    @Volatile var lastDiscoveredHostTime: Long = 0L

    @Volatile private var isReceiverRunning = false
    private var receiverThread: Thread? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile private var isServerRunning = false
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    @Volatile private var appContext: Context? = null

    private fun pairCode(): String {
        val ctx = appContext ?: return "realme-xperia"
        return BridgePreferences.getPairCode(ctx)
    }

    // ==========================================
    // HOST SIDE: Local HTTP Server & UDP Beacon
    // ==========================================

    fun startHostLanServices(context: Context) {
        appContext = context.applicationContext
        if (isServerRunning) return
        isServerRunning = true

        serverThread = Thread {
            try {
                serverSocket = ServerSocket(HTTP_PORT).apply { reuseAddress = true }
                Log.i(TAG, "Host LAN HTTP server started on port $HTTP_PORT")
                while (isServerRunning) {
                    try {
                        val client: Socket = serverSocket?.accept() ?: break
                        handleClientConnection(client)
                    } catch (e: Exception) {
                        if (!isServerRunning) break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "LAN server socket error", e)
            }
        }.apply { isDaemon = true; start() }
    }

    fun stopHostLanServices() {
        isServerRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverThread?.interrupt()
        serverThread = null
    }

    private fun handleClientConnection(client: Socket) {
        Thread {
            try {
                client.soTimeout = 3000
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), "UTF-8"))
                val requestLine = reader.readLine() ?: return@Thread
                val parts = requestLine.split(" ")
                val path = if (parts.size > 1) parts[1] else "/"

                val (statusCode, plainBody) = when {
                    path.startsWith("/telemetry") -> {
                        val ctx = appContext
                        val t = if (ctx != null) HostState.latestTelemetry else null
                        if (t != null) Pair("200 OK", EventCodec.serializeTelemetry(t))
                        else Pair("404 Not Found", "{}")
                    }
                    path.startsWith("/events") -> {
                        Pair("200 OK", hostEventsJson())
                    }
                    else -> Pair("404 Not Found", "{}")
                }

                // Encrypt the response body so only a device with the pair code can read it.
                val body = if (statusCode.startsWith("200")) Crypto.encrypt(pairCode(), plainBody) else plainBody
                val bodyBytes = body.toByteArray(Charsets.UTF_8)
                val response = "HTTP/1.1 $statusCode\r\n" +
                        "Content-Type: text/plain; charset=UTF-8\r\n" +
                        "Content-Length: ${bodyBytes.size}\r\n" +
                        "Connection: close\r\n\r\n"

                val out = client.getOutputStream()
                out.write(response.toByteArray(Charsets.UTF_8))
                out.write(bodyBytes)
                out.flush()
                client.close()
            } catch (_: Exception) {
                try { client.close() } catch (_: Exception) {}
            }
        }.start()
    }

    /** Serve the persistent host outbox (survives reboot) so a long-offline viewer can fully re-sync. */
    private fun hostEventsJson(): String {
        val ctx = appContext
        val events = if (ctx != null) EventCache.loadEvents(ctx) else HostState.recentEvents()
        val array = JSONArray()
        for (ev in events) array.put(JSONObject(EventCodec.serializeEvent(ev)))
        return array.toString()
    }

    fun broadcastTelemetry(context: Context, telemetry: NativeTelemetry) {
        appContext = context.applicationContext
        HostState.latestTelemetry = telemetry
        val cipher = Crypto.encrypt(pairCode(), EventCodec.serializeTelemetry(telemetry))
        Thread {
            try { sendUdpBroadcast(cipher) } catch (e: Exception) { Log.w(TAG, "telemetry beacon failed", e) }
        }.start()
    }

    fun broadcastEvent(context: Context, event: NativeEvent) {
        appContext = context.applicationContext
        HostState.addEvent(event)
        val cipher = Crypto.encrypt(pairCode(), EventCodec.serializeEvent(event))
        Thread {
            try { sendUdpBroadcast(cipher) } catch (e: Exception) { Log.w(TAG, "event beacon failed", e) }
        }.start()
    }

    private fun sendUdpBroadcast(message: String) {
        val bytes = message.toByteArray(Charsets.UTF_8)
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply { broadcast = true }
            val universal = InetAddress.getByName("255.255.255.255")
            socket.send(DatagramPacket(bytes, bytes.size, universal, UDP_PORT))
            val subnet = getSubnetBroadcastAddress()
            if (subnet != null && subnet != universal) {
                socket.send(DatagramPacket(bytes, bytes.size, subnet, UDP_PORT))
            }
        } catch (e: Exception) {
            Log.w(TAG, "sendUdpBroadcast failed: ${e.message}")
        } finally {
            socket?.close()
        }
    }

    // ==========================================
    // VIEWER SIDE: UDP Listener & LAN Queries
    // ==========================================

    fun startViewerLanReceiver(
        context: Context,
        onTelemetry: (NativeTelemetry) -> Unit,
        onEvent: (NativeEvent) -> Unit
    ) {
        appContext = context.applicationContext
        if (isReceiverRunning) return
        isReceiverRunning = true

        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("PhoneBridgeMulticast")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire MulticastLock: ${e.message}")
        }

        receiverThread = Thread {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(UDP_PORT).apply { broadcast = true; soTimeout = 4000 }
                val buffer = ByteArray(16384)
                while (isReceiverRunning) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val raw = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        val senderIp = packet.address?.hostAddress ?: ""

                        val plain = Crypto.decrypt(pairCode(), raw) ?: continue
                        if (!plain.startsWith("{")) continue
                        val json = JSONObject(plain)

                        if (senderIp.isNotBlank()) {
                            lastDiscoveredHostIp = senderIp
                            lastDiscoveredHostTime = System.currentTimeMillis()
                        }

                        when (EventCodec.kindOf(json)) {
                            EventCodec.KIND_TELEMETRY -> {
                                val t = EventCodec.parseTelemetry(json)
                                onTelemetry(t.copy(network = "${t.network} (LAN 🟢)"))
                            }
                            else -> onEvent(EventCodec.parseEvent(json))
                        }
                    } catch (_: Exception) {
                        // timeout / parse error → keep looping
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Viewer UDP Receiver error", e)
            } finally {
                socket?.close()
            }
        }.apply { isDaemon = true; start() }
    }

    fun stopViewerLanReceiver() {
        isReceiverRunning = false
        receiverThread?.interrupt()
        receiverThread = null
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (_: Exception) {}
        multicastLock = null
    }

    fun queryLanTelemetry(context: Context, hostIp: String, timeoutMs: Int = 1800): NativeTelemetry? {
        return try {
            val url = URL("http://$hostIp:$HTTP_PORT/telemetry")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = timeoutMs; readTimeout = timeoutMs
            }
            if (conn.responseCode == 200) {
                val cipher = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val plain = Crypto.decrypt(BridgePreferences.getPairCode(context), cipher) ?: return null
                val t = EventCodec.parseTelemetry(JSONObject(plain))
                t.copy(network = "${t.network} (LAN 🟢)")
            } else {
                conn.disconnect(); null
            }
        } catch (_: Exception) { null }
    }

    fun queryLanEvents(context: Context, hostIp: String, timeoutMs: Int = 2000): List<NativeEvent> {
        val list = mutableListOf<NativeEvent>()
        try {
            val url = URL("http://$hostIp:$HTTP_PORT/events")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = timeoutMs; readTimeout = timeoutMs
            }
            if (conn.responseCode == 200) {
                val cipher = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val plain = Crypto.decrypt(BridgePreferences.getPairCode(context), cipher)
                if (plain != null) {
                    val array = JSONArray(plain)
                    for (i in 0 until array.length()) list.add(EventCodec.parseEvent(array.getJSONObject(i)))
                }
            } else {
                conn.disconnect()
            }
        } catch (_: Exception) {}
        return list
    }

    // ==========================================
    // HELPERS
    // ==========================================

    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress
                        if (host != null && !host.startsWith("127.")) return host
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun getSubnetBroadcastAddress(): InetAddress? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (!intf.isUp || intf.isLoopback) continue
                for (ifAddr in intf.interfaceAddresses) {
                    val broadcast = ifAddr.broadcast
                    if (broadcast != null && broadcast is Inet4Address) return broadcast
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /** In-memory fallback state for the host when a Context is not available. */
    private object HostState {
        @Volatile var latestTelemetry: NativeTelemetry? = null
        private val history = mutableListOf<NativeEvent>()

        fun addEvent(event: NativeEvent) {
            synchronized(history) {
                history.add(0, event)
                if (history.size > 100) history.removeAt(history.size - 1)
            }
        }

        fun recentEvents(): List<NativeEvent> = synchronized(history) { history.toList() }
    }
}
