package com.randaladams.trenchrun

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
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
class Renderer(private val density: Float, private val buildLabel: String) {

    companion object {
        private const val SEG = 6f
        private const val SPACE = 0xFF05070F.toInt()
        private const val FOG = 0xFF0B1020.toInt()
        private const val EDGE = 0xFF9AA3B6.toInt()
        private const val SURFACE = 0xFF5C6373.toInt()
        private const val HAZ_METAL = 0xFF3E3A40.toInt()   // obstacles: dark metal...
        private const val HAZ_RED = 0xFFE8321E.toInt()     // ...with red stripes, lamps and edges
        private const val DRONE = 0xFF7A1410.toInt()
        const val SHIELD = 0xFF40E0FF.toInt()               // good: cyan
        const val WEAPON = 0xFF7CFF4A.toInt()               // good: green
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

    /** Hit areas of the title-screen toggles (read by GameView). */
    val toggleRect = RectF()
    val soundRect = RectF()

    private val wallTex = Textures.walls(4)
    private val floorTex = Textures.floors(2)
    private val texMatrix = Matrix()
    private val src = FloatArray(8)
    private val dst = FloatArray(8)
    private val texPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    // Fog for textures: color * (1 - t) * shade + fogColor * t, cached at 33 fog levels
    private val fogFilters = Array(2) { si ->
        val k = if (si == 0) 1f else 0.8f
        Array(33) { i ->
            val t = i / 32f
            val m = ((1f - t) * k * 255f).toInt()
            LightingColorFilter(
                Color.rgb(m, m, m),
                Color.rgb((Color.red(FOG) * t).toInt(), (Color.green(FOG) * t).toInt(), (Color.blue(FOG) * t).toInt())
            )
        }
    }

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
        val ww = Textures.WALL_W.toFloat(); val wh = Textures.WALL_H.toFloat()
        val fw = Textures.FLOOR_W.toFloat(); val fh = Textures.FLOOR_H.toFloat()

        for (k in kFar downTo kNear) {
            val zA = k * SEG - g.distance
            val za = max(zA, near)
            val zb = min(zA + SEG, Game.FAR)
            if (zb <= za) continue
            val t = fogT(za)
            val hs = hash(k)
            val partial = za > zA || zb < zA + SEG
            val u0 = (za - zA) / SEG * ww; val u1 = (zb - zA) / SEG * ww

            // textured floor (v = 0 at the far edge of the segment)
            val fv0 = (zA + SEG - zb) / SEG * fh; val fv1 = (zA + SEG - za) / SEG * fh
            texQuad(c, floorTex[hs % floorTex.size], 0f, fv0, fw, fv1,
                -hw, 0f, zb, hw, 0f, zb, hw, 0f, za, -hw, 0f, za, fogFilter(0, t), partial)

            // textured walls (row 0 = top of the wall)
            texQuad(c, wallTex[hs % wallTex.size], u0, 0f, u1, wh,
                -hw, top, za, -hw, top, zb, -hw, 0f, zb, -hw, 0f, za, fogFilter(0, t), partial)
            texQuad(c, wallTex[(hs shr 4) % wallTex.size], u0, 0f, u1, wh,
                hw, top, za, hw, top, zb, hw, 0f, zb, hw, 0f, za, fogFilter(1, t), partial)

            // structures on the surface above the walls
            if (hs % 11 == 0) box(c, -hw - 3f, -hw - 0.6f, top, top + rand01(hs) * 2.5f + 0.8f, zA + 1f, zA + 4f, SURFACE, 0, bottom = false)
            if ((hs shr 5) % 11 == 0) box(c, hw + 0.6f, hw + 3f, top, top + rand01(hs shr 2) * 2.5f + 0.8f, zA + 1f, zA + 4f, SURFACE, 0, bottom = false)

            // bright wall top edges
            line.color = fog(EDGE, t)
            line.strokeWidth = max(dp(1f), 0.06f * scaleAt(za))
            c.drawLine(sx(-hw, za), sy(top, za), sx(-hw, zb), sy(top, zb), line)
            c.drawLine(sx(hw, za), sy(top, za), sx(hw, zb), sy(top, zb), line)
        }
    }

