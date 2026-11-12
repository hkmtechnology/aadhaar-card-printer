package com.hkmtech.cardprinter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.graphics.Rect
import android.util.Base64
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Offline on-device Passport & Studio Photo AI processor powered by Google ML Kit Face Detection
 * (`16.1.7`), ML Kit Selfie Segmentation, and OpenCV (`4.11.0`).
 */
object PassportAiStudio {

    private const val TAG = "HKM_PHOTO_AI"
    private const val MAX_WORK_DIM = 1600
    private const val EYE_CLOSED_THRESHOLD = 0.48f
    private const val EYE_OPEN_MIN_MIRROR = 0.52f

    private val faceLock = Any()

    @Volatile
    private var openCvReady = false

    private val faceDetector: FaceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.12f)
            .build()
        FaceDetection.getClient(options)
    }

    private fun ensureOpenCv(): Boolean {
        if (openCvReady) return true
        return try {
            openCvReady = OpenCVLoader.initLocal()
            openCvReady
        } catch (t: Throwable) {
            Log.w(TAG, "OpenCV init failed: ${t.message}")
            false
        }
    }

    /**
     * Entry point for all offline Passport Studio AI actions.
     * Supported [action] values: `align`, `open_eyes`, `retouch`, `lighting`, `auto_studio`, `analyze`.
     */
    fun processJson(
        action: String,
        imageBytes: ByteArray,
        aspectW: Float,
        aspectH: Float
    ): String {
        val report = JSONObject()
        if (imageBytes.isEmpty()) {
            return errorJson("Empty image data")
        }
        if (!ensureOpenCv()) {
            return errorJson("OpenCV runtime failed to initialize")
        }

        val decoded = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: return errorJson("Failed to decode portrait image")
        val workInit = ensureArgb8888(downscaleIfNeeded(decoded, MAX_WORK_DIM), recycleOrig = true)
        var current = workInit

        try {
            var face = detectPrimaryFace(current)
            if (face == null && action != "lighting") {
                return errorJson("No clear face detected. Ensure face is facing forward and well-lit.")
            }

            if (face != null) {
                populateFaceTelemetry(face, current, report)
            }

            when (action) {
                "analyze" -> {
                    report.put("action", "analyze")
                    return JSONObject().apply {
                        put("success", true)
                        put("report", report)
                    }.toString()
                }

                "align" -> {
                    val aligned = biometricAlignFace(current, face!!, aspectW, aspectH, report)
                    if (aligned !== current) {
                        current.recycle()
                        current = aligned
                    }
                    detectPrimaryFace(current)?.let {
                        populateFaceTelemetry(it, current, report, suffix = "After")
                        populateFaceTelemetry(it, current, report)
                    }
                    val uri = encodeJpegDataUri(current, 96)
                    report.put("action", "align")
                    return successJson(uri, report = report)
                }

                "open_eyes" -> {
                    val opened = openClosedEyesIfNeeded(current, face!!, report, forceBelowHalf = true)
                    if (opened !== current) {
                        current.recycle()
                        current = opened
                    }
                    val uri = encodeJpegDataUri(current, 96)
                    report.put("action", "open_eyes")
                    return successJson(uri, report = report)
                }

                "retouch" -> {
                    val retouched = applyFaceRetouch(current, face!!)
                    if (retouched !== current) {
                        current.recycle()
                        current = retouched
                    }
                    val uri = encodeJpegDataUri(current, 96)
                    report.put("action", "retouch")
                    return successJson(uri, report = report)
                }

                "lighting" -> {
                    val lit = applyStudioLighting(current, face)
                    if (lit !== current) {
                        current.recycle()
                        current = lit
                    }
                    val uri = encodeJpegDataUri(current, 96)
                    report.put("action", "lighting")
                    return successJson(uri, report = report)
                }

                "auto_studio" -> {
                    // 1. Biometric Face Alignment & Passport Auto-Crop
                    val aligned = biometricAlignFace(current, face!!, aspectW, aspectH, report)
                    if (aligned !== current) {
                        current.recycle()
                        current = aligned
                    }
                    var postFace = detectPrimaryFace(current) ?: face

                    // 2. Open Closed Eyes if blinking/closed
                    val eyesFixed = openClosedEyesIfNeeded(current, postFace, report, forceBelowHalf = false)
                    if (eyesFixed !== current) {
                        current.recycle()
                        current = eyesFixed
                        postFace = detectPrimaryFace(current) ?: postFace
                    }

                    // 3. Studio Lighting & Skin-Tone Balance + Natural Skin Retouch
                    val lit = applyStudioLighting(current, postFace)
                    if (lit !== current) {
                        current.recycle()
                        current = lit
                    }
                    val retouched = applyFaceRetouch(current, postFace)
                    if (retouched !== current) {
                        current.recycle()
                        current = retouched
                    }

                    // 4. Offline AI Portrait Background Cutout on the aligned + retouched frame
                    val bgRes = PassportBgRemover.removeBackgroundFromBitmap(current, recycleInput = false)
                    detectPrimaryFace(current)?.let {
                        populateFaceTelemetry(it, current, report, suffix = "After")
                        populateFaceTelemetry(it, current, report)
                    }
                    val enhancedUri = encodeJpegDataUri(current, 96)
                    report.put("action", "auto_studio")
                    report.put("bgRemoved", bgRes.success)
                    return JSONObject().apply {
                        put("success", true)
                        put("image", enhancedUri)
                        if (bgRes.success) put("cutout", bgRes.cutoutDataUri)
                        put("report", report)
                    }.toString()
                }

                else -> return errorJson("Unsupported AI Studio action: $action")
            }
        } catch (e: Exception) {
            Log.e(TAG, "AI Studio processing error ($action)", e)
            return errorJson(e.message ?: "Offline AI Studio error")
        } finally {
            if (!current.isRecycled) current.recycle()
        }
    }

    private fun detectPrimaryFace(bitmap: Bitmap): Face? {
        val input = InputImage.fromBitmap(bitmap, 0)
        val faces = synchronized(faceLock) {
            Tasks.await(faceDetector.process(input), 15, TimeUnit.SECONDS)
        }
        if (faces.isNullOrEmpty()) return null
        return faces.maxByOrNull { f ->
            val b = f.boundingBox
            b.width().toLong() * b.height().toLong()
        }
    }

    private fun extractEyePair(face: Face): Pair<PointF, PointF>? {
        val leftMark = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
            ?: centroidOfContour(face.getContour(FaceContour.LEFT_EYE)?.points)
        val rightMark = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
            ?: centroidOfContour(face.getContour(FaceContour.RIGHT_EYE)?.points)
        if (leftMark == null || rightMark == null) return null
        return if (rightMark.x <= leftMark.x) {
            Pair(rightMark, leftMark) // (viewerLeft, viewerRight)
        } else {
            Pair(leftMark, rightMark)
        }
    }

    private fun centroidOfContour(pts: List<PointF>?): PointF? {
        if (pts.isNullOrEmpty()) return null
        var sx = 0f
        var sy = 0f
        for (p in pts) {
            sx += p.x
            sy += p.y
        }
        return PointF(sx / pts.size, sy / pts.size)
    }

    private fun populateFaceTelemetry(face: Face, bmp: Bitmap, report: JSONObject, suffix: String = "") {
        val eyes = extractEyePair(face)
        val tiltDeg = if (eyes != null) {
            atan2((eyes.second.y - eyes.first.y).toDouble(), (eyes.second.x - eyes.first.x).toDouble()) * 180.0 / PI
        } else {
            face.headEulerAngleZ.toDouble()
        }
        val leftProb = face.leftEyeOpenProbability ?: -1f
        val rightProb = face.rightEyeOpenProbability ?: -1f
        report.put("faceDetected", true)
        report.put("tiltDeg$suffix", String.format(Locale.US, "%.2f", tiltDeg).toDouble())
        if (suffix.isEmpty()) {
            report.put("leftEyeOpenProb", leftProb)
            report.put("rightEyeOpenProb", rightProb)
            face.getLandmark(FaceLandmark.LEFT_EYE)?.position?.let {
                report.put("leftEyeX", it.x / max(1, bmp.width))
                report.put("leftEyeY", it.y / max(1, bmp.height))
            }
            face.getLandmark(FaceLandmark.RIGHT_EYE)?.position?.let {
                report.put("rightEyeX", it.x / max(1, bmp.width))
                report.put("rightEyeY", it.y / max(1, bmp.height))
            }
            report.put("smilingProb", face.smilingProbability ?: -1f)
            report.put("headYawDeg", face.headEulerAngleY)
            report.put("imageW", bmp.width)
            report.put("imageH", bmp.height)
        }
    }

    /**
     * Deskews image so eyes are horizontally level (0 deg) and crops/pads cleanly to ICAO/Indian Passport
     * biometric framing matching the requested [aspectW] : [aspectH] preset.
     */
    private fun biometricAlignFace(
        srcBitmap: Bitmap,
        initialFace: Face,
        aspectW: Float,
        aspectH: Float,
        report: JSONObject
    ): Bitmap {
        val rgba = Mat()
        Utils.bitmapToMat(srcBitmap, rgba)
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        rgba.release()

        var leveledBgr = bgr
        var leveledBmp = srcBitmap
        var leveledFace: Face = initialFace

        try {
            val eyes = extractEyePair(initialFace)
            if (eyes != null) {
                val dx = (eyes.second.x - eyes.first.x).toDouble()
                val dy = (eyes.second.y - eyes.first.y).toDouble()
                val tiltDeg = atan2(dy, dx) * 180.0 / PI
                report.put("correctedTiltDeg", String.format(Locale.US, "%.2f", tiltDeg).toDouble())

                if (abs(tiltDeg) > 0.35 && abs(tiltDeg) < 42.0) {
                    val cx = (eyes.first.x + eyes.second.x) * 0.5
                    val cy = (eyes.first.y + eyes.second.y) * 0.5
                    val safeZoom = min(1.16, 1.0 + abs(kotlin.math.sin(tiltDeg * PI / 180.0)) * 0.35)
                    val rotMat = Imgproc.getRotationMatrix2D(Point(cx, cy), tiltDeg, safeZoom)
                    val rotatedBgr = Mat()
                    Imgproc.warpAffine(
                        bgr,
                        rotatedBgr,
                        rotMat,
                        bgr.size(),
                        Imgproc.INTER_CUBIC,
                        Core.BORDER_REPLICATE,
                        Scalar(255.0, 255.0, 255.0)
                    )
                    rotMat.release()

                    val rotBmp = matBgrToBitmap(rotatedBgr)
                    leveledBgr = rotatedBgr
                    bgr.release()
                    leveledBmp = rotBmp
                    leveledFace = detectPrimaryFace(rotBmp) ?: initialFace
                }
            }

            val box: Rect = leveledFace.boundingBox
            val eyePair = extractEyePair(leveledFace)
            val eyeMidX = eyePair?.let { (it.first.x + it.second.x) * 0.5f } ?: box.exactCenterX()
            val eyeMidY = eyePair?.let { (it.first.y + it.second.y) * 0.5f } ?: (box.top + box.height() * 0.36f)

            val faceContours = leveledFace.getContour(FaceContour.FACE)?.points
            val chinY = faceContours?.maxOfOrNull { it.y } ?: box.bottom.toFloat()

            // Estimate true hair crown using bundled Selfie Segmentation mask above face box.
            val segCrownY = PassportBgRemover.estimateCrownY(
                leveledBmp,
                box.left.toFloat(),
                box.right.toFloat(),
                box.top.toFloat()
            )
            val minExpectedCrown = box.top - box.height() * 0.48f
            val maxExpectedCrown = box.top - box.height() * 0.16f
            val crownY = if (segCrownY != null) {
                segCrownY.coerceIn(minExpectedCrown, maxExpectedCrown)
            } else {
                box.top - box.height() * 0.28f
            }

            val fullHeadH = max(box.height() * 1.15f, chinY - crownY)
            // Standard ICAO / Indian Passport framing: head occupies ~70% of height; eyes at ~44% from top.
            val targetRatio = (aspectW / max(1f, aspectH)).coerceIn(0.45f, 1.8f)
            val desiredHFromHead = fullHeadH / 0.70f
            val desiredHFromCrown = (eyeMidY - crownY) / 0.32f
            val cropH = max(desiredHFromHead, desiredHFromCrown).roundToInt().coerceAtLeast(180)
            val cropW = (cropH * targetRatio).roundToInt().coerceAtLeast(140)

            val topY = (eyeMidY - cropH * 0.44f).roundToInt()
            val leftX = (eyeMidX - cropW * 0.50f).roundToInt()

            val imgW = leveledBgr.cols()
            val imgH = leveledBgr.rows()

            // Fit crop safely inside original image bounds while preserving exact passport aspect ratio,
            // preventing stretched border-replicate streaks on tightly framed selfies.
            val maxFitH = min(imgH, (imgW.toFloat() / targetRatio).roundToInt()).coerceAtLeast(16)
            val safeH = cropH.coerceIn(min(180, maxFitH), maxFitH)
            val safeW = (safeH * targetRatio).roundToInt().coerceIn(16, imgW)

            val clampedTop = topY.coerceIn(0, max(0, imgH - safeH))
            val clampedLeft = leftX.coerceIn(0, max(0, imgW - safeW))

            val finalRect = org.opencv.core.Rect(clampedLeft, clampedTop, safeW, safeH)
            val cropped = Mat(leveledBgr, finalRect)

            // Upscale cleanly to crisp print resolution if small, cap huge crops cleanly.
            val outH = finalRect.height.coerceIn(900, 1350)
            val outW = max(120, (outH * targetRatio).roundToInt())
            val outMat = Mat()
            val interp = if (outH >= finalRect.height) Imgproc.INTER_CUBIC else Imgproc.INTER_AREA
            Imgproc.resize(cropped, outMat, Size(outW.toDouble(), outH.toDouble()), 0.0, 0.0, interp)

            cropped.release()

            report.put("aligned", true)
            report.put("alignedEyeLevelPct", 44)
            return matBgrToBitmap(outMat).also { outMat.release() }
        } finally {
            if (!leveledBgr.empty()) leveledBgr.release()
            if (leveledBmp !== srcBitmap && !leveledBmp.isRecycled) leveledBmp.recycle()
        }
    }

    /**
     * Checks left and right eye open probabilities and restores closed eye(s):
     * - If one eye is closed and the other is open, mirrors and tone-matches the user's open eye.
     * - If both eyes are closed, renders anatomically proportioned shaded sclera, matched iris, pupil,
     *   catchlight, and upper lash curve inside the eye contour.
     */
    private fun openClosedEyesIfNeeded(
        srcBitmap: Bitmap,
        face: Face,
        report: JSONObject,
        forceBelowHalf: Boolean
    ): Bitmap {
        val leftProb = face.leftEyeOpenProbability ?: 1.0f
        val rightProb = face.rightEyeOpenProbability ?: 1.0f
        val thresh = if (forceBelowHalf) 0.52f else EYE_CLOSED_THRESHOLD

        val leftClosed = leftProb in 0f..thresh
        val rightClosed = rightProb in 0f..thresh

        report.put("leftEyeOpenProb", leftProb)
        report.put("rightEyeOpenProb", rightProb)

        if (!leftClosed && !rightClosed) {
            report.put("eyesOpened", false)
            report.put("eyeStatus", "Both eyes already open")
            return srcBitmap
        }

        val leftPos = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
            ?: centroidOfContour(face.getContour(FaceContour.LEFT_EYE)?.points)
        val rightPos = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
            ?: centroidOfContour(face.getContour(FaceContour.RIGHT_EYE)?.points)
        if (leftPos == null || rightPos == null) {
            report.put("eyesOpened", false)
            report.put("eyeStatus", "Eye landmarks unavailable")
            return srcBitmap
        }

        val iod = hypot((leftPos.x - rightPos.x).toDouble(), (leftPos.y - rightPos.y).toDouble()).toFloat()
        if (iod < 24f) {
            report.put("eyesOpened", false)
            return srcBitmap
        }

        val rgba = Mat()
        Utils.bitmapToMat(srcBitmap, rgba)
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        rgba.release()

        val eyeAxisAngleDeg = atan2(
            (if (leftPos.x > rightPos.x) leftPos.y - rightPos.y else rightPos.y - leftPos.y).toDouble(),
            abs((leftPos.x - rightPos.x).toDouble())
        ) * 180.0 / PI

        var leftOpened = false
        var rightOpened = false
        var method = "none"

        try {
            if (leftClosed && !rightClosed && rightProb >= EYE_OPEN_MIN_MIRROR) {
                mirrorOpenEyeOntoClosed(bgr, srcCenter = rightPos, dstCenter = leftPos, iod = iod)
                leftOpened = true
                method = "mirrored_open_eye"
            } else if (rightClosed && !leftClosed && leftProb >= EYE_OPEN_MIN_MIRROR) {
                mirrorOpenEyeOntoClosed(bgr, srcCenter = leftPos, dstCenter = rightPos, iod = iod)
                rightOpened = true
                method = "mirrored_open_eye"
            } else {
                val irisBgr = estimateNaturalIrisColor(bgr, face, iod)
                if (leftClosed) {
                    renderAnatomicalOpenEye(
                        bgr,
                        center = leftPos,
                        contourPts = face.getContour(FaceContour.LEFT_EYE)?.points,
                        iod = iod,
                        angleDeg = eyeAxisAngleDeg,
                        irisBgr = irisBgr
                    )
                    leftOpened = true
                }
                if (rightClosed) {
                    renderAnatomicalOpenEye(
                        bgr,
                        center = rightPos,
                        contourPts = face.getContour(FaceContour.RIGHT_EYE)?.points,
                        iod = iod,
                        angleDeg = eyeAxisAngleDeg,
                        irisBgr = irisBgr
                    )
                    rightOpened = true
                }
                method = "synthesized_iris"
            }

            report.put("eyesOpened", leftOpened || rightOpened)
            report.put("leftEyeOpened", leftOpened)
            report.put("rightEyeOpened", rightOpened)
            report.put("eyeMethod", method)
            report.put(
                "eyeStatus",
                when {
                    leftOpened && rightOpened -> "Opened both eyes ($method)"
                    leftOpened -> "Opened left eye ($method)"
                    rightOpened -> "Opened right eye ($method)"
                    else -> "No eyes modified"
                }
            )
            return matBgrToBitmap(bgr)
        } finally {
            bgr.release()
        }
    }

    private fun mirrorOpenEyeOntoClosed(
        bgr: Mat,
        srcCenter: PointF,
        dstCenter: PointF,
        iod: Float
    ) {
        val halfW = (iod * 0.21f).roundToInt().coerceAtLeast(10)
        val halfH = (iod * 0.135f).roundToInt().coerceAtLeast(7)

        val sx0 = (srcCenter.x.roundToInt() - halfW).coerceIn(0, bgr.cols() - 2)
        val sy0 = (srcCenter.y.roundToInt() - halfH).coerceIn(0, bgr.rows() - 2)
        val sx1 = (srcCenter.x.roundToInt() + halfW).coerceIn(sx0 + 2, bgr.cols())
        val sy1 = (srcCenter.y.roundToInt() + halfH).coerceIn(sy0 + 2, bgr.rows())

        val dx0 = (dstCenter.x.roundToInt() - halfW).coerceIn(0, bgr.cols() - 2)
        val dy0 = (dstCenter.y.roundToInt() - halfH).coerceIn(0, bgr.rows() - 2)
        val w = min(sx1 - sx0, bgr.cols() - dx0)
        val h = min(sy1 - sy0, bgr.rows() - dy0)
        if (w < 8 || h < 6) return

        val srcPatch = Mat(bgr, org.opencv.core.Rect(sx0, sy0, w, h)).clone()
        val mirrored = Mat()
        Core.flip(srcPatch, mirrored, 1)

        val dstRoi = Mat(bgr, org.opencv.core.Rect(dx0, dy0, w, h))

        // Match mean brightness/color in LAB space so transferred eye skin tone blends invisibly.
        val srcLab = Mat()
        val dstLab = Mat()
        Imgproc.cvtColor(mirrored, srcLab, Imgproc.COLOR_BGR2Lab)
        Imgproc.cvtColor(dstRoi, dstLab, Imgproc.COLOR_BGR2Lab)
        val srcMean = Core.mean(srcLab)
        val dstMean = Core.mean(dstLab)
        val deltaL = (dstMean.`val`[0] - srcMean.`val`[0]).coerceIn(-25.0, 25.0)
        Core.add(srcLab, Scalar(deltaL, (dstMean.`val`[1] - srcMean.`val`[1]) * 0.4, (dstMean.`val`[2] - srcMean.`val`[2]) * 0.4), srcLab)
        val matched = Mat()
        Imgproc.cvtColor(srcLab, matched, Imgproc.COLOR_Lab2BGR)
        srcLab.release()
        dstLab.release()

        val mask = Mat.zeros(h, w, CvType.CV_32FC1)
        Imgproc.ellipse(
            mask,
            Point(w * 0.5, h * 0.5),
            Size(w * 0.42, h * 0.39),
            0.0,
            0.0,
            360.0,
            Scalar(1.0),
            -1
        )
        val blurK = (max(w, h) * 0.24).roundToInt().let { if (it % 2 == 0) it + 1 else it }.coerceIn(5, 31)
        Imgproc.GaussianBlur(mask, mask, Size(blurK.toDouble(), blurK.toDouble()), 0.0)

        alphaBlendMat(matched, dstRoi, mask)

        srcPatch.release()
        mirrored.release()
        matched.release()
        mask.release()
    }

    private fun estimateNaturalIrisColor(bgr: Mat, face: Face, iod: Float): Scalar {
        val browPts = (face.getContour(FaceContour.LEFT_EYEBROW_TOP)?.points.orEmpty() +
            face.getContour(FaceContour.RIGHT_EYEBROW_TOP)?.points.orEmpty())
        if (browPts.isNotEmpty()) {
            var sumB = 0.0
            var sumG = 0.0
            var sumR = 0.0
            var count = 0
            for (pt in browPts) {
                val x = pt.x.roundToInt().coerceIn(0, bgr.cols() - 1)
                val y = pt.y.roundToInt().coerceIn(0, bgr.rows() - 1)
                val px = bgr.get(y, x)
                val lum = px[0] * 0.114 + px[1] * 0.587 + px[2] * 0.299
                if (lum < 110.0) {
                    sumB += px[0]
                    sumG += px[1]
                    sumR += px[2]
                    count++
                }
            }
            if (count >= 3) {
                val b = (sumB / count * 0.72).coerceIn(14.0, 48.0)
                val g = (sumG / count * 0.72).coerceIn(18.0, 56.0)
                val r = (sumR / count * 0.72).coerceIn(22.0, 72.0)
                return Scalar(b, g, r)
            }
        }
        return Scalar(24.0, 32.0, 46.0) // Natural dark warm brown iris default
    }

    private fun renderAnatomicalOpenEye(
        bgr: Mat,
        center: PointF,
        contourPts: List<PointF>?,
        iod: Float,
        angleDeg: Double,
        irisBgr: Scalar
    ) {
        val contourW = if (!contourPts.isNullOrEmpty()) {
            val minX = contourPts.minOf { it.x }
            val maxX = contourPts.maxOf { it.x }
            (maxX - minX).coerceIn(iod * 0.24f, iod * 0.38f)
        } else {
            iod * 0.30f
        }

        val aperW = contourW * 0.96f
        val aperH = (iod * 0.165f).coerceIn(aperW * 0.44f, aperW * 0.56f)

        val padX = (aperW * 0.28f).roundToInt().coerceAtLeast(6)
        val padY = (aperH * 0.45f).roundToInt().coerceAtLeast(6)
        val roiW = (aperW.roundToInt() + padX * 2).coerceAtLeast(16)
        val roiH = (aperH.roundToInt() + padY * 2).coerceAtLeast(12)

        // Center vertically over the closed eyelid crease so the crease is cleanly replaced.
        val cxImg = center.x.roundToInt()
        val cyImg = center.y.roundToInt()
        val x0 = (cxImg - roiW / 2).coerceIn(0, max(0, bgr.cols() - roiW))
        val y0 = (cyImg - roiH / 2).coerceIn(0, max(0, bgr.rows() - roiH))
        val actualW = min(roiW, bgr.cols() - x0)
        val actualH = min(roiH, bgr.rows() - y0)
        if (actualW < 12 || actualH < 10) return

        val dstRoi = Mat(bgr, org.opencv.core.Rect(x0, y0, actualW, actualH))
        val localMean = Core.mean(dstRoi)
        val localLScale = ((localMean.`val`[0] * 0.114 + localMean.`val`[1] * 0.587 + localMean.`val`[2] * 0.299) / 145.0)
            .coerceIn(0.68, 1.12)

        // Supersample 2x for crisp anti-aliased iris, limbal ring, lash curve, and catchlight.
        val ss = 2
        val sw = actualW * ss
        val sh = actualH * ss
        val eyeCanvas = Mat()
        Imgproc.resize(dstRoi, eyeCanvas, Size(sw.toDouble(), sh.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)

        val maskSs = Mat.zeros(sh, sw, CvType.CV_32FC1)
        val ecx = sw * 0.5
        val ecy = sh * 0.52
        val rx = aperW * ss * 0.48
        val ry = aperH * ss * 0.48

        // Soft almond aperture mask
        Imgproc.ellipse(maskSs, Point(ecx, ecy), Size(rx, ry), angleDeg, 0.0, 360.0, Scalar(1.0), -1)

        // Render warm shaded ivory sclera matched to surrounding lighting
        val scleraBase = Mat(sh, sw, CvType.CV_8UC3, Scalar(198.0 * localLScale, 204.0 * localLScale, 214.0 * localLScale))
        val scleraDark = Mat(sh, sw, CvType.CV_8UC3, Scalar(144.0 * localLScale, 150.0 * localLScale, 164.0 * localLScale))
        val lidShadowMask = Mat.zeros(sh, sw, CvType.CV_32FC1)
        Imgproc.ellipse(lidShadowMask, Point(ecx, ecy + ry * 0.14), Size(rx * 0.78, ry * 0.74), angleDeg, 0.0, 360.0, Scalar(1.0), -1)
        Imgproc.GaussianBlur(lidShadowMask, lidShadowMask, Size(15.0, 15.0), 0.0)
        alphaBlendMat(scleraBase, scleraDark, lidShadowMask)
        val scleraLayer = scleraDark
        scleraBase.release()
        lidShadowMask.release()

        // Draw iris spanning ~56% of aperture height so upper lid gently overlaps top of iris naturally
        val irisR = max(5.5, (aperH * 0.56 * ss))
        val pupilR = max(2.5, irisR * 0.44)
        val irisCenter = Point(ecx, ecy - ry * 0.08)

        val darkRim = Scalar(irisBgr.`val`[0] * 0.55, irisBgr.`val`[1] * 0.55, irisBgr.`val`[2] * 0.55)
        val midIris = Scalar(irisBgr.`val`[0] * 1.18, irisBgr.`val`[1] * 1.18, irisBgr.`val`[2] * 1.18)
        Imgproc.circle(scleraLayer, irisCenter, irisR.roundToInt(), darkRim, -1, Imgproc.LINE_AA)
        Imgproc.circle(scleraLayer, irisCenter, max(3, (irisR * 0.84).roundToInt()), midIris, -1, Imgproc.LINE_AA)
        Imgproc.circle(scleraLayer, irisCenter, pupilR.roundToInt(), Scalar(12.0, 12.0, 15.0), -1, Imgproc.LINE_AA)

        // Subtle off-center studio catchlight
        val catchPos = Point(irisCenter.x - irisR * 0.30, irisCenter.y - irisR * 0.32)
        Imgproc.circle(
            scleraLayer,
            catchPos,
            max(1, (irisR * 0.18).roundToInt()),
            Scalar(242.0, 244.0, 248.0),
            -1,
            Imgproc.LINE_AA
        )

        // Composite eyeball inside blurred almond aperture mask onto eyeCanvas
        val blurK = (ss * 5).let { if (it % 2 == 0) it + 1 else it }
        Imgproc.GaussianBlur(maskSs, maskSs, Size(blurK.toDouble(), blurK.toDouble()), 0.0)
        alphaBlendMat(scleraLayer, eyeCanvas, maskSs)
        scleraLayer.release()

        // Soft dark upper lash curve & subtle lower rim over boundary
        val lashColor = Scalar(26.0 * localLScale, 28.0 * localLScale, 36.0 * localLScale)
        val lashThick = max(2, (ss * max(1.2, aperH * 0.08)).roundToInt())
        Imgproc.ellipse(
            eyeCanvas,
            Point(ecx, ecy),
            Size(rx * 1.01, ry * 1.01),
            angleDeg,
            195.0,
            345.0,
            lashColor,
            lashThick,
            Imgproc.LINE_AA
        )

        // Downsample smoothly back to ROI size
        val downBgr = Mat()
        Imgproc.resize(eyeCanvas, downBgr, Size(actualW.toDouble(), actualH.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val downMask = Mat()
        Imgproc.resize(maskSs, downMask, Size(actualW.toDouble(), actualH.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        Imgproc.GaussianBlur(downMask, downMask, Size(3.0, 3.0), 0.0)

        // Expand blend mask slightly on upper edge so lash curve is preserved
        val dilatedMask = Mat()
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
        Imgproc.dilate(downMask, dilatedMask, k)
        k.release()

        alphaBlendMat(downBgr, dstRoi, dilatedMask)

        eyeCanvas.release()
        maskSs.release()
        downBgr.release()
        downMask.release()
        dilatedMask.release()
    }

    /**
     * Studio lighting & warm/cool white balance + gentle shadow fill without blowing highlights.
     */
    private fun applyStudioLighting(srcBitmap: Bitmap, face: Face?): Bitmap {
        val rgba = Mat()
        Utils.bitmapToMat(srcBitmap, rgba)
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        rgba.release()

        val lab = Mat()
        try {
            Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)
            val channels = ArrayList<Mat>(3)
            Core.split(lab, channels)
            val lCh = channels[0]
            val aCh = channels[1]
            val bCh = channels[2]

            // Measure face brightness if detected so exposure lifts dark portraits cleanly.
            val roiL = if (face != null) {
                val r = clampRect(face.boundingBox, lCh.cols(), lCh.rows())
                Mat(lCh, org.opencv.core.Rect(r.left, r.top, r.width(), r.height()))
            } else {
                lCh
            }
            val faceMeanL = Core.mean(roiL).`val`[0]
            if (roiL !== lCh) roiL.release()

            val targetL = 136.0
            val lShift = ((targetL - faceMeanL) * 0.35).coerceIn(-10.0, 14.0)
            if (abs(lShift) > 1.0) {
                Core.add(lCh, Scalar(lShift), lCh)
            }

            // Gentle CLAHE blend (22%) lifts uneven shadows without blowing bright clothing/skin highlights.
            val clahe = Imgproc.createCLAHE(1.3, Size(8.0, 8.0))
            val claheL = Mat()
            clahe.apply(lCh, claheL)
            Core.addWeighted(lCh, 0.78, claheL, 0.22, 0.0, lCh)
            claheL.release()

            // Subtle white-balance neutralization (40% correction toward neutral gray-world, preserving skin warmth)
            val aMean = Core.mean(aCh).`val`[0]
            val bMean = Core.mean(bCh).`val`[0]
            val aAdj = ((128.0 - aMean) * 0.25).coerceIn(-6.0, 6.0)
            val bAdj = ((128.0 - bMean) * 0.25).coerceIn(-8.0, 8.0)
            Core.add(aCh, Scalar(aAdj), aCh)
            Core.add(bCh, Scalar(bAdj), bCh)

            Core.merge(channels, lab)
            channels.forEach { it.release() }
            Imgproc.cvtColor(lab, bgr, Imgproc.COLOR_Lab2BGR)
            return matBgrToBitmap(bgr)
        } finally {
            lab.release()
            bgr.release()
        }
    }

    /**
     * Natural Studio Portrait Skin Retouch:
     * Smooths skin texture via edge-preserving bilateral filtering strictly inside face oval while masking out
     * eyes, eyebrows, and lips so crisp facial features get subtle studio micro-sharpening instead.
     */
    private fun applyFaceRetouch(srcBitmap: Bitmap, face: Face): Bitmap {
        val rgba = Mat()
        Utils.bitmapToMat(srcBitmap, rgba)
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        rgba.release()

        val faceRect = clampRect(face.boundingBox, bgr.cols(), bgr.rows())
        if (faceRect.width() < 24 || faceRect.height() < 24) {
            bgr.release()
            return srcBitmap
        }

        val roi = Mat(bgr, org.opencv.core.Rect(faceRect.left, faceRect.top, faceRect.width(), faceRect.height()))
        val smoothed = Mat()
        val sharpened = Mat()
        val skinMask = Mat.zeros(bgr.rows(), bgr.cols(), CvType.CV_32FC1)
        val detailMask = Mat.zeros(bgr.rows(), bgr.cols(), CvType.CV_32FC1)

        try {
            // Bilateral smooth inside face box
            Imgproc.bilateralFilter(roi, smoothed, 9, 42.0, 42.0)

            // Build face oval skin mask
            val facePts = face.getContour(FaceContour.FACE)?.points
            if (!facePts.isNullOrEmpty()) {
                val poly = MatOfPoint(*facePts.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
                Imgproc.fillPoly(skinMask, listOf(poly), Scalar(0.58))
                poly.release()
            } else {
                Imgproc.ellipse(
                    skinMask,
                    Point(faceRect.exactCenterX().toDouble(), faceRect.exactCenterY().toDouble()),
                    Size(faceRect.width() * 0.42, faceRect.height() * 0.46),
                    0.0,
                    0.0,
                    360.0,
                    Scalar(0.58),
                    -1
                )
            }

            // Exclude eyes, brows, and lips from skin blur; mark them for crisp studio sharpening
            val excludeContours = listOf(
                FaceContour.LEFT_EYE,
                FaceContour.RIGHT_EYE,
                FaceContour.LEFT_EYEBROW_TOP,
                FaceContour.LEFT_EYEBROW_BOTTOM,
                FaceContour.RIGHT_EYEBROW_TOP,
                FaceContour.RIGHT_EYEBROW_BOTTOM,
                FaceContour.UPPER_LIP_TOP,
                FaceContour.LOWER_LIP_BOTTOM
            )
            for (cType in excludeContours) {
                val pts = face.getContour(cType)?.points ?: continue
                if (pts.size < 3) continue
                val poly = MatOfPoint(*pts.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
                Imgproc.fillPoly(skinMask, listOf(poly), Scalar(0.0))
                Imgproc.fillPoly(detailMask, listOf(poly), Scalar(0.45))
                poly.release()
            }

            // Also exclude fallback eye landmark circles if contour was unavailable
            val padEye = max(6.0, faceRect.width() * 0.09)
            listOf(FaceLandmark.LEFT_EYE, FaceLandmark.RIGHT_EYE, FaceLandmark.MOUTH_BOTTOM).forEach { lmType ->
                face.getLandmark(lmType)?.position?.let { pt ->
                    Imgproc.circle(skinMask, Point(pt.x.toDouble(), pt.y.toDouble()), padEye.roundToInt(), Scalar(0.0), -1)
                    Imgproc.circle(detailMask, Point(pt.x.toDouble(), pt.y.toDouble()), padEye.roundToInt(), Scalar(0.45), -1)
                }
            }

            val k = (faceRect.width() * 0.07).roundToInt().let { if (it % 2 == 0) it + 1 else it }.coerceIn(5, 25)
            Imgproc.GaussianBlur(skinMask, skinMask, Size(k.toDouble(), k.toDouble()), 0.0)
            Imgproc.GaussianBlur(detailMask, detailMask, Size(7.0, 7.0), 0.0)

            val skinMaskRoi = Mat(skinMask, org.opencv.core.Rect(faceRect.left, faceRect.top, faceRect.width(), faceRect.height()))
            alphaBlendMat(smoothed, roi, skinMaskRoi)
            skinMaskRoi.release()

            // Crisp micro-sharpening on eyes/brows/lips
            val blurredForUnsharp = Mat()
            Imgproc.GaussianBlur(roi, blurredForUnsharp, Size(0.0, 0.0), 1.4)
            Core.addWeighted(roi, 1.38, blurredForUnsharp, -0.38, 0.0, sharpened)
            blurredForUnsharp.release()

            val detailMaskRoi = Mat(detailMask, org.opencv.core.Rect(faceRect.left, faceRect.top, faceRect.width(), faceRect.height()))
            alphaBlendMat(sharpened, roi, detailMaskRoi)
            detailMaskRoi.release()

            return matBgrToBitmap(bgr)
        } finally {
            roi.release()
            smoothed.release()
            sharpened.release()
            skinMask.release()
            detailMask.release()
            bgr.release()
        }
    }

    private fun alphaBlendMat(fgBgr: Mat, dstBgr: Mat, alpha32F: Mat) {
        val fgF = Mat()
        val bgF = Mat()
        val a3 = Mat()
        val invA3 = Mat()
        val weightedFg = Mat()
        val weightedBg = Mat()
        val sumF = Mat()
        try {
            fgBgr.convertTo(fgF, CvType.CV_32FC3)
            dstBgr.convertTo(bgF, CvType.CV_32FC3)
            Imgproc.cvtColor(alpha32F, a3, Imgproc.COLOR_GRAY2BGR)
            Core.absdiff(a3, Scalar(1.0, 1.0, 1.0), invA3)
            Core.multiply(fgF, a3, weightedFg)
            Core.multiply(bgF, invA3, weightedBg)
            Core.add(weightedFg, weightedBg, sumF)
            sumF.convertTo(dstBgr, CvType.CV_8UC3)
        } finally {
            fgF.release()
            bgF.release()
            a3.release()
            invA3.release()
            weightedFg.release()
            weightedBg.release()
            sumF.release()
        }
    }

    private fun clampRect(r: Rect, maxW: Int, maxH: Int): Rect {
        val l = r.left.coerceIn(0, max(0, maxW - 2))
        val t = r.top.coerceIn(0, max(0, maxH - 2))
        val ri = r.right.coerceIn(l + 1, maxW)
        val b = r.bottom.coerceIn(t + 1, maxH)
        return Rect(l, t, ri, b)
    }

    private fun downscaleIfNeeded(bmp: Bitmap, maxDim: Int): Bitmap {
        val longest = max(bmp.width, bmp.height)
        if (longest <= maxDim) return bmp
        val scale = maxDim.toFloat() / longest.toFloat()
        val w = max(1, (bmp.width * scale).roundToInt())
        val h = max(1, (bmp.height * scale).roundToInt())
        return Bitmap.createScaledBitmap(bmp, w, h, true).also {
            if (!bmp.isRecycled) bmp.recycle()
        }
    }

    private fun ensureArgb8888(bmp: Bitmap, recycleOrig: Boolean): Bitmap {
        if (bmp.config == Bitmap.Config.ARGB_8888) return bmp
        return bmp.copy(Bitmap.Config.ARGB_8888, false).also {
            if (recycleOrig && !bmp.isRecycled) bmp.recycle()
        }
    }

    private fun matBgrToBitmap(bgr: Mat): Bitmap {
        val rgba = Mat()
        Imgproc.cvtColor(bgr, rgba, Imgproc.COLOR_BGR2RGBA)
        val out = Bitmap.createBitmap(bgr.cols(), bgr.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, out)
        rgba.release()
        return out
    }

    private fun encodeJpegDataUri(bmp: Bitmap, quality: Int): String {
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        return "data:image/jpeg;base64,$b64"
    }

    private fun successJson(imageUri: String, cutoutUri: String? = null, report: JSONObject): String {
        return JSONObject().apply {
            put("success", true)
            put("image", imageUri)
            if (cutoutUri != null) put("cutout", cutoutUri)
            put("report", report)
        }.toString()
    }

    private fun errorJson(msg: String): String {
        return JSONObject().apply {
            put("success", false)
            put("error", msg)
        }.toString()
    }
}
