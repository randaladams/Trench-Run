package com.randaladams.trenchrun

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sign

/**
 * Hosts the game loop thread, reads tilt from the gravity sensor and handles touch.
 *
 * Tilt mapping (landscape):
 *   roll  - tilting the right edge down moves the ship right
 *   pitch - tilting the top edge away from you (forward) dives, toward you climbs.
 *           The neutral angle is captured when you tap to launch, so hold the phone
 *           however is comfortable. "Invert" on the title screen flips it.
 */
@SuppressLint("ViewConstructor")
class GameView(context: Context, private val host: Host) : SurfaceView(context), SurfaceHolder.Callback, Runnable, SensorEventListener {

    /** Things the game asks of its activity (all called on the UI thread). */
    interface Host {
        fun setBannerVisible(visible: Boolean)
        fun beforeRun(totalRuns: Int, then: () -> Unit)
        fun exitApp()
        fun showRewarded(then: (Boolean) -> Unit)
    }

    private var bannerShown: Boolean? = null

    companion object {
        private const val RAD2DEG = 57.29578f
        private const val ROLL_RANGE = 24f    // degrees of tilt for full left/right
        private const val PITCH_RANGE = 20f   // degrees of tilt for full up/down
        private const val DEADZONE = 1.5f
    }

    private val game = Game(context.getSharedPreferences("trenchrun", Context.MODE_PRIVATE))
    private val renderer = Renderer(resources.displayMetrics.density, "v${BuildConfig.VERSION_NAME}  build ${BuildConfig.VERSION_CODE - 100}")
    private val sound = Sound(context).also { it.enabled = game.soundOn }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val tiltSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val rawAccel = tiltSensor?.type == Sensor.TYPE_ACCELEROMETER
    private val grav = floatArrayOf(0f, 0f, 9.81f)
    private var pitchDeg = 40f
    private var neutralPitch = 40f

