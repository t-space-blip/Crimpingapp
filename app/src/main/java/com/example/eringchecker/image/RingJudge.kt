package com.example.eringchecker.image

enum class Verdict { HAS_RING, NO_RING, UNCERTAIN, NO_TARGET }

data class JudgeResult(
    val verdict: Verdict,
    /** リング領域内での「リングなし」基準画像との類似度。 */
    val noRingScore: Float,
    /** リング領域内での「リングあり」基準画像との類似度。 */
    val hasRingScore: Float,
    /** 背景から変化した最大領域の面積割合（工具検知の根拠）。 */
    val changedArea: Float,
    /** リング領域で「リングあり」に近い画素の割合（0〜1）。 */
    val vote: Float = 0f,
    /** 位置合わせで求めたフレームのずれ量（96px 換算）。 */
    val shiftX: Int = 0,
    val shiftY: Int = 0,
)

/**
 * 1. 「背景のみ」の基準画像との差分で工具が写っているかを判定（[ToolDetector]）
 * 2. ガイド枠内全体が基準画像のどちらにも似ていなければ「対象なし」
 * 3. 2 枚の基準画像が食い違うリング領域（[RingRegion]）だけで比較し、
 *    「リングあり」「リングなし」のどちらの基準画像により似ているかで〇×を決める
 *    （同点の場合は見逃し防止のため NG）。
 */
class RingJudge(
    private val noRingRef: ImageFeature,
    private val hasRingRef: ImageFeature,
    private val background: ImageFeature,
) {

    val detector = ToolDetector(background, noRingRef, hasRingRef)
    val ringRegion = RingRegion(noRingRef, hasRingRef)

    /** 手順 1・2 だけを行い、ガイド枠内に工具があるかを返す（リング領域の比較はしない）。 */
    fun detect(frame: ImageFeature): Detection {
        val changedArea = detector.largestChangedArea(frame)
        if (!detector.isPresent(changedArea)) return Detection(false, changedArea)
        val globalScore = maxOf(
            ImageComparator.similarity(frame, noRingRef),
            ImageComparator.similarity(frame, hasRingRef),
        )
        if (globalScore < MIN_SIMILARITY) return Detection(false, changedArea)
        // 工具をどけた直後の露出変化や影で変化領域が残っても、背景のほうに似ていれば工具なしとする
        val backgroundScore = ImageComparator.similarity(frame, background)
        return Detection(globalScore - backgroundScore >= BACKGROUND_MARGIN, changedArea)
    }

    /** 工具ありと分かっているフレームについて、リング領域で「あり」「なし」のより似ている方に〇×を決める。 */
    fun judgeRing(frame: ImageFeature, changedArea: Float): JudgeResult {
        val s = ringRegion.compare(frame)
        val verdict = if (s.hasRing > s.noRing) Verdict.HAS_RING else Verdict.NO_RING
        return JudgeResult(verdict, s.noRing, s.hasRing, changedArea, s.vote, s.shiftX, s.shiftY)
    }

    companion object {
        /** ガイド枠内全体がどちらの基準画像にもこれ未満しか似ていなければ「対象なし」とする。 */
        const val MIN_SIMILARITY = 0.35f

        /** ガイド枠内全体が「背景のみ」より基準画像（工具あり）にこれ以上似ていなければ「対象なし」とする。 */
        const val BACKGROUND_MARGIN = 0.05f
    }
}

data class Detection(val present: Boolean, val changedArea: Float)

/** [JudgeSession] が画面に出すべき状態。 */
sealed interface SessionState {
    /** 枠内に工具なし。！・〇×をすべて消す。 */
    data object Idle : SessionState

    /**
     * 現在のフレームの判定結果。
     * [result] はこのフレーム単独の生の判定、[verdict] は直近数フレームの多数決で決めた表示用の判定。
     */
    data class Judged(val result: JudgeResult, val verdict: Verdict) : SessionState
}

/**
 * リアルタイム判定（解析スレッドからのみ呼ぶ）。
 *
 * 静止待ちや結果の保持はせず、枠内に工具がある間は毎フレーム〇×を判定し直す。
 * ただし手ブレや光のチラつきで〇×が高速に入れ替わらないよう、表示用の判定は
 * 直近 [WINDOW_SIZE] フレームの生の判定の多数決で決め、過半数が今の表示と食い違ったときだけ切り替える。
 * 工具が検知されなかったフレームでは即座に [SessionState.Idle] を返し、履歴も捨てて表示を消す。
 */
class JudgeSession(private val judge: RingJudge) {

    private val history = ArrayDeque<Verdict>()
    private var shown: Verdict? = null

    fun onFrame(frame: ImageFeature): SessionState {
        val detection = judge.detect(frame)
        if (!detection.present) {
            history.clear()
            shown = null
            return SessionState.Idle
        }
        val result = judge.judgeRing(frame, detection.changedArea)
        history.addLast(result.verdict)
        if (history.size > WINDOW_SIZE) history.removeFirst()
        val verdict = smoothed(shown, result.verdict)
        shown = verdict
        return SessionState.Judged(result, verdict)
    }

    /**
     * 検知直後の最初のフレームは応答性のため生の判定をそのまま出す。
     * 以降は履歴の過半数が今の表示と異なる判定になったときだけ切り替える（同数なら今の表示を保つ）。
     */
    private fun smoothed(current: Verdict?, latest: Verdict): Verdict {
        if (current == null) return latest
        val otherCount = history.count { it != current }
        return if (otherCount * 2 > history.size) latest else current
    }

    companion object {
        /** 多数決に使う直近フレーム数（250ms 間隔なら 5 フレーム ≒ 1.25 秒）。 */
        const val WINDOW_SIZE = 5
    }
}
