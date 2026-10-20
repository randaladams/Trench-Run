package com.randaladams.trenchrun

import android.content.SharedPreferences
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** All game state and rules. Rendering lives in Renderer, input in GameView. */
class Game(private val prefs: SharedPreferences) {

    companion object {
        const val HALF_W = 6f          // trench half width
        const val TOP = 10f            // wall height
        const val X_LIMIT = 4.8f       // how far the ship can go left/right
        const val Y_MIN = 1.0f
        const val Y_MAX = 8.6f
        const val Y_MID = (Y_MIN + Y_MAX) / 2f
        const val Y_RANGE = (Y_MAX - Y_MIN) / 2f
        const val HX = 0.95f           // ship hitbox half extents
        const val HY = 0.4f
        const val HZ = 0.8f
        const val CAM_Z = -7f          // chase camera sits behind the ship
        const val FAR = 220f           // draw distance
        const val SPAWN_Z = 205f
        const val CROSSHAIR_Z = 45f

        // ---- tuning knobs ----
        const val MAX_ENERGY = 100f
        const val HIT_DAMAGE = 40f       // hit with shield down
        const val SHIELDED_DAMAGE = 15f  // hit with shield raised
        const val SHIELD_DRAIN = 14f     // energy per second while shield is raised
        const val REGEN_BASE = 0.5f      // energy per second rebuilt while flying
        const val REGEN_MAX = 1.5f       // regen slowly grows with distance up to this
        const val KILL_ENERGY = 4f       // energy gained for each drone destroyed
        const val PICKUP_ENERGY = 25f
        const val BASE_SPEED = 30f
        const val SPEED_GAIN = 36f       // added speed at full difficulty
        const val DIFFICULTY_DISTANCE = 6000f
        const val BOLT_SPEED = 160f
    }

    enum class State { TITLE, PLAYING, GAME_OVER }
    enum class Sfx { LASER, EXPLODE, HIT, SHIELD_HIT, PICKUP, UPGRADE, GAME_OVER, SHIELD_UP, START }

    /** Sound events raised this frame; GameView drains and plays them. */
    val sfx = ArrayList<Sfx>()
    private fun play(s: Sfx) { if (!sfx.contains(s)) sfx.add(s) }

    var state = State.TITLE
        private set
    var paused = false

    // Input, written from the UI thread.
    @Volatile var inputRoll = 0f    // -1 (left) .. 1 (right)
    @Volatile var inputPitch = 0f   // -1 (down) .. 1 (up)
    @Volatile var firing = false
    @Volatile var shieldHeld = false

    // Player
    var px = 0f; var py = Y_MID
    var vx = 0f; var vy = 0f
    var energy = MAX_ENERGY
    var shieldUp = false
    var weaponLevel = 1
    var invuln = 0f
    var hitFlash = 0f
    var shieldFlash = 0f
    var shake = 0f

    // Progress
    var distance = 0f
    var speed = BASE_SPEED
    var score = 0
    var kills = 0
    var bonus = 0
    var highScore = prefs.getInt("high", 0)
    var newHigh = false
    var time = 0f
    var stateTime = 0f

    var invertPitch = prefs.getBoolean("invertPitch", false)
        set(v) { field = v; prefs.edit().putBoolean("invertPitch", v).apply() }

    var soundOn = prefs.getBoolean("soundOn", true)
        set(v) { field = v; prefs.edit().putBoolean("soundOn", v).apply() }

    var message = ""
    var messageTime = 0f

    val entities = ArrayList<Entity>()
    val bolts = ArrayList<Bolt>()
    val particles = ArrayList<Particle>()

    private var fireCooldown = 0f
    private var nextSpawnAt = 0f
    private val rng = Random(System.nanoTime())
    private var hapticPending = false

    val difficulty get() = min(1f, distance / DIFFICULTY_DISTANCE)

    fun consumeHaptic(): Boolean { val h = hapticPending; hapticPending = false; return h }

    // ------------------------------------------------------------------ flow

