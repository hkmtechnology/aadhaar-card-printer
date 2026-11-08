package com.hkmtech.cardprinter

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
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
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

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
                // Check if camera captured file exists and has size
                val file = File(cameraImageUri?.path ?: "")
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
    }

    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.useWideViewPort = false
        settings.loadWithOverviewMode = false
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.setSupportZoom(false)
        settings.textZoom = 100
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.webViewClient = object : WebViewClient() {}

        webView.webChromeClient = object : WebChromeClient() {
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
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir = cacheDir
        return File.createTempFile("SCAN_${timeStamp}_", ".jpg", storageDir)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    inner class AndroidBridge(private val context: Context) {

        @JavascriptInterface
        fun isAndroidApp(): Boolean = true

        @JavascriptInterface
        fun showToast(message: String) {
            runOnUiThread {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun printPdf(base64Data: String, jobName: String) {
            runOnUiThread {
                try {
                    val cleanBase64 = if (base64Data.contains(",")) base64Data.split(",")[1] else base64Data
                    val pdfBytes = Base64.decode(cleanBase64, Base64.DEFAULT)

                    val file = File(cacheDir, "${jobName.replace(" ", "_")}.pdf")
                    FileOutputStream(file).use { it.write(pdfBytes) }

                    val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
                    val adapter = PdfPrintDocumentAdapter(file)
                    val attributes = PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                        .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                        .build()

                    printManager.print(jobName, adapter, attributes)
                } catch (e: Exception) {
                    Toast.makeText(context, "Print error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface
        fun sharePdf(base64Data: String, fileName: String) {
            runOnUiThread {
                try {
                    val cleanBase64 = if (base64Data.contains(",")) base64Data.split(",")[1] else base64Data
                    val pdfBytes = Base64.decode(cleanBase64, Base64.DEFAULT)

                    val file = File(cacheDir, fileName)
                    FileOutputStream(file).use { it.write(pdfBytes) }

                    val uri = FileProvider.getUriForFile(
                        this@MainActivity,
                        "${applicationContext.packageName}.fileprovider",
                        file
                    )

                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }

                    startActivity(Intent.createChooser(intent, "Share Document via..."))
                } catch (e: Exception) {
                    Toast.makeText(context, "Share error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
