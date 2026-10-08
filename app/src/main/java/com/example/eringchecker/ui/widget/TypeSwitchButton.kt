package com.example.eringchecker.ui.widget

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.widget.Button
import androidx.core.content.res.ResourcesCompat
import com.example.eringchecker.R

/**
 * Type選択用の「半透明の黄色いプラスチックスイッチ」。
 * 選択状態は [View.isSelected] で表し、切り替え時は沈み込みと発光をアニメーションする。
 * ボタン外側の発光は親の clipChildren=false を前提にView境界の外へはみ出して描く。
 */
class TypeSwitchButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val dp = resources.displayMetrics.density

    var text: CharSequence = ""
        set(value) {
            field = value
            contentDescription = value
            invalidate()
        }

    /** 0 = 未選択（浮いている）, 1 = 選択中（押し込まれて発光）。 */
    private var progress = 0f
    private var animator: ValueAnimator? = null

    private val frameStroke = 4 * dp
    private val frameRadius = 18 * dp
    private val bodyHeight = 52 * dp
    private val bodyRadius = 14 * dp
    private val thicknessUp = 4 * dp
    private val thicknessDown = 1 * dp
    private val sinkDepth = 3 * dp

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15f, resources.displayMetrics)
        typeface = ResourcesCompat.getFont(context, R.font.mplus1p_extrabold)
    }
    private val argb = ArgbEvaluator()

    private val outer = RectF()
    private val inner = RectF()
    private val body = RectF()
    private val tmp = RectF()
    private val framePath = Path()
    private val innerPath = Path()

    init {
        isClickable = true
        isFocusable = true
        attrs?.let {
            val a = context.obtainStyledAttributes(it, intArrayOf(android.R.attr.text))
            text = a.getText(0) ?: ""
            a.recycle()
        }
    }

    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 枠4dp + ボタン52dp + 厚み4dp + 枠4dp = 64dp
        val h = (frameStroke * 2 + bodyHeight + thicknessUp).toInt()
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(h, heightMeasureSpec),
        )
    }

    override fun setSelected(selected: Boolean) {
        val changed = selected != isSelected
        super.setSelected(selected)
        if (!changed) return
        animator?.cancel()
        if (!isAttachedToWindow) {
            progress = if (selected) 1f else 0f
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(progress, if (selected) 1f else 0f).apply {
            duration = 160
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        outer.set(0f, 0f, w.toFloat(), h.toFloat())
        inner.set(outer)
        inner.inset(frameStroke, frameStroke)
        innerPath.reset()
        innerPath.addRoundRect(inner, bodyRadius, bodyRadius, Path.Direction.CW)
        framePath.reset()
        framePath.fillType = Path.FillType.EVEN_ODD
        framePath.addRoundRect(outer, frameRadius, frameRadius, Path.Direction.CW)
        framePath.addRoundRect(inner, bodyRadius, bodyRadius, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        val p = progress
        val sink = sinkDepth * p
        val thickness = DrawUtils.lerp(thicknessUp, thicknessDown, p)
        body.set(inner.left, inner.top + sink, inner.right, inner.top + sink + bodyHeight)

        // 外側の黄色い発光（選択中のみ）
        DrawUtils.drawGlow(canvas, outer, frameRadius, Color.rgb(255, 248, 40), 18 * dp, p * 0.9f)

        // 枠の内側：塗りつぶさず白18%のみ（背景が透ける）＋上部の内側の影
        canvas.save()
        canvas.clipPath(innerPath)
        paint.shader = null
        paint.color = DrawUtils.rgba(255, 255, 255, 0.18f)
        canvas.drawRect(inner, paint)
        DrawUtils.drawInnerShadow(canvas, inner, 7 * dp, DrawUtils.rgba(0, 0, 0, 0.28f), fromTop = true)

        // 厚み（ボタン下の影）
        paint.color = DrawUtils.rgba(185, 178, 0, 0.55f)
        tmp.set(body)
        tmp.offset(0f, thickness)
        canvas.drawRoundRect(tmp, bodyRadius, bodyRadius, paint)

        // ボタン本体：未選択（縦グラデーション）と選択中（中心から光る放射グラデーション）をクロスフェード
        if (p < 1f) {
            paint.shader = DrawUtils.verticalGradient(
                body.top, body.bottom,
                intArrayOf(
                    DrawUtils.rgba(255, 254, 170, 0.54f),
                    DrawUtils.rgba(255, 247, 50, 0.42f),
                    DrawUtils.rgba(250, 236, 0, 0.48f),
                ),
                floatArrayOf(0f, 0.55f, 1f),
            )
            paint.alpha = (255 * (1f - p)).toInt()
            canvas.drawRoundRect(body, bodyRadius, bodyRadius, paint)
        }
        if (p > 0f) {
            paint.shader = DrawUtils.ellipseGradient(
                body,
                intArrayOf(
                    DrawUtils.rgba(255, 255, 235, 0.98f),
                    DrawUtils.rgba(255, 253, 120, 0.92f),
                    DrawUtils.rgba(255, 247, 30, 0.76f),
                    DrawUtils.rgba(240, 230, 0, 0.66f),
                ),
                floatArrayOf(0f, 0.4f, 0.75f, 1f),
            )
            paint.alpha = (255 * p).toInt()
            canvas.drawRoundRect(body, bodyRadius, bodyRadius, paint)
        }
        paint.alpha = 255

        // 上辺の白いハイライト
        canvas.save()
        tmp.set(body)
        canvas.clipRect(tmp.left, tmp.top, tmp.right, tmp.top + 5 * dp)
        paint.color = Color.BLACK
        paint.shader = DrawUtils.verticalGradient(
            body.top, body.top + 5 * dp,
            intArrayOf(DrawUtils.rgba(255, 255, 255, 0.85f), DrawUtils.rgba(255, 255, 255, 0f)),
        )
        canvas.drawRoundRect(body, bodyRadius, bodyRadius, paint)
        canvas.restore()
        paint.shader = null
        canvas.restore()

        // 濃いグレーの縁（上辺はやや暗く、下辺はやや明るく）
        paint.color = Color.BLACK
        paint.shader = DrawUtils.verticalGradient(
            outer.top, outer.bottom,
            intArrayOf(
                Color.parseColor("#252A33"), Color.parseColor("#2F3541"),
                Color.parseColor("#2F3541"), Color.parseColor("#3A4250"),
            ),
            floatArrayOf(0f, 0.12f, 0.88f, 1f),
        )
        canvas.drawPath(framePath, paint)
        paint.shader = null

        // 文字（選択中はうっすら白く発光）
        textPaint.color = argb.evaluate(p, Color.parseColor("#474200"), Color.parseColor("#333000")) as Int
        if (p > 0f) {
            textPaint.setShadowLayer(6 * dp, 0f, 0f, DrawUtils.rgba(255, 255, 255, 0.9f * p))
        } else {
            textPaint.clearShadowLayer()
        }
        val baseline = body.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(text, 0, text.length, body.centerX(), baseline, textPaint)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        progress = if (isSelected) 1f else 0f
        super.onDetachedFromWindow()
    }
}
