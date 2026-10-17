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
 * @param onFrame プレビュー表示範囲で切り出す前の、カメラの元の映像全体（表示向きにそろえたもの）を受け取る（AI 判定用）。
 *   学習写真（カメラアプリで横向き全画面で撮影）と同じ見え方にするため、ビューポートでは切り出さない。
 */
class RoiFrameAnalyzer(
    private val mirror: Boolean,
    private val onRoi: (Bitmap) -> Unit,
    private val onFrame: ((Bitmap) -> Unit)? = null,
) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        val (full, visible, rotation) = try {
            val full = image.toBitmap()
            Triple(full, cropToViewport(full, image), image.imageInfo.rotationDegrees)
        } finally {
            image.close()
        }
        // 3) ガイド枠と同じ割合で中央の正方形を切り出し（プレビューと縦横比が同じなので枠と一致する）
        val oriented = orient(visible, rotation)
        val r = Roi.rect(oriented.width, oriented.height)
        onRoi(Bitmap.createBitmap(oriented, r.left, r.top, r.width(), r.height()))
        onFrame?.invoke(orient(full, rotation))
    }

    /** 1) ビューポート（プレビュー表示範囲）で切り出し */
    private fun cropToViewport(full: Bitmap, image: ImageProxy): Bitmap {
        val crop = image.cropRect
        val left = crop.left.coerceIn(0, full.width - 1)
        val top = crop.top.coerceIn(0, full.height - 1)
        val w = crop.width().coerceIn(1, full.width - left)
        val h = crop.height().coerceIn(1, full.height - top)
        return Bitmap.createBitmap(full, left, top, w, h)
    }

    /** 2) 表示向きに回転（前面カメラはプレビューに合わせて左右反転） */
    private fun orient(bitmap: Bitmap, rotation: Int): Bitmap {
        if (rotation == 0 && !mirror) return bitmap
        val m = Matrix().apply {
            postRotate(rotation.toFloat())
            if (mirror) postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }
}
