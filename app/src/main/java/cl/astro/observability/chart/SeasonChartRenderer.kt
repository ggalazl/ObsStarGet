package cl.astro.observability.chart

import cl.astro.observability.astro.SeasonResult
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Year overview: for each night of the scan, the hours when the target is observable (dark time,
 * X ≤ limit) as columns — Moon down solid, Moon up light — under the length of dark time (thin line).
 * The best observability window(s) are shaded, and the opposition night (transit at local midnight) and the best
 * single night are marked. One axis: hours per night.
 */
class SeasonChartRenderer(var palette: ChartPalette, private val dp: Float, private val sp: Float) {
    var plotL = 0f
        private set
    var plotR = 0f
        private set
    var plotT = 0f
        private set
    var plotB = 0f
        private set
    private var n = 1

    fun nightAt(x: Float): Int? {
        if (plotR <= plotL || x < plotL - 2 || x > plotR + 2 || n == 0) return null
        return ((x.coerceIn(plotL, plotR - 0.01f) - plotL) / (plotR - plotL) * n).toInt().coerceIn(0, n - 1)
    }

    fun draw(sink: DrawSink, width: Float, height: Float, r: SeasonResult, cursor: Int?, zone: ZoneId) {
        val p = palette
        val fs = 11f * sp
        val fsSmall = 9.5f * sp
        val fsTitle = 13f * sp
        sink.fillRect(0f, 0f, width, height, p.surface)
        val nights = r.nights
        n = nights.size
        if (nights.isEmpty()) return

        plotL = 40f * dp
        plotR = width - 14f * dp
        plotT = 84f * dp
        val legendH = 40f * dp
        val dateH = 22f * dp
        plotB = height - legendH - dateH
        if (plotR - plotL < 60f || plotB - plotT < 50f) return
        val colW = (plotR - plotL) / n
        fun colX(c: Int): Float = plotL + c * colW
        fun cx(c: Int): Float = plotL + (c + 0.5f) * colW

        val top = max(2.0, ceil(max(r.maxDarkHours, r.maxObservableHours) / 2.0) * 2.0)
        fun yOf(h: Double): Float = plotB - (h / top).toFloat() * (plotB - plotT)

        // --- best periods (background band)
        val band = Colors.alpha(p.textPrimary, 0.08f)
        for (b in r.bestPeriods) {
            sink.fillRect(colX(b.first), plotT, colX(b.last + 1), plotB, band)
        }

        // --- grid and y axis
        val stepH = if (top <= 8.0) 2.0 else 4.0
        var h = 0.0
        while (h <= top + 1e-9) {
            val y = yOf(h)
            sink.line(plotL, y, plotR, y, p.grid, 1f)
            sink.text(String.format(Locale.US, "%.0fh", h), plotL - 5f * dp, y + fsSmall * 0.35f, fsSmall, p.textSecondary,
                TextAlign.RIGHT)
            h += stepH
        }

        // --- columns: observable hours, Moon-down part solid, Moon-up part light
        val gap = if (colW >= 4f * dp) 1f * dp else 0f
        for ((c, nt) in nights.withIndex()) {
            val x0 = floor(colX(c)) + gap / 2
            val x1 = if (c == n - 1) plotR else floor(colX(c + 1)) - gap / 2
            val all = nt.observableHours
            val md = min(nt.moonDownHours, all)
            if (all > 0.0) sink.fillRect(x0, yOf(all), x1 + 0.4f, yOf(md), p.barMoonUp)
            if (md > 0.0) sink.fillRect(x0, yOf(md), x1 + 0.4f, plotB, p.barMoonDown)
        }

        // --- dark time (upper envelope)
        val xs = FloatArray(n) { cx(it) }
        val ys = FloatArray(n) { yOf(nights[it].darkHours) }
        // Same style as the Sun lines of the date-window diagram, with a halo so it stays visible over the columns.
        sink.polyline(xs, ys, 0, n, Colors.alpha(p.surface, 0.8f), 3.2f * dp)
        sink.polyline(xs, ys, 0, n, darkLine(), 1.3f * dp)

        sink.strokeRect(plotL, plotT, plotR, plotB, p.axis, 1f)

        // --- annotations above the plot: row A holds the best periods, the opposition and best-night
        // labels go to row B (or row A) wherever they do not collide.
        val dm = DateTimeFormatter.ofPattern("d MMM", Locale.US)
        val rowA = plotT - 26f * dp
        val rowB = plotT - 7f * dp
        val used = arrayOf(ArrayList<ClosedFloatingPointRange<Float>>(), ArrayList())
        fun free(row: Int, a: Float, b: Float) = used[row].none { a < it.endInclusive + 6f * dp && b > it.start - 6f * dp }
        for (b in r.bestPeriods) {
            val x0 = colX(b.first)
            val x1 = colX(b.last + 1)
            sink.line(x0, rowA + 4f * dp, x1, rowA + 4f * dp, p.textSecondary, 2f * dp)
            val label = "best window"
            val w = sink.textWidth(label, fsSmall)
            // Left-aligned on the band, leaving its middle (opposition, best night) for other labels.
            val lx = x0.coerceIn(plotL, plotR - w)
            if (free(0, lx, lx + w)) {
                sink.text(label, lx, rowA, fsSmall, p.textSecondary)
                used[0].add(lx..lx + w)
            }
        }
        class Mark(val x: Float, val labels: List<String>)
        val marks = ArrayList<Mark>()
        r.peak?.let { k ->
            val x = cx(k)
            val y = yOf(nights[k].observableHours) - 2f * dp
            val s = 4.5f * dp
            sink.fillPolygon(floatArrayOf(x - s, x + s, x), floatArrayOf(y - s * 1.6f, y - s * 1.6f, y), 3, p.textPrimary)
            val d = dm.format(nights[k].date)
            marks.add(Mark(x, listOf("▼ best night $d", "▼ $d")))
        }
        r.midnightTransit?.let { k ->
            val x = cx(k)
            sink.line(x, plotT, x, plotB, Colors.alpha(p.textPrimary, 0.7f), 1f * dp, floatArrayOf(2f * dp, 3f * dp))
            val d = dm.format(nights[k].date)
            marks.add(Mark(x, listOf("opposition $d", "opp. $d")))
        }
        for (m in marks) {
            var placed = false
            for (label in m.labels) {
                val w = sink.textWidth(label, fsSmall)
                for (row in intArrayOf(1, 0)) {
                    for (lx in floatArrayOf(m.x + 3f * dp, m.x - 3f * dp - w)) {
                        if (lx < 4f * dp || lx + w > width - 4f * dp || !free(row, lx, lx + w)) continue
                        val y = if (row == 1) rowB else rowA
                        sink.line(m.x, y + 2f * dp, m.x, plotT, Colors.alpha(p.textPrimary, 0.7f), 1f * dp)
                        sink.text(label, lx, y, fsSmall, p.textPrimary)
                        used[row].add(lx..lx + w)
                        // Keep the connector of this mark clear of later labels (it crosses row B too).
                        used[1].add(m.x - 1f..m.x + 1f)
                        placed = true
                        break
                    }
                    if (placed) break
                }
                if (placed) break
            }
        }

        // --- x axis: month starts
        val mFmt = DateTimeFormatter.ofPattern("MMM", Locale.US)
        val yDate = plotB + 4f * dp + fs
        var lastX = Float.NEGATIVE_INFINITY
        val minGap = sink.textWidth("Mmm 2027", fs) * 0.5f + 6f * dp
        for ((c, nt) in nights.withIndex()) {
            if (nt.date.dayOfMonth != 1) continue
            val x = colX(c)
            sink.line(x, plotB, x, plotB + 4f * dp, p.axis, 1f)
            val s = if (nt.date.monthValue == 1) "${mFmt.format(nt.date)} ${nt.date.year}" else mFmt.format(nt.date)
            val mx = x + 15f * colW // label centred in its month
            if (mx - lastX >= minGap && mx < plotR - 8f * dp) {
                sink.text(s, mx, yDate, fs, p.textSecondary, TextAlign.CENTER)
                lastX = mx
            }
        }

        // --- cursor
        if (cursor != null && cursor in nights.indices) {
            val x = cx(cursor)
            sink.line(x, plotT, x, plotB, p.textPrimary, 1f * dp)
            drawTooltip(sink, r, cursor, x, zone, fsSmall)
        }

        // --- title
        val title = ChartText.ellipsize(sink, "Best time of year · ${r.target.name} · ${r.site.name}", width - 16f * dp, fsTitle, true)
        sink.text(title, 8f * dp, 18f * dp, fsTitle, p.textPrimary, TextAlign.LEFT, true)
        val df = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)
        val sub = "${n} nights from ${df.format(nights.first().date)} · X ≤ ${ChartText.airmassLimit(r.options.airmassLimit)} · " +
            "Sun < ${r.options.dark.sunAltDeg.toInt()}° · tap for a night"
        sink.text(ChartText.ellipsize(sink, sub, width - 16f * dp, fsSmall), 8f * dp, 35f * dp, fsSmall, p.textSecondary)

