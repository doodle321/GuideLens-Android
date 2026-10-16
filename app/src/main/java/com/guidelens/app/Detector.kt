package com.guidelens.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.atan
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Object detection, fully on-device (SSD MobileNet, bundled detect.tflite).
 *
 * Fixes vs. the original:
 *  - FOV is per-device (constructor takes the calibrated horizontal FOV from
 *    prefs) instead of a hardcoded 62 deg that is wrong on most phones.
 *  - Classes with no entry in KNOWN_H now use FALLBACK_H (typical real-world
 *    sizes for alert-critical objects) so stop signs, hydrants, doors, bottles
 *    etc. are still alerted instead of silently skipped. estDistance() itself
 *    is unchanged and still returns null for unknowns (unit-test contract).
 *  - per-frame Log.d string building removed from the hot path.
 */
class Detector(context: Context, private val hfovDeg: Double = HFOV_DEG) {

    companion object {
        private const val TAG = "GuideLens/Detector"

        const val HFOV_DEG = 62.0
        const val MIN_SCORE = 0.5f
        const val MAX_RESULTS = 25

        val KNOWN_H = mapOf(
            "person" to 1.7, "bicycle" to 1.1, "car" to 1.5, "motorcycle" to 1.2,
            "bus" to 3.0, "truck" to 2.5, "dog" to 0.6, "cat" to 0.35,
            "bench" to 0.9, "chair" to 0.9, "potted plant" to 0.5,
            "suitcase" to 0.7, "backpack" to 0.5, "handbag" to 0.4
        )

        /**
         * Fallback real-world heights for classes that matter for collision
         * avoidance but aren't in KNOWN_H. These produce coarser distance bands
         * than the calibrated classes — good enough for the 7/5/3 m alert tiers.
         */
        val FALLBACK_H = mapOf(
            "stop sign" to 0.8, "fire hydrant" to 0.75, "street sign" to 2.2,
            "traffic light" to 2.5, "parking meter" to 1.2, "door" to 2.0,
            "bottle" to 0.3, "wine glass" to 0.2, "cup" to 0.12, "bowl" to 0.08,
            "couch" to 0.85, "dining table" to 0.75, "bed" to 0.6, "toilet" to 0.7,
            "tv" to 0.6, "laptop" to 0.25, "refrigerator" to 1.7, "oven" to 0.7,
            "sink" to 0.5, "vase" to 0.35, "umbrella" to 0.9, "mailbox" to 1.1
        )

        val FRIENDLY = mapOf(
            "potted plant" to "plant", "handbag" to "bag", "backpack" to "backpack",
            "suitcase" to "luggage", "person" to "person", "dog" to "dog", "cat" to "cat",
            "bicycle" to "bicycle", "car" to "car", "bus" to "bus", "truck" to "truck",
            "motorcycle" to "motorcycle", "bench" to "bench", "chair" to "chair",
            "stop sign" to "stop sign", "fire hydrant" to "fire hydrant",
            "dining table" to "table", "couch" to "sofa"
        )

        val LABELS = listOf(
            "object",
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train",
            "truck", "boat", "traffic light", "fire hydrant", "street sign",
            "stop sign", "parking meter", "bench", "bird", "cat", "dog", "horse",
            "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "hat",
            "backpack", "umbrella", "shoe", "eye glasses", "handbag", "tie",
            "suitcase", "frisbee", "skis", "snowboard", "sports ball", "kite",
            "baseball bat", "baseball glove", "skateboard", "surfboard",
            "tennis racket", "bottle", "plate", "wine glass", "cup", "fork",
            "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange",
            "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
            "couch", "potted plant", "bed", "mirror", "dining table", "window",
            "desk", "toilet", "door", "tv", "laptop", "mouse", "remote",
            "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
            "refrigerator", "blender", "book", "clock", "vase", "scissors",
            "teddy bear", "hair drier", "toothbrush"
        )

        val LABELS_0BASED = LABELS.drop(1)

        internal fun sideOf(r: Float): String = when {
            r < -0.35f -> "on your left"
            r > 0.35f -> "on your right"
            else -> "ahead"
        }

        /** Unit-test contract: only KNOWN_H classes produce a distance. */
        internal fun estDistance(cls: String, bhPx: Float, frameH: Int, frameW: Int): Float? =
            estDistance(cls, bhPx, frameH, frameW, HFOV_DEG, KNOWN_H)

        internal fun estDistance(
            cls: String, bhPx: Float, frameH: Int, frameW: Int,
            hfovDeg: Double, heightMap: Map<String, Double>
        ): Float? {
            val real = heightMap[cls] ?: return null
            if (bhPx <= 0) return null
            val vfov = 2 * atan(tan(Math.toRadians(hfovDeg / 2.0)) * (frameH.toDouble() / frameW))
            val fPx = frameH / (2 * tan(vfov / 2))
            val d = (real * fPx / bhPx).toFloat()
            if (d.isNaN() || d < 0.2f || d > 25f) return null
            return d
        }
    }

    private var interpreter: Interpreter? = null

    val available: Boolean
    val error: String