    private fun rand01(h: Int) = (h % 1000) / 1000f

    private fun fogFilter(shadeIdx: Int, t: Float) = fogFilters[shadeIdx][(t * 32f + 0.5f).toInt().coerceIn(0, 32)]

    /**
     * Draws a rectangle of a texture (u0,v0)-(u1,v1) onto a world-space quad with true
     * perspective, using a 4-point poly-to-poly matrix. Corners go top-left, top-right,
     * bottom-right, bottom-left in texture space.
     */
    private fun texQuad(
        c: Canvas, bmp: Bitmap, u0: Float, v0: Float, u1: Float, v1: Float,
        x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float,
        x3: Float, y3: Float, z3: Float, x4: Float, y4: Float, z4: Float,
        filter: LightingColorFilter, clip: Boolean
    ) {
        src[0] = u0; src[1] = v0; src[2] = u1; src[3] = v0
        src[4] = u1; src[5] = v1; src[6] = u0; src[7] = v1
        dst[0] = sx(x1, z1); dst[1] = sy(y1, z1); dst[2] = sx(x2, z2); dst[3] = sy(y2, z2)
        dst[4] = sx(x3, z3); dst[5] = sy(y3, z3); dst[6] = sx(x4, z4); dst[7] = sy(y4, z4)
        if (!texMatrix.setPolyToPoly(src, 0, dst, 0, 4)) return
        texPaint.colorFilter = filter
        if (clip) {
            path.reset()
            path.moveTo(dst[0], dst[1]); path.lineTo(dst[2], dst[3])
            path.lineTo(dst[4], dst[5]); path.lineTo(dst[6], dst[7]); path.close()
            c.save()
            c.clipPath(path)
            c.drawBitmap(bmp, texMatrix, texPaint)
            c.restore()
        } else {
            c.drawBitmap(bmp, texMatrix, texPaint)
        }
    }

    /** Axis-aligned box. Only faces that point toward the camera are drawn. */
    private fun box(
        c: Canvas, x0: Float, x1: Float, y0: Float, y1: Float, z0In: Float, z1: Float,
        color: Int, outlineColor: Int, flash: Float = 0f, bottom: Boolean = true
    ): Boolean {
        val z0 = max(z0In, near)
        if (z1 <= z0 || z0 > Game.FAR) return false
        val t = fogT(z0)
        val base = if (flash > 0f) mix(color, 0xFFFFFFFF.toInt(), flash * 0.8f) else color
        if (camY > y1) quad(x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, fog(shade(base, 1.3f), t), c)
        if (bottom && camY < y0) quad(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, fog(shade(base, 0.55f), t), c)
        if (camX < x0) quad(x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1, fog(shade(base, 0.78f), t), c)
        if (camX > x1) quad(x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, fog(shade(base, 0.78f), t), c)
        // the front face is always a screen-aligned rectangle
        rect.set(sx(x0, z0), sy(y1, z0), sx(x1, z0), sy(y0, z0))
        fill.color = fog(base, t)
        c.drawRect(rect, fill)
        if (outlineColor != 0) {
            line.color = fog(outlineColor, t)
            line.strokeWidth = max(dp(1.5f), 0.07f * scaleAt(z0))
            c.drawRect(rect, line)
        }
        return true
    }

    // -------------------------------------------------------------- entities
    // Color language: anything RED / striped is dangerous, glowing CYAN and GREEN orbs are good.