        drawLegend(sink, width, height, fsSmall)
    }

    private fun drawTooltip(sink: DrawSink, r: SeasonResult, k: Int, x: Float, zone: ZoneId, fs: Float) {
        val p = palette
        val nt = r.nights[k]
        val df = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.US)
        val lim = ChartText.airmassLimit(r.options.airmassLimit)
        val lines = ArrayList<String>()
        lines.add(df.format(nt.date))
        lines.add(String.format(Locale.US, "%.1f h at X ≤ %s · %.1f h Moon down", nt.observableHours, lim, nt.moonDownHours))
        val best = when {
            !nt.bestAirmass.isFinite() || nt.bestTime == null -> "below the horizon in the dark"
            nt.bestAirmass <= 3.0 -> String.format(Locale.US, "X min %.2f at %s", nt.bestAirmass, ChartText.hm(nt.bestTime, zone))
            else -> String.format(Locale.US, "highest %.0f° at %s", r.options.airmassModel.altitudeForAirmass(nt.bestAirmass),
                ChartText.hm(nt.bestTime, zone))
        }
        lines.add(String.format(Locale.US, "%s · dark %.1f h · Moon %.0f%%", best, nt.darkHours, nt.moonIllumination * 100))
        val w = lines.maxOf { sink.textWidth(it, fs) } + 12f * dp
        val lh = fs * 1.35f
        val hgt = lines.size * lh + 8f * dp
        var bx = x + 8f * dp
        if (bx + w > plotR) bx = x - 8f * dp - w
        bx = bx.coerceIn(2f * dp, plotR - w)
        val by = plotT + 4f * dp
        sink.fillRect(bx, by, bx + w, by + hgt, p.tooltip)
        sink.strokeRect(bx, by, bx + w, by + hgt, p.axis, 1f)
        for ((i, s) in lines.withIndex()) {
            sink.text(s, bx + 6f * dp, by + 4f * dp + (i + 1) * lh - fs * 0.3f, fs, if (i == 0) p.textPrimary else p.textSecondary,
                TextAlign.LEFT, i == 0)
        }
    }

    private fun drawLegend(sink: DrawSink, width: Float, height: Float, fs: Float) {
        val p = palette
        val items = listOf<Pair<String, (Float, Float) -> Unit>>(
            "Moon down" to { x, y -> sink.fillRect(x, y - fs * 0.8f, x + 10f * dp, y, p.barMoonDown) },
            "Moon up" to { x, y -> sink.fillRect(x, y - fs * 0.8f, x + 10f * dp, y, p.barMoonUp) },
            "dark time" to { x, y -> sink.line(x, y - fs * 0.35f, x + 10f * dp, y - fs * 0.35f, darkLine(), 1.3f * dp) },
            "best window" to { x, y ->
                sink.fillRect(x, y - fs * 0.9f, x + 10f * dp, y + 1f * dp, Colors.alpha(p.textPrimary, 0.16f))
            },
            "opposition" to { x, y ->
                sink.line(x + 5f * dp, y - fs * 0.9f, x + 5f * dp, y + 1f * dp, Colors.alpha(p.textPrimary, 0.7f), 1f * dp,
                    floatArrayOf(2f * dp, 3f * dp))
            },
        )
        val head = "Hours per night, observable (dark, X ≤ limit):"
        var y = height - legendRowGap(fs) - 6f * dp
        sink.text(ChartText.ellipsize(sink, head, width - 16f * dp, fs), 8f * dp, y, fs, p.textMuted)
        y += legendRowGap(fs)
        var x = 8f * dp
        for ((label, swatch) in items) {
            val w = 14f * dp + sink.textWidth(label, fs) + 12f * dp
            if (x + w > width - 4f * dp) break
            swatch(x, y)
            sink.text(label, x + 14f * dp, y, fs, p.textSecondary)
            x += w
        }
    }

    private fun legendRowGap(fs: Float) = fs * 1.6f

    private fun darkLine() = Colors.alpha(palette.textPrimary, 0.55f)
}
