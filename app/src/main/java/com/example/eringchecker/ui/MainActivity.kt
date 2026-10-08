package com.example.eringchecker.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import com.example.eringchecker.R
import com.example.eringchecker.data.SampleStore
import com.example.eringchecker.data.SampleType
import com.example.eringchecker.data.ToolSize
import com.example.eringchecker.data.ToolType
import com.example.eringchecker.databinding.ActivityMainBinding
import com.example.eringchecker.ui.widget.TypeSwitchButton
import kotlin.math.max

/** ホーム画面（縦画面固定）：工具タイプ・サイズ選択と、サンプル撮影／判定への遷移。 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: SampleStore

    /** タイプ選択ボタンと対応する工具タイプ。 */
    private lateinit var typeButtons: Map<TypeSwitchButton, ToolType>

    /** 選択中の工具タイプ。起動直後は未選択（null）。 */
    private var selectedType: ToolType? = null

    /** タイプ未選択、またはサイズ未選択（ドラムロールが先頭行）の場合は null。 */
    private var selectedSize: ToolSize? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // 背景を画面全体（ステータスバーの裏まで）に敷き、明るい背景なのでバーのアイコンは暗色にする
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = SampleStore(this)

        setupTypeButtons()
        setupSizePicker()
        setupBackgroundSymbols()

        binding.buttonSampleCapture.setOnClickListener {
            val size = selectedSize ?: return@setOnClickListener
            startActivity(CameraActivity.newIntent(this, size.id, CameraActivity.Mode.SAMPLE))
        }

        binding.buttonStartJudge.setOnClickListener {
            val size = selectedSize ?: return@setOnClickListener
            if (store.hasAll(size.id)) {
                startActivity(CameraActivity.newIntent(this, size.id, CameraActivity.Mode.JUDGE))
            } else {
                // 基準画像が未登録 → 警告してサンプル撮影へ誘導
                Toast.makeText(this, R.string.toast_samples_missing, Toast.LENGTH_LONG).show()
                startActivity(CameraActivity.newIntent(this, size.id, CameraActivity.Mode.SAMPLE))
            }
        }

        updateUi()
    }

    override fun onResume() {
        super.onResume()
        // サンプル撮影から戻ったときに登録状況を更新
        updateUi()
    }

    /** タイプ選択ボタン（単一選択）。選択に応じてドラムロールの選択肢を切り替える。 */
    private fun setupTypeButtons() {
        typeButtons = mapOf(
            binding.buttonTypeA to ToolType.A_TYPE,
            binding.buttonTypeMini to ToolType.MINI_TYPE,
            binding.buttonTypeHozan to ToolType.HOZAN,
        )
        typeButtons.forEach { (button, type) ->
            button.setOnClickListener { selectType(type) }
        }
        updateTypeButtons()
    }

    private fun selectType(type: ToolType) {
        if (type == selectedType) return
        selectedType = type
        updateTypeButtons()
        updateSizeItems()
    }

    /** 選択中のボタンだけを押し込まれて光った状態にする。 */
    private fun updateTypeButtons() {
        typeButtons.forEach { (button, type) ->
            button.isSelected = type == selectedType
        }
    }

    private fun setupSizePicker() {
        binding.pickerSize.disabledText = getString(R.string.size_placeholder)
        binding.pickerSize.onSelectedChange = { index ->
            // 先頭行は「サイズを選択」（未選択扱い）
            selectedSize = selectedType?.sizes?.getOrNull(index - 1)
            updateUi()
        }
        updateSizeItems()
    }

    /**
     * 選択中タイプの型番でドラムロールの行を作り直し、先頭（未選択）に戻す。
     * タイプ未選択の間は回せないようにする。
     */
    private fun updateSizeItems() {
        val type = selectedType
        val items = listOf(getString(R.string.size_select_prompt)) +
            (type?.sizes?.map { it.displayName } ?: emptyList())
        binding.pickerSize.apply {
            setItems(items)
            isEnabled = type != null
        }
        selectedSize = null
        updateUi()
    }

    /** 背景の〇×を、案内文のあたり（ドラムロールより下）に合わせる。 */
    private fun setupBackgroundSymbols() {
        val update = {
            val bgLoc = IntArray(2).also { binding.homeBackground.getLocationInWindow(it) }
            val hintLoc = IntArray(2).also { binding.textHint.getLocationInWindow(it) }
            val pickerLoc = IntArray(2).also { binding.pickerSize.getLocationInWindow(it) }
            val hintCenter = hintLoc[1] - bgLoc[1] + binding.textHint.height / 2f
            // 〇の外径（半径58dp＋線幅の半分14dp）がドラムロールに重ならない位置より下に置く
            val minCenter = pickerLoc[1] - bgLoc[1] + binding.pickerSize.height + 76 * resources.displayMetrics.density
            binding.homeBackground.symbolCenterY = max(hintCenter, minCenter)
        }
        binding.textHint.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> update() }
        binding.scrollHome.viewTreeObserver.addOnScrollChangedListener { update() }
    }

    private fun updateUi() {
        val size = selectedSize
        val enabled = size != null
        binding.buttonSampleCapture.isEnabled = enabled
        binding.buttonStartJudge.isEnabled = enabled

        val status = when {
            selectedType == null -> getString(R.string.sample_status_none)
            size == null -> getString(R.string.sample_status_no_size)
            else -> {
                fun mark(type: SampleType) =
                    getString(if (store.has(size.id, type)) R.string.mark_registered else R.string.mark_missing)
                getString(
                    R.string.sample_status_format,
                    mark(SampleType.NO_RING),
                    mark(SampleType.HAS_RING),
                    mark(SampleType.BACKGROUND),
                )
            }
        }
        // 「基準画像：」は太字・濃い色、値は Medium（TextView 側の書体・色）
        binding.textSampleStatus.text = SpannableStringBuilder().apply {
            append(getString(R.string.label_sample_status))
            setSpan(
                FontSpan(ResourcesCompat.getFont(this@MainActivity, R.font.mplus1p_bold), Color.parseColor("#3A4556")),
                0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            append(status)
        }
    }

    /** 書体と文字色を差し替える Span（TypefaceSpan(Typeface) は API 28 以上のため自前で用意）。 */
    private class FontSpan(private val typeface: Typeface?, private val color: Int) : MetricAffectingSpan() {
        override fun updateDrawState(tp: TextPaint) {
            updateMeasureState(tp)
            tp.color = color
        }

        override fun updateMeasureState(tp: TextPaint) {
            if (typeface != null) tp.typeface = typeface
        }
    }
}
