package com.example.eringchecker.image

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

// ---------------------------------------------------------------- AI 判定の調整用定数

/** 「OK」の確率がこの値以上のときだけ「Eリング あり」とする（0〜1）。 */
const val AI_OK_THRESHOLD = 0.90f

/** 同じ判定がこの回数続いたら表示を切り替える（チラつき防止）。 */
const val AI_STABLE_COUNT = 3

/** モデルに通す間隔[ms]。200ms なら 1 秒に約 5 回。 */
const val AI_INFERENCE_INTERVAL_MS = 200L

/** モデルに入れる正方形の大きさ。映像の短辺に対する割合（1.0 = 映像の中央いっぱいの正方形）。 */
const val AI_CROP_FRACTION = 1.0f

/** テスト用：画面の隅に OK・NG・工具なしの％を表示する。不要になったら false にする。 */
const val AI_SHOW_DEBUG_SCORES = true

// ----------------------------------------------------------------

/** モデルが出した各ラベルの確率（0〜1）。 */
data class AiScores(val ok: Float, val ng: Float, val noTool: Float)

/**
 * Teachable Machine で作った画像分類モデル（assets の model_unquant.tflite / labels.txt）で
 * 工具先端の画像を「OK」「NG」「工具なし」に分類する。
 *
 * 入力は Teachable Machine の画像モデルに合わせ、中央の正方形を 224×224 に縮小し、
 * RGB を -1〜1（value / 127.5 - 1）に変換して渡す。
 * 解析スレッドからのみ呼ぶこと（Interpreter はスレッドセーフではない）。
 */
class AiRingClassifier(context: Context) : Closeable {

    private val interpreter: Interpreter
    private val inputSize: Int
    private val okIndex: Int
    private val ngIndex: Int
    private val noToolIndex: Int
    private val output: Array<FloatArray>
    private val input: ByteBuffer
    private val pixels: IntArray

    init {
        val labels = context.assets.open(LABELS_FILE).bufferedReader(Charsets.UTF_8).useLines { lines ->
            // 「0 OK」のように先頭に番号が付いているので取り除く
            lines.map { it.trim().replaceFirst(Regex("^\\d+\\s+"), "") }.filter { it.isNotEmpty() }.toList()
        }
        okIndex = labels.indexOf(LABEL_OK)
        ngIndex = labels.indexOf(LABEL_NG)
        noToolIndex = labels.indexOfFirst { it in LABELS_NO_TOOL }
        require(okIndex >= 0 && ngIndex >= 0 && noToolIndex >= 0) { "labels.txt のラベルが想定と違います: $labels" }

        val modelBytes = context.assets.open(MODEL_FILE).use { it.readBytes() }
        val model = ByteBuffer.allocateDirect(modelBytes.size).order(ByteOrder.nativeOrder()).put(modelBytes)
        interpreter = Interpreter(model)

        val inShape = interpreter.getInputTensor(0).shape() // [1, 224, 224, 3]
        inputSize = inShape[1]
        val outShape = interpreter.getOutputTensor(0).shape() // [1, ラベル数]
        require(outShape.last() == labels.size) { "モデルの出力数(${outShape.last()})とラベル数(${labels.size})が違います" }

        output = arrayOf(FloatArray(labels.size))
        input = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3).order(ByteOrder.nativeOrder())
        pixels = IntArray(inputSize * inputSize)
    }

    /** [frame]（表示向きにそろえた、カメラの元の映像全体）の中央の正方形を分類する。 */
    fun classify(frame: Bitmap): AiScores {
        val side = (minOf(frame.width, frame.height) * AI_CROP_FRACTION).toInt().coerceAtLeast(1)
        val left = (frame.width - side) / 2
        val top = (frame.height - side) / 2
        val square = Bitmap.createBitmap(frame, left, top, side, side)
        val scaled = Bitmap.createScaledBitmap(square, inputSize, inputSize, true)

        scaled.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        input.rewind()
        for (p in pixels) {
            input.putFloat(((p shr 16) and 0xFF) / 127.5f - 1f)
            input.putFloat(((p shr 8) and 0xFF) / 127.5f - 1f)
            input.putFloat((p and 0xFF) / 127.5f - 1f)
        }
        input.rewind()
        interpreter.run(input, output)

        val probs = output[0]
        return AiScores(ok = probs[okIndex], ng = probs[ngIndex], noTool = probs[noToolIndex])
    }

    override fun close() = interpreter.close()

    companion object {
        private const val MODEL_FILE = "model_unquant.tflite"
        private const val LABELS_FILE = "labels.txt"
        private const val LABEL_OK = "OK"
        private const val LABEL_NG = "NG"
        /** 「工具なし」の表記ゆれ。どれか 1 つが labels.txt にあればよい。 */
        private val LABELS_NO_TOOL = setOf("工具なし", "工具無し")

        /**
         * 1 回分の確率から判定を決める。
         * - 「工具なし」が最も高い → [Verdict.NO_TARGET]（表示しない）
         * - 「NG」が最も高い → [Verdict.NO_RING]
         * - 「OK」が [AI_OK_THRESHOLD] 以上 → [Verdict.HAS_RING]
         * - それ以外 → [Verdict.UNCERTAIN]（判定できません）
         */
        fun decide(s: AiScores): Verdict = when {
            s.noTool >= s.ok && s.noTool >= s.ng -> Verdict.NO_TARGET
            s.ng >= s.ok -> Verdict.NO_RING
            s.ok >= AI_OK_THRESHOLD -> Verdict.HAS_RING
            else -> Verdict.UNCERTAIN
        }
    }
}

/**
 * 表示のチラつきを抑える。同じ判定が [AI_STABLE_COUNT] 回続いたら表示を切り替える。
 * ただし「Eリング あり」を表示中に OK 以外の判定が出たときは、見逃し防止のため待たずにすぐ切り替える。
 */
class AiVerdictStabilizer {

    var shown: Verdict = Verdict.NO_TARGET
        private set
    private var candidate: Verdict = Verdict.NO_TARGET
    private var count = 0

    /** 新しい判定を入れ、表示すべき判定を返す。 */
    fun update(raw: Verdict): Verdict {
        if (raw == candidate) count++ else { candidate = raw; count = 1 }
        if (shown == Verdict.HAS_RING && raw != Verdict.HAS_RING) {
            shown = raw
        } else if (count >= AI_STABLE_COUNT) {
            shown = candidate
        }
        return shown
    }
}
