package com.example.eringchecker.data

/** 圧着工具のサイズ定義。[id] は基準画像の保存フォルダ名にも使う。 */
data class ToolSize(val id: String, val displayName: String)

/** 圧着工具のタイプ。ホーム画面のタイプ選択ボタンに対応し、選択可能なサイズを持つ。 */
enum class ToolType(val sizes: List<ToolSize>) {
    A_TYPE(
        listOf(
            ToolSize(id = "ak_15a", displayName = "AK-15A"),
            ToolSize(id = "ak_17a", displayName = "AK-17A"),
        )
    ),
    MINI_TYPE(
        listOf(
            ToolSize(id = "ak_1ma2", displayName = "AK-1MA2"),
            ToolSize(id = "ak_2ma", displayName = "AK-2MA"),
        )
    ),
    HOZAN(
        listOf(
            ToolSize(id = "p_75", displayName = "P-75"),
            ToolSize(id = "p_738", displayName = "P-738"),
        )
    ),
}

object ToolSizeRepository {

    /** 登録サイズ（全タイプ）。サイズを追加する場合は [ToolType] に追記する。 */
    val sizes: List<ToolSize> = ToolType.entries.flatMap { it.sizes }

    fun findById(id: String?): ToolSize? = sizes.firstOrNull { it.id == id }
}
