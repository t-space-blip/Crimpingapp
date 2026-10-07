package com.example.eringchecker.image

import kotlin.math.abs
import kotlin.math.sqrt

/** リング領域での比較結果。 */
class RingScores(
    /** リング領域内での「リングなし」基準画像との類似度。 */
    val noRing: Float,
    /** リング領域内での「リングあり」基準画像との類似度。 */
    val hasRing: Float,
    /** リング領域の画素のうち、「リングなし」より「リングあり」に近い画素の割合（0〜1）。 */
    val vote: Float,
    /** フレームを「リングあり」基準画像に重ねるためのずれ量（96px 換算）。 */
    val shiftX: Int,
    val shiftY: Int,
)

/**
 * 「リングあり」と「リングなし」の基準画像が食い違う部分＝リング周辺の領域（マスク）を求め、
 * その領域だけで現在フレームとの類似度を計算する。
 *
 * 画面全体で比較するとリングは面積が小さく、工具本体や位置ずれの影響に埋もれてしまう
 * （テープでリングを隠しても全体の類似度はほとんど変わらない）。
 * リング領域に絞ることで、リングの見え方の違いだけを判定に使う。
 *
 * 位置ずれ対策として、まず工具全体のエッジでフレームを「リングあり」基準画像に大まかに位置合わせし
 * （±[ALIGN_SHIFT]px）、その周辺 ±[REFINE_SHIFT]px だけでリング領域を比較する。
 * 「あり」「なし」の両方を同じフレーム位置で比較するため、片方だけ都合のよい位置で高得点になることがない。
 */
