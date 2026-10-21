package com.example.udpmulticast

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private lateinit var etGroup: EditText
    private lateinit var etPort: EditText
    private lateinit var etTtl: EditText
    private lateinit var etInterval: EditText
    private lateinit var etMessage: EditText
    private lateinit var cbWifi: CheckBox
    private lateinit var cbListen: CheckBox
    private lateinit var btnLoop: Button
    private lateinit var tvLog: TextView

    @Volatile private var listenSocket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var listenThread: Thread? = null

    private var looping = false
    private var counter = 0

    private val loopTask = object : Runnable {
        override fun run() {
            if (!looping) return
            sendOnce(appendCounter = true)
            val interval = etInterval.text.toString().toLongOrNull()?.coerceAtLeast(50) ?: 1000L
            handler.postDelayed(this, interval)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etGroup = findViewById(R.id.etGroup)
        etPort = findViewById(R.id.etPort)
        etTtl = findViewById(R.id.etTtl)
        etInterval = findViewById(R.id.etInterval)
        etMessage = findViewById(R.id.etMessage)
        cbWifi = findViewById(R.id.cbWifi)
        cbListen = findViewById(R.id.cbListen)
        btnLoop = findViewById(R.id.btnLoop)
        tvLog = findViewById(R.id.tvLog)

        cbListen.setOnCheckedChangeListener { _, checked ->
            if (checked) startListening() else stopListening()
        }
        val uncheckOnChange = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (cbListen.isChecked) cbListen.isChecked = false
            }
        }
        etGroup.addTextChangedListener(uncheckOnChange)
        etPort.addTextChangedListener(uncheckOnChange)

        findViewById<Button>(R.id.btnSend).setOnClickListener { sendOnce(appendCounter = false) }

        btnLoop.setOnClickListener {
            if (looping) {
                stopLoop()
            } else {
                looping = true
                counter = 0
                btnLoop.text = "停止循环发送"
                handler.post(loopTask)
            }
        }
    }

    override fun onDestroy() {
        stopLoop()
        stopListening()
        executor.shutdown()
        super.onDestroy()
    }

    private fun startListening() {
        val groupStr = etGroup.text.toString().trim()
        val port = etPort.text.toString().toIntOrNull()
        val useWifi = cbWifi.isChecked
        if (port == null || port !in 1..65535) {
            log("端口无效")
            cbListen.isChecked = false
            return
        }
        val group = try {
            InetAddress.getByName(groupStr).also { require(it.isMulticastAddress) }
        } catch (e: Exception) {
            log("监听失败: $groupStr 不是有效的组播地址")
            cbListen.isChecked = false
            return
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wm?.createMulticastLock("udpmulticast")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        val t = Thread {
            var socket: MulticastSocket? = null
            try {
                socket = MulticastSocket(port)
                if (useWifi) {
                    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    @Suppress("DEPRECATION")
                    val wifi = cm.allNetworks.firstOrNull { n ->
                        cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                    } ?: throw IllegalStateException("未找到 Wi-Fi 网络")
                    wifi.bindSocket(socket)
                }
                @Suppress("DEPRECATION")
                socket.joinGroup(group)
                socket.soTimeout = 500
                listenSocket = socket
                log("开始监听 $groupStr:$port")
                val buf = ByteArray(65535)
                while (listenSocket === socket) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    val msg = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    log("已接收 <- ${packet.address.hostAddress}:${packet.port} ($groupStr:$port): $msg")
                }
            } catch (e: Exception) {
                if (listenSocket === socket || socket == null || listenSocket == null) {
                    log("监听失败: ${e.javaClass.simpleName}: ${e.message}")
                    runOnUiThread { cbListen.isChecked = false }
                }
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }
        listenThread = t
        t.start()
    }

    private fun stopListening() {
        val s = listenSocket
        listenSocket = null
        if (s != null) {
            try { s.close() } catch (_: Exception) {}
            log("已停止监听")
        }
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
        listenThread = null
    }

    private fun stopLoop() {
        looping = false
        handler.removeCallbacks(loopTask)
        if (::btnLoop.isInitialized) btnLoop.text = "开始循环发送"
    }

    private fun sendOnce(appendCounter: Boolean) {
        val group = etGroup.text.toString().trim()
        val port = etPort.text.toString().toIntOrNull()
        val ttl = etTtl.text.toString().toIntOrNull() ?: 1
        var message = etMessage.text.toString()
        val useWifi = cbWifi.isChecked

        if (port == null || port !in 1..65535) {
            log("端口无效")
            return
        }
        if (appendCounter) {
            counter++
            message = "$message #$counter"
        }
        val finalMessage = message

        // 网络操作必须放到子线程
        executor.execute {
            try {
                sendMulticast(applicationContext, group, port, ttl, finalMessage, useWifi)
                log("已发送 -> $group:$port (TTL=$ttl): $finalMessage")
            } catch (e: Exception) {
                log("发送失败: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun log(line: String) {
        runOnUiThread {
            val text = "[${timeFmt.format(Date())}] $line\n" + tvLog.text
            // 只保留最近 3000 个字符
            tvLog.text = if (text.length > 3000) text.substring(0, 3000) else text
        }
    }

    companion object {
        /**
         * 发送一个 UDP 组播包(同步,需在子线程调用)。
         * 发送组播不需要 MulticastLock(那是接收用的),只需要 INTERNET 权限。
         */
        fun sendMulticast(
            context: Context,
            groupAddress: String,
            port: Int,
            ttl: Int,
            message: String,
            bindToWifi: Boolean
        ) {
            val group = InetAddress.getByName(groupAddress)
            require(group.isMulticastAddress) { "$groupAddress 不是组播地址(224.0.0.0 ~ 239.255.255.255)" }

            val data = message.toByteArray(Charsets.UTF_8)

            MulticastSocket().use { socket ->
                socket.timeToLive = ttl          // 1 = 仅本局域网
                // socket.loopbackMode = true     // 如需禁止本机回环可打开(语义在不同系统上不一致)

                if (bindToWifi) {
                    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    @Suppress("DEPRECATION")
                    val wifi = cm.allNetworks.firstOrNull { n ->
                        cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                    }
                    if (wifi != null) wifi.bindSocket(socket) else throw IllegalStateException("未找到 Wi-Fi 网络")
                }

                socket.send(DatagramPacket(data, data.size, group, port))
            }
        }
    }
}
