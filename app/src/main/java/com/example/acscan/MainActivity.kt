package com.example.acscan

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import javax.xml.parsers.DocumentBuilderFactory

class MainActivity : AppCompatActivity() {
    private lateinit var scanBtn: Button
    private lateinit var output: TextView
    private val port = 57223
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        scanBtn = findViewById(R.id.scanBtn)
        output = findViewById(R.id.outputText)
        scanBtn.setOnClickListener {
            scanBtn.isEnabled = false
            output.text = "Scanning...\n"
            scope.launch {
                val base = getLocalBaseIp() ?: run {
                    output.append("無法取得本機 IP\n")
                    scanBtn.isEnabled = true
                    return@launch
                }
                output.append("掃描網段: ${base}.1-254, 端口: $port\n")
                val results = withContext(Dispatchers.IO) {
                    scanNetwork(base, port)
                }
                if (results.isEmpty()) {
                    output.append("沒有找到開啟 $port 端口的設備。\n")
                } else {
                    for ((i, ip) in results.withIndex()) {
                        output.append("設備 ${i+1}：$ip\n")
                        val info = fetchDeviceInfo(ip, port, "/device.xml")
                        output.append("  型號名稱：${info["modelName"]}\n")
                        output.append("  型號編號：${info["modelNumber"]}\n")
                        output.append("  描述：${info["modelDescription"]}\n")
                        output.append("  UDN：${info["UDN"]}\n")
                        output.append("------------------------\n")
                    }
                }
                scanBtn.isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun getLocalBaseIp(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr.hostAddress.contains('.')) {
                        val ip = addr.hostAddress
                        val parts = ip.split('.')
                        if (parts.size == 4) {
                            return parts.take(3).joinToString('.')
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private suspend fun scanNetwork(base: String, port: Int): List<String> {
        val results = mutableListOf<String>()
        val sem = Semaphore(100)
        coroutineScope {
            val jobs = (1..254).map { i ->
                launch(Dispatchers.IO) {
                    sem.acquire()
                    try {
                        val ip = "$base.$i"
                        val socket = Socket()
                        try {
                            socket.connect(InetSocketAddress(ip, port), 300)
                            synchronized(results) { results.add(ip) }
                        } catch (_: Exception) {
                        } finally {
                            try { socket.close() } catch (_: Exception) {}
                        }
                    } finally {
                        sem.release()
                    }
                }
            }
            jobs.joinAll()
        }
        return results
    }

    private fun fetchDeviceInfo(ip: String, port: Int, resource: String): Map<String, String> {
        val url = "http://$ip:$port$resource"
        val fields = listOf("modelName", "modelNumber", "modelDescription", "UDN")
        val result = fields.associateWith { "-" }.toMutableMap()
        try {
            val conn = java.net.URL(url).openConnection()
            conn.connectTimeout = 1000
            conn.readTimeout = 1000
            val data = conn.getInputStream().readBytes()
            try {
                val factory = DocumentBuilderFactory.newInstance()
                factory.isNamespaceAware = true
                val builder = factory.newDocumentBuilder()
                val doc = builder.parse(java.io.ByteArrayInputStream(data))
                val root = doc.documentElement
                for (field in fields) {
                    val nodes = root.getElementsByTagName(field)
                    if (nodes.length > 0) {
                        val text = nodes.item(0).textContent
                        if (!text.isNullOrBlank()) result[field] = text.trim()
                    }
                }
            } catch (_: Exception) {
                val text = data.toString(Charsets.UTF_8)
                for (field in fields) {
                    Regex("<$field>(.*?)</$field>", RegexOption.DOT_MATCHES_ALL).find(text)?.let {
                        result[field] = it.groupValues[1].trim()
                    }
                }
            }
        } catch (_: Exception) {}
        return result
    }
}
