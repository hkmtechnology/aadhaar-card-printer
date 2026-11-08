package com.hkmtech.cardprinter

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.print.PrintAttributes
import android.print.PrintManager
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

object NativePdfEngine {

    const val MM_TO_PT = 72.0f / 25.4f // 2.8346456 points per mm
    const val A4_WIDTH_PT = 595 // 210 mm
    const val A4_HEIGHT_PT = 842 // 297 mm

    fun rotateBitmap(source: Bitmap, degrees: Float): Bitmap {
        if (degrees % 360 == 0f) return source
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    fun cropBitmap(source: Bitmap, cropRectNorm: RectF): Bitmap {
        val left = (cropRectNorm.left.coerceIn(0f, 1f) * source.width).toInt()
        val top = (cropRectNorm.top.coerceIn(0f, 1f) * source.height).toInt()
        val right = (cropRectNorm.right.coerceIn(0f, 1f) * source.width).toInt()
        val bottom = (cropRectNorm.bottom.coerceIn(0f, 1f) * source.height).toInt()

        val width = (right - left).coerceAtLeast(10)
        val height = (bottom - top).coerceAtLeast(10)

        val safeW = width.coerceAtMost(source.width - left)
        val safeH = height.coerceAtMost(source.height - top)

        return Bitmap.createBitmap(source, left, top, safeW, safeH)
    }

    fun createCardSheetPdf(
        context: Context,
        frontBitmap: Bitmap?,
        backBitmap: Bitmap?,
        frontRotation: Float = 0f,
        backRotation: Float = 0f,
        cardWidthMm: Float = 85.6f,
        cardHeightMm: Float = 54.0f,
        topMarginMm: Float = 48.0f,
        bottomMarginMm: Float = 180.0f,
        cornerCurveMm: Float = 3.18f,
        showCutLine: Boolean = true
    ): File {
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(A4_WIDTH_PT, A4_HEIGHT_PT, 1).create()
        val page = document.startPage(pageInfo)
        val canvas: Canvas = page.canvas

        // Paint white background
        canvas.drawColor(Color.WHITE)

        val cardWPt = cardWidthMm * MM_TO_PT
        val cardHPt = cardHeightMm * MM_TO_PT
        val centerXPt = (A4_WIDTH_PT - cardWPt) / 2f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // 1. Draw Front Card
        frontBitmap?.let { orig ->
            val rotated = rotateBitmap(orig, frontRotation)
            val topYPt = topMarginMm * MM_TO_PT
            val dstRect = RectF(centerXPt, topYPt, centerXPt + cardWPt, topYPt + cardHPt)
            canvas.drawBitmap(rotated, null, dstRect, paint)

            // Optional corner outline
            if (cornerCurveMm > 0) {
                val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#334155")
                    style = Paint.Style.STROKE
                    strokeWidth = 0.5f
                }
                val rPt = cornerCurveMm * MM_TO_PT
                canvas.drawRoundRect(dstRect, rPt, rPt, borderPaint)
            }
        }

        // 2. Center Cut / Fold Guide Line (148.5 mm)
        if (showCutLine) {
            val cutLineY = 148.5f * MM_TO_PT
            val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#94A3B8")
                strokeWidth = 0.6f
                pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
            }
            canvas.drawLine(30f, cutLineY, A4_WIDTH_PT - 30f, cutLineY, linePaint)
        }

        // 3. Draw Back Card
        backBitmap?.let { orig ->
            val rotated = rotateBitmap(orig, backRotation)
            val bottomYPt = bottomMarginMm * MM_TO_PT
            val dstRect = RectF(centerXPt, bottomYPt, centerXPt + cardWPt, bottomYPt + cardHPt)
            canvas.drawBitmap(rotated, null, dstRect, paint)

            if (cornerCurveMm > 0) {
                val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#334155")
                    style = Paint.Style.STROKE
                    strokeWidth = 0.5f
                }
                val rPt = cornerCurveMm * MM_TO_PT
                canvas.drawRoundRect(dstRect, rPt, rPt, borderPaint)
            }
        }

        document.finishPage(page)

        val outFile = File(context.cacheDir, "Aadhaar_Cards_Print.pdf")
        FileOutputStream(outFile).use { out ->
            document.writeTo(out)
        }
        document.close()
        return outFile
    }

    fun createMultiPageDocumentPdf(
        context: Context,
        pageBitmaps: List<Bitmap>
    ): File {
        val document = PdfDocument()

        for (i in pageBitmaps.indices) {
            val pageInfo = PdfDocument.PageInfo.Builder(A4_WIDTH_PT, A4_HEIGHT_PT, i + 1).create()
            val page = document.startPage(pageInfo)
            val canvas = page.canvas
            canvas.drawColor(Color.WHITE)

            val bmp = pageBitmaps[i]
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

            // Scale to fit A4 preserving aspect ratio
            val scale = minOf(
                A4_WIDTH_PT.toFloat() / bmp.width,
                A4_HEIGHT_PT.toFloat() / bmp.height
            )
            val dstW = bmp.width * scale
            val dstH = bmp.height * scale
            val dstX = (A4_WIDTH_PT - dstW) / 2f
            val dstY = (A4_HEIGHT_PT - dstH) / 2f

            canvas.drawBitmap(bmp, null, RectF(dstX, dstY, dstX + dstW, dstY + dstH), paint)
            document.finishPage(page)
        }

        val outFile = File(context.cacheDir, "MultiPage_Document_${pageBitmaps.size}_Pages.pdf")
        FileOutputStream(outFile).use { out ->
            document.writeTo(out)
        }
        document.close()
        return outFile
    }

    fun printPdf(context: Context, pdfFile: File, jobName: String = "HKMTECH_Card_Print") {
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        val adapter = PdfPrintDocumentAdapter(pdfFile)
        val attributes = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .build()
        printManager.print(jobName, adapter, attributes)
    }

    fun sharePdf(context: Context, pdfFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            pdfFile
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share PDF Document"))
    }
}
