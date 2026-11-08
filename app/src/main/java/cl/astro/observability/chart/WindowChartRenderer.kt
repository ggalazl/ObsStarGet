package cl.astro.observability.chart

import cl.astro.observability.astro.Levels
import cl.astro.observability.astro.MS_PER_HOUR
import cl.astro.observability.astro.WindowResult
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * Date-window overview: one column per night (x = date, y = time of night). Cells are coloured by the
 * target's airmass from 1 to [maxAirmass] (sequential ramp, brightest = lowest airmass) when the Sun is
 * down; twilight cells are dimmed; hatching marks the Moon above the horizon, bolder when it is fuller.
 * A strip on top shows the Moon's illuminated fraction and a bar chart below the observable hours.
 */
class WindowChartRenderer(var palette: ChartPalette, private val dp: Float, private val sp: Float) {
    var plotL = 0f
        private set
    var plotR = 0f
        private set
    var plotT = 0f
        private set
    var plotB = 0f
        private set
    private var nNights = 1

    /** Airmass at the dark end of the colour scale (raised to the airmass limit if that is larger). */
    var maxAirmass = 2.5

    fun nightAt(x: Float): Int? {
        if (x < plotL || x > plotR || nNights == 0) return null
        return ((x - plotL) / (plotR - plotL) * nNights).toInt().coerceIn(0, nNights - 1)
    }

