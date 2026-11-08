package com.hkmtech.cardprinter

import android.content.Context
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager

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
    val printerIp: String = "192.168.192.168"
)

object DeviceDetector {

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

        // 2. Check if connected to Epson Wi-Fi Direct
        val ssidUpper = (ssid ?: "").uppercase()
        val isDirectSsid = ssidUpper.contains("DIRECT") && (ssidUpper.contains("L3250") || ssidUpper.contains("EPSON"))
        val isWifiDirect = isDirectSsid
        val detectedIp = "192.168.31.223"

        // 3. Check USB OTG
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
            isWifiDirect -> "Wi-Fi Direct"
            isWifi -> "Wi-Fi"
            hasUsbPrinter -> "USB"
            isCellular -> "Mobile Data"
            else -> "Local Mode"
        }

        val portStr = when {
            isWifiDirect -> "Wi-Fi Direct ($detectedIp)"
            isWifi -> "Wi-Fi Network (${ssid ?: "Connected"})"
            hasUsbPrinter -> "USB OTG Port"
            else -> "Android Print Spooler"
        }

        val isReady = isWifiDirect || isWifi || hasUsbPrinter

        return DeviceInfo(
            connectionType = connType,
            wifiSsid = if (isWifi || isWifiDirect) (ssid ?: "DIRECT-Epson-L3250") else null,
            printerName = "EPSON L3250 Series",
            modelNumber = "L3250",
            port = portStr,
            isReady = isReady,
            scannerName = if (isWifiDirect) "EPSON L3250 Wi-Fi Direct Scanner" else "EPSON L3250 Flatbed / Camera HD",
            scannerOnline = isReady,
            isWifiDirect = isWifiDirect,
            printerIp = detectedIp
        )
    }
}