    fun start() {
        entities.clear(); bolts.clear(); particles.clear()
        px = 0f; py = Y_MID; vx = 0f; vy = 0f
        distance = 0f; speed = BASE_SPEED
        energy = MAX_ENERGY; weaponLevel = 1; shieldUp = false
        kills = 0; bonus = 0; score = 0
        invuln = 1.5f; hitFlash = 0f; shake = 0f
        fireCooldown = 0f
        nextSpawnAt = 110f
        newHigh = false
        paused = false
        state = State.PLAYING
        stateTime = 0f
        play(Sfx.START)
        showMessage("GOOD LUCK, PILOT", 2f)
    }

    private fun gameOver() {
        state = State.GAME_OVER
        stateTime = 0f
        shieldUp = false
        explode(px, py, 0f, 0xFFFFB040.toInt(), 70, 14f)
        explode(px, py, 0f, 0xFFFFFFFF.toInt(), 25, 8f)
        shake = 1.6f
        hapticPending = true
        play(Sfx.GAME_OVER)
        if (score > highScore) {
            highScore = score
            newHigh = true
            prefs.edit().putInt("high", score).apply()
        }
    }

    fun pause() { if (state == State.PLAYING) paused = true }

    private fun showMessage(m: String, t: Float = 1.6f) { message = m; messageTime = t }

    // ---------------------------------------------------------------- update

    fun update(dt: Float) {
        time += dt
        if (paused) return
        stateTime += dt
        messageTime = max(0f, messageTime - dt)
        hitFlash = max(0f, hitFlash - dt * 2.5f)
        shieldFlash = max(0f, shieldFlash - dt * 3f)
        shake = max(0f, shake - dt * 2.5f)

        when (state) {
            State.TITLE -> updateTitle(dt)
            State.PLAYING -> updatePlaying(dt)
            State.GAME_OVER -> updateGameOver(dt)
        }
        updateParticles(dt)
    }

    private fun updateTitle(dt: Float) {
        // Attract mode: autopilot cruising down an empty trench.
        speed = 34f
        distance += speed * dt
        val nx = sin(time * 0.6f) * 3f
        val ny = Y_MID + sin(time * 0.9f) * 2.5f
        vx = (nx - px) / dt; vy = (ny - py) / dt
        px = nx; py = ny
    }

    private fun updateGameOver(dt: Float) {
        speed = max(0f, speed - 35f * dt)
        moveWorld(speed * dt, dt)
        moveBolts(dt, speed * dt)
    }

    private fun updatePlaying(dt: Float) {
        // ---- ship movement: tilt maps to a target position, ship eases toward it
        val tx = inputRoll.coerceIn(-1f, 1f) * X_LIMIT
        val ty = Y_MID + inputPitch.coerceIn(-1f, 1f) * Y_RANGE
        val k = min(1f, dt * 7f)
        val nx = (px + (tx - px) * k).coerceIn(-X_LIMIT, X_LIMIT)
        val ny = (py + (ty - py) * k).coerceIn(Y_MIN, Y_MAX)
        vx = (nx - px) / dt; vy = (ny - py) / dt
        px = nx; py = ny

        // ---- forward motion
        speed = BASE_SPEED + SPEED_GAIN * difficulty
        val dz = speed * dt
        distance += dz
        moveWorld(dz, dt)
        spawn()

        // ---- shield: raising it drains energy; energy slowly rebuilds as you fly
        val wasUp = shieldUp
        shieldUp = shieldHeld && energy > 0.5f
        if (shieldUp && !wasUp) play(Sfx.SHIELD_UP)
        if (shieldUp) {
            energy = max(0f, energy - SHIELD_DRAIN * dt)
        } else {
            val regen = min(REGEN_MAX, REGEN_BASE + distance / 20000f)
            energy = min(MAX_ENERGY, energy + regen * dt)
        }
        invuln = max(0f, invuln - dt)

        // ---- weapons
        fireCooldown -= dt
        if (firing && fireCooldown <= 0f) fire()
        moveBolts(dt, dz)

        // ---- collisions with the ship
        for (e in entities) {
            if (!e.alive || e.spent) continue
            if (e.x0 < px + HX && e.x1 > px - HX &&
                e.y0 < py + HY && e.y1 > py - HY &&
                e.z0 < HZ && e.z1 > -HZ
            ) {
                if (e.isPickup) collect(e) else {
                    e.spent = true
                    if (e.kind == Kind.DRONE) {
                        e.alive = false
                        explode(e.cx, e.cy, e.z0, 0xFFFF6030.toInt(), 30, 9f)
                        play(Sfx.EXPLODE)
                    }
                    if (state == State.PLAYING) onHit()
                }
                if (state != State.PLAYING) return
            }
        }

        score = (distance * 0.5f).toInt() + kills * 100 + bonus
        entities.removeAll { !it.alive || it.z1 < CAM_Z + 1f }
    }

