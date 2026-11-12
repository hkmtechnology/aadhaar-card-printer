package com.hkmtech.cardprinter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.util.Base64
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.RotatedRect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class CropResult(
    val success: Boolean,
    val croppedImageDataUri: String,
    val rawImageDataUri: String,
    val isCropped: Boolean,
    val aspect: Float,
    val ratioType: String,
    val bbox: IntArray?,
    val corners: List<IntArray>? = null,
    val angle: Float = 0f,
    val widthMm: Float = 0f,
    val heightMm: Float = 0f,
    val docType: String = "id_card"
)

/**
 * Precision hybrid ID card detector & cropper combining OpenCV geometry (canny + color-diff +
 * morphological isolation + contour scoring + affine/perspective warping) with Google ML Kit
 * (text-line baseline angle & cluster verification + object-detector fallback).
 */
object CardAutoCropper {

    private const val TAG = "HKM_CROPPER"
    private const val PLATEN_SHORT_SIDE_MM = 210.0f

    @Volatile
    private var openCvReady = false

    private data class TextLineHint(
        val box: Rect,
        val angleDeg: Float,
        val weight: Float,
        val corners: Array<Point>? = null
    )

    private data class MlKitHints(
        val lines: List<TextLineHint>,
        val objects: List<Rect>
    )

    private data class CardDetection(
        val origCorners: Array<Point>,
        val origCenter: Point,
        val shortPxOrig: Float,
        val longPxOrig: Float,
        val alignAngleDeg: Float,
        val pxPerMm: Float,
        val bgRgb: DoubleArray,
        val inlierLinesOrig: List<TextLineHint>,
        val isPlaten: Boolean,
        val alreadyTightAndAligned: Boolean,
        val bgPlaneCoeffs: DoubleArray? = null
    )

    fun ensureOpenCV(): Boolean {
        if (openCvReady) return true
        synchronized(this) {
            if (openCvReady) return true
            openCvReady = try {
                OpenCVLoader.initLocal()
            } catch (t: Throwable) {
                Log.e(TAG, "OpenCVLoader.initLocal() failed: ${t.message}", t)
                false
            }
            Log.i(TAG, "OpenCV initialized=$openCvReady (Core=${try { Core.VERSION } catch (_: Throwable) { "?" }})")
            return openCvReady
        }
    }

