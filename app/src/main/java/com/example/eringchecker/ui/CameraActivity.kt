package com.example.eringchecker.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.example.eringchecker.R
import com.example.eringchecker.camera.RoiFrameAnalyzer
import com.example.eringchecker.data.SampleStore
import com.example.eringchecker.data.SampleType
import com.example.eringchecker.data.ToolSize
import com.example.eringchecker.data.ToolSizeRepository
import com.example.eringchecker.databinding.ActivityCameraBinding
import com.example.eringchecker.image.AI_INFERENCE_INTERVAL_MS
import com.example.eringchecker.image.AI_SHOW_DEBUG_SCORES
import com.example.eringchecker.image.AiRingClassifier
import com.example.eringchecker.image.AiScores
import com.example.eringchecker.image.AiVerdictStabilizer
import com.example.eringchecker.image.ImageComparator
import com.example.eringchecker.image.JudgeSession
import com.example.eringchecker.image.RingJudge
import com.example.eringchecker.image.RingRegion
import com.example.eringchecker.image.SessionState
import com.example.eringchecker.image.Verdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * カメラ画面（横画面固定）。
 * - SAMPLE モード: 「リングなし」「リングあり」「背景のみ」の基準画像を撮影・保存
 * - JUDGE モード : AI モデル（[AiRingClassifier]）でリアルタイム判定して結果を表示
 *   （[USE_AI_JUDGE] を false にすると、以前の基準画像との比較による判定に戻る）
 */
class CameraActivity : AppCompatActivity() {

    enum class Mode { SAMPLE, JUDGE }

    private lateinit var binding: ActivityCameraBinding
    private lateinit var store: SampleStore
    private lateinit var size: ToolSize
    private lateinit var analysisExecutor: ExecutorService

    @Volatile
    private var mode = Mode.SAMPLE

    /** 判定モード中のみ非 null。解析スレッドから参照する（判定モードに入るたびに作り直してリセット）。 */
    @Volatile
    private var session: JudgeSession? = null

    /** AI 判定モード中のみ非 null。解析スレッドでだけ使う。 */
    @Volatile
    private var classifier: AiRingClassifier? = null

    @Volatile
    private var stabilizer = AiVerdictStabilizer()

    /** 最後に AI 判定した時刻（解析スレッドでだけ使う）。 */
    private var lastInferenceAt = 0L

    /** 最新フレームのガイド枠内画像（サンプル撮影ボタン押下時に保存する）。 */
    private val latestRoi = AtomicReference<Bitmap?>()

