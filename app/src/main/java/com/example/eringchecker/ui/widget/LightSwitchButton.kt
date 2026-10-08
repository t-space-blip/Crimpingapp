package com.example.eringchecker.ui.widget

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.widget.Button
import androidx.core.content.res.ResourcesCompat
import com.example.eringchecker.R

/**
 * 受け皿付きのスイッチ風ボタン。押せない時は消灯（くすんだ色）、押せる時は中から光る。
 * 有効/無効は [View.isEnabled]、タップ中の沈み込みは [View.isPressed] に連動する。
 * 外側の発光は親の clipChildren=false を前提にView境界の外へはみ出して描く。
 */
class LightSwitchButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 点灯時の色。 */
    enum class Variant { WHITE, SKY }

    /** 消灯／点灯それぞれの配色。 */
    private class Palette(
        val offTop: Int, val offBottom: Int, val offText: Int, val offThickness: Int,
        val onColors: IntArray, val onStops: FloatArray, val onText: Int, val onThickness: Int,
        val glow: Int,
    )

    private val dp = resources.displayMetrics.density

    var text: CharSequence = ""
        set(value) {
            field = value
            contentDescription = value
            invalidate()
        }

    var icon: Drawable? = null
        set(value) {
            field = value?.mutate()
            invalidate()
        }

    private var palette = palette(Variant.WHITE)

    private val frameRadius = 22 * dp
    private val padSide = 5 * dp
    private val padBottom = 8 * dp
    private val bodyHeight = 56 * dp
    private val bodyRadius = 17 * dp
    private val thicknessUp = 4 * dp
    private val thicknessDown = 1 * dp
    private val sinkDepth = 3 * dp
    private val iconSize = (20 * dp).toInt()
    private val iconGap = 8 * dp

    /** 0 = 消灯, 1 = 点灯。 */
    private var lit = 0f
    /** 0 = 通常, 1 = タップ中。 */
    private var press = 0f
    private var litAnimator: ValueAnimator? = null
    private var pressAnimator: ValueAnimator? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 17f, resources.displayMetrics)
        typeface = ResourcesCompat.getFont(context, R.font.mplus1p_extrabold)
    }
    private val argb = ArgbEvaluator()

    private val frame = RectF()
    private val body = RectF()
    private val tmp = RectF()
    private val framePath = Path()

    init {
        isClickable = true
        isFocusable = true
        attrs?.let {
            val a = context.obtainStyledAttributes(it, R.styleable.LightSwitchButton)
            text = a.getText(R.styleable.LightSwitchButton_android_text) ?: ""
            icon = a.getDrawable(R.styleable.LightSwitchButton_switchIcon)
            palette = palette(Variant.entries[a.getInt(R.styleable.LightSwitchButton_switchVariant, 0)])
            a.recycle()
        }
        lit = if (isEnabled) 1f else 0f
    }

    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (padSide + bodyHeight + padBottom).toInt()
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(h, heightMeasureSpec),
        )
    }

    override fun setEnabled(enabled: Boolean) {
        val changed = enabled != isEnabled
        super.setEnabled(enabled)
        if (changed) litAnimator = animateTo(litAnimator, lit, if (enabled) 1f else 0f, 200) { lit = it }
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        val target = if (isPressed && isEnabled) 1f else 0f
        if (pressAnimator?.isRunning != true && press == target) return
        pressAnimator = animateTo(pressAnimator, press, target, 120) { press = it }
    }

    private fun animateTo(
        current: ValueAnimator?, from: Float, to: Float, ms: Long, apply: (Float) -> Unit,
    ): ValueAnimator? {
        current?.cancel()
        if (!isAttachedToWindow) {
            apply(to)
            invalidate()
            return null
        }
        return ValueAnimator.ofFloat(from, to).apply {
            duration = ms
            addUpdateListener {
                apply(it.animatedValue as Float)
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        frame.set(0f, 0f, w.toFloat(), h.toFloat())
        framePath.reset()
        framePath.addRoundRect(frame, frameRadius, frameRadius, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        val pal = palette
        val sink = sinkDepth * press
        val thickness = DrawUtils.lerp(thicknessUp, thicknessDown, press)
        body.set(frame.left + padSide, padSide + sink, frame.right - padSide, padSide + sink + bodyHeight)

        // 外側の発光（点灯時のみ）
        DrawUtils.drawGlow(canvas, frame, frameRadius, pal.glow, 18 * dp, lit)

        // 受け皿：濃いグレーの縦グラデーション＋内側上部の影
        paint.color = Color.BLACK // 前回の描画で変えたアルファを戻す
        paint.shader = DrawUtils.verticalGradient(
            frame.top, frame.bottom, intArrayOf(Color.parseColor("#4A5363"), Color.parseColor("#2B313C")),
        )
        canvas.drawPath(framePath, paint)
        canvas.save()
        canvas.clipPath(framePath)
        DrawUtils.drawInnerShadow(canvas, frame, 7 * dp, DrawUtils.rgba(0, 0, 0, 0.35f), fromTop = true)
        canvas.restore()

        // 厚み
        paint.shader = null
        paint.color = argb.evaluate(lit, pal.offThickness, pal.onThickness) as Int
        tmp.set(body)
        tmp.offset(0f, thickness)
        canvas.drawRoundRect(tmp, bodyRadius, bodyRadius, paint)

        // ボタン本体：消灯（縦グラデーション）と点灯（放射グラデーション）をクロスフェード
        if (lit < 1f) {
            paint.shader = DrawUtils.verticalGradient(body.top, body.bottom, intArrayOf(pal.offTop, pal.offBottom))
            paint.alpha = (255 * (1f - lit)).toInt()
            canvas.drawRoundRect(body, bodyRadius, bodyRadius, paint)
        }
        if (lit > 0f) {
            paint.shader = DrawUtils.ellipseGradient(body, pal.onColors, pal.onStops)
            paint.alpha = (255 * lit).toInt()
            canvas.drawRoundRect(body, bodyRadius, bodyRadius, paint)
        }
        paint.shader = null
        paint.alpha = 255

        // タップ中は少し明るく
        if (press > 0f) {
            paint.color = DrawUtils.rgba(255, 255, 255, 0.18f * press)
            canvas.drawRoundRect(body, bodyRadius, bodyRadius, paint)
        }

        // 上辺のハイライト
        canvas.save()
        canvas.clipRect(body.left, body.top, body.right, body.top + 5 * dp)
        paint.color = Color.BLACK
        paint.shader = DrawUtils.verticalGradient(
            body.top, body.top + 5 * dp,
            intArrayOf(DrawUtils.rgba(255, 255, 255, 0.7f), DrawUtils.rgba(255, 255, 255, 0f)),
        )
        canvas.drawRoundRect(body, bodyRadius, bodyRadius, paint)
        paint.shader = null
        canvas.restore()

        // アイコン＋文字を中央に横並び
        val color = argb.evaluate(lit, pal.offText, pal.onText) as Int
        textPaint.color = color
        val textWidth = textPaint.measureText(text, 0, text.length)
        val ic = icon
        val contentWidth = textWidth + if (ic != null) iconSize + iconGap else 0f
        var x = body.centerX() - contentWidth / 2f
        if (ic != null) {
            val top = (body.centerY() - iconSize / 2f).toInt()
            ic.setBounds(x.toInt(), top, x.toInt() + iconSize, top + iconSize)
            ic.setTint(color)
            ic.draw(canvas)
            x += iconSize + iconGap
        }
        val baseline = body.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(text, 0, text.length, x, baseline, textPaint)
    }

    override fun onDetachedFromWindow() {
        litAnimator?.cancel()
        pressAnimator?.cancel()
        lit = if (isEnabled) 1f else 0f
        press = 0f
        super.onDetachedFromWindow()
    }

    private companion object {
        fun palette(variant: Variant): Palette = when (variant) {
            Variant.WHITE -> Palette(
                offTop = DrawUtils.rgba(236, 240, 246, 0.95f),
                offBottom = DrawUtils.rgba(184, 193, 206, 0.92f),
                offText = Color.parseColor("#5B6678"),
                offThickness = Color.parseColor("#8E98A8"),
                onColors = intArrayOf(
                    Color.parseColor("#FFFFFF"), Color.parseColor("#FBFBF9"),
                    Color.parseColor("#ECEEF1"), Color.parseColor("#DCDFE4"),
                ),
                onStops = floatArrayOf(0f, 0.45f, 0.85f, 1f),
                onText = Color.parseColor("#262B35"),
                onThickness = Color.parseColor("#A3A9B3"),
                glow = DrawUtils.rgba(255, 255, 255, 1f),
            )
            Variant.SKY -> Palette(
                offTop = DrawUtils.rgba(198, 210, 228, 0.95f),
                offBottom = DrawUtils.rgba(140, 160, 190, 0.92f),
                offText = Color.parseColor("#2C3B55"),
                offThickness = Color.parseColor("#6C7E9C"),
                onColors = intArrayOf(
                    Color.parseColor("#EAF7FF"), Color.parseColor("#A9DEFF"),
                    Color.parseColor("#55B8F6"), Color.parseColor("#2696E6"),
                ),
                onStops = floatArrayOf(0f, 0.35f, 0.75f, 1f),
                onText = Color.parseColor("#053355"),
                onThickness = Color.parseColor("#1E78BE"),
                glow = DrawUtils.rgba(95, 185, 255, 0.85f),
            )
        }
    }
}