    // keyboard / emulator fallback
    private var keyMode = false
    private var keyX = 0f
    private var keyY = 0f

    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var surfaceReady = false
    @Volatile private var resumed = false

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
        keepScreenOn = true
    }

    // ------------------------------------------------------------ lifecycle

    fun resume() {
        resumed = true
        tiltSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        startLoop()
    }

    fun pause() {
        resumed = false
        sensorManager.unregisterListener(this)
        synchronized(game) {
            game.pause()
            game.firing = false
            game.tractorHeld = false
        }
        stopLoop()
        sound.stopLoops()
    }

    fun release() = sound.release()

    /** Back button: pause a running game, otherwise let the activity close. */
    fun onBack(): Boolean {
        synchronized(game) {
            if (game.state == Game.State.PLAYING && !game.paused) { game.pause(); return true }
            if (game.state == Game.State.SCORES) {
                if (game.confirmClear) game.confirmClear = false else game.backToTitle()
                return true
            }
        }
        return false
    }

    override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true; startLoop() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false; stopLoop() }

    private fun startLoop() {
        if (running || !surfaceReady || !resumed) return
        running = true
        thread = Thread(this, "game-loop").also { it.start() }
    }

    private fun stopLoop() {
        running = false
        try { thread?.join(500) } catch (_: InterruptedException) {}
        thread = null
    }

    override fun run() {
        var last = System.nanoTime()
        while (running) {
            val now = System.nanoTime()
            val dt = ((now - last) / 1_000_000_000f).coerceIn(0.0005f, 1f / 30f)
            last = now

            synchronized(game) {
                game.update(dt)
                for (i in game.sfx.indices) sound.play(game.sfx[i], game.sfxPan[i])
                game.sfx.clear(); game.sfxPan.clear()
                val flying = game.state == Game.State.PLAYING && !game.paused
                sound.updateLoops(
                    flying || game.state == Game.State.TITLE,
                    (game.speed - Game.BASE_SPEED) / (Game.MAX_SPEED - Game.BASE_SPEED),
                    flying && game.tractorOn,
                    if (flying) max(game.scrape, abs(game.wallScrape)) else 0f,
                    if (game.scrape >= abs(game.wallScrape)) 0f else game.wallScrape
                )
            }

            val canvas = try { holder.lockHardwareCanvas() } catch (_: Exception) { null }
            if (canvas == null) {
                try { Thread.sleep(8) } catch (_: InterruptedException) {}
                continue
            }
            try {
                synchronized(game) { renderer.draw(canvas, game) }
            } finally {
                try { holder.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
            }

            // banner ad: free edition, menu screens only (never during a run)
            val wantBanner = !game.pro && game.state != Game.State.PLAYING
            if (wantBanner != bannerShown) {
                bannerShown = wantBanner
                post { host.setBannerVisible(wantBanner) }
            }

            if (game.consumeHaptic()) post { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
        }
    }

    // --------------------------------------------------------------- sensors

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        if (rawAccel) {
            for (i in 0..2) grav[i] = grav[i] * 0.8f + v[i] * 0.2f
        } else {
            grav[0] = v[0]; grav[1] = v[1]; grav[2] = v[2]
        }

        // Convert from the device's natural (portrait) axes to screen axes.
        val ax = grav[0]; val ay = grav[1]; val az = grav[2]
        val sxv: Float
        val syv: Float
        when (display?.rotation ?: Surface.ROTATION_0) {
            Surface.ROTATION_90 -> { sxv = -ay; syv = ax }
            Surface.ROTATION_180 -> { sxv = -ax; syv = -ay }
            Surface.ROTATION_270 -> { sxv = ay; syv = -ax }
            else -> { sxv = ax; syv = ay }
        }

        val rollDeg = atan2(-sxv, hypot(syv, az)) * RAD2DEG   // + = right edge down
        pitchDeg = atan2(az, syv) * RAD2DEG                   // 0 = upright, 90 = flat

        // On the menus, "level" slowly follows however the phone is being held (about a 3 s drift),
        // so the ship can be flown around without calibrating. START re-centers it exactly.
        if (game.state == Game.State.TITLE || game.state == Game.State.SCORES) {
            neutralPitch += (pitchDeg - neutralPitch) * 0.006f
        }

        if (keyMode) return
        var pitchInput = -deadzone(pitchDeg - neutralPitch) / PITCH_RANGE
        if (game.invertPitch) pitchInput = -pitchInput
        game.inputRoll = (deadzone(rollDeg) / ROLL_RANGE).coerceIn(-1f, 1f)
        game.inputPitch = pitchInput.coerceIn(-1f, 1f)
    }

    private fun deadzone(d: Float) = if (abs(d) < DEADZONE) 0f else d - sign(d) * DEADZONE

    private fun calibrate() {
        neutralPitch = pitchDeg
    }

    // ----------------------------------------------------------------- touch

    /** Pointer ids that started on an on-screen button; they never count as fire/shield touches. */
    private val buttonPointers = HashSet<Int>()

    // Hidden tester switch: press and hold the SCOREBOARD tab for 2 seconds to flip Free/Pro.
    private val editionHold = Runnable {
        synchronized(game) { if (game.state == Game.State.SCORES) game.toggleEdition() }
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }
    private val editionHoldHint = Runnable {
        synchronized(game) { if (game.state == Game.State.SCORES) game.showMessage("Keep holding to switch edition...", 1.4f) }
    }

    private fun cancelEditionHold() { removeCallbacks(editionHold); removeCallbacks(editionHoldHint) }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val action = e.actionMasked
        if (BuildConfig.DEBUG) {
            when (action) {
                MotionEvent.ACTION_DOWN -> if (game.state == Game.State.SCORES && !game.confirmClear &&
                    renderer.tabScoresRect.contains(e.x, e.y)) {
                    postDelayed(editionHoldHint, 700)
                    postDelayed(editionHold, 2000)
                }
                MotionEvent.ACTION_MOVE -> if (!renderer.tabScoresRect.contains(e.x, e.y)) cancelEditionHold()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> cancelEditionHold()
            }
        }
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            val i = e.actionIndex
            val x = e.getX(i); val y = e.getY(i)
            if (handleButton(x, y)) buttonPointers.add(e.getPointerId(i))
            else if (action == MotionEvent.ACTION_DOWN) handleTap(x, y)
        }

        // Work out which halves of the screen are currently held (multi-touch).
        var left = false
        var right = false
        if (action != MotionEvent.ACTION_CANCEL && action != MotionEvent.ACTION_UP) {
            val lifted = if (action == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
            for (i in 0 until e.pointerCount) {
                if (i == lifted || buttonPointers.contains(e.getPointerId(i))) continue
                if (e.getX(i) < width / 2f) left = true else right = true
            }
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) buttonPointers.clear()
        else if (action == MotionEvent.ACTION_POINTER_UP) buttonPointers.remove(e.getPointerId(e.actionIndex))

        val paused = game.paused
        game.firing = left && !paused
        game.tractorHeld = right && !paused
        return true
    }

    /** In-game pause and sound buttons. Returns true if the touch landed on one. */
    private fun handleButton(x: Float, y: Float): Boolean {
        synchronized(game) {
            if (game.state != Game.State.PLAYING) return false
            if (game.paused && renderer.endRect.contains(x, y)) {
                game.endRun()
                return true
            }
            if (renderer.pauseRect.contains(x, y)) {
                if (game.paused) { calibrate(); game.paused = false } else game.pause()
                return true
            }
            if (renderer.soundBtnRect.contains(x, y)) {
                toggleSound()
                return true
            }
        }
        return false
    }

    private fun toggleSound() {
        game.soundOn = !game.soundOn
        sound.enabled = game.soundOn
        if (!game.soundOn) sound.stopLoops()
    }

    private var versionTaps = 0
    private var versionTapAt = 0L

    private fun handleTap(x: Float, y: Float) {
        synchronized(game) {
            when (game.state) {
                Game.State.TITLE -> when {
                    renderer.startRect.contains(x, y) -> beginRun(zen = game.zenSelected && game.pro)
                    renderer.tutorialRect.contains(x, y) -> { calibrate(); game.start(asTutorial = true) }
                    renderer.zenRect.contains(x, y) -> {
                        if (game.pro) game.zenSelected = !game.zenSelected
                        else game.showMessage("ZEN MODE IS PART OF TRENCH RUN PRO", 2.5f)
                    }
                    renderer.scoresRect.contains(x, y) -> game.showScores()
                    renderer.toggleRect.contains(x, y) -> { game.invertPitch = !game.invertPitch }
                    renderer.soundRect.contains(x, y) -> toggleSound()
                    renderer.exitRect.contains(x, y) -> host.exitApp()
                    BuildConfig.DEBUG && renderer.editionRect.contains(x, y) -> {
                        // hidden tester switch: tap the FREE/PRO EDITION label 5 times
                        val now = System.currentTimeMillis()
                        if (now - versionTapAt > 1500) versionTaps = 0
                        versionTapAt = now
                        versionTaps++
                        if (versionTaps >= 5) { versionTaps = 0; game.toggleEdition() }
                        else if (versionTaps >= 2) game.showMessage("${5 - versionTaps} more taps to switch edition", 1f)
                    }
                }
                Game.State.PLAYING -> when {
                    game.paused -> { calibrate(); game.paused = false }
                    game.tutComplete -> game.endRun()
                }
                Game.State.SCORES -> when {
                    game.confirmClear -> {
                        if (renderer.confirmYesRect.contains(x, y)) game.clearScores()
                        else if (renderer.confirmNoRect.contains(x, y)) { game.confirmClear = false }
                    }
                    renderer.tabScoresRect.contains(x, y) -> { game.scoresTab = 0 }
                    renderer.tabBadgesRect.contains(x, y) -> { game.scoresTab = 1 }
                    renderer.backRect.contains(x, y) -> game.backToTitle()
                    renderer.clearRect.contains(x, y) -> { game.confirmClear = true }
                }
                Game.State.GAME_OVER -> if (game.offerActive) {
                    if (game.offerPending) Unit
                    else if (renderer.continueRect.contains(x, y)) acceptContinue()
                    else if (renderer.declineRect.contains(x, y)) game.declineContinue()
                } else if (game.stateTime > 1.2f) {
                    if (renderer.againRect.contains(x, y)) beginRun(zen = false)
                    else if (renderer.menuRect.contains(x, y)) game.endRun()
                }
            }
            Unit
        }
    }

    /** One-time continue: Pro continues right away, Free watches a rewarded ad first. */
    private fun acceptContinue() {
        val revive = { synchronized(game) { calibrate(); game.revive() } }
        if (game.pro) { revive(); return }
        game.offerPending = true
        host.showRewarded { watched ->
            synchronized(game) {
                game.offerPending = false
                if (watched) { calibrate(); game.revive() }
                else game.showMessage("WATCH THE WHOLE AD TO CONTINUE", 2f)
            }
        }
    }

    /** Starts a scored run, showing a full-screen ad first if one is due (free edition only). */
    private fun beginRun(zen: Boolean) {
        val go = { synchronized(game) { calibrate(); game.start(asZen = zen) } }
        if (game.pro || zen) go() else host.beforeRun(game.book.runs) { go() }
    }

    // -------------------------------------------------------------- keyboard
    // Handy for testing in the emulator: arrows steer, Space fires, Shift/Z shields, Enter starts.

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> keyX = -1f
            KeyEvent.KEYCODE_DPAD_RIGHT -> keyX = 1f
            KeyEvent.KEYCODE_DPAD_UP -> keyY = 1f
            KeyEvent.KEYCODE_DPAD_DOWN -> keyY = -1f
            KeyEvent.KEYCODE_SPACE -> { game.firing = true; return true }
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_Z -> { game.tractorHeld = true; return true }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (game.state == Game.State.TITLE || game.state == Game.State.GAME_OVER) beginRun(zen = false) else handleTap(-1f, -1f)
                return true
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        keyMode = true
        game.inputRoll = keyX; game.inputPitch = keyY
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> keyX = 0f
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> keyY = 0f
            KeyEvent.KEYCODE_SPACE -> { game.firing = false; return true }
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_Z -> { game.tractorHeld = false; return true }
            else -> return super.onKeyUp(keyCode, event)
        }
        game.inputRoll = keyX; game.inputPitch = keyY
        return true
    }
}