    fun draw(sink: DrawSink, width: Float, height: Float, r: WindowResult, selected: Int) {
        val p = palette
        val fs = 11f * sp
        val fsSmall = 9.5f * sp
        val fsTitle = 13f * sp
        sink.fillRect(0f, 0f, width, height, p.surface)
        val nights = r.nights
        nNights = nights.size
        if (nights.isEmpty()) return

        plotL = 44f * dp
        plotR = width - 16f * dp
        val moonT = 52f * dp
        val moonB = moonT + 16f * dp
        plotT = moonB + 6f * dp
        val legendH = 22f * dp
        val barsH = 50f * dp
        val barsHeaderH = 18f * dp
        val dateH = 32f * dp
        plotB = height - legendH - barsH - barsHeaderH - dateH
        val barsT = plotB + dateH + barsHeaderH
        val barsB = barsT + barsH
        if (plotR - plotL < 40f || plotB - plotT < 40f) return
        val colW = (plotR - plotL) / nights.size
        val rows = r.rows
        val rowH = (plotB - plotT) / rows
        val hours = r.yEndHours - r.yStartHours
        fun yOfHours(h: Double): Float = plotT + ((h - r.yStartHours) / hours).toFloat() * (plotB - plotT)
        fun yOfMs(ms: Long, ref: Long): Float = yOfHours((ms - ref) / MS_PER_HOUR.toDouble())
        val darkLevel = r.options.dark.sunAltDeg
        val xMax = ChartText.axisMaxAirmass(maxAirmass, r.options.airmassLimit)

        // Column edges snapped to whole pixels so that adjacent columns do not leave seams.
        fun colX(c: Int): Float = kotlin.math.floor(plotL + c * colW)

        // --- heat map, drawn as vertical runs of equal colour
        for ((c, nt) in nights.withIndex()) {
            val x0 = colX(c)
            val x1 = if (c == nights.size - 1) plotR else colX(c + 1)
            var runStart = 0
            var runColor = 0
            for (row in 0..rows) {
                val color = if (row < rows) {
                    val sun = nt.rowSunAlt[row].toDouble()
                    val x = nt.rowAirmass[row]
                    var col = when {
                        sun >= Levels.SUN_RISE_SET -> p.day
                        sun >= Levels.CIVIL -> p.civil
                        sun >= Levels.NAUTICAL -> p.nautical
                        sun >= Levels.ASTRONOMICAL -> p.astro
                        else -> p.night
                    }
                    if (sun < Levels.SUN_RISE_SET && x <= xMax) {
                        val ramp = p.rampAt(((x - 1.0) / (xMax - 1.0)).toFloat())
                        col = if (sun < darkLevel) ramp else Colors.mix(col, ramp, 0.35f)
                    }
                    col
                } else {
                    0
                }
                if (row == 0) {
                    runColor = color
                } else if (row == rows || color != runColor) {
                    sink.fillRect(x0, plotT + runStart * rowH, x1, plotT + row * rowH + 0.5f, runColor)
                    runStart = row
                    runColor = color
                }
            }
        }

        // --- Moon above the horizon (Sun down): 45° hatching whose opacity follows the illuminated
        // fraction. Texture rather than a wash, so that it cannot be mistaken for a lower airmass.
        val hatch = 7f * dp
        for ((c, nt) in nights.withIndex()) {
            val x0 = colX(c)
            val x1 = if (c == nights.size - 1) plotR else colX(c + 1)
            val illum = nt.moonPhase.illuminated.toFloat()
            val col = Colors.alpha(p.moonOverlay, 0.25f + 0.65f * illum)
            // A dark under-stroke keeps the hatching visible on the bright (low-airmass) cells too.
            val under = Colors.alpha(p.night, 0.25f + 0.45f * illum)
            var row = 0
            while (row < rows) {
                if (nt.rowMoonAlt[row] > Levels.MOON_RISE_SET && nt.rowSunAlt[row] < Levels.SUN_RISE_SET) {
                    var end = row
                    while (end < rows && nt.rowMoonAlt[end] > Levels.MOON_RISE_SET &&
                        nt.rowSunAlt[end] < Levels.SUN_RISE_SET
                    ) end++
                    val y0 = plotT + row * rowH
                    val y1 = plotT + end * rowH
                    sink.save()
                    sink.clipRect(x0, y0, x1, y1)
                    // Lines x + y = k·hatch (global pattern, continuous across columns).
                    var k = kotlin.math.floor((x0 + y0) / hatch)
                    while (k * hatch <= x1 + y1) {
                        val s = k * hatch
                        sink.line(s - y0, y0, s - y1, y1, under, 2.6f * dp)
                        sink.line(s - y0, y0, s - y1, y1, col, 1f * dp)
                        k += 1f
                    }
                    sink.restore()
                    row = end
                } else {
                    row++
                }
            }
        }

        // --- Sun lines across nights: sunset/sunrise solid, start/end of dark time dashed
        fun sunLine(get: (Int) -> Long?, dash: FloatArray?) {
            val xs = FloatArray(nights.size)
            val ys = FloatArray(nights.size)
            var k = 0
            fun flush() {
                if (k > 1) sink.polyline(xs, ys, 0, k, Colors.alpha(p.textPrimary, 0.55f), 1.2f * dp, dash)
                else if (k == 1) sink.fillCircle(xs[0], ys[0], 1.5f * dp, Colors.alpha(p.textPrimary, 0.55f))
                k = 0
            }
            for (c in nights.indices) {
                val ms = get(c)
                if (ms == null) {
                    flush()
                    continue
                }
                xs[k] = plotL + (c + 0.5f) * colW
                ys[k] = yOfMs(ms, nights[c].refMidnight)
                k++
            }
            flush()
        }
        sink.save()
        sink.clipRect(plotL, plotT, plotR, plotB)
        sunLine({ nights[it].sunset }, null)
        sunLine({ nights[it].sunrise }, null)
        val dd = floatArrayOf(4f * dp, 3f * dp)
        sunLine({ nights[it].darkStart }, dd)
        sunLine({ nights[it].darkEnd }, dd)
        // Direct labels at the right end of the Sun lines (sunset/dark above their line, dawn ones below).
        val last = nights.last()
        for ((ms, label, above) in listOf(
            Triple(last.sunset, "sunset", true), Triple(last.darkStart, "dark", false),
            Triple(last.darkEnd, "dark", true), Triple(last.sunrise, "sunrise", false),
        )) {
            if (ms == null) continue
            val y = yOfMs(ms, last.refMidnight)
            // Keep the label inside the plot: flip it to the other side of its line at the edges.
            var ly = if (above) y - 3f * dp else y + fsSmall + 1f * dp
            if (ly > plotB - 2f * dp) ly = y - 3f * dp
            if (ly - fsSmall < plotT) ly = y + fsSmall + 1f * dp
            sink.text(label, plotR - 3f * dp, ly, fsSmall, p.textPrimary, TextAlign.RIGHT)
        }

        // --- airmass-limit contour (target reaching X = limit while rising / setting)
        val lim = r.options.airmassLimit
        for (risingBranch in listOf(true, false)) {
            val xs = FloatArray(nights.size)
            val ys = FloatArray(nights.size)
            var k = 0
            var lastY = 0f
            fun flush() {
                if (k > 1) sink.polyline(xs, ys, 0, k, p.textPrimary, 1.4f * dp)
                k = 0
            }
            for ((c, nt) in nights.withIndex()) {
                var found = Float.NaN
                for (row in 1 until rows) {
                    val a = nt.rowAirmass[row - 1]
                    val b = nt.rowAirmass[row]
                    val crosses = if (risingBranch) a > lim && b <= lim else a <= lim && b > lim
                    if (crosses && nt.rowSunAlt[row] < Levels.SUN_RISE_SET) {
                        val fa = if (a.isInfinite()) 1f else ((a - lim) / (a - b)).toFloat().coerceIn(0f, 1f)
                        found = plotT + (row - 0.5f + fa) * rowH
                        break
                    }
                }
                if (found.isNaN() || (k > 0 && abs(found - lastY) > (plotB - plotT) / hours.toFloat() * 1.5f)) flush()
                if (!found.isNaN()) {
                    xs[k] = plotL + (c + 0.5f) * colW
                    ys[k] = found
                    lastY = found
                    k++
                }
            }
            flush()
        }
        sink.restore()

        // --- selected night
        if (selected in nights.indices) {
            val x0 = plotL + selected * colW
            sink.strokeRect(x0, plotT - 2f, x0 + max(colW, 2f), plotB + 2f, p.textPrimary, 1.5f * dp)
        }
        sink.strokeRect(plotL, plotT, plotR, plotB, p.axis, 1f)

        // --- y axis: clock hours of the display time scale
        val pxPerHour = (plotB - plotT) / hours.toFloat()
        val yStep = if (pxPerHour >= 15f * dp) 1 else if (pxPerHour >= 8f * dp) 2 else 3
        var h = ceil(r.yStartHours).toInt()
        while (h <= r.yEndHours) {
            if (((h % 24) + 24) % 24 % yStep == 0) {
                val y = yOfHours(h.toDouble())
                sink.line(plotL - 4f * dp, y, plotL, y, p.axis, 1f)
                sink.text(String.format(Locale.US, "%02d", ((h % 24) + 24) % 24), plotL - 6f * dp, y + fs * 0.35f, fs,
                    p.textSecondary, TextAlign.RIGHT)
            }
            h++
        }
        val tz = ChartText.zoneLabel(r.displayZone, nights[0].refMidnight)
        val yTitle = when (val short = ChartText.shortZone(r.displayZone, r.site.zone)) {
            "local" -> "Local time"
            else -> if (r.displayZone is java.time.ZoneOffset || short == "UT") tz else "Time ($short)"
        }
        sink.textUp(yTitle, 12f * dp, (plotT + plotB) / 2 + sink.textWidth(yTitle, fs) / 2, fs, p.textMuted)

        // --- x axis: dates
        drawDates(sink, r, colW, fs)

        // --- Moon strip: illuminated fraction per night; full / new Moon marked
        sink.text("Moon", plotL - 6f * dp, moonB - 3f * dp, fsSmall, p.textMuted, TextAlign.RIGHT)
        sink.fillRect(plotL, moonT, plotR, moonB, p.night)
        val gap = if (colW >= 5f * dp) 1f * dp else 0f
        for ((c, nt) in nights.withIndex()) {
            val k = nt.moonPhase.illuminated.toFloat()
            val x0 = plotL + c * colW + gap / 2
            sink.fillRect(x0, moonB - (moonB - moonT) * k, x0 + colW - gap + 0.3f, moonB, Colors.alpha(p.moon, 0.85f))
        }
        for (c in nights.indices) {
            val e = nights[c].moonPhase.elongationEastDeg
            val ePrev = if (c > 0) nights[c - 1].moonPhase.elongationEastDeg else Double.NaN
            if (ePrev.isNaN()) continue
            val cx = plotL + (c + 0.5f) * colW
            if (ePrev < 180.0 && e >= 180.0) {
                sink.strokeCircle(cx, moonT - 7f * dp, 4f * dp, p.moonLit, 1.4f * dp) // full Moon
            } else if (ePrev > 300.0 && e < 60.0) {
                sink.fillCircle(cx, moonT - 7f * dp, 4f * dp, p.moonDark)       // new Moon
                sink.strokeCircle(cx, moonT - 7f * dp, 4f * dp, p.moonLit, 1f * dp)
            }
        }

        // --- bars: observable hours (dark, X ≤ limit), Moon-down part solid, Moon-up part light
        val maxH = max(1.0, nights.maxOf { it.observableHours })
        val top = ceil(maxH / 2.0) * 2.0
        fun yBar(v: Double) = barsB - (v / top).toFloat() * (barsB - barsT)
        for (v in listOf(0.0, top / 2, top)) {
            sink.line(plotL, yBar(v), plotR, yBar(v), p.grid, 1f)
            sink.text(String.format(Locale.US, "%.0fh", v), plotL - 6f * dp, yBar(v) + fsSmall * 0.35f, fsSmall,
                p.textSecondary, TextAlign.RIGHT)
        }
        val bgap = if (colW >= 4f * dp) 2f else 0f
        for ((c, nt) in nights.withIndex()) {
            val x0 = plotL + c * colW + bgap / 2
            val x1 = x0 + colW - bgap + 0.3f
            val md = nt.observableMoonDownHours
            val all = nt.observableHours
            if (all > 0.0) sink.fillRect(x0, yBar(all), x1, yBar(md), p.barMoonUp)
            if (md > 0.0) sink.fillRect(x0, yBar(md), x1, barsB, p.barMoonDown)
            if (c == selected) sink.strokeRect(x0, barsT, x1, barsB, p.textPrimary, 1f * dp)
        }

        // --- bars header
        val hy = barsT - 6f * dp
        var hx = plotL
        val head = String.format(Locale.US, "Observable hours (dark, X ≤ %.1f):", r.options.airmassLimit)
        sink.text(head, hx, hy, fsSmall, p.textSecondary)
        hx += sink.textWidth(head, fsSmall) + 8f * dp
        sink.fillRect(hx, hy - fsSmall * 0.8f, hx + 10f * dp, hy, p.barMoonDown)
        hx += 14f * dp
        sink.text("Moon down", hx, hy, fsSmall, p.textSecondary)
        hx += sink.textWidth("Moon down", fsSmall) + 10f * dp
        sink.fillRect(hx, hy - fsSmall * 0.8f, hx + 10f * dp, hy, p.barMoonUp)
        hx += 14f * dp
        sink.text("Moon up", hx, hy, fsSmall, p.textSecondary)

        // --- title
        val df = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.US)
        val title = ChartText.ellipsize(sink, "${r.target.name} · ${r.site.name}", width - 16f * dp, fsTitle, true)
        sink.text(title, 8f * dp, 18f * dp, fsTitle, p.textPrimary, TextAlign.LEFT, true)
        val sub = "Nights of ${df.format(nights.first().date)} → ${df.format(nights.last().date)} (${nights.size})" +
            "  ·  tap a night for details"
        sink.text(ChartText.ellipsize(sink, sub, width - 16f * dp, fsSmall), 8f * dp, 36f * dp, fsSmall, p.textSecondary)

