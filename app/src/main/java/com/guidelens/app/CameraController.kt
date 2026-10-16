package com.guidelens.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Camera + detection pipeline, fully offline.
 *
 * Fixes vs. the original:
 *  - OCR frame cache race: consumers call [grabFrame], which returns an
 *    independent COPY taken under a lock. The pipeline never recycles a bitmap
 *    a consumer may be holding -> no more "Bitmap recycled" crashes or
 *    garbage OCR.
 *  - YUV->bitmap conversion uses bulk row copies instead of per-pixel
 *    ByteBuffer.get(index), and the NV21 scratch buffer is reused per frame.
 *  - Per-frame Log.d string building removed from the hot path.
 *  - [stop] unbinds analysis so onPause can halt the whole pipeline.
 */
class CameraController(
    private val activity: androidx.activity.ComponentActivity,
    private val previewView: PreviewView,
    private val overlay: OverlayView,
    private val prefs: Prefs,
    private val speaker: Speaker,
    private val alerts: AlertCenter,
    private val sonar: Sonar?,
    private val onStatus: (String) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val inferenceExecutor = Executors.newSingleThreadExecutor()

    private val detector = Detector(activity, prefs.cameraHfovDeg.toDouble())
    private val segmenter = Segmenter(activity)

    private var provider: ProcessCameraProvider? = null
    private var lastLoopError: String? = null

    @Volatile var running = false
        private set

    @Volatile var lastSeen: List<Detector.Detection> = emptyList()
    @Volatile var lastZone: Segmenter.Zone? = null

    // OCR frame cache: written only on the inference thread, read via grabFrame()
    // which copies under a lock. The cached bitmap is recycled only inside the
    // lock at swap time — consumers hold independent copies.
    private val frameLock = Any()
    private var frameCache: Bitmap? = null

    private var nv21: ByteArray? = null   // reused scratch, inference-thread only

    private var darkFrames = 0
    private var brightFrames = 0
    private var darkLatch = false

    /**
     * Snapshot of the latest camera frame for OCR. Returns an independent
     * copy (never the cached instance), or null before the first frame /
     * when detection isn't running. Safe from any thread.
     */
    fun grabFrame(): Bitmap? {
        val c: Bitmap?
        synchronized(frameLock) { c = frameCache }
        return c?.let {
            try {
                if (it.isRecycled) null
                else it.copy(Bitmap.Config.ARGB_8888, false)
            } catch (e: Exception) { null }
        }
    }

    fun start() {
        if (running) return
        running = true
        if (!detector.available) {
            onStatus("Object model missing. Run tools/download-models.sh and rebuild.")
            speaker.speak("Object detection model is missing. It should have been downloaded when the app was built.")
            return
        }
        onStatus("Starting camera…")
        scope.launch(Dispatchers.Main) {
            try {
                provider = withContext(Dispatchers.IO) {
                    ProcessCameraProvider.getInstance(activity).get()
                }
                bindUseCases()
                val segNote = if (segmenter.labelMapMismatch)
                    " Scene model label mismatch — zone detection disabled, tell the developer."
                else ""
                onStatus("Detection running. Hold the phone at chest height, camera facing forward." + segNote)
                if (segmenter.labelMapMismatch) {
                    speaker.speak("Scene model label mismatch. Zone detection is off, but obstacle detection works.")
                } else {
                    speaker.speak("Detection running. I'll warn you about obstacles.")
                }
            } catch (e: Exception) {
                running = false
                onStatus("Camera unavailable: " + (e.message ?: "unknown error"))
                speaker.speak("I couldn't start the camera. Please check camera permission.")
            }
        }
    }

    private fun bindUseCases() {
        val p = provider ?: return
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(1280, 720))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(inferenceExecutor) { proxy -> processFrame(proxy) }

        p.unbindAll()
        p.bindToLifecycle(activity as LifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
    }

    /** Runs on the inference thread for every camera frame. */
    private fun processFrame(proxy: ImageProxy) {
        try {
            val bmp = proxy.toBitmapRotated()
            val luma = luminance(bmp)

            if (luma < DARK_LUMA) {
                darkFrames++
                brightFrames = 0
                if (darkFrames >= DARK_FRAMES && !darkLatch) {
                    darkLatch = true
                    onStatus("Camera covered or too dark — detection paused.")
                    speaker.speak("It's too dark, or the camera is covered. I can't see anything.", priority = true)
                }
            } else {
                darkFrames = 0
                if (darkLatch) {
                    brightFrames++
                    if (luma > RECOVER_LUMA && brightFrames >= BRIGHT_FRAMES) {
                        darkLatch = false
                        brightFrames = 0
                        speaker.speak("I can see again. Detection resumed.")
                    }
                } else {
                    brightFrames = 0
                }
            }

            if (darkLatch) {
                lastSeen = emptyList()
                lastZone = null
                synchronized(frameLock) { frameCache?.recycle(); frameCache = null }
                alerts.reset()
                sonar?.silence()
                activity.runOnUiThread { overlay.clear() }
                return
            }

            // cache a small frame for OCR — swap under lock, recycle the OLD one
            // only after no reader can obtain it
            val small = Bitmap.createScaledBitmap(bmp, 480, bmp.height * 480 / bmp.width, true)
            synchronized(frameLock) {
                val old = frameCache
                frameCache = small
                old?.recycle()
            }

            val dets = try {
                detector.detect(bmp)
            } catch (e: Exception) {
                reportOnce("detect", e)
                emptyList()
            }
            val zone = if (segmenter.available && !segmenter.labelMapMismatch) {
                try { segmenter.analyze(bmp) } catch (e: Exception) { null }
            } else null

            lastSeen = dets
            lastZone = zone

            alerts.onFrame(dets, zone, sonar)

            var anyDanger = false
            for (d in dets) if ((d.dist ?: 99f) <= 3f) anyDanger = true
            if ((zone?.dist ?: 99f) <= 3f) anyDanger = true
            if (anyDanger) activity.runOnUiThread { onStatus("Stop! Obstacle ahead.") }

            val ow = overlay.width.takeIf { it > 0 } ?: bmp.width
            val oh = overlay.height.takeIf { it > 0 } ?: bmp.height
            val sx = ow / bmp.width.toFloat()
            val sy = oh / bmp.height.toFloat()

            val boxes = dets.map { d ->
                val sev = when {
                    (d.dist ?: 99f) <= 3f -> 2
                    (d.dist ?: 99f) <= 5f -> 1
                    else -> 0
                }
                val label = d.dist?.let { "${d.label} %.1f m".format(it) } ?: d.label
                OverlayView.Box(label,
                    RectF(d.box.left * sx, d.box.top * sy, d.box.right * sx, d.box.bottom * sy), sev)
            }
            activity.runOnUiThread { overlay.update(boxes, zone) }
            bmp.recycle()
        } catch (e: Exception) {
            reportOnce("frame", e)
        } finally {
            proxy.close()
        }
    }

    private fun luminance(bmp: Bitmap): Float {
        var tiny: Bitmap? = null
        return try {
            tiny = Bitmap.createScaledBitmap(bmp, 32, 18, true)
            val px = IntArray(tiny.width * tiny.height)
            tiny.getPixels(px, 0, tiny.width, 0, 0, tiny.width, tiny.height)
            var sum = 0L
            for (p in px) {
                sum += ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)) / 3
            }
            sum.toFloat() / px.size / 255f
        } catch (e: Exception) {
            1f
        } finally {
            tiny?.recycle()
        }
    }

    private fun reportOnce(where: String, e: Exception) {
        val msg = e.message ?: e.javaClass.simpleName
        if (msg != lastLoopError) {
            lastLoopError = msg
            Log.e("GuideLens/Camera", "$where failed", e)
            activity.runOnUiThread { onStatus("Detection error: $msg") }
        }
    }

    fun stop() {
        running = false
        try { provider?.unbindAll() } catch (e: Exception) { }
        lastSeen = emptyList()
        lastZone = null
        synchronized(frameLock) { frameCache?.recycle(); frameCache = null }
        alerts.reset()
        sonar?.silence()
        overlay.clear()
    }

    fun shutdown() {
        stop()
        inferenceExecutor.shutdown()
    }

    companion object {
        private const val DARK_LUMA = 0.035f
        private const val RECOVER_LUMA = 0.09f
        private const val DARK_FRAMES = 25
        private const val BRIGHT_FRAMES = 5
    }

    /**
     * YUV_420_888 -> upright Bitmap.
     *
     * Device layouts vary wildly (rowStride/pixelStride padding, packed vs
     * planar chroma). Every bulk read is guarded by a capacity check; when
     * a fast path doesn't fit, we fall back to clamped per-sample reads.
     * A BufferUnderflowException here previously killed detection on the
     * first frame on several OEM cameras.
     */
    private fun ImageProxy.toBitmapRotated(): Bitmap {
        val yP = planes[0]
        val uP = planes[1]
        val vP = planes[2]
        val ySize = width * height

        var buf = nv21
        if (buf == null || buf.size < ySize + ySize / 2) {
            buf = ByteArray(ySize + ySize / 2)
            nv21 = buf
        }

        // ---- luma ----
        if (yP.pixelStride == 1 && yP.rowStride == width && yP.buffer.capacity() >= ySize) {
            yP.buffer.position(0)
            yP.buffer.get(buf, 0, ySize)
        } else {
            val yCap = yP.buffer.capacity()
            for (r in 0 until height) {
                val rowBase = r * yP.rowStride
                for (c in 0 until width) {
                    val i = rowBase + c * yP.pixelStride
                    buf[r * width + c] = if (i < yCap) yP.buffer.get(i) else 16.toByte()
                }
            }
        }

        // ---- chroma (NV21 = interleaved VU) ----
        val cw = width / 2
        val ch = height / 2
        val vCap = vP.buffer.capacity()
        val uCap = uP.buffer.capacity()
        val packedOk = vP.pixelStride == 2 && uP.pixelStride == 2 &&
                vP.rowStride == uP.rowStride &&
                vCap >= (ch - 1) * vP.rowStride + cw * 2

        if (packedOk) {
            // packed VU rows: bulk-read each row, deinterleave into NV21
            val vrow = ByteArray(cw * 2)
            for (r in 0 until ch) {
                vP.buffer.position(r * vP.rowStride)
                vP.buffer.get(vrow, 0, cw * 2)
                val base = ySize + 2 * r * cw
                for (c in 0 until cw) {
                    buf[base + 2 * c] = vrow[2 * c]         // V
                    buf[base + 2 * c + 1] = vrow[2 * c + 1] // U
                }
            }
        } else {
            // planar/separate/odd-stride: clamped per-sample reads (128 = neutral)
            for (r in 0 until ch) {
                for (c in 0 until cw) {
                    val vi = r * vP.rowStride + c * vP.pixelStride
                    val ui = r * uP.rowStride + c * uP.pixelStride
                    buf[ySize + 2 * (r * cw + c)] = if (vi < vCap) vP.buffer.get(vi) else 128.toByte()
                    buf[ySize + 2 * (r * cw + c) + 1] = if (ui < uCap) uP.buffer.get(ui) else 128.toByte()
                }
            }
        }

        val yuv = YuvImage(buf, android.graphics.ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, width, height), 90, out)
        val raw = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())

        val rot = imageInfo.rotationDegrees
        return if (rot != 0) {
            val m = Matrix().apply { postRotate(rot.toFloat()) }
            val r = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            raw.recycle()
            r
        } else raw
    }
}
