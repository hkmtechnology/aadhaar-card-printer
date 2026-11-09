package com.hkmtech.cardprinter

import android.content.Context
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
    val bridgeIp: String = "10.237.2.154"
)

object DeviceDetector {

    @Volatile
    private var cachedBridgeIp = "10.237.2.154"
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

    fun isPortOpen(host: String, port: Int, timeoutMs: Int = 250): Boolean {
        return try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.close()
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Fast non-blocking detect() that returns immediately (< 1ms).
     * Background thread updates cache periodically without stalling UI or JS bridge.
     */
    fun detect(context: Context): DeviceInfo {
        val prefs = context.getSharedPreferences("hkm_prefs", Context.MODE_PRIVATE)
        val savedBridge = prefs.getString("scanner_ip", null)
        val bridgeIp = if (!savedBridge.isNullOrBlank()) savedBridge else cachedBridgeIp

        val savedPrinter = prefs.getString("printer_ip", null)
        if (!savedPrinter.isNullOrBlank()) cachedPrinterIp = savedPrinter

        val now = System.currentTimeMillis()
        if (now - lastProbeTime > 15000 && !isProbeRunning) {
            lastProbeTime = now
            isProbeRunning = true
            Thread {
                try {
                    val bridgeOk = isPortOpen(bridgeIp, 8767, 300)
                    if (bridgeOk) cachedBridgeIp = bridgeIp

                    val activePrinterIp = EpsonDirectScanner.findHotspotPrinterIp(context, cachedPrinterIp)
                    cachedPrinterIp = activePrinterIp

                    val printerOk = isPortOpen(cachedPrinterIp, 80, 300) || isPortOpen(cachedPrinterIp, 8080, 300) || isPortOpen(cachedPrinterIp, 9100, 300)
                    isScannerReady = (printerOk || bridgeOk)
                } catch (_: Exception) {
                } finally {
                    isProbeRunning = false
                }
            }.start()
        }

        return DeviceInfo(
            connectionType = "Wi-Fi Hotspot",
            wifiSsid = "HKMTECH",
            printerName = "EPSON L3250 Series",
            modelNumber = "L3250",
            port = "Hotspot ($cachedPrinterIp)",
            isReady = isScannerReady,
            scannerName = "EPSON L3250 (Direct Wi-Fi)",
            scannerOnline = isScannerReady,
            isWifiDirect = false,
            printerIp = cachedPrinterIp,
            bridgeIp = bridgeIp
        )
    }
}