    @JvmOverloads
    fun processScannedImage(
        rawBytes: ByteArray,
        docType: String = "id_card",
        isPlatenScan: Boolean = false
    ): CropResult {
        val rawMime = if (rawBytes.size > 2 && rawBytes[0] == 0xFF.toByte() && rawBytes[1] == 0xD8.toByte()) {
            "image/jpeg"
        } else {
            "image/png"
        }
        val rawDataUri = "data:$rawMime;base64,${Base64.encodeToString(rawBytes, Base64.NO_WRAP)}"

        if (docType == "a4") {
            return CropResult(
                success = true,
                croppedImageDataUri = rawDataUri,
                rawImageDataUri = rawDataUri,
                isCropped = false,
                aspect = 0.707f,
                ratioType = "a4",
                bbox = null,
                widthMm = 210.0f,
                heightMm = 297.0f,
                docType = "a4"
            )
        }

        val isSmallDocMode = (docType == "small" || docType == "auto")

        var originalBmp: Bitmap? = null
        try {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            originalBmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, opts)
                ?: return fallbackResult(rawDataUri, if (isSmallDocMode) 0.707f else 1.5858f, if (isSmallDocMode) "a4" else "standard_landscape", docType)

            val origW = originalBmp.width
            val origH = originalBmp.height

            if (!ensureOpenCV()) {
                Log.e(TAG, "OpenCV unavailable, returning uncropped image")
                val fallbackAspect = origW.toFloat() / max(1, origH).toFloat()
                return fallbackResult(
                    rawDataUri,
                    fallbackAspect,
                    if (fallbackAspect < 1.0f) "standard_portrait" else "standard_landscape",
                    docType
                )
            }

            val detectedPlaten = isPlatenScan || isA4PlatenAspect(origW, origH)
            val pxPerMm = if (detectedPlaten) {
                min(origW, origH).toFloat() / PLATEN_SHORT_SIDE_MM
            } else {
                // Approximate scale for standalone uploaded photos
                (min(origW, origH).toFloat() / 68.0f).coerceAtLeast(2.0f)
            }

            Log.i(TAG, "Starting OpenCV+MLKit crop: docType=$docType, image=${origW}x${origH}, platen=$detectedPlaten, pxPerMm=$pxPerMm")

            val detection = detectCardGeometry(originalBmp, origW, origH, pxPerMm, detectedPlaten, docMode = isSmallDocMode)
                ?: run {
                    Log.w(TAG, "No isolated contour detected ($docType); returning full image")
                    val origAspect = origW.toFloat() / max(1, origH).toFloat()
                    val resolvedType = if (docType == "auto") "a4" else docType
                    val wMm = if (detectedPlaten) origW / pxPerMm else 0f
                    val hMm = if (detectedPlaten) origH / pxPerMm else 0f
                    return fallbackResult(
                        rawDataUri,
                        origAspect,
                        if (resolvedType == "a4") "a4" else if (origAspect < 1.0f) "portrait" else "landscape",
                        resolvedType,
                        wMm,
                        hMm
                    )
                }

            val detAreaRatio = (detection.shortPxOrig * detection.longPxOrig) / max(1f, (origW * origH).toFloat())
            val isAutoFullA4 = (docType == "auto") && (
                detAreaRatio >= 0.55f ||
                ((detection.shortPxOrig / pxPerMm) > 165f && (detection.longPxOrig / pxPerMm) > 230f)
            )
            if (isAutoFullA4) {
                val trim = max(4, (min(origW, origH) * 0.006f).roundToInt())
                val trimW = (origW - trim * 2).coerceAtLeast(32)
                val trimH = (origH - trim * 2).coerceAtLeast(32)
                val fullCropped = Bitmap.createBitmap(originalBmp, trim, trim, trimW, trimH)
                originalBmp.recycle()
                originalBmp = null
                val (uprightFull, flippedFull) = ensureUprightOrientation180(fullCropped)
                val fW = uprightFull.width
                val fH = uprightFull.height
                val fAspect = fW.toFloat() / max(1, fH).toFloat()
                val outStream = ByteArrayOutputStream()
                uprightFull.compress(Bitmap.CompressFormat.JPEG, 95, outStream)
                uprightFull.recycle()
                val a4Uri = "data:image/jpeg;base64,${Base64.encodeToString(outStream.toByteArray(), Base64.NO_WRAP)}"
                val fWidthMm = if (detectedPlaten) fW / pxPerMm else 210.0f
                val fHeightMm = if (detectedPlaten) fH / pxPerMm else 297.0f
                return CropResult(
                    success = true,
                    croppedImageDataUri = a4Uri,
                    rawImageDataUri = rawDataUri,
                    isCropped = true,
                    aspect = fAspect,
                    ratioType = "a4",
                    bbox = intArrayOf(trim, trim, trimW, trimH),
                    angle = if (flippedFull) 180f else 0f,
                    widthMm = fWidthMm,
                    heightMm = fHeightMm,
                    docType = "a4"
                )
            }

            val rawCroppedBmp = extractDeskewedAndTightTrimmedCard(
                originalBmp,
                detection,
                enforceCardSize = !isSmallDocMode
            )
            originalBmp.recycle()
            originalBmp = null

            // Stage 4: Final 0° vs 180° ML Kit upright readability check — flips completely inverted cards/slips right-side up
            val (croppedBmp, flipped180) = ensureUprightOrientation180(rawCroppedBmp)
            val finalAngle = if (flipped180) normalizeDeg(detection.alignAngleDeg + 180f) else detection.alignAngleDeg

            val finalW = croppedBmp.width
            val finalH = croppedBmp.height
            val finalAspect = finalW.toFloat() / max(1, finalH).toFloat()

            val outStream = ByteArrayOutputStream()
            croppedBmp.compress(Bitmap.CompressFormat.JPEG, 95, outStream)
            croppedBmp.recycle()

            val croppedDataUri = "data:image/jpeg;base64,${Base64.encodeToString(outStream.toByteArray(), Base64.NO_WRAP)}"
            val resolvedDocType = if (isSmallDocMode) "small" else "id_card"
            val ratioType = if (isSmallDocMode) {
                if (finalAspect < 1.0f) "portrait" else "landscape"
            } else {
                if (finalAspect < 1.0f) "standard_portrait" else "standard_landscape"
            }
            val finalWidthMm = if (detectedPlaten) finalW / pxPerMm else 0f
            val finalHeightMm = if (detectedPlaten) finalH / pxPerMm else 0f

            val bbox = cornersToBoundingRect(detection.origCorners, origW, origH)
            val cornerList = detection.origCorners.map { pt ->
                intArrayOf(
                    pt.x.roundToInt().coerceIn(0, origW),
                    pt.y.roundToInt().coerceIn(0, origH)
                )
            }

            Log.i(
                TAG,
                "OpenCV+MLKit Crop SUCCESS ($resolvedDocType): ${finalW}x${finalH} (~${finalWidthMm.roundToInt()}x${finalHeightMm.roundToInt()}mm, aspect=$finalAspect, $ratioType), alignAngle=${finalAngle}°" +
                    if (flipped180) " [180° inverted-text flip applied]" else ""
            )

            return CropResult(
                success = true,
                croppedImageDataUri = croppedDataUri,
                rawImageDataUri = rawDataUri,
                isCropped = true,
                aspect = finalAspect,
                ratioType = ratioType,
                bbox = intArrayOf(bbox.left, bbox.top, bbox.width(), bbox.height()),
                corners = cornerList,
                angle = finalAngle,
                widthMm = finalWidthMm,
                heightMm = finalHeightMm,
                docType = resolvedDocType
            )
        } catch (e: Exception) {
            Log.e(TAG, "CardAutoCropper error: ${e.message}", e)
            return fallbackResult(rawDataUri, if (isSmallDocMode) 0.707f else 1.5858f, if (isSmallDocMode) "a4" else "standard_landscape", docType)
        } finally {
            try {
                originalBmp?.takeIf { !it.isRecycled }?.recycle()
            } catch (_: Throwable) {}
        }
    }

    /**
     * Detects the 4 card corners & bounding box in rawBytes WITHOUT returning a warped bitmap.
     * Used by the Manual Crop modal's Auto-Detect button so handles snap directly onto card edges.
     */
    fun detectCardBoundsJson(rawBytes: ByteArray): String {
        val result = JSONObject()
        var bmp: Bitmap? = null
        try {
            bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                ?: return result.put("success", false).put("error", "Decode failed").toString()
            if (!ensureOpenCV()) {
                return result.put("success", false).put("error", "OpenCV unavailable").toString()
            }

            val origW = bmp.width
            val origH = bmp.height
            val detectedPlaten = isA4PlatenAspect(origW, origH)
            val pxPerMm = if (detectedPlaten) {
                min(origW, origH).toFloat() / PLATEN_SHORT_SIDE_MM
            } else {
                (min(origW, origH).toFloat() / 68.0f).coerceAtLeast(2.0f)
            }

            val det = detectCardGeometry(bmp, origW, origH, pxPerMm, detectedPlaten)
                ?: return result.put("success", false).put("error", "Card boundary not found").toString()

            val ordered = orderQuadPoints(det.origCorners)
            val bbox = cornersToBoundingRect(ordered, origW, origH)
            val cornersJson = JSONArray()
            ordered.forEach { pt ->
                cornersJson.put(JSONArray().apply {
                    put(pt.x.roundToInt().coerceIn(0, origW))
                    put(pt.y.roundToInt().coerceIn(0, origH))
                })
            }

            val topW = hypot(ordered[1].x - ordered[0].x, ordered[1].y - ordered[0].y)
            val leftH = hypot(ordered[3].x - ordered[0].x, ordered[3].y - ordered[0].y)
            val aspect = (topW / max(1.0, leftH)).toFloat()
            val ratioType = if (aspect < 1.0f) "standard_portrait" else "standard_landscape"

            result.put("success", true)
            result.put("corners", cornersJson)
            result.put("bbox", JSONArray().apply {
                put(bbox.left); put(bbox.top); put(bbox.width()); put(bbox.height())
            })
            result.put("angle", det.alignAngleDeg.toDouble())
            result.put("aspect", aspect.toDouble())
            result.put("ratio_type", ratioType)
            result.put("naturalW", origW)
            result.put("naturalH", origH)
            return result.toString()
        } catch (e: Exception) {
            Log.e(TAG, "detectCardBoundsJson failed: ${e.message}", e)
            return JSONObject().put("success", false).put("error", e.message ?: "Bounds error").toString()
        } finally {
            try {
                bmp?.takeIf { !it.isRecycled }?.recycle()
            } catch (_: Throwable) {}
        }
    }

    /**
     * Performs native OpenCV 4-point perspective warp for the Manual Quad Crop modal offline.
     */
    fun warpQuadBase64(rawBytes: ByteArray, pointsJson: String): CropResult? {
        if (!ensureOpenCV()) return null
        var bmp: Bitmap? = null
        val srcMat = Mat()
        val dstMat = Mat()
        val srcPtsMat = MatOfPoint2f()
        val dstPtsMat = MatOfPoint2f()
        var perspectiveM: Mat? = null
        try {
            val arr = JSONArray(pointsJson)
            if (arr.length() != 4) return null
            val pts = Array(4) { i ->
                val pair = arr.getJSONArray(i)
                Point(pair.getDouble(0), pair.getDouble(1))
            }
            val ordered = orderQuadPoints(pts)

            bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return null
            Utils.bitmapToMat(bmp, srcMat)
            bmp.recycle()
            bmp = null

            val wTop = hypot(ordered[1].x - ordered[0].x, ordered[1].y - ordered[0].y)
            val wBot = hypot(ordered[2].x - ordered[3].x, ordered[2].y - ordered[3].y)
            val hLeft = hypot(ordered[3].x - ordered[0].x, ordered[3].y - ordered[0].y)
            val hRight = hypot(ordered[2].x - ordered[1].x, ordered[2].y - ordered[1].y)

            val outW = max(wTop, wBot).roundToInt().coerceAtLeast(32)
            val outH = max(hLeft, hRight).roundToInt().coerceAtLeast(32)

            srcPtsMat.fromArray(*ordered)
            dstPtsMat.fromArray(
                Point(0.0, 0.0),
                Point((outW - 1).toDouble(), 0.0),
                Point((outW - 1).toDouble(), (outH - 1).toDouble()),
                Point(0.0, (outH - 1).toDouble())
            )

            perspectiveM = Imgproc.getPerspectiveTransform(srcPtsMat, dstPtsMat)
            Imgproc.warpPerspective(
                srcMat,
                dstMat,
                perspectiveM,
                Size(outW.toDouble(), outH.toDouble()),
                Imgproc.INTER_CUBIC,
                Core.BORDER_REPLICATE
            )

            val warpedBmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(dstMat, warpedBmp)
            val (outBmp, alignedAngle) = alignBitmapByText(warpedBmp, allowFineDeskew = true)

            val alignedW = outBmp.width
            val alignedH = outBmp.height
            val outStream = ByteArrayOutputStream()
            outBmp.compress(Bitmap.CompressFormat.JPEG, 95, outStream)
            outBmp.recycle()

            val dataUri = "data:image/jpeg;base64,${Base64.encodeToString(outStream.toByteArray(), Base64.NO_WRAP)}"
            val aspect = alignedW.toFloat() / max(1, alignedH).toFloat()
            val quadBbox = cornersToBoundingRect(ordered, srcMat.cols(), srcMat.rows())
            Log.i(TAG, "warpQuadBase64 SUCCESS: ${alignedW}x${alignedH} (aspect=$aspect), textAlignAngle=${alignedAngle}°")
            return CropResult(
                success = true,
                croppedImageDataUri = dataUri,
                rawImageDataUri = dataUri,
                isCropped = true,
                aspect = aspect,
                ratioType = if (aspect < 1.0f) "standard_portrait" else "standard_landscape",
                bbox = intArrayOf(quadBbox.left, quadBbox.top, quadBbox.width(), quadBbox.height()),
                angle = alignedAngle
            )
        } catch (e: Exception) {
            Log.e(TAG, "warpQuadBase64 failed: ${e.message}", e)
            return null
        } finally {
            try { bmp?.takeIf { !it.isRecycled }?.recycle() } catch (_: Throwable) {}
            srcMat.release()
            dstMat.release()
            srcPtsMat.release()
            dstPtsMat.release()
            perspectiveM?.release()
        }
    }

    /**
     * Aligns a manually box-cropped region by text orientation using ML Kit + OpenCV.
     * - If the box is a loose selection with platen margin around a tilted/rotated card,
     *   runs full isolated card detection, deskewing, and tight trimming inside the box.
     * - If the box already tightly frames the card edges, preserves the exact user crop bounds
     *   while rotating (0°/90°/180°/270° + fine text deskew) so text is upright & horizontal.
     */
    fun alignManualBoxCrop(roiBytes: ByteArray): CropResult? {
        if (!ensureOpenCV()) return null
        var roiBmp: Bitmap? = null
        try {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            roiBmp = BitmapFactory.decodeByteArray(roiBytes, 0, roiBytes.size, opts) ?: return null
            val w = roiBmp.width
            val h = roiBmp.height
            if (w < 16 || h < 16) return null

            val isPlaten = isA4PlatenAspect(w, h)
            val pxPerMm = if (isPlaten) {
                min(w, h).toFloat() / PLATEN_SHORT_SIDE_MM
            } else {
                (min(w, h).toFloat() / 68.0f).coerceAtLeast(2.0f)
            }

            var chosenBmp: Bitmap? = null
            var appliedAngle = 0f

            val det = detectCardGeometry(roiBmp, w, h, pxPerMm, isPlaten)
            if (det != null) {
                val polyArea = det.shortPxOrig.toDouble() * det.longPxOrig.toDouble()
                val roiArea = w.toDouble() * h.toDouble()
                val areaRatio = polyArea / max(1.0, roiArea)
                val perimBgFrac = computePerimeterBgFraction(roiBmp, det.bgRgb)
                val hasLooseMargin = perimBgFrac >= 0.35f && areaRatio in 0.12..0.90
                val isSignificantlyTilted = abs(det.alignAngleDeg) > 4.0f && areaRatio >= 0.25

                if (hasLooseMargin || isSignificantlyTilted) {
                    val extracted = extractDeskewedAndTightTrimmedCard(roiBmp, det)
                    val (oriented, rotStep) = alignBitmapByText(extracted, allowFineDeskew = false)
                    chosenBmp = oriented
                    appliedAngle = normalizeDeg(det.alignAngleDeg + rotStep)
                }
            }

            if (chosenBmp == null) {
                val (aligned, rotDeg) = alignBitmapByText(roiBmp, allowFineDeskew = true)
                chosenBmp = aligned
                appliedAngle = rotDeg
            } else if (!roiBmp.isRecycled) {
                roiBmp.recycle()
            }

            val finalW = chosenBmp.width
            val finalH = chosenBmp.height
            val finalAspect = finalW.toFloat() / max(1, finalH).toFloat()

            val outStream = ByteArrayOutputStream()
            chosenBmp.compress(Bitmap.CompressFormat.JPEG, 95, outStream)
            if (!chosenBmp.isRecycled) chosenBmp.recycle()

            val dataUri = "data:image/jpeg;base64,${Base64.encodeToString(outStream.toByteArray(), Base64.NO_WRAP)}"
            Log.i(TAG, "alignManualBoxCrop SUCCESS: ${finalW}x${finalH} (aspect=$finalAspect), alignAngle=${appliedAngle}°")
            return CropResult(
                success = true,
                croppedImageDataUri = dataUri,
                rawImageDataUri = dataUri,
                isCropped = true,
                aspect = finalAspect,
                ratioType = if (finalAspect < 1.0f) "standard_portrait" else "standard_landscape",
                bbox = null,
                angle = appliedAngle
            )
        } catch (e: Exception) {
            Log.e(TAG, "alignManualBoxCrop failed: ${e.message}", e)
            return null
        } finally {
            try { roiBmp?.takeIf { !it.isRecycled }?.recycle() } catch (_: Throwable) {}
        }
    }

    /**
     * Orients any bitmap upright & horizontal by ML Kit text recognition (0°/90°/180°/270° + fine skew).
     */
    fun orientByText(rawBytes: ByteArray): CropResult? {
        var bmp: Bitmap? = null
        try {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, opts) ?: return null
            val (aligned, rotDeg) = alignBitmapByText(bmp, allowFineDeskew = true)
            val finalW = aligned.width
            val finalH = aligned.height
            val finalAspect = finalW.toFloat() / max(1, finalH).toFloat()

            val outStream = ByteArrayOutputStream()
            aligned.compress(Bitmap.CompressFormat.JPEG, 95, outStream)
            if (!aligned.isRecycled) aligned.recycle()

            val dataUri = "data:image/jpeg;base64,${Base64.encodeToString(outStream.toByteArray(), Base64.NO_WRAP)}"
            Log.i(TAG, "orientByText SUCCESS: ${finalW}x${finalH}, rot=${rotDeg}°")
            return CropResult(
                success = true,
                croppedImageDataUri = dataUri,
                rawImageDataUri = dataUri,
                isCropped = false,
                aspect = finalAspect,
                ratioType = if (finalAspect < 1.0f) "standard_portrait" else "standard_landscape",
                bbox = null,
                angle = rotDeg
            )
        } catch (e: Exception) {
            Log.e(TAG, "orientByText failed: ${e.message}", e)
            return null
        } finally {
            try { bmp?.takeIf { !it.isRecycled }?.recycle() } catch (_: Throwable) {}
        }
    }

    private fun fallbackResult(
        rawDataUri: String,
        aspect: Float,
        ratioType: String,
        docType: String = "id_card",
        widthMm: Float = 0f,
        heightMm: Float = 0f
    ): CropResult {
        return CropResult(
            success = true,
            croppedImageDataUri = rawDataUri,
            rawImageDataUri = rawDataUri,
            isCropped = false,
            aspect = aspect,
            ratioType = ratioType,
            bbox = null,
            widthMm = widthMm,
            heightMm = heightMm,
            docType = docType
        )
    }

    private fun isA4PlatenAspect(w: Int, h: Int): Boolean {
        if (w <= 0 || h <= 0) return false
        val longSide = max(w, h).toFloat()
        val shortSide = min(w, h).toFloat()
        val ratio = longSide / shortSide
        return shortSide >= 350f && ratio in 1.28f..1.58f
    }

    private fun detectCardGeometry(
        originalBmp: Bitmap,
        origW: Int,
        origH: Int,
        pxPerMm: Float,
        isPlaten: Boolean,
        docMode: Boolean = false
    ): CardDetection? {
        // Scale down platen image for fast OpenCV contour analysis + initial ML Kit OCR
        val workMaxDim = 960
        val scale = min(1.0f, workMaxDim.toFloat() / max(origW, origH).toFloat())
        val workW = (origW * scale).roundToInt().coerceAtLeast(80)
        val workH = (origH * scale).roundToInt().coerceAtLeast(80)
        val workPxPerMm = pxPerMm * scale

        val workBmp = if (workW == origW && workH == origH) {
            originalBmp.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            Bitmap.createScaledBitmap(originalBmp, workW, workH, true)
        }

        val rgbaMat = Mat()
        val rgbMat = Mat()
        val fgMask = Mat()
        var workBgField: Mat? = null
        try {
            Utils.bitmapToMat(workBmp, rgbaMat)
            Imgproc.cvtColor(rgbaMat, rgbMat, Imgproc.COLOR_RGBA2RGB)

            val bgRgb = estimatePlatenBackground(rgbMat, isPlaten)

            if (isPlaten) {
                suppressPlatenBezelEdges(
                    rgbMat,
                    workPxPerMm,
                    bgRgb,
                    touchLeft = true,
                    touchTop = true,
                    touchRight = true,
                    touchBottom = true
                )
            }

            val bgPlaneCoeffs = fitPlatenBackgroundPlane(rgbMat, isPlaten, bgRgb)
            workBgField = bgPlaneCoeffs?.let { renderBackgroundFieldMat(it, workW, workH) }

            // Convert cleaned work Mat back to Bitmap so ML Kit never sees bezel black border lines
            Imgproc.cvtColor(rgbMat, rgbaMat, Imgproc.COLOR_RGB2RGBA)
            val cleanWorkBmp = Bitmap.createBitmap(workW, workH, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgbaMat, cleanWorkBmp)
            workBmp.recycle()

            val mlHints = try {
                runMlKitTextAndObject(cleanWorkBmp)
            } finally {
                cleanWorkBmp.recycle()
            }

            buildOpenCvForegroundMask(rgbMat, bgRgb, workPxPerMm, isPlaten, fgMask, bgFieldMat = workBgField)

            var candidate = findBestCardContour(
                fgMask = fgMask,
                workW = workW,
                workH = workH,
                workPxPerMm = workPxPerMm,
                isPlaten = isPlaten,
                mlHints = mlHints,
                docMode = docMode
            )

            // If platen shadow/crease bloated the primary contour past ID bounds while ML Kit saw text,
            // retry once with stricter thresholding and smaller closing radius to detach the ambient shadow.
            if (!docMode && isPlaten && candidate != null && mlHints.lines.size >= 2) {
                val cShortMm = min(candidate.first.size.width, candidate.first.size.height) / workPxPerMm
                val cLongMm = max(candidate.first.size.width, candidate.first.size.height) / workPxPerMm
                if (cShortMm > 78.0 || cLongMm > 118.0) {
                    buildOpenCvForegroundMask(
                        rgbMat = rgbMat,
                        bgRgb = bgRgb,
                        pxPerMmWork = workPxPerMm,
                        isPlaten = true,
                        dstMask = fgMask,
                        strictTrimMode = false,
                        bgFieldMat = workBgField,
                        diffThreshOverride = 27.0,
                        cannyLow = 30.0,
                        cannyHigh = 90.0,
                        closeFactorOverride = 1.8f,
                        dilateEdges = false
                    )
                    val strictCand = findBestCardContour(
                        fgMask = fgMask,
                        workW = workW,
                        workH = workH,
                        workPxPerMm = workPxPerMm,
                        isPlaten = true,
                        mlHints = mlHints,
                        docMode = false
                    )
                    if (strictCand != null) {
                        val sShortMm = min(strictCand.first.size.width, strictCand.first.size.height) / workPxPerMm
                        val sLongMm = max(strictCand.first.size.width, strictCand.first.size.height) / workPxPerMm
                        if (sShortMm <= 78.0 && sLongMm <= 118.0) {
                            candidate = strictCand
                        }
                    }
                }
            }

            val rotRectWork: RotatedRect
            val contourRectWork: Array<Point>
            val rectangularity: Float

            if (candidate != null) {
                rotRectWork = candidate.first
                rectangularity = candidate.second
                val pts = Array(4) { Point() }
                rotRectWork.points(pts)
                contourRectWork = pts
            } else if (mlHints.lines.isNotEmpty() || mlHints.objects.isNotEmpty()) {
                // ML Kit fallback if contrast/edges were extremely faint
                val unionRect = computeMlKitFallbackRect(mlHints, workW, workH, workPxPerMm) ?: return null
                rotRectWork = RotatedRect(
                    Point(unionRect.exactCenterX().toDouble(), unionRect.exactCenterY().toDouble()),
                    Size(unionRect.width().toDouble(), unionRect.height().toDouble()),
                    0.0
                )
                contourRectWork = arrayOf(
                    Point(unionRect.left.toDouble(), unionRect.top.toDouble()),
                    Point(unionRect.right.toDouble(), unionRect.top.toDouble()),
                    Point(unionRect.right.toDouble(), unionRect.bottom.toDouble()),
                    Point(unionRect.left.toDouble(), unionRect.bottom.toDouble())
                )
                rectangularity = 0.85f
            } else {
                return null
            }

            val invScale = 1.0 / scale.toDouble()
            val origCorners = Array(4) { i ->
                Point(contourRectWork[i].x * invScale, contourRectWork[i].y * invScale)
            }
            val origCenter = Point(rotRectWork.center.x * invScale, rotRectWork.center.y * invScale)
            val shortPxOrig = (min(rotRectWork.size.width, rotRectWork.size.height) * invScale).toFloat()
            val longPxOrig = (max(rotRectWork.size.width, rotRectWork.size.height) * invScale).toFloat()

            // Filter ML Kit lines that belong inside the winning card contour
            val expandedPolyWork = expandQuadFromCenter(contourRectWork, (6.0f * workPxPerMm).toDouble())
            val poly2f = MatOfPoint2f(*expandedPolyWork)
            var inlierLinesWork = try {
                mlHints.lines.filter { line ->
                    Imgproc.pointPolygonTest(
                        poly2f,
                        Point(line.box.exactCenterX().toDouble(), line.box.exactCenterY().toDouble()),
                        false
                    ) >= 0.0
                }
            } finally {
                poly2f.release()
            }

            // If full-platen OCR found < 2 lines (e.g. small/low-DPI or inverted card),
            // extract just the isolated card region, upscale if small, and run high-res ML Kit OCR!
            var highResAngle: Float? = computeWeightedCircularAngle(inlierLinesWork)
            var refinedOrigLines: List<TextLineHint>? = null
            if (inlierLinesWork.size < 2) {
                val refined = refineTextAngleOnHighResPatch(originalBmp, origCorners, origW, origH, pxPerMm, bgRgb)
                if (refined.first != null) {
                    highResAngle = refined.first
                }
                if (refined.second.isNotEmpty()) {
                    refinedOrigLines = refined.second
                }
            }

            val inlierLinesOrig = refinedOrigLines ?: inlierLinesWork.map {
                TextLineHint(
                    box = Rect(
                        (it.box.left * invScale).roundToInt(),
                        (it.box.top * invScale).roundToInt(),
                        (it.box.right * invScale).roundToInt(),
                        (it.box.bottom * invScale).roundToInt()
                    ),
                    angleDeg = it.angleDeg,
                    weight = it.weight,
                    corners = it.corners?.map { pt -> Point(pt.x * invScale, pt.y * invScale) }?.toTypedArray()
                )
            }

            // Choose upright alignment angle:
            // ML Kit gives true upright orientation in (-180, 180], avoiding upside-down ambiguity.
            // OpenCV minAreaRect edges refine tiny OCR skew wobble when aligned within 2.5°.
            val rawRotRectAngle = rotRectWork.angle.toFloat()
            val alignAngle = chooseFinalAlignAngle(
                textAngle = highResAngle,
                rotRectAngle = rawRotRectAngle,
                rectangularity = rectangularity,
                inlierCount = inlierLinesOrig.size
            )

            // Check if non-platen uploaded photo already tightly frames an upright card
            val covW = (boundingSpanX(contourRectWork) / workW.toDouble()).toFloat()
            val covH = (boundingSpanY(contourRectWork) / workH.toDouble()).toFloat()
            val alreadyTightAndAligned = !isPlaten && covW > 0.93f && covH > 0.93f && abs(alignAngle) < 1.8f

            Log.i(
                TAG,
                "Contour isolated: short=${(shortPxOrig / pxPerMm).roundToInt()}mm, long=${(longPxOrig / pxPerMm).roundToInt()}mm, " +
                    "textAngle=$highResAngle°, rotRectAngle=$rawRotRectAngle°, chosenAlign=$alignAngle°, inliers=${inlierLinesOrig.size}"
            )

            return CardDetection(
                origCorners = origCorners,
                origCenter = origCenter,
                shortPxOrig = shortPxOrig,
                longPxOrig = longPxOrig,
                alignAngleDeg = alignAngle,
                pxPerMm = pxPerMm,
                bgRgb = bgRgb,
                inlierLinesOrig = inlierLinesOrig,
                isPlaten = isPlaten,
                alreadyTightAndAligned = alreadyTightAndAligned,
                bgPlaneCoeffs = bgPlaneCoeffs
            )
        } finally {
            try { if (!workBmp.isRecycled) workBmp.recycle() } catch (_: Throwable) {}
            workBgField?.release()
            rgbaMat.release()
            rgbMat.release()
            fgMask.release()
        }
    }

    private fun extractDeskewedAndTightTrimmedCard(
        originalBmp: Bitmap,
        det: CardDetection,
        enforceCardSize: Boolean = true
    ): Bitmap {
        val origW = originalBmp.width
        val origH = originalBmp.height

        if (det.alreadyTightAndAligned) {
            return originalBmp.copy(Bitmap.Config.ARGB_8888, false)
        }

        val roiPadPx = (8.0f * det.pxPerMm).roundToInt().coerceAtLeast(10)
        val rawBbox = cornersToBoundingRect(det.origCorners, origW, origH)
        val roiLeft = (rawBbox.left - roiPadPx).coerceIn(0, origW - 1)
        val roiTop = (rawBbox.top - roiPadPx).coerceIn(0, origH - 1)
        val roiRight = (rawBbox.right + roiPadPx).coerceIn(roiLeft + 1, origW)
        val roiBottom = (rawBbox.bottom + roiPadPx).coerceIn(roiTop + 1, origH)
        val roiW = roiRight - roiLeft
        val roiH = roiBottom - roiTop

        val roiBmp = Bitmap.createBitmap(originalBmp, roiLeft, roiTop, roiW, roiH)
        val roiRgba = Mat()
        val roiRgb = Mat()
        val maskedRgb = Mat()
        val cardMask = Mat.zeros(roiH, roiW, CvType.CV_8UC1)
        val deskewedRgb = Mat()
        var roiBgField: Mat? = null
        var deskewedBgField: Mat? = null
        var rotMat: Mat? = null
        val localBgRgb = evaluatePlaneRgb(
            det.bgPlaneCoeffs,
            det.origCenter.x / origW.toDouble() - 0.5,
            det.origCenter.y / origH.toDouble() - 0.5,
            det.bgRgb
        )

        try {
            Utils.bitmapToMat(roiBmp, roiRgba)
            roiBmp.recycle()
            Imgproc.cvtColor(roiRgba, roiRgb, Imgproc.COLOR_RGBA2RGB)

            // Clean any scanner platen bezel black line right on the ROI border touching platen outer edges
            if (det.isPlaten) {
                suppressPlatenBezelEdges(
                    rgbMat = roiRgb,
                    pxPerMm = det.pxPerMm,
                    bgRgb = localBgRgb,
                    touchLeft = roiLeft <= (2.5f * det.pxPerMm),
                    touchTop = roiTop <= (2.5f * det.pxPerMm),
                    touchRight = roiRight >= origW - (2.5f * det.pxPerMm),
                    touchBottom = roiBottom >= origH - (2.5f * det.pxPerMm)
                )
            }

            roiBgField = det.bgPlaneCoeffs?.let {
                renderBackgroundFieldMat(it, roiW, roiH, roiLeft, roiTop, origW, origH)
            }

            // Translate detected card corners to ROI coordinates and expand slightly (+3.5mm)
            val roiCorners = Array(4) { i ->
                Point(det.origCorners[i].x - roiLeft, det.origCorners[i].y - roiTop)
            }
            val safeExpandedCorners = expandQuadFromCenter(roiCorners, (3.5f * det.pxPerMm).toDouble())
            // Keep polygon vertices unclamped so OpenCV fillPoly clips edges mathematically at ROI bounds
            // instead of bending a clamped edge inward across corners that touch the platen border (y=0/x=0).
            val polyPts = MatOfPoint(*safeExpandedCorners.map {
                Point(it.x.roundToInt().toDouble(), it.y.roundToInt().toDouble())
            }.toTypedArray())

            try {
                Imgproc.fillPoly(cardMask, listOf(polyPts), Scalar(255.0))
            } finally {
                polyPts.release()
            }

            // Feather outer mask perimeter so platen transition never forms a hard polygon seam line,
            // then reinforce the inner +1.5mm core to solid 255 so no card corner ever gets faded/blurred.
            val blurK = makeOdd((2.0f * det.pxPerMm).roundToInt().coerceIn(7, 15)).toDouble()
            Imgproc.GaussianBlur(cardMask, cardMask, Size(blurK, blurK), 0.0)
            val solidCoreCorners = expandQuadFromCenter(roiCorners, (1.5f * det.pxPerMm).toDouble())
            val corePts = MatOfPoint(*solidCoreCorners.map {
                Point(it.x.roundToInt().toDouble(), it.y.roundToInt().toDouble())
            }.toTypedArray())
            try {
                Imgproc.fillPoly(cardMask, listOf(corePts), Scalar(255.0))
            } finally {
                corePts.release()
            }

            // Fill background outside expanded card polygon with seamless spatially-matched platen tone
            if (roiBgField != null) {
                roiBgField.copyTo(maskedRgb)
            } else {
                maskedRgb.create(roiH, roiW, CvType.CV_8UC3)
                maskedRgb.setTo(Scalar(localBgRgb[0], localBgRgb[1], localBgRgb[2]))
            }
            val fRgb = Mat()
            val fBg = Mat()
            val fAlpha = Mat()
            val fAlpha3 = Mat()
            try {
                roiRgb.convertTo(fRgb, CvType.CV_32FC3)
                maskedRgb.convertTo(fBg, CvType.CV_32FC3)
                cardMask.convertTo(fAlpha, CvType.CV_32FC1, 1.0 / 255.0)
                Core.merge(listOf(fAlpha, fAlpha, fAlpha), fAlpha3)
                Core.subtract(fRgb, fBg, fRgb)
                Core.multiply(fRgb, fAlpha3, fRgb)
                Core.add(fRgb, fBg, fRgb)
                fRgb.convertTo(maskedRgb, CvType.CV_8UC3)
            } finally {
                fRgb.release()
                fBg.release()
                fAlpha.release()
                fAlpha3.release()
            }

            val cx = det.origCenter.x - roiLeft
            val cy = det.origCenter.y - roiTop

            var activeAlignDeg = det.alignAngleDeg
            val rotExtraPx = (2.5f * det.pxPerMm).roundToInt().coerceAtLeast(4)

            // Compute exact axis-aligned output dimensions needed after OpenCV rotation around (cx, cy).
            // NOTE: OpenCV getRotationMatrix2D(+theta) rotates CCW in screen coords, whereas
            // Android Matrix.postRotate(+theta) rotates CW. Since alignAngle follows Android CW
            // convention (from ML Kit line.angle), passing +alignAngle to OpenCV rotates -alignAngle.
            fun computeDeskewBounds(angleDeg: Float): Pair<Int, Int> {
                val radCw = Math.toRadians(-angleDeg.toDouble())
                val cosA = cos(radCw)
                val sinA = sin(radCw)
                var maxAbsX = 0.0
                var maxAbsY = 0.0
                for (pt in safeExpandedCorners) {
                    val dx = pt.x - cx
                    val dy = pt.y - cy
                    val rx = dx * cosA - dy * sinA
                    val ry = dx * sinA + dy * cosA
                    maxAbsX = max(maxAbsX, abs(rx))
                    maxAbsY = max(maxAbsY, abs(ry))
                }
                val wOut = ((maxAbsX * 2.0).roundToInt() + rotExtraPx * 2).coerceAtLeast(32)
                val hOut = ((maxAbsY * 2.0).roundToInt() + rotExtraPx * 2).coerceAtLeast(32)
                return Pair(wOut, hOut)
            }

            fun buildAlignedRotMat(angleDeg: Float, outW: Int, outH: Int): Mat {
                val m = Imgproc.getRotationMatrix2D(Point(cx, cy), angleDeg.toDouble(), 1.0)
                m.put(0, 2, m.get(0, 2)[0] + (outW / 2.0 - cx))
                m.put(1, 2, m.get(1, 2)[0] + (outH / 2.0 - cy))
                return m
            }

            var (deskewW, deskewH) = computeDeskewBounds(activeAlignDeg)
            rotMat = buildAlignedRotMat(activeAlignDeg, deskewW, deskewH)
            Imgproc.warpAffine(
                maskedRgb,
                deskewedRgb,
                rotMat,
                Size(deskewW.toDouble(), deskewH.toDouble()),
                Imgproc.INTER_CUBIC,
                Core.BORDER_REPLICATE
            )

            // Refine residual fine skew (< 8.5°) using card edges + horizontal/vertical internal rules
            // so low-DPI coarse OCR text-box jitter never leaves a tilted bottom/top corner.
            val fineDeltaDeg = measureResidualAxisAngleDeg(deskewedRgb, det.pxPerMm)
            if (abs(fineDeltaDeg) in 0.15f..8.5f) {
                activeAlignDeg += fineDeltaDeg
                val (newW, newH) = computeDeskewBounds(activeAlignDeg)
                deskewW = newW
                deskewH = newH
                rotMat.release()
                rotMat = buildAlignedRotMat(activeAlignDeg, deskewW, deskewH)
                Imgproc.warpAffine(
                    maskedRgb,
                    deskewedRgb,
                    rotMat,
                    Size(deskewW.toDouble(), deskewH.toDouble()),
                    Imgproc.INTER_CUBIC,
                    Core.BORDER_REPLICATE
                )
                Log.d(TAG, "FineHoughRefine: coarse=${det.alignAngleDeg}°, delta=$fineDeltaDeg°, refined=$activeAlignDeg°")
            }

            if (roiBgField != null) {
                deskewedBgField = Mat()
                Imgproc.warpAffine(
                    roiBgField,
                    deskewedBgField,
                    rotMat,
                    Size(deskewW.toDouble(), deskewH.toDouble()),
                    Imgproc.INTER_LINEAR,
                    Core.BORDER_REPLICATE
                )
            }

            // Transform Stage 1 card corners & ML Kit inlier text cornerPoints into deskewed coordinates
            fun transformPolyBounds(pts: Array<Point>): Rect? {
                var minX = Double.MAX_VALUE
                var minY = Double.MAX_VALUE
                var maxX = -Double.MAX_VALUE
                var maxY = -Double.MAX_VALUE
                for (p in pts) {
                    val tx = rotMat.get(0, 0)[0] * p.x + rotMat.get(0, 1)[0] * p.y + rotMat.get(0, 2)[0]
                    val ty = rotMat.get(1, 0)[0] * p.x + rotMat.get(1, 1)[0] * p.y + rotMat.get(1, 2)[0]
                    minX = min(minX, tx); minY = min(minY, ty)
                    maxX = max(maxX, tx); maxY = max(maxY, ty)
                }
                return if (maxX > 0 && minX < deskewW && maxY > 0 && minY < deskewH) {
                    Rect(
                        minX.roundToInt().coerceIn(0, deskewW - 1),
                        minY.roundToInt().coerceIn(0, deskewH - 1),
                        maxX.roundToInt().coerceIn(1, deskewW),
                        maxY.roundToInt().coerceIn(1, deskewH)
                    )
                } else null
            }

            val detIsPlausibleCard = enforceCardSize && (
                !det.isPlaten ||
                ((det.shortPxOrig / det.pxPerMm) in 38f..74f && (det.longPxOrig / det.pxPerMm) in 62f..108f)
            )
            val expectedCardRect = if (detIsPlausibleCard) transformPolyBounds(roiCorners) else null
            val transformedTextRects = det.inlierLinesOrig.mapNotNull { line ->
                val corners = if (line.corners != null && line.corners.size >= 4) {
                    line.corners.map { Point(it.x - roiLeft, it.y - roiTop) }.toTypedArray()
                } else {
                    arrayOf(
                        Point(line.box.left.toDouble() - roiLeft, line.box.top.toDouble() - roiTop),
                        Point(line.box.right.toDouble() - roiLeft, line.box.top.toDouble() - roiTop),
                        Point(line.box.right.toDouble() - roiLeft, line.box.bottom.toDouble() - roiTop),
                        Point(line.box.left.toDouble() - roiLeft, line.box.bottom.toDouble() - roiTop)
                    )
                }
                transformPolyBounds(corners)
            }

            // Stage 3b: Final Tight OpenCV Border-to-Border Trim inside deskewed frame
            val tightRect = findTightAxisAlignedCardRect(
                deskewedRgb = deskewedRgb,
                bgRgb = localBgRgb,
                pxPerMm = det.pxPerMm,
                textGuards = transformedTextRects,
                expectedCardRect = expectedCardRect,
                bgFieldMat = deskewedBgField
            )

            val subMat = Mat(deskewedRgb, tightRect)
            val subRgba = Mat()
            try {
                Imgproc.cvtColor(subMat, subRgba, Imgproc.COLOR_RGB2RGBA)
                val outBmp = Bitmap.createBitmap(tightRect.width, tightRect.height, Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(subRgba, outBmp)
                return outBmp
            } finally {
                subRgba.release()
                subMat.release()
            }
        } finally {
            try { if (!roiBmp.isRecycled) roiBmp.recycle() } catch (_: Throwable) {}
            roiBgField?.release()
            deskewedBgField?.release()
            roiRgba.release()
            roiRgb.release()
            maskedRgb.release()
            cardMask.release()
            deskewedRgb.release()
            rotMat?.release()
        }
    }

    private fun findTightAxisAlignedCardRect(
        deskewedRgb: Mat,
        bgRgb: DoubleArray,
        pxPerMm: Float,
        textGuards: List<Rect>,
        expectedCardRect: Rect? = null,
        bgFieldMat: Mat? = null
    ): org.opencv.core.Rect {
        val w = deskewedRgb.cols()
        val h = deskewedRgb.rows()

        val scale = min(1.0f, 640.0f / max(w, h).toFloat())
        val sw = (w * scale).roundToInt().coerceAtLeast(24)
        val sh = (h * scale).roundToInt().coerceAtLeast(24)
        val sPxPerMm = pxPerMm * scale

        val smallRgb = Mat()
        val smallFg = Mat()
        var smallBgField: Mat? = null
        try {
            if (sw == w && sh == h) {
                deskewedRgb.copyTo(smallRgb)
                if (bgFieldMat != null) {
                    smallBgField = bgFieldMat.clone()
                }
            } else {
                Imgproc.resize(deskewedRgb, smallRgb, Size(sw.toDouble(), sh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                if (bgFieldMat != null) {
                    smallBgField = Mat()
                    Imgproc.resize(bgFieldMat, smallBgField, Size(sw.toDouble(), sh.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
                }
            }

            buildOpenCvForegroundMask(
                rgbMat = smallRgb,
                bgRgb = bgRgb,
                pxPerMmWork = sPxPerMm,
                isPlaten = bgFieldMat != null,
                dstMask = smallFg,
                strictTrimMode = true,
                bgFieldMat = smallBgField,
                diffThreshOverride = if (smallBgField != null) 24.0 else 17.0,
                cannyLow = 34.0,
                cannyHigh = 96.0
            )

            val contours = mutableListOf<MatOfPoint>()
            val hierarchy = Mat()
            try {
                Imgproc.findContours(smallFg, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                val sCx = sw / 2.0
                val sCy = sh / 2.0
                val minArea = sw * sh * 0.12

                var bestRect: org.opencv.core.Rect? = null
                var bestScore = Double.NEGATIVE_INFINITY

                for (c in contours) {
                    val area = Imgproc.contourArea(c)
                    if (area < minArea) continue
                    val br = Imgproc.boundingRect(c)
                    val cx = br.x + br.width / 2.0
                    val cy = br.y + br.height / 2.0
                    val centerDist = hypot(cx - sCx, cy - sCy)
                    // Strongly favor the main central card body; ignores tiny right-side specks
                    val score = area - centerDist * sw * 0.5
                    if (score > bestScore) {
                        bestScore = score
                        bestRect = br
                    }
                }

                if (bestRect == null && contours.isNotEmpty()) {
                    // Fallback: union of medium central card segments when one outer edge was interrupted
                    val segMinArea = sw * sh * 0.012
                    var uL = sw; var uT = sh; var uR = 0; var uB = 0
                    var hits = 0
                    for (c in contours) {
                        val area = Imgproc.contourArea(c)
                        val br = Imgproc.boundingRect(c)
                        if (area >= segMinArea || (br.width >= sw * 0.25 && br.height >= 4)) {
                            uL = min(uL, br.x); uT = min(uT, br.y)
                            uR = max(uR, br.x + br.width); uB = max(uB, br.y + br.height)
                            hits++
                        }
                    }
                    if (hits > 0 && uR > uL + 10 && uB > uT + 10) {
                        bestRect = org.opencv.core.Rect(uL, uT, uR - uL, uB - uT)
                    }
                }

                val inv = 1.0 / scale.toDouble()
                var left: Int
                var top: Int
                var right: Int
                var bottom: Int

                if (bestRect != null) {
                    left = (bestRect.x * inv).roundToInt()
                    top = (bestRect.y * inv).roundToInt()
                    right = ((bestRect.x + bestRect.width) * inv).roundToInt()
                    bottom = ((bestRect.y + bestRect.height) * inv).roundToInt()
                } else {
                    left = 0; top = 0; right = w; bottom = h
                }

                // Stage 1 sanity guard: tight trim must never truncate >15% of an ID card's blank white interior
                if (expectedCardRect != null && expectedCardRect.width() > 16 && expectedCardRect.height() > 16) {
                    val slackPx = (2.2f * pxPerMm).roundToInt().coerceAtLeast(3)
                    val safeL = expectedCardRect.left.coerceIn(0, w - 16)
                    val safeR = expectedCardRect.right.coerceIn(safeL + 16, w)
                    val safeT = expectedCardRect.top.coerceIn(0, h - 16)
                    val safeB = expectedCardRect.bottom.coerceIn(safeT + 16, h)
                    val minW = ((safeR - safeL) * 0.85f).roundToInt()
                    val minH = ((safeB - safeT) * 0.85f).roundToInt()
                    if ((right - left) < minW) {
                        val guardL = min(safeR - 16, safeL + slackPx).coerceAtLeast(0)
                        val guardR = max(guardL + 16, safeR - slackPx).coerceAtMost(w)
                        left = min(left, guardL)
                        right = max(right, guardR)
                    }
                    if ((bottom - top) < minH) {
                        val guardT = min(safeB - 16, safeT + slackPx).coerceAtLeast(0)
                        val guardB = max(guardT + 16, safeB - slackPx).coerceAtMost(h)
                        top = min(top, guardT)
                        bottom = max(bottom, guardB)
                    }
                }

                // Never clip verified ML Kit text lines
                if (textGuards.isNotEmpty()) {
                    val guardPadPx = (1.5f * pxPerMm).roundToInt().coerceAtLeast(2)
                    for (tr in textGuards) {
                        left = min(left, tr.left - guardPadPx)
                        top = min(top, tr.top - guardPadPx)
                        right = max(right, tr.right + guardPadPx)
                        bottom = max(bottom, tr.bottom + guardPadPx)
                    }
                }

                // Cushion rounded PVC corners and anti-aliased card rims cleanly
                val microPad = (0.55f * pxPerMm).roundToInt().coerceIn(2, 5)
                val finalL = (left - microPad).coerceIn(0, w - 16)
                val finalT = (top - microPad).coerceIn(0, h - 16)
                val finalR = (right + microPad).coerceIn(finalL + 16, w)
                val finalB = (bottom + microPad).coerceIn(finalT + 16, h)

                Log.d(TAG, "TightCrop: contours=${contours.size}, bestRect=$bestRect -> final=${finalR - finalL}x${finalB - finalT} in ${w}x${h}")
                return org.opencv.core.Rect(finalL, finalT, finalR - finalL, finalB - finalT)
            } finally {
                hierarchy.release()
                for (c in contours) c.release()
            }
        } finally {
            smallBgField?.release()
            smallRgb.release()
            smallFg.release()
        }
    }

    private fun buildOpenCvForegroundMask(
        rgbMat: Mat,
        bgRgb: DoubleArray,
        pxPerMmWork: Float,
        isPlaten: Boolean,
        dstMask: Mat,
        strictTrimMode: Boolean = false,
        bgFieldMat: Mat? = null,
        diffThreshOverride: Double? = null,
        cannyLow: Double = 20.0,
        cannyHigh: Double = 64.0,
        closeFactorOverride: Float? = null,
        dilateEdges: Boolean = !strictTrimMode
    ) {
        val w = rgbMat.cols()
        val h = rgbMat.rows()

        val blurRgb = Mat()
        val diffRgb = Mat()
        val channels = ArrayList<Mat>(3)
        val tmpMax = Mat()
        val maxDiff = Mat()
        val diffMask = Mat()
        val gray = Mat()
        val edges = Mat()
        val speckKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        val closeFactor = closeFactorOverride ?: if (strictTrimMode) 2.0f else 2.8f
        val openFactor = if (strictTrimMode) 0.85f else 1.2f
        val closeSize = makeOdd((closeFactor * pxPerMmWork).roundToInt().coerceIn(3, 19)).toDouble()
        val openSize = makeOdd((openFactor * pxPerMmWork).roundToInt().coerceIn(3, if (strictTrimMode) 5 else 7)).toDouble()
        val openShape = if (strictTrimMode) Imgproc.MORPH_ELLIPSE else Imgproc.MORPH_RECT
        val closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(closeSize, closeSize))
        val openKernel = Imgproc.getStructuringElement(openShape, Size(openSize, openSize))
        val fgRaw = Mat()
        val closedMask = Mat()
        val fillContours = ArrayList<MatOfPoint>()
        val fillHier = Mat()

        try {
            Imgproc.GaussianBlur(rgbMat, blurRgb, Size(3.0, 3.0), 0.0)

            // 1. Max-channel RGB diff against spatially varying platen plane (or flat bgRgb)
            if (bgFieldMat != null && bgFieldMat.cols() == w && bgFieldMat.rows() == h) {
                Core.absdiff(blurRgb, bgFieldMat, diffRgb)
            } else {
                Core.absdiff(blurRgb, Scalar(bgRgb[0], bgRgb[1], bgRgb[2]), diffRgb)
            }
            Core.split(diffRgb, channels)
            Core.max(channels[0], channels[1], tmpMax)
            Core.max(tmpMax, channels[2], maxDiff)
            val diffThresh = diffThreshOverride ?: if (isPlaten) 21.0 else 15.0
            Imgproc.threshold(maxDiff, diffMask, diffThresh, 255.0, Imgproc.THRESH_BINARY)
            if (strictTrimMode) {
                Imgproc.morphologyEx(diffMask, diffMask, Imgproc.MORPH_OPEN, speckKernel)
            }

            // 2. Canny edge map captures thin borders & faint paper edges on white lid
            Imgproc.cvtColor(blurRgb, gray, Imgproc.COLOR_RGB2GRAY)
            Imgproc.Canny(gray, edges, cannyLow, cannyHigh)

            // Zero outer frame perimeter so platen bezel brightness ramps cannot bridge into contours
            val edgeRimPx = if (strictTrimMode) 4 else if (isPlaten) 6 else 4
            Imgproc.rectangle(edges, Point(0.0, 0.0), Point((w - 1).toDouble(), (h - 1).toDouble()), Scalar(0.0), edgeRimPx)
            if (dilateEdges) {
                Imgproc.dilate(edges, edges, speckKernel)
            }

            Core.bitwise_or(diffMask, edges, fgRaw)
            // Zero extreme 2px frame again before closing
            Imgproc.rectangle(fgRaw, Point(0.0, 0.0), Point((w - 1).toDouble(), (h - 1).toDouble()), Scalar(0.0), 2)

            // Close internal gaps in card border outlines
            Imgproc.morphologyEx(fgRaw, closedMask, Imgproc.MORPH_CLOSE, closeKernel)

            // CRITICAL: Flood-fill enclosed card interiors SOLID BEFORE MORPH_OPEN!
            // Otherwise white-paper cards whose interior matches the white lid only have hollow
            // outline borders that MORPH_OPEN would erase.
            Imgproc.findContours(closedMask, fillContours, fillHier, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            val minFillDim = max(6, (3.0f * pxPerMmWork).roundToInt())
            for (idx in fillContours.indices) {
                val br = Imgproc.boundingRect(fillContours[idx])
                if (br.width >= minFillDim && br.height >= minFillDim) {
                    Imgproc.drawContours(closedMask, fillContours, idx, Scalar(255.0), -1)
                }
            }

            // Open removes thin 1-4px protruding platen bezel lines & detached background specks
            Imgproc.morphologyEx(closedMask, dstMask, Imgproc.MORPH_OPEN, openKernel)
        } finally {
            blurRgb.release()
            diffRgb.release()
            for (c in channels) c.release()
            tmpMax.release()
            maxDiff.release()
            diffMask.release()
            gray.release()
            edges.release()
            speckKernel.release()
            closeKernel.release()
            openKernel.release()
            fgRaw.release()
            closedMask.release()
            fillHier.release()
            for (c in fillContours) c.release()
        }
    }

    /**
     * Fits a trimmed 2D linear illumination plane per R/G/B channel across the platen lid
     * (`c0 + cx*(x/w - 0.5) + cy*(y/h - 0.5)`), modeling natural uneven lid lighting/vignette
     * (e.g. TL=216 -> BR=248 on Epson L3250) so platen shading does not glue onto ID cards.
     */
    private fun fitPlatenBackgroundPlane(
        rgbMat: Mat,
        isPlaten: Boolean,
        fallbackBg: DoubleArray
    ): DoubleArray? {
        if (!isPlaten) return null
        val w = rgbMat.cols()
        val h = rgbMat.rows()
        if (w < 40 || h < 40) return null

        data class BgSample(val xn: Double, val yn: Double, val r: Double, val g: Double, val b: Double, val lum: Double)

        val cols = 14
        val rows = 18
        val raw = ArrayList<BgSample>(cols * rows)
        val px = ByteArray(3)
        for (ry in 0 until rows) {
            val y = ((0.05 + 0.90 * (ry.toDouble() / (rows - 1))) * (h - 1)).roundToInt().coerceIn(2, h - 3)
            for (cx in 0 until cols) {
                val x = ((0.05 + 0.90 * (cx.toDouble() / (cols - 1))) * (w - 1)).roundToInt().coerceIn(2, w - 3)
                rgbMat.get(y, x, px)
                val r = (px[0].toInt() and 0xFF).toDouble()
                val g = (px[1].toInt() and 0xFF).toDouble()
                val b = (px[2].toInt() and 0xFF).toDouble()
                val chroma = max(r, max(g, b)) - min(r, min(g, b))
                val lum = 0.299 * r + 0.587 * g + 0.114 * b
                if (chroma <= 24.0 && lum >= 165.0) {
                    raw.add(BgSample(x.toDouble() / w - 0.5, y.toDouble() / h - 0.5, r, g, b, lum))
                }
            }
        }
        if (raw.size < 16) return null

        val sorted = raw.sortedBy { it.lum }
        val refLum = sorted[(sorted.size * 0.70).toInt().coerceIn(0, sorted.lastIndex)].lum
        var inliers = raw.filter { abs(it.lum - refLum) <= 36.0 }
        if (inliers.size < 16) return null

        fun fitOnce(pts: List<BgSample>): DoubleArray? {
            var n = 0.0; var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0; var syy = 0.0
            var sr = 0.0; var sxr = 0.0; var syr = 0.0
            var sg = 0.0; var sxg = 0.0; var syg = 0.0
            var sb = 0.0; var sxb = 0.0; var syb = 0.0
            for (s in pts) {
                n += 1.0; sx += s.xn; sy += s.yn
                sxx += s.xn * s.xn; sxy += s.xn * s.yn; syy += s.yn * s.yn
                sr += s.r; sxr += s.xn * s.r; syr += s.yn * s.r
                sg += s.g; sxg += s.xn * s.g; syg += s.yn * s.g
                sb += s.b; sxb += s.xn * s.b; syb += s.yn * s.b
            }
            val detA = n * (sxx * syy - sxy * sxy) - sx * (sx * syy - sxy * sy) + sy * (sx * sxy - sxx * sy)
            if (abs(detA) < 1e-6) return null
            fun solve(sz: Double, sxz: Double, syz: Double): DoubleArray {
                val c0 = (sz * (sxx * syy - sxy * sxy) - sx * (sxz * syy - sxy * syz) + sy * (sxz * sxy - sxx * syz)) / detA
                val cx = (n * (sxz * syy - sxy * syz) - sz * (sx * syy - sxy * sy) + sy * (sx * syz - sxz * sy)) / detA
                val cy = (n * (sxx * syz - sxz * sxy) - sx * (sx * syz - sxz * sy) + sz * (sx * sxy - sxx * sy)) / detA
                return doubleArrayOf(c0, cx, cy)
            }
            val cr = solve(sr, sxr, syr)
            val cg = solve(sg, sxg, syg)
            val cb = solve(sb, sxb, syb)
            return doubleArrayOf(cr[0], cr[1], cr[2], cg[0], cg[1], cg[2], cb[0], cb[1], cb[2])
        }

        val pass1 = fitOnce(inliers) ?: return null
        val trimmed = inliers.filter { s ->
            val pr = pass1[0] + pass1[1] * s.xn + pass1[2] * s.yn
            val pg = pass1[3] + pass1[4] * s.xn + pass1[5] * s.yn
            val pb = pass1[6] + pass1[7] * s.xn + pass1[8] * s.yn
            max(abs(s.r - pr), max(abs(s.g - pg), abs(s.b - pb))) <= 14.0
        }
        if (trimmed.size >= 14) {
            inliers = trimmed
        }
        return fitOnce(inliers) ?: pass1
    }

    private fun evaluatePlaneRgb(
        coeffs: DoubleArray?,
        xn: Double,
        yn: Double,
        fallbackBg: DoubleArray
    ): DoubleArray {
        if (coeffs == null || coeffs.size < 9) return fallbackBg
        return doubleArrayOf(
            (coeffs[0] + coeffs[1] * xn + coeffs[2] * yn).coerceIn(120.0, 255.0),
            (coeffs[3] + coeffs[4] * xn + coeffs[5] * yn).coerceIn(120.0, 255.0),
            (coeffs[6] + coeffs[7] * xn + coeffs[8] * yn).coerceIn(120.0, 255.0)
        )
    }

    private fun renderBackgroundFieldMat(
        coeffs: DoubleArray,
        dstW: Int,
        dstH: Int,
        subX: Int = 0,
        subY: Int = 0,
        fullW: Int = dstW,
        fullH: Int = dstH
    ): Mat {
        val gw = 32
        val gh = 44
        val buf = ByteArray(gw * gh * 3)
        val fw = max(1, fullW).toDouble()
        val fh = max(1, fullH).toDouble()
        for (gy in 0 until gh) {
            val sy = subY + (gy.toDouble() / (gh - 1)) * max(1, dstH - 1)
            val yn = sy / fh - 0.5
            for (gx in 0 until gw) {
                val sx = subX + (gx.toDouble() / (gw - 1)) * max(1, dstW - 1)
                val xn = sx / fw - 0.5
                val off = (gy * gw + gx) * 3
                buf[off] = (coeffs[0] + coeffs[1] * xn + coeffs[2] * yn).roundToInt().coerceIn(120, 255).toByte()
                buf[off + 1] = (coeffs[3] + coeffs[4] * xn + coeffs[5] * yn).roundToInt().coerceIn(120, 255).toByte()
                buf[off + 2] = (coeffs[6] + coeffs[7] * xn + coeffs[8] * yn).roundToInt().coerceIn(120, 255).toByte()
            }
        }
        val mini = Mat(gh, gw, CvType.CV_8UC3)
        val out = Mat()
        try {
            mini.put(0, 0, buf)
            Imgproc.resize(mini, out, Size(dstW.toDouble(), dstH.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            return out
        } finally {
            mini.release()
        }
    }

    private fun estimatePlatenBackground(rgbMat: Mat, isPlaten: Boolean): DoubleArray {
        val w = rgbMat.cols()
        val h = rgbMat.rows()
        val buf = ByteArray(3)
        val rVals = ArrayList<Int>()
        val gVals = ArrayList<Int>()
        val bVals = ArrayList<Int>()

        val marginX = max(2, (w * 0.03f).roundToInt())
        val marginY = max(2, (h * 0.03f).roundToInt())
        val step = max(2, min(w, h) / 70)

        for (y in marginY until (h - marginY) step step) {
            for (x in marginX until (w - marginX) step step) {
                rgbMat.get(y, x, buf)
                val r = buf[0].toInt() and 0xFF
                val g = buf[1].toInt() and 0xFF
                val b = buf[2].toInt() and 0xFF
                val bright = (0.299 * r + 0.587 * g + 0.114 * b)
                if (bright >= if (isPlaten) 170.0 else 155.0) {
                    rVals.add(r); gVals.add(g); bVals.add(b)
                }
            }
        }

        // If very few bright interior pixels (e.g., tightly cropped dark photo), sample outer perimeter strip
        if (rVals.size < 25) {
            rVals.clear(); gVals.clear(); bVals.clear()
            val edgeBandX = max(3, (w * 0.08f).roundToInt())
            val edgeBandY = max(3, (h * 0.08f).roundToInt())
            for (y in 2 until (h - 2) step step) {
                for (x in 2 until (w - 2) step step) {
                    if (x < edgeBandX || x >= w - edgeBandX || y < edgeBandY || y >= h - edgeBandY) {
                        rgbMat.get(y, x, buf)
                        rVals.add(buf[0].toInt() and 0xFF)
                        gVals.add(buf[1].toInt() and 0xFF)
                        bVals.add(buf[2].toInt() and 0xFF)
                    }
                }
            }
        }

        if (rVals.isEmpty()) return doubleArrayOf(248.0, 248.0, 248.0)
        rVals.sort(); gVals.sort(); bVals.sort()
        val mid = rVals.size / 2
        return doubleArrayOf(rVals[mid].toDouble(), gVals[mid].toDouble(), bVals[mid].toDouble())
    }

    /**
     * Suppresses dark scanner bezel border shadows/lines along the outer platen rims.
     * Compares against the local inward platen brightness (instead of one global constant) and walks
     * contiguously inward so a white platen gap stops the wipe before reaching any real card border.
     */
    private fun suppressPlatenBezelEdges(
        rgbMat: Mat,
        pxPerMm: Float,
        bgRgb: DoubleArray,
        touchLeft: Boolean,
        touchTop: Boolean,
        touchRight: Boolean,
        touchBottom: Boolean
    ) {
        val w = rgbMat.cols()
        val h = rgbMat.rows()
        if (w < 20 || h < 20) return

        val hardBezelPx = (1.55f * pxPerMm).roundToInt().coerceIn(2, min(w / 10, h / 10).coerceIn(2, 18))
        val rimDepth = (2.4f * pxPerMm).roundToInt().coerceIn(hardBezelPx, min(w / 8, h / 8).coerceAtLeast(hardBezelPx))
        val guardDepth = (3.2f * pxPerMm).roundToInt().coerceIn(4, 38)

        val workMat = if (rgbMat.isContinuous()) rgbMat else rgbMat.clone()
        val allBytes = ByteArray(w * h * 3)
        workMat.get(0, 0, allBytes)

        fun cleanInwardRay(startX: Int, startY: Int, stepX: Int, stepY: Int, maxLen: Int): Boolean {
            if (maxLen <= rimDepth + 4) return false
            val actualRim = min(rimDepth, maxLen)
            val gEnd = min(maxLen, actualRim + guardDepth)
            val gSpan = max(1, gEnd - actualRim)
            var neutralHits = 0
            var sumR = 0.0; var sumG = 0.0; var sumB = 0.0
            for (d in actualRim until gEnd) {
                val off = ((startY + stepY * d) * w + (startX + stepX * d)) * 3
                val r = allBytes[off].toInt() and 0xFF
                val g = allBytes[off + 1].toInt() and 0xFF
                val b = allBytes[off + 2].toInt() and 0xFF
                val lum = 0.299 * r + 0.587 * g + 0.114 * b
                val chroma = max(r, max(g, b)) - min(r, min(g, b))
                if (chroma < 26 && lum >= 160.0) {
                    neutralHits++
                    sumR += r; sumG += g; sumB += b
                }
            }
            val guardIsWhite = neutralHits.toFloat() / gSpan >= 0.70f
            val refR = if (guardIsWhite && neutralHits > 0) sumR / neutralHits else bgRgb[0]
            val refG = if (guardIsWhite && neutralHits > 0) sumG / neutralHits else bgRgb[1]
            val refB = if (guardIsWhite && neutralHits > 0) sumB / neutralHits else bgRgb[2]
            val refBright = 0.299 * refR + 0.587 * refG + 0.114 * refB
            val repR = refR.roundToInt().coerceIn(0, 255).toByte()
            val repG = refG.roundToInt().coerceIn(0, 255).toByte()
            val repB = refB.roundToInt().coerceIn(0, 255).toByte()

            var sawDarkBezel = false
            var modified = false
            for (d in 0 until actualRim) {
                val off = ((startY + stepY * d) * w + (startX + stepX * d)) * 3
                val r = allBytes[off].toInt() and 0xFF
                val g = allBytes[off + 1].toInt() and 0xFF
                val b = allBytes[off + 2].toInt() and 0xFF
                val maxC = max(r, max(g, b))
                val minC = min(r, min(g, b))
                if (maxC - minC >= 26) break

                val br = 0.299 * r + 0.587 * g + 0.114 * b
                val cDiff = abs(r - refR) + abs(g - refG) + abs(b - refB)
                val isLocalBg = abs(br - refBright) < 16.0 && cDiff < 26.0
                if (isLocalBg) {
                    if (sawDarkBezel || d >= 2) break
                    continue
                }
                if ((d < hardBezelPx && maxC < 120) || (guardIsWhite && maxC < 240)) {
                    allBytes[off] = repR; allBytes[off + 1] = repG; allBytes[off + 2] = repB
                    sawDarkBezel = true
                    modified = true
                } else {
                    break
                }
            }
            return modified
        }

        var anyModified = false
        if (touchLeft) {
            for (y in 0 until h) if (cleanInwardRay(0, y, 1, 0, w)) anyModified = true
        }
        if (touchRight) {
            for (y in 0 until h) if (cleanInwardRay(w - 1, y, -1, 0, w)) anyModified = true
        }
        if (touchTop) {
            for (x in 0 until w) if (cleanInwardRay(x, 0, 0, 1, h)) anyModified = true
        }
        if (touchBottom) {
            for (x in 0 until w) if (cleanInwardRay(x, h - 1, 0, -1, h)) anyModified = true
        }
        if (anyModified) {
            workMat.put(0, 0, allBytes)
            if (workMat !== rgbMat) workMat.copyTo(rgbMat)
        }
        if (workMat !== rgbMat) workMat.release()
    }

    private fun runMlKitTextAndObject(bmp: Bitmap): MlKitHints {
        val inputImage = InputImage.fromBitmap(bmp, 0)
        val lines = ArrayList<TextLineHint>()
        val objects = ArrayList<Rect>()

        try {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val result = Tasks.await(recognizer.process(inputImage), 4000, TimeUnit.MILLISECONDS)
            for (block in result.textBlocks) {
                for (line in block.lines) {
                    val box = line.boundingBox ?: continue
                    val len = line.text.trim().length.coerceAtLeast(1)
                    val span = (box.width() + box.height()).toFloat().coerceAtLeast(1f)
                    val cPts = line.cornerPoints?.map { Point(it.x.toDouble(), it.y.toDouble()) }?.toTypedArray()
                    lines.add(TextLineHint(box = box, angleDeg = line.angle, weight = len * span, corners = cPts))
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "ML Kit TextRecognition note: ${e.message}")
        }

        // If fewer than 2 text lines detected (e.g. photo/QR-only back side or very low contrast),
        // run ML Kit ObjectDetection as an auxiliary anchor signal.
        if (lines.size < 2) {
            try {
                val options = ObjectDetectorOptions.Builder()
                    .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                    .enableMultipleObjects()
                    .build()
                val detector = ObjectDetection.getClient(options)
                val detected = Tasks.await(detector.process(inputImage), 3000, TimeUnit.MILLISECONDS)
                for (obj in detected) {
                    objects.add(obj.boundingBox)
                }
            } catch (e: Exception) {
                Log.d(TAG, "ML Kit ObjectDetection note: ${e.message}")
            }
        }

        return MlKitHints(lines, objects)
    }

    private fun findBestCardContour(
        fgMask: Mat,
        workW: Int,
        workH: Int,
        workPxPerMm: Float,
        isPlaten: Boolean,
        mlHints: MlKitHints,
        docMode: Boolean = false
    ): Pair<RotatedRect, Float>? {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        try {
            Imgproc.findContours(fgMask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            if (contours.isEmpty()) return null

            val workArea = (workW * workH).toDouble()
            val pxPerMm2 = (workPxPerMm * workPxPerMm).toDouble()
            val padPolyPx = (4.0f * workPxPerMm).toDouble()

            data class ScoredCandidate(
                val contour: MatOfPoint,
                val rotRect: RotatedRect,
                val rectangularity: Float,
                val textHits: Int,
                val objHits: Int,
                val score: Double
            )

            val scored = ArrayList<ScoredCandidate>()

            for (c in contours) {
                val area = Imgproc.contourArea(c)
                val areaMm2 = area / pxPerMm2
                if (isPlaten) {
                    val minAreaMm2 = if (docMode) 450.0 else 1000.0
                    val minAreaFrac = if (docMode) 0.008 else 0.012
                    if (areaMm2 < minAreaMm2 || area < workArea * minAreaFrac) continue
                } else {
                    if (area < workArea * 0.03) continue
                }

                val pts2f = MatOfPoint2f(*c.toArray())
                val rotRect: RotatedRect
                try {
                    rotRect = Imgproc.minAreaRect(pts2f)
                } finally {
                    pts2f.release()
                }

                val shortPx = min(rotRect.size.width, rotRect.size.height)
                val longPx = max(rotRect.size.width, rotRect.size.height)
                if (shortPx < 12.0 || longPx < 16.0) continue

                val aspectLong = longPx / shortPx
                // Reject ultra-thin scanner bezel edge lines while allowing tall narrow receipts/slips in docMode
                val maxAspect = if (docMode) 6.5 else 2.75
                if (aspectLong > maxAspect) continue

                val shortMm = shortPx / workPxPerMm
                val longMm = longPx / workPxPerMm
                if (isPlaten) {
                    val minShortMm = if (docMode) 18.0 else 26.0
                    val minLongMm = if (docMode) 24.0 else 40.0
                    if (shortMm < minShortMm || longMm < minLongMm) continue
                    // Reject full-platen A4 outer border loops
                    if (shortPx > workW * 0.89 && longPx > workH * 0.89) continue
                    if (!docMode && longMm > 192.0 && shortMm > 145.0) continue
                }

                val rectArea = max(1.0, rotRect.size.width * rotRect.size.height)
                val rectangularity = (area / rectArea).toFloat().coerceIn(0f, 1f)

                val corners = Array(4) { Point() }
                rotRect.points(corners)
                val expandedCorners = expandQuadFromCenter(corners, padPolyPx)
                val expandedPoly2f = MatOfPoint2f(*expandedCorners)
                var textHits = 0
                var objHits = 0
                try {
                    for (line in mlHints.lines) {
                        val pt = Point(line.box.exactCenterX().toDouble(), line.box.exactCenterY().toDouble())
                        if (Imgproc.pointPolygonTest(expandedPoly2f, pt, false) >= 0.0) {
                            textHits++
                        }
                    }
                    for (obj in mlHints.objects) {
                        val pt = Point(obj.exactCenterX().toDouble(), obj.exactCenterY().toDouble())
                        if (Imgproc.pointPolygonTest(expandedPoly2f, pt, false) >= 0.0) {
                            objHits++
                        }
                    }
                } finally {
                    expandedPoly2f.release()
                }

                var sizeFitBonus = 0.0
                if (docMode) {
                    sizeFitBonus += (area / workArea) * 1400.0
                } else if (isPlaten) {
                    if (shortMm in 40.0..86.0 && longMm in 66.0..132.0) sizeFitBonus += 600.0
                    if (aspectLong in 1.25..1.90) sizeFitBonus += 400.0
                } else {
                    sizeFitBonus += (area / workArea) * 1200.0
                }

                val score = textHits * 10000.0 +
                    objHits * 2500.0 +
                    sizeFitBonus +
                    rectangularity * 650.0 +
                    min(areaMm2, if (docMode) 40000.0 else 10000.0) * 0.08

                scored.add(ScoredCandidate(c, rotRect, rectangularity, textHits, objHits, score))
            }

            if (scored.isEmpty()) return null
            scored.sortByDescending { it.score }
            val top = scored[0]

            // If white-paper card or receipt had split sections across faint bands,
            // merge nearby valid text-bearing contours.
            if (mlHints.lines.size >= 3 && top.textHits < (mlHints.lines.size * 0.75f)) {
                val mergedPoints = ArrayList<Point>(top.contour.toList())
                val maxMergeDistPx = ((if (docMode) 180.0f else 95.0f) * workPxPerMm).toDouble()
                for (i in 1 until scored.size) {
                    val other = scored[i]
                    if (other.textHits == 0 && other.objHits == 0) continue
                    val dist = hypot(other.rotRect.center.x - top.rotRect.center.x, other.rotRect.center.y - top.rotRect.center.y)
                    if (dist <= maxMergeDistPx) {
                        val combined = ArrayList<Point>(mergedPoints).apply { addAll(other.contour.toList()) }
                        val ptsMat = MatOfPoint2f(*combined.toTypedArray())
                        val combinedRot = try {
                            Imgproc.minAreaRect(ptsMat)
                        } finally {
                            ptsMat.release()
                        }
                        val cShort = min(combinedRot.size.width, combinedRot.size.height) / workPxPerMm
                        val cLong = max(combinedRot.size.width, combinedRot.size.height) / workPxPerMm
                        if (docMode || !isPlaten || (cLong <= 145.0 && cShort <= 98.0 && (cLong / max(1.0, cShort)) <= 2.4)) {
                            mergedPoints.addAll(other.contour.toList())
                        }
                    }
                }
                if (mergedPoints.size > top.contour.total()) {
                    val finalPts2f = MatOfPoint2f(*mergedPoints.toTypedArray())
                    try {
                        val mergedRot = Imgproc.minAreaRect(finalPts2f)
                        return Pair(mergedRot, top.rectangularity)
                    } finally {
                        finalPts2f.release()
                    }
                }
            }

            return Pair(top.rotRect, top.rectangularity)
        } finally {
            hierarchy.release()
            for (c in contours) c.release()
        }
    }

    private fun refineTextAngleOnHighResPatch(
        originalBmp: Bitmap,
        origCorners: Array<Point>,
        origW: Int,
        origH: Int,
        pxPerMm: Float,
        bgRgb: DoubleArray
    ): Pair<Float?, List<TextLineHint>> {
        var patchBmp: Bitmap? = null
        var ocrBmp: Bitmap? = null
        var ocrBmp180: Bitmap? = null
        try {
            val bbox = cornersToBoundingRect(origCorners, origW, origH)
            val pad = (4.0f * pxPerMm).roundToInt().coerceAtLeast(4)
            val l = (bbox.left - pad).coerceIn(0, origW - 1)
            val t = (bbox.top - pad).coerceIn(0, origH - 1)
            val r = (bbox.right + pad).coerceIn(l + 1, origW)
            val b = (bbox.bottom + pad).coerceIn(t + 1, origH)
            val w = r - l
            val h = b - t
            if (w < 20 || h < 20) return Pair(null, emptyList())

            patchBmp = Bitmap.createBitmap(originalBmp, l, t, w, h)
            val maxDim = max(w, h).toFloat()
            val targetOcrDim = if (maxDim < 700f) min(880f, maxDim * 2.5f) else 1000f
            val oScale = if (maxDim < 700f || maxDim > 1000f) targetOcrDim / maxDim else 1.0f
            ocrBmp = if (abs(oScale - 1.0f) > 0.02f) {
                Bitmap.createScaledBitmap(
                    patchBmp,
                    max(24, (w * oScale).roundToInt()),
                    max(24, (h * oScale).roundToInt()),
                    true
                )
            } else {
                patchBmp
            }

            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val res = Tasks.await(recognizer.process(InputImage.fromBitmap(ocrBmp, 0)), 4000, TimeUnit.MILLISECONDS)

            val patchLines = ArrayList<TextLineHint>()
            for (block in res.textBlocks) {
                for (line in block.lines) {
                    val lb = line.boundingBox ?: continue
                    val mappedOrig = Rect(
                        l + (lb.left / oScale).roundToInt(),
                        t + (lb.top / oScale).roundToInt(),
                        l + (lb.right / oScale).roundToInt(),
                        t + (lb.bottom / oScale).roundToInt()
                    )
                    val weight = line.text.trim().length.coerceAtLeast(1) *
                        (lb.width() + lb.height()).toFloat().coerceAtLeast(1f)
                    val cPts = line.cornerPoints?.map { Point(l + it.x.toDouble() / oScale, t + it.y.toDouble() / oScale) }?.toTypedArray()
                    patchLines.add(TextLineHint(mappedOrig, line.angle, weight, cPts))
                }
            }

            // If 0° OCR still found < 2 lines (common when card is upside-down or ~150..180° inverted),
            // test the 180°-rotated patch so readable inverted angles normalize back cleanly!
            val eval0 = scoreOcrTextResult(res)
            if (patchLines.size < 2 || eval0.score < 20.0) {
                val m = Matrix().apply { postRotate(180f) }
                ocrBmp180 = Bitmap.createBitmap(ocrBmp, 0, 0, ocrBmp.width, ocrBmp.height, m, false)
                val res180 = Tasks.await(recognizer.process(InputImage.fromBitmap(ocrBmp180, 0)), 3500, TimeUnit.MILLISECONDS)
                val eval180 = scoreOcrTextResult(res180)
                if (eval180.score > eval0.score * 1.15 && eval180.score >= 16.0) {
                    val ocrW = ocrBmp.width.toDouble()
                    val ocrH = ocrBmp.height.toDouble()
                    val lines180 = ArrayList<TextLineHint>()
                    for (block in res180.textBlocks) {
                        for (line in block.lines) {
                            val lb = line.boundingBox ?: continue
                            val mappedOrig = Rect(
                                l + ((ocrW - lb.right) / oScale).roundToInt(),
                                t + ((ocrH - lb.bottom) / oScale).roundToInt(),
                                l + ((ocrW - lb.left) / oScale).roundToInt(),
                                t + ((ocrH - lb.top) / oScale).roundToInt()
                            )
                            val weight = line.text.trim().length.coerceAtLeast(1) *
                                (lb.width() + lb.height()).toFloat().coerceAtLeast(1f)
                            val cPts = line.cornerPoints?.map {
                                Point(l + (ocrW - it.x) / oScale, t + (ocrH - it.y) / oScale)
                            }?.toTypedArray()
                            lines180.add(TextLineHint(mappedOrig, normalizeDeg(line.angle + 180f), weight, cPts))
                        }
                    }
                    if (lines180.isNotEmpty()) {
                        return Pair(computeWeightedCircularAngle(lines180), lines180)
                    }
                }
            }

            val angle = computeWeightedCircularAngle(patchLines)
            return Pair(angle, patchLines)
        } catch (e: Exception) {
            Log.d(TAG, "High-res OCR refine skipped: ${e.message}")
            return Pair(null, emptyList())
        } finally {
            if (ocrBmp180 != null && !ocrBmp180.isRecycled) ocrBmp180.recycle()
            if (ocrBmp != null && ocrBmp !== patchBmp && !ocrBmp.isRecycled) ocrBmp.recycle()
            if (patchBmp != null && !patchBmp.isRecycled) patchBmp.recycle()
        }
    }

    private data class UprightOcrEval(
        val score: Double,
        val uprightKeywords: Int,
        val invertedLines: Int
    )

    private fun isIdUprightKeyword(upToken: String): Boolean {
        return when (upToken) {
            "INDIA", "GOVT", "GOVERNMENT", "ELECTION", "COMMISSION", "INCOME", "TAX",
            "DEPARTMENT", "AADHAAR", "UNIQUE", "IDENTIFICATION", "AUTHORITY", "UIDAI",
            "PERMANENT", "ACCOUNT", "NUMBER", "CARD", "IDENTITY", "NAME", "FATHER",
            "MOTHER", "HUSBAND", "SPOUSE", "DOB", "DATE", "BIRTH", "YEAR", "GENDER",
            "MALE", "FEMALE", "ADDRESS", "SIGNATURE", "REPUBLIC", "DRIVING", "LICENCE",
            "LICENSE", "PASSPORT", "VOTER", "ELECTOR", "ELECTORAL", "PHOTO", "BENGAL",
            "PRADESH", "MAHARASHTRA", "GUJARAT", "KARNATAKA", "TAMIL", "KERALA", "DELHI",
            "ASSAM", "BIHAR", "ODISHA", "PUNJAB", "HARYANA", "RAJASTHAN", "VILLAGE",
            "TOWN", "DIST", "STATE", "PIN", "CODE", "VALID", "DOWNLOAD", "OFFICER", "HELP" -> true
            else -> false
        }
    }

    private fun scoreOcrTextResult(res: com.google.mlkit.vision.text.Text): UprightOcrEval {
        var totalScore = 0.0
        var keywords = 0
        var invertedLines = 0

        for (block in res.textBlocks) {
            for (line in block.lines) {
                val txt = line.text.trim()
                if (txt.isEmpty()) continue
                val ang = abs(normalizeDeg(line.angle))
                if (ang > 120f) {
                    invertedLines++
                    continue
                }
                if (ang > 35f) continue

                var lineScore = 0.0
                val tokens = txt.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
                for (tok in tokens) {
                    val up = tok.uppercase(Locale.US)
                    if (isIdUprightKeyword(up)) {
                        keywords++
                        lineScore += 65.0
                    }
                    val alphaLen = tok.count { it in 'A'..'Z' || it in 'a'..'z' }
                    val digitLen = tok.count { it in '0'..'9' }
                    if (alphaLen >= 3) {
                        val hasVowel = tok.any { it in "AEIOUaeiou" }
                        lineScore += if (hasVowel) (14.0 + alphaLen * 3.5) else (4.0 + alphaLen * 1.2)
                    } else if (alphaLen + digitLen >= 4) {
                        lineScore += 5.0 + (alphaLen + digitLen) * 1.0
                    } else if (alphaLen >= 2) {
                        lineScore += 3.0
                    }
                }
                val noiseCount = txt.count { it in "~^_|\\/<>{}[]*;" }
                lineScore -= noiseCount * 4.5
                totalScore += max(0.0, lineScore)
            }
        }
        return UprightOcrEval(totalScore, keywords, invertedLines)
    }

    /**
     * Stage 4 upright readability verification: runs ML Kit OCR on the tightly cropped card at 0°
     * vs 180° (upscaled for low-DPI crispness) and rotates 180° cleanly if inverted text is detected.
     */
    private fun ensureUprightOrientation180(srcBmp: Bitmap): Pair<Bitmap, Boolean> {
        var evalBmp0: Bitmap? = null
        var evalBmp180: Bitmap? = null
        try {
            val w = srcBmp.width
            val h = srcBmp.height
            if (w < 24 || h < 24) return Pair(srcBmp, false)

            val maxDim = max(w, h).toFloat()
            val targetDim = when {
                maxDim < 760f -> min(860f, maxDim * 2.6f)
                maxDim > 1100f -> 1000f
                else -> maxDim
            }
            val scale = targetDim / maxDim
            evalBmp0 = if (abs(scale - 1.0f) > 0.03f) {
                Bitmap.createScaledBitmap(
                    srcBmp,
                    max(24, (w * scale).roundToInt()),
                    max(24, (h * scale).roundToInt()),
                    true
                )
            } else {
                srcBmp
            }

            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val res0 = Tasks.await(recognizer.process(InputImage.fromBitmap(evalBmp0, 0)), 3500, TimeUnit.MILLISECONDS)
            val eval0 = scoreOcrTextResult(res0)

            // Fast-path: overwhelmingly clear upright English card text with zero inverted lines
            if (eval0.score >= 300.0 && eval0.uprightKeywords >= 3 && eval0.invertedLines == 0) {
                return Pair(srcBmp, false)
            }

            val m180 = Matrix().apply { postRotate(180f) }
            evalBmp180 = Bitmap.createBitmap(evalBmp0, 0, 0, evalBmp0.width, evalBmp0.height, m180, false)
            val res180 = Tasks.await(recognizer.process(InputImage.fromBitmap(evalBmp180, 0)), 3500, TimeUnit.MILLISECONDS)
            val eval180 = scoreOcrTextResult(res180)

            val scoreDiffStrong = eval180.score > eval0.score * 1.15 && eval180.score >= 16.0
            val invertedSignalFlip = eval0.invertedLines > 0 && eval180.invertedLines == 0 &&
                eval180.score >= eval0.score * 0.90 && eval180.score >= 8.0
            val keywordFlip = eval180.uprightKeywords > eval0.uprightKeywords &&
                eval180.score >= eval0.score * 0.95 && eval180.uprightKeywords >= 1

            Log.d(
                TAG,
                "Stage 4 Upright Check: 0°(score=${eval0.score.roundToInt()}, kw=${eval0.uprightKeywords}, inv=${eval0.invertedLines}) vs " +
                    "180°(score=${eval180.score.roundToInt()}, kw=${eval180.uprightKeywords}, inv=${eval180.invertedLines})"
            )

            if (scoreDiffStrong || invertedSignalFlip || keywordFlip) {
                val flippedBmp = Bitmap.createBitmap(srcBmp, 0, 0, w, h, m180, false)
                try { if (!srcBmp.isRecycled && flippedBmp !== srcBmp) srcBmp.recycle() } catch (_: Throwable) {}
                return Pair(flippedBmp, true)
            }
            return Pair(srcBmp, false)
        } catch (e: Exception) {
            Log.d(TAG, "Stage 4 180° check skipped: ${e.message}")
            return Pair(srcBmp, false)
        } finally {
            if (evalBmp180 != null && !evalBmp180.isRecycled) evalBmp180.recycle()
            if (evalBmp0 != null && evalBmp0 !== srcBmp && !evalBmp0.isRecycled) evalBmp0.recycle()
        }
    }

    /**
     * Full-orientation ML Kit text alignment across 0°/90°/180°/270° plus residual/diagonal
     * text skew straightening so manually cropped or quad-warped cards always come out aligned by text.
     */
    private fun alignBitmapByText(srcBmp: Bitmap, allowFineDeskew: Boolean = true): Pair<Bitmap, Float> {
        var ocrBase: Bitmap? = null
        try {
            val w = srcBmp.width
            val h = srcBmp.height
            if (w < 24 || h < 24) return Pair(srcBmp, 0f)

            val maxDim = max(w, h).toFloat()
            val targetDim = when {
                maxDim < 760f -> min(880f, maxDim * 2.6f)
                maxDim > 1100f -> 1000f
                else -> maxDim
            }
            val scale = targetDim / maxDim
            ocrBase = if (abs(scale - 1.0f) > 0.03f) {
                Bitmap.createScaledBitmap(
                    srcBmp,
                    max(24, (w * scale).roundToInt()),
                    max(24, (h * scale).roundToInt()),
                    true
                )
            } else {
                srcBmp
            }

            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

            data class CardinalCand(
                val rotDeg: Int,
                val eval: UprightOcrEval,
                val rank: Double,
                val horizMedianTilt: Float?,
                val horizCount: Int,
                val allLines: List<TextLineHint>
            )

            val candidates = ArrayList<CardinalCand>(4)
            for (rotDeg in intArrayOf(0, 90, 180, 270)) {
                var probeBmp: Bitmap? = null
                try {
                    probeBmp = if (rotDeg == 0) {
                        ocrBase
                    } else {
                        val m = Matrix().apply { postRotate(rotDeg.toFloat()) }
                        Bitmap.createBitmap(ocrBase, 0, 0, ocrBase.width, ocrBase.height, m, false)
                    }
                    val res = Tasks.await(
                        recognizer.process(InputImage.fromBitmap(probeBmp, 0)),
                        3500,
                        TimeUnit.MILLISECONDS
                    )
                    val eval = scoreOcrTextResult(res)
                    val horizHints = ArrayList<TextLineHint>()
                    val allHints = ArrayList<TextLineHint>()
                    for (block in res.textBlocks) {
                        for (line in block.lines) {
                            val lb = line.boundingBox ?: continue
                            val txt = line.text.trim()
                            if (txt.isEmpty()) continue
                            val ang = normalizeDeg(line.angle)
                            val wt = txt.length.coerceAtLeast(1) * (lb.width() + lb.height()).toFloat().coerceAtLeast(1f)
                            val hint = TextLineHint(lb, ang, wt, null)
                            allHints.add(hint)
                            if (abs(ang) <= 35f) horizHints.add(hint)
                        }
                    }
                    val horizMed = computeWeightedCircularAngle(horizHints)
                    val rank = eval.score + eval.uprightKeywords * 45.0 - eval.invertedLines * 35.0 +
                        (if (rotDeg == 0) 6.0 else 0.0)
                    candidates.add(CardinalCand(rotDeg, eval, rank, horizMed, horizHints.size, allHints))
                } finally {
                    if (probeBmp != null && probeBmp !== ocrBase && !probeBmp.isRecycled) {
                        probeBmp.recycle()
                    }
                }
            }

            val best = candidates.maxByOrNull { it.rank }
            if (best != null && (best.eval.score >= 8.0 || best.horizCount > 0)) {
                val cardinalDeg = best.rotDeg.toFloat()
                var workOut = if (best.rotDeg == 0) srcBmp else rotateBitmapExact(srcBmp, cardinalDeg)
                if (workOut !== srcBmp && !srcBmp.isRecycled) {
                    try { srcBmp.recycle() } catch (_: Throwable) {}
                }
                val residual = best.horizMedianTilt
                if (allowFineDeskew && residual != null && abs(residual) > 1.8f) {
                    workOut = deskewBitmapByTextAngle(workOut, residual)
                    val netRot = normalizeDeg(cardinalDeg - residual)
                    Log.d(
                        TAG,
                        "alignBitmapByText cardinal=${best.rotDeg}° (score=${best.eval.score.roundToInt()}, kw=${best.eval.uprightKeywords}) + deskew=${-residual}°"
                    )
                    return Pair(workOut, netRot)
                }
                Log.d(
                    TAG,
                    "alignBitmapByText cardinal=${best.rotDeg}° (score=${best.eval.score.roundToInt()}, kw=${best.eval.uprightKeywords})"
                )
                return Pair(workOut, normalizeDeg(cardinalDeg))
            }

            // Diagonal text fallback (e.g., ~35..55° where cardinal windows miss lines):
            val bestRawPass = candidates.maxByOrNull { pass ->
                pass.allLines.sumOf { it.weight.toDouble() }
            }
            val rawAngle = if (bestRawPass != null && bestRawPass.allLines.isNotEmpty()) {
                computeWeightedCircularAngle(bestRawPass.allLines)
            } else null
            if (allowFineDeskew && bestRawPass != null && rawAngle != null &&
                (abs(rawAngle) > 1.8f || bestRawPass.rotDeg != 0)
            ) {
                val preRot = if (bestRawPass.rotDeg == 0) srcBmp else rotateBitmapExact(srcBmp, bestRawPass.rotDeg.toFloat())
                if (preRot !== srcBmp && !srcBmp.isRecycled) {
                    try { srcBmp.recycle() } catch (_: Throwable) {}
                }
                val leveled = if (abs(rawAngle) > 1.8f) deskewBitmapByTextAngle(preRot, rawAngle) else preRot
                val (upright, flipped) = ensureUprightOrientation180(leveled)
                val netRot = normalizeDeg(bestRawPass.rotDeg.toFloat() - rawAngle + if (flipped) 180f else 0f)
                Log.d(
                    TAG,
                    "alignBitmapByText diagonal fallback: pass=${bestRawPass.rotDeg}°, raw=${rawAngle}°, netRot=${netRot}°"
                )
                return Pair(upright, netRot)
            }

            val (upright, flipped) = ensureUprightOrientation180(srcBmp)
            return Pair(upright, if (flipped) 180f else 0f)
        } catch (e: Exception) {
            Log.d(TAG, "alignBitmapByText skipped: ${e.message}")
            return Pair(srcBmp, 0f)
        } finally {
            if (ocrBase != null && ocrBase !== srcBmp && !ocrBase.isRecycled) {
                ocrBase.recycle()
            }
        }
    }

    private fun rotateBitmapExact(src: Bitmap, rotCwDeg: Float): Bitmap {
        val norm = normalizeDeg(rotCwDeg)
        if (abs(norm) < 0.01f) return src
        val m = Matrix().apply { postRotate(norm) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, false)
    }

    /**
     * Checks whether the outer 3px ring of roiBmp predominantly matches bgRgb (loose platen margin).
     */
    private fun computePerimeterBgFraction(bmp: Bitmap, bgRgb: DoubleArray): Float {
        val w = bmp.width
        val h = bmp.height
        if (w < 16 || h < 16) return 0f
        val bgBright = 0.299 * bgRgb[0] + 0.587 * bgRgb[1] + 0.114 * bgRgb[2]
        if (bgBright < 135.0) return 0f

        val ring = 3
        val step = max(1, min(w, h) / 80)
        var total = 0
        var bgCount = 0

        fun samplePx(x: Int, y: Int) {
            val px = bmp.getPixel(x, y)
            val r = Color.red(px).toDouble()
            val g = Color.green(px).toDouble()
            val b = Color.blue(px).toDouble()
            val d = sqrt((r - bgRgb[0]).pow(2.0) + (g - bgRgb[1]).pow(2.0) + (b - bgRgb[2]).pow(2.0))
            if (d <= 32.0) bgCount++
            total++
        }

        for (y in 0 until ring) {
            var x = 0
            while (x < w) {
                samplePx(x, y)
                samplePx(x, h - 1 - y)
                x += step
            }
        }
        for (x in 0 until ring) {
            var y = ring
            while (y < h - ring) {
                samplePx(x, y)
                samplePx(w - 1 - x, y)
                y += step
            }
        }
        return if (total > 0) bgCount.toFloat() / total.toFloat() else 0f
    }

    /**
     * Straightens a slightly tilted card bitmap using ML Kit residual angle (CW convention).
     * Expands canvas & tight-trims when uniform outer platen margin is present; otherwise rotates
     * smoothly in-place with BORDER_REPLICATE so tight manual bounds never gain artificial borders.
     */
    private fun deskewBitmapByTextAngle(srcBmp: Bitmap, textTiltCwDeg: Float): Bitmap {
        val rgba = Mat()
        val rgb = Mat()
        val rotRgb = Mat()
        val outRgba = Mat()
        var rotMat: Mat? = null
        try {
            Utils.bitmapToMat(srcBmp, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            val w = rgb.cols()
            val h = rgb.rows()
            if (w < 24 || h < 24) return srcBmp

            val bgEst = estimatePlatenBackground(rgb, isPlaten = false)
            val perimBg = computePerimeterBgFraction(srcBmp, bgEst)
            val hasOuterMargin = perimBg >= 0.35f

            val rad = Math.toRadians(abs(textTiltCwDeg).toDouble())
            val cosA = abs(cos(rad))
            val sinA = abs(sin(rad))

            val targetW: Int
            val targetH: Int
            if (hasOuterMargin || abs(textTiltCwDeg) > 10f) {
                targetW = (w * cosA + h * sinA).roundToInt().coerceAtLeast(32)
                targetH = (w * sinA + h * cosA).roundToInt().coerceAtLeast(32)
            } else {
                targetW = w
                targetH = h
            }

            rotMat = Imgproc.getRotationMatrix2D(Point(w / 2.0, h / 2.0), textTiltCwDeg.toDouble(), 1.0)
            rotMat.put(0, 2, rotMat.get(0, 2)[0] + (targetW - w) / 2.0)
            rotMat.put(1, 2, rotMat.get(1, 2)[0] + (targetH - h) / 2.0)

            Imgproc.warpAffine(
                rgb,
                rotRgb,
                rotMat,
                Size(targetW.toDouble(), targetH.toDouble()),
                Imgproc.INTER_CUBIC,
                if (hasOuterMargin) Core.BORDER_CONSTANT else Core.BORDER_REPLICATE,
                Scalar(bgEst[0], bgEst[1], bgEst[2])
            )

            val subMat = if (hasOuterMargin) {
                val pxPerMm = (min(w, h).toFloat() / 56.0f).coerceAtLeast(2.0f)
                val tightRect = findTightAxisAlignedCardRect(
                    deskewedRgb = rotRgb,
                    bgRgb = bgEst,
                    pxPerMm = pxPerMm,
                    textGuards = emptyList()
                )
                Mat(rotRgb, tightRect)
            } else {
                rotRgb
            }

            try {
                Imgproc.cvtColor(subMat, outRgba, Imgproc.COLOR_RGB2RGBA)
                val outBmp = Bitmap.createBitmap(subMat.cols(), subMat.rows(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(outRgba, outBmp)
                try { if (!srcBmp.isRecycled && outBmp !== srcBmp) srcBmp.recycle() } catch (_: Throwable) {}
                return outBmp
            } finally {
                if (subMat !== rotRgb) subMat.release()
            }
        } catch (e: Exception) {
            Log.d(TAG, "deskewBitmapByTextAngle failed: ${e.message}")
            return srcBmp
        } finally {
            rotMat?.release()
            outRgba.release()
            rotRgb.release()
            rgb.release()
            rgba.release()
        }
    }

    private fun computeWeightedCircularAngle(lines: List<TextLineHint>): Float? {
        if (lines.isEmpty()) return null
        if (lines.size == 1) return normalizeDeg(lines[0].angleDeg)

        // 36 bins of 10 degrees in [-180, 180)
        val bins = DoubleArray(36)
        for (line in lines) {
            val a = normalizeDeg(line.angleDeg)
            val idx = (((a + 180f) / 10f).toInt()).coerceIn(0, 35)
            bins[idx] += line.weight.toDouble()
        }

        // Cyclic 3-bin smooth to pick strongest dominant orientation cluster
        var bestBin = 0
        var bestVal = -1.0
        for (i in 0 until 36) {
            val v = bins[(i + 35) % 36] + 2.0 * bins[i] + bins[(i + 1) % 36]
            if (v > bestVal) {
                bestVal = v
                bestBin = i
            }
        }
        val modeDeg = -180f + bestBin * 10f + 5f

        var sumSin = 0.0
        var sumCos = 0.0
        for (line in lines) {
            val a = normalizeDeg(line.angleDeg)
            if (abs(signedAngleDiff(a, modeDeg)) <= 24f) {
                val rad = Math.toRadians(a.toDouble())
                sumSin += line.weight * sin(rad)
                sumCos += line.weight * cos(rad)
            }
        }
        if (sumSin == 0.0 && sumCos == 0.0) return normalizeDeg(lines[0].angleDeg)
        return Math.toDegrees(atan2(sumSin, sumCos)).toFloat()
    }

    private fun chooseFinalAlignAngle(
        textAngle: Float?,
        rotRectAngle: Float,
        rectangularity: Float,
        inlierCount: Int = 0
    ): Float {
        if (textAngle == null) {
            // No OCR lines available: level nearest orthogonal rectangle axis
            var a = normalizeDeg(rotRectAngle)
            while (a > 45f) a -= 90f
            while (a < -45f) a += 90f
            return a
        }

        // Preserve ML Kit's multi-line text angle when OpenCV minAreaRect deviates slightly due
        // to rounded PVC corners or clipped platen rims; snap when rectangularity & alignment agree tightly,
        // or rescue low-DPI OCR polygon jitter (>3.2 deg outlier) using a rectangular card contour.
        val snapMinRect = if (inlierCount >= 2) 0.90f else 0.84f
        val snapMaxDiff = if (inlierCount >= 2) 0.55f else 1.4f
        if (rectangularity >= 0.80f) {
            var bestEdge = rotRectAngle
            var minDiff = Float.MAX_VALUE
            for (k in -2..2) {
                val cand = normalizeDeg(rotRectAngle + k * 90f)
                val diff = abs(signedAngleDiff(cand, textAngle))
                if (diff < minDiff) {
                    minDiff = diff
                    bestEdge = cand
                }
            }
            if ((rectangularity >= snapMinRect && minDiff <= snapMaxDiff) ||
                (minDiff in 2.6f..20.0f) ||
                (inlierCount <= 2 && rectangularity >= 0.85f && minDiff <= 20.0f)
            ) {
                return bestEdge
            }
        }
        return normalizeDeg(textAngle)
    }

    /**
     * Measures residual micro-skew within ±4.8° using Canny + HoughLinesP on straight card borders
     * and internal header/divider lines, resisting ±2° OCR box jitter.
     */
    private fun measureResidualAxisAngleDeg(rgbMat: Mat, pxPerMm: Float): Float {
        val w = rgbMat.cols()
        val h = rgbMat.rows()
        if (w < 48 || h < 48) return 0f

        val scale = min(1.0, 960.0 / max(w, h).toDouble())
        val workW = (w * scale).roundToInt().coerceAtLeast(48)
        val workH = (h * scale).roundToInt().coerceAtLeast(48)
        val sPxPerMm = pxPerMm * scale.toFloat()

        val workRgb = Mat()
        val gray = Mat()
        val blur = Mat()
        val edges = Mat()
        val linesMat = Mat()

        try {
            if (workW == w && workH == h) {
                rgbMat.copyTo(workRgb)
            } else {
                Imgproc.resize(rgbMat, workRgb, Size(workW.toDouble(), workH.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            }
            Imgproc.cvtColor(workRgb, gray, Imgproc.COLOR_RGB2GRAY)
            Imgproc.GaussianBlur(gray, blur, Size(3.0, 3.0), 0.0)
            Imgproc.Canny(blur, edges, 26.0, 86.0)

            // Ignore outer 5px frame perimeter so warp borders never trigger false 0°/90° lines
            Imgproc.rectangle(edges, Point(0.0, 0.0), Point((workW - 1).toDouble(), (workH - 1).toDouble()), Scalar(0.0), 5)

            val minLineLen = max(28.0, min(workW, workH) * 0.18)
            val maxGap = max(4.5, sPxPerMm * 1.25)
            Imgproc.HoughLinesP(edges, linesMat, 1.0, Math.PI / 360.0, 36, minLineLen, maxGap)

            val count = linesMat.rows()
            if (count <= 0) return 0f

            val deltas = ArrayList<Pair<Float, Double>>(count)
            var totalWeight = 0.0
            for (i in 0 until count) {
                val seg = linesMat.get(i, 0) ?: continue
                if (seg.size < 4) continue
                val x1 = seg[0]; val y1 = seg[1]; val x2 = seg[2]; val y2 = seg[3]
                val len = hypot(x2 - x1, y2 - y1)
                if (len < minLineLen) continue

                var ang = Math.toDegrees(atan2(y2 - y1, x2 - x1))
                while (ang <= -90.0) ang += 180.0
                while (ang > 90.0) ang -= 180.0

                val res: Double = when {
                    abs(ang) <= 8.5 -> ang
                    abs(ang - 90.0) <= 8.5 -> ang - 90.0
                    abs(ang + 90.0) <= 8.5 -> ang + 90.0
                    else -> continue
                }
                val wt = len * len
                deltas.add(Pair(res.toFloat(), wt))
                totalWeight += wt
            }

            if (deltas.size < 2 || totalWeight <= 0.0) return 0f
            deltas.sortBy { it.first }

            // Find strongest coherent angular cluster within ±0.9° so sparse diagonal strokes never dilute the peak
            var bestClusterW = 0.0
            var bestClusterSum = 0.0
            for ((center, _) in deltas) {
                var wSum = 0.0
                var dSum = 0.0
                for ((d, wt) in deltas) {
                    if (abs(d - center) <= 0.9f) {
                        wSum += wt
                        dSum += d * wt
                    }
                }
                if (wSum > bestClusterW) {
                    bestClusterW = wSum
                    bestClusterSum = dSum
                }
            }
            if (bestClusterW < totalWeight * 0.45 || bestClusterW <= 0.0) return 0f
            return (bestClusterSum / bestClusterW).toFloat()
        } finally {
            workRgb.release()
            gray.release()
            blur.release()
            edges.release()
            linesMat.release()
        }
    }

    private fun computeMlKitFallbackRect(
        mlHints: MlKitHints,
        w: Int,
        h: Int,
        pxPerMmWork: Float
    ): Rect? {
        val rects = if (mlHints.objects.isNotEmpty()) {
            mlHints.objects
        } else {
            mlHints.lines.map { it.box }
        }
        if (rects.isEmpty()) return null
        var minX = w; var minY = h; var maxX = 0; var maxY = 0
        for (r in rects) {
            minX = min(minX, r.left)
            minY = min(minY, r.top)
            maxX = max(maxX, r.right)
            maxY = max(maxY, r.bottom)
        }
        val pad = (6.0f * pxPerMmWork).roundToInt().coerceAtLeast(8)
        return Rect(
            (minX - pad).coerceIn(0, w - 10),
            (minY - pad).coerceIn(0, h - 10),
            (maxX + pad).coerceIn(10, w),
            (maxY + pad).coerceIn(10, h)
        )
    }

    private fun expandQuadFromCenter(pts: Array<Point>, expandPx: Double): Array<Point> {
        var cx = 0.0
        var cy = 0.0
        for (p in pts) {
            cx += p.x; cy += p.y
        }
        cx /= pts.size
        cy /= pts.size
        return Array(pts.size) { i ->
            val dx = pts[i].x - cx
            val dy = pts[i].y - cy
            val len = max(1e-3, hypot(dx, dy))
            Point(pts[i].x + (dx / len) * expandPx, pts[i].y + (dy / len) * expandPx)
        }
    }

    private fun orderQuadPoints(pts: Array<Point>): Array<Point> {
        // Order clockwise around centroid (TL, TR, BR, BL) so arbitrary/45° quads cannot cross into a bowtie
        if (pts.size != 4) return pts
        var cx = 0.0
        var cy = 0.0
        for (p in pts) {
            cx += p.x
            cy += p.y
        }
        cx /= 4.0
        cy /= 4.0
        val cyclic = pts.sortedBy { atan2(it.y - cy, it.x - cx) }
        var tlIdx = 0
        var bestSum = Double.MAX_VALUE
        for (i in 0 until 4) {
            val s = cyclic[i].x + cyclic[i].y
            if (s < bestSum) {
                bestSum = s
                tlIdx = i
            }
        }
        return Array(4) { k -> cyclic[(tlIdx + k) % 4] }
    }

    private fun cornersToBoundingRect(pts: Array<Point>, maxW: Int, maxH: Int): Rect {
        var minX = maxW.toDouble()
        var minY = maxH.toDouble()
        var maxX = 0.0
        var maxY = 0.0
        for (p in pts) {
            minX = min(minX, p.x); minY = min(minY, p.y)
            maxX = max(maxX, p.x); maxY = max(maxY, p.y)
        }
        val l = minX.roundToInt().coerceIn(0, max(0, maxW - 1))
        val t = minY.roundToInt().coerceIn(0, max(0, maxH - 1))
        val r = maxX.roundToInt().coerceIn(min(l + 1, maxW), maxW)
        val b = maxY.roundToInt().coerceIn(min(t + 1, maxH), maxH)
        return Rect(l, t, r, b)
    }

    private fun boundingSpanX(pts: Array<Point>): Double {
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        for (p in pts) { lo = min(lo, p.x); hi = max(hi, p.x) }
        return max(0.0, hi - lo)
    }

    private fun boundingSpanY(pts: Array<Point>): Double {
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        for (p in pts) { lo = min(lo, p.y); hi = max(hi, p.y) }
        return max(0.0, hi - lo)
    }

    private fun makeOdd(v: Int): Int = if (v % 2 == 1) v else v + 1

    private fun normalizeDeg(deg: Float): Float {
        var d = deg % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }

    private fun signedAngleDiff(a: Float, b: Float): Float {
        return normalizeDeg(a - b)
    }
}
