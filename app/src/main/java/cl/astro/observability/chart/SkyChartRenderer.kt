package cl.astro.observability.chart

import cl.astro.observability.astro.Ephemeris
import cl.astro.observability.astro.Levels
import cl.astro.observability.astro.NightResult
import cl.astro.observability.astro.cosD
import cl.astro.observability.astro.sinD
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.min

/**
 * Sky tracks ("star tracks"): the paths of the targets and of the Moon across the sky during the
 * plotted night, on a polar alt–az chart with the zenith at the centre, the horizon as the outer
 * circle, north up and east to the left (the sky as seen looking up). The radius is linear in zenith
 * distance. The airmass-limit circle is marked; the observable part of each path is drawn thicker;
 * dots mark whole hours of the display time scale.
 */
class SkyChartRenderer(var palette: ChartPalette, private val dp: Float, private val sp: Float) {

    fun draw(
        sink: DrawSink,
        width: Float,
        height: Float,
        results: List<NightResult>,
        active: Int,
        displayZone: ZoneId,
        cursor: Long? = null,
    ) {
        val p = palette
        sink.fillRect(0f, 0f, width, height, p.surface)
        if (results.isEmpty()) return
        val r = results[0]
        val fs = 11f * sp
        val fsSmall = 9.5f * sp
        // Legend (targets + Moon), wrapped into rows under the title.
        val names = results.map { ChartText.ellipsize(sink, it.target.name, width * 0.4f, fsSmall) } + "Moon"
        val itemW = names.map { 22f * dp + sink.textWidth(it, fsSmall) }
        val rowsX = ArrayList<MutableList<Int>>()
        var used = 0f
        for (i in names.indices) {
            if (rowsX.isEmpty() || used + itemW[i] + 12f * dp > width - 16f * dp) {
                rowsX.add(mutableListOf())
                used = 0f
            }
            rowsX.last().add(i)
            used += itemW[i] + 12f * dp
        }
        for ((ri, row) in rowsX.withIndex()) {
            var x = 8f * dp
            val y = 40f * dp + ri * 16f * dp
            for (i in row) {
                val isMoon = i == names.size - 1
                val c = if (isMoon) p.moon else p.seriesColor(i)
                sink.line(x, y - fsSmall * 0.35f, x + 16f * dp, y - fsSmall * 0.35f, c, 2.2f * dp,
                    if (isMoon) floatArrayOf(1.2f * dp, 3.2f * dp) else p.seriesDash(i, dp))
                sink.text(names[i], x + 20f * dp, y, fsSmall, if (i == active) p.textPrimary else p.textSecondary,
                    TextAlign.LEFT, i == active)
                x += itemW[i] + 12f * dp
            }
        }
        val top = 30f * dp + rowsX.size * 16f * dp + 6f * dp
        val bottom = 26f * dp
        // The disc is centred in the space left (also in a tall full-screen view); the cardinal letters
        // sit 13 dp outside it, so the side margins must hold them.
        val avail = height - top - bottom
        val rad = min(width / 2 - 28f * dp, avail / 2 - 16f * dp)
        if (rad < 30f) return
        val cx = width / 2
        val cy = top + avail / 2

        fun px(altDeg: Double, azDeg: Double): FloatArray {
            val rr = ((90.0 - altDeg) / 90.0 * rad).toFloat()
            return floatArrayOf(cx - rr * sinD(azDeg).toFloat(), cy - rr * cosD(azDeg).toFloat())
        }

        // Sky disc and grid
        sink.fillCircle(cx, cy, rad, p.night)
        for (alt in listOf(30.0, 60.0)) sink.strokeCircle(cx, cy, ((90 - alt) / 90 * rad).toFloat(), p.grid, 1f)
        for (az in 0 until 360 step 45) {
            val e = px(0.0, az.toDouble())
            sink.line(cx, cy, e[0], e[1], p.grid, 1f)
        }
        val limAlt = r.options.altitudeLimitDeg
        sink.strokeCircle(cx, cy, ((90 - limAlt) / 90 * rad).toFloat(), Colors.alpha(p.limit, 0.75f), 1f * dp)
        sink.strokeCircle(cx, cy, rad, p.axis, 1.2f * dp)
        for ((az, s) in listOf(0.0 to "N", 90.0 to "E", 180.0 to "S", 270.0 to "W")) {
            val rr = rad + 13f * dp
            val ex = cx - rr * sinD(az).toFloat()
            val ey = cy - rr * cosD(az).toFloat()
            sink.text(s, ex, ey + fs * 0.35f, fs, p.textPrimary, TextAlign.CENTER, true)
        }
        for (alt in listOf(30, 60)) {
            val e = px(alt.toDouble(), 202.5)
            sink.text("$alt°", e[0], e[1], fsSmall, p.textMuted, TextAlign.CENTER)
        }
        val e = px(limAlt, 157.5)
        sink.text("X=${ChartText.airmassLimit(r.options.airmassLimit)}", e[0], e[1] - 3f * dp, fsSmall, p.textSecondary, TextAlign.CENTER)

        val from = r.plotFrom
        val to = r.plotTo
        sink.save()
        sink.clipRect(cx - rad - 1, cy - rad - 1, cx + rad + 1, cy + rad + 1)

        // Moon path (only while above the horizon)
        drawPath(sink, r, from, to, { r.moonAlt[it] }, { r.moonAz[it] }, ::px, p.moon, 2f * dp,
            floatArrayOf(1.2f * dp, 3.2f * dp), null)

        // Target paths, active one last
        val order = results.indices.filter { it != active } + listOf(active).filter { it in results.indices }
        for (k in order) {
            val rk = results[k]
            val obs = BooleanArray(rk.times.size)
            for (iv in rk.observable) for (i in rk.indexAt(iv.start)..rk.indexAt(iv.end)) obs[i] = true
            drawPath(sink, rk, from, to, { rk.objAlt[it] }, { rk.objAz[it] }, ::px, p.seriesColor(k),
                if (k == active) 2f * dp else 1.6f * dp, p.seriesDash(k, dp), obs)
            // whole-hour dots, with labels on the active target
            var z = Instant.ofEpochMilli(rk.times[from]).atZone(displayZone).withMinute(0).withSecond(0).withNano(0).plusHours(1)
            while (z.toInstant().toEpochMilli() <= rk.times[to]) {
                val i = rk.indexAt(z.toInstant().toEpochMilli())
                if (rk.objAlt[i] > 0) {
                    val q = px(rk.objAlt[i], rk.objAz[i])
                    sink.fillCircle(q[0], q[1], 2.6f * dp, p.seriesColor(k))
                    if (k == active && z.hour % 2 == 0) {
                        sink.text(String.format(Locale.US, "%02d", z.hour), q[0] + 4f * dp, q[1] - 3f * dp, fsSmall, p.textSecondary)
                    }
                }
                z = z.plusHours(1)
            }
        }

        // Moon glyph at local midnight (or at the cursor time) if it is up
        val eph = Ephemeris(r.site, r.target, r.options.airmassModel)
        val tm = cursor ?: r.midnight
        val im = r.indexAt(tm)
        if (r.moonAlt[im] > Levels.MOON_RISE_SET) {
            val q = px(r.moonAlt[im].coerceAtLeast(0.0), r.moonAz[im])
            MoonGlyph.draw(sink, q[0], q[1], 7f * dp, r.moonPhase.illuminated, eph.moonBrightLimbFromZenithDeg(r.times[im]),
                p.moonLit, p.moonDark, p.moon)
        }
        // Cursor positions
        if (cursor != null) {
            for ((k, rk) in results.withIndex()) {
                val i = rk.indexAt(cursor)
                if (rk.objAlt[i] > 0) {
                    val q = px(rk.objAlt[i], rk.objAz[i])
                    sink.fillCircle(q[0], q[1], 5f * dp, p.seriesColor(k))
                    sink.strokeCircle(q[0], q[1], 5f * dp, p.textPrimary, 1.2f * dp)
                }
            }
        }
        sink.restore()

        sink.text("Sky tracks", 8f * dp, 18f * dp, 13f * sp, p.textPrimary, TextAlign.LEFT, true)
        sink.text("zenith at centre · N up, E left · dots every hour (${ChartText.shortZone(displayZone, r.site.zone)})",
            width - 8f * dp, 18f * dp, fsSmall, p.textMuted, TextAlign.RIGHT)
        val note = "${ChartText.hm(r.times[from], displayZone)} → ${ChartText.hm(r.times[to], displayZone)}; " +
            "thick = observable" + (cursor?.let { " · ● at ${ChartText.hm(it, displayZone)}" } ?: "")
        sink.text(note, cx, height - 8f * dp, fsSmall, p.textMuted, TextAlign.CENTER)
    }

