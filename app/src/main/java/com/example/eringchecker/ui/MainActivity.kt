package com.example.eringchecker.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.eringchecker.R
import com.example.eringchecker.data.SampleStore
import com.example.eringchecker.data.SampleType
import com.example.eringchecker.data.ToolSize
import com.example.eringchecker.data.ToolType
import com.example.eringchecker.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors

/** ホーム画面（縦画面固定）：工具タイプ・サイズ選択と、サンプル撮影／判定への遷移。 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: SampleStore

    /** タイプ選択ボタンと対応する工具タイプ。 */
    private lateinit var typeButtons: Map<MaterialButton, ToolType>

    /** 選択中の工具タイプ。起動直後は未選択（null）。 */
    private var selectedType: ToolType? = null

    /** タイプ未選択の場合は null。 */
    private var selectedSize: ToolSize? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = SampleStore(this)

        setupTypeButtons()
        setupSizeSpinner()

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

    /** タイプ選択ボタン（単一選択）。選択に応じて型番プルダウンの選択肢を切り替える。 */
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

    /** 選択中のボタンのみ塗りつぶしでハイライトし、それ以外はアウトライン表示に戻す。 */
    private fun updateTypeButtons() {
        val primary = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary)
        val onPrimary = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnPrimary)
        typeButtons.forEach { (button, type) ->
            val selected = type == selectedType
            button.backgroundTintList = ColorStateList.valueOf(if (selected) primary else Color.TRANSPARENT)
            button.setTextColor(if (selected) onPrimary else primary)
            button.strokeColor = ColorStateList.valueOf(primary)
        }
    }

    private fun setupSizeSpinner() {
        binding.spinnerSize.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedSize = selectedType?.sizes?.getOrNull(position)
                updateUi()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedSize = null
                updateUi()
            }
        }
        updateSizeItems()
    }

    /**
     * 選択中タイプの型番でプルダウンの選択肢を作り直す。
     * タイプ未選択の間はプレースホルダのみ表示して操作不可にする。
     */
    private fun updateSizeItems() {
        val type = selectedType
        val items = type?.sizes?.map { it.displayName }
            ?: listOf(getString(R.string.size_placeholder))
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerSize.apply {
            this.adapter = adapter
            setSelection(0, false)
            isEnabled = type != null
            alpha = if (type != null) 1f else 0.4f
        }
        selectedSize = type?.sizes?.firstOrNull()
        updateUi()
    }

    private fun updateUi() {
        val size = selectedSize
        val enabled = size != null
        binding.buttonSampleCapture.isEnabled = enabled
        binding.buttonStartJudge.isEnabled = enabled

        binding.textSampleStatus.text = if (size == null) {
            getString(R.string.sample_status_none)
        } else {
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
}
