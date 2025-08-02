package com.example.acscan

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import org.w3c.dom.Document
import java.io.BufferedReader
import java.net.*
import java.util.concurrent.Semaphore
import java.util.regex.Pattern
import javax.net.ssl.HttpsURLConnection
import javax.xml.parsers.DocumentBuilderFactory

class MainActivity : AppCompatActivity() {
    private lateinit var resultContainer: LinearLayout
    private lateinit var scanButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private val port = 57223
    private val resourcePath = "/device.xml"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        resultContainer = findViewById(R.id.result_container)
        scanButton = findViewById(R.id.scan_button)
        progressBar = findViewById(R.id.progress_bar)
        statusText = findViewById(R.id.status_text)

        scanButton.setOnClickListener {
            startScan()
        }

        startScan()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun startScan() {
        resultContainer.removeAllViews()
        progressBar.progress = 0
        statusText.text = "正在取得本地 IP..."
        scanButton.isEnabled = false
        scope.launch {
            val baseIp = withContext(Dispatchers.IO) { getLocalBaseIp() }
            if (baseIp == null) {
                statusText.text = "無法取得本地網路 IP"
                scanButton.isEnabled = true
                return@launch
            }
            statusText.text = "掃描 $baseIp.1-254 第 $port 端口..."
            val devices = scanNetwork(baseIp, port)
            if (devices.isEmpty()) {
                statusText.text = "沒有找到開啟該端口的設備。"
                scanButton.isEnabled = true
                return@launch
            }
            statusText.text = "找到 ${devices.size} 台設備，正在抓取資訊..."
            var idx = 1
            for (ip in devices) {
                val info = withContext(Dispatchers.IO) { fetchDeviceInfo(ip, port, resourcePath) }
                addResultView(idx, ip, info)
                idx++
            }
            statusText.text = "完成"
            scanButton.isEnabled = true
        }
    }

    private fun addResultView(idx: Int, ip: String, info: Map<String, String>) {
        val tv = TextView(this)
        tv.text = buildString {
            append("設備 $idx：\n")
            append("  IP：$ip\n")
            append("  型號名稱：${info["modelName"]}\n")
            append("  型號編號：${info["modelNumber"]}\n")
            append("  描述：${info["modelDescription"]}\n")
            append("  UDN：${info["UDN"]}\n")
            append("----------------------------------------\n")
        }
        resultContainer.addView(tv)
    }

    private fun updateProgress(done: Int, total: Int) {
        val percent = (done * 100 / total).coerceAtMost(100)
        progressBar.progress = percent
        statusText.text = "掃描中: $done/$total"
    }

    private fun getLocalBaseIp(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress
                        val parts = ip.split(".")
                        if (parts.size == 4) {
                            return parts.subList(0, 3).joinToString(".")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private suspend fun scanNetwork(baseIp: String, port: Int): List<String> {
        val results = mutableListOf<String>()
        val total = 254
        var done = 0
        val semaphore = Semaphore(100)
        val deferred = (1..254).map { i ->
            scope.async(Dispatchers.IO) {
                semaphore.acquire()
                try {
                    val ip = "$baseIp.$i"
                    val open = try {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress(ip, port), 500)
                            true
                        }
                    } catch (e: Exception) {
                        false
                    }
                    synchronized(results) {
                        done++
                        withContext(Dispatchers.Main) { updateProgress(done, total) }
                    }
                    if (open) ip else null
                } finally {
                    semaphore.release()
                }
            }
        }
        deferred.awaitAll().filterNotNull().let { results.addAll(it) }
        return results
    }

    private fun fetchDeviceInfo(ip: String, port: Int, resource: String): Map<String, String> {
        val fields = listOf("modelName", "modelNumber", "modelDescription", "UDN")
        val result = mutableMapOf<String, String>()
        for (f in fields) result[f] = "-"
        try {
            val url = URL("http://$ip:$port$resource")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 1000
            conn.readTimeout = 1000
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = true
            conn.connect()
            val code = conn.responseCode
            if (code in 200..299) {
                val text = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                try {
                    val dbf = DocumentBuilderFactory.newInstance()
                    val db = dbf.newDocumentBuilder()
                    val doc: Document = db.parse(text.byteInputStream())
                    doc.documentElement.normalize()
                    for (field in fields) {
                        val nodes = doc.getElementsByTagName(field)
                        if (nodes.length > 0) {
                            val value = nodes.item(0).textContent.trim()
                            if (value.isNotEmpty()) result[field] = value
                        }
                    }
                } catch (ex: Exception) {
                    for (field in fields) {
                        val pattern = Pattern.compile("<$field>(.*?)</$field>", Pattern.DOTALL)
                        val matcher = pattern.matcher(text)
                        if (matcher.find()) {
                            result[field] = matcher.group(1).trim()
                        }
                    }
                }
            }
            conn.disconnect()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }
}
