package com.hkmtech.cardprinter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class CropResult(
    val success: Boolean,
    val croppedImageDataUri: String,
    val rawImageDataUri: String,
    val isCropped: Boolean,
    val aspect: Float,
    val ratioType: String,
    val bbox: IntArray?
)

object CardAutoCropper {

    private const val TAG = "HKM_CROPPER"

    fun processScannedImage(rawBytes: ByteArray, docType: String = "id_card"): CropResult {
        val rawMime = if (rawBytes.size > 2 && rawBytes[0] == 0xFF.toByte() && rawBytes[1] == 0xD8.toByte()) "image/jpeg" else "image/png"
        val rawDataUri = "data:$rawMime;base64,${Base64.encodeToString(rawBytes, Base64.NO_WRAP)}"

        // If docType is full a4 page, keep full scan
        if (docType == "a4") {
            return CropResult(
                success = true,
                croppedImageDataUri = rawDataUri,
                rawImageDataUri = rawDataUri,
                isCropped = false,
                aspect = 0.707f,
                ratioType = "a4",
                bbox = null
            )
        }

        try {
            val originalBmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return CropResult(
                success = true,
                croppedImageDataUri = rawDataUri,
                rawImageDataUri = rawDataUri,
                isCropped = false,
                aspect = 1.585f,
                ratioType = "standard",
                bbox = null
            )

            val origW = originalBmp.width
            val origH = originalBmp.height

            // Downsample for high-speed analysis (< 30ms on mobile CPU)
            val maxDim = 400
            val scale = maxDim.toFloat() / max(origW, origH)
            val sampleW = (origW * scale).toInt().coerceAtLeast(50)
            val sampleH = (origH * scale).toInt().coerceAtLeast(50)

            val sampleBmp = Bitmap.createScaledBitmap(originalBmp, sampleW, sampleH, true)
            val pixels = IntArray(sampleW * sampleH)
            sampleBmp.getPixels(pixels, 0, sampleW, 0, 0, sampleW, sampleH)
            sampleBmp.recycle()

            // 1. Sample outer corners to determine flatbed scanner background (white lid)
            val cornerSizeW = max(2, (sampleW * 0.05f).toInt())
            val cornerSizeH = max(2, (sampleH * 0.05f).toInt())
            var bgSumR = 0L
            var bgSumG = 0L
            var bgSumB = 0L
            var bgCount = 0

            for (y in 0 until sampleH) {
                for (x in 0 until sampleW) {
                    val inCorner = (x < cornerSizeW || x >= sampleW - cornerSizeW) &&
                                   (y < cornerSizeH || y >= sampleH - cornerSizeH)
                    if (inCorner) {
                        val p = pixels[y * sampleW + x]
                        bgSumR += (p shr 16) and 0xff
                        bgSumG += (p shr 8) and 0xff
                        bgSumB += p and 0xff
                        bgCount++
                    }
                }
            }

            val bgR = if (bgCount > 0) (bgSumR / bgCount).toInt() else 245
            val bgG = if (bgCount > 0) (bgSumG / bgCount).toInt() else 245
            val bgB = if (bgCount > 0) (bgSumB / bgCount).toInt() else 245
            val bgBright = (0.299 * bgR + 0.587 * bgG + 0.114 * bgB).toInt()

            // 2. Count content pixels along rows and columns
            val rowContentCount = IntArray(sampleH)
            val colContentCount = IntArray(sampleW)

            // Ignore extreme outer 1.5% margin (scanner glass rim shadows)
            val edgeMarginX = (sampleW * 0.015f).toInt()
            val edgeMarginY = (sampleH * 0.015f).toInt()

            for (y in edgeMarginY until (sampleH - edgeMarginY)) {
                for (x in edgeMarginX until (sampleW - edgeMarginX)) {
                    val p = pixels[y * sampleW + x]
                    val r = (p shr 16) and 0xff
                    val g = (p shr 8) and 0xff
                    val b = p and 0xff
                    val bright = (0.299 * r + 0.587 * g + 0.114 * b).toInt()

                    val colorDiff = abs(r - bgR) + abs(g - bgG) + abs(b - bgB)
                    val brightDiff = abs(bright - bgBright)

                    // A card pixel differs noticeably from the scanner bed
                    if (brightDiff > 22 || colorDiff > 35) {
                        rowContentCount[y]++
                        colContentCount[x]++
                    }
                }
            }

            // Min content threshold: at least 4% of width/height
            val minRowThresh = max(4, (sampleW * 0.04f).toInt())
            val minColThresh = max(4, (sampleH * 0.04f).toInt())

            var sMinY = -1
            var sMaxY = -1
            for (y in edgeMarginY until (sampleH - edgeMarginY)) {
                if (rowContentCount[y] >= minRowThresh) {
                    if (sMinY == -1) sMinY = y
                    sMaxY = y
                }
            }

            var sMinX = -1
            var sMaxX = -1
            for (x in edgeMarginX until (sampleW - edgeMarginX)) {
                if (colContentCount[x] >= minColThresh) {
                    if (sMinX == -1) sMinX = x
                    sMaxX = x
                }
            }

            // Verify if card is detected (must occupy at least 8% of width and height)
            val cardSampleW = if (sMaxX > sMinX) sMaxX - sMinX else 0
            val cardSampleH = if (sMaxY > sMinY) sMaxY - sMinY else 0

            if (sMinX != -1 && sMinY != -1 && cardSampleW > sampleW * 0.08f && cardSampleH > sampleH * 0.08f) {
                // Map back to original resolution
                val invScale = 1.0f / scale
                var origCropX = (sMinX * invScale).toInt().coerceIn(0, origW - 10)
                var origCropY = (sMinY * invScale).toInt().coerceIn(0, origH - 10)
                var origCropW = (cardSampleW * invScale).toInt().coerceIn(20, origW - origCropX)
                var origCropH = (cardSampleH * invScale).toInt().coerceIn(20, origH - origCropY)

                // Add 1.5% padding so text/borders aren't shaved
                val padX = (origCropW * 0.015f).toInt()
                val padY = (origCropH * 0.015f).toInt()

                origCropX = max(0, origCropX - padX)
                origCropY = max(0, origCropY - padY)
                origCropW = min(origW - origCropX, origCropW + (padX * 2))
                origCropH = min(origH - origCropY, origCropH + (padY * 2))

                var croppedBmp = Bitmap.createBitmap(originalBmp, origCropX, origCropY, origCropW, origCropH)

                // If docType == "id_card" and card is oriented vertically (90 degree placement), rotate 90 degrees to landscape!
                if (docType == "id_card" && croppedBmp.height > croppedBmp.width) {
                    val matrix = android.graphics.Matrix()
                    matrix.postRotate(90f)
                    val rotBmp = Bitmap.createBitmap(croppedBmp, 0, 0, croppedBmp.width, croppedBmp.height, matrix, true)
                    croppedBmp.recycle()
                    croppedBmp = rotBmp
                }

                val outStream = ByteArrayOutputStream()
                croppedBmp.compress(Bitmap.CompressFormat.JPEG, 92, outStream)
                val finalW = croppedBmp.width
                val finalH = croppedBmp.height
                croppedBmp.recycle()
                originalBmp.recycle()

                val croppedBytes = outStream.toByteArray()
                val croppedDataUri = "data:image/jpeg;base64,${Base64.encodeToString(croppedBytes, Base64.NO_WRAP)}"

                val aspect = finalW.toFloat() / finalH.toFloat()
                val isCardAspect = (aspect in 1.4f..1.8f) || (aspect in 0.55f..0.72f)
                val ratioType = if (isCardAspect) "standard" else "custom"

                Log.i(TAG, "Auto-crop success: ${origCropW}x${origCropH} at ($origCropX, $origCropY), aspect $aspect")

                return CropResult(
                    success = true,
                    croppedImageDataUri = croppedDataUri,
                    rawImageDataUri = rawDataUri,
                    isCropped = true,
                    aspect = aspect,
                    ratioType = ratioType,
                    bbox = intArrayOf(origCropX, origCropY, origCropW, origCropH)
                )
            } else {
                Log.w(TAG, "No clear card bounds detected, returning full platen scan")
                originalBmp.recycle()
                return CropResult(
                    success = true,
                    croppedImageDataUri = rawDataUri,
                    rawImageDataUri = rawDataUri,
                    isCropped = false,
                    aspect = origW.toFloat() / origH.toFloat(),
                    ratioType = "custom",
                    bbox = null
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "CardAutoCropper error: ${e.message}", e)
            return CropResult(
                success = true,
                croppedImageDataUri = rawDataUri,
                rawImageDataUri = rawDataUri,
                isCropped = false,
                aspect = 1.585f,
                ratioType = "standard",
                bbox = null
            )
        }
    }
}
