package com.example.eringchecker.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

/** 基準画像の種類。[fileKey] は保存ファイル名。 */
enum class SampleType(val fileKey: String) {
    NO_RING("no-ring"),
    HAS_RING("has-ring"),

    /** 工具を映していない背景のみの画像。工具検知（背景差分）に使う。 */
    BACKGROUND("background"),
}

/**
 * サイズごとの基準画像（リングなし／リングあり／背景のみ）をアプリ内部ストレージに保存する。
 * 保存先: files/samples_roi/<sizeId>/<no-ring|has-ring|background>.png
 *
 * 保存するのはガイド枠内の画像のみ。以前の画面全体の基準画像（files/samples）とは
 * 写っている範囲が違い比較できないため、保存先を分けて再撮影させる。
 */
class SampleStore(context: Context) {

    private val rootDir = File(context.applicationContext.filesDir, "samples_roi")

    private fun file(sizeId: String, type: SampleType): File =
        File(File(rootDir, sizeId), "${type.fileKey}.png")

    fun has(sizeId: String, type: SampleType): Boolean = file(sizeId, type).isFile

    /** 判定に必要な基準画像（リングなし・リングあり・背景のみ）がすべてそろっているか。 */
    fun hasAll(sizeId: String): Boolean = SampleType.entries.all { has(sizeId, it) }

    /** 一時ファイルに書いてからリネームし、書き込み途中のファイルを読まないようにする。 */
    fun save(sizeId: String, type: SampleType, bitmap: Bitmap): Boolean {
        val target = file(sizeId, type)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        val ok = FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (!ok) {
            tmp.delete()
            return false
        }
        if (target.exists()) target.delete()
        return tmp.renameTo(target)
    }

    fun load(sizeId: String, type: SampleType): Bitmap? {
        val f = file(sizeId, type)
        return if (f.isFile) BitmapFactory.decodeFile(f.absolutePath) else null
    }
}
