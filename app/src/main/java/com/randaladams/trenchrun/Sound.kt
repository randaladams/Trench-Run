package com.randaladams.trenchrun

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Procedurally synthesized sound effects (no audio assets needed).
 * Each effect is rendered to a small WAV in the cache dir at startup and played with SoundPool.
 */
class Sound(context: Context) {

    companion object {
        private const val RATE = 22050
        private const val TWO_PI = (2 * PI).toFloat()
    }

    private val pool = SoundPool.Builder()
        .setMaxStreams(12)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()

    private val ids = java.util.concurrent.ConcurrentHashMap<Game.Sfx, Int>()
    @Volatile private var engineId = 0
    @Volatile private var shieldLoopId = 0
    @Volatile private var ttsReady = false
    private var tts: TextToSpeech? = null
    private val loaded = HashSet<Int>()

    private var engineStream = 0
    private var shieldStream = 0

    @Volatile var enabled = true

    init {
        // Spoken warnings use the phone's built-in text-to-speech voice (silently skipped if unavailable).
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                val t = tts
                if (status == TextToSpeech.SUCCESS && t != null) {
                    val res = t.setLanguage(Locale.US)
                    t.setPitch(0.85f)
                    t.setSpeechRate(1.1f)
                    t.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    ttsReady = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
                }
            }
        } catch (_: Exception) { tts = null }
        pool.setOnLoadCompleteListener { _, sampleId, status -> if (status == 0) synchronized(loaded) { loaded.add(sampleId) } }
        val dir = File(context.cacheDir, "sfx").apply { mkdirs() }
        Thread {
            try {
                fun load(name: String, data: FloatArray): Int {
                    val f = File(dir, "$name.wav")
                    writeWav(f, data)
                    return pool.load(f.path, 1)
                }
                ids[Game.Sfx.LASER] = load("laser", laser())
                ids[Game.Sfx.EXPLODE] = load("explode", explosion(0.9f, 0.9f))
                ids[Game.Sfx.HIT] = load("hit", hit())
                ids[Game.Sfx.SHIELD_HIT] = load("shield_hit", shieldHit())
                ids[Game.Sfx.PICKUP] = load("pickup", arpeggio(floatArrayOf(660f, 880f, 1320f), 0.09f, false))
                ids[Game.Sfx.UPGRADE] = load("upgrade", arpeggio(floatArrayOf(523f, 659f, 784f, 1047f, 1319f), 0.08f, true))
                ids[Game.Sfx.GAME_OVER] = load("game_over", gameOver())
                ids[Game.Sfx.SHIELD_UP] = load("shield_up", shieldUp())
                ids[Game.Sfx.START] = load("start", start())
                ids[Game.Sfx.ENEMY_FIRE] = load("enemy_fire", enemyFire())
                ids[Game.Sfx.TURRET_CHARGE] = load("turret_charge", turretCharge())
                ids[Game.Sfx.SLOW] = load("slow", slowMo())
                ids[Game.Sfx.ALARM] = load("alarm", alarmBeep())
                engineId = load("engine", engineLoop())
                shieldLoopId = load("shield_loop", shieldLoop())
            } catch (_: Exception) {
                // Sound is optional; the game keeps running silently if anything fails.
            }
        }.start()
    }

    private fun ready(id: Int) = id != 0 && synchronized(loaded) { loaded.contains(id) }

    fun play(s: Game.Sfx, pan: Float = 0f) {
        if (!enabled) return
        if (s == Game.Sfx.VOICE_CRITICAL || s == Game.Sfx.VOICE_DEPLETED) {
            speak(if (s == Game.Sfx.VOICE_CRITICAL) "Warning. Shield critical." else "Shields down!")
            return
        }
        val id = ids[s] ?: return
        if (!ready(id)) return
        val vol = when (s) {
            Game.Sfx.LASER -> 0.35f
            Game.Sfx.SHIELD_UP -> 0.5f
            Game.Sfx.TURRET_CHARGE -> 0.55f
            else -> 0.9f
        }
        val p = pan.coerceIn(-1f, 1f)
        val lv = vol * (if (p > 0f) 1f - p * 0.75f else 1f)
        val rv = vol * (if (p < 0f) 1f + p * 0.75f else 1f)
        val rate = if (s == Game.Sfx.LASER || s == Game.Sfx.EXPLODE) 0.92f + Random.nextFloat() * 0.16f else 1f
        pool.play(id, lv, rv, 1, 0, rate)
    }

    /** Called every frame to keep the looping engine and shield hums in the right state. */
    private fun speak(text: String) {
        val t = tts ?: return
        if (!ttsReady) return
        // short delay so the voice lands right after the warning beep
        Thread {
            try { Thread.sleep(450) } catch (_: InterruptedException) {}
            if (enabled) t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "warn")
        }.start()
    }

    fun updateLoops(engineOn: Boolean, speedFactor: Float, shieldOn: Boolean) {

        val wantEngine = enabled && engineOn
        if (wantEngine && engineStream == 0 && ready(engineId)) {
            engineStream = pool.play(engineId, 0.32f, 0.32f, 0, -1, 1f)
        } else if (!wantEngine && engineStream != 0) {
            pool.stop(engineStream); engineStream = 0
        }
        if (engineStream != 0) pool.setRate(engineStream, 0.85f + 0.5f * speedFactor.coerceIn(0f, 1f))

        val wantShield = enabled && shieldOn
        if (wantShield && shieldStream == 0 && ready(shieldLoopId)) {
            shieldStream = pool.play(shieldLoopId, 0.4f, 0.4f, 1, -1, 1f)
        } else if (!wantShield && shieldStream != 0) {
            pool.stop(shieldStream); shieldStream = 0
        }
    }

    fun stopLoops() {
        if (engineStream != 0) { pool.stop(engineStream); engineStream = 0 }
        if (shieldStream != 0) { pool.stop(shieldStream); shieldStream = 0 }
    }

    fun release() { stopLoops(); pool.release(); tts?.shutdown() }

    // ------------------------------------------------------------- synthesis

    private fun buf(seconds: Float) = FloatArray((seconds * RATE).toInt())

    private fun laser(): FloatArray {
        val out = buf(0.17f)
        var ph = 0f
        for (i in out.indices) {
            val t = i / RATE.toFloat()
            val p = t / 0.17f
            val freq = 1500f * exp(-p * 2.2f) + 220f
            ph += TWO_PI * freq / RATE
            val sq = if (sin(ph) >= 0f) 1f else -1f
            out[i] = (0.55f * sq + 0.45f * sin(ph * 0.5f)) * exp(-p * 3.5f)
        }
        return out
    }

    private fun noiseLp(out: FloatArray, seconds: Float, cutoffStart: Float, cutoffEnd: Float, decay: Float, seed: Int, gain: Float) {
        val r = Random(seed)
        var lp = 0f
        var lp2 = 0f
        val n = (seconds * RATE).toInt().coerceAtMost(out.size)
        for (i in 0 until n) {
            val p = i / n.toFloat()
            val cut = cutoffStart + (cutoffEnd - cutoffStart) * p
            val a = (TWO_PI * cut / RATE).coerceIn(0f, 1f)
            val white = r.nextFloat() * 2f - 1f
            lp += a * (white - lp)
            lp2 += a * (lp - lp2)
            out[i] += lp2 * 3f * exp(-p * decay) * gain
        }
    }

    private fun thump(out: FloatArray, seconds: Float, f0: Float, f1: Float, gain: Float) {
        val n = (seconds * RATE).toInt().coerceAtMost(out.size)
        var ph = 0f
        for (i in 0 until n) {
            val p = i / n.toFloat()
            ph += TWO_PI * (f0 + (f1 - f0) * p) / RATE
            out[i] += sin(ph) * exp(-p * 4f) * gain
        }
    }

    private fun explosion(seconds: Float, gain: Float): FloatArray {
        val out = buf(seconds)
        noiseLp(out, seconds, 3500f, 200f, 4f, 11, gain)
        thump(out, seconds * 0.6f, 120f, 40f, gain * 0.8f)
        return out
    }

    private fun hit(): FloatArray {
        val out = buf(0.5f)
        noiseLp(out, 0.5f, 5000f, 400f, 6f, 3, 1f)
        thump(out, 0.4f, 160f, 50f, 1f)
        // metallic crunch
        for (i in out.indices) {
            val t = i / RATE.toFloat()
            out[i] += (sin(TWO_PI * 230f * t) * sin(TWO_PI * 317f * t)) * exp(-t * 14f) * 0.6f
        }
        return out
    }

    private fun shieldHit(): FloatArray {
        val out = buf(0.45f)
        val partials = floatArrayOf(620f, 935f, 1410f, 2120f)
        for (i in out.indices) {
            val t = i / RATE.toFloat()
            var v = 0f
            for ((k, f) in partials.withIndex()) v += sin(TWO_PI * f * (1f - t * 0.3f) * t) * exp(-t * (6f + k * 2f)) / (k + 1)
            out[i] = v * 0.9f
        }
        noiseLp(out, 0.15f, 6000f, 2000f, 8f, 5, 0.4f)
        return out
    }

    private fun arpeggio(notes: FloatArray, step: Float, square: Boolean): FloatArray {
        val out = buf(step * notes.size + 0.25f)
        for ((n, f) in notes.withIndex()) {
            val start = (n * step * RATE).toInt()
            val len = ((0.25f + step) * RATE).toInt()
            for (j in 0 until len) {
                val i = start + j
                if (i >= out.size) break
                val t = j / RATE.toFloat()
                val s = sin(TWO_PI * f * t)
                val tone = if (square) (if (s >= 0f) 0.6f else -0.6f) * 0.5f + s * 0.5f else s
                out[i] += tone * exp(-t * 10f) * 0.6f
            }
        }
        return out
    }

    private fun gameOver(): FloatArray {
        val out = buf(1.8f)
        noiseLp(out, 1.8f, 4000f, 120f, 3f, 21, 1.1f)
        thump(out, 1.2f, 90f, 30f, 1f)
        var ph = 0f
        for (i in out.indices) {
            val p = i / out.size.toFloat()
            ph += TWO_PI * (330f * exp(-p * 2.5f) + 40f) / RATE
            out[i] += (if (sin(ph) >= 0f) 0.25f else -0.25f) * (1f - p) * 0.8f
        }
        return out
    }

    private fun shieldUp(): FloatArray {
        val out = buf(0.3f)
        var ph = 0f
        for (i in out.indices) {
            val p = i / out.size.toFloat()
            ph += TWO_PI * (300f + 700f * p) / RATE
            val trem = 0.7f + 0.3f * sin(TWO_PI * 30f * p)
            out[i] = sin(ph) * trem * (1f - p) * 0.8f
        }
        return out
    }

    private fun start(): FloatArray {
        val out = buf(0.9f)
        val r = Random(9)
        var lp = 0f
        var ph = 0f
        for (i in out.indices) {
            val p = i / out.size.toFloat()
            val cut = 300f + 3000f * p
            lp += (TWO_PI * cut / RATE).coerceIn(0f, 1f) * ((r.nextFloat() * 2f - 1f) - lp)
            ph += TWO_PI * (110f + 330f * p) / RATE
            val env = (p * 4f).coerceAtMost(1f) * (1f - p)
            out[i] = (lp * 1.8f + sin(ph) * 0.4f) * env
        }
        return out
    }

    private fun enemyFire(): FloatArray {
        val out = buf(0.35f)
        var ph = 0f
        for (i in out.indices) {
            val p = i / out.size.toFloat()
            ph += TWO_PI * (900f * exp(-p * 3f) + 120f) / RATE
            val saw = (ph / TWO_PI) % 1f * 2f - 1f
            out[i] = (saw * 0.6f + sin(ph * 2f) * 0.3f) * exp(-p * 4f)
        }
        noiseLp(out, 0.12f, 6000f, 1500f, 6f, 17, 0.5f)
        return out
    }

    /** Rising whine that warns a turret is about to fire. */
    private fun turretCharge(): FloatArray {
        val out = buf(0.6f)
        var ph = 0f
        for (i in out.indices) {
            val p = i / out.size.toFloat()
            ph += TWO_PI * (300f + 1100f * p * p) / RATE
            val trem = 0.6f + 0.4f * sin(TWO_PI * 18f * p * 0.6f)
            out[i] = sin(ph) * trem * (p * 3f).coerceAtMost(1f) * (1f - p * 0.3f) * 0.7f
        }
        return out
    }

    /** Descending "time slowing down" sweep. */
    private fun slowMo(): FloatArray {
        val out = buf(0.9f)
        var ph = 0f
        var ph2 = 0f
        for (i in out.indices) {
            val p = i / out.size.toFloat()
            ph += TWO_PI * (880f * exp(-p * 2f)) / RATE
            ph2 += TWO_PI * (1320f * exp(-p * 2f)) / RATE
            val wob = 0.7f + 0.3f * sin(TWO_PI * (12f - 9f * p) * p)
            out[i] = (sin(ph) * 0.6f + sin(ph2) * 0.3f) * wob * (1f - p)
        }
        return out
    }

    /** Two-tone warning beeps, 1 second, silent at the edges so it loops cleanly. */
    private fun alarmBeep(): FloatArray {
        val out = buf(1f)
        fun beep(start: Float, freq: Float) {
            val a = (start * RATE).toInt()
            val n = (0.18f * RATE).toInt()
            for (j in 0 until n) {
                val t = j / RATE.toFloat()
                val env = (j / 200f).coerceAtMost(1f) * ((n - j) / 200f).coerceAtMost(1f)
                val sq = if (sin(TWO_PI * freq * t) >= 0f) 1f else -1f
                out[a + j] += (sq * 0.5f + sin(TWO_PI * freq * t) * 0.5f) * env
            }
        }
        beep(0.05f, 880f)
        beep(0.30f, 660f)
        return out
    }

    /** Exactly 1 second, built only from whole-number frequencies so it loops seamlessly. */
    private fun engineLoop(): FloatArray {
        val out = buf(1f)
        val r = Random(4)
        val hum = floatArrayOf(47f, 55f, 94f, 110f)
        val hiss = IntArray(40) { 180 + r.nextInt(900) }
        val hissPh = FloatArray(40) { r.nextFloat() * TWO_PI }
        for (i in out.indices) {
            val t = i / RATE.toFloat()
            var v = 0f
            for ((k, f) in hum.withIndex()) v += sin(TWO_PI * f * t) / (k + 1.5f)
            var h = 0f
            for (k in hiss.indices) h += sin(TWO_PI * hiss[k] * t + hissPh[k])
            v += h * 0.025f
            out[i] = v * (0.85f + 0.15f * sin(TWO_PI * 6f * t))
        }
        return out
    }

    private fun shieldLoop(): FloatArray {
        val out = buf(1f)
        for (i in out.indices) {
            val t = i / RATE.toFloat()
            val v = sin(TWO_PI * 220f * t) * 0.5f + sin(TWO_PI * 331f * t) * 0.3f + sin(TWO_PI * 662f * t) * 0.12f
            out[i] = v * (0.65f + 0.35f * sin(TWO_PI * 8f * t)) * 0.6f
        }
        return out
    }

    private fun writeWav(file: File, data: FloatArray) {
        var peak = 0.001f
        for (v in data) if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v)
        val norm = 0.95f / peak
        val pcm = ByteBuffer.allocate(data.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in data) pcm.putShort((v * norm * 32767f).toInt().coerceIn(-32768, 32767).toShort())
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()); header.putInt(36 + data.size * 2)
        header.put("WAVE".toByteArray()); header.put("fmt ".toByteArray())
        header.putInt(16); header.putShort(1); header.putShort(1)
        header.putInt(RATE); header.putInt(RATE * 2); header.putShort(2); header.putShort(16)
        header.put("data".toByteArray()); header.putInt(data.size * 2)
        FileOutputStream(file).use { it.write(header.array()); it.write(pcm.array()) }
    }
}
