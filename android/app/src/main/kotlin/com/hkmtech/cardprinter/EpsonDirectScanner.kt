package com.hkmtech.cardprinter

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Locale

object EpsonDirectScanner {

    private const val TAG = "HKM_DIRECT_SCAN"

    @Volatile
    private var isAbortRequested = false

    @Volatile
    private var activeJobUrl: String? = null

    fun abortCurrentScan() {
        isAbortRequested = true
        val jobUrl = activeJobUrl
        if (!jobUrl.isNullOrBlank()) {
            Thread {
                try {
                    val conn = (URL(jobUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "DELETE"
                        connectTimeout = 2000
                        readTimeout = 2000
                        setRequestProperty("User-Agent", "HKMTECH-CardPrinter")
                    }
                    conn.responseCode
                    conn.disconnect()
                    Log.i(TAG, "Sent DELETE to cancel eSCL job: $jobUrl")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send DELETE for abort: ${e.message}")
                }
            }.start()
        }
    }

    fun isPortReachable(host: String, port: Int, timeoutMs: Int = 400): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.close()
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Reads /proc/net/arp and Wi-Fi DHCP info to discover connected devices on phone hotspot.
     */
    fun findHotspotPrinterIp(context: Context, fallbackIp: String = "10.237.2.251"): String {
        val candidates = mutableListOf<String>()

        // 1. Check saved IP from SharedPreferences
        val prefs = context.getSharedPreferences("hkm_prefs", Context.MODE_PRIVATE)
        val saved = prefs.getString("printer_ip", null)
        if (!saved.isNullOrBlank()) candidates.add(saved.trim())

        // 2. Default & common hotspot IPs
        candidates.add(fallbackIp)
        candidates.add("10.237.2.251")
        candidates.add("192.168.43.251")
        candidates.add("192.168.43.100")
        candidates.add("192.168.31.223")

        // 3. Scan /proc/net/arp for connected hotspot clients
        try {
            val arp = File("/proc/net/arp")
            if (arp.exists() && arp.canRead()) {
                arp.forEachLine { line ->
                    val parts = line.split("\\s+".toRegex())
                    if (parts.size >= 4 && parts[0] != "IP") {
                        val ip = parts[0].trim()
                        val mac = parts[3].trim()
                        if (mac != "00:00:00:00:00:00" && ip.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
                            candidates.add(0, ip)
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // Test which candidate has eSCL port 80 or 8080 or RAW print 9100 open
        for (ip in candidates.distinct()) {
            if (isPortReachable(ip, 80, 250) || isPortReachable(ip, 8080, 250) || isPortReachable(ip, 9100, 250)) {
                Log.i(TAG, "Found responsive Epson printer at IP: $ip")
                prefs.edit().putString("printer_ip", ip).apply()
                return ip
            }
        }

        return fallbackIp
    }

    fun checkPrinterOnline(context: Context): Pair<Boolean, String> {
        val printerIp = findHotspotPrinterIp(context)
        val reachable = isPortReachable(printerIp, 80, 350) || isPortReachable(printerIp, 8080, 350) || isPortReachable(printerIp, 9100, 350)
        return Pair(reachable, printerIp)
    }

    /**
     * Executes direct eSCL scanning over Wi-Fi network directly with the Epson L3250.
     * Returns raw image bytes (JPEG) or null if failed/aborted.
     */
    fun scanViaEscl(targetIp: String, dpi: Int = 300, colorMode: String = "RGB24"): ByteArray? {
        isAbortRequested = false
        activeJobUrl = null

        val portsToTry = listOf(80, 8080)
        var selectedPort = 80
        var baseUrl = "http://$targetIp/eSCL"

        // Step 1: Detect whether port 80 or 8080 responds to eSCL
        for (p in portsToTry) {
            val testUrl = "http://$targetIp:$p/eSCL/ScannerCapabilities"
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(testUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 2000
                    readTimeout = 3000
                    setRequestProperty("User-Agent", "HKMTECH-CardPrinter")
                }
                if (conn.responseCode == 200) {
                    selectedPort = p
                    baseUrl = "http://$targetIp:$p/eSCL"
                    Log.i(TAG, "Epson eSCL confirmed active on port $p")
                    break
                }
            } catch (_: Exception) {
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }

        val scanJobXml = """<?xml version="1.0" encoding="UTF-8"?>
<scan:ScanSettings xmlns:scan="http://schemas.hp.com/imaging/escl/2011/05/03" xmlns:pwg="http://www.pwg.org/schemas/2010/12/sm">
  <pwg:Version>2.6</pwg:Version>
  <scan:Intent>Document</scan:Intent>
  <pwg:ScanRegions>
    <pwg:ScanRegion>
      <pwg:Height>3507</pwg:Height>
      <pwg:Width>2480</pwg:Width>
      <pwg:XOffset>0</pwg:XOffset>
      <pwg:YOffset>0</pwg:YOffset>
    </pwg:ScanRegion>
  </pwg:ScanRegions>
  <scan:InputSource>Platen</scan:InputSource>
  <scan:ColorMode>$colorMode</scan:ColorMode>
  <scan:XResolution>$dpi</scan:XResolution>
  <scan:YResolution>$dpi</scan:YResolution>
</scan:ScanSettings>""".trimIndent()

        val postUrlStr = "$baseUrl/ScanJobs"
        var jobLocation: String? = null

        try {
            val postUrl = URL(postUrlStr)
            val postConn = (postUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                doInput = true
                connectTimeout = 5000
                readTimeout = 15000
                setRequestProperty("Content-Type", "text/xml; charset=utf-8")
                setRequestProperty("User-Agent", "HKMTECH-CardPrinter")
            }

            postConn.outputStream.use { os ->
                os.write(scanJobXml.toByteArray(Charsets.UTF_8))
            }

            val statusCode = postConn.responseCode
            if (statusCode !in 200..204) {
                Log.e(TAG, "eSCL ScanJobs POST failed with status: $statusCode")
                postConn.disconnect()
                return null
            }

            jobLocation = postConn.getHeaderField("Location")
            postConn.disconnect()
            Log.i(TAG, "ScanJob created successfully! Location header: $jobLocation")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to submit ScanJob: ${e.message}", e)
            return null
        }

        // Step 2: Build accurate NextDocument URL
        val cleanLoc = (jobLocation ?: "/eSCL/ScanJobs/1").trim().trimEnd('/')
        val jobUrlStr = if (cleanLoc.startsWith("http://") || cleanLoc.startsWith("https://")) {
            cleanLoc
        } else if (cleanLoc.startsWith("/")) {
            "http://$targetIp:$selectedPort$cleanLoc"
        } else {
            "http://$targetIp:$selectedPort/$cleanLoc"
        }
        activeJobUrl = jobUrlStr
        val docUrlStr = "$jobUrlStr/NextDocument"

        Log.i(TAG, "Polling for scanned image at: $docUrlStr")

        // Step 3: Wait for scanner carriage to finish and download image
        // Allow up to 45 iterations * 1.5s = ~68 seconds (covers 150, 300, and 600 DPI scans)
        try {
            Thread.sleep(2000)
        } catch (_: InterruptedException) {
            return null
        }

        for (attempt in 1..45) {
            if (isAbortRequested) {
                Log.i(TAG, "Scan aborted by user during polling loop")
                return null
            }

            var docConn: HttpURLConnection? = null
            try {
                val docUrl = URL(docUrlStr)
                docConn = (docUrl.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 40000
                    setRequestProperty("User-Agent", "HKMTECH-CardPrinter")
                }

                val code = docConn.responseCode
                Log.d(TAG, "Poll attempt $attempt/45 -> HTTP $code")

                if (code == 200) {
                    val bytes = docConn.inputStream.use { it.readBytes() }
                    if (bytes.isNotEmpty()) {
                        Log.i(TAG, "Successfully downloaded scanned document! Size: ${bytes.size} bytes")
                        return bytes
                    }
                } else if (code in listOf(503, 404, 409, 204)) {
                    // 503 Service Unavailable / 404 Not Ready / 409 Busy: scanner carriage still moving
                    try { docConn.errorStream?.close() } catch (_: Exception) {}
                } else {
                    Log.w(TAG, "Unexpected HTTP code $code on NextDocument poll")
                    try { docConn.errorStream?.close() } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.w(TAG, "Exception on poll attempt $attempt: ${e.message}")
            } finally {
                try { docConn?.disconnect() } catch (_: Exception) {}
            }

            try {
                Thread.sleep(1500)
            } catch (_: InterruptedException) {
                return null
            }
        }

        Log.e(TAG, "Polling timed out after 45 attempts without receiving image.")
        return null
    }
}