class RingRegion(
    private val noRingRef: ImageFeature,
    private val hasRingRef: ImageFeature,
) {

    private val size = ImageComparator.SIZE

    /** リングあり基準画像の座標系でのリング領域。基準画像がほぼ同一なら null（全体比較にフォールバック）。 */
    val mask: BooleanArray?

    /** リングなし基準画像を、リングあり基準画像に重ねるためのずれ量。 */
    val noShiftX: Int
    val noShiftY: Int

    /** リングなし基準画像の明るさをリングあり基準画像にそろえる係数（撮影時の露出差の補正）。 */
    val noGain: Float

    init {
        val (sx, sy) = bestShift(hasRingRef.edge, noRingRef.edge, ALIGN_SHIFT)
        noShiftX = sx
        noShiftY = sy
        noGain = median(hasRingRef.gray) / maxOf(median(noRingRef.gray), 1f)
        var m = BooleanArray(size * size) { i ->
            val x = i % size
            val y = i / size
            val xx = x + sx
            val yy = y + sy
            if (xx !in 0 until size || yy !in 0 until size) return@BooleanArray false
            val j = yy * size + xx
            val grayDiff = abs(hasRingRef.gray[i] - noRingRef.gray[j] * noGain)
            val edgeDiff = abs(hasRingRef.edge[i] - noRingRef.edge[j] * noGain) * EDGE_DIFF_SCALE
            maxOf(grayDiff, edgeDiff) > DIFF_THRESHOLD
        }
        // 細かいノイズを除去（収縮→膨張）し、リング周辺の文脈を含めるため膨張させる
        m = dilate(erode(m, 1), 1)
        m = dilate(m, MASK_MARGIN)
        val area = m.count { it }.toFloat() / m.size
        mask = if (area < MIN_MASK_FRACTION) null else m
    }

    val maskFraction: Float get() = mask?.let { it.count { v -> v }.toFloat() / it.size } ?: 0f

    /** フレームを位置合わせしたうえで、リング領域内の「リングあり」「リングなし」との類似度を求める。 */
    fun compare(frame: ImageFeature): RingScores {
        val (ax, ay) = bestShift(hasRingRef.edge, frame.edge, ALIGN_SHIFT)
        val m = mask
        if (m == null) {
            return RingScores(
                noRing = ImageComparator.similarity(frame, noRingRef),
                hasRing = ImageComparator.similarity(frame, hasRingRef),
                vote = 0.5f, shiftX = ax, shiftY = ay,
            )
        }
        var hasBest = -1f
        var noBest = -1f
        var bestX = ax
        var bestY = ay
        for (dy in -REFINE_SHIFT..REFINE_SHIFT) {
            for (dx in -REFINE_SHIFT..REFINE_SHIFT) {
                val fx = ax + dx
                val fy = ay + dy
                val h = regionNcc(m, hasRingRef, 0, 0, frame, fx, fy)
                if (h > hasBest) {
                    hasBest = h; bestX = fx; bestY = fy
                }
                val n = regionNcc(m, noRingRef, noShiftX, noShiftY, frame, fx, fy)
                if (n > noBest) noBest = n
            }
        }
        return RingScores(noBest, hasBest, pixelVote(m, frame, bestX, bestY), bestX, bestY)
    }

    /** マスク内で、基準画像（[refOffX]/[refOffY] ずらし）とフレーム（[fx]/[fy] ずらし）の輝度・エッジ NCC の重み付き和。 */
    private fun regionNcc(
        m: BooleanArray, ref: ImageFeature, refOffX: Int, refOffY: Int,
        frame: ImageFeature, fx: Int, fy: Int,
    ): Float {
        val g = maskedNcc(m, ref.gray, frame.gray, refOffX, refOffY, fx, fy)
        val e = maskedNcc(m, ref.edge, frame.edge, refOffX, refOffY, fx, fy)
        return GRAY_WEIGHT * g + EDGE_WEIGHT * e
    }

    private fun maskedNcc(
        m: BooleanArray, ref: FloatArray, frame: FloatArray,
        refOffX: Int, refOffY: Int, fOffX: Int, fOffY: Int,
    ): Float {
        var sa = 0.0
        var sb = 0.0
        var saa = 0.0
        var sbb = 0.0
        var sab = 0.0
        var n = 0
        for (i in m.indices) {
            if (!m[i]) continue
            val x = i % size
            val y = i / size
            val rx = x + refOffX
            val ry = y + refOffY
            val fx = x + fOffX
            val fy = y + fOffY
            if (rx !in 0 until size || ry !in 0 until size) continue
            if (fx !in 0 until size || fy !in 0 until size) continue
            val va = ref[ry * size + rx].toDouble()
            val vb = frame[fy * size + fx].toDouble()
            sa += va
            sb += vb
            saa += va * va
            sbb += vb * vb
            sab += va * vb
            n++
        }
        if (n < MIN_PIXELS) return -1f
        val cov = sab - sa * sb / n
        val varA = saa - sa * sa / n
        val varB = sbb - sb * sb / n
        val denom = sqrt(varA * varB)
        // 一方が無地（テープで塗りつぶされた等）なら似ていないとみなす
        return if (denom < 1e-6) 0f else (cov / denom).toFloat()
    }

    /**
     * マスク内の画素ごとに、フレームが「リングあり」「リングなし」のどちらの輝度に近いかを数え、
     * 「リングあり」に近い画素の割合を返す。光の反射など一部の画素の大きな外れは 1 票にしかならないため、
     * NCC より局所的な外れ値に強い。両基準画像の差が小さい画素は判定に使わない。
     */
    private fun pixelVote(m: BooleanArray, frame: ImageFeature, fx: Int, fy: Int): Float {
        // フレームの明るさをリングあり基準画像にそろえる（露出の自動調整対策）
        val gain = median(hasRingRef.gray) / maxOf(median(frame.gray), 1f)
        var votes = 0
        var n = 0
        for (i in m.indices) {
            if (!m[i]) continue
            val x = i % size
            val y = i / size
            val nx = x + noShiftX
            val ny = y + noShiftY
            val ffx = x + fx
            val ffy = y + fy
            if (nx !in 0 until size || ny !in 0 until size) continue
            if (ffx !in 0 until size || ffy !in 0 until size) continue
            val h = hasRingRef.gray[i]
            val no = noRingRef.gray[ny * size + nx] * noGain
            if (abs(h - no) < VOTE_MIN_DIFF) continue
            val f = frame.gray[ffy * size + ffx] * gain
            if (abs(f - h) < abs(f - no)) votes++
            n++
        }
        return if (n < MIN_PIXELS) 0.5f else votes.toFloat() / n
    }

    /**
     * b を (sx, sy) ずらしたとき a と最もよく重なるずれ量（±[range]）。
     * 2px 刻みで粗く探してから周囲 ±1px を詳しく探す。
     */
    private fun bestShift(a: FloatArray, b: FloatArray, range: Int): Pair<Int, Int> {
        var best = -2.0
        var bx = 0
        var by = 0
        for (sy in -range..range step 2) {
            for (sx in -range..range step 2) {
                val v = shiftNcc(a, b, sx, sy)
                if (v > best) {
                    best = v; bx = sx; by = sy
                }
            }
        }
        val cx = bx
        val cy = by
        for (sy in cy - 1..cy + 1) {
            for (sx in cx - 1..cx + 1) {
                if (abs(sx) > range || abs(sy) > range || (sx == cx && sy == cy)) continue
                val v = shiftNcc(a, b, sx, sy)
                if (v > best) {
                    best = v; bx = sx; by = sy
                }
            }
        }
        return bx to by
    }

    private fun shiftNcc(a: FloatArray, b: FloatArray, sx: Int, sy: Int): Double {
        var sa = 0.0
        var sb = 0.0
        var saa = 0.0
        var sbb = 0.0
        var sab = 0.0
        var n = 0
        for (y in maxOf(0, -sy) until minOf(size, size - sy)) {
            for (x in maxOf(0, -sx) until minOf(size, size - sx)) {
                val va = a[y * size + x].toDouble()
                val vb = b[(y + sy) * size + x + sx].toDouble()
                sa += va; sb += vb; saa += va * va; sbb += vb * vb; sab += va * vb
                n++
            }
        }
        if (n == 0) return -1.0
        val denom = sqrt((saa - sa * sa / n) * (sbb - sb * sb / n))
        return if (denom < 1e-6) 0.0 else (sab - sa * sb / n) / denom
    }

    private fun median(values: FloatArray): Float {
        val sorted = values.copyOf().apply { sort() }
        return sorted[sorted.size / 2]
    }

    private fun erode(m: BooleanArray, r: Int) = morph(m, r, keepIfAll = true)

    private fun dilate(m: BooleanArray, r: Int) = morph(m, r, keepIfAll = false)

    private fun morph(m: BooleanArray, r: Int, keepIfAll: Boolean): BooleanArray =
        BooleanArray(m.size) { i ->
            val x = i % size
            val y = i / size
            var all = true
            var any = false
            for (dy in -r..r) {
                val yy = y + dy
                if (yy !in 0 until size) continue
                for (dx in -r..r) {
                    val xx = x + dx
                    if (xx !in 0 until size) continue
                    if (m[yy * size + xx]) any = true else all = false
                }
            }
            if (keepIfAll) all else any
        }

    companion object {
        /** 2 枚の基準画像の輝度差（0〜255、明るさ補正後）がこれを超える画素をリング候補とする。 */
        const val DIFF_THRESHOLD = 25f

        /** エッジ強度差を輝度差と同じ尺度にそろえる係数。 */
        const val EDGE_DIFF_SCALE = 0.25f

        /** リング領域の周囲に含める余白（px、96px 換算）。 */
        const val MASK_MARGIN = 3

        /** リング領域がこれ未満の面積割合なら基準画像に差がないとみなし、全体比較にする。 */
        const val MIN_MASK_FRACTION = 0.003f

        /** リング領域がこれを超える面積割合なら、基準画像 2 枚の撮影条件（位置・明るさ）がずれている疑いがある。 */
        const val WARN_MASK_FRACTION = 0.35f

        /** 工具全体での位置合わせで許容する位置ずれ（px、96px 換算）。 */
        const val ALIGN_SHIFT = 14

        /** 位置合わせ後、リング領域の比較で追加で許容する位置ずれ（px、96px 換算）。 */
        const val REFINE_SHIFT = 2

        /** 画素投票で、両基準画像の輝度差がこれ未満の画素は使わない。 */
        const val VOTE_MIN_DIFF = 12f

        private const val MIN_PIXELS = 20
        private const val GRAY_WEIGHT = 0.4f
        private const val EDGE_WEIGHT = 0.6f
    }
}
