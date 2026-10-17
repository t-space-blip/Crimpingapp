package com.example.eringchecker.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.example.eringchecker.R
import com.example.eringchecker.image.Verdict
import java.io.Closeable

/** 効果音の再生速度（SoundPool の上限は 2.0）。速くするほど音の高さも上がる。 */
private const val SOUND_RATE = 2.0f

/**
 * 〇・×の効果音（res/raw の sound_ok / sound_ng）を鳴らす。
 * 反応を速くするため、あらかじめメモリに読み込んでおける SoundPool を使う。
 */
class VerdictSound(context: Context) : Closeable {

    private val pool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val okSound = pool.load(context, R.raw.sound_ok, 1)
    private val ngSound = pool.load(context, R.raw.sound_ng, 1)
    private var lastStream = 0

    /** OK なら〇の音、NG なら×の音を 1 回鳴らす。それ以外の判定では鳴らさない。 */
    fun play(verdict: Verdict) {
        val sound = when (verdict) {
            Verdict.HAS_RING -> okSound
            Verdict.NO_RING -> ngSound
            else -> return
        }
        // 前の音が鳴っている途中でも、新しい結果の音を優先する
        if (lastStream != 0) pool.stop(lastStream)
        lastStream = pool.play(sound, 1f, 1f, 1, 0, SOUND_RATE)
    }

    override fun close() = pool.release()
}
