package com.example.simpleacscan

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.*
import java.util.concurrent.Semaphore

class MainActivity : ComponentActivity() {
    private val TAG = "ACScan"
    private lateinit var outputTv: TextView
    private val port = 57223
    private val resource = "/device.xml"
    private val timeoutMillis = 500

    // 單一掃描 scope
    private val scanScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile
    private var isScanning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        outputTv = TextView(this).apply {
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
        }
        setContentView(outputTv)

        // 一進來跑一次
        doScan()

        // 點一下畫面再掃一次（不改原本邏輯，只是再觸發）
        outputTv.setOnClickListener {
            doScan()
        }
    }

    private fun doScan() {
        if (isScanning) {
            append("已有掃描在進行，請等候。\n")
            return
        }
        isScanning = true
        scanScope.launch {
            try {
                append("=== 開始掃描 網段 at ${Date()} ===\n")
                val base = getLocalBaseIpPrefix()
                if (base == null) {
                    append("找不到可用內網 IP\n")
                    return@launch
                }
                scanNetwork(base)
                append("=== 掃描完成 ===\n")
            } finally {
                isScanning = false
            }
        }
    }

    private suspend fun scanNetwork(base: String) {
        val jobs = mutableListOf<Job>()
        val semaphore = Semaphore(50) // 限制同時掃描數

        for (i in 1..254) {
            val target = "$base.$i"
            val job = scanScope.launch {
                semaphore.acquire()
                try {
                    val url = URL("http://$target:$port$resource")
                    try {
                        with(url.openConnection() as HttpURLConnection) {
                            connectTimeout = timeoutMillis
                            readTimeout = timeoutMillis
                            requestMethod = "GET"
                            // Optional: header 可以加 if 你原本有
                            val code = responseCode
                            if (code == 200) {
                                val body = inputStream.bufferedReader().use { it.readText() }
                                append("[$target] 取得 device.xml，長度 ${body.length} bytes\n")
                                // 顯示前幾行 snippet（避免太長）
                                val lines = body.lines()
                                val snippet = lines.take(5).joinToString("\n")
                                append("  snippet:\n$snippet\n")
                            } else {
                                append("[$target] HTTP $code\n")
                            }
                        }
                    } catch (e: Exception) {
                        append("[$target] 連線失敗: ${e.message}\n")
                    }
                } finally {
                    semaphore.release()
                }
            }
            jobs += job
        }

        // 等所有完成
        jobs.joinAll()
    }

    private fun getLocalBaseIpPrefix(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue // e.g., "192.168.1.42"
                        val parts = ip.split(".")
                        if (parts.size == 4) {
                            return "${parts[0]}.${parts[1]}.${parts[2]}"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getLocalBaseIpPrefix failed", e)
        }
        return null
    }

    // UI 安全更新
    private fun append(text: String) {
        runOnUiThread {
            outputTv.append(text)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scanScope.cancel()
    }
}