    private fun drawEntity(c: Canvas, e: Entity, g: Game) {
        val blink = ((g.time * 3f + e.z0 * 0.05f).toInt() % 2 == 0)
        when (e.kind) {
            Kind.PICKUP_SHIELD, Kind.PICKUP_WEAPON -> drawPickup(c, e, g)
            Kind.DRONE -> drawDrone(c, e, g)
            Kind.BRIDGE -> if (box(c, e.x0, e.x1, e.y0, e.y1, e.z0, e.z1, HAZ_METAL, HAZ_RED)) {
                stripes(c, e.x0, e.x1, e.y0 + 0.2f, e.y1 - 0.2f, e.z0)
                lamps(c, e, e.y1 - 0.05f, blink)
            }
            Kind.CEILING -> if (box(c, e.x0, e.x1, e.y0, e.y1, e.z0, e.z1, HAZ_METAL, HAZ_RED)) {
                stripes(c, e.x0, e.x1, e.y0, e.y0 + 0.55f, e.z0)
                lamps(c, e, e.y0 + 0.8f, blink)
            }
            Kind.RAISED_FLOOR -> if (box(c, e.x0, e.x1, e.y0, e.y1, e.z0, e.z1, HAZ_METAL, HAZ_RED)) {
                stripes(c, e.x0, e.x1, e.y1 - 0.55f, e.y1, e.z0)
                lamps(c, e, e.y1 - 0.8f, blink)
            }
            Kind.SIDE_BLOCK -> if (box(c, e.x0, e.x1, e.y0, e.y1, e.z0, e.z1, HAZ_METAL, HAZ_RED)) {
                // stripe the edge that sticks out into the trench
                if (e.x0 <= -Game.HALF_W + 0.01f) stripes(c, e.x1 - 0.6f, e.x1, e.y0, e.y1, e.z0)
                else stripes(c, e.x0, e.x0 + 0.6f, e.y0, e.y1, e.z0)
            }
            Kind.PILLAR -> if (box(c, e.x0, e.x1, e.y0, e.y1, e.z0, e.z1, HAZ_METAL, HAZ_RED)) {
                stripes(c, e.x0, e.x0 + 0.45f, e.y0, e.y1, e.z0)
                stripes(c, e.x1 - 0.45f, e.x1, e.y0, e.y1, e.z0)
            }
        }
    }

    /** Diagonal red/black hazard stripes on the front face of an obstacle. */
    private fun stripes(c: Canvas, xa: Float, xb: Float, ya: Float, yb: Float, zIn: Float) {
        val z = max(zIn, near)
        val t = fogT(z)
        val l = sx(xa, z); val r = sx(xb, z); val top = sy(yb, z); val bot = sy(ya, z)
        if (r <= l || bot <= top) return
        rect.set(l, top, r, bot)
        fill.color = fog(0xFF15151A.toInt(), t)
        c.drawRect(rect, fill)
        val sw = max(dp(3f), 0.35f * scaleAt(z))
        val hgt = bot - top
        c.save()
        c.clipRect(rect)
        fill.color = fog(HAZ_RED, t)
        var x = l - hgt
        while (x < r) {
            path.reset()
            path.moveTo(x, bot); path.lineTo(x + sw, bot); path.lineTo(x + sw + hgt, top); path.lineTo(x + hgt, top); path.close()
            c.drawPath(path, fill)
            x += sw * 2f
        }
        c.restore()
    }

    /** Blinking red warning lamps along the front of an obstacle. */
    private fun lamps(c: Canvas, e: Entity, y: Float, on: Boolean) {
        val z = max(e.z0, near)
        val t = fogT(z) * 0.5f
        val s = scaleAt(z)
        val n = 4
        for (i in 0 until n) {
            val x = e.x0 + (e.x1 - e.x0) * (i + 0.5f) / n
            val px = sx(x, z); val py = sy(y, z)
            if (on) {
                fill.color = withAlpha(fog(0xFFFF2A1A.toInt(), t), 0.35f)
                c.drawCircle(px, py, 0.45f * s, fill)
            }
            fill.color = fog(if (on) 0xFFFF6A50.toInt() else 0xFF5A1A14.toInt(), t)
            c.drawCircle(px, py, max(dp(1.5f), 0.14f * s), fill)
        }
    }

