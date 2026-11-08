package com.hkmtech.cardprinter

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request Camera Permission if needed
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 101)
        }

        setContent {
            HkmTheme {
                MainAppScreen()
            }
        }
    }
}

@Composable
fun HkmTheme(content: @Composable () -> Unit) {
    val darkColors = darkColorScheme(
        primary = Color(0xFF0284C7),
        onPrimary = Color.White,
        secondary = Color(0xFF10B981),
        onSecondary = Color.White,
        background = Color(0xFF0A0F1D),
        surface = Color(0xFF111C33),
        surfaceVariant = Color(0xFF1A2744),
        onSurface = Color(0xFFF8FAFC),
        onSurfaceVariant = Color(0xFF94A3B8)
    )

    MaterialTheme(
        colorScheme = darkColors,
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen() {
    val context = LocalContext.current

    var selectedTab by remember { mutableStateOf(0) }
    var showConnectionModal by remember { mutableStateOf(true) }
    var deviceInfo by remember { mutableStateOf(DeviceDetector.detect(context)) }

    // Front Card State
    var frontBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var frontRotation by remember { mutableStateOf(0f) }

    // Back Card State
    var backBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var backRotation by remember { mutableStateOf(0f) }

    // Dimensions
    var topMarginMm by remember { mutableStateOf(48f) }
    var bottomMarginMm by remember { mutableStateOf(180f) }
    var cardWidthMm by remember { mutableStateOf(85.6f) }
    var cardHeightMm by remember { mutableStateOf(54.0f) }

    // Multi-page Document Scanner State
    val docPages = remember { mutableStateListOf<Bitmap>() }

    // Photo Studio State
    var passportBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var photoGridCount by remember { mutableStateOf(16) }

    // Camera Temp URI generator
    fun createTempImageUri(): Pair<Uri, File> {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(context.cacheDir, "CAPTURE_${timeStamp}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Pair(uri, file)
    }

    var pendingCameraFile by remember { mutableStateOf<File?>(null) }

    // Image Picker & Camera Launchers for Front Card
    val frontGalleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.openInputStream(it)?.use { stream ->
                    frontBitmap = BitmapFactory.decodeStream(stream)
                    frontRotation = 0f
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error loading image: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val frontCameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && pendingCameraFile != null && pendingCameraFile!!.exists()) {
            frontBitmap = BitmapFactory.decodeFile(pendingCameraFile!!.absolutePath)
            frontRotation = 0f
        }
    }

    // Image Picker & Camera Launchers for Back Card
    val backGalleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.openInputStream(it)?.use { stream ->
                    backBitmap = BitmapFactory.decodeStream(stream)
                    backRotation = 0f
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error loading image: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val backCameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && pendingCameraFile != null && pendingCameraFile!!.exists()) {
            backBitmap = BitmapFactory.decodeFile(pendingCameraFile!!.absolutePath)
            backRotation = 0f
        }
    }

    // Launchers for Multi-page Documents
    val docGalleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.openInputStream(it)?.use { stream ->
                    val bmp = BitmapFactory.decodeStream(stream)
                    bmp?.let { docPages.add(it) }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val docCameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && pendingCameraFile != null && pendingCameraFile!!.exists()) {
            val bmp = BitmapFactory.decodeFile(pendingCameraFile!!.absolutePath)
            bmp?.let { docPages.add(it) }
        }
    }

    // Launcher for Passport Photo
    val photoGalleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.openInputStream(it)?.use { stream ->
                    passportBitmap = BitmapFactory.decodeStream(stream)
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // CONNECTION SETUP DIALOG (Appears on Launch)
    if (showConnectionModal) {
        Dialog(onDismissRequest = { showConnectionModal = false }) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = BorderStroke(1.5.dp, Color(0xFF38BDF8).copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF0284C7)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("📡", fontSize = 22.sp)
                        }
                        Column {
                            Text(
                                "Device & Printer Setup",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                "Hardware connection discovery",
                                fontSize = 12.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Tile 1: Connection Interface
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF1E293B),
                        border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.2f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(if (deviceInfo.connectionType == "Wi-Fi") "📶" else "🔌", fontSize = 20.sp)
                                Column {
                                    Text("CONNECTION INTERFACE", fontSize = 10.sp, color = Color(0xFF94A3B8), fontWeight = FontWeight.Bold)
                                    Text(
                                        if (deviceInfo.connectionType == "Wi-Fi") "Wi-Fi: ${deviceInfo.wifiSsid ?: "Connected"}" else "USB Direct Cable",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                            Text(
                                "Connected",
                                fontSize = 11.sp,
                                color = Color(0xFF34D399),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(Color(0xFF064E3B))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Tile 2: Printer Name & Model
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF1E293B),
                        border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.2f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text("🖨️", fontSize = 20.sp)
                                Column {
                                    Text("PRINTER NAME & MODEL", fontSize = 10.sp, color = Color(0xFF94A3B8), fontWeight = FontWeight.Bold)
                                    Text(deviceInfo.printerName, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    Text("Model: ${deviceInfo.modelNumber} • ${deviceInfo.port}", fontSize = 11.sp, color = Color(0xFF64748B))
                                }
                            }
                            Text(
                                "Ready",
                                fontSize = 11.sp,
                                color = Color(0xFF34D399),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(Color(0xFF064E3B))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Proceed Button
                    Button(
                        onClick = { showConnectionModal = false },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Text(
                            "🚀 Proceed to Studio Activities →",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = { deviceInfo = DeviceDetector.detect(context) },
                        border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🔄 Re-Detect Devices", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0E172A))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("🖨️", fontSize = 20.sp)
                        Column {
                            Text(
                                "HKMTECH Card Printer",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                            Text(
                                "Native High-Resolution Spooler",
                                fontSize = 10.sp,
                                color = Color(0xFF38BDF8)
                            )
                        }
                    }

                    // Clickable Status Pill
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = Color(0xFF1E293B),
                        border = BorderStroke(1.dp, Color(0xFF10B981)),
                        modifier = Modifier.clickable {
                            deviceInfo = DeviceDetector.detect(context)
                            showConnectionModal = true
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(if (deviceInfo.connectionType == "Wi-Fi") "📶" else "🔌", fontSize = 12.sp)
                            Text(
                                "${deviceInfo.modelNumber} Ready",
                                fontSize = 11.sp,
                                color = Color(0xFF34D399),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF090E1A),
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = (selectedTab == 0),
                    onClick = { selectedTab = 0 },
                    icon = { Text("🪪", fontSize = 20.sp) },
                    label = { Text("Cards 2-in-1", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                )
                NavigationBarItem(
                    selected = (selectedTab == 1),
                    onClick = { selectedTab = 1 },
                    icon = { Text("📄", fontSize = 20.sp) },
                    label = { Text("Multi-Doc", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                )
                NavigationBarItem(
                    selected = (selectedTab == 2),
                    onClick = { selectedTab = 2 },
                    icon = { Text("🖼️", fontSize = 20.sp) },
                    label = { Text("Photo Grid", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFF0A0F1D))
        ) {
            when (selectedTab) {
                0 -> {
                    // TAB 0: CARDS 2-IN-1 STUDIO
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // 1. FRONT CARD SLOT
                        item {
                            CardSlotView(
                                title = "Top Half (Front Side)",
                                badgeColor = Color(0xFF38BDF8),
                                bitmap = frontBitmap,
                                rotation = frontRotation,
                                onCameraClick = {
                                    val (uri, file) = createTempImageUri()
                                    pendingCameraFile = file
                                    frontCameraLauncher.launch(uri)
                                },
                                onGalleryClick = { frontGalleryLauncher.launch("image/*") },
                                onRotateClick = { frontRotation = (frontRotation + 90f) % 360f },
                                onClearClick = { frontBitmap = null }
                            )
                        }

                        // 2. BACK CARD SLOT
                        item {
                            CardSlotView(
                                title = "Bottom Half (Back Side)",
                                badgeColor = Color(0xFF10B981),
                                bitmap = backBitmap,
                                rotation = backRotation,
                                onCameraClick = {
                                    val (uri, file) = createTempImageUri()
                                    pendingCameraFile = file
                                    backCameraLauncher.launch(uri)
                                },
                                onGalleryClick = { backGalleryLauncher.launch("image/*") },
                                onRotateClick = { backRotation = (backRotation + 90f) % 360f },
                                onClearClick = { backBitmap = null }
                            )
                        }

                        // 3. LIVE SIMULATED A4 SHEET PREVIEW
                        item {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF111C33)),
                                border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.2f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("📄 LIVE A4 SHEET SIMULATION", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
                                        Text("ISO 210 × 297 mm", fontSize = 11.sp, color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))

                                    // Canvas Sheet Simulation
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(280.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF060911)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Canvas(
                                            modifier = Modifier
                                                .width(180.dp)
                                                .height(254.dp) // 210 x 297 mm aspect ratio
                                                .shadow(12.dp, RoundedCornerShape(2.dp))
                                                .background(Color.White)
                                        ) {
                                            val w = size.width
                                            val h = size.height

                                            // Draw center fold / cut line at 50%
                                            drawLine(
                                                color = Color(0xFF94A3B8),
                                                start = Offset(0f, h * 0.5f),
                                                end = Offset(w, h * 0.5f),
                                                strokeWidth = 2f,
                                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                                            )

                                            // Draw Front Card Box
                                            val cardW = w * (cardWidthMm / 210f)
                                            val cardH = h * (cardHeightMm / 297f)
                                            val cardX = (w - cardW) / 2f
                                            val topY = h * (topMarginMm / 297f)

                                            drawRoundRect(
                                                color = if (frontBitmap != null) Color(0xFF0284C7) else Color(0xFFE2E8F0),
                                                topLeft = Offset(cardX, topY),
                                                size = Size(cardW, cardH),
                                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
                                            )

                                            // Draw Back Card Box
                                            val bottomY = h * (bottomMarginMm / 297f)
                                            drawRoundRect(
                                                color = if (backBitmap != null) Color(0xFF10B981) else Color(0xFFE2E8F0),
                                                topLeft = Offset(cardX, bottomY),
                                                size = Size(cardW, cardH),
                                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))

                                    // Margin Sliders
                                    Text("Top Half Margin: ${topMarginMm.toInt()} mm", fontSize = 12.sp, color = Color.White)
                                    Slider(
                                        value = topMarginMm,
                                        onValueChange = { topMarginMm = it },
                                        valueRange = 20f..90f
                                    )

                                    Text("Bottom Half Margin: ${bottomMarginMm.toInt()} mm", fontSize = 12.sp, color = Color.White)
                                    Slider(
                                        value = bottomMarginMm,
                                        onValueChange = { bottomMarginMm = it },
                                        valueRange = 140f..230f
                                    )
                                }
                            }
                        }

                        // 4. ACTION BAR (PRINT & SAVE PDF)
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        if (frontBitmap == null && backBitmap == null) {
                                            Toast.makeText(context, "Please upload at least one card side first", Toast.LENGTH_SHORT).show()
                                        } else {
                                            val pdfFile = NativePdfEngine.createCardSheetPdf(
                                                context = context,
                                                frontBitmap = frontBitmap,
                                                backBitmap = backBitmap,
                                                frontRotation = frontRotation,
                                                backRotation = backRotation,
                                                topMarginMm = topMarginMm,
                                                bottomMarginMm = bottomMarginMm
                                            )
                                            NativePdfEngine.printPdf(context, pdfFile, "HKM_Aadhaar_Cards")
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(54.dp)
                                ) {
                                    Text("🖨️ Print to Epson L3250 (1:1 Exact Scale)", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }

                                OutlinedButton(
                                    onClick = {
                                        if (frontBitmap == null && backBitmap == null) {
                                            Toast.makeText(context, "Please upload at least one card side first", Toast.LENGTH_SHORT).show()
                                        } else {
                                            val pdfFile = NativePdfEngine.createCardSheetPdf(
                                                context = context,
                                                frontBitmap = frontBitmap,
                                                backBitmap = backBitmap,
                                                frontRotation = frontRotation,
                                                backRotation = backRotation,
                                                topMarginMm = topMarginMm,
                                                bottomMarginMm = bottomMarginMm
                                            )
                                            NativePdfEngine.sharePdf(context, pdfFile)
                                        }
                                    },
                                    border = BorderStroke(1.dp, Color(0xFF38BDF8)),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(50.dp)
                                ) {
                                    Text("📥 Share / Save 300 DPI PDF", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                                }
                            }
                        }
                    }
                }

                1 -> {
                    // TAB 1: MULTI-PAGE DOCUMENT SCANNER
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        item {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF111C33)),
                                border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.2f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("📄 MULTI-PAGE DOCUMENT SCANNER", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text("Pages scanned: ${docPages.size}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)

                                    Spacer(modifier = Modifier.height(14.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                val (uri, file) = createTempImageUri()
                                                pendingCameraFile = file
                                                docCameraLauncher.launch(uri)
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("📸 Camera", fontWeight = FontWeight.Bold)
                                        }

                                        OutlinedButton(
                                            onClick = { docGalleryLauncher.launch("image/*") },
                                            border = BorderStroke(1.dp, Color(0xFF38BDF8)),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("📁 Gallery", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }

                        // Thumbnails of scanned pages
                        if (docPages.isNotEmpty()) {
                            item {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    items(docPages.size) { idx ->
                                        Box(
                                            modifier = Modifier
                                                .width(100.dp)
                                                .height(140.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0xFF1E293B))
                                                .border(BorderStroke(1.dp, Color(0xFF38BDF8)))
                                        ) {
                                            androidx.compose.foundation.Image(
                                                bitmap = docPages[idx].asImageBitmap(),
                                                contentDescription = "Page ${idx + 1}",
                                                modifier = Modifier.fillMaxSize()
                                            )
                                            Text(
                                                "Page ${idx + 1}",
                                                fontSize = 10.sp,
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier
                                                    .align(Alignment.BottomCenter)
                                                    .background(Color.Black.copy(alpha = 0.7f))
                                                    .fillMaxWidth()
                                                    .padding(2.dp),
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }
                            }

                            item {
                                Button(
                                    onClick = {
                                        val pdf = NativePdfEngine.createMultiPageDocumentPdf(context, docPages)
                                        NativePdfEngine.printPdf(context, pdf, "MultiPage_Document")
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp)
                                ) {
                                    Text("🖨️ Print All ${docPages.size} Pages", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            item {
                                OutlinedButton(
                                    onClick = {
                                        val pdf = NativePdfEngine.createMultiPageDocumentPdf(context, docPages)
                                        NativePdfEngine.sharePdf(context, pdf)
                                    },
                                    border = BorderStroke(1.dp, Color(0xFF38BDF8)),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("📥 Export Multi-Page PDF", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                2 -> {
                    // TAB 2: PASSPORT PHOTO STUDIO
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        item {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF111C33)),
                                border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.2f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("🖼️ PASSPORT PHOTO STUDIO", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
                                    Spacer(modifier = Modifier.height(10.dp))

                                    if (passportBitmap == null) {
                                        Button(
                                            onClick = { photoGalleryLauncher.launch("image/*") },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("📁 Select Portrait Photo", fontWeight = FontWeight.Bold)
                                        }
                                    } else {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            androidx.compose.foundation.Image(
                                                bitmap = passportBitmap!!.asImageBitmap(),
                                                contentDescription = "Passport Photo",
                                                modifier = Modifier
                                                    .size(70.dp, 90.dp)
                                                    .clip(RoundedCornerShape(6.dp))
                                            )
                                            Column {
                                                Text("Standard Indian Passport (35 × 45 mm)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                                Text("Grid Layout on A4", fontSize = 11.sp, color = Color(0xFF94A3B8))
                                                TextButton(onClick = { passportBitmap = null }) {
                                                    Text("Change Photo", color = Color(0xFFEF4444), fontSize = 12.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Text("Grid Size: $photoGridCount Photos on A4", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(8, 12, 16, 24, 30).forEach { count ->
                                    FilterChip(
                                        selected = (photoGridCount == count),
                                        onClick = { photoGridCount = count },
                                        label = { Text("$count", fontWeight = FontWeight.Bold) }
                                    )
                                }
                            }
                        }

                        if (passportBitmap != null) {
                            item {
                                Button(
                                    onClick = {
                                        val list = List(photoGridCount) { passportBitmap!! }
                                        val pdf = NativePdfEngine.createMultiPageDocumentPdf(context, list)
                                        NativePdfEngine.printPdf(context, pdf, "Passport_Photos_${photoGridCount}")
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp)
                                ) {
                                    Text("🖨️ Print $photoGridCount Passport Photos", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CardSlotView(
    title: String,
    badgeColor: Color,
    bitmap: Bitmap?,
    rotation: Float,
    onCameraClick: () -> Unit,
    onGalleryClick: () -> Unit,
    onRotateClick: () -> Unit,
    onClearClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111C33)),
        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = badgeColor
                )
                if (bitmap != null) {
                    Text(
                        "85.6 × 54 mm",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8),
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (bitmap == null) {
                // Empty Upload Box
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF1E293B).copy(alpha = 0.5f),
                    border = BorderStroke(1.5.dp, Color(0xFF334155)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = onCameraClick,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("📸 Camera", fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = onGalleryClick,
                            border = BorderStroke(1.dp, Color(0xFF38BDF8)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("📁 Gallery", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                // Display Card Image & Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val rotated = remember(bitmap, rotation) {
                        NativePdfEngine.rotateBitmap(bitmap, rotation)
                    }

                    androidx.compose.foundation.Image(
                        bitmap = rotated.asImageBitmap(),
                        contentDescription = title,
                        modifier = Modifier
                            .size(110.dp, 70.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .border(BorderStroke(1.dp, badgeColor))
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = onRotateClick,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("🔄 90°", fontSize = 11.sp)
                            }
                            OutlinedButton(
                                onClick = onClearClick,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444))
                            ) {
                                Text("❌ Clear", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
