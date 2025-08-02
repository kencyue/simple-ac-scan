package com.example.simpleacscan

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
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
    @Volatile
    private var isScanning = false
    private val scanScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        outputTv = TextView(this).apply {
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            text = "點一下畫面重新掃描\n"
            setOnClickListener {
                triggerScan()
            }
        }

        setContentView(outputTv)

        // 開機先掃一次
        triggerScan()
    }

    private fun triggerScan() {
        if (isScanning) {
            runOnUiThread {
                Toast.makeText(this, "掃描進行中，稍後再點", Toast.LENGTH_SHORT).show()
            }
            return
        }

        scanScope.launch {
            isScanning = true
            append("----- 開始掃描 ${Date()} -----\n")
            val base = getLocalBaseIpPrefix()
            if (base == null) {
                append("找不到可用內網 IP\n")
                isScanning = false
                return@launch
            }

            val semaphore = Semaphore(40) // 控制最大併發數
            val jobs = mutableListOf<Job>()
            for (i in 1..254) {
                val ip = "$base.$i"
                jobs += launch {
                    semaphore.acquire()
                    try {
                        val url = URL("http://$ip:$port$resource")
                        val conn = url.openConnection() as HttpURLConnection
                        conn.connectTimeout = timeoutMillis
                        conn.readTimeout = timeoutMillis
                        conn.requestMethod = "GET"
                        val code = try { conn.responseCode } catch (e: Exception) { -1 }
                        if (code == 200) {
                            val reader = BufferedReader(InputStreamReader(conn.inputStream))
                            val firstLine = reader.readLine() ?: ""
                            append("有回應：$ip → ${firstLine.trim()}\n")
                            reader.close()
                        }
                        conn.disconnect()
                    } catch (_: Exception) {
                        // silent fail
                    } finally {
                        semaphore.release()
                    }
                }
            }

            jobs.joinAll()
            append("----- 掃描結束 -----\n")
            isScanning = false
        }
    }

    private fun append(text: String) {
        runOnUiThread {
            outputTv.append(text)
        }
    }

    private fun getLocalBaseIpPrefix(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is InetAddress && !addr.isLoopbackAddress && addr.hostAddress.contains(".")) {
                        val parts = addr.hostAddress.split(".")
                        if (parts.size == 4) {
                            return "${parts[0]}.${parts[1]}.${parts[2]}"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "取得本機 IP 失敗", e)
        }
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        scanScope.cancel()
    }
}