    private fun moveWorld(dz: Float, dt: Float) {
        for (e in entities) {
            e.z0 -= dz; e.z1 -= dz
            e.hitFlash = max(0f, e.hitFlash - dt * 6f)
            if (e.driftAmp > 0f) {
                e.phase += dt
                val half = (e.x1 - e.x0) * 0.5f
                val c = (e.baseX + sin(e.phase * 1.7f) * e.driftAmp)
                    .coerceIn(-HALF_W + half, HALF_W - half)
                e.x0 = c - half; e.x1 = c + half
            }
            if (e.isPickup) e.phase += dt
        }
        for (p in particles) p.z -= dz
    }

    private fun onHit() {
        if (invuln > 0f) return
        if (energy <= 0.5f) {           // no shield power left: ship is destroyed
            gameOver()
            return
        }
        energy = max(0f, energy - if (shieldUp) SHIELDED_DAMAGE else HIT_DAMAGE)
        invuln = 0.8f
        if (shieldUp) shieldFlash = 1f else hitFlash = 1f
        play(if (shieldUp) Sfx.SHIELD_HIT else Sfx.HIT)
        shake = if (shieldUp) 0.5f else 1.0f
        hapticPending = true
        explode(px, py, 0.6f, if (shieldUp) 0xFF60E8FF.toInt() else 0xFFFFC060.toInt(), 22, 7f)
        if (energy <= 0.5f) showMessage("SHIELD DEPLETED", 2f)
    }

    private fun collect(e: Entity) {
        e.alive = false
        if (e.kind == Kind.PICKUP_SHIELD) {
            energy = min(MAX_ENERGY, energy + PICKUP_ENERGY)
            bonus += 50
            showMessage("SHIELD +${PICKUP_ENERGY.toInt()}")
            play(Sfx.PICKUP)
            explode(e.cx, e.cy, e.z0, 0xFF60E8FF.toInt(), 18, 5f)
        } else {
            play(Sfx.UPGRADE)
            if (weaponLevel < 4) {
                weaponLevel++
                showMessage("LASER UPGRADE: MK ${weaponName()}")
            } else {
                bonus += 250
                showMessage("WEAPONS MAXED +250")
            }
            explode(e.cx, e.cy, e.z0, 0xFF7CFF4A.toInt(), 18, 5f)
        }
    }

    fun weaponName() = when (weaponLevel) { 1 -> "I"; 2 -> "II"; 3 -> "III"; else -> "IV" }

    // ------------------------------------------------------------- weapons

    private fun fire() {
        val rate: Float
        val offsets: FloatArray
        val dmg: Int
        when (weaponLevel) {
            1 -> { rate = 5f; offsets = floatArrayOf(0f); dmg = 1 }
            2 -> { rate = 6f; offsets = floatArrayOf(-0.9f, 0.9f); dmg = 1 }
            3 -> { rate = 8f; offsets = floatArrayOf(-0.9f, 0.9f); dmg = 2 }
            else -> { rate = 9f; offsets = floatArrayOf(-1.1f, 0f, 1.1f); dmg = 2 }
        }
        fireCooldown = 1f / rate
        play(Sfx.LASER)
        for (ox in offsets) {
            // Bolts start at the wing tips and converge on the crosshair point.
            val sx = px + ox; val sy = py - 0.05f; val sz = 1f
            val dx = px - sx; val dy = py - sy; val dzz = CROSSHAIR_Z - sz
            val len = sqrt(dx * dx + dy * dy + dzz * dzz)
            bolts.add(Bolt(sx, sy, sz, dx / len * BOLT_SPEED, dy / len * BOLT_SPEED, dzz / len * BOLT_SPEED, dmg))
        }
    }

