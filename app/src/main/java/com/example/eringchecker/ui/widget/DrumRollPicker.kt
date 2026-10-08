package com.example.eringchecker.ui.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import androidx.core.content.res.ResourcesCompat
import com.example.eringchecker.R
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 筒状に回るドラムロール（ホイールピッカー）。
 * スワイプで回し、指を離すと最寄りの行にスナップする。行のタップでその行まで回る。
 * 先頭行はプレースホルダ（未選択扱い）として灰色で描く。
 * 無効（[isEnabled] = false）の間は回せず、筒を暗くして [disabledText] を中央に表示する。
 */
class DrumRollPicker @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val dp = resources.displayMetrics.density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    /** 選択位置が止まったときに呼ばれる（ユーザー操作によるもののみ）。 */
    var onSelectedChange: ((index: Int) -> Unit)? = null

    /** 無効時に中央へ表示する文言。 */
    var disabledText: CharSequence = ""
        set(value) {
            field = value
            invalidate()
        }

    private var items: List<String> = emptyList()

    /** 現在選択されている行（スクロールが止まった位置）。 */
    var selectedIndex: Int = 0
        private set

    /** 中央に来ている行の位置（行単位の連続値）。 */
    private var position = 0f

    private val rowHeight = 34 * dp
    private val visibleRows = 5
    private val drumHeight = 170 * dp
    private val padTop = 5 * dp
    private val padSide = 5 * dp
    private val padBottom = 7 * dp
    private val frameRadius = 18 * dp
    private val drumRadius = 12 * dp
    private val bandInset = 6 * dp
    private val bandRadius = 8 * dp

    /** 行を倒す角度（1行あたり）と遠近のカメラ距離。距離を短めにして遠近感をやや強くする。 */
    private val degreesPerRow = 24f
    private val cameraDistance = 170 * dp

    private val frame = RectF()
    private val drum = RectF()
    private val band = RectF()
    private val framePath = Path()
    private val drumPath = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val fontMedium: Typeface? = ResourcesCompat.getFont(context, R.font.mplus1p_medium)
    private val fontExtraBold: Typeface? = ResourcesCompat.getFont(context, R.font.mplus1p_extrabold)
    private val fontRegular: Typeface? = ResourcesCompat.getFont(context, R.font.mplus1p_regular)

    private val rowMatrix = Matrix()
    private val src = FloatArray(8)
    private val dst = FloatArray(8)

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var velocityTracker: VelocityTracker? = null
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false
    private var animator: ValueAnimator? = null

    init {
        isClickable = true
        isFocusable = true
    }

    /** 表示する行を差し替え、先頭行（未選択）に戻す。リスナーは呼ばない。 */
    fun setItems(newItems: List<String>) {
        animator?.cancel()
        items = newItems
        position = 0f
        selectedIndex = 0
        updateDescription()
        invalidate()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (!enabled) {
            animator?.cancel()
            dragging = false
        }
        updateDescription()
        invalidate()
    }

    private val maxIndex get() = max(0, items.size - 1)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (padTop + drumHeight + padBottom).toInt()
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(h, heightMeasureSpec),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        frame.set(0f, 0f, w.toFloat(), h.toFloat())
        drum.set(padSide, padTop, w - padSide, padTop + drumHeight)
        band.set(drum.left + bandInset, drum.centerY() - rowHeight / 2f, drum.right - bandInset, drum.centerY() + rowHeight / 2f)
        framePath.reset()
        framePath.addRoundRect(frame, frameRadius, frameRadius, Path.Direction.CW)
        drumPath.reset()
        drumPath.addRoundRect(drum, drumRadius, drumRadius, Path.Direction.CW)
    }

    // ---- 描画 ----

    override fun onDraw(canvas: Canvas) {
        val enabled = isEnabled

        // 濃いグレーの枠
        paint.color = Color.BLACK // 前回の描画で変えたアルファを戻す
        paint.shader = DrawUtils.verticalGradient(
            frame.top, frame.bottom, intArrayOf(Color.parseColor("#4A5363"), Color.parseColor("#2B313C")),
        )
        canvas.drawPath(framePath, paint)

        canvas.save()
        canvas.clipPath(drumPath)

        // 筒の背景（中央が明るい金属調）
        val drumColors = if (enabled) {
            intArrayOf(0xFFAEB6C2.toInt(), 0xFFE6EAF0.toInt(), 0xFFFFFFFF.toInt(), 0xFFE6EAF0.toInt(), 0xFFAEB6C2.toInt())
        } else {
            intArrayOf(0xFFA3AAB5.toInt(), 0xFFD5DAE1.toInt(), 0xFFE9ECF0.toInt(), 0xFFD5DAE1.toInt(), 0xFFA3AAB5.toInt())
        }
        paint.shader = DrawUtils.verticalGradient(drum.top, drum.bottom, drumColors, floatArrayOf(0f, 0.22f, 0.5f, 0.78f, 1f))
        canvas.drawRect(drum, paint)
        paint.shader = null

        if (enabled) {
            drawBand(canvas)
            drawRows(canvas)
        } else {
            textPaint.typeface = fontRegular
            textPaint.textSize = sp(16f)
            textPaint.color = Color.parseColor("#6B7586")
            textPaint.alpha = 255
            val baseline = drum.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(disabledText, 0, disabledText.length, drum.centerX(), baseline, textPaint)
        }

        // 上下端の内側の影（筒の丸み）
        DrawUtils.drawInnerShadow(canvas, drum, 16 * dp, DrawUtils.rgba(0x2B, 0x31, 0x3C, 0.32f), fromTop = true)
        DrawUtils.drawInnerShadow(canvas, drum, 16 * dp, DrawUtils.rgba(0x2B, 0x31, 0x3C, 0.32f), fromTop = false)
        canvas.restore()
    }

    /** 中央の選択位置の帯。 */
    private fun drawBand(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = DrawUtils.rgba(255, 196, 80, 0.16f)
        canvas.drawRoundRect(band, bandRadius, bandRadius, paint)
        paint.color = DrawUtils.rgba(150, 85, 0, 0.35f)
        val line = max(1f, dp)
        canvas.drawRect(band.left + bandRadius / 2, band.top, band.right - bandRadius / 2, band.top + line, paint)
        canvas.drawRect(band.left + bandRadius / 2, band.bottom - line, band.right - bandRadius / 2, band.bottom, paint)
    }

    private fun drawRows(canvas: Canvas) {
        if (items.isEmpty()) return
        val layer = canvas.saveLayer(drum, null)
        val halfW = drum.width() / 2f
        val halfH = rowHeight / 2f
        val reach = visibleRows / 2 + 1
        val first = max(0, position.toInt() - reach)
        val last = min(items.size - 1, position.toInt() + reach + 1)
        for (i in first..last) {
            val d = i - position
            val ad = abs(d)
            if (ad > reach) continue

            // 中央からの距離 d に応じて倒す・縮める・薄くする
            val theta = Math.toRadians((d * degreesPerRow).toDouble().coerceIn(-89.0, 89.0))
            val scale = max(0.7f, 1f - ad * 0.07f)
            val alpha = max(0.12f, 1f - ad * 0.28f)
            setRowMatrix(halfW, halfH, theta, scale, drum.centerX(), drum.centerY() + d * rowHeight)

            val t = min(ad, 1f)
            textPaint.typeface = if (ad < 0.5f) fontExtraBold else fontMedium
            textPaint.textSize = DrawUtils.lerp(sp(19f), sp(17f), t)
            textPaint.color = if (i == 0) {
                Color.parseColor("#6B7586")
            } else {
                blend(Color.parseColor("#18202B"), Color.parseColor("#3A4556"), t)
            }
            textPaint.alpha = (255 * alpha).toInt()

            canvas.save()
            canvas.concat(rowMatrix)
            val baseline = -(textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(items[i], 0f, baseline, textPaint)
            canvas.restore()
        }
        // 上下端を透明にフェードアウト
        maskPaint.shader = DrawUtils.verticalGradient(
            drum.top, drum.bottom,
            intArrayOf(Color.TRANSPARENT, Color.BLACK, Color.BLACK, Color.TRANSPARENT),
            floatArrayOf(0f, 0.24f, 0.76f, 1f),
        )
        canvas.drawRect(drum, maskPaint)
        canvas.restoreToCount(layer)
    }

    /**
     * 行の中心を原点とした座標系を、X軸まわりに [theta] 回転させて透視投影し、
     * [scale] 倍して (cx, cy) に置く行列を作る。
     * theta < 0（中央より上の行）は上端が奥へ、theta > 0（下の行）は下端が奥へ倒れる。
     */
    private fun setRowMatrix(halfW: Float, halfH: Float, theta: Double, scale: Float, cx: Float, cy: Float) {
        val c = cos(theta).toFloat()
        val s = sin(theta).toFloat()
        var k = 0
        for ((x, y) in arrayOf(-halfW to -halfH, halfW to -halfH, halfW to halfH, -halfW to halfH)) {
            val z = y * s // 正 = 奥
            val p = cameraDistance / (cameraDistance + z)
            src[k] = x
            src[k + 1] = y
            dst[k] = cx + x * p * scale
            dst[k + 1] = cy + y * c * p * scale
            k += 2
        }
        rowMatrix.setPolyToPoly(src, 0, dst, 0, 4)
    }

    private fun blend(from: Int, to: Int, t: Float): Int = Color.rgb(
        DrawUtils.lerp(Color.red(from).toFloat(), Color.red(to).toFloat(), t).roundToInt(),
        DrawUtils.lerp(Color.green(from).toFloat(), Color.green(to).toFloat(), t).roundToInt(),
        DrawUtils.lerp(Color.blue(from).toFloat(), Color.blue(to).toFloat(), t).roundToInt(),
    )

    // ---- タッチ操作 ----

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || items.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.cancel()
                parent?.requestDisallowInterceptTouchEvent(true)
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                downY = event.y
                lastY = event.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                if (!dragging && abs(event.y - downY) > touchSlop) {
                    dragging = true
                    lastY = event.y
                }
                if (dragging) {
                    var delta = -(event.y - lastY) / rowHeight
                    // 端を越えた分は重くする
                    if ((position < 0f && delta < 0f) || (position > maxIndex && delta > 0f)) delta /= 3f
                    position = (position + delta).coerceIn(-0.6f, maxIndex + 0.6f)
                    lastY = event.y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                velocityTracker?.addMovement(event)
                if (dragging) {
                    val tracker = velocityTracker
                    tracker?.computeCurrentVelocity(1000)
                    val vy = tracker?.yVelocity ?: 0f
                    // 慣性で進む量を見込んでから最寄りの行にスナップ
                    val projected = position - vy * 0.18f / rowHeight
                    animateTo(projected.roundToInt().coerceIn(0, maxIndex))
                } else {
                    val d = (event.y - drum.centerY()) / rowHeight
                    animateTo((position + d).roundToInt().coerceIn(0, maxIndex))
                    performClick()
                }
                endTouch()
            }
            MotionEvent.ACTION_CANCEL -> {
                animateTo(position.roundToInt().coerceIn(0, maxIndex))
                endTouch()
            }
        }
        return true
    }

    private fun endTouch() {
        dragging = false
        velocityTracker?.recycle()
        velocityTracker = null
    }

    override fun performClick(): Boolean = super.performClick()

    /** [target] 行まで回して止め、止まった位置を選択として通知する。 */
    private fun animateTo(target: Int) {
        animator?.cancel()
        val distance = abs(target - position)
        if (distance < 0.001f) {
            settle(target)
            return
        }
        animator = ValueAnimator.ofFloat(position, target.toFloat()).apply {
            duration = (180 + distance * 90).toLong().coerceAtMost(650)
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                position = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var canceled = false
                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!canceled) settle(target)
                }
            })
            start()
        }
    }

    private fun settle(index: Int) {
        position = index.toFloat()
        invalidate()
        if (index != selectedIndex) {
            selectedIndex = index
            updateDescription()
            onSelectedChange?.invoke(index)
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        position = selectedIndex.toFloat()
        endTouch()
        super.onDetachedFromWindow()
    }

    // ---- アクセシビリティ ----

    private fun updateDescription() {
        contentDescription = if (isEnabled) items.getOrNull(selectedIndex) else disabledText
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isScrollable = isEnabled && items.size > 1
        if (isEnabled && selectedIndex < maxIndex) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        }
        if (isEnabled && selectedIndex > 0) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (isEnabled) {
            when (action) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> if (selectedIndex < maxIndex) {
                    animateTo(selectedIndex + 1)
                    return true
                }
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> if (selectedIndex > 0) {
                    animateTo(selectedIndex - 1)
                    return true
                }
            }
        }
        return super.performAccessibilityAction(action, arguments)
    }
}
