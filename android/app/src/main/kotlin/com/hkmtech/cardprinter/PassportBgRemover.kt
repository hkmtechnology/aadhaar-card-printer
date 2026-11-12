package com.hkmtech.cardprinter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

data class BgRemovalResult(
    val success: Boolean,
    val cutoutDataUri: String = "",
    val error: String? = null
)

/**
 * Offline portrait background remover using Google ML Kit's bundled Selfie Segmentation model
 * paired with OpenCV matte refinement to produce transparent RGBA PNG cutouts with smooth edges.
 */
object PassportBgRemover {

    private const val TAG = "HKM_BG_REMOVER"
    private const val MAX_DIM_PX = 1600

    private val inferLock = Any()

    @Volatile
    private var openCvReady = false

    private val segmenter: Segmenter by lazy {
        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .enableRawSizeMask()
            .build()
        Segmentation.getClient(options)
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
     * Removes the background from [imageBytes] offline and returns a transparent PNG data URI.
     */
    fun removeBackground(imageBytes: ByteArray): BgRemovalResult {
        if (imageBytes.isEmpty()) {
            return BgRemovalResult(success = false, error = "Empty image data")
        }

        val decoded = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: return BgRemovalResult(success = false, error = "Failed to decode image")

        val srcBitmap = downscaleIfNeeded(decoded, MAX_DIM_PX)
        if (srcBitmap !== decoded) decoded.recycle()

        val argbBitmap = if (srcBitmap.config == Bitmap.Config.ARGB_8888) {
            srcBitmap
        } else {
            srcBitmap.copy(Bitmap.Config.ARGB_8888, false).also { srcBitmap.recycle() }
        }

        return removeBackgroundFromBitmap(argbBitmap, recycleInput = true)
    }

    /**
     * Runs Selfie Segmentation + OpenCV matte refinement directly on an ARGB_8888 [Bitmap].
     */
    internal fun removeBackgroundFromBitmap(src: Bitmap, recycleInput: Boolean = false): BgRemovalResult {
        val argbBitmap = if (src.config == Bitmap.Config.ARGB_8888) {
            src
        } else {
            src.copy(Bitmap.Config.ARGB_8888, false).also {
                if (recycleInput && !src.isRecycled) src.recycle()
            }
        }
        return try {
            val w = argbBitmap.width
            val h = argbBitmap.height
            val maskData = computeMaskArray(argbBitmap)
                ?: return BgRemovalResult(
                    success = false,
                    error = "No person detected in photo for background removal"
                )

            val alphaBytes = refineAlphaMatte(maskData.conf, maskData.width, maskData.height, w, h)
            val cutout = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            cutout.setHasAlpha(true)

            val pixels = IntArray(w * h)
            argbBitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            for (i in pixels.indices) {
                val a = alphaBytes[i].toInt() and 0xFF
                pixels[i] = if (a < 8) {
                    0
                } else {
                    (a shl 24) or (pixels[i] and 0x00FFFFFF)
                }
            }
            cutout.setPixels(pixels, 0, w, 0, 0, w, h)

            val outStream = ByteArrayOutputStream()
            cutout.compress(Bitmap.CompressFormat.PNG, 100, outStream)
            cutout.recycle()

            val b64 = Base64.encodeToString(outStream.toByteArray(), Base64.NO_WRAP)
            BgRemovalResult(success = true, cutoutDataUri = "data:image/png;base64,$b64")
        } catch (e: Exception) {
            Log.e(TAG, "Offline segmentation error", e)
            BgRemovalResult(success = false, error = e.message ?: "Offline segmentation error")
        } finally {
            if ((recycleInput || argbBitmap !== src) && !argbBitmap.isRecycled) {
                argbBitmap.recycle()
            }
        }
    }

    private fun downscaleIfNeeded(bmp: Bitmap, maxDim: Int): Bitmap {
        val longest = max(bmp.width, bmp.height)
        if (longest <= maxDim) return bmp
        val scale = maxDim.toFloat() / longest.toFloat()
        val dstW = max(1, (bmp.width * scale).roundToInt())
        val dstH = max(1, (bmp.height * scale).roundToInt())
        return Bitmap.createScaledBitmap(bmp, dstW, dstH, true)
    }

    internal data class RawMaskData(val conf: FloatArray, val width: Int, val height: Int)

    /** Runs bundled Selfie Segmentation on [argbBitmap] and returns raw confidence values if a person is present. */
    internal fun computeMaskArray(argbBitmap: Bitmap): RawMaskData? {
        val inputImage = InputImage.fromBitmap(argbBitmap, 0)
        val mask = synchronized(inferLock) {
            Tasks.await(segmenter.process(inputImage), 20, TimeUnit.SECONDS)
        }
        val maskW = mask.width
        val maskH = mask.height
        val totalMaskPx = maskW * maskH
        if (maskW <= 0 || maskH <= 0) return null

        val buffer = mask.buffer
        buffer.rewind()
        val conf = FloatArray(totalMaskPx)
        var maxConf = 0f
        var fgCount = 0
        for (i in 0 until totalMaskPx) {
            val c = buffer.float
            conf[i] = c
            if (c > maxConf) maxConf = c
            if (c >= 0.45f) fgCount++
        }
        if (maxConf < 0.25f || fgCount < (totalMaskPx * 0.01f).roundToInt()) {
            return null
        }
        return RawMaskData(conf, maskW, maskH)
    }

    /** Computes the topmost foreground pixel Y in [argbBitmap] coordinates above a detected face box. */
    internal fun estimateCrownY(argbBitmap: Bitmap, faceLeftPx: Float, faceRightPx: Float, faceTopPx: Float): Float? {
        val raw = computeMaskArray(argbBitmap) ?: return null
        val scaleX = raw.width.toFloat() / argbBitmap.width.toFloat()
        val scaleY = raw.height.toFloat() / argbBitmap.height.toFloat()
        val xStart = (faceLeftPx * scaleX).roundToInt().coerceIn(0, raw.width - 1)
        val xEnd = (faceRightPx * scaleX).roundToInt().coerceIn(xStart, raw.width - 1)
        val maxY = (faceTopPx * scaleY).roundToInt().coerceIn(0, raw.height - 1)

        val minRun = max(2, ((xEnd - xStart) * 0.14f).roundToInt())
        for (y in 0..maxY) {
            var run = 0
            val row = y * raw.width
            for (x in xStart..xEnd) {
                if (raw.conf[row + x] >= 0.45f) {
                    run++
                    if (run >= minRun) {
                        return y.toFloat() / scaleY
                    }
                } else {
                    run = 0
                }
            }
        }
        return null
    }

    /**
     * Converts raw ML Kit confidence values into a clean [0..255] alpha matte at [targetW]x[targetH].
     * Uses OpenCV soft-ramp thresholding + cubic resize + light anti-aliasing when available.
     */
    private fun refineAlphaMatte(
        conf: FloatArray,
        maskW: Int,
        maskH: Int,
        targetW: Int,
        targetH: Int
    ): ByteArray {
        val totalOut = targetW * targetH
        val alphaBytes = ByteArray(totalOut)

        // Asymmetric soft ramp [0.28 .. 0.78] trims faint background halo while preserving hair transitions.
        val lowThresh = 0.28f
        val invSpan = 1f / (0.78f - lowThresh)
        for (i in conf.indices) {
            val v = ((conf[i] - lowThresh) * invSpan).coerceIn(0f, 1f)
            conf[i] = v * v * (3f - 2f * v)
        }

        if (ensureOpenCv()) {
            val maskFloat = Mat(maskH, maskW, CvType.CV_32FC1)
            val blurredLow = Mat()
            val resizedFloat = Mat()
            val clampedFloat = Mat()
            val tempHigh = Mat()
            val alphaU8 = Mat()
            val alphaSmooth = Mat()
            try {
                maskFloat.put(0, 0, conf)
                Imgproc.GaussianBlur(maskFloat, blurredLow, Size(3.0, 3.0), 0.0)
                Imgproc.resize(blurredLow, resizedFloat, Size(targetW.toDouble(), targetH.toDouble()), 0.0, 0.0, Imgproc.INTER_CUBIC)
                // Cubic upscaling can overshoot [0, 1]; clamp strictly before 8-bit conversion.
                Core.max(resizedFloat, Scalar(0.0), tempHigh)
                Core.min(tempHigh, Scalar(1.0), clampedFloat)
                clampedFloat.convertTo(alphaU8, CvType.CV_8UC1, 255.0)
                Imgproc.GaussianBlur(alphaU8, alphaSmooth, Size(3.0, 3.0), 0.0)
                alphaSmooth.get(0, 0, alphaBytes)
                return alphaBytes
            } catch (t: Throwable) {
                Log.w(TAG, "OpenCV matte refinement fallback: ${t.message}")
            } finally {
                maskFloat.release()
                blurredLow.release()
                resizedFloat.release()
                clampedFloat.release()
                tempHigh.release()
                alphaU8.release()
                alphaSmooth.release()
            }
        }

        // Pure-Kotlin bilinear fallback if OpenCV failed to load.
        val scaleX = maskW.toFloat() / targetW.toFloat()
        val scaleY = maskH.toFloat() / targetH.toFloat()
        for (y in 0 until targetH) {
            val sy = ((y + 0.5f) * scaleY - 0.5f).coerceIn(0f, (maskH - 1).toFloat())
            val y0 = sy.toInt()
            val y1 = minOf(maskH - 1, y0 + 1)
            val fy = sy - y0
            val row0 = y0 * maskW
            val row1 = y1 * maskW
            val outRow = y * targetW
            for (x in 0 until targetW) {
                val sx = ((x + 0.5f) * scaleX - 0.5f).coerceIn(0f, (maskW - 1).toFloat())
                val x0 = sx.toInt()
                val x1 = minOf(maskW - 1, x0 + 1)
                val fx = sx - x0
                val v0 = conf[row0 + x0] * (1f - fx) + conf[row0 + x1] * fx
                val v1 = conf[row1 + x0] * (1f - fx) + conf[row1 + x1] * fx
                val v = (v0 * (1f - fy) + v1 * fy).coerceIn(0f, 1f)
                alphaBytes[outRow + x] = (v * 255f).roundToInt().toByte()
            }
        }
        return alphaBytes
    }
}