        drawLegend(sink, width, height, r, fsSmall)
    }

    private fun drawDates(sink: DrawSink, r: WindowResult, colW: Float, fs: Float) {
        val p = palette
        val nights = r.nights
        val dFmt = DateTimeFormatter.ofPattern("d", Locale.US)
        val dmFmt = DateTimeFormatter.ofPattern("d MMM", Locale.US)
        val mFmt = DateTimeFormatter.ofPattern("MMM", Locale.US)
        val labelW = sink.textWidth("30 Sep", fs) + 6f * dp
        val step = listOf(1, 2, 3, 5, 7, 14).firstOrNull { it * colW >= sink.textWidth("30", fs) + 8f * dp } ?: 0
        val y = plotB + 4f * dp + fs
        if (step == 0 || nights.size > 120) {
            // Month starts only.
            var lastX = Float.NEGATIVE_INFINITY
            for ((c, nt) in nights.withIndex()) {
                if (nt.date.dayOfMonth != 1 && c != 0) continue
                val x = plotL + (c + 0.5f) * colW
                sink.line(x, plotB, x, plotB + 4f * dp, p.axis, 1f)
                if (x - lastX >= labelW) {
                    val s = if (c == 0 || nt.date.monthValue == 1) "${mFmt.format(nt.date)} ${nt.date.year}" else mFmt.format(nt.date)
                    sink.text(s, x, y, fs, p.textSecondary, TextAlign.CENTER)
                    lastX = x
                }
            }
            return
        }
        var lastMonth = -1
        var lastX = Float.NEGATIVE_INFINITY
        for ((c, nt) in nights.withIndex()) {
            if (c % step != 0) continue
            val x = plotL + (c + 0.5f) * colW
            sink.line(x, plotB, x, plotB + 4f * dp, p.axis, 1f)
            val withMonth = nt.date.monthValue != lastMonth
            val s = if (withMonth) dmFmt.format(nt.date) else dFmt.format(nt.date)
            val w = sink.textWidth(s, fs)
            if (x - w / 2 > lastX + 4f * dp) {
                sink.text(s, x, y, fs, p.textSecondary, TextAlign.CENTER)
                lastX = x + w / 2
                lastMonth = nt.date.monthValue
            }
        }
        sink.text("Date of evening", (plotL + plotR) / 2, y + fs + 2f * dp, fs * 0.9f, p.textMuted, TextAlign.CENTER)
    }

    private fun drawLegend(sink: DrawSink, width: Float, height: Float, r: WindowResult, fs: Float) {
        val p = palette
        val y = height - 7f * dp
        var x = 8f * dp
        // airmass colour bar
        sink.text("X 1.0", x, y, fs, p.textSecondary)
        x += sink.textWidth("X 1.0", fs) + 4f * dp
        val barW = 60f * dp
        val n = 30
        for (i in 0 until n) {
            sink.fillRect(x + barW * i / n, y - fs * 0.8f, x + barW * (i + 1) / n + 0.5f, y, p.rampAt(i / (n - 1f), 64))
        }
        x += barW + 4f * dp
        val maxS = ChartText.airmassLimit(ChartText.axisMaxAirmass(maxAirmass, r.options.airmassLimit))
        sink.text(maxS, x, y, fs, p.textSecondary)
        x += sink.textWidth(maxS, fs) + 12f * dp
        // limit contour
        sink.line(x, y - fs * 0.35f, x + 16f * dp, y - fs * 0.35f, p.textPrimary, 1.4f * dp)
        x += 20f * dp
        val limS = String.format(Locale.US, "X = %.1f", r.options.airmassLimit)
        sink.text(limS, x, y, fs, p.textSecondary)
        x += sink.textWidth(limS, fs) + 12f * dp
        // Moon up (hatched swatch)
        val sx0 = x
        val sy0 = y - fs * 0.9f
        val sx1 = x + 14f * dp
        sink.fillRect(sx0, sy0, sx1, y, p.night)
        sink.save()
        sink.clipRect(sx0, sy0, sx1, y)
        var s = sx0
        while (s < sx1 + (y - sy0)) {
            sink.line(s, sy0, s - (y - sy0), y, Colors.alpha(p.moonOverlay, 0.7f), 1f * dp)
            s += 7f * dp
        }
        sink.restore()
        x += 18f * dp
        val moonLegend = "Moon up (bolder = fuller)"
        sink.text(moonLegend, x, y, fs, p.textSecondary)
        x += sink.textWidth(moonLegend, fs) + 12f * dp
        // Sun lines
        if (x + 120f * dp < width) {
            sink.line(x, y - fs * 0.35f, x + 14f * dp, y - fs * 0.35f, Colors.alpha(p.textPrimary, 0.55f), 1.2f * dp)
            x += 18f * dp
            sink.text("Sun set/rise", x, y, fs, p.textSecondary)
            x += sink.textWidth("Sun set/rise", fs) + 10f * dp
            sink.line(x, y - fs * 0.35f, x + 14f * dp, y - fs * 0.35f, Colors.alpha(p.textPrimary, 0.55f), 1.2f * dp,
                floatArrayOf(4f * dp, 3f * dp))
            x += 18f * dp
            sink.text("dark", x, y, fs, p.textSecondary)
        }
    }
}
