package com.example.eringchecker.ui.widget

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import kotlin.math.sqrt

/** ホーム画面のカスタムView群で共通に使う描画ヘルパー。 */
internal object DrawUtils {

    fun rgba(r: Int, g: Int, b: Int, a: Float): Int = Color.argb((a * 255).toInt(), r, g, b)

    /** 上→下の縦グラデーション。[stops] が null の場合は等間隔。 */
    fun verticalGradient(top: Float, bottom: Float, colors: IntArray, stops: FloatArray? = null): Shader =
        LinearGradient(0f, top, 0f, bottom, colors, stops, Shader.TileMode.CLAMP)

    /**
     * CSS の `radial-gradient(ellipse farthest-corner at center)` 相当。
     * 円形グラデーションを横方向に引き伸ばして矩形に合わせる。
     */
    fun ellipseGradient(rect: RectF, colors: IntArray, stops: FloatArray): Shader {
        val ry = rect.height() / 2f * sqrt(2f)
        val shader = RadialGradient(0f, 0f, ry, colors, stops, Shader.TileMode.CLAMP)
        shader.setLocalMatrix(Matrix().apply {
            setScale(rect.width() / rect.height(), 1f)
            postTranslate(rect.centerX(), rect.centerY())
        })
        return shader
    }

    /** BlurMaskFilter がハードウェア描画で使えるのは API 28 以降。 */
    val blurSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    /**
     * 角丸矩形の外側に発光（グロー）を描く。API 28 未満では一回り大きい半透明の角丸を重ねて代用する。
     * [strength] 0..1 で全体の濃さを調整する（アニメーション用）。
     */
    fun drawGlow(canvas: Canvas, rect: RectF, radius: Float, color: Int, blur: Float, strength: Float) {
        if (strength <= 0.01f) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val baseAlpha = Color.alpha(color) / 255f
        if (blurSupported) {
            paint.color = color
            paint.alpha = (255 * baseAlpha * strength).toInt()
            paint.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
            canvas.drawRoundRect(rect, radius, radius, paint)
        } else {
            val steps = 4
            val r = RectF()
            for (i in steps downTo 1) {
                val grow = blur * i / steps
                r.set(rect.left - grow, rect.top - grow, rect.right + grow, rect.bottom + grow)
                paint.color = color
                paint.alpha = (255 * baseAlpha * strength * 0.22f).toInt()
                canvas.drawRoundRect(r, radius + grow, radius + grow, paint)
            }
        }
    }

    /** 角丸矩形の内側上部（または下部）に落ちる影。呼び出し側で角丸にクリップしておくこと。 */
    fun drawInnerShadow(canvas: Canvas, rect: RectF, depth: Float, color: Int, fromTop: Boolean) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val transparent = color and 0x00FFFFFF
        if (fromTop) {
            paint.shader = verticalGradient(rect.top, rect.top + depth, intArrayOf(color, transparent))
            canvas.drawRect(rect.left, rect.top, rect.right, rect.top + depth, paint)
        } else {
            paint.shader = verticalGradient(rect.bottom - depth, rect.bottom, intArrayOf(transparent, color))
            canvas.drawRect(rect.left, rect.bottom - depth, rect.right, rect.bottom, paint)
        }
    }

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
