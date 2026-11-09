package com.hkmtech.cardprinter

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Base64
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Locale

object EpsonDirectScanner {

    fun getGatewayIp(context: Context): String {
        try {
            val wifiMgr = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val dhcp = wifiMgr?.dhcpInfo
            if (dhcp != null && dhcp.gateway != 0) {
                val g = dhcp.gateway
                return String.format(Locale.US, "%d.%d.%d.%d", g and 0xff, g shr 8 and 0xff, g shr 16 and 0xff, g shr 24 and 0xff)
            }
        } catch (_: Exception) {}
        return "10.237.2.165"
    }

    fun isPortReachable(host: String, port: Int, timeoutMs: Int = 500): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.close()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun checkPrinterOnline(context: Context): Pair<Boolean, String> {
        val dev = DeviceDetector.detect(context)
        val ipsToTest = listOf(dev.printerIp, "10.237.2.251", "192.168.31.223", "192.168.192.168", "192.168.223.1")

        for (ip in ipsToTest.distinct()) {
            // Check RAW Print port 9100, HTTP port 80, or IPP port 631
            if (isPortReachable(ip, 9100, 400) || isPortReachable(ip, 80, 400) || isPortReachable(ip, 631, 400)) {
                return Pair(true, ip)
            }
        }
        return Pair(false, dev.printerIp)
    }

    fun scanViaEscl(printerIp: String, dpi: Int = 300): String? {
        val urlsToTry = listOf(
            "http://$printerIp/eSCL/ScanJobs",
            "http://$printerIp:8080/eSCL/ScanJobs",
            "http://$printerIp:80/eSCL/ScanJobs"
        )

        // Standard eSCL XML Scan Request for Platen (Flatbed)
        val xmlBody = """<?xml version="1.0" encoding="UTF-8"?>
<scan:ScanSettings xmlns:scan="http://schemas.hp.com/imaging/escl/2011/05/03" xmlns:pwg="http://www.pwg.org/schemas/2010/12/sm">
    <pwg:Version>2.0</pwg:Version>
    <scan:InputSource>Platen</scan:InputSource>
    <scan:ColorMode>RGB24</scan:ColorMode>
    <scan:XResolution>$dpi</scan:XResolution>
    <scan:YResolution>$dpi</scan:YResolution>
    <scan:DocumentFormat>image/jpeg</scan:DocumentFormat>
</scan:ScanSettings>""".trimIndent()

        for (postUrlStr in urlsToTry) {
            try {
                val url = URL(postUrlStr)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    doInput = true
                    connectTimeout = 3000
                    readTimeout = 15000
                    setRequestProperty("Content-Type", "text/xml; charset=UTF-8")
                }

                conn.outputStream.use { os ->
                    os.write(xmlBody.toByteArray(Charsets.UTF_8))
                }

                val code = conn.responseCode
                if (code == 201 || code == 200) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()

                    val docUrlStr = if (location != null) {
                        if (location.startsWith("http")) location + "/NextDocument" else "http://$printerIp$location/NextDocument"
                    } else {
                        "http://$printerIp/eSCL/ScanJobs/NextDocument"
                    }

                    // Poll for NextDocument
                    Thread.sleep(1500)
                    for (attempt in 1..25) {
                        try {
                            val docUrl = URL(docUrlStr)
                            val docConn = (docUrl.openConnection() as HttpURLConnection).apply {
                                connectTimeout = 4000
                                readTimeout = 30000
                            }
                            if (docConn.responseCode == 200) {
                                val bytes = docConn.inputStream.use { it.readBytes() }
                                docConn.disconnect()
                                if (bytes.isNotEmpty()) {
                                    return Base64.encodeToString(bytes, Base64.NO_WRAP)
                                }
                            }
                            docConn.disconnect()
                        } catch (_: Exception) {}
                        Thread.sleep(1000)
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }
}