    private fun moveBolts(dt: Float, worldDz: Float) {
        for (b in bolts) {
            if (!b.alive) continue
            b.prevZ = b.z
            b.x += b.vx * dt; b.y += b.vy * dt; b.z += b.vz * dt
            if (b.z > FAR) { b.alive = false; continue }
            for (e in entities) {
                if (!e.alive || e.isPickup) continue
                // swept test: bolt travelled prevZ..z while the object came toward us by worldDz
                if (b.z >= e.z0 && b.prevZ <= e.z1 + worldDz &&
                    b.x > e.x0 - 0.15f && b.x < e.x1 + 0.15f &&
                    b.y > e.y0 - 0.15f && b.y < e.y1 + 0.15f
                ) {
                    b.alive = false
                    val hz = max(e.z0, 0.5f)
                    if (e.destructible) {
                        e.hp -= b.damage
                        e.hitFlash = 1f
                        if (e.hp <= 0) {
                            e.alive = false
                            kills++
                            energy = min(MAX_ENERGY, energy + KILL_ENERGY)
                            play(Sfx.EXPLODE)
                            explode(e.cx, e.cy, hz, 0xFFFF7030.toInt(), 34, 10f)
                            explode(e.cx, e.cy, hz, 0xFFFFE080.toInt(), 12, 5f)
                        } else {
                            explode(b.x, b.y, hz, 0xFFFFD0A0.toInt(), 6, 4f)
                        }
                    } else {
                        explode(b.x, b.y, hz, 0xFFFF8060.toInt(), 5, 3f)
                    }
                    break
                }
            }
        }
        bolts.removeAll { !it.alive }
    }

    // ----------------------------------------------------------- particles

    private fun explode(x: Float, y: Float, z: Float, color: Int, count: Int, power: Float) {
        repeat(count) {
            val vx = (rng.nextFloat() - 0.5f) * 2f * power
            val vy = (rng.nextFloat() - 0.5f) * 2f * power
            val vz = (rng.nextFloat() - 0.5f) * 2f * power
            particles.add(Particle(x, y, z, vx, vy, vz, 0.4f + rng.nextFloat() * 0.6f, color, 0.08f + rng.nextFloat() * 0.18f))
        }
    }

    private fun updateParticles(dt: Float) {
        for (p in particles) {
            p.x += p.vx * dt; p.y += p.vy * dt; p.z += p.vz * dt
            p.life -= dt
        }
        particles.removeAll { it.life <= 0f || it.z < CAM_Z + 1f }
    }

    // ------------------------------------------------------------ spawning

    private fun rand(a: Float, b: Float) = a + rng.nextFloat() * (b - a)

    private fun addBox(kind: Kind, x0: Float, x1: Float, y0: Float, y1: Float, z: Float, depth: Float): Entity {
        val e = Entity(kind, x0, x1, y0, y1, z, z + depth)
        entities.add(e)
        return e
    }

    private fun spawn() {
        while (nextSpawnAt - distance < SPAWN_Z) {
            val z = nextSpawnAt - distance
            val depth = spawnPattern(z)
            // gap scales with speed so reaction time stays fair, shrinking as difficulty rises
            val reaction = 0.8f - 0.4f * difficulty + rng.nextFloat() * 0.25f
            val gap = max(18f, speed * reaction)
            maybeSpawnPickup(z + depth + gap * 0.5f)
            nextSpawnAt += depth + gap
        }
    }

