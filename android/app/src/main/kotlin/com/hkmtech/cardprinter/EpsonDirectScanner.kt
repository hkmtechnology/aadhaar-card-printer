package com.hkmtech.cardprinter

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object EpsonDirectScanner {

    private const val TAG = "HKM_DIRECT_SCAN"

    @Volatile
    private var isAbortRequested = false

    @Volatile
    private var activeJobUrl: String? = null

    @Volatile
    private var activeSocket: Socket? = null

    fun abortCurrentScan() {
        isAbortRequested = true
        // 1. Abort ESC/I socket if active
        try {
            activeSocket?.close()
        } catch (_: Exception) {}

        // 2. Abort eSCL job if active
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

    fun isPortReachable(host: String, port: Int, timeoutMs: Int = 300): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.close()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun isPrinterOnlineQuick(ip: String): Boolean {
        // Safe probe: port 9100 (raw print port) responds in < 5ms without creating an ESC/I scanner session lock
        if (isPortReachable(ip, 9100, 100)) return true
        if (isPortReachable(ip, 80, 100) || isPortReachable(ip, 443, 100)) return true
        return false
    }

    /**
     * Fast non-blocking printer locator:
     * 1. Instant check for Wi-Fi Direct interface (p2p0).
     * 2. Fast check of saved printer IP.
     * 3. Fast check of priority fallback IPs.
     */
    fun findHotspotPrinterIp(context: Context, fallbackIp: String = "192.168.223.1"): String {
        val prefs = context.getSharedPreferences("hkm_prefs", Context.MODE_PRIVATE)

        // 1. Wi-Fi Direct interface check (instant 0ms)
        val p2p = DeviceDetector.getActiveWifiDirectGateway()
        if (p2p != null && isPrinterOnlineQuick(p2p)) {
            Log.i(TAG, "Wi-Fi Direct printer discovered instantly at: $p2p")
            prefs.edit().putString("printer_ip", p2p).apply()
            return p2p
        }

        // 2. Saved preference IP
        val saved = prefs.getString("printer_ip", null)?.trim()
        if (!saved.isNullOrBlank() && isPrinterOnlineQuick(saved)) {
            Log.i(TAG, "Saved printer IP is responsive: $saved")
            return saved
        }

        // 3. Priority fallback IPs
        val priorityIps = listOf(
            "192.168.223.1",
            "10.237.2.251",
            "192.168.43.251",
            "192.168.192.1",
            "192.168.31.223"
        )
        for (ip in priorityIps) {
            if (isPrinterOnlineQuick(ip)) {
                Log.i(TAG, "Found responsive Epson printer at priority IP: $ip")
                prefs.edit().putString("printer_ip", ip).apply()
                return ip
            }
        }

        return fallbackIp
    }

    fun checkPrinterOnline(context: Context): Pair<Boolean, String> {
        val printerIp = findHotspotPrinterIp(context)
        val reachable = isPrinterOnlineQuick(printerIp)
        return Pair(reachable, printerIp)
    }

    /**
     * UNIFIED DIRECT MOBILE SCANNER:
     * 1. First tries native Epson ESC/I-net (port 1865) used by L3250 / EcoTank series.
     * 2. If port 1865 fails, falls back to eSCL AirScan (port 80/8080).
     */
    fun scanDirect(targetIp: String, dpi: Int = 150, colorMode: String = "color"): ByteArray? {
        isAbortRequested = false

        // 1. Native Epson ESC/I-net (ENTP port 1865) directly:
        // Do NOT probe port 1865 beforehand because opening/closing TCP creates a firmware session conflict!
        Log.i(TAG, "Attempting native ESC/I-net scan to $targetIp:1865 (DPI=$dpi, mode=$colorMode)...")
        try {
            val bytes = scanViaEscI(targetIp, dpi, colorMode)
            if (bytes != null && bytes.isNotEmpty()) {
                Log.i(TAG, "Native ESC/I scan succeeded! Received ${bytes.size} bytes")
                return bytes
            }
        } catch (e: Exception) {
            Log.w(TAG, "Native ESC/I scan exception: ${e.message}")
        }

        // 2. Fallback to eSCL AirScan protocol (port 80 / 8080)
        if (isPortReachable(targetIp, 80, 350) || isPortReachable(targetIp, 8080, 350)) {
            Log.i(TAG, "Attempting fallback eSCL scan to $targetIp (DPI=$dpi)...")
            try {
                val colorArg = if (colorMode.contains("gray", ignoreCase = true)) "Grayscale8" else "RGB24"
                val bytes = scanViaEscl(targetIp, dpi, colorArg)
                if (bytes != null && bytes.isNotEmpty()) {
                    Log.i(TAG, "Fallback eSCL scan succeeded! Received ${bytes.size} bytes")
                    return bytes
                }
            } catch (e: Exception) {
                Log.w(TAG, "Fallback eSCL scan exception: ${e.message}")
            }
        }

        Log.e(TAG, "All direct scan protocols failed for $targetIp")
        return null
    }

    // =========================================================================
    // NATIVE EPSON ESC/I-net (PORT 1865) ENGINE
    // =========================================================================

    private fun readExact(stream: InputStream, n: Int): ByteArray {
        val data = ByteArray(n)
        var total = 0
        while (total < n) {
            val r = stream.read(data, total, n - total)
            if (r < 0) throw java.io.EOFException("Socket closed during readExact(wanted=$n, got=$total)")
            total += r
        }
        return data
    }

    private fun sendEscIPacket(out: OutputStream, cmd: Int, payload: ByteArray = ByteArray(0), replyLen: Int = 0) {
        val h1 = ByteArray(12)
        h1[0] = 'I'.code.toByte()
        h1[1] = 'S'.code.toByte()
        h1[2] = ((cmd ushr 8) and 0xff).toByte()
        h1[3] = (cmd and 0xff).toByte()
        h1[4] = 0x00
        h1[5] = 0x0C // Header size (offset to payload)

        val bufSize = payload.size
        val isPassthru = ((cmd ushr 8) == 0x20)

        if (isPassthru) {
            val totalSize = bufSize + 8
            h1[6] = ((totalSize ushr 24) and 0xff).toByte()
            h1[7] = ((totalSize ushr 16) and 0xff).toByte()
            h1[8] = ((totalSize ushr 8) and 0xff).toByte()
            h1[9] = (totalSize and 0xff).toByte()

            val h2 = ByteArray(8)
            h2[0] = ((bufSize ushr 24) and 0xff).toByte()
            h2[1] = ((bufSize ushr 16) and 0xff).toByte()
            h2[2] = ((bufSize ushr 8) and 0xff).toByte()
            h2[3] = (bufSize and 0xff).toByte()
            h2[4] = ((replyLen ushr 24) and 0xff).toByte()
            h2[5] = ((replyLen ushr 16) and 0xff).toByte()
            h2[6] = ((replyLen ushr 8) and 0xff).toByte()
            h2[7] = (replyLen and 0xff).toByte()

            out.write(h1)
            out.write(h2)
            if (bufSize > 0) out.write(payload)
        } else {
            h1[6] = ((bufSize ushr 24) and 0xff).toByte()
            h1[7] = ((bufSize ushr 16) and 0xff).toByte()
            h1[8] = ((bufSize ushr 8) and 0xff).toByte()
            h1[9] = (bufSize and 0xff).toByte()

            out.write(h1)
            if (bufSize > 0) out.write(payload)
        }
        out.flush()
    }

    private fun readEscIPacket(stream: InputStream): ByteArray {
        val hdr = readExact(stream, 12)
        if (hdr[0] != 'I'.code.toByte() || hdr[1] != 'S'.code.toByte()) {
            throw java.io.IOException("Invalid ESC/I header signature: [${hdr[0]}, ${hdr[1]}]")
        }
        val size = ((hdr[6].toInt() and 0xff) shl 24) or
                   ((hdr[7].toInt() and 0xff) shl 16) or
                   ((hdr[8].toInt() and 0xff) shl 8) or
                   (hdr[9].toInt() and 0xff)
        return if (size > 0) readExact(stream, size) else ByteArray(0)
    }

    private fun hasJpegEoi(data: ByteArray): Boolean {
        if (data.size < 4) return false
        val start = (data.size - 256).coerceAtLeast(0)
        for (i in data.size - 2 downTo start) {
            if ((data[i].toInt() and 0xff) == 0xff && (data[i + 1].toInt() and 0xff) == 0xd9) {
                return true
            }
        }
        return false
    }

    /**
     * Executes native Epson ESC/I-net scan directly over TCP port 1865.
     * Returns JPEG byte array.
     */
    fun scanViaEscI(targetIp: String, dpi: Int = 150, colorMode: String = "color"): ByteArray? {
        isAbortRequested = false
        val socket = Socket()
        activeSocket = socket
        var hasEoi = false
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 45000 // Allow up to 45s for scanner carriage traversal
            socket.connect(InetSocketAddress(targetIp, 1865), 5000)

            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            // 1. Read welcome message (12-byte header + payload)
            val welcomeHdr = readExact(input, 12)
            val welcomeSize = ((welcomeHdr[6].toInt() and 0xff) shl 24) or
                              ((welcomeHdr[7].toInt() and 0xff) shl 16) or
                              ((welcomeHdr[8].toInt() and 0xff) shl 8) or
                              (welcomeHdr[9].toInt() and 0xff)
            val welcomePayload = if (welcomeSize > 0) readExact(input, welcomeSize) else ByteArray(0)
            Log.d(TAG, "ESC/I welcome message consumed (${welcomePayload.size} bytes)")

            // Check if a stale session lock exists (tag 0x81)
            if (welcomePayload.size >= 5 && (welcomePayload[4].toInt() and 0xff) == 0x81) {
                Log.w(TAG, "Printer indicates busy/lock (0x81), clearing with FIN and waiting 1.5s for carriage home...")
                try {
                    sendEscIPacket(output, 0x2000, "CAN x0000000".toByteArray(Charsets.US_ASCII), 64)
                    try { readEscIPacket(input) } catch (_: Exception) {}
                    sendEscIPacket(output, 0x2000, "FIN x0000000".toByteArray(Charsets.US_ASCII), 64)
                    try { readEscIPacket(input) } catch (_: Exception) {}
                    sendEscIPacket(output, 0x2101, ByteArray(0), 0)
                    try { readEscIPacket(input) } catch (_: Exception) {}
                } catch (_: Exception) {}
                Thread.sleep(1500)
            }

            // 2. Lock network (cmd 0x2100 with empty payload - retry if carriage returning home)
            var lockAck = ByteArray(0)
            for (attempt in 1..4) {
                sendEscIPacket(output, 0x2100, ByteArray(0), 0)
                lockAck = readEscIPacket(input)
                Log.d(TAG, "ESC/I network lock ack (attempt $attempt): ${lockAck.size} bytes")
                if (lockAck.isNotEmpty()) break
                Thread.sleep(500)
            }

            // 3. Lock hardware scanner (cmd 0x2000, payload = FS X = \x1cX)
            val fsxPayload = byteArrayOf(0x1c, 'X'.code.toByte())
            sendEscIPacket(output, 0x2000, fsxPayload, 1)
            val fsxAck = readEscIPacket(input)
            Log.d(TAG, "ESC/I hardware lock ack: ${fsxAck.size} bytes")

            // 4. Configure scan parameters
            val isGray = colorMode.contains("gray", ignoreCase = true)
            val colorTag = if (isGray) "#COLM008" else "#COLC024"

            // Standard A4 platen dimensions by DPI (8.267 x 11.692 inches)
            val widthPx = (8.267 * dpi).toInt().coerceIn(100, 15000)
            val heightPx = (11.692 * dpi).toInt().coerceIn(100, 20000)

            val paramsStr = String.format(
                Locale.US,
                "#FB %s#FMTJPG #JPGd090#GMMUG18#RSMd%03d#RSSd%03d#BSZi1048576#ACQi0000000i0000000i%07di%07d",
                colorTag, dpi, dpi, widthPx, heightPx
            )
            val paramsBytes = paramsStr.toByteArray(Charsets.US_ASCII)
            val paraHdr = String.format(Locale.US, "PARAx%07x", paramsBytes.size).toByteArray(Charsets.US_ASCII)

            // Send PARA header (reply_len = 0) and read its ACK
            sendEscIPacket(output, 0x2000, paraHdr, 0)
            val paraHdrAck = readEscIPacket(input)
            Log.d(TAG, "ESC/I PARA header ack: ${paraHdrAck.size} bytes")

            // Send PARA payload (reply_len = 64) and read response (#parOK)
            sendEscIPacket(output, 0x2000, paramsBytes, 64)
            val paraResp = readEscIPacket(input)
            Log.d(TAG, "ESC/I PARA response: ${String(paraResp, Charsets.US_ASCII)}")

            // 5. Start data transfer mode (TRDT)
            sendEscIPacket(output, 0x2000, "TRDTx0000000".toByteArray(Charsets.US_ASCII), 64)
            val trdtResp = readEscIPacket(input)
            Log.d(TAG, "ESC/I TRDT response: ${String(trdtResp, Charsets.US_ASCII)}")

            // 6. Acquire JPEG chunks (IMG x0000000)
            val jpegStream = ByteArrayOutputStream()
            val imgCmd = "IMG x0000000".toByteArray(Charsets.US_ASCII)
            var consecutiveEmpty = 0
            var totalChunks = 0

            for (chunkNum in 0..10000) {
                if (isAbortRequested) {
                    Log.i(TAG, "Scan aborted by user during chunk acquisition")
                    try {
                        sendEscIPacket(output, 0x2000, byteArrayOf(0x18), 0)
                        sendEscIPacket(output, 0x2000, byteArrayOf(0x1b, '@'.code.toByte()), 0)
                    } catch (_: Exception) {}
                    break
                }

                sendEscIPacket(output, 0x2000, imgCmd, 64)
                val chunkHdr = readEscIPacket(input)
                val hdrStr = String(chunkHdr, Charsets.US_ASCII)

                val imgIdx = hdrStr.indexOf("IMG ")
                if (imgIdx == -1) {
                    Log.w(TAG, "Non-IMG response header: $hdrStr")
                    if (jpegStream.size() == 0 && consecutiveEmpty++ < 80) {
                        Thread.sleep(250)
                        continue
                    }
                    if (hasEoi || jpegStream.size() > 50000) {
                        Log.i(TAG, "Non-IMG header after substantial image (${jpegStream.size()} bytes), stream complete")
                        break
                    }
                    if (consecutiveEmpty++ < 40) {
                        Thread.sleep(200)
                        continue
                    }
                    break
                }

                val imgPart = hdrStr.substring(imgIdx)
                val moreHex = if (imgPart.length >= 12) imgPart.substring(5, 12).trim() else ""
                val moreLen = try { moreHex.toInt(16) } catch (_: Exception) { 0 }

                if (moreLen <= 0) {
                    // Check if complete JPEG has been received
                    if (hasEoi) {
                        Log.i(TAG, "Scan successfully finished: JPEG EOI marker (FF D9) confirmed! Total size: ${jpegStream.size()} bytes across $totalChunks chunks")
                        break
                    }

                    // Before any data arrives: carriage is warming up and moving to scan position
                    if (jpegStream.size() == 0) {
                        if (consecutiveEmpty++ < 80) {
                            Thread.sleep(250)
                            continue
                        }
                        Log.w(TAG, "Timed out waiting for carriage warm-up")
                        break
                    }

                    // During data transfer: carriage is traversing platen between line buffers
                    if (consecutiveEmpty++ < 50) {
                        Thread.sleep(200)
                        continue
                    }

                    // If substantial data was collected and line feed has ceased:
                    if (jpegStream.size() > 50000) {
                        Log.i(TAG, "Scan stream ended cleanly after line feed silence (${jpegStream.size()} bytes)")
                        break
                    }

                    Log.w(TAG, "Scan stream interrupted prematurely (received ${jpegStream.size()} bytes)")
                    break
                }

                // Reset empty retries as active data is being transferred
                consecutiveEmpty = 0

                // Request the actual data chunk of length moreLen
                sendEscIPacket(output, 0x2000, ByteArray(0), moreLen)
                val chunkBytes = readEscIPacket(input)
                if (chunkBytes.isNotEmpty()) {
                    jpegStream.write(chunkBytes)
                    totalChunks++
                    if (hasJpegEoi(jpegStream.toByteArray())) {
                        hasEoi = true
                        Log.i(TAG, "Detected JPEG EOI (FF D9) marker! Current size: ${jpegStream.size()} bytes")
                    }
                }
                Log.d(TAG, "Acquired chunk $totalChunks: ${chunkBytes.size} bytes (Total: ${jpegStream.size()})")
            }

            // 7. Terminate data transfer cleanly with FIN x0000000
            try {
                Log.i(TAG, "Sending ESC/I-2 FIN x0000000 to terminate transfer state...")
                sendEscIPacket(output, 0x2000, "FIN x0000000".toByteArray(Charsets.US_ASCII), 64)
                val finResp = readEscIPacket(input)
                Log.i(TAG, "ESC/I-2 FIN response: ${String(finResp, Charsets.US_ASCII)}")
            } catch (e: Exception) {
                Log.w(TAG, "Notice on FIN command: ${e.message}")
            }

            // 8. Release network lock (0x2101)
            try {
                sendEscIPacket(output, 0x2101, ByteArray(0), 0)
                val unlAck = readEscIPacket(input)
                Log.i(TAG, "ESC/I-2 network session cleanly unlocked: ${unlAck.size} bytes")
            } catch (e: Exception) {
                Log.w(TAG, "Notice on network unlock: ${e.message}")
            }

            val finalBytes = jpegStream.toByteArray()
            if (finalBytes.isNotEmpty() && !isAbortRequested) {
                return finalBytes
            }
        } catch (e: Exception) {
            Log.e(TAG, "scanViaEscI exception: ${e.message}", e)
        } finally {
            // Safety fallback: ensure session is always released even on error/abort
            try {
                activeSocket?.let { s ->
                    if (!s.isClosed) {
                        val out = s.getOutputStream()
                        val inp = s.getInputStream()

                        // If scan was aborted or didn't finish cleanly, send CAN and FIN
                        if (isAbortRequested || !hasEoi) {
                            try {
                                sendEscIPacket(out, 0x2000, "CAN x0000000".toByteArray(Charsets.US_ASCII), 64)
                                try { readEscIPacket(inp) } catch (_: Exception) {}
                                sendEscIPacket(out, 0x2000, "FIN x0000000".toByteArray(Charsets.US_ASCII), 64)
                                try { readEscIPacket(inp) } catch (_: Exception) {}
                            } catch (_: Exception) {}
                        }

                        // Unlock network session (0x2101)
                        sendEscIPacket(out, 0x2101, ByteArray(0), 0)
                        try { readEscIPacket(inp) } catch (_: Exception) {}

                        Log.i(TAG, "ESC/I hardware and network session cleanly closed")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error during cleanup unlock: ${e.message}")
            } finally {
                try { socket.close() } catch (_: Exception) {}
                activeSocket = null
            }
        }
        return null
    }

    // =========================================================================
    // eSCL (AIRSCAN) FALLBACK ENGINE
    // =========================================================================

    fun scanViaEscl(targetIp: String, dpi: Int = 150, colorMode: String = "RGB24"): ByteArray? {
        isAbortRequested = false
        activeJobUrl = null

        val portsToTry = listOf(80, 8080)
        var selectedPort = 80
        var baseUrl = "http://$targetIp/eSCL"

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
                    Log.i(TAG, "eSCL confirmed on port $p")
                    break
                }
            } catch (_: Exception) {
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }

        val wPx = (8.267 * dpi).toInt().coerceIn(100, 15000)
        val hPx = (11.692 * dpi).toInt().coerceIn(100, 20000)
        val modeXml = if (colorMode.contains("gray", ignoreCase = true)) "Grayscale8" else "RGB24"

        val scanJobXml = """<?xml version="1.0" encoding="UTF-8"?>
<scan:ScanSettings xmlns:scan="http://schemas.hp.com/imaging/escl/2011/05/03" xmlns:pwg="http://www.pwg.org/schemas/2010/12/sm">
  <pwg:Version>2.6</pwg:Version>
  <scan:Intent>Document</scan:Intent>
  <pwg:ScanRegions>
    <pwg:ScanRegion>
      <pwg:Height>$hPx</pwg:Height>
      <pwg:Width>$wPx</pwg:Width>
      <pwg:XOffset>0</pwg:XOffset>
      <pwg:YOffset>0</pwg:YOffset>
    </pwg:ScanRegion>
  </pwg:ScanRegions>
  <scan:InputSource>Platen</scan:InputSource>
  <scan:ColorMode>$modeXml</scan:ColorMode>
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to submit ScanJob: ${e.message}", e)
            return null
        }

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

        try { Thread.sleep(1500) } catch (_: InterruptedException) { return null }

        for (attempt in 1..45) {
            if (isAbortRequested) return null

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
                if (code == 200) {
                    val bytes = docConn.inputStream.use { it.readBytes() }
                    if (bytes.isNotEmpty()) return bytes
                } else if (code in listOf(503, 404, 409, 204)) {
                    try { docConn.errorStream?.close() } catch (_: Exception) {}
                } else {
                    try { docConn.errorStream?.close() } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.w(TAG, "Poll attempt $attempt exception: ${e.message}")
            } finally {
                try { docConn?.disconnect() } catch (_: Exception) {}
            }

            try { Thread.sleep(1200) } catch (_: InterruptedException) { return null }
        }

        return null
    }
}
