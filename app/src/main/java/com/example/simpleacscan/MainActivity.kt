package com.example.simpleacscan

import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.Log
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

class MainActivity : ComponentActivity() {
    private val TAG = "ACScan"
    private lateinit var outputTv: TextView
    private val port = 57223
    private val resource = "/device.xml"
    private val timeoutMillis = 1000
    private var isScanning = false // 控制掃描狀態

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        outputTv = TextView(this)
        outputTv.setTextIsSelectable(true)
        outputTv.typeface = android.graphics.Typeface.MONOSPACE
        outputTv.textSize = 12f
        outputTv.movementMethod = LinkMovementMethod.getInstance() // 啟用超連結
        setContentView(outputTv)

        // 添加點擊事件以觸發重新掃描
        outputTv.setOnClickListener {
            if (!isScanning) {
                startScan()
            }
        }

        // 初始掃描
        startScan()
    }

    private fun startScan() {
        isScanning = true
        outputTv.text = "正在掃描...\n"
        CoroutineScope(Dispatchers.IO).launch {
            val base = getLocalBaseIpPrefix()
            if (base == null) {
                append("找不到可用內網 IP\n")
                isScanning = false
                return@launch
            }
            append("掃描 $base.1-254\n")
            val found = scanNetwork(base)
            append("\n完成，找到 ${found.size} 台設備\n")
            isScanning = false
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
                            append("=== ")
                            append(createHyperlink(ip, "http://$ip:$port$resource"))
                            append(" ===\n")
                            append("  modelName: ${info["modelName"]}\n")
                            append("  modelNumber: ${info["modelNumber"]}\n")
                            append("  modelDescription: ${info["modelDescription"]}\n")
                            append("  UDN: ${info["UDN"]}\n")
                            if (info.containsKey("error")) {
                                append("  Error: ${info["error"]}\n")
                            }
                        }
                        append(entry)
                        synchronized(results) { results.add(ip) }
                    } else {
                        Log.d(TAG, "Port $port is closed on $ip")
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

    private fun isPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { sock ->
                sock.soTimeout = timeoutMs
                sock.connect(InetSocketAddress(ip, port), timeoutMs)
                Log.d(TAG, "Port $port is open on $ip")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Port check failed for $ip:$port: ${e.message}")
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
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "GET"
            Log.d(TAG, "Connecting to $url")
            conn.connect()
            val responseCode = conn.responseCode
            Log.d(TAG, "Response code for $ip: $responseCode")
            
            if (responseCode != 200) {
                Log.e(TAG, "無法讀取 device.xml for $ip: HTTP $responseCode")
                result["error"] = "HTTP $responseCode"
                return result
            }

            // 讀取並記錄 XML 內容
            val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            if (body.isEmpty()) {
                Log.e(TAG, "無法讀取 device.xml for $ip: Empty response")
                result["error"] = "Empty response"
                return result
            }
            Log.d(TAG, "Response body for $ip:\n$body")
            
            // 驗證 XML 是否可解析
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            val builder = factory.newDocumentBuilder()
            val doc: Document
            try {
                doc = builder.parse(InputSource(StringReader(body)))
                doc.documentElement.normalize()
                Log.d(TAG, "XML parsed successfully for $ip")
            } catch (e: Exception) {
                Log.e(TAG, "XML parsing failed for $ip: ${e.message}")
                result["error"] = "XML parsing failed: ${e.message}"
                return result
            }

            // 查找 <device> 節點
            val deviceNodeList = doc.getElementsByTagNameNS("urn:schemas-upnp-org:device-1-0", "device")
            Log.d(TAG, "Device nodes found for $ip: ${deviceNodeList.length}")
            if (deviceNodeList.length > 0) {
                val deviceNode = deviceNodeList.item(0)
                // 記錄 <device> 節點的子標籤和值
                val deviceTags = mutableMapOf<String, String>()
                val childNodes = deviceNode.childNodes
                for (i in 0 until childNodes.length) {
                    val child = childNodes.item(i)
                    if (child.nodeType == Node.ELEMENT_NODE) {
                        val tagName = child.nodeName
                        val tagValue = child.textContent?.trim() ?: ""
                        deviceTags[tagName] = tagValue
                        Log.d(TAG, "Device tag for $ip: $tagName = $tagValue")
                    }
                }
                Log.d(TAG, "All tags in <device> node for $ip: ${deviceTags.keys.joinToString(", ")}")

                // 提取標籤值
                val tags = listOf("modelName", "modelNumber", "modelDescription", "UDN")
                for (tag in tags) {
                    val value = deviceTags.entries.find { it.key.endsWith(tag) }?.value
                    if (value != null) {
                        result[tag] = value
                        Log.d(TAG, "Found $tag in deviceTags for $ip: $value")
                    } else {
                        Log.d(TAG, "Tag $tag not found in deviceTags for $ip")
                    }
                }
                Log.d(TAG, "Parsed info for $ip: $result")
            } else {
                Log.e(TAG, "No <device> node found in XML for $ip")
                result["error"] = "No <device> node found"
            }

            // 記錄所有標籤名稱
            val allTags = mutableSetOf<String>()
            fun traverseNodes(node: Node) {
                if (node.nodeType == Node.ELEMENT_NODE) {
                    allTags.add(node.nodeName)
                    for (i in 0 until node.childNodes.length) {
                        traverseNodes(node.childNodes.item(i))
                    }
                }
            }
            traverseNodes(doc.documentElement)
            Log.d(TAG, "All tags in XML for $ip: ${allTags.joinToString(", ")}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch device info for $ip: ${e.message}")
            result["error"] = "Failed to fetch: ${e.message}"
        }
        return result
    }

    private fun append(text: String) {
        Log.i(TAG, text)
        runOnUiThread {
            val spannable = SpannableString.valueOf(text)
            outputTv.append(spannable)
        }
    }

    private fun createHyperlink(text: String, url: String): SpannableString {
        val spannable = SpannableString(text)
        spannable.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    widget.context.startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open URL $url: ${
