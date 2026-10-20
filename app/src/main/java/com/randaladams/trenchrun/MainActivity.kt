package com.randaladams.trenchrun

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout

class MainActivity : Activity(), GameView.Host {

    private lateinit var gameView: GameView
    private lateinit var ads: Ads
    private val rehide = Runnable { hideSystemUi() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        volumeControlStream = AudioManager.STREAM_MUSIC

        val root = FrameLayout(this)
        gameView = GameView(this, this)
        root.addView(gameView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)

        // If the status/navigation bars ever reappear (edge swipe, notification, ad closing),
        // hide them again shortly after so they don't sit on top of the HUD.
        window.decorView.setOnApplyWindowInsetsListener { v, insets ->
            val barsShown = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                insets.isVisible(WindowInsets.Type.statusBars()) || insets.isVisible(WindowInsets.Type.navigationBars())
            else insets.systemWindowInsetTop > 0
            if (barsShown) { v.removeCallbacks(rehide); v.postDelayed(rehide, 1500) }
            v.onApplyWindowInsets(insets)
        }
        window.decorView.post { hideSystemUi() }

        ads = Ads(this, getSharedPreferences("trenchrun", Context.MODE_PRIVATE))
        ads.init(root)
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        ads.resume()
        gameView.resume()
    }

    override fun onPause() {
        gameView.pause()
        ads.pause()
        super.onPause()
    }

    override fun onDestroy() {
        gameView.release()
        ads.destroy()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!gameView.onBack()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ---- GameView.Host (called on the UI thread)

    override fun setBannerVisible(visible: Boolean) = ads.setBannerVisible(visible)

    override fun beforeRun(totalRuns: Int, then: () -> Unit) = ads.beforeRun(totalRuns, then)

    override fun exitApp() = finishAndRemoveTask()

    override fun showRewarded(then: (Boolean) -> Unit) = ads.showRewarded(then)

    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            }
        }
        // Also set the legacy flags: some phones (notably Samsung) still honor these more reliably.
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }
}