    init {
        var ok = false
        var err = ""
        try {
            val fd = context.assets.openFd("detect.tflite")
            val model = FileInputStream(fd.fileDescriptor).channel.map(
                FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            interpreter = Interpreter(model, Interpreter.Options().apply { numThreads = 4 })
            ok = true
            val itp = interpreter!!
            val inT = itp.getInputTensor(0)
            Log.i(TAG, "model loaded: input ${inT.shape().contentToString()} ${inT.dataType()}, " +
                    "$itp.outputTensorCount outputs, hfov=${"%.1f".format(hfovDeg)}deg")
        } catch (e: Exception) {
            err = e.message ?: "model load failed"
            Log.e(TAG, "model load failed", e)
        }
        available = ok
        error = err
    }

    data class Detection(
        val label: String,
        val score: Float,
        val box: RectF,
        val dist: Float?,
        val side: String
    )

    fun detect(frame: Bitmap): List<Detection> {
        val itp = interpreter ?: return emptyList()
        val fw = frame.width
        val fh = frame.height

        val inShape = itp.getInputTensor(0).shape()
        val ih = inShape[1]
        val iw = inShape[2]
        val scaled = Bitmap.createScaledBitmap(frame, iw, ih, true)
        val px = IntArray(iw * ih)
        scaled.getPixels(px, 0, iw, 0, 0, iw, ih)

        val input: Any = if (itp.getInputTensor(0).dataType() == DataType.UINT8) {
            val b = ByteBuffer.allocateDirect(iw * ih * 3).order(ByteOrder.nativeOrder())
            for (p in px) {
                b.put((p shr 16 and 0xFF).toByte())
                b.put((p shr 8 and 0xFF).toByte())
                b.put((p and 0xFF).toByte())
            }
            b.rewind()
            b
        } else {
            val f = ByteBuffer.allocateDirect(iw * ih * 3 * 4).order(ByteOrder.nativeOrder())
            for (p in px) {
                f.putFloat((p shr 16 and 0xFF) / 255f)
                f.putFloat((p shr 8 and 0xFF) / 255f)
                f.putFloat((p and 0xFF) / 255f)
            }
            f.rewind()
            f
        }
        if (scaled !== frame) scaled.recycle()

        var iBoxes = -1; var iClasses = -1; var iScores = -1; var iNum = -1
        for (i in 0 until itp.outputTensorCount) {
            val nm = itp.getOutputTensor(i).name().lowercase()
            when {
                "num" in nm -> iNum = i
                "box" in nm -> iBoxes = i
                "score" in nm && "multi" !in nm -> if (iScores < 0) iScores = i
                "class" in nm -> if (iClasses < 0) iClasses = i
            }
        }

        val labels: List<String>
        if (iBoxes < 0 || iScores < 0) {
            if (itp.outputTensorCount >= 4) {
                iBoxes = 0; iClasses = 1; iScores = 2; iNum = 3
                labels = LABELS_0BASED
            } else {
                Log.w(TAG, "unrecognized model: ${itp.outputTensorCount} outputs, no boxes/scores")
                return emptyList()
            }
        } else {
            labels = LABELS
        }

        val maxD = run {
            val s = itp.getOutputTensor(iBoxes).shape()
            if (s.size >= 3) s[1] else MAX_RESULTS
        }

        val boxes = Array(1) { Array(maxD) { FloatArray(4) } }
        val classes = Array(1) { FloatArray(maxD) }
        val scores = Array(1) { FloatArray(maxD) }
        val nums = FloatArray(1)

        val outputs = mutableMapOf<Int, Any>()
        outputs[iBoxes] = boxes
        if (iClasses >= 0) outputs[iClasses] = classes
        outputs[iScores] = scores
        if (iNum >= 0) outputs[iNum] = nums

        itp.runForMultipleInputsOutputs(arrayOf(input), outputs)

        val n = (if (iNum >= 0) nums[0].toInt() else maxD).coerceIn(0, maxD)
        val out = mutableListOf<Detection>()
        for (i in 0 until n) {
            val score = scores[0][i]
            if (score < MIN_SCORE) continue
            val clsInt = if (iClasses >= 0) classes[0][i].toInt() else 0
            val raw = labels.getOrNull(clsInt) ?: continue

            val top = boxes[0][i][0].coerceIn(0f, 1f)
            val left = boxes[0][i][1].coerceIn(0f, 1f)
            val bottom = boxes[0][i][2].coerceIn(0f, 1f)
            val right = boxes[0][i][3].coerceIn(0f, 1f)
            val box = RectF(left * fw, top * fh, right * fw, bottom * fh)
            if (box.height() < fh * 0.05f && box.width() < fw * 0.05f) continue

            val label = FRIENDLY[raw] ?: raw
            // known classes -> calibrated estimate; unknowns -> fallback estimate;
            // both use the per-device FOV. This is what makes "stop sign 1m away"
            // actually alert instead of being skipped as dist == null.
            val dist = estDistance(raw, box.height(), fh, fw, hfovDeg, KNOWN_H)
                ?: estDistance(raw, box.height(), fh, fw, hfovDeg, FALLBACK_H)
            val r = (box.centerX() - fw / 2f) / (fw / 2f)
            val side = sideOf(r)
            out.add(Detection(label, score, box, dist, side))
        }
        return out
    }
}

fun distWord(d: Float): String =
    if (d < 2f) "right in front of you" else "about " + (d.roundToInt().coerceAtLeast(1)) + " meters"
