package com.hkmtech.cardprinter

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JsResult
import android.os.Parcelable
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    companion object {
        var currentRequestedTab: String = "upload"
    }

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "Camera permission needed for scanning photos", Toast.LENGTH_SHORT).show()
        }
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (filePathCallback == null) return@registerForActivityResult

        var results: Array<Uri>? = null
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            if (data?.data != null) {
                results = arrayOf(data.data!!)
            } else if (cameraImageUri != null) {
                results = arrayOf(cameraImageUri!!)
            } else if (data?.clipData != null) {
                val clip = data.clipData!!
                val count = clip.itemCount
                val uris = mutableListOf<Uri>()
                for (i in 0 until count) {
                    uris.add(clip.getItemAt(i).uri)
                }
                results = uris.toTypedArray()
            }
        }

        filePathCallback?.onReceiveValue(results)
        filePathCallback = null
    }

    
    private val tabReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val tab = intent?.getStringExtra("tab")
            if (tab != null) {
                runOnUiThread {
                    webView.evaluateJavascript("if (typeof switchMobileTab === 'function') switchMobileTab('$tab');", null)
                }
            }
            val mode = intent?.getStringExtra("mode")
            if (mode != null) {
                runOnUiThread {
                    webView.evaluateJavascript("if (typeof switchAppMode === 'function') switchAppMode('$mode');", null)
                }
            }
            val evalJs = intent?.getStringExtra("eval_js")
            if (evalJs != null) {
                runOnUiThread {
                    webView.evaluateJavascript(evalJs, null)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request Camera Permission if needed
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        webView = WebView(this)
        setContentView(webView)

        setupWebView()
        webView.loadUrl("file:///android_asset/index.html")
        val filter = android.content.IntentFilter().apply {
            addAction("com.hkmtech.cardprinter.SWITCH_TAB")
            addAction("com.hkmtech.cardprinter.SWITCH_MODE")
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(tabReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(tabReceiver, filter)
        }
        handleTabIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTabIntent(intent)
    }

    private fun handleTabIntent(intent: Intent?) {
        val tab = intent?.getStringExtra("tab")
        if (tab != null) {
            currentRequestedTab = tab
            runOnUiThread {
                webView.evaluateJavascript("if (typeof switchMobileTab === 'function') switchMobileTab('$tab');", null)
            }
        }
        val mode = intent?.getStringExtra("mode")
        if (mode != null) {
            runOnUiThread {
                webView.evaluateJavascript("if (typeof switchAppMode === 'function') switchAppMode('$mode');", null)
            }
        }
        val evalJs = intent?.getStringExtra("eval_js")
        if (evalJs != null) {
            runOnUiThread {
                webView.evaluateJavascript(evalJs, null)
            }
        }
    }

    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true

        // Crucial for cross-origin file:// access to PC bridge HTTP server
        settings.allowFileAccessFromFileURLs = true
        settings.allowUniversalAccessFromFileURLs = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        // Crucial for readable, finger-friendly mobile native UI
        settings.useWideViewPort = false
        settings.loadWithOverviewMode = false
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.setSupportZoom(false)
        settings.textZoom = 100
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                handleTabIntent(intent)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                Log.d("HKM_JS", "[${consoleMessage.messageLevel()}] ${consoleMessage.message()} (L${consoleMessage.lineNumber()})")
                return true
            }

            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("HKM Card Printer")
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result?.confirm() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("HKM Card Printer")
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result?.confirm() }
                    .setNegativeButton("Cancel") { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback

                // 1. Create intent for Camera capture
                val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                val photoFile = createImageFile()
                cameraImageUri = FileProvider.getUriForFile(
                    this@MainActivity,
                    "${applicationContext.packageName}.fileprovider",
                    photoFile
                )
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)

                // 2. Create intent for Gallery / File selection
                val contentSelectionIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                    if (params?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                }

                // 3. Create chooser combining Camera + Gallery
                val chooserIntent = Intent(Intent.ACTION_CHOOSER).apply {
                    putExtra(Intent.EXTRA_INTENT, contentSelectionIntent)
                    putExtra(Intent.EXTRA_TITLE, "Select Card or Document")
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf<Parcelable>(takePictureIntent))
                }

                fileChooserLauncher.launch(chooserIntent)
                return true
            }
        }

        // Bridge to receive JavaScript calls
        webView.addJavascriptInterface(AndroidBridge(this), "AndroidBridge")
    }

    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir = getExternalFilesDir(null) ?: filesDir
        return File.createTempFile("CARD_${timeStamp}_", ".jpg", storageDir)
    }

    
    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(tabReceiver)
        } catch (e: Exception) {}
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    /**
     * JavaScript Interface Bridge exposed to Web application as `window.AndroidBridge`
     */
    inner class AndroidBridge(private val context: Context) {

        @JavascriptInterface
        fun getInitialTab(): String = currentRequestedTab

        @JavascriptInterface
        fun isAndroidApp(): Boolean = true

        @JavascriptInterface
        fun showToast(message: String) {
            runOnUiThread {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun printCurrentPage() {
            runOnUiThread {
                try {
                    val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
                    val printAdapter = webView.createPrintDocumentAdapter("HKM_Card_Print")
                    val printAttributes = PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                        .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                        .build()
                    printManager.print("HKM_Card_Print", printAdapter, printAttributes)
                } catch (e: Exception) {
                    Toast.makeText(context, "Print error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface
        fun abortScan() {
            EpsonDirectScanner.abortCurrentScan()
        }

        @JavascriptInterface
        fun getPrinterIp(): String {
            val dev = DeviceDetector.detect(context)
            return dev.printerIp
        }

        @JavascriptInterface
        fun setPrinterIp(ip: String) {
            val clean = ip.trim().replace("http://", "").replace("https://", "").split(":")[0]
            val prefs = context.getSharedPreferences("hkm_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("printer_ip", clean).apply()
            DeviceDetector.setPrinterIp(clean)
        }

        @JavascriptInterface
        fun scanDirectOrBridge(side: String, autoCrop: Boolean, dpi: Int, docType: String = "id_card"): String {
            val dev = DeviceDetector.detect(context)
            val printerIp = dev.printerIp
            val candidates = DeviceDetector.getCandidateBridgeIps(context)

            Log.i("HKM_SCAN", "Initiating scan for side=$side, autoCrop=$autoCrop, dpi=$dpi, docType=$docType. Candidates: $candidates, printerIp: $printerIp")

            // 1. Fast check if PC Scanner Bridge is actively running on any candidate host
            for (bridgeIp in candidates) {
                if (DeviceDetector.isPortOpen(bridgeIp, 8767, 500)) {
                    Log.i("HKM_SCAN", "Found active PC scanner bridge at $bridgeIp:8767, executing scan...")
                    try {
                        val url = java.net.URL("http://$bridgeIp:8767/api/scan")
                        val conn = url.openConnection() as java.net.HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                        conn.connectTimeout = 3000
                        conn.readTimeout = 120000
                        conn.doOutput = true
                        conn.doInput = true

                        val payload = JSONObject().apply {
                            put("side", side)
                            put("autocrop", autoCrop)
                            put("dpi", dpi)
                            put("doc_type", docType)
                        }.toString()

                        conn.outputStream.use { os ->
                            os.write(payload.toByteArray(Charsets.UTF_8))
                        }

                        val code = conn.responseCode
                        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                        val response = stream.bufferedReader().use { it.readText() }
                        val json = JSONObject(response)
                        if (json.optBoolean("success", false) || json.optBoolean("aborted", false)) {
                            Log.i("HKM_SCAN", "Scan successfully returned from bridge at $bridgeIp:8767")
                            return response
                        }
                    } catch (e: Exception) {
                        Log.w("HKM_SCAN", "PC bridge scan attempt failed at $bridgeIp: ${e.message}")
                    }
                }
            }

                        // 1b. Direct HTTP attempt to 127.0.0.1 (ADB Reverse) in case socket probe was restricted
            try {
                val url = java.net.URL("http://127.0.0.1:8767/api/scan")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.connectTimeout = 2500
                conn.readTimeout = 120000
                conn.doOutput = true
                conn.doInput = true

                val payload = JSONObject().apply {
                    put("side", side)
                    put("autocrop", autoCrop)
                    put("dpi", dpi)
                    put("doc_type", docType)
                }.toString()

                conn.outputStream.use { os ->
                    os.write(payload.toByteArray(Charsets.UTF_8))
                }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val response = stream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                if (json.optBoolean("success", false) || json.optBoolean("aborted", false)) {
                    Log.i("HKM_SCAN", "Scan successfully returned from 127.0.0.1:8767 direct fallback")
                    return response
                }
            } catch (e: Exception) {
                Log.d("HKM_SCAN", "127.0.0.1 direct fallback skipped: ${e.message}")
            }

            // 2. Direct Wi-Fi scan fallback
            Log.i("HKM_SCAN", "Running direct Wi-Fi scan to Epson printer at $printerIp...")
            try {
                val rawBytes = EpsonDirectScanner.scanViaEscl(printerIp, dpi)
                if (rawBytes != null && rawBytes.isNotEmpty()) {
                    Log.i("HKM_SCAN", "Direct scan succeeded, received ${rawBytes.size} bytes from Epson L3250")

                    if (autoCrop && docType != "a4") {
                        val cropRes = CardAutoCropper.processScannedImage(rawBytes, docType)
                        val resp = JSONObject().apply {
                            put("success", true)
                            put("image", cropRes.croppedImageDataUri)
                            put("raw_image", cropRes.rawImageDataUri)
                            put("cropped", cropRes.isCropped)
                            put("aspect", cropRes.aspect.toDouble())
                            put("ratio_type", cropRes.ratioType)
                            if (cropRes.bbox != null) {
                                put("bbox", org.json.JSONArray(cropRes.bbox))
                            }
                            put("source", "epson_direct_mobile")
                        }
                        return resp.toString()
                    } else {
                        val rawMime = if (rawBytes.size > 2 && rawBytes[0] == 0xFF.toByte() && rawBytes[1] == 0xD8.toByte()) "image/jpeg" else "image/png"
                        val b64 = android.util.Base64.encodeToString(rawBytes, android.util.Base64.NO_WRAP)
                        val dataUri = "data:$rawMime;base64,$b64"
                        val resp = JSONObject().apply {
                            put("success", true)
                            put("image", dataUri)
                            put("raw_image", dataUri)
                            put("cropped", false)
                            put("aspect", 0.707)
                            put("ratio_type", "a4")
                            put("source", "epson_direct_mobile")
                        }
                        return resp.toString()
                    }
                }
            } catch (e: Exception) {
                Log.e("HKM_SCAN", "Direct eSCL scan exception: ${e.message}", e)
            }

            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            val phoneIp = try {
                val ipInt = wm?.connectionInfo?.ipAddress ?: 0
                if (ipInt != 0) {
                    String.format(
                        java.util.Locale.US,
                        "%d.%d.%d.%d",
                        ipInt and 0xff,
                        (ipInt shr 8) and 0xff,
                        (ipInt shr 16) and 0xff,
                        (ipInt shr 24) and 0xff
                    )
                } else "Not Connected"
            } catch (_: Exception) { "Unknown" }

            return JSONObject().apply {
                put("success", false)
                put("error", "Scan Connection Error: Could not reach Epson Printer or PC Bridge.\n\n" +
                    "• USB Mode (Recommended): Keep USB cable plugged in. Enable USB Debugging on your phone. PC server will auto-connect.\n" +
                    "• Wi-Fi Mode: Phone Wi-Fi IP is $phoneIp. Connect phone to the same Wi-Fi router as your PC (PC IP: 192.168.31.156).\n" +
                    "• Epson Printer: Make sure Epson L3250 is ON and Start_App.bat (server.py) is running on your PC.")
            }.toString()
        }

        @JavascriptInterface
        fun getScannerBridgeIp(): String {
            val activeIp = DeviceDetector.findActiveBridgeIp(context)
            val prefs = context.getSharedPreferences("hkm_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("scanner_ip", activeIp).apply()
            return activeIp
        }

        @JavascriptInterface
        fun setScannerBridgeIp(ip: String) {
            val prefs = context.getSharedPreferences("hkm_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("scanner_ip", ip.trim()).apply()
        }

        @JavascriptInterface
        fun scanFromEpsonDirect(dpi: Int, docType: String): String? {
            val dev = DeviceDetector.detect(context)
            val rawBytes = EpsonDirectScanner.scanViaEscl(dev.printerIp, dpi) ?: return null
            val cropRes = CardAutoCropper.processScannedImage(rawBytes, docType)
            return cropRes.croppedImageDataUri
        }

        @JavascriptInterface
        fun checkPrinterDirectOnline(): Boolean {
            val (online, _) = EpsonDirectScanner.checkPrinterOnline(context)
            return online
        }

        @JavascriptInterface
        fun getDeviceConnectionInfo(): String {
            val json = JSONObject()
            try {
                json.put("isAndroid", true)
                val dev = DeviceDetector.detect(context)

                json.put("connection_type", dev.connectionType)
                json.put("wifi_ssid", dev.wifiSsid)
                json.put("printer_name", dev.printerName)
                json.put("model_number", dev.modelNumber)
                json.put("bridge_ip", getScannerBridgeIp())
                json.put("printer_ip", dev.printerIp)
                json.put("port", dev.port)
                json.put("printer_connection", dev.port)
                json.put("printer_status", if (dev.isReady) "Ready" else "Offline")
                json.put("scanner_name", dev.scannerName)
                json.put("scanner_online", dev.isReady)
                val statusText = if (dev.isReady) "Ready on Hotspot (${dev.printerIp})" else "Offline"
                json.put("scanner_message", "${dev.modelNumber} $statusText")
                json.put("is_wifi_direct", dev.isWifiDirect)
                json.put("is_usb", dev.connectionType.contains("USB", ignoreCase = true))
            } catch (e: Exception) {
                json.put("error", e.message)
            }
            return json.toString()
        }

        @JavascriptInterface
        fun printDirect(base64Data: String, copies: Int = 1, title: String = "HKM_Card_Print"): String {
            val candidates = DeviceDetector.getCandidateBridgeIps(context)
            val bridgeIps = mutableListOf("127.0.0.1")
            for (c in candidates) {
                if (!bridgeIps.contains(c)) bridgeIps.add(c)
            }

            for (ip in bridgeIps) {
                try {
                    val url = java.net.URL("http://$ip:8767/api/print_silent")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    conn.connectTimeout = 3000
                    conn.readTimeout = 45000
                    conn.doOutput = true
                    conn.doInput = true

                    val payload = JSONObject().apply {
                        put("image", base64Data)
                        put("copies", copies)
                        put("title", title)
                    }.toString()

                    conn.outputStream.use { os ->
                        os.write(payload.toByteArray(Charsets.UTF_8))
                    }

                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val response = stream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    if (json.optBoolean("success", false)) {
                        val msg = json.optString("message", "Document sent directly to Epson L3250!")
                        runOnUiThread {
                            Toast.makeText(context, "🖨️ $msg", Toast.LENGTH_LONG).show()
                        }
                        return response
                    }
                } catch (e: Exception) {
                    Log.w("HKM_PRINT", "Direct print attempt to $ip:8767 failed: ${e.message}")
                }
            }

            // Bridge not reachable: ask user if they want to fallback to Android System Print
            runOnUiThread {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Epson L3250 Direct Print")
                    .setMessage("Could not connect to PC Bridge for direct silent printing.\n\nMake sure the PC Bridge is connected via USB/Wi-Fi, or tap 'System Dialog' to use Android's print spooler.")
                    .setPositiveButton("System Dialog") { _, _ ->
                        printPdfSystem(base64Data, title)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            return JSONObject().apply {
                put("success", false)
                put("error", "PC Bridge not reachable")
            }.toString()
        }

        @JavascriptInterface
        fun printPdf(base64Data: String, jobName: String) {
            printDirect(base64Data, 1, jobName)
        }

        @JavascriptInterface
        fun printPdfSystem(base64Data: String, jobName: String) {
            runOnUiThread {
                try {
                    val pdfBytes = Base64.decode(base64Data, Base64.DEFAULT)
                    val pdfFile = File(context.cacheDir, "print_job_${System.currentTimeMillis()}.pdf")
                    FileOutputStream(pdfFile).use { it.write(pdfBytes) }

                    val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
                    val printAdapter = PdfPrintDocumentAdapter(pdfFile)
                    val printAttributes = PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                        .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                        .build()

                    printManager.print(jobName, printAdapter, printAttributes)
                } catch (e: Exception) {
                    Toast.makeText(context, "Print Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface
        fun savePdf(base64Data: String, fileName: String): Boolean {
            return saveFileToStorage(base64Data, fileName, "application/pdf", Environment.DIRECTORY_DOWNLOADS)
        }

        @JavascriptInterface
        fun saveImage(base64Data: String, fileName: String): Boolean {
            val mime = if (fileName.endsWith(".jpg", ignoreCase = true) || fileName.endsWith(".jpeg", ignoreCase = true)) "image/jpeg" else "image/png"
            return saveFileToStorage(base64Data, fileName, mime, Environment.DIRECTORY_PICTURES)
        }

        private fun saveFileToStorage(base64Data: String, fileName: String, mimeType: String, publicDir: String): Boolean {
            return try {
                val cleanB64 = if (base64Data.contains(",")) base64Data.substringAfter(",") else base64Data
                val bytes = Base64.decode(cleanB64, Base64.DEFAULT)
                var savedUri: Uri? = null

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        if (publicDir == Environment.DIRECTORY_PICTURES) {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/HKM_Cards")
                        } else {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        }
                    }
                    val targetUri = if (publicDir == Environment.DIRECTORY_PICTURES) {
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    } else {
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    }
                    savedUri = context.contentResolver.insert(targetUri, values)
                    savedUri?.let { uri ->
                        context.contentResolver.openOutputStream(uri)?.use { os ->
                            os.write(bytes)
                        }
                    }
                } else {
                    val dir = Environment.getExternalStoragePublicDirectory(publicDir)
                    val targetDir = if (publicDir == Environment.DIRECTORY_PICTURES) File(dir, "HKM_Cards") else dir
                    if (!targetDir.exists()) targetDir.mkdirs()
                    val destFile = File(targetDir, fileName)
                    FileOutputStream(destFile).use { it.write(bytes) }
                    savedUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destFile)
                    MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), arrayOf(mimeType), null)
                }

                runOnUiThread {
                    val folderName = if (publicDir == Environment.DIRECTORY_PICTURES) "Pictures/HKM_Cards" else "Downloads"
                    Toast.makeText(context, "âœ… Saved $fileName to $folderName", Toast.LENGTH_LONG).show()

                    savedUri?.let { uri ->
                        try {
                            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, mimeType)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(Intent.createChooser(viewIntent, "Open $fileName"))
                        } catch (e: Exception) {
                            Log.w("HKM_SAVE", "Chooser error: ${e.message}")
                        }
                    }
                }
                true
            } catch (e: Exception) {
                Log.e("HKM_SAVE", "Save error: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(context, "âŒ Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
                false
            }
        }

        @JavascriptInterface
        fun sharePdf(base64Data: String, fileName: String) {
            runOnUiThread {
                try {
                    val pdfBytes = Base64.decode(base64Data, Base64.DEFAULT)
                    val outputDir = File(context.getExternalFilesDir(null), "documents")
                    if (!outputDir.exists()) outputDir.mkdirs()
                    val pdfFile = File(outputDir, fileName)
                    FileOutputStream(pdfFile).use { it.write(pdfBytes) }

                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        pdfFile
                    )

                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share PDF Document"))
                } catch (e: Exception) {
                    Toast.makeText(context, "Share Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
