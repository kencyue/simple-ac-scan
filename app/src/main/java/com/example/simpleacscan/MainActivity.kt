package com.example.acscan

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.w3c.dom.Document
import java.io.StringReader
import java.net.InetSocketAddress
import java.net.Socket
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

data class DeviceInfo(
    val modelName: String,
    val modelNumber: String,
    val modelDescription: String,
    val UDN: String,
    val raw: String,
    val error: String?
)

class MainActivity : AppCompatActivity() {

    private lateinit var outputText: TextView
    private val port = 57223

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        outputText = findViewById(R.id.outputText)
        val scanBtn = findViewById<Button>(R.id.scanBtn)
        val rescanBtn = findViewById<Button>(R.id.rescanBtn)

        val baseIp = getLocalBaseIp()

        scanBtn.setOnClickListener {
            startScan(baseIp, port)
        }
        rescanBtn.setOnClickListener {
            startScan(baseIp, port)
        }

        // 初次自動掃一次（可選）
        startScan(baseIp, port)
    }

    private fun startScan(baseIp: String, port: Int) {
        outputText.text = "掃描 $baseIp.1-254，port $port...\n"
        lifecycleScope.launch(Dispatchers.IO) {
            val liveResults = StringBuilder()
            val devices = scanNetwork(baseIp, port)
            if (devices.isEmpty()) {
                liveResults.append("沒有找到開啟該端口的設備。\n")
            } else {
                for (ip in devices) {
                    val info = fetchDeviceInfo(ip, port, "/device.xml")
                    liveResults.append("=== $ip ===\n")
                    liveResults.append("modelName: ${info.modelName}\n")
                    liveResults.append("modelNumber: ${info.modelNumber}\n")
                    liveResults.append("modelDescription: ${info.modelDescription}\n")
                    liveResults.append("UDN: ${info.UDN}\n")
                    if (info.error != null) {
                        liveResults.append("error: ${info.error}\n")
                    }
                    liveResults.append("\n")
                    withContext(Dispatchers.Main) {
                        outputText.text = liveResults.toString()
                    }
                }
            }
            withContext(Dispatchers.Main) {
                outputText.text = liveResults.toString()
            }
        }
    }

    private suspend fun scanNetwork(baseIp: String, port: Int): List<String> = coroutineScope {
        val result = mutableListOf<String>()
        val semaphore = Semaphore(50) // 限制併發不爆掉
        val jobs = (1..254).map { i ->
            launch(Dispatchers.IO) {
                semaphore.withPermit {
                    val ip = "$baseIp.$i"
                    try {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress(ip, port), 200) // 200ms timeout
                            synchronized(result) { result.add(ip) }
                        }
                    } catch (_: Exception) {
                        // 連不到就略過
                    }
                }
            }
        }
        jobs.joinAll()
        result.sorted()
    }

    private suspend fun fetchDeviceInfo(ip: String, port: Int, resource: String): DeviceInfo {
        val url = "http://$ip:$port$resource"
        val fields = listOf("modelName", "modelNumber", "modelDescription", "UDN")
        val result = mutableMapOf<String, String>().withDefault { "-" }
        var raw = ""
        var error: String? = null

        try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 1000
            conn.readTimeout = 1000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "ACScan/1.0")
            val code = conn.responseCode
            raw = conn.inputStream.bufferedReader().use { it.readText() }

            try {
                val factory = DocumentBuilderFactory.newInstance()
                factory.isNamespaceAware = true
                val builder = factory.newDocumentBuilder()
                val doc: Document = builder.parse(InputSource(StringReader(raw)))
                for (field in fields) {
                    val nodes = doc.getElementsByTagName(field)
                    if (nodes.length > 0) {
                        val text = nodes.item(0).textContent.trim()
                        result[field] = text
                    }
                }
            } catch (e: Exception) {
                // fallback 用 regex
                for (field in fields) {
                    val regex = Regex("<$field>(.*?)</$field>", RegexOption.DOT_MATCHES_ALL)
                    regex.find(raw)?.groups?.get(1)?.let {
                        result[field] = it.value.trim()
                    }
                }
            }
        } catch (e: Exception) {
            error = e.toString()
        }

        return DeviceInfo(
            result.getValue("modelName"),
            result.getValue("modelNumber"),
            result.getValue("modelDescription"),
            result.getValue("UDN"),
            raw,
            error
        )
    }

    private fun getLocalBaseIp(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress // e.g., "192.168.31.56"
                        val parts = ip.split(".")
                        if (parts.size == 4) {
                            return "${parts[0]}.${parts[1]}.${parts[2]}"
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return "192.168.1" // 預設 fallback
    }
}
