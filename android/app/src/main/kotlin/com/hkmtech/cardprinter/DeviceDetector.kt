package com.hkmtech.cardprinter

import android.content.Context
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
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
    val bridgeIp: String = "10.237.2.154"
)

object DeviceDetector {

    private fun isPortOpen(host: String, port: Int, timeoutMs: Int = 350): Boolean {
        return try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.close()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun getArpEntries(): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        try {
            val file = File("/proc/net/arp")
            if (file.exists()) {
                file.forEachLine { line ->
                    val tokens = line.trim().split("\\s+".toRegex())
                    if (tokens.size >= 4 && tokens[0] != "IP") {
                        val ip = tokens[0]
                        val mac = tokens[3].lowercase()
                        if (ip.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+")) && mac != "00:00:00:00:00:00") {
                            list.add(Pair(ip, mac))
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return list
    }

    fun detect(context: Context): DeviceInfo {
        var isWifi = false
        var isCellular = false
        var ssid: String? = null

        // 1. Check Network Connectivity
        try {
            val connMgr = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNw = connMgr?.activeNetwork
            val caps = connMgr?.getNetworkCapabilities(activeNw)
            isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            isCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

            if (isWifi) {
                val wifiMgr = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val wifiInfo = wifiMgr?.connectionInfo
                val raw = wifiInfo?.ssid?.replace("\"", "") ?: ""
                if (raw.isNotEmpty() && raw != "<unknown ssid>") {
                    ssid = raw
                }
            }
        } catch (_: Exception) {}

        // 2. Discover Connected Devices via Hotspot Direct Probe & Subnet Scan
        var detectedBridgeIp = "10.237.2.154"
        var detectedPrinterIp = "10.237.2.251"
        var isHotspotActive = false

        // Fast probe default hotspot candidate IPs
        if (isPortOpen("10.237.2.154", 8765, 300)) {
            detectedBridgeIp = "10.237.2.154"
            isHotspotActive = true
        }
        if (isPortOpen("10.237.2.251", 9100, 300) || isPortOpen("10.237.2.251", 80, 300)) {
            detectedPrinterIp = "10.237.2.251"
            isHotspotActive = true
        }

        // Check local interfaces for active hotspot/Wi-Fi subnet
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val host = addr.hostAddress ?: ""
                        val parts = host.split(".")
                        if (parts.size == 4) {
                            val prefix = "${parts[0]}.${parts[1]}.${parts[2]}."
                            for (lastOctet in listOf("154", "251", "223", "1", "100")) {
                                val testIp = prefix + lastOctet
                                if (testIp != host) {
                                    if (isPortOpen(testIp, 8765, 200)) {
                                        detectedBridgeIp = testIp
                                        isHotspotActive = true
                                    }
                                    if (isPortOpen(testIp, 9100, 200) || isPortOpen(testIp, 80, 200)) {
                                        detectedPrinterIp = testIp
                                        isHotspotActive = true
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // Fallback to ARP entries if readable
        val arpList = getArpEntries()
        if (arpList.isNotEmpty()) {
            isHotspotActive = true
            for ((ip, mac) in arpList) {
                if (isPortOpen(ip, 8765, 200)) detectedBridgeIp = ip
                if (mac.startsWith("a4:d7:3c") || mac.startsWith("00:26:ab") || isPortOpen(ip, 9100, 200)) {
                    detectedPrinterIp = ip
                }
            }
        }

        // Check if printer or bridge is saved in preferences
        val prefs = context.getSharedPreferences("hkm_prefs", Context.MODE_PRIVATE)
        val savedBridge = prefs.getString("scanner_ip", null)
        if (!savedBridge.isNullOrBlank()) {
            detectedBridgeIp = savedBridge
        } else {
            prefs.edit().putString("scanner_ip", detectedBridgeIp).apply()
        }

        // 3. Check Wi-Fi Direct
        val ssidUpper = (ssid ?: "").uppercase()
        val isDirectSsid = ssidUpper.contains("DIRECT") && (ssidUpper.contains("L3250") || ssidUpper.contains("EPSON"))
        val isWifiDirect = isDirectSsid

        // 4. Check USB OTG
        var hasUsbPrinter = false
        try {
            val usbMgr = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            val list = usbMgr?.deviceList
            if (!list.isNullOrEmpty()) {
                for (dev in list.values) {
                    for (i in 0 until dev.interfaceCount) {
                        if (dev.getInterface(i).interfaceClass == 7) {
                            hasUsbPrinter = true
                            break
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        val connType = when {
            isHotspotActive -> "Hotspot / Wi-Fi"
            isWifiDirect -> "Wi-Fi Direct"
            isWifi -> "Wi-Fi"
            hasUsbPrinter -> "USB OTG"
            isCellular -> "Mobile Data"
            else -> "Hotspot Mode"
        }

        val portStr = when {
            isHotspotActive -> "Hotspot (Printer: $detectedPrinterIp • PC: $detectedBridgeIp)"
            isWifiDirect -> "Wi-Fi Direct ($detectedPrinterIp)"
            isWifi -> "Wi-Fi Network (${ssid ?: "Connected"})"
            hasUsbPrinter -> "USB OTG Port"
            else -> "Hotspot / Wi-Fi ($detectedPrinterIp)"
        }

        val isReady = true

        return DeviceInfo(
            connectionType = connType,
            wifiSsid = ssid ?: "HKMTECH (Hotspot)",
            printerName = "EPSON L3250 Series",
            modelNumber = "L3250",
            port = portStr,
            isReady = isReady,
            scannerName = "EPSON L3250 (PC Bridge & Flatbed)",
            scannerOnline = isReady,
            isWifiDirect = isWifiDirect,
            printerIp = detectedPrinterIp,
            bridgeIp = detectedBridgeIp
        )
    }
}