    private fun drawDrone(c: Canvas, e: Entity, g: Game) {
        val z = max(e.z0, near)
        if (z >= e.z1) return
        val t = fogT(z)
        val s = scaleAt(z)
        val px = sx(e.cx, z); val py = sy(e.cy, z)
        val spin = g.time * 2.5f + e.phase
        val pulse = 0.75f + 0.25f * sin(g.time * 10f + e.phase)
        val flash = e.hitFlash

        // red danger aura
        fill.color = withAlpha(fog(0xFFFF2A1A.toInt(), t), 0.22f + 0.1f * pulse)
        c.drawCircle(px, py, 1.25f * s, fill)

        // spikes
        line.color = fog(mix(0xFFB8BEC8.toInt(), 0xFFFFFFFF.toInt(), flash), t)
        line.strokeWidth = max(dp(1.5f), 0.12f * s)
        for (i in 0 until 8) {
            val a = spin + i * (Math.PI.toFloat() / 4f)
            val r1 = 0.45f * s; val r2 = (if (i % 2 == 0) 1.05f else 0.8f) * s
            c.drawLine(px + cos(a) * r1, py + sin(a) * r1, px + cos(a) * r2, py + sin(a) * r2, line)
        }
        // body
        fill.color = fog(mix(DRONE, 0xFFFFFFFF.toInt(), flash * 0.8f), t)
        c.drawCircle(px, py, 0.55f * s, fill)
        line.color = fog(HAZ_RED, t)
        line.strokeWidth = max(dp(1.2f), 0.06f * s)
        c.drawCircle(px, py, 0.55f * s, line)
        // eye
        fill.color = fog(0xFF2A0606.toInt(), t)
        c.drawCircle(px, py, 0.3f * s, fill)
        fill.color = fog(mix(0xFFFF3020.toInt(), 0xFFFFD0A0.toInt(), pulse - 0.5f), t)
        c.drawCircle(px, py, 0.18f * s * pulse, fill)
    }

