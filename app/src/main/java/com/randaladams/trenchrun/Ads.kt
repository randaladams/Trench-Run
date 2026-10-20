package com.randaladams.trenchrun

import android.app.Activity
import android.content.SharedPreferences
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * AdMob for the free edition:
 *  - a banner at the bottom of menu screens only (title, scoreboard, game over), never during play
 *  - a full-screen ad between runs, at most every [EVERY_RUNS] runs and [MIN_GAP_MS] apart,
 *    never in a player's first [FIRST_AFTER_RUNS] runs.
 * Pro players never see ads. All calls must come from the UI thread.
 */
class Ads(private val activity: Activity, private val prefs: SharedPreferences) {

    companion object {
        const val EVERY_RUNS = 3
        const val FIRST_AFTER_RUNS = 3
        const val MIN_GAP_MS = 120_000L
    }

    private var started = false
    private var banner: AdView? = null
    private var interstitial: InterstitialAd? = null
    private var bannerWanted = false
    private var lastAdAt = 0L
    private var container: FrameLayout? = null

    /** Asks for privacy consent where the law requires it, then starts the ad SDK. */
    fun init(container: FrameLayout) {
        this.container = container
        try {
            val info = UserMessagingPlatform.getConsentInformation(activity)
            info.requestConsentInfoUpdate(
                activity, ConsentRequestParameters.Builder().build(),
                {
                    UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                        if (info.canRequestAds()) start()
                    }
                },
                { if (info.canRequestAds()) start() }
            )
            if (info.canRequestAds()) start()
        } catch (_: Exception) {
            start()
        }
    }

    private fun start() {
        if (started) return
        started = true
        try {
            MobileAds.initialize(activity) {}
            val view = AdView(activity).apply {
                setAdSize(AdSize.BANNER)
                adUnitId = BuildConfig.AD_BANNER_ID
                visibility = View.GONE
            }
            container?.addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                )
            )
            view.loadAd(AdRequest.Builder().build())
            banner = view
            loadInterstitial()
            applyBanner()
        } catch (_: Exception) {
            // ads are optional; the game runs fine without them
        }
    }

    fun setBannerVisible(visible: Boolean) {
        bannerWanted = visible
        applyBanner()
    }

    private fun applyBanner() {
        banner?.visibility = if (bannerWanted) View.VISIBLE else View.GONE
    }

    private fun loadInterstitial() {
        InterstitialAd.load(activity, BuildConfig.AD_INTERSTITIAL_ID, AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) { interstitial = ad }
                override fun onAdFailedToLoad(error: LoadAdError) { interstitial = null }
            })
    }

    /**
     * Called right before a new run starts. Shows a full-screen ad first if one is due,
     * then calls [then]. If no ad is due or loaded, [then] runs immediately.
     */
    fun beforeRun(totalRuns: Int, then: () -> Unit) {
        val ad = interstitial
        val sinceLast = totalRuns - prefs.getInt("adRunsAtLastAd", 0)
        val now = System.currentTimeMillis()
        val due = totalRuns >= FIRST_AFTER_RUNS && sinceLast >= EVERY_RUNS && now - lastAdAt > MIN_GAP_MS
        if (!due || ad == null) { then(); return }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { interstitial = null; loadInterstitial(); then() }
            override fun onAdFailedToShowFullScreenContent(error: AdError) { interstitial = null; loadInterstitial(); then() }
        }
        lastAdAt = now
        prefs.edit().putInt("adRunsAtLastAd", totalRuns).apply()
        ad.show(activity)
    }

    fun pause() { banner?.pause() }
    fun resume() { banner?.resume() }
    fun destroy() { banner?.destroy() }
}
