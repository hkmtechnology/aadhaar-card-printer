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
    val scannerOnline: Boolean
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

        // 2. Check USB OTG
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
            isWifi -> "Wi-Fi"
            hasUsbPrinter -> "USB"
            isCellular -> "Mobile Data"
            else -> "Local Mode"
        }

        val portStr = when {
            isWifi -> "Wi-Fi Direct / Local Network"
            hasUsbPrinter -> "USB OTG Port"
            else -> "Android Print Spooler"
        }

        return DeviceInfo(
            connectionType = connType,
            wifiSsid = if (isWifi) (ssid ?: "Connected to Wi-Fi") else null,
            printerName = "EPSON L3250 Series",
            modelNumber = "L3250",
            port = portStr,
            isReady = true,
            scannerName = "EPSON L3250 / Mobile HD Camera",
            scannerOnline = true
        )
    }
}
