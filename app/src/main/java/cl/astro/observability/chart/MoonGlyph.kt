package cl.astro.observability.chart

import cl.astro.observability.astro.cosD
import cl.astro.observability.astro.sinD

/**
 * Draws the Moon's phase as seen in the sky.
 *
 * [illuminated] is the illuminated fraction k; [brightLimbFromZenithDeg] is the position angle of the
 * bright limb measured from the zenith towards the east (χ − q), so the glyph shows the terminator
 * the way the observer sees it with the zenith up (e.g. a waxing crescent lit on the left in Chile).
 */
object MoonGlyph {
    fun draw(
        sink: DrawSink,
        cx: Float,
        cy: Float,
        r: Float,
        illuminated: Double,
        brightLimbFromZenithDeg: Double,
        lit: Int,
        dark: Int,
        outline: Int,
    ) {
        sink.fillCircle(cx, cy, r, dark)
        val k = illuminated.coerceIn(0.0, 1.0)
        if (k > 0.005) {
            val c = 2.0 * k - 1.0 // cos of the phase angle
            val n = 24
            val xs = FloatArray(2 * n + 2)
            val ys = FloatArray(2 * n + 2)
            // Local frame: bright limb towards +x. Lit limb from top (−90°) through +x to bottom (+90°),
            // then the terminator back from bottom to top.
            var j = 0
            for (i in 0..n) {
                val a = -90.0 + 180.0 * i / n
                xs[j] = (cosD(a)).toFloat()
                ys[j] = (sinD(a)).toFloat()
                j++
            }
            for (i in 0..n) {
                val t = 90.0 - 180.0 * i / n
                xs[j] = (-c * cosD(t)).toFloat()
                ys[j] = (sinD(t)).toFloat()
                j++
            }
            // Screen direction of the bright limb: rotate "up" by θ counter-clockwise (towards the east).
            val dx = -sinD(brightLimbFromZenithDeg).toFloat()
            val dy = -cosD(brightLimbFromZenithDeg).toFloat()
            val px = FloatArray(j)
            val py = FloatArray(j)
            for (i in 0 until j) {
                // local +x → (dx, dy); local +y → (−dy, dx)
                px[i] = cx + r * (xs[i] * dx - ys[i] * dy)
                py[i] = cy + r * (xs[i] * dy + ys[i] * dx)
            }
            sink.fillPolygon(px, py, j, lit)
        }
        sink.strokeCircle(cx, cy, r, outline, maxOf(1f, r / 10f))
    }
}
