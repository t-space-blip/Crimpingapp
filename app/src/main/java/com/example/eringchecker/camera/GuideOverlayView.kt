package com.example.eringchecker.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.example.eringchecker.R

/** プレビュー上に ROI のガイド枠を描画し、枠外を暗くする。 */
class GuideOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val dimPaint = Paint().apply { color = Color.argb(110, 0, 0, 0) }

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(14f)
        textAlign = Paint.Align.CENTER
    }

    private val label = context.getString(R.string.guide_label)
    private val dimPath = Path()
    private val frame = RectF()

    /** 判定結果に応じて枠の色を変える。 */
    var frameColor: Int = Color.WHITE
        set(value) {
            field = value
            framePaint.color = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        frame.set(Roi.rect(width, height))

        dimPath.reset()
        dimPath.fillType = Path.FillType.EVEN_ODD
        dimPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        dimPath.addRect(frame, Path.Direction.CW)
        canvas.drawPath(dimPath, dimPaint)

        canvas.drawRect(frame, framePaint)
        canvas.drawText(label, frame.centerX(), frame.bottom + dp(22f), textPaint)
    }

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}
