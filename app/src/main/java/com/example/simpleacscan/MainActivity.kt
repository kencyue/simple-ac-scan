package com.example.simpleacscan

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.StringReader
import java.net.*
import java.util.concurrent.Semaphore
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Node
import org.xml.sax.InputSource
import android.content.Intent
import android.net.Uri
import android.util.Log

class MainActivity : ComponentActivity() {
    private val TAG = "ACScan"
    private lateinit var outputTv: TextView
    private val port = 57223
    private val resource = "/device.xml"
    private val timeoutMillis = 1000
    private var isScanning = false
    private val scanTip = "請點擊畫面重新掃描\n"
    private val scanningTip = "正在掃描，請稍候...\n"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        outputTv = TextView(this)
        outputTv.setTextIsSelectable(true)
        outputTv.typeface = Typeface.MONOSPACE
        outputTv.textSize = 16f
        outputTv.movementMethod = LinkMovementMethod.getInstance()
        outputTv.setBackgroundColor(Color.BLACK)         // 黑色背景
        outputTv.setTextColor(Color.WHITE)               // 預設白色字
        setContentView(outputTv)

        showTipAndScan()

        outputTv.setOnClickListener {
            if (!isScanning) {
                showTipAndScan()
            }
        }
    }

    private fun showTipAndScan() {
        runOnUiThread {
            outputTv.text = scanningTip
            isScanning = true
        }
        CoroutineScope(Dispatchers.IO).launch {
            val base = getLocalBaseIpPrefix()
            if (base == null) {
                append(makeColoredSpan("找不到可用內網 IP\n", Color.RED))
                endScan()
                return@launch
            }
            append(makeColoredSpan("掃描 $base.1-254\n\n", Color.CYAN))
            val found = scanNetwork(base)
            append(makeColoredSpan("\n完成，找到 ${found.size} 台設備\n", Color.GREEN))
            endScan()
        }
    }

    private fun endScan() {
        runOnUiThread {
            isScanning = false
            if (!outputTv.text.startsWith(scanTip)) {
                outputTv.append(scanTip)
            }
        }
    }

    // 自訂：加彩色、emoji 等顯示
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
                        // Emoji: 電腦(💻) + 綠字
                        val header = makeColoredSpan("💻 $ip", Color.GREEN, bold = true)
                        val details = buildString {
                            append("\n  型號: ${info["modelName"]}\n")
                            append("  機號: ${info["modelNumber"]}\n")
                            append("  描述: ${info["modelDescription"]}\n")
                            append("  UDN: ${info["UDN"]}\n")
                            if (info.containsKey("error")) {
                                append("  ⚠️ 錯誤: ${info["error"]}\n")
                            }
                        }
                        append(header)
                        append(details)
                        append("\n\n")
                        synchronized(results) { results.add(ip) }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error scanning $ip: ${e.message}")
                } finally {
                    sem.release()
                }
            }
        }
        jobs.awaitAll()
        return results
    }

    // 彩色span+可加粗
    private fun makeColoredSpan(text: String, color: Int, bold: Boolean = false): SpannableString {
        val ss = SpannableString(text)
        ss.setSpan(ForegroundColorSpan(color), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (bold) ss.setSpan(StyleSpan(Typeface.BOLD), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return ss
    }

    private fun isPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { sock ->
                sock.soTimeout = timeoutMs
                sock.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun fetchDeviceInfo(ip: String): Map<String, String> {
        val result = mutableMapOf(
            "modelName" to "-",
            "modelNumber" to "-",
            "modelDescription" to "-",
            "UDN" to "-",
            "status" to "資料已讀取"
        )
        try {
            val url = URL("http://$ip:$port$resource")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "GET"
            conn.connect()
            val responseCode = conn.responseCode
            if (responseCode != 200) {
                result["status"] = "無法讀取設備資料"
                result["error"] = "HTTP $responseCode"
                return result
            }

            val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            if (body.isEmpty()) {
                result["status"] = "無法讀取設備資料"
                result["error"] = "Empty response"
                return result
            }

            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            val builder = factory.newDocumentBuilder()
            val doc: Document
            try {
                doc = builder.parse(InputSource(StringReader(body)))
                doc.documentElement.normalize()
            } catch (e: Exception) {
                result["status"] = "無法讀取設備資料"
                result["error"] = "XML parsing failed: ${e.message}"
                return result
            }

            val deviceNodeList = doc.getElementsByTagNameNS("urn:schemas-upnp-org:device-1-0", "device")
            if (deviceNodeList.length > 0) {
                val deviceNode = deviceNodeList.item(0)
                val deviceTags = mutableMapOf<String, String>()
                val childNodes = deviceNode.childNodes
                for (i in 0 until childNodes.length) {
                    val child = childNodes.item(i)
                    if (child.nodeType == Node.ELEMENT_NODE) {
                        val tagName = child.nodeName
                        val tagValue = child.textContent?.trim() ?: ""
                        deviceTags[tagName] = tagValue
                    }
                }
                val tags = listOf("modelName", "modelNumber", "modelDescription", "UDN")
                for (tag in tags) {
                    val value = deviceTags.entries.find { it.key.endsWith(tag) }?.value
                    if (value != null) {
                        result[tag] = value
                    }
                }
            } else {
                result["status"] = "無法讀取設備資料"
                result["error"] = "No <device> node found"
            }
        } catch (e: Exception) {
            result["status"] = "無法讀取設備資料"
            result["error"] = "Failed to fetch: ${e.message}"
        }
        return result
    }

    // 可以 append String 或 SpannableString
    private fun append(obj: CharSequence) {
        runOnUiThread {
            outputTv.append(obj)
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
        } catch (e: Exception) {
        }
        return null
    }
}
