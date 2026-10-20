package com.randaladams.trenchrun

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Procedurally painted textures for the trench walls and floor.
 * Generated once at startup, so there are no image assets to ship.
 *
 * Wall texture: width spans one 6-unit trench segment along z, height spans the 10-unit wall
 * (row 0 = top of the wall). Pipes and bands sit at the same heights in every variant so
 * they read as continuous conduits running the length of the trench.
 */
object Textures {

    const val WALL_W = 256
    const val WALL_H = 432
    const val FLOOR_W = 512   // spans the 12-unit trench width
    const val FLOOR_H = 256   // spans one 6-unit segment along z

    private const val WALL_PPU_Y = WALL_H / Game.TOP          // pixels per unit, vertical
    private const val BASE = 0xFF5C6373.toInt()
    private const val DARK = 0xFF2A2F39.toInt()

    private val p = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }
    private val s = Paint().apply { isAntiAlias = false; style = Paint.Style.STROKE; strokeWidth = 1f }

    fun walls(count: Int) = List(count) { wall(Random(1000 + it * 77)) }
    fun floors(count: Int) = List(count) { floor(Random(5000 + it * 31)) }

    // ------------------------------------------------------------ helpers

    private fun shade(c: Int, k: Float): Int {
        val r = min(255, max(0, (Color.red(c) * k).toInt()))
        val g = min(255, max(0, (Color.green(c) * k).toInt()))
        val b = min(255, max(0, (Color.blue(c) * k).toInt()))
        return Color.rgb(r, g, b)
    }

    private fun rect(c: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int) {
        p.color = color
        c.drawRect(l, t, r, b, p)
    }

    /** A beveled metal plate: lit top/left edge, shadowed bottom/right edge, dark seam. */
    private fun plate(c: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int, bevel: Float = 2f) {
        rect(c, l, t, r, b, color)
        rect(c, l, t, r, t + bevel, shade(color, 1.28f))
        rect(c, l, t, l + bevel, b, shade(color, 1.15f))
        rect(c, l, b - bevel, r, b, shade(color, 0.62f))
        rect(c, r - bevel, t, r, b, shade(color, 0.7f))
        s.color = DARK
        c.drawRect(l, t, r, b, s)
    }

    private fun rivets(c: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int) {
        val inset = 5f
        for ((x, y) in listOf(l + inset to t + inset, r - inset to t + inset, l + inset to b - inset, r - inset to b - inset)) {
            rect(c, x - 1.5f, y - 1.5f, x + 1.5f, y + 1.5f, shade(color, 0.55f))
            rect(c, x - 1.5f, y - 1.5f, x + 0.5f, y + 0.5f, shade(color, 1.4f))
        }
    }

    private fun vent(c: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int) {
        rect(c, l, t, r, b, shade(color, 0.45f))
        var y = t + 3f
        while (y < b - 3f) {
            rect(c, l + 3f, y, r - 3f, y + 2f, shade(color, 0.95f))
            rect(c, l + 3f, y + 2f, r - 3f, y + 3f, shade(color, 0.3f))
            y += 6f
        }
    }

    /** Horizontal cylinder shaded with a cosine profile. */
    private fun pipe(c: Canvas, w: Float, centerY: Float, radius: Float, color: Int) {
        val top = centerY - radius
        val n = (radius * 2).toInt().coerceAtLeast(2)
        for (i in 0 until n) {
            val a = (i / (n - 1f) - 0.35f) * Math.PI.toFloat()
            val k = 0.45f + 0.85f * max(0f, cos(a))
            rect(c, 0f, top + i, w, top + i + 1, shade(color, k))
        }
    }

    private fun light(c: Canvas, x: Float, y: Float, color: Int) {
        rect(c, x - 4f, y - 3f, x + 4f, y + 3f, shade(color, 0.35f))
        rect(c, x - 3f, y - 2f, x + 3f, y + 2f, color)
        rect(c, x - 1f, y - 1f, x + 1f, y + 1f, Color.WHITE)
    }

    // --------------------------------------------------------------- walls

    private fun yPx(units: Float) = (Game.TOP - units) * WALL_PPU_Y

    private fun wall(r: Random): Bitmap {
        val w = WALL_W.toFloat()
        val bmp = Bitmap.createBitmap(WALL_W, WALL_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        rect(c, 0f, 0f, w, WALL_H.toFloat(), BASE)

        // top lip (y 9.4 .. 10)
        plate(c, 0f, 0f, w, yPx(9.4f), shade(BASE, 1.2f), 3f)

        // upper plating (7.9 .. 9.4)
        var x = 0f
        while (x < w) {
            val pw = min(w - x, 50f + r.nextInt(70))
            plate(c, x, yPx(9.4f), x + pw, yPx(7.9f), shade(BASE, 0.9f + r.nextFloat() * 0.18f))
            if (r.nextInt(3) == 0) light(c, x + pw / 2f, yPx(8.65f), if (r.nextBoolean()) 0xFFFFC870.toInt() else 0xFFBFE8FF.toInt())
            x += pw
        }

        // conduit band (6.7 .. 7.9): large pipe running the length of the trench
        rect(c, 0f, yPx(7.9f), w, yPx(6.7f), shade(BASE, 0.5f))
        pipe(c, w, yPx(7.3f), 0.42f * WALL_PPU_Y, 0xFF7D8696.toInt())

        // main panel area (2.9 .. 6.7)
        val mt = yPx(6.7f); val mb = yPx(2.9f)
        x = 0f
        while (x < w) {
            val cw = min(w - x, 36f + r.nextInt(80))
            val split = r.nextInt(3)
            val rows = if (split == 0) listOf(mt to mb) else {
                val m = mt + (mb - mt) * (0.3f + r.nextFloat() * 0.4f)
                listOf(mt to m, m to mb)
            }
            for ((pt, pb) in rows) {
                val col = shade(BASE, 0.82f + r.nextFloat() * 0.3f)
                plate(c, x, pt, x + cw, pb, col)
                val ph = pb - pt
                when (r.nextInt(6)) {
                    0 -> if (ph > 30 && cw > 26) vent(c, x + 6f, pt + 6f, x + cw - 6f, pb - 6f, col)
                    1 -> rivets(c, x, pt, x + cw, pb, col)
                    2 -> if (ph > 28 && cw > 28) {  // recessed machinery box
                        val ix = x + cw * 0.2f; val iy = pt + ph * 0.2f
                        rect(c, ix, iy, x + cw * 0.8f, pt + ph * 0.8f, shade(col, 0.4f))
                        plate(c, ix + 3f, iy + 3f, x + cw * 0.8f - 2f, pt + ph * 0.8f - 2f, shade(col, 0.85f))
                    }
                    3 -> light(c, x + cw / 2f, pt + 10f, if (r.nextInt(4) == 0) 0xFFFF5A4A.toInt() else 0xFFFFC870.toInt())
                    4 -> { // horizontal ribs
                        var yy = pt + 6f
                        while (yy < pb - 6f) { rect(c, x + 4f, yy, x + cw - 4f, yy + 2f, shade(col, 1.25f)); rect(c, x + 4f, yy + 2f, x + cw - 4f, yy + 3f, shade(col, 0.5f)); yy += 9f }
                    }
                    else -> {}
                }
            }
            x += cw
        }

        // twin small conduits (1.8 .. 2.9)
        rect(c, 0f, yPx(2.9f), w, yPx(1.8f), shade(BASE, 0.45f))
        pipe(c, w, yPx(2.55f), 0.18f * WALL_PPU_Y, 0xFF8E8370.toInt())
        pipe(c, w, yPx(2.1f), 0.2f * WALL_PPU_Y, 0xFF6F7A8C.toInt())
        // pipe clamps
        repeat(2 + r.nextInt(2)) {
            val cx = r.nextFloat() * (w - 10f)
            rect(c, cx, yPx(2.9f), cx + 7f, yPx(1.8f), shade(BASE, 0.75f))
            rect(c, cx, yPx(2.9f), cx + 2f, yPx(1.8f), shade(BASE, 1.1f))
        }
        repeat(2) {
            val cx = r.nextFloat() * (w - 12f)
            rect(c, cx, yPx(7.9f), cx + 9f, yPx(6.7f), shade(BASE, 0.75f))
            rect(c, cx, yPx(7.9f), cx + 2f, yPx(6.7f), shade(BASE, 1.1f))
        }

        // heavy skirt plating at the base (0 .. 1.8) with vertical ribs
        x = 0f
        while (x < w) {
            val pw = min(w - x, 60f + r.nextInt(60))
            val col = shade(BASE, 0.62f + r.nextFloat() * 0.12f)
            plate(c, x, yPx(1.8f), x + pw, WALL_H.toFloat(), col, 3f)
            var rx = x + 8f
            while (rx < x + pw - 8f) { rect(c, rx, yPx(1.6f), rx + 3f, WALL_H - 6f, shade(col, 1.3f)); rect(c, rx + 3f, yPx(1.6f), rx + 4f, WALL_H - 6f, shade(col, 0.5f)); rx += 14f }
            x += pw
        }

        // grime streaks running down the wall
        repeat(4 + r.nextInt(4)) {
            val gx = r.nextFloat() * w
            val gw = 3f + r.nextFloat() * 10f
            val gt = yPx(9.4f) + r.nextFloat() * 80f
            val gb = gt + 60f + r.nextFloat() * 200f
            p.color = Color.argb(40 + r.nextInt(40), 10, 12, 16)
            c.drawRect(gx, gt, gx + gw, min(gb, WALL_H.toFloat()), p)
        }
        // darker segment seam so motion along the trench is easy to read
        rect(c, 0f, 0f, 2f, WALL_H.toFloat(), DARK)

        bmp.setHasMipMap(true)
        return bmp
    }

    // --------------------------------------------------------------- floor

    private fun floor(r: Random): Bitmap {
        val w = FLOOR_W.toFloat(); val h = FLOOR_H.toFloat()
        val ppu = FLOOR_W / (Game.HALF_W * 2f)
        fun xPx(units: Float) = (units + Game.HALF_W) * ppu
        val base = 0xFF424856.toInt()
        val bmp = Bitmap.createBitmap(FLOOR_W, FLOOR_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        rect(c, 0f, 0f, w, h, base)

        // deck plates
        var y = 0f
        while (y < h) {
            val ph = min(h - y, 50f + r.nextInt(60))
            var x = 0f
            while (x < w) {
                val pw = min(w - x, 60f + r.nextInt(90))
                val col = shade(base, 0.85f + r.nextFloat() * 0.25f)
                plate(c, x, y, x + pw, y + ph, col)
                if (r.nextInt(4) == 0) rivets(c, x, y, x + pw, y + ph, col)
                x += pw
            }
            y += ph
        }

        // gutters along the base of each wall
        rect(c, 0f, 0f, xPx(-5.2f), h, shade(base, 0.45f))
        rect(c, xPx(5.2f), 0f, w, h, shade(base, 0.45f))
        var gy = 4f
        while (gy < h) { rect(c, 0f, gy, xPx(-5.2f), gy + 3f, shade(base, 0.8f)); rect(c, xPx(5.2f), gy, w, gy + 3f, shade(base, 0.8f)); gy += 10f }

        // recessed center channel with grooves
        rect(c, xPx(-1.4f), 0f, xPx(1.4f), h, shade(base, 0.55f))
        var gx = xPx(-1.2f)
        while (gx < xPx(1.2f)) { rect(c, gx, 0f, gx + 3f, h, shade(base, 0.85f)); rect(c, gx + 3f, 0f, gx + 4f, h, shade(base, 0.35f)); gx += 12f }

        // amber guide lights at x = +/-3
        for (lx in floatArrayOf(-3f, 3f)) {
            val cx = xPx(lx)
            rect(c, cx - 7f, 30f, cx + 7f, 110f, shade(base, 0.4f))
            rect(c, cx - 5f, 34f, cx + 5f, 106f, 0xFFD88A2A.toInt())
            rect(c, cx - 2f, 38f, cx + 2f, 102f, 0xFFFFE0A0.toInt())
        }

        // scuffs
        repeat(6) {
            p.color = Color.argb(30 + r.nextInt(40), 0, 0, 0)
            val sx = r.nextFloat() * w; val sy = r.nextFloat() * h
            c.drawRect(sx, sy, sx + 10f + r.nextFloat() * 40f, sy + 3f + r.nextFloat() * 8f, p)
        }
        rect(c, 0f, h - 2f, w, h, DARK)
        bmp.setHasMipMap(true)
        return bmp
    }
}
