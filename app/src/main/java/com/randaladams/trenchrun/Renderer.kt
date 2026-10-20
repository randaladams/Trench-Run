package com.randaladams.trenchrun

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Draws the game with a simple perspective projection onto an Android Canvas.
 * No engine or 3D API needed: every surface is a projected quad, painted far to near.
 */
class Renderer(private val density: Float) {

    companion object {
        private const val SEG = 6f
        private const val SPACE = 0xFF05070F.toInt()
        private const val FOG = 0xFF0B1020.toInt()
        private const val FLOOR = 0xFF3A4150.toInt()
        private const val WALL_L = 0xFF545C6C.toInt()
        private const val WALL_R = 0xFF434A59.toInt()
        private const val EDGE = 0xFF8A93A6.toInt()
        private const val AMBER = 0xFFFFB347.toInt()
        private const val LAMP = 0xFFFFD890.toInt()
        private const val BRIDGE = 0xFF7C5A4E.toInt()
        private const val BLOCK = 0xFF66707F.toInt()
        private const val DRONE = 0xFF8C2A2A.toInt()
        private const val SHIELD = 0xFF40E0FF.toInt()
        private const val WEAPON = 0xFFFFA020.toInt()
        private const val HUD = 0xFF9FF5C8.toInt()
        private const val DANGER = 0xFFFF4A4A.toInt()
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()
    private val rect = RectF()
    private val sorted = ArrayList<Entity>()
    private val byDepth = Comparator<Entity> { a, b -> b.z0.compareTo(a.z0) }
    private val shakeRnd = java.util.Random()

    /** Hit area of the pitch-direction toggle on the title screen (read by GameView). */
    val toggleRect = RectF()

    private var w = 0f; private var h = 0f
    private var f = 0f; private var cx = 0f; private var cy = 0f
    private var camX = 0f; private var camY = 0f
    private val camZ = Game.CAM_Z
    private val near = Game.CAM_Z + 1.2f
    private var glow: RadialGradient? = null

    private val starX = FloatArray(110)
    private val starY = FloatArray(110)
    private val starB = FloatArray(110)

    init {
        val r = java.util.Random(7)
        for (i in starX.indices) {
            starX[i] = r.nextFloat(); starY[i] = r.nextFloat(); starB[i] = 0.25f + 0.75f * r.nextFloat()
        }
    }

    private fun dp(v: Float) = v * density

    private fun setup(width: Int, height: Int) {
        w = width.toFloat(); h = height.toFloat()
        f = h * 0.95f
        cx = w / 2f
        cy = h * 0.42f
        glow = RadialGradient(cx, cy, w * 0.35f, 0x55FFB060, 0x00000000, Shader.TileMode.CLAMP)
    }

    // ---------------------------------------------------------- projection

    private fun sx(x: Float, z: Float) = cx + (x - camX) * f / (z - camZ)
    private fun sy(y: Float, z: Float) = cy - (y - camY) * f / (z - camZ)
    private fun scaleAt(z: Float) = f / (z - camZ)

    private fun fogT(z: Float): Float {
        val t = ((z - camZ) / (Game.FAR - camZ)).coerceIn(0f, 1f)
        return t.pow(1.15f) * 0.93f
    }

    private fun shade(c: Int, k: Float): Int {
        val r = min(255, ((c shr 16 and 0xFF) * k).toInt())
        val g = min(255, ((c shr 8 and 0xFF) * k).toInt())
        val b = min(255, ((c and 0xFF) * k).toInt())
        return (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
    }

    private fun mix(c: Int, to: Int, t: Float): Int {
        val r = ((c shr 16 and 0xFF) + ((to shr 16 and 0xFF) - (c shr 16 and 0xFF)) * t).toInt()
        val g = ((c shr 8 and 0xFF) + ((to shr 8 and 0xFF) - (c shr 8 and 0xFF)) * t).toInt()
        val b = ((c and 0xFF) + ((to and 0xFF) - (c and 0xFF)) * t).toInt()
        return (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
    }

    private fun fog(c: Int, t: Float) = mix(c, FOG, t)

    private fun withAlpha(c: Int, a: Float) = (c and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)

    private fun quad(
        x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float,
        x3: Float, y3: Float, z3: Float, x4: Float, y4: Float, z4: Float,
        color: Int, c: Canvas
    ) {
        path.reset()
        path.moveTo(sx(x1, z1), sy(y1, z1))
        path.lineTo(sx(x2, z2), sy(y2, z2))
        path.lineTo(sx(x3, z3), sy(y3, z3))
        path.lineTo(sx(x4, z4), sy(y4, z4))
        path.close()
        fill.color = color
        c.drawPath(path, fill)
    }

    private fun hash(k: Int): Int {
        var x = k * 374761393 + 668265263
        x = (x xor (x ushr 13)) * 1274126177
        return (x xor (x ushr 16)) and 0x7FFFFFFF
    }

    // ---------------------------------------------------------------- frame

    fun draw(c: Canvas, g: Game) {
        if (c.width.toFloat() != w || c.height.toFloat() != h) setup(c.width, c.height)

        camX = g.px * 0.7f
        camY = g.py * 0.75f + 2.2f
        if (g.shake > 0f) {
            camX += (shakeRnd.nextFloat() - 0.5f) * g.shake * 0.6f
            camY += (shakeRnd.nextFloat() - 0.5f) * g.shake * 0.6f
        }

        c.drawColor(SPACE)
        drawStars(c)
        fill.shader = glow
        c.drawRect(0f, 0f, w, h, fill)
        fill.shader = null

        drawTrench(c, g)

        sorted.clear()
        for (e in g.entities) if (e.alive && e.z0 < Game.FAR && e.z1 > near) sorted.add(e)
        sorted.sortWith(byDepth)
        for (e in sorted) if (e.z1 >= 0f) drawEntity(c, e, g)
        for (b in g.bolts) drawBolt(c, b)
        if (g.state != Game.State.GAME_OVER) drawShip(c, g)
        for (e in sorted) if (e.z1 < 0f) drawEntity(c, e, g)
        drawParticles(c, g)

        when (g.state) {
            Game.State.TITLE -> drawTitle(c, g)
            Game.State.PLAYING -> {
                drawCrosshair(c, g)
                drawHud(c, g)
                if (g.paused) drawPaused(c)
            }
            Game.State.GAME_OVER -> { drawHud(c, g); drawGameOver(c, g) }
        }
    }

    private fun drawStars(c: Canvas) {
        val ox = -camX * dp(3f)
        for (i in starX.indices) {
            var x = (starX[i] * w + ox) % w
            if (x < 0f) x += w
            fill.color = withAlpha(0xFFFFFFFF.toInt(), starB[i])
            c.drawCircle(x, starY[i] * h * 0.75f, dp(0.6f + starB[i]), fill)
        }
    }

    private fun drawTrench(c: Canvas, g: Game) {
        val hw = Game.HALF_W
        val top = Game.TOP
        val kNear = floor((g.distance + near) / SEG).toInt()
        val kFar = floor((g.distance + Game.FAR) / SEG).toInt()

        for (k in kFar downTo kNear) {
            val zA = k * SEG - g.distance
            val za = max(zA, near)
            val zb = min(zA + SEG, Game.FAR)
            if (zb <= za) continue
            val t = fogT(za)
            val hs = hash(k)
            val v = (if ((k and 1) == 0) 1f else 0.9f) * (0.93f + (hs % 15) / 100f)

            // floor and walls
            quad(-hw, 0f, za, hw, 0f, za, hw, 0f, zb, -hw, 0f, zb, fog(shade(FLOOR, v), t), c)
            quad(-hw, 0f, za, -hw, top, za, -hw, top, zb, -hw, 0f, zb, fog(shade(WALL_L, v), t), c)
            quad(hw, 0f, za, hw, top, za, hw, top, zb, hw, 0f, zb, fog(shade(WALL_R, v), t), c)

            // recessed wall panels
            if (hs % 3 == 0) panel(c, -hw, 1.5f, 4.5f, zA + 1f, zA + SEG - 1f, shade(WALL_L, 0.68f), t)
            if ((hs shr 3) % 3 == 0) panel(c, hw, 5f, 8.5f, zA + 1f, zA + SEG - 1f, shade(WALL_R, 0.68f), t)
            if ((hs shr 6) % 4 == 0) panel(c, -hw, 6f, 8.8f, zA + 0.5f, zA + 3f, shade(WALL_L, 0.8f), t)

            // small lamps on the walls (they cut through the fog a bit)
            if (hs % 5 == 0) panel(c, -hw, 7f, 7.5f, zA + 2f, zA + 3f, LAMP, t * 0.6f)
            if ((hs shr 4) % 5 == 0) panel(c, hw, 2.6f, 3.1f, zA + 2f, zA + 3f, LAMP, t * 0.6f)

            // amber guide lights on the floor give a strong sense of speed
            if (k % 2 == 0) {
                val z1 = max(zA + 1f, near); val z2 = zA + 3f
                if (z2 > z1) {
                    val col = fog(shade(AMBER, 0.75f), t * 0.8f)
                    quad(-3.2f, 0f, z1, -2.8f, 0f, z1, -2.8f, 0f, z2, -3.2f, 0f, z2, col, c)
                    quad(2.8f, 0f, z1, 3.2f, 0f, z1, 3.2f, 0f, z2, 2.8f, 0f, z2, col, c)
                }
            }

            // structures on the surface above the walls
            if (hs % 11 == 0) box(c, -hw - 3f, -hw - 0.6f, top, top + rand01(hs) * 2.5f + 0.8f, zA + 1f, zA + 4f, BLOCK, false)
            if ((hs shr 5) % 11 == 0) box(c, hw + 0.6f, hw + 3f, top, top + rand01(hs shr 2) * 2.5f + 0.8f, zA + 1f, zA + 4f, BLOCK, false)

            // bright wall top edges
            line.color = fog(EDGE, t)
            line.strokeWidth = max(dp(1f), 0.06f * scaleAt(za))
            c.drawLine(sx(-hw, za), sy(top, za), sx(-hw, zb), sy(top, zb), line)
            c.drawLine(sx(hw, za), sy(top, za), sx(hw, zb), sy(top, zb), line)
        }
    }

    private fun rand01(h: Int) = (h % 1000) / 1000f

    /** A rectangle lying flat on a wall at x = wx. */
    private fun panel(c: Canvas, wx: Float, y0: Float, y1: Float, z0In: Float, z1: Float, color: Int, t: Float) {
        val z0 = max(z0In, near)
        if (z1 <= z0) return
        quad(wx, y0, z0, wx, y1, z0, wx, y1, z1, wx, y0, z1, fog(color, t), c)
    }

    /** Axis-aligned box. Only faces that point toward the camera are drawn. */
    private fun box(
        c: Canvas, x0: Float, x1: Float, y0: Float, y1: Float, z0In: Float, z1: Float,
        color: Int, outline: Boolean = true, flash: Float = 0f
    ) {
        val z0 = max(z0In, near)
        if (z1 <= z0 || z0 > Game.FAR) return
        val t = fogT(z0)
        val base = if (flash > 0f) mix(color, 0xFFFFFFFF.toInt(), flash * 0.8f) else color
        if (camY > y1) quad(x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, fog(shade(base, 1.3f), t), c)
        if (camY < y0 && outline) quad(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, fog(shade(base, 0.55f), t), c)
        if (camX < x0) quad(x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1, fog(shade(base, 0.78f), t), c)
        if (camX > x1) quad(x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, fog(shade(base, 0.78f), t), c)
        // the front face is always a screen-aligned rectangle
        rect.set(sx(x0, z0), sy(y1, z0), sx(x1, z0), sy(y0, z0))
        fill.color = fog(base, t)
        c.drawRect(rect, fill)
        if (outline) {
            line.color = fog(shade(base, 1.6f), t)
            line.strokeWidth = dp(1.2f)
            c.drawRect(rect, line)
        }
    }

    // -------------------------------------------------------------- entities

    private fun drawEntity(c: Canvas, e: Entity, g: Game) {
        when (e.kind) {
            Kind.PICKUP_SHIELD, Kind.PICKUP_WEAPON -> drawPickup(c, e)
            Kind.DRONE -> drawDrone(c, e, g)
            Kind.BRIDGE -> {
                box(c, e.x0, e.x1, e.y0, e.y1, e.z0, e.z1, BRIDGE)
                hazardStripe(c, e)
            }
            Kind.CEILING -> {
                box(c, e.x0, e.x1, e.y0, e.y1, e.z0, e.z1, BRIDGE)
                hazardStripe(c, e)
            }
            else -> box(c, e.x0, e.x1, e.y0, e.y1, e.z0, e.z1, BLOCK)
        }
    }

    private fun hazardStripe(c: Canvas, e: Entity) {
        val z = max(e.z0, near)
        val t = fogT(z)
        val yb = e.y0 + 0.2f
        val yt = e.y0 + 0.45f
        val n = 12
        val step = (e.x1 - e.x0) / n
        for (i in 0 until n) {
            if (i % 2 == 1) continue
            val xa = e.x0 + i * step
            rect.set(sx(xa, z), sy(yt, z), sx(xa + step, z), sy(yb, z))
            fill.color = fog(AMBER, t)
            c.drawRect(rect, fill)
        }
    }

    private fun drawDrone(c: Canvas, e: Entity, g: Game) {
        box(c, e.x0, e.x1, e.y0, e.y1, e.z0, e.z1, DRONE, true, e.hitFlash)
        val z = max(e.z0, near)
        val t = fogT(z)
        val s = scaleAt(z)
        val px = sx(e.cx, z); val py = sy(e.cy, z)
        val pulse = 0.75f + 0.25f * sin(g.time * 10f + e.phase)
        fill.color = withAlpha(fog(0xFFFF5030.toInt(), t), 0.35f)
        c.drawCircle(px, py, 0.7f * s, fill)
        fill.color = fog(mix(0xFFFF5030.toInt(), 0xFFFFE0A0.toInt(), pulse - 0.5f), t)
        c.drawCircle(px, py, 0.28f * s * pulse, fill)
        // side vanes
        line.color = fog(0xFFB0B6C2.toInt(), t)
        line.strokeWidth = max(dp(1f), 0.08f * s)
        c.drawLine(sx(e.x0 - 0.5f, z), py, sx(e.x0, z), py, line)
        c.drawLine(sx(e.x1, z), py, sx(e.x1 + 0.5f, z), py, line)
    }

    private fun drawPickup(c: Canvas, e: Entity) {
        val z = max((e.z0 + e.z1) * 0.5f, near)
        val t = fogT(z) * 0.7f
        val s = scaleAt(z)
        val px = sx(e.cx, z); val py = sy(e.cy, z)
        val color = if (e.kind == Kind.PICKUP_SHIELD) SHIELD else WEAPON
        val r = 0.6f * s
        val spin = abs(cos(e.phase * 3f)) * 0.8f + 0.2f

        fill.color = withAlpha(fog(color, t), 0.22f + 0.1f * sin(e.phase * 8f))
        c.drawCircle(px, py, r * 1.9f, fill)

        path.reset()
        path.moveTo(px, py - r * 1.25f)
        path.lineTo(px + r * spin, py)
        path.lineTo(px, py + r * 1.25f)
        path.lineTo(px - r * spin, py)
        path.close()
        fill.color = fog(color, t)
        c.drawPath(path, fill)
        line.color = fog(0xFFFFFFFF.toInt(), t)
        line.strokeWidth = max(dp(1f), 0.07f * s)
        c.drawPath(path, line)

        // symbol: a plus for shield energy, a chevron for weapons
        line.strokeWidth = max(dp(1.5f), 0.12f * s)
        if (e.kind == Kind.PICKUP_SHIELD) {
            c.drawLine(px - r * 0.35f * spin, py, px + r * 0.35f * spin, py, line)
            c.drawLine(px, py - r * 0.4f, px, py + r * 0.4f, line)
        } else {
            c.drawLine(px - r * 0.35f * spin, py + r * 0.2f, px, py - r * 0.25f, line)
            c.drawLine(px, py - r * 0.25f, px + r * 0.35f * spin, py + r * 0.2f, line)
        }
    }

    private fun drawBolt(c: Canvas, b: Bolt) {
        val zh = b.z
        if (zh <= near + 0.2f) return
        val zt = max(b.z - 3f, near + 0.1f)
        val back = (b.z - zt) / b.vz
        val tx = b.x - b.vx * back; val ty = b.y - b.vy * back
        val x1 = sx(b.x, zh); val y1 = sy(b.y, zh)
        val x2 = sx(tx, zt); val y2 = sy(ty, zt)
        val wdt = max(dp(2f), 0.22f * scaleAt(zh))
        line.color = 0x66FF2A2A
        line.strokeWidth = wdt * 3f
        c.drawLine(x1, y1, x2, y2, line)
        line.color = 0xFFFF5A4A.toInt()
        line.strokeWidth = wdt
        c.drawLine(x1, y1, x2, y2, line)
        line.color = 0xFFFFE0D8.toInt()
        line.strokeWidth = wdt * 0.4f
        c.drawLine(x1, y1, x2, y2, line)
    }

    private fun drawParticles(c: Canvas, g: Game) {
        for (p in g.particles) {
            if (p.z <= near) continue
            val s = scaleAt(p.z)
            fill.color = withAlpha(p.color, p.life / p.maxLife)
            c.drawCircle(sx(p.x, p.z), sy(p.y, p.z), max(dp(1f), p.size * s), fill)
        }
    }

    // ----------------------------------------------------------------- ship

    private fun drawShip(c: Canvas, g: Game) {
        if (g.state == Game.State.PLAYING && g.invuln > 0f && g.invuln < 1.2f &&
            ((g.time * 14f).toInt() % 2 == 0)) return

        val s = scaleAt(0f)
        val x = sx(g.px, 0f)
        val y = sy(g.py, 0f)
        val bank = (g.vx * 2.4f).coerceIn(-38f, 38f)
        val pitch = (g.vy * 0.015f).coerceIn(-0.12f, 0.12f)

        c.save()
        c.translate(x, y)
        c.rotate(bank)
        c.scale(s, s)

        // engine glow behind everything
        val flick = 0.85f + 0.15f * sin(g.time * 60f)
        fill.color = 0x55FF8A2A
        c.drawCircle(-0.2f, 0.06f, 0.26f * flick, fill)
        c.drawCircle(0.2f, 0.06f, 0.26f * flick, fill)

        // wings (seen from behind, y is flipped for screen space)
        path.reset()
        path.moveTo(-1.35f, -0.02f + pitch)
        path.lineTo(-0.35f, -0.17f)
        path.lineTo(0.35f, -0.17f)
        path.lineTo(1.35f, -0.02f + pitch)
        path.lineTo(1.35f, 0.07f + pitch)
        path.lineTo(0.35f, 0.12f)
        path.lineTo(-0.35f, 0.12f)
        path.lineTo(-1.35f, 0.07f + pitch)
        path.close()
        fill.color = 0xFFAEB4C0.toInt()
        c.drawPath(path, fill)

        // wing stripes
        fill.color = 0xFFE0662E.toInt()
        rect.set(-1.0f, -0.08f, -0.7f, 0.0f); c.drawRect(rect, fill)
        rect.set(0.7f, -0.08f, 1.0f, 0.0f); c.drawRect(rect, fill)

        // wingtip fins
        fill.color = 0xFF7E8695.toInt()
        rect.set(-1.42f, -0.32f + pitch, -1.30f, 0.12f + pitch); c.drawRect(rect, fill)
        rect.set(1.30f, -0.32f + pitch, 1.42f, 0.12f + pitch); c.drawRect(rect, fill)

        // fuselage
        path.reset()
        path.moveTo(0f, -0.45f)
        path.lineTo(0.38f, -0.05f)
        path.lineTo(0.26f, 0.3f)
        path.lineTo(-0.26f, 0.3f)
        path.lineTo(-0.38f, -0.05f)
        path.close()
        fill.color = 0xFFD8DDE6.toInt()
        c.drawPath(path, fill)
        fill.color = 0xFF2E3644.toInt()   // canopy
        rect.set(-0.12f, -0.33f, 0.12f, -0.17f); c.drawRect(rect, fill)

        // engines
        fill.color = 0xFF3A404C.toInt()
        c.drawCircle(-0.2f, 0.08f, 0.13f, fill)
        c.drawCircle(0.2f, 0.08f, 0.13f, fill)
        fill.color = mix(0xFFFF9A3C.toInt(), 0xFFFFF0C0.toInt(), flick - 0.7f)
        c.drawCircle(-0.2f, 0.08f, 0.09f * flick, fill)
        c.drawCircle(0.2f, 0.08f, 0.09f * flick, fill)

        // shield bubble
        if (g.shieldUp || g.shieldFlash > 0f) {
            val a = if (g.shieldUp) 1f else g.shieldFlash
            val shimmer = 0.85f + 0.15f * sin(g.time * 25f)
            rect.set(-1.75f, -0.95f, 1.75f, 0.85f)
            fill.color = withAlpha(SHIELD, (0.16f + 0.3f * g.shieldFlash) * a * shimmer)
            c.drawOval(rect, fill)
            line.color = withAlpha(SHIELD, (0.7f + 0.3f * g.shieldFlash) * a)
            line.strokeWidth = 0.05f
            c.drawOval(rect, line)
        }
        c.restore()
    }

    // ------------------------------------------------------------------ HUD

    private fun drawCrosshair(c: Canvas, g: Game) {
        val z = Game.CROSSHAIR_Z
        val x = sx(g.px, z)
        val y = sy(g.py, z)
        val col = if (g.targetLocked()) DANGER else HUD
        val r = dp(16f)
        line.color = withAlpha(col, 0.85f)
        line.strokeWidth = dp(1.6f)
        c.drawCircle(x, y, r, line)
        c.drawLine(x - r * 1.7f, y, x - r * 0.6f, y, line)
        c.drawLine(x + r * 0.6f, y, x + r * 1.7f, y, line)
        c.drawLine(x, y - r * 1.7f, x, y - r * 0.6f, line)
        c.drawLine(x, y + r * 0.6f, x, y + r * 1.7f, line)
        fill.color = col
        c.drawCircle(x, y, dp(2f), fill)
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, align: Paint.Align = Paint.Align.CENTER) {
        txt.textSize = size
        txt.color = color
        txt.textAlign = align
        c.drawText(s, x, y, txt)
    }

    private fun drawHud(c: Canvas, g: Game) {
        val pad = dp(18f)
        val ts = max(dp(12f), h / 30f)

        // shield energy bar
        val barW = w * 0.28f
        val barH = dp(12f)
        val by = pad + ts
        val frac = g.energy / Game.MAX_ENERGY
        val empty = g.energy <= 0.5f
        val barColor = when {
            empty -> DANGER
            frac < 0.25f -> 0xFFFF9A3C.toInt()
            else -> SHIELD
        }
        text(c, if (g.shieldUp) "SHIELD  [ACTIVE]" else "SHIELD", pad, pad + ts * 0.8f, ts * 0.8f, barColor, Paint.Align.LEFT)
        rect.set(pad, by, pad + barW, by + barH)
        fill.color = 0x55FFFFFF
        c.drawRect(rect, fill)
        rect.set(pad, by, pad + barW * frac, by + barH)
        fill.color = barColor
        c.drawRect(rect, fill)
        line.color = 0xAAFFFFFF.toInt(); line.strokeWidth = dp(1f)
        rect.set(pad, by, pad + barW, by + barH)
        c.drawRect(rect, line)

        text(c, "LASER MK ${g.weaponName()}", pad, by + barH + ts * 1.2f, ts * 0.8f, WEAPON, Paint.Align.LEFT)

        // score
        text(c, "%,d".format(g.score), w - pad, pad + ts, ts * 1.2f, 0xFFFFFFFF.toInt(), Paint.Align.RIGHT)
        text(c, "%,d m".format((g.distance).toInt()), w - pad, pad + ts * 2.2f, ts * 0.75f, 0xBBFFFFFF.toInt(), Paint.Align.RIGHT)

        if (g.state != Game.State.PLAYING) return

        // warning when the next hit is fatal
        if (empty && (g.time * 3f).toInt() % 2 == 0) {
            text(c, "SHIELD DEPLETED - NEXT HIT IS FATAL", w / 2f, h * 0.2f, ts * 0.9f, DANGER)
        }

        // floating messages
        if (g.messageTime > 0f) {
            text(c, g.message, w / 2f, h * 0.14f, ts, withAlpha(0xFFFFFFFF.toInt(), min(1f, g.messageTime * 2f)))
        }

        // touch-zone hints
        val zr = dp(38f)
        val zy = h - pad - zr
        line.strokeWidth = dp(2f)
        line.color = withAlpha(0xFFFF6A5A.toInt(), if (g.firing) 0.9f else 0.35f)
        c.drawCircle(pad + zr, zy, zr, line)
        text(c, "FIRE", pad + zr, zy + ts * 0.3f, ts * 0.75f, line.color)
        line.color = withAlpha(SHIELD, if (g.shieldUp) 0.9f else 0.35f)
        c.drawCircle(w - pad - zr, zy, zr, line)
        text(c, "SHIELD", w - pad - zr, zy + ts * 0.3f, ts * 0.7f, line.color)

        // damage flash
        if (g.hitFlash > 0f) {
            fill.color = withAlpha(DANGER, g.hitFlash * 0.35f)
            c.drawRect(0f, 0f, w, h, fill)
        }
    }

    private fun dim(c: Canvas, a: Float) {
        fill.color = withAlpha(0xFF000000.toInt(), a)
        c.drawRect(0f, 0f, w, h, fill)
    }

    private fun drawTitle(c: Canvas, g: Game) {
        dim(c, 0.45f)
        val ts = max(dp(12f), h / 26f)
        text(c, "TRENCH RUN", w / 2f, h * 0.2f, ts * 2.6f, 0xFFFFD27A.toInt())
        val lines = arrayOf(
            "TILT LEFT / RIGHT ........ steer",
            if (g.invertPitch) "TILT FORWARD / BACK ...... climb / dive"
            else "TILT FORWARD / BACK ...... dive / climb",
            "HOLD LEFT SIDE ........... fire lasers",
            "HOLD RIGHT SIDE .......... shield (uses energy)",
            "Fly through CYAN cells for energy,",
            "ORANGE cores to upgrade your lasers."
        )
        var y = h * 0.33f
        for (l in lines) { text(c, l, w / 2f, y, ts * 0.78f, 0xFFE6ECF5.toInt()); y += ts * 1.15f }

        // pitch toggle button
        val label = if (g.invertPitch) "PITCH: FORWARD = CLIMB  (tap to swap)" else "PITCH: FORWARD = DIVE  (tap to swap)"
        txt.textSize = ts * 0.75f
        val tw = txt.measureText(label)
        val bx = w / 2f
        val byy = y + ts * 0.6f
        toggleRect.set(bx - tw / 2f - dp(14f), byy - ts, bx + tw / 2f + dp(14f), byy + ts * 0.5f)
        line.color = HUD; line.strokeWidth = dp(1.5f)
        c.drawRoundRect(toggleRect, dp(8f), dp(8f), line)
        text(c, label, bx, byy, ts * 0.75f, HUD)

        val blink = 0.6f + 0.4f * sin(g.time * 4f)
        text(c, "Hold your phone the way you want to play, then tap to launch", w / 2f, h * 0.88f, ts * 0.8f, withAlpha(0xFFFFFFFF.toInt(), blink))
        if (g.highScore > 0) text(c, "HIGH SCORE  %,d".format(g.highScore), w / 2f, h * 0.95f, ts * 0.7f, 0xBBFFD27A.toInt())
    }

    private fun drawPaused(c: Canvas) {
        dim(c, 0.55f)
        val ts = max(dp(12f), h / 26f)
        text(c, "PAUSED", w / 2f, h * 0.45f, ts * 2f, 0xFFFFFFFF.toInt())
        text(c, "Tap to resume (tilt is re-centered)", w / 2f, h * 0.58f, ts * 0.8f, 0xCCFFFFFF.toInt())
    }

    private fun drawGameOver(c: Canvas, g: Game) {
        dim(c, min(0.6f, g.stateTime * 0.5f))
        if (g.stateTime < 0.6f) return
        val ts = max(dp(12f), h / 26f)
        text(c, "SHIP DESTROYED", w / 2f, h * 0.3f, ts * 2f, DANGER)
        text(c, "SCORE  %,d".format(g.score), w / 2f, h * 0.45f, ts * 1.3f, 0xFFFFFFFF.toInt())
        text(c, "%,d m flown   |   %d drones destroyed".format(g.distance.toInt(), g.kills), w / 2f, h * 0.54f, ts * 0.8f, 0xCCFFFFFF.toInt())
        if (g.newHigh) text(c, "NEW HIGH SCORE!", w / 2f, h * 0.64f, ts, 0xFFFFD27A.toInt())
        else text(c, "HIGH SCORE  %,d".format(g.highScore), w / 2f, h * 0.64f, ts * 0.8f, 0xBBFFD27A.toInt())
        if (g.stateTime > 1.2f) {
            val blink = 0.6f + 0.4f * sin(g.time * 4f)
            text(c, "Tap to fly again", w / 2f, h * 0.8f, ts, withAlpha(0xFFFFFFFF.toInt(), blink))
        }
    }
}
