package com.example.eringchecker.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * カメラフレームのうちガイド枠（[Roi]）内の領域だけを切り出して [onRoi] に渡す。
 *
 * ImageAnalysis は OUTPUT_IMAGE_FORMAT_RGBA_8888 で構成し、
 * UseCaseGroup のビューポートにより cropRect がプレビュー表示範囲と一致している前提。
 * サンプル撮影と判定で同じ処理経路を使うため、基準画像と判定フレームの条件（枠の位置・大きさ）がそろう。
 *
 * @param mirror 前面カメラ用。プレビュー（鏡像表示）と向きを合わせるため左右反転する。
 * @param onFrame ガイド枠で切り出す前の、表示範囲・表示向きにそろえた映像を受け取る（AI 判定用）。
 */
class RoiFrameAnalyzer(
    private val mirror: Boolean,
    private val onRoi: (Bitmap) -> Unit,
    private val onFrame: ((Bitmap) -> Unit)? = null,
) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        val oriented = try {
            extractOriented(image)
        } finally {
            image.close()
        }
        // 3) ガイド枠と同じ割合で中央の正方形を切り出し（プレビューと縦横比が同じなので枠と一致する）
        val r = Roi.rect(oriented.width, oriented.height)
        onRoi(Bitmap.createBitmap(oriented, r.left, r.top, r.width(), r.height()))
        onFrame?.invoke(oriented)
    }

    private fun extractOriented(image: ImageProxy): Bitmap {
        val full = image.toBitmap()

        // 1) ビューポート（プレビュー表示範囲）で切り出し
        val crop = image.cropRect
        val left = crop.left.coerceIn(0, full.width - 1)
        val top = crop.top.coerceIn(0, full.height - 1)
        val w = crop.width().coerceIn(1, full.width - left)
        val h = crop.height().coerceIn(1, full.height - top)
        val visible = Bitmap.createBitmap(full, left, top, w, h)

        // 2) 表示向きに回転（前面カメラはプレビューに合わせて左右反転）
        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0 && !mirror) return visible
        val m = Matrix().apply {
            postRotate(rotation.toFloat())
            if (mirror) postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(visible, 0, 0, visible.width, visible.height, m, true)
    }
}
