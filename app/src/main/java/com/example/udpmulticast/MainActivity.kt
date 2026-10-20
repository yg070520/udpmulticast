package com.example.udpmulticast

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
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
    private lateinit var btnLoop: Button
    private lateinit var tvLog: TextView

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
        btnLoop = findViewById(R.id.btnLoop)
        tvLog = findViewById(R.id.tvLog)

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
        executor.shutdown()
        super.onDestroy()
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
