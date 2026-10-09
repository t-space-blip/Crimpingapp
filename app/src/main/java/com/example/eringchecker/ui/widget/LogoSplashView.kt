package com.example.eringchecker.ui.widget

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.eringchecker.R

/**
 * 起動ロゴ画面。黒地の中央に「HANTEIKUN」、右下に「Ver.2.0」、最下部に「© 2026 TSUNOYAN WORKS」を描き、
 * 最初のフレームから [TOTAL_SEC] 秒のアニメーション（フェードイン → 光の帯 → キラーン → フェードアウト）を再生する。
 * 位置はすべて画面サイズと「HANTEIKUN」の実際の字形の外形から計算する。
 */
class LogoSplashView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** アニメーションが最後まで再生されたときに1回だけ呼ばれる。 */
    var onFinished: (() -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val typeface = ResourcesCompat.getFont(context, R.font.archivo_black)

    private val title = "HANTEIKUN"
    private val version = context.getString(R.string.home_version)
    private val copyrightYear = context.getString(R.string.splash_copyright_year)
    private val copyrightName = context.getString(R.string.splash_copyright_name)

    private var startTime = -1L
    private var finished = false
    private var navBarInset = 0

    // ---- 「HANTEIKUN」 ----
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = this@LogoSplashView.typeface
        letterSpacing = 0.04f
    }
    private val titlePath = Path()
    /** 「HANTEIKUN」の字形の外形（左端＝Hの左端、右端＝Nの右端）。 */
    private val titleBounds = RectF()
    private val titleFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titleOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.5f * dp
        color = Color.argb((255 * 0.35f).toInt(), 255, 255, 255)
    }

    // ---- 光の帯 ----
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bandGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        if (DrawUtils.blurSupported) {
            maskFilter = BlurMaskFilter(6 * dp, BlurMaskFilter.Blur.NORMAL)
        } else {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
        }
    }
    private val bandMatrix = Matrix()
    private var bandShader: Shader? = null
    private var bandGlowShader: Shader? = null

    // ---- 「Ver.2.0」 ----
    private val versionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = this@LogoSplashView.typeface
        letterSpacing = 0.12f
        textSize = sp(13f)
        color = Color.parseColor("#9AA1AB")
    }
    private var versionX = 0f
    private var versionBaseline = 0f
    /** 「Ver.2.0」のフェードインを始める時刻[秒]。字形の位置から [onSizeChanged] で求める。 */
    private var versionFadeStart = Float.MAX_VALUE

    // ---- 「© 2026 TSUNOYAN WORKS」 ----
    private val copyrightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = this@LogoSplashView.typeface
        letterSpacing = 0.32f
        textSize = sp(10f)
    }

    // ---- キラーン ----
    private val sparkleMainRay = Path()
    private val sparkleSubRay = Path()
    private val sparkleRayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sparkleGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sparkleDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    init {
        // 細長いひし形の光線（中心が太く先端が尖る）。上下方向に作り、90°回して左右にも使う
        diamond(sparkleMainRay, halfLength = 60 * dp, halfWidth = 2.6f * dp)
        diamond(sparkleSubRay, halfLength = 30 * dp, halfWidth = 1.8f * dp)
        sparkleRayPaint.shader = RadialGradient(
            0f, 0f, 60 * dp,
            intArrayOf(Color.WHITE, Color.WHITE, Color.argb(0, 255, 255, 255)),
            floatArrayOf(0f, 0.35f, 1f),
            Shader.TileMode.CLAMP,
        )
        sparkleGlowPaint.shader = RadialGradient(
            0f, 0f, 26 * dp,
            intArrayOf(Color.argb(150, 255, 255, 255), Color.argb(60, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )

        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            navBarInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            invalidate()
            insets
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w == 0 || h == 0) return

        // 字形の外形の幅が画面幅の76%になる文字サイズを求める（基準サイズで測って比例させる）
        titlePaint.textSize = 100f
        titlePaint.getTextPath(title, 0, title.length, 0f, 0f, titlePath)
        titlePath.computeBounds(titleBounds, true)
        titlePaint.textSize = 100f * (w * 0.76f) / titleBounds.width()

        // 字形の外形が画面の中央に来るように配置する
        titlePaint.getTextPath(title, 0, title.length, 0f, 0f, titlePath)
        titlePath.computeBounds(titleBounds, true)
        val dx = w / 2f - titleBounds.centerX()
        titlePath.offset(dx, h / 2f - titleBounds.centerY())
        titlePath.computeBounds(titleBounds, true)

        // 「Ver.2.0」は、光の帯の中心が「K」と「U」の間（Kの右端とUの左端の中点）を通過する瞬間にフェードインを始める
        val kuIndex = title.indexOf("KU") + 1
        val glyphPath = Path()
        val glyphBounds = RectF()
        titlePaint.getTextPath(title, 0, kuIndex, 0f, 0f, glyphPath)
        glyphPath.computeBounds(glyphBounds, true)
        val kRight = glyphBounds.right
        titlePaint.getTextPath(title, kuIndex, kuIndex + 1, titlePaint.measureText(title, 0, kuIndex), 0f, glyphPath)
        glyphPath.computeBounds(glyphBounds, true)
        val kuGapX = (kRight + glyphBounds.left) / 2f + dx
        versionFadeStart = BAND_START_SEC + (BAND_END_SEC - BAND_START_SEC) * bandProgressAt(kuGapX)

        titleFill.shader = LinearGradient(
            0f, titleBounds.top, 0f, titleBounds.bottom,
            intArrayOf(
                Color.parseColor("#FFFFFF"), Color.parseColor("#EEF1F4"), Color.parseColor("#A7AEB8"),
                Color.parseColor("#F7F9FB"), Color.parseColor("#C9CED5"), Color.parseColor("#FFFFFF"),
            ),
            floatArrayOf(0f, 0.28f, 0.48f, 0.54f, 0.74f, 1f),
            Shader.TileMode.CLAMP,
        )

        // 光の帯：文字幅の12%。中心（x=0）が最も明るく両端は透明。描画時に位置と傾きを行列で与える
        val half = titleBounds.width() * 0.12f / 2f
        bandShader = LinearGradient(
            -half, 0f, half, 0f,
            intArrayOf(
                Color.argb(0, 255, 255, 255), Color.argb(110, 255, 255, 255), Color.argb(245, 255, 255, 255),
                Color.argb(110, 255, 255, 255), Color.argb(0, 255, 255, 255),
            ),
            floatArrayOf(0f, 0.3f, 0.5f, 0.7f, 1f),
            Shader.TileMode.CLAMP,
        )
        bandGlowShader = LinearGradient(
            -half * 2f, 0f, half * 2f, 0f,
            intArrayOf(Color.argb(0, 255, 255, 255), Color.argb(70, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            null,
            Shader.TileMode.CLAMP,
        )

        // 「Ver.2.0」：字形の右端を「N」の右端にそろえ、上端を「HANTEIKUN」の下10dpに置く
        val versionPath = Path()
        val versionBounds = RectF()
        versionPaint.getTextPath(version, 0, version.length, 0f, 0f, versionPath)
        versionPath.computeBounds(versionBounds, true)
        versionX = titleBounds.right - versionBounds.right
        versionBaseline = titleBounds.bottom + 10 * dp - versionBounds.top
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        if (startTime < 0) startTime = now
        val t = (now - startTime) / 1000f

        canvas.drawColor(Color.BLACK)
        val fadeOut = 1f - progress(t, 2.7f, 3.0f)

        // 1. 「HANTEIKUN」と「©」表記のフェードイン
        val mainAlpha = progress(t, 0f, 0.4f) * fadeOut
        drawTitle(canvas, mainAlpha)
        drawCopyright(canvas, mainAlpha)

        // 2. 光の帯（文字の形の中だけ）
        if (t in BAND_START_SEC..BAND_END_SEC) drawBand(canvas, progress(t, BAND_START_SEC, BAND_END_SEC), mainAlpha)

        // 3. 「Ver.2.0」のフェードイン（光の帯の中心が「K」と「U」の間を通過した瞬間から）
        val versionAlpha = progress(t, versionFadeStart, versionFadeStart + VERSION_FADE_SEC) * fadeOut
        if (versionAlpha > 0f) {
            versionPaint.alpha = (255 * versionAlpha).toInt()
            canvas.drawText(version, versionX, versionBaseline, versionPaint)
        }

        // 4. 「N」の右上の角でキラーン
        if (t in SPARKLE_TIMES.first()..SPARKLE_TIMES.last()) drawSparkle(canvas, t, fadeOut)

        if (t < TOTAL_SEC) {
            postInvalidateOnAnimation()
        } else if (!finished) {
            finished = true
            onFinished?.invoke()
        }
    }

    /** 光の帯の中心の通り道：「H」の左のすぐ外側（帯の半幅ぶん外）から「N」の右のすぐ外側まで。 */
    private val bandStartX get() = titleBounds.left - titleBounds.width() * 0.12f / 2f
    private val bandEndX get() = titleBounds.right + titleBounds.width() * 0.12f / 2f

    /** 光の帯の中心が x を通過するときの進み具合（0〜1）。 */
    private fun bandProgressAt(x: Float): Float = ((x - bandStartX) / (bandEndX - bandStartX)).coerceIn(0f, 1f)

    private fun drawTitle(canvas: Canvas, alpha: Float) {
        if (alpha <= 0f) return
        titleFill.alpha = (255 * alpha).toInt()
        canvas.drawPath(titlePath, titleFill)
        titleOutline.alpha = (255 * 0.35f * alpha).toInt()
        canvas.drawPath(titlePath, titleOutline)
    }

    private fun drawCopyright(canvas: Canvas, alpha: Float) {
        if (alpha <= 0f) return
        val yearWidth = copyrightPaint.measureText(copyrightYear)
        val total = yearWidth + copyrightPaint.measureText(copyrightName)
        val x = (width - total) / 2f
        val baseline = height - navBarInset - 44 * dp
        copyrightPaint.color = Color.parseColor("#6E7480")
        copyrightPaint.alpha = (255 * alpha).toInt()
        canvas.drawText(copyrightYear, x, baseline, copyrightPaint)
        copyrightPaint.color = Color.parseColor("#A3AAB4")
        copyrightPaint.alpha = (255 * alpha).toInt()
        canvas.drawText(copyrightName, x + yearWidth, baseline, copyrightPaint)
    }

    /** 帯の中心を「H」の左のすぐ外側から「N」の右のすぐ外側まで等速で動かし、約10°右に傾けて文字を塗る。 */
    private fun drawBand(canvas: Canvas, p: Float, alpha: Float) {
        bandMatrix.reset()
        bandMatrix.postRotate(10f)
        bandMatrix.postTranslate(bandStartX + (bandEndX - bandStartX) * p, titleBounds.centerY())

        // うっすらしたグロー：帯が当たっている文字をぼかして重ね、文字のまわりを淡く光らせる
        // （ぼかしが使えない端末では、太さを変えた薄い輪郭線を重ねて代用する）
        bandGlowShader?.setLocalMatrix(bandMatrix)
        bandGlowPaint.shader = bandGlowShader
        if (DrawUtils.blurSupported) {
            bandGlowPaint.alpha = (255 * alpha).toInt()
            canvas.drawPath(titlePath, bandGlowPaint)
        } else {
            bandGlowPaint.alpha = (255 * alpha * 0.3f).toInt()
            for (i in 3 downTo 1) {
                bandGlowPaint.strokeWidth = 4 * dp * i
                canvas.drawPath(titlePath, bandGlowPaint)
            }
        }

        bandShader?.setLocalMatrix(bandMatrix)
        bandPaint.shader = bandShader
        bandPaint.alpha = (255 * alpha).toInt()
        canvas.drawPath(titlePath, bandPaint)
    }

    private fun drawSparkle(canvas: Canvas, t: Float, fadeOut: Float) {
        val scale = interpolate(t, SPARKLE_SCALE).coerceAtLeast(0f)
        val alpha = interpolate(t, SPARKLE_ALPHA).coerceIn(0f, 1f) * fadeOut
        if (scale <= 0f || alpha <= 0f) return
        val a = (255 * alpha).toInt()

        val save = canvas.save()
        canvas.translate(titleBounds.right, titleBounds.top)
        canvas.rotate(interpolate(t, SPARKLE_ROTATION))
        canvas.scale(scale, scale)

        sparkleGlowPaint.alpha = a
        canvas.drawCircle(0f, 0f, 26 * dp, sparkleGlowPaint)

        // 45°方向の短い光線（不透明度70%）
        sparkleRayPaint.alpha = (a * 0.7f).toInt()
        canvas.rotate(45f)
        canvas.drawPath(sparkleSubRay, sparkleRayPaint)
        canvas.rotate(90f)
        canvas.drawPath(sparkleSubRay, sparkleRayPaint)
        canvas.rotate(-135f)

        // 上下左右の長い光線
        sparkleRayPaint.alpha = a
        canvas.drawPath(sparkleMainRay, sparkleRayPaint)
        canvas.rotate(90f)
        canvas.drawPath(sparkleMainRay, sparkleRayPaint)

        sparkleDotPaint.alpha = a
        canvas.drawCircle(0f, 0f, 2.2f * dp, sparkleDotPaint)
        canvas.restoreToCount(save)
    }

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private companion object {
        const val TOTAL_SEC = 3.0f

        // 光の帯が「H」の左から「N」の右まで走る時間帯[秒]
        const val BAND_START_SEC = 0.4f
        const val BAND_END_SEC = 1.6f

        /** 「Ver.2.0」のフェードインにかける時間[秒]。 */
        const val VERSION_FADE_SEC = 0.15f

        // キラーンのキーフレーム（時刻[秒] → 大きさ・回転[度]・不透明度）
        val SPARKLE_TIMES = floatArrayOf(1.44f, 1.67f, 1.98f, 2.43f)
        val SPARKLE_SCALE = floatArrayOf(0f, 1.15f, 0.92f, 0.15f)
        val SPARKLE_ROTATION = floatArrayOf(0f, 45f, 110f, 220f)
        val SPARKLE_ALPHA = floatArrayOf(0f, 1f, 1f, 0f)

        /** [from]〜[to] 秒の間で 0→1 に直線的に変化する値。 */
        fun progress(t: Float, from: Float, to: Float): Float = ((t - from) / (to - from)).coerceIn(0f, 1f)

        /**
         * [SPARKLE_TIMES] のキーフレームを単調な3次エルミート補間（Fritsch–Carlson）でなめらかにつなぐ。
         * キーフレームで速度が途切れず、キーフレーム間で行き過ぎ（大きさが負になる等）もしない。
         */
        fun interpolate(t: Float, values: FloatArray): Float {
            val times = SPARKLE_TIMES
            val n = times.size
            if (t <= times[0]) return values[0]
            if (t >= times[n - 1]) return values[n - 1]

            val slopes = FloatArray(n - 1) { (values[it + 1] - values[it]) / (times[it + 1] - times[it]) }
            val tangents = FloatArray(n) { i ->
                when (i) {
                    0 -> slopes[0]
                    n - 1 -> slopes[n - 2]
                    else -> if (slopes[i - 1] * slopes[i] <= 0f) 0f else (slopes[i - 1] + slopes[i]) / 2f
                }
            }
            for (i in 0 until n - 1) {
                if (slopes[i] == 0f) {
                    tangents[i] = 0f
                    tangents[i + 1] = 0f
                    continue
                }
                val a = tangents[i] / slopes[i]
                val b = tangents[i + 1] / slopes[i]
                val s = a * a + b * b
                if (s > 9f) {
                    val k = 3f / kotlin.math.sqrt(s)
                    tangents[i] = k * a * slopes[i]
                    tangents[i + 1] = k * b * slopes[i]
                }
            }

            val i = (0 until n - 1).first { t < times[it + 1] }
            val h = times[i + 1] - times[i]
            val u = (t - times[i]) / h
            val u2 = u * u
            val u3 = u2 * u
            return (2 * u3 - 3 * u2 + 1) * values[i] + (u3 - 2 * u2 + u) * h * tangents[i] +
                (-2 * u3 + 3 * u2) * values[i + 1] + (u3 - u2) * h * tangents[i + 1]
        }

        /** 中心(0,0)・上下方向に長い、細長いひし形。 */
        fun diamond(path: Path, halfLength: Float, halfWidth: Float) {
            path.reset()
            path.moveTo(0f, -halfLength)
            path.lineTo(halfWidth, 0f)
            path.lineTo(0f, halfLength)
            path.lineTo(-halfWidth, 0f)
            path.close()
        }
    }
}
