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
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable, SensorEventListener {

    companion object {
        private const val RAD2DEG = 57.29578f
        private const val ROLL_RANGE = 24f    // degrees of tilt for full left/right
        private const val PITCH_RANGE = 20f   // degrees of tilt for full up/down
        private const val DEADZONE = 1.5f
    }

    private val game = Game(context.getSharedPreferences("trenchrun", Context.MODE_PRIVATE))
    private val renderer = Renderer(resources.displayMetrics.density)

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
            game.shieldHeld = false
        }
        stopLoop()
    }

    /** Back button: pause a running game, otherwise let the activity close. */
    fun onBack(): Boolean {
        synchronized(game) {
            if (game.state == Game.State.PLAYING && !game.paused) { game.pause(); return true }
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

            synchronized(game) { game.update(dt) }

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

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val action = e.actionMasked
        if (action == MotionEvent.ACTION_DOWN) handleTap(e.x, e.y)

        // Work out which halves of the screen are currently held (multi-touch).
        var left = false
        var right = false
        if (action != MotionEvent.ACTION_CANCEL && action != MotionEvent.ACTION_UP) {
            val lifted = if (action == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
            for (i in 0 until e.pointerCount) {
                if (i == lifted) continue
                if (e.getX(i) < width / 2f) left = true else right = true
            }
        }
        game.firing = left
        game.shieldHeld = right
        return true
    }

    private fun handleTap(x: Float, y: Float) {
        synchronized(game) {
            when (game.state) {
                Game.State.TITLE -> {
                    if (renderer.toggleRect.contains(x, y)) {
                        game.invertPitch = !game.invertPitch
                    } else {
                        calibrate()
                        game.start()
                    }
                }
                Game.State.PLAYING -> if (game.paused) { calibrate(); game.paused = false }
                Game.State.GAME_OVER -> if (game.stateTime > 1.2f) { calibrate(); game.start() }
            }
        }
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
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_Z -> { game.shieldHeld = true; return true }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> { handleTap(-1f, -1f); return true }
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
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_Z -> { game.shieldHeld = false; return true }
            else -> return super.onKeyUp(keyCode, event)
        }
        game.inputRoll = keyX; game.inputPitch = keyY
        return true
    }
}