    private fun spawnPattern(z: Float): Float {
        val d = difficulty
        val weights = floatArrayOf(
            3f,            // 0 bridge
            2f,            // 1 raised floor
            1.5f,          // 2 ceiling
            2.5f,          // 3 side block
            1.5f,          // 4 pillar
            0.8f + 2f * d, // 5 slot (floor + ceiling, thread the gap)
            2.5f + d       // 6 drones
        )
        var r = rng.nextFloat() * weights.sum()
        var pick = 0
        for (i in weights.indices) { r -= weights[i]; if (r <= 0f) { pick = i; break } }

        return when (pick) {
            0 -> {
                val y0 = rand(2.2f, 6.8f)
                addBox(Kind.BRIDGE, -HALF_W, HALF_W, y0, y0 + 1.0f, z, 1.6f)
                if (d > 0.3f && rng.nextFloat() < 0.4f) {
                    val y1 = if (y0 > 4.5f) rand(2.2f, 3.4f) else rand(5.6f, 6.8f)
                    addBox(Kind.BRIDGE, -HALF_W, HALF_W, y1, y1 + 1.0f, z + 9f, 1.6f)
                    11f
                } else 2f
            }
            1 -> {
                val h = rand(1.6f, 3.8f)
                val len = rand(5f, 10f)
                addBox(Kind.RAISED_FLOOR, -HALF_W, HALF_W, 0f, h, z, len)
                len
            }
            2 -> {
                val y0 = rand(5.6f, 7.6f)
                val len = rand(3f, 7f)
                addBox(Kind.CEILING, -HALF_W, HALF_W, y0, TOP, z, len)
                len
            }
            3 -> {
                val reach = rand(-2.6f, -0.8f)   // never past the centerline
                if (rng.nextBoolean()) addBox(Kind.SIDE_BLOCK, -HALF_W, reach, 0f, TOP, z, 3f)
                else addBox(Kind.SIDE_BLOCK, -reach, HALF_W, 0f, TOP, z, 3f)
                3f
            }
            4 -> {
                val off = rand(-1.5f, 1.5f)
                addBox(Kind.PILLAR, off - 1.0f, off + 1.0f, 0f, TOP, z, 2.5f)
                3f
            }
            5 -> {
                val h = rand(1.4f, 3f)
                val gapH = 4.8f - 0.6f * d
                addBox(Kind.RAISED_FLOOR, -HALF_W, HALF_W, 0f, h, z, 4f)
                addBox(Kind.CEILING, -HALF_W, HALF_W, h + gapH, TOP, z, 4f)
                4f
            }
            else -> {
                val n = 1 + rng.nextInt(1 + (d * 3f).toInt())
                for (i in 0 until n) {
                    val x = rand(-4f, 4f)
                    val y = rand(2f, 7.5f)
                    val e = addBox(Kind.DRONE, x - 0.6f, x + 0.6f, y - 0.6f, y + 0.6f, z + i * 6f, 1.2f)
                    e.hp = if (rng.nextFloat() < d) 2 else 1
                    e.baseX = x
                    e.driftAmp = if (rng.nextFloat() < 0.3f + d * 0.5f) rand(0.8f, 2.2f) else 0f
                    e.phase = rand(0f, 6f)
                }
                n * 6f
            }
        }
    }

    private fun maybeSpawnPickup(z: Float) {
        val shieldChance = 0.10f + if (energy < 40f) 0.12f else 0f
        val weaponChance = if (weaponLevel < 4) 0.06f else 0.02f
        val r = rng.nextFloat()
        val kind = when {
            r < shieldChance -> Kind.PICKUP_SHIELD
            r < shieldChance + weaponChance -> Kind.PICKUP_WEAPON
            else -> return
        }
        val x = rand(-4f, 4f)
        val y = rand(2f, 7f)
        addBox(kind, x - 0.6f, x + 0.6f, y - 0.6f, y + 0.6f, z, 1.2f)
    }

    /** True if a drone sits under the crosshair (used to tint it red). */
    fun targetLocked(): Boolean {
        for (e in entities) {
            if (e.alive && e.destructible && e.z0 > 2f &&
                px > e.x0 - 0.3f && px < e.x1 + 0.3f && py > e.y0 - 0.3f && py < e.y1 + 0.3f
            ) return true
        }
        return false
    }
}