    /** Draws the above-horizon parts of a path; [thick] marks samples drawn with a heavier stroke. */
    private fun drawPath(
        sink: DrawSink,
        r: NightResult,
        from: Int,
        to: Int,
        alt: (Int) -> Double,
        az: (Int) -> Double,
        px: (Double, Double) -> FloatArray,
        color: Int,
        width: Float,
        dash: FloatArray?,
        thick: BooleanArray?,
    ) {
        val n = to - from + 1
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        val up = BooleanArray(n)
        for (k in 0 until n) {
            val i = from + k
            val a = alt(i)
            up[k] = a > 0.0
            val q = px(a.coerceAtLeast(-2.0), az(i))
            xs[k] = q[0]
            ys[k] = q[1]
        }
        var k = 0
        while (k < n) {
            if (!up[k]) {
                k++
                continue
            }
            var j = k
            while (j + 1 < n && up[j + 1]) j++
            // include one sample on each side so that the path reaches the horizon circle
            val a = (k - 1).coerceAtLeast(0)
            val b = (j + 1).coerceAtMost(n - 1)
            sink.polyline(xs, ys, a, b + 1, color, width, dash)
            if (thick != null) {
                var s = a
                while (s <= b) {
                    if (thick[from + s]) {
                        var e = s
                        while (e + 1 <= b && thick[from + e + 1]) e++
                        if (e > s) sink.polyline(xs, ys, s, e + 1, color, width * 2.2f, dash)
                        s = e + 1
                    } else {
                        s++
                    }
                }
            }
            k = j + 1
        }
    }
}
