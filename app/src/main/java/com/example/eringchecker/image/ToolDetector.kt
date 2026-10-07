package com.example.eringchecker.image

import kotlin.math.abs

/**
 * 「背景のみ」の基準画像との差分で、カメラに工具が提示されているかを判定する。
 *
 * 1. 露出の自動調整で全体の明るさが変わっても誤検知しないよう、中央値の比で背景の明るさを合わせる
 * 2. 背景との輝度差が [DIFF_THRESHOLD] を超える画素を「変化あり」とする
 *    （手ぶれ程度の位置ずれは ±1px の近傍で最小差をとって吸収）
 * 3. 変化画素の連結領域（輪郭）のうち最大のものの面積割合を求める
 * 4. その割合が、基準画像（工具あり）での面積の [REF_AREA_RATIO] 倍以上なら工具ありとする
 *    （ただし最低でも [MIN_AREA_FRACTION]）
 *
 * 散発的なノイズは小さな領域に分かれるため、最大領域だけを見ることで除外できる。
 */
class ToolDetector(
    private val background: ImageFeature,
    noRingRef: ImageFeature,
    hasRingRef: ImageFeature,
) {

    /** 工具ありとみなす最大変化領域の面積割合（0〜1）。 */
    val areaThreshold: Float = run {
        val refArea = minOf(largestChangedArea(noRingRef), largestChangedArea(hasRingRef))
        maxOf(MIN_AREA_FRACTION, refArea * REF_AREA_RATIO)
    }

    /** 背景から変化した最大領域の面積割合（0〜1）。 */
    fun largestChangedArea(frame: ImageFeature): Float {
        val mask = changedMask(frame.gray, background.gray)
        return largestBlob(mask).toFloat() / mask.size
    }

    fun isPresent(changedArea: Float): Boolean = changedArea >= areaThreshold

    private fun changedMask(frame: FloatArray, bg: FloatArray): BooleanArray {
        val size = ImageComparator.SIZE
        val gain = median(frame) / maxOf(median(bg), 1f)
        return BooleanArray(frame.size) { i ->
            val x = i % size
            val y = i / size
            var minDiff = Float.MAX_VALUE
            for (dy in -1..1) {
                val yy = y + dy
                if (yy !in 0 until size) continue
                for (dx in -1..1) {
                    val xx = x + dx
                    if (xx !in 0 until size) continue
                    val d = abs(frame[i] - bg[yy * size + xx] * gain)
                    if (d < minDiff) minDiff = d
                }
            }
            minDiff > DIFF_THRESHOLD
        }
    }

    /** 4 近傍で連結した変化画素の最大領域の画素数。 */
    private fun largestBlob(mask: BooleanArray): Int {
        val size = ImageComparator.SIZE
        val visited = BooleanArray(mask.size)
        val stack = IntArray(mask.size)
        var best = 0
        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue
            var top = 0
            var count = 0
            stack[top++] = start
            visited[start] = true
            while (top > 0) {
                val p = stack[--top]
                count++
                val x = p % size
                val y = p / size
                if (x > 0) top = push(mask, visited, stack, top, p - 1)
                if (x < size - 1) top = push(mask, visited, stack, top, p + 1)
                if (y > 0) top = push(mask, visited, stack, top, p - size)
                if (y < size - 1) top = push(mask, visited, stack, top, p + size)
            }
            if (count > best) best = count
        }
        return best
    }

    private fun push(mask: BooleanArray, visited: BooleanArray, stack: IntArray, top: Int, p: Int): Int {
        if (!mask[p] || visited[p]) return top
        visited[p] = true
        stack[top] = p
        return top + 1
    }

    private fun median(values: FloatArray): Float {
        val sorted = values.copyOf().apply { sort() }
        return sorted[sorted.size / 2]
    }

    companion object {
        /** 背景との輝度差（0〜255）がこれを超える画素を変化ありとする。 */
        const val DIFF_THRESHOLD = 30f

        /** 工具ありとみなす最大変化領域の下限（ガイド枠内の面積に対する割合）。 */
        const val MIN_AREA_FRACTION = 0.02f

        /** 基準画像で測った工具の面積に対し、これ以上の割合が見えていれば工具ありとする。 */
        const val REF_AREA_RATIO = 0.4f
    }
}
