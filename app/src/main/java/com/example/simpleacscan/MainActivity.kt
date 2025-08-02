package com.example.simpleacscan

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedReader
import java.net.*
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : ComponentActivity() {
    private val TAG = "ACScan"
    private lateinit var outputTv: TextView
    private val port = 57223
    private val resource = "/device.xml"
    // 調高一點避免太多裝置因慢而 timeout 掉
    private val connectTimeoutMs = 1500
    private val readTimeoutMs = 1500
    private val maxConcurrency = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        outputTv = TextView(this).apply {
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
        }
        setContentView(outputTv)

        // 注意：要用這個需在 build.gradle 加上 lifecycle-runtime-ktx
        lifecycleScope.launch(Dispatchers.IO) {
            val base = getLocalBaseIpPrefix()
            if (base == null) {
                append("找不到可用內網 IP\n")
                return@launch
            }
            append("掃描 $base.1-254，port $port\n")
            val found = scanNetwork(base)
            append("\n完成，找到 ${found.size} 台設備\n")
        }
    }

    private suspend fun scanNetwork(base: String): List<String> = coroutineScope {
        val results = mutableListOf<String>()
        val resultsMutex = Mutex()
        val sem = Semaphore(maxConcurrency)
        val total = 254
        val doneCount = AtomicInteger(0)
        val foundCount = AtomicInteger(0)

        val jobs = (1..254).map { i ->
            val ip = "$base.$i"
            launch {
                sem.withPermit {
                    if (!isActive) return@withPermit
                    try {
                        if (isPortOpen(ip, port, connectTimeoutMs)) {
                            val info = fetchDeviceInfo(ip)
                            val entry = buildString {
                                append("=== $ip ===\n")
                                append("  modelName: ${info["modelName"]}\n")
                                append("  modelNumber: ${info["modelNumber"]}\n")
                                append("  modelDescription: ${info["modelDescription"]}\n")
                                append("  UDN: ${info["UDN"]}\n")
                            }
                            append(entry)
                            resultsMutex.withLock { results.add(ip) }
                            foundCount.incrementAndGet()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "掃描 $ip 時例外", e)
                    } finally {
                        val done = doneCount.incrementAndGet()
                        // 簡單進度更新，每 10 個更新一次避免太頻繁
                        if (done % 10 == 0 || done == total) {
                            append("進度：$done/$total，已找到 ${foundCount.get()} 台\n")
                        }
                    }
                }
            }
        }
        jobs.joinAll()
        return@coroutineScope results
    }

    private fun isPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { sock ->
                sock.soTimeout = timeoutMs
                sock.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (e: Exception) {
            // 可以選擇不 log 太多，避免噪音，但開發時可打開
            Log.d(TAG, "port $port 在 $ip 無回應: ${e.message}")
            false
        }
    }

    private fun fetchDeviceInfo(ip: String): Map<String, String> {
        val result = mutableMapOf(
            "modelName" to "-",
            "modelNumber" to "-",
            "modelDescription" to "-",
            "UDN" to "-"
        )
        var conn: HttpURLConnection? = null
        try {
            val url = URL("http://$ip:$port$resource")
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                requestMethod = "GET"
            }
            conn.connect()
            val code = conn.responseCode
            if (code == 200) {
                // 讀全文做 log 與 parser 用
                val raw = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                Log.i(TAG, "device.xml from $ip: $raw")

                // 用 XmlPullParser 解析（不靠 regex）
                val parser: XmlPullParser = Xml.newPullParser()
                parser.setInput(raw.reader())
                var eventType = parser.eventType
                var currentTag: String? = null
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    when (eventType) {
                        XmlPullParser.START_TAG -> currentTag = parser.name
                        XmlPullParser.TEXT -> {
                            val text = parser.text.trim()
                            if (!text.isNullOrEmpty() && currentTag != null) {
                                when (currentTag.lowercase()) {
                                    "modelname" -> result["modelName"] = text
                                    "modelnumber" -> result["modelNumber"] = text
                                    "modeldescription" -> result["modelDescription"] = text
                                    "udn" -> result["UDN"] = text
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> currentTag = null
                    }
                    eventType = parser.next()
                }
            } else {
                Log.w(TAG, "fetchDeviceInfo $ip 回應碼: $code")
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchDeviceInfo for $ip 失敗", e)
        } finally {
            conn?.disconnect()
        }
        return result
    }

    private fun append(text: String) {
        Log.i(TAG, text.trimEnd())
        runOnUiThread {
            outputTv.append(text)
        }
    }

    private fun getLocalBaseIpPrefix(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress
                        if (ip.startsWith("10.") || ip.startsWith("192.168.") ||
                            (ip.startsWith("172.") && ip.split(".")[1].toIntOrNull() in 16..31)
                        ) {
                            val parts = ip.split(".")
                            if (parts.size >= 3) {
                                return "${parts[0]}.${parts[1]}.${parts[2]}"
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "取得本地 IP 前綴失敗", e)
        }
        return null
    }
}
