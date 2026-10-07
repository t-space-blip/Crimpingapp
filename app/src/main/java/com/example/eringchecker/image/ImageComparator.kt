package com.example.eringchecker.image

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.sqrt

/** 比較用に正規化した画像特徴（グレースケール輝度とエッジ強度）。 */
class ImageFeature(val gray: FloatArray, val edge: FloatArray)

/**
 * OpenCV を使わないビットマップ直接処理によるシンプルな画像比較。
 *
 * 1. ROI 画像を [SIZE]x[SIZE] に縮小しグレースケール化、3x3 平滑化
 * 2. Sobel でエッジ強度を算出（照明変化に比較的強い形状情報）
 * 3. 基準画像との正規化相互相関（NCC）を ±[MAX_SHIFT] px の位置ずれを許容して計算
 * 4. 輝度 NCC とエッジ NCC の重み付き和を類似度（-1〜1）とする
 */
object ImageComparator {

    const val SIZE = 96
    private const val MAX_SHIFT = 4
    private const val GRAY_WEIGHT = 0.4f
    private const val EDGE_WEIGHT = 0.6f

    fun extract(src: Bitmap): ImageFeature {
        val scaled = Bitmap.createScaledBitmap(src, SIZE, SIZE, true)
        val pixels = IntArray(SIZE * SIZE)
        scaled.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        if (scaled !== src) scaled.recycle()

        val gray = FloatArray(pixels.size) { i ->
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            0.299f * r + 0.587f * g + 0.114f * b
        }
        val blurred = boxBlur3(gray)
        return ImageFeature(gray = blurred, edge = sobel(blurred))
    }

    fun similarity(a: ImageFeature, b: ImageFeature): Float =
        GRAY_WEIGHT * shiftedNcc(a.gray, b.gray) + EDGE_WEIGHT * shiftedNcc(a.edge, b.edge)

    /** 輝度の平均絶対差（0〜255）。連続フレーム間の揺れの大きさの目安。 */
    fun meanAbsDiff(a: ImageFeature, b: ImageFeature): Float {
        var sum = 0f
        for (i in a.gray.indices) sum += abs(a.gray[i] - b.gray[i])
        return sum / a.gray.size
    }

    private fun boxBlur3(src: FloatArray): FloatArray {
        val dst = FloatArray(src.size)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                var sum = 0f
                var count = 0
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy !in 0 until SIZE) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx !in 0 until SIZE) continue
                        sum += src[yy * SIZE + xx]
                        count++
                    }
                }
                dst[y * SIZE + x] = sum / count
            }
        }
        return dst
    }

    private fun sobel(src: FloatArray): FloatArray {
        val dst = FloatArray(src.size)
        for (y in 1 until SIZE - 1) {
            for (x in 1 until SIZE - 1) {
                fun p(dx: Int, dy: Int) = src[(y + dy) * SIZE + (x + dx)]
                val gx = (p(1, -1) + 2 * p(1, 0) + p(1, 1)) - (p(-1, -1) + 2 * p(-1, 0) + p(-1, 1))
                val gy = (p(-1, 1) + 2 * p(0, 1) + p(1, 1)) - (p(-1, -1) + 2 * p(0, -1) + p(1, -1))
                dst[y * SIZE + x] = sqrt(gx * gx + gy * gy)
            }
        }
        return dst
    }

    /** 位置ずれを許容した NCC。各シフトで重なり領域のみを使い、最大値を返す。 */
    private fun shiftedNcc(a: FloatArray, b: FloatArray): Float {
        var best = -1f
        for (sy in -MAX_SHIFT..MAX_SHIFT) {
            for (sx in -MAX_SHIFT..MAX_SHIFT) {
                val v = ncc(a, b, sx, sy)
                if (v > best) best = v
            }
        }
        return best
    }

    private fun ncc(a: FloatArray, b: FloatArray, sx: Int, sy: Int): Float {
        val x0 = maxOf(0, -sx)
        val x1 = minOf(SIZE, SIZE - sx)
        val y0 = maxOf(0, -sy)
        val y1 = minOf(SIZE, SIZE - sy)
        var sa = 0.0
        var sb = 0.0
        var saa = 0.0
        var sbb = 0.0
        var sab = 0.0
        var n = 0
        for (y in y0 until y1) {
            val rowA = y * SIZE
            val rowB = (y + sy) * SIZE + sx
            for (x in x0 until x1) {
                val va = a[rowA + x].toDouble()
                val vb = b[rowB + x].toDouble()
                sa += va
                sb += vb
                saa += va * va
                sbb += vb * vb
                sab += va * vb
                n++
            }
        }
        if (n == 0) return -1f
        val cov = sab - sa * sb / n
        val varA = saa - sa * sa / n
        val varB = sbb - sb * sb / n
        val denom = sqrt(varA * varB)
        // 無地（分散ほぼ 0）の画像同士は比較不能として 0 を返す
        return if (denom < 1e-6) 0f else (cov / denom).toFloat()
    }
}
