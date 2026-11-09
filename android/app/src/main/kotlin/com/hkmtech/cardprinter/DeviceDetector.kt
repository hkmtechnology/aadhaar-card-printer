package com.hkmtech.cardprinter

import android.content.Context
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

data class DeviceInfo(
    val connectionType: String,
    val wifiSsid: String?,
    val printerName: String,
    val modelNumber: String,
    val port: String,
    val isReady: Boolean,
    val scannerName: String,
    val scannerOnline: Boolean,
    val isWifiDirect: Boolean = false,
    val printerIp: String = "10.237.2.251",
    val bridgeIp: String = "127.0.0.1"
)

object DeviceDetector {

    @Volatile
    private var cachedBridgeIp = "127.0.0.1"
    @Volatile
    private var cachedPrinterIp = "10.237.2.251"
    @Volatile
    private var isScannerReady = true
    @Volatile
    private var isProbeRunning = false
    @Volatile
    private var lastProbeTime = 0L

    fun setPrinterIp(ip: String) {
        if (ip.isNotBlank()) cachedPrinterIp = ip.trim()
    }

    fun isPortOpen(host: String, port: Int, timeoutMs: Int = 200): Boolean {
        return try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.close()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun getCandidateBridgeIps(context: Context): List<String> {
        val list = mutableListOf<String>()
        // 1. Localhost (ADB reverse / USB tethering)
        list.add("127.0.0.1")

        // 2. Saved preference in app
        val prefs = context.getSharedPreferences("hkm_prefs", Context.MODE_PRIVATE)
        val saved = prefs.getString("scanner_ip", null)
        if (!saved.isNullOrBlank() && saved.trim() != "127.0.0.1") {
            list.add(saved.trim())
        }

        // 3. Known PC Wi-Fi and Hotspot IPs
        list.add("192.168.31.156")
        list.add("10.237.2.154")
        list.add("192.168.43.1")
        list.add("10.237.2.1")
        list.add("192.168.31.1")

        // 4. Read /proc/net/arp for reachable hosts
        try {
            val arp = File("/proc/net/arp")
            if (arp.exists() && arp.canRead()) {
                arp.forEachLine { line ->
                    val parts = line.split("\\s+".toRegex())
                    if (parts.size >= 4 && parts[0] != "IP") {
                        val ip = parts[0].trim()
                        val mac = parts[3].trim()
                        if (mac != "00:00:00:00:00:00" && ip.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
                            list.add(ip)
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return list.distinct()
    }

    fun findActiveBridgeIp(context: Context): String {
        for (ip in getCandidateBridgeIps(context)) {
            if (isPortOpen(ip, 8767, 180)) {
                cachedBridgeIp = ip
                return ip
            }
        }
        return cachedBridgeIp
    }

    /**
     * Fast non-blocking detect() that returns immediately (< 1ms).
     * Background thread updates cache periodically without stalling UI or JS bridge.
     */
    fun detect(context: Context): DeviceInfo {
        val prefs = context.getSharedPreferences("hkm_prefs", Context.MODE_PRIVATE)
        val savedPrinter = prefs.getString("printer_ip", null)
        if (!savedPrinter.isNullOrBlank()) cachedPrinterIp = savedPrinter

        val now = System.currentTimeMillis()
        if (now - lastProbeTime > 10000 && !isProbeRunning) {
            lastProbeTime = now
            isProbeRunning = true
            Thread {
                try {
                    val activeBridge = findActiveBridgeIp(context)
                    val bridgeOk = isPortOpen(activeBridge, 8767, 250)

                    val activePrinterIp = EpsonDirectScanner.findHotspotPrinterIp(context, cachedPrinterIp)
                    cachedPrinterIp = activePrinterIp

                    val printerOk = isPortOpen(cachedPrinterIp, 80, 250) || isPortOpen(cachedPrinterIp, 8080, 250) || isPortOpen(cachedPrinterIp, 9100, 250)
                    isScannerReady = (printerOk || bridgeOk)
                } catch (_: Exception) {
                } finally {
                    isProbeRunning = false
                }
            }.start()
        }

        return DeviceInfo(
            connectionType = "Wi-Fi / USB",
            wifiSsid = "HKMTECH",
            printerName = "EPSON L3250 Series",
            modelNumber = "L3250",
            port = "Ready ($cachedBridgeIp)",
            isReady = isScannerReady,
            scannerName = "EPSON L3250 (Scanner Bridge)",
            scannerOnline = isScannerReady,
            isWifiDirect = false,
            printerIp = cachedPrinterIp,
            bridgeIp = cachedBridgeIp
        )
    }
}
