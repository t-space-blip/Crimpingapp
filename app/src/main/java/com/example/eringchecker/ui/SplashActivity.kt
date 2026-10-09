package com.example.eringchecker.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.example.eringchecker.R
import com.example.eringchecker.ui.widget.LogoSplashView

/** 起動ロゴ画面（縦画面固定）：約3秒のロゴアニメーションのあと、ホーム画面へフェードで切り替える。 */
class SplashActivity : AppCompatActivity() {

    /** アニメーションは終わったが、画面が前面にないため切り替えを保留している。 */
    private var pendingHome = false
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // 黒地を画面全体に敷き、ステータスバー・ナビゲーションバーも黒（アイコンは明色）にする
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(Color.BLACK),
        )
        super.onCreate(savedInstanceState)
        setContentView(LogoSplashView(this).apply {
            onFinished = {
                if (resumed) goHome() else pendingHome = true
            }
        })
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (pendingHome) goHome()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    /** ホーム画面を開いてこの画面を閉じる（戻るボタンでロゴ画面に戻らないようにする）。 */
    private fun goHome() {
        pendingHome = false
        startActivity(Intent(this, MainActivity::class.java))
        // 直後に開くホーム画面への切り替えに効かせるため、overrideActivityTransition（自分自身の開閉用）ではなくこちらを使う
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.splash_fade_in, R.anim.splash_hold)
        finish()
    }
}
