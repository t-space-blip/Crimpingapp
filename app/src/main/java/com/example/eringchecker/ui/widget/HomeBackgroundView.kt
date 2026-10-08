package com.example.eringchecker.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.sqrt

/**
 * ホーム画面の背景。ベース色の上に左右半面の色を重ね、左に「〇」・右に「×」を薄く描く。
 * 記号の縦位置は [symbolCenterY]（このView座標系）で指定する。
 */
class HomeBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val dp = resources.displayMetrics.density

    /** 〇×の中心のY座標。NaN の間は画面中央に描く。 */
    var symbolCenterY: Float = Float.NaN
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val fillPaint = Paint()

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 28 * dp
        color = Color.parseColor("#3F8A3E")
    }

    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 28 * dp
        strokeCap = Paint.Cap.SQUARE
        color = Color.parseColor("#8A1A2A")
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val half = w / 2f

        canvas.drawColor(Color.parseColor("#F6F8FB"))
        fillPaint.color = DrawUtils.rgba(0xA8, 0xDC, 0x9C, 0.22f)
        canvas.drawRect(0f, 0f, half, h, fillPaint)
        fillPaint.color = DrawUtils.rgba(0xF2, 0xA6, 0xAD, 0.22f)
        canvas.drawRect(half, 0f, w, h, fillPaint)

        val cy = if (symbolCenterY.isNaN()) h / 2f else symbolCenterY

        // 記号全体を不透明度14%で合成する（×の交点が二重に濃くならないようレイヤーでまとめる）
        val layer = canvas.saveLayerAlpha(0f, 0f, w, h, (255 * 0.14f).toInt())

        // 〇：半径58dp・線幅28dp
        canvas.drawCircle(half / 2f, cy, 58 * dp, ringPaint)

        // ×：長さ約150dpの対角線2本。四角い線端の分だけ線分を短くして全長を150dpに収める
        val cx = half + half / 2f
        val armHalf = (150 * dp - crossPaint.strokeWidth) / 2f / sqrt(2f)
        canvas.drawLine(cx - armHalf, cy - armHalf, cx + armHalf, cy + armHalf, crossPaint)
        canvas.drawLine(cx - armHalf, cy + armHalf, cx + armHalf, cy - armHalf, crossPaint)
        canvas.restoreToCount(layer)
    }
}