    private var lastLoggedState: SessionState? = null
    private val clearVerdictRunnable = Runnable { if (mode == Mode.JUDGE) clearVerdictDisplay() }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, R.string.toast_permission_denied, Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)
        hideSystemBars()

        size = ToolSizeRepository.findById(intent.getStringExtra(EXTRA_SIZE_ID)) ?: run {
            finish()
            return
        }
        store = SampleStore(this)
        analysisExecutor = Executors.newSingleThreadExecutor()

        val requested = savedInstanceState?.getString(STATE_MODE)
            ?: intent.getStringExtra(EXTRA_MODE)
        val initialMode = Mode.entries.firstOrNull { it.name == requested } ?: Mode.SAMPLE

        binding.buttonCaptureNoRing.setOnClickListener { captureSample(SampleType.NO_RING) }
        binding.buttonCaptureHasRing.setOnClickListener { captureSample(SampleType.HAS_RING) }
        binding.buttonCaptureBackground.setOnClickListener { captureSample(SampleType.BACKGROUND) }
        binding.buttonGoJudge.setOnClickListener { enterJudgeMode() }
        binding.buttonGoSample.setOnClickListener { enterSampleMode() }

        if (initialMode == Mode.JUDGE) enterJudgeMode() else enterSampleMode()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_MODE, mode.name)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::binding.isInitialized) binding.root.removeCallbacks(clearVerdictRunnable)
        if (::analysisExecutor.isInitialized) {
            // Interpreter は解析スレッドで使っているので、同じスレッドで閉じる
            analysisExecutor.execute { classifier?.close(); classifier = null }
            analysisExecutor.shutdown()
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ---------------------------------------------------------------- カメラ

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            // ビューポートはレイアウト確定後に取得できる
            binding.previewView.post { bindUseCases(provider) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindUseCases(provider: ProcessCameraProvider) {
        if (isFinishing || isDestroyed) return
        val viewPort = binding.previewView.viewPort ?: return

        // 前面（インカメラ）を使用。搭載されていない端末では背面にフォールバック
        val useFront = provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
        val selector =
            if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.previewView.surfaceProvider)
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { it.setAnalyzer(analysisExecutor, RoiFrameAnalyzer(mirror = useFront, onRoi = ::onRoiFrame, onFrame = ::onAiFrame)) }

        val group = UseCaseGroup.Builder()
            .setViewPort(viewPort)
            .addUseCase(preview)
            .addUseCase(analysis)
            .build()

        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, group)
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed", e)
            Toast.makeText(this, R.string.toast_camera_error, Toast.LENGTH_LONG).show()
        }
    }

    /** 解析スレッドで呼ばれる。 */
    private fun onRoiFrame(roi: Bitmap) {
        latestRoi.set(roi)

        val currentSession = session
        if (mode != Mode.JUDGE || currentSession == null) return

        // 間引きせず毎フレーム判定する（処理が追いつかない分は STRATEGY_KEEP_ONLY_LATEST で捨てられる）
        val state = currentSession.onFrame(ImageComparator.extract(roi))
        logState(state)
        runOnUiThread { showState(state) }
    }

    /** 解析スレッドで呼ばれる。AI 判定モード中、[AI_INFERENCE_INTERVAL_MS] ごとにモデルに通す。 */
    private fun onAiFrame(frame: Bitmap) {
        val currentClassifier = classifier
        if (mode != Mode.JUDGE || currentClassifier == null) return
        val now = System.currentTimeMillis()
        if (now - lastInferenceAt < AI_INFERENCE_INTERVAL_MS) return
        lastInferenceAt = now

        val scores = currentClassifier.classify(frame)
        val raw = AiRingClassifier.decide(scores)
        val shown = stabilizer.update(raw)
        Log.d(TAG, "AI OK=%.2f NG=%.2f 工具なし=%.2f → %s（表示: %s）".format(
            scores.ok, scores.ng, scores.noTool, raw, shown))
        runOnUiThread { showAiResult(shown, scores) }
    }

    // ---------------------------------------------------------------- サンプル撮影

    private fun enterSampleMode() {
        mode = Mode.SAMPLE
        session = null
        binding.textAiScores.visibility = View.GONE
        binding.root.removeCallbacks(clearVerdictRunnable)
        binding.textModeTitle.text = getString(R.string.title_sample_mode, size.displayName)
        binding.samplePanel.visibility = View.VISIBLE
        binding.judgePanel.visibility = View.GONE
        binding.resultBanner.visibility = View.GONE
        binding.imageVerdictMark.visibility = View.GONE
        binding.textToolDetected.visibility = View.GONE
        binding.guideOverlay.frameColor = Color.WHITE
        binding.guideOverlay.visibility = View.VISIBLE
        refreshSampleThumbnails()
    }

    private fun captureSample(type: SampleType) {
        val roi = latestRoi.get()
        if (roi == null) {
            Toast.makeText(this, R.string.toast_camera_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        setCaptureButtonsEnabled(false)
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { store.save(size.id, type, roi) }
            val label = getString(labelOf(type))
            Toast.makeText(
                this@CameraActivity,
                if (ok) getString(R.string.toast_saved, label) else getString(R.string.toast_save_failed),
                Toast.LENGTH_SHORT,
            ).show()
            setCaptureButtonsEnabled(true)
            refreshSampleThumbnails()
        }
    }

    private fun setCaptureButtonsEnabled(enabled: Boolean) {
        binding.buttonCaptureNoRing.isEnabled = enabled
        binding.buttonCaptureHasRing.isEnabled = enabled
        binding.buttonCaptureBackground.isEnabled = enabled
    }

    private fun refreshSampleThumbnails() {
        lifecycleScope.launch {
            val samples = withContext(Dispatchers.IO) {
                SampleType.entries.associateWith { store.load(size.id, it) }
            }
            showThumbnail(binding.imageNoRing, binding.textNoRingEmpty, samples[SampleType.NO_RING])
            showThumbnail(binding.imageHasRing, binding.textHasRingEmpty, samples[SampleType.HAS_RING])
            showThumbnail(binding.imageBackground, binding.textBackgroundEmpty, samples[SampleType.BACKGROUND])
            // AI 判定は基準画像を使わないので、未登録でも判定へ進める
            binding.buttonGoJudge.isEnabled = USE_AI_JUDGE || samples.values.all { it != null }
        }
    }

    private fun showThumbnail(image: ImageView, emptyLabel: TextView, bitmap: Bitmap?) {
        image.setImageBitmap(bitmap)
        emptyLabel.visibility = if (bitmap == null) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------- 判定

    private fun enterJudgeMode() {
        if (USE_AI_JUDGE) enterAiJudgeMode() else enterLegacyJudgeMode()
    }

    private fun enterAiJudgeMode() {
        lifecycleScope.launch {
            if (classifier == null) {
                classifier = withContext(Dispatchers.Default) {
                    runCatching { AiRingClassifier(this@CameraActivity) }
                        .onFailure { Log.e(TAG, "AI モデルの読み込みに失敗", it) }
                        .getOrNull()
                }
            }
            if (classifier == null) {
                Toast.makeText(this@CameraActivity, R.string.toast_ai_load_failed, Toast.LENGTH_LONG).show()
                enterSampleMode()
                return@launch
            }
            stabilizer = AiVerdictStabilizer()
            session = null
            mode = Mode.JUDGE
            binding.textModeTitle.text = getString(R.string.title_judge_mode, size.displayName)
            binding.samplePanel.visibility = View.GONE
            binding.judgePanel.visibility = View.VISIBLE
            binding.textAiScores.text = ""
            binding.textAiScores.visibility = if (AI_SHOW_DEBUG_SCORES) View.VISIBLE else View.GONE
            // AI は映像全体の中央を見るので、作業者に位置合わせさせないよう枠は出さない
            binding.guideOverlay.visibility = View.GONE
            clearVerdictDisplay()
        }
    }

    /** 以前の、基準画像との比較による判定（[USE_AI_JUDGE] が false のときだけ使う）。 */
    private fun enterLegacyJudgeMode() {
        lifecycleScope.launch {
            val newJudge = withContext(Dispatchers.Default) {
                val noRing = store.load(size.id, SampleType.NO_RING)
                val hasRing = store.load(size.id, SampleType.HAS_RING)
                val background = store.load(size.id, SampleType.BACKGROUND)
                if (noRing == null || hasRing == null || background == null) {
                    null
                } else {
                    RingJudge(
                        ImageComparator.extract(noRing),
                        ImageComparator.extract(hasRing),
                        ImageComparator.extract(background),
                    ).also {
                        val region = it.ringRegion
                        Log.d(TAG, "工具検知しきい値（変化領域の面積割合）=%.3f リング領域の面積割合=%.3f 基準画像間のずれ=(%d,%d) 明るさ補正=%.2f%s".format(
                            it.detector.areaThreshold, region.maskFraction,
                            region.noShiftX, region.noShiftY, region.noGain,
                            when {
                                region.mask == null -> "（基準画像の差が小さいため全体比較）"
                                region.maskFraction > RingRegion.WARN_MASK_FRACTION ->
                                    "（リング領域が広すぎます。基準画像を同じ位置・明るさで撮り直してください）"
                                else -> ""
                            }))
                    }
                }
            }
            if (newJudge == null) {
                // 基準画像が欠けている場合はサンプル撮影へ戻す
                Toast.makeText(this@CameraActivity, R.string.toast_samples_missing, Toast.LENGTH_LONG).show()
                enterSampleMode()
                return@launch
            }
            session = JudgeSession(newJudge)
            mode = Mode.JUDGE
            binding.textModeTitle.text = getString(R.string.title_judge_mode, size.displayName)
            binding.samplePanel.visibility = View.GONE
            binding.judgePanel.visibility = View.VISIBLE
            binding.resultBanner.visibility = View.VISIBLE
            binding.textResult.setText(R.string.result_waiting)
            binding.resultBanner.setBackgroundColor(getColor(R.color.result_neutral))
            binding.imageVerdictMark.visibility = View.GONE
            binding.textToolDetected.visibility = View.GONE
            binding.guideOverlay.visibility = View.VISIBLE
        }
    }

    /** 解析スレッドで呼ばれる。判定結果は毎回、工具なしは切り替わったときだけ Logcat に出す。 */
    private fun logState(state: SessionState) {
        val prev = lastLoggedState
        lastLoggedState = state
        when (state) {
            SessionState.Idle ->
                if (prev != SessionState.Idle) Log.d(TAG, "枠内工具なし → 表示リセット")
            is SessionState.Judged -> {
                val r = state.result
                Log.d(TAG, "枠内変化領域=%.3f ずれ=(%d,%d) リング領域の類似度 リングなし=%.2f リングあり=%.2f 差=%+.2f リングあり画素率=%.2f → %s（表示: %s）".format(
                    r.changedArea, r.shiftX, r.shiftY, r.noRingScore, r.hasRingScore,
                    r.hasRingScore - r.noRingScore, r.vote, r.verdict, state.verdict))
            }
        }
    }

    private fun showState(state: SessionState) {
        if (mode != Mode.JUDGE) return
        // 解析が止まった等で状態が届かなくなったときに古い表示が残らないよう、一定時間で自動的に消す
        // （工具を枠内に置いている間は毎フレーム判定結果が届くので消えない）
        binding.root.removeCallbacks(clearVerdictRunnable)
        binding.root.postDelayed(clearVerdictRunnable, RESULT_TIMEOUT_MS)

        when (state) {
            SessionState.Idle -> clearVerdictDisplay()
            is SessionState.Judged -> {
                val verdict = state.verdict
                val (textRes, colorRes) = when (verdict) {
                    Verdict.HAS_RING -> R.string.result_has_ring to R.color.result_ok
                    else -> R.string.result_no_ring to R.color.result_ng
                }
                val color = getColor(colorRes)
                binding.textResult.setText(textRes)
                binding.resultBanner.setBackgroundColor(color)
                binding.guideOverlay.frameColor = color
                binding.textToolDetected.visibility = View.VISIBLE
                showVerdictMark(verdict)
            }
        }
    }

    /** AI 判定の結果を表示する。工具なしのときは判定表示（灰色の四角も含めて）を出さない。 */
    private fun showAiResult(verdict: Verdict, scores: AiScores) {
        if (mode != Mode.JUDGE) return
        binding.root.removeCallbacks(clearVerdictRunnable)
        binding.root.postDelayed(clearVerdictRunnable, RESULT_TIMEOUT_MS)

        if (AI_SHOW_DEBUG_SCORES) {
            binding.textAiScores.text = getString(R.string.debug_ai_scores,
                Math.round(scores.ok * 100), Math.round(scores.ng * 100), Math.round(scores.noTool * 100))
        }

        val (textRes, colorRes) = when (verdict) {
            Verdict.HAS_RING -> R.string.result_has_ring to R.color.result_ok
            Verdict.NO_RING -> R.string.result_no_ring to R.color.result_ng
            Verdict.UNCERTAIN -> R.string.result_uncertain to R.color.result_neutral
            Verdict.NO_TARGET -> {
                clearVerdictDisplay()
                return
            }
        }
        val color = getColor(colorRes)
        binding.textResult.setText(textRes)
        binding.resultBanner.setBackgroundColor(color)
        binding.resultBanner.visibility = View.VISIBLE
        // 「判定できません」のときは枠を白のままにする
        binding.guideOverlay.frameColor = if (verdict == Verdict.UNCERTAIN) Color.WHITE else color
        binding.textToolDetected.visibility = View.VISIBLE
        showVerdictMark(verdict)
    }

    /** 判定表示（！・〇×・結果文字・枠の色）をすべてリセットする。 */
    private fun clearVerdictDisplay() {
        binding.textResult.text = ""
        binding.resultBanner.setBackgroundColor(getColor(R.color.result_neutral))
        // AI 判定では、判定がないときは灰色の四角も出さない
        if (USE_AI_JUDGE) binding.resultBanner.visibility = View.GONE
        binding.guideOverlay.frameColor = Color.WHITE
        binding.textToolDetected.visibility = View.GONE
        showVerdictMark(Verdict.NO_TARGET)
    }

    /** OK なら緑の〇、NG なら赤の×をガイド枠の中央に重ねる。それ以外は消す */
    private fun showVerdictMark(verdict: Verdict) {
        val markRes = when (verdict) {
            Verdict.HAS_RING -> R.drawable.ic_result_ok
            Verdict.NO_RING -> R.drawable.ic_result_ng
            else -> null
        }
        val mark = binding.imageVerdictMark
        if (markRes == null) {
            mark.visibility = View.GONE
            mark.tag = null
            return
        }
        // 同じ判定が続く間は描き直さない
        if (mark.tag == markRes && mark.visibility == View.VISIBLE) return
        mark.setImageResource(markRes)
        mark.tag = markRes
        mark.visibility = View.VISIBLE
    }

    private fun labelOf(type: SampleType) = when (type) {
        SampleType.NO_RING -> R.string.label_no_ring
        SampleType.HAS_RING -> R.string.label_has_ring
        SampleType.BACKGROUND -> R.string.label_background
    }

    companion object {
        private const val TAG = "CameraActivity"
        private const val EXTRA_SIZE_ID = "extra_size_id"
        private const val EXTRA_MODE = "extra_mode"
        private const val STATE_MODE = "state_mode"
        /** この時間状態が届かなければ（解析停止など）、！・〇×を消す。 */
        private const val RESULT_TIMEOUT_MS = 1000L
        /** true: AI モデルで判定する。false: 以前の基準画像との比較で判定する。 */
        const val USE_AI_JUDGE = true

        fun newIntent(context: Context, sizeId: String, mode: Mode): Intent =
            Intent(context, CameraActivity::class.java)
                .putExtra(EXTRA_SIZE_ID, sizeId)
                .putExtra(EXTRA_MODE, mode.name)
    }
}
