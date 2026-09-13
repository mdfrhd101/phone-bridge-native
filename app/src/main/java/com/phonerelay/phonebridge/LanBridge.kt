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

object LanBridge {
    private const val TAG = "LanBridge"
    const val UDP_PORT = 8889
    const val HTTP_PORT = 8888

    @Volatile
    var lastDiscoveredHostIp: String? = null
    @Volatile
    var lastDiscoveredHostTime: Long = 0L

    @Volatile
    private var isReceiverRunning = false
    private var receiverThread: Thread? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile
    private var isServerRunning = false
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null

    private val localEventsHistory = mutableListOf<NativeEvent>()
    @Volatile
    private var latestLocalTelemetry: NativeTelemetry? = null

    fun setLatestTelemetry(telemetry: NativeTelemetry) {
        latestLocalTelemetry = telemetry
    }

    fun addLocalEvent(event: NativeEvent) {
        synchronized(localEventsHistory) {
            localEventsHistory.add(0, event)
            if (localEventsHistory.size > 100) {
                localEventsHistory.removeAt(localEventsHistory.size - 1)
            }
        }
    }

    // ==========================================
    // HOST SIDE: Local HTTP Server & UDP Beacon
    // ==========================================

    fun startHostLanServices(context: Context) {
        if (isServerRunning) return
        isServerRunning = true

        serverThread = Thread {
            try {
                serverSocket = ServerSocket(HTTP_PORT).apply {
                    reuseAddress = true
                }
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
        }.apply {
            isDaemon = true
            start()
        }
    }

    fun stopHostLanServices() {
        isServerRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
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

                val (statusCode, bodyJson) = when {
                    path.startsWith("/telemetry") -> {
                        val t = latestLocalTelemetry
                        if (t != null) {
                            val json = JSONObject().apply {
                                put("battery", t.battery)
                                put("isCharging", t.isCharging)
                                put("network", t.network)
                                put("lastSeen", t.lastSeen)
                                put("deviceModel", t.deviceModel)
                                put("source", "LAN")
                            }
                            Pair("200 OK", json.toString())
                        } else {
                            Pair("404 Not Found", "{}")
                        }
                    }
                    path.startsWith("/events") -> {
                        val array = JSONArray()
                        synchronized(localEventsHistory) {
                            for (ev in localEventsHistory) {
                                val obj = JSONObject().apply {
                                    put("id", ev.id)
                                    put("type", ev.type)
                                    put("title", ev.title)
                                    put("body", ev.body)
                                    put("sender", ev.sender)
                                    put("otp", ev.otp)
                                    put("extra", ev.extra)
                                    put("timestamp", ev.timestamp)
                                    put("deviceModel", ev.deviceModel)
                                }
                                array.put(obj)
                            }
                        }
                        Pair("200 OK", array.toString())
                    }
                    else -> Pair("404 Not Found", "Not Found")
                }

                val bodyBytes = bodyJson.toByteArray(Charsets.UTF_8)
                val response = "HTTP/1.1 $statusCode\r\n" +
                        "Content-Type: application/json; charset=UTF-8\r\n" +
                        "Content-Length: ${bodyBytes.size}\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
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

    fun broadcastTelemetry(context: Context, telemetry: NativeTelemetry) {
        setLatestTelemetry(telemetry)
        Thread {
            try {
                val myIp = getLocalIpAddress() ?: ""
                val json = JSONObject().apply {
                    put("kind", "TELEMETRY")
                    put("battery", telemetry.battery)
                    put("isCharging", telemetry.isCharging)
                    put("network", telemetry.network)
                    put("lastSeen", telemetry.lastSeen)
                    put("deviceModel", telemetry.deviceModel)
                    put("hostIp", myIp)
                    put("httpPort", HTTP_PORT)
                }
                sendUdpBroadcast(json.toString())
            } catch (e: Exception) {
                Log.w(TAG, "Error broadcasting telemetry", e)
            }
        }.start()
    }

    fun broadcastEvent(context: Context, event: NativeEvent) {
        addLocalEvent(event)
        Thread {
            try {
                val myIp = getLocalIpAddress() ?: ""
                val json = JSONObject().apply {
                    put("kind", "EVENT")
                    put("id", event.id)
                    put("type", event.type)
                    put("title", event.title)
                    put("body", event.body)
                    put("sender", event.sender)
                    put("otp", event.otp)
                    put("extra", event.extra)
                    put("timestamp", event.timestamp)
                    put("deviceModel", event.deviceModel)
                    put("hostIp", myIp)
                }
                sendUdpBroadcast(json.toString())
            } catch (e: Exception) {
                Log.w(TAG, "Error broadcasting event", e)
            }
        }.start()
    }

    private fun sendUdpBroadcast(message: String) {
        val bytes = message.toByteArray(Charsets.UTF_8)
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply {
                broadcast = true
            }

            // 1. Send to 255.255.255.255
            val universalBroadcast = InetAddress.getByName("255.255.255.255")
            socket.send(DatagramPacket(bytes, bytes.size, universalBroadcast, UDP_PORT))

            // 2. Also send to specific interface subnet broadcast if detected
            val subnetBroadcast = getSubnetBroadcastAddress()
            if (subnetBroadcast != null && subnetBroadcast != universalBroadcast) {
                socket.send(DatagramPacket(bytes, bytes.size, subnetBroadcast, UDP_PORT))
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
                socket = DatagramSocket(UDP_PORT).apply {
                    broadcast = true
                    soTimeout = 4000
                }
                val buffer = ByteArray(8192)

                while (isReceiverRunning) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val msg = String(packet.data, 0, packet.length, Charsets.UTF_8)

                        val json = JSONObject(msg)
                        val senderIp = packet.address.hostAddress ?: ""
                        val kind = json.optString("kind", "")

                        if (senderIp.isNotBlank()) {
                            lastDiscoveredHostIp = json.optString("hostIp", senderIp)
                            lastDiscoveredHostTime = System.currentTimeMillis()
                        }

                        if (kind == "TELEMETRY") {
                            val battery = json.optInt("battery", -1)
                            val isCharging = json.optBoolean("isCharging", false)
                            val network = json.optString("network", "WiFi")
                            val lastSeen = json.optLong("lastSeen", System.currentTimeMillis())
                            val model = json.optString("deviceModel", "Realme Phone")

                            val telemetry = NativeTelemetry(
                                battery = battery,
                                isCharging = isCharging,
                                network = "$network (LAN 🟢)",
                                lastSeen = lastSeen,
                                deviceModel = model
                            )
                            onTelemetry(telemetry)
                        } else if (kind == "EVENT") {
                            val id = json.optString("id", System.currentTimeMillis().toString())
                            val type = json.optString("type", "EVENT")
                            val title = json.optString("title", "Alert")
                            val body = json.optString("body", "")
                            val sender = json.optString("sender", title)
                            val otp = json.optString("otp", "")
                            val extra = json.optString("extra", "")
                            val timestamp = json.optLong("timestamp", System.currentTimeMillis())
                            val model = json.optString("deviceModel", "Realme")

                            val event = NativeEvent(
                                id = id,
                                type = type,
                                title = title,
                                body = body,
                                sender = sender,
                                otp = otp,
                                extra = extra,
                                timestamp = timestamp,
                                deviceModel = model
                            )
                            onEvent(event)
                        }
                    } catch (_: Exception) {
                        // socket timeout or parsing error, continue loop
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Viewer UDP Receiver error", e)
            } finally {
                socket?.close()
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    fun stopViewerLanReceiver() {
        isReceiverRunning = false
        receiverThread?.interrupt()
        receiverThread = null
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {}
        multicastLock = null
    }

    fun queryLanTelemetry(hostIp: String, timeoutMs: Int = 1800): NativeTelemetry? {
        return try {
            val url = URL("http://$hostIp:$HTTP_PORT/telemetry")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
            }
            if (conn.responseCode == 200) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val json = JSONObject(text)
                NativeTelemetry(
                    battery = json.optInt("battery", -1),
                    isCharging = json.optBoolean("isCharging", false),
                    network = "${json.optString("network", "WiFi")} (LAN 🟢)",
                    lastSeen = json.optLong("lastSeen", System.currentTimeMillis()),
                    deviceModel = json.optString("deviceModel", "Realme Phone")
                )
            } else {
                conn.disconnect()
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun queryLanEvents(hostIp: String, timeoutMs: Int = 2000): List<NativeEvent> {
        val list = mutableListOf<NativeEvent>()
        try {
            val url = URL("http://$hostIp:$HTTP_PORT/events")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
            }
            if (conn.responseCode == 200) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val array = JSONArray(text)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        NativeEvent(
                            id = obj.optString("id", ""),
                            type = obj.optString("type", "EVENT"),
                            title = obj.optString("title", ""),
                            body = obj.optString("body", ""),
                            sender = obj.optString("sender", ""),
                            otp = obj.optString("otp", ""),
                            extra = obj.optString("extra", ""),
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                            deviceModel = obj.optString("deviceModel", "Realme")
                        )
                    )
                }
            } else {
                conn.disconnect()
            }
        } catch (_: Exception) {}
        return list
    }

    // ==========================================
    // HELPER FUNCTIONS
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
                        if (host != null && !host.startsWith("127.")) {
                            return host
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    fun getSubnetBroadcastAddress(): InetAddress? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (!intf.isUp || intf.isLoopback) continue
                for (ifAddr in intf.interfaceAddresses) {
                    val broadcast = ifAddr.broadcast
                    if (broadcast != null && broadcast is Inet4Address) {
                        return broadcast
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }
}
