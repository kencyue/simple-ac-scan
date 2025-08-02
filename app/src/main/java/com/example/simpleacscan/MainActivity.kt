package com.example.simpleacscan

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.net.*
import java.util.concurrent.Semaphore
import java.util.regex.Pattern

class MainActivity : ComponentActivity() {
    private val TAG = "ACScan"
    private lateinit var outputTv: TextView
    private val port = 57223
    private val resource = "/device.xml"
    private val timeoutMillis = 500

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        outputTv = TextView(this)
        outputTv.setTextIsSelectable(true)
        outputTv.typeface = android.graphics.Typeface.MONOSPACE
        outputTv.textSize = 12f
        setContentView(outputTv)

        CoroutineScope(Dispatchers.IO).launch {
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

    private suspend fun scanNetwork(base: String): List<String> {
        val results = mutableListOf<String>()
        val sem = Semaphore(100)
        val jobs = (1..254).map { i ->
            val ip = "$base.$i"
            CoroutineScope(Dispatchers.IO).async {
                sem.acquire()
                try {
                    if (isPortOpen(ip, port, timeoutMillis)) {
                        val info = fetchDeviceInfo(ip)
                        val entry = buildString {
                            append("=== $ip ===\n")
                            append("  modelName: ${info["modelName"]}\n")
                            append("  modelNumber: ${info["modelNumber"]}\n")
                            append("  modelDescription: ${info["modelDescription"]}\n")
                            append("  UDN: ${info["UDN"]}\n")
                        }
                        append(entry)
                        synchronized(results) { results.add(ip) }
                    }
                } finally {
                    sem.release()
                }
            }
        }
        jobs.awaitAll()
        return results
    }

    private fun isPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { sock ->
                sock.soTimeout = timeoutMs
                sock.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
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
        try {
            val url = URL("http://$ip:$port$resource")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 800
            conn.readTimeout = 800
            conn.requestMethod = "GET"
            conn.connect()
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                fun extract(tag: String): String {
                    val p = Pattern.compile("<$tag>(.*?)</$tag>", Pattern.DOTALL)
                    val m = p.matcher(body)
                    return if (m.find()) m.group(1).trim() else "-"
                }
                result["modelName"] = extract("modelName")
                result["modelNumber"] = extract("modelNumber")
                result["modelDescription"] = extract("modelDescription")
                result["UDN"] = extract("UDN")
            }
        } catch (_: Exception) {
        }
        return result
    }

    private fun append(text: String) {
        Log.i(TAG, text)
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
                            (ip.startsWith("172.") && ip.split(".")[1].toIntOrNull() in 16..31)) {
                            val parts = ip.split(".")
                            if (parts.size >= 3) {
                                return "${parts[0]}.${parts[1]}.${parts[2]}"
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }
}
