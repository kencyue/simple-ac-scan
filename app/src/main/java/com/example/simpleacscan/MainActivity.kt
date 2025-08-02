package com.example.simpleacscan

import android.os.Bundle
import android.util.Log
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

class MainActivity : ComponentActivity() {
    private val TAG = "ACScan"
    private lateinit var outputTv: TextView
    private val port = 57223
    private val resource = "/device.xml"
    private val timeoutMillis = 1000

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
            if (responseCode == 200) {
                // 讀取並記錄原始 XML 內容
                val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                Log.d(TAG, "Response body for $ip:\n$body")
                
                // 解析 XML
                val factory = DocumentBuilderFactory.newInstance()
                factory.isNamespaceAware = true
                val builder = factory.newDocumentBuilder()
                val doc = builder.parse(InputSource(StringReader(body)))
                doc.documentElement.normalize()

                // 查找 <device> 節點
                val deviceNodeList = doc.getElementsByTagNameNS("urn:schemas-upnp-org:device-1-0", "device")
                if (deviceNodeList.length > 0) {
                    val deviceNode = deviceNodeList.item(0)
                    Log.d(TAG, "Found <device> node for $ip")

                    // 從 <device> 節點中提取標籤值
                    fun getTagValue(tag: String): String {
                        // 首先嘗試命名空間
                        var nodeList = doc.getElementsByTagNameNS("urn:schemas-upnp-org:device-1-0", tag)
                        if (nodeList.length > 0) {
                            return nodeList.item(0).textContent?.trim() ?: "-"
                        }
                        // 回退到不帶命名空間
                        nodeList = doc.getElementsByTagName(tag)
                        return if (nodeList.length > 0) {
                            nodeList.item(0).textContent?.trim() ?: "-"
                        } else {
                            "-"
                        }
                    }

                    result["modelName"] = getTagValue("modelName")
                    result["modelNumber"] = getTagValue("modelNumber")
                    result["modelDescription"] = getTagValue("modelDescription")
                    result["UDN"] = getTagValue("UDN")
                    Log.d(TAG, "Parsed info for $ip: $result")
                } else {
                    Log.e(TAG, "No <device> node found in XML for $ip")
                }

                // 記錄所有標籤名稱以供診斷
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
            } else {
                Log.e(TAG, "Invalid response code for $ip: $responseCode")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch device info for $ip: ${e.message}")
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
                                Log.d(TAG, "Found local IP prefix: ${parts[0]}.${parts[1]}.${parts[2]}")
                                return "${parts[0]}.${parts[1]}.${parts[2]}"
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local IP prefix: ${e.message}")
        }
        return null
    }
}
