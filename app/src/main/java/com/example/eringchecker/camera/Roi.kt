package com.example.eringchecker.camera

import android.graphics.Rect

/**
 * 判定対象領域（ROI）＝ガイド枠。サンプル撮影・判定の両方でこの枠内だけを画像処理に使う。
 * プレビュー中央の正方形で、短辺に対する割合 [FRACTION] で大きさを決める。
 * 解析画像はビューポートでプレビューと同じ範囲に切り出されるため、
 * 同じ割合を使えばガイド枠と解析領域が一致する。
 */
object Roi {

    const val FRACTION = 0.75f

    fun rect(width: Int, height: Int): Rect {
        val side = (minOf(width, height) * FRACTION).toInt()
        val left = (width - side) / 2
        val top = (height - side) / 2
        return Rect(left, top, left + side, top + side)
    }
}