    private fun drawPickup(c: Canvas, e: Entity, g: Game) {
        val z = max((e.z0 + e.z1) * 0.5f, near)
        val t = fogT(z) * 0.6f
        val s = scaleAt(z)
        val bob = sin(e.phase * 3f) * 0.2f
        val px = sx(e.cx, z); val py = sy(e.cy + bob, z)
        val shield = e.kind == Kind.PICKUP_SHIELD
        val color = if (shield) SHIELD else WEAPON
        val r = 0.55f * s

        // soft glow + expanding ring
        fill.color = withAlpha(fog(color, t), 0.25f)
        c.drawCircle(px, py, r * 2f, fill)
        val ring = (e.phase * 1.4f) % 1f
        line.color = withAlpha(fog(color, t), 1f - ring)
        line.strokeWidth = max(dp(1.5f), 0.08f * s)
        c.drawCircle(px, py, r * (1.1f + ring * 1.3f), line)

        // orb with highlight
        fill.color = fog(color, t)
        c.drawCircle(px, py, r, fill)
        fill.color = withAlpha(0xFFFFFFFF.toInt(), 0.55f * (1f - t))
        c.drawCircle(px - r * 0.3f, py - r * 0.3f, r * 0.3f, fill)

        // symbol: plus for shield energy, double chevron for laser upgrade
        line.color = fog(0xFF06202A.toInt(), t)
        line.strokeWidth = max(dp(1.5f), 0.12f * s)
        if (shield) {
            c.drawLine(px - r * 0.45f, py, px + r * 0.45f, py, line)
            c.drawLine(px, py - r * 0.45f, px, py + r * 0.45f, line)
        } else {
            for (k in 0..1) {
                val oy = r * (0.05f + k * 0.35f) - r * 0.2f
                c.drawLine(px - r * 0.42f, py + oy + r * 0.2f, px, py + oy - r * 0.15f, line)
                c.drawLine(px, py + oy - r * 0.15f, px + r * 0.42f, py + oy + r * 0.2f, line)
            }
        }

        // name tag as it gets close
        if (z < 110f && g.state == Game.State.PLAYING) {
            val ts = (0.42f * s).coerceIn(dp(9f), dp(18f))
            text(c, if (shield) "SHIELD" else "LASER+", px, py - r * 2.2f, ts, withAlpha(fog(color, t), 0.95f))
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
        dim(c, 0.5f)
        val ts = max(dp(11f), h / 28f)
        text(c, "TRENCH RUN", w / 2f, h * 0.17f, ts * 2.6f, 0xFFFFD27A.toInt())
        val lines = arrayOf(
            "TILT LEFT / RIGHT ........ steer",
            if (g.invertPitch) "TILT FORWARD / BACK ...... climb / dive"
            else "TILT FORWARD / BACK ...... dive / climb",
            "HOLD LEFT SIDE ........... fire lasers",
            "HOLD RIGHT SIDE .......... shield (uses energy)"
        )
        var y = h * 0.29f
        for (l in lines) { text(c, l, w / 2f, y, ts * 0.78f, 0xFFE6ECF5.toInt()); y += ts * 1.1f }

        // legend: what is good and what is dangerous
        y += ts * 0.3f
        val r = ts * 0.45f
        val col1 = w * 0.2f; val col2 = w * 0.5f; val col3 = w * 0.78f
        fill.color = SHIELD; c.drawCircle(col1 - r * 2.2f, y - r * 0.6f, r, fill)
        text(c, "SHIELD +", col1 + r, y, ts * 0.72f, SHIELD, Paint.Align.LEFT)
        fill.color = WEAPON; c.drawCircle(col2 - r * 2.2f, y - r * 0.6f, r, fill)
        text(c, "LASER UPGRADE", col2 + r - ts * 1.6f, y, ts * 0.72f, WEAPON, Paint.Align.LEFT)
        fill.color = HAZ_RED; c.drawRect(col3 - r * 3.2f, y - r * 1.6f, col3 - r * 1.2f, y + r * 0.4f, fill)
        text(c, "RED = DANGER", col3 - r * 0.6f, y, ts * 0.72f, HAZ_RED, Paint.Align.LEFT)
        y += ts * 1.5f

        // toggles
        txt.textSize = ts * 0.72f
        val pitchLabel = if (g.invertPitch) "PITCH: FORWARD = CLIMB" else "PITCH: FORWARD = DIVE"
        val soundLabel = if (g.soundOn) "SOUND: ON" else "SOUND: OFF"
        val pw = txt.measureText(pitchLabel) + dp(28f)
        val sw = txt.measureText(soundLabel) + dp(28f)
        val gap = dp(16f)
        val left = w / 2f - (pw + sw + gap) / 2f
        toggleRect.set(left, y - ts, left + pw, y + ts * 0.5f)
        soundRect.set(left + pw + gap, y - ts, left + pw + gap + sw, y + ts * 0.5f)
        line.color = HUD; line.strokeWidth = dp(1.5f)
        c.drawRoundRect(toggleRect, dp(8f), dp(8f), line)
        c.drawRoundRect(soundRect, dp(8f), dp(8f), line)
        text(c, pitchLabel, toggleRect.centerX(), y, ts * 0.72f, HUD)
        text(c, soundLabel, soundRect.centerX(), y, ts * 0.72f, HUD)
        text(c, "(tap a button to change it)", w / 2f, y + ts * 1.2f, ts * 0.6f, 0x99FFFFFF.toInt())

        val blink = 0.6f + 0.4f * sin(g.time * 4f)
        text(c, "Hold your phone the way you want to play, then tap to launch", w / 2f, h * 0.87f, ts * 0.8f, withAlpha(0xFFFFFFFF.toInt(), blink))
        if (g.highScore > 0) text(c, "HIGH SCORE  %,d".format(g.highScore), w / 2f, h * 0.94f, ts * 0.7f, 0xBBFFD27A.toInt())
        text(c, buildLabel, w - dp(10f), h - dp(8f), ts * 0.55f, 0x88FFFFFF.toInt(), Paint.Align.RIGHT)
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
