package cl.astro.observability.chart

import cl.astro.observability.astro.DarkLimit
import cl.astro.observability.astro.Ephemeris
import cl.astro.observability.astro.Levels
import cl.astro.observability.astro.MS_PER_HOUR
import cl.astro.observability.astro.NightResult
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

enum class YAxisMode(val label: String) { AIRMASS("Airmass"), ALTITUDE("Altitude") }

/** Shared text helpers. */
object ChartText {
    private val hm = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    fun hm(ms: Long, zone: ZoneId): String = hm.format(Instant.ofEpochMilli(ms).atZone(zone))

    fun lstHm(deg: Double): String {
        val totalMin = Math.round((deg / 15.0) * 60.0).toInt() % (24 * 60)
        return String.format(Locale.US, "%02d:%02d", totalMin / 60, totalMin % 60)
    }

    fun ellipsize(sink: DrawSink, s: String, maxWidth: Float, size: Float, bold: Boolean = false): String {
        if (sink.textWidth(s, size, bold) <= maxWidth) return s
        var t = s
        while (t.length > 1 && sink.textWidth("$t…", size, bold) > maxWidth) t = t.dropLast(1)
        return "$t…"
    }

    fun isUt(zone: ZoneId): Boolean = zone.id == "UTC" || zone.id == "Z" || zone.id == "GMT"

    /** "UTC−3", "UTC+5:30" for an offset in seconds. */
    fun utcOffset(totalSeconds: Int): String {
        val sign = if (totalSeconds < 0) "−" else "+"
        val a = abs(totalSeconds)
        val hh = a / 3600
        val mm = (a % 3600) / 60
        return if (mm == 0) "UTC$sign$hh" else String.format(Locale.US, "UTC%s%d:%02d", sign, hh, mm)
    }

    /** "UT", "UTC−3" (a fixed offset) or "America/Santiago, UTC−3" (a region, offset at [at]). */
    fun zoneLabel(zone: ZoneId, at: Long): String {
        if (isUt(zone)) return "UT"
        val off = utcOffset(zone.rules.getOffset(Instant.ofEpochMilli(at)).totalSeconds)
        return if (zone is ZoneOffset) off else "${zone.id}, $off"
    }

    /**
     * Short name of the time scale for read-outs: "UT", "UTC−3", "local" (the site's own zone) or the
     * city of another zone ("Paris" for Europe/Paris, e.g. the phone's zone).
     */
    fun shortZone(zone: ZoneId, siteZone: ZoneId): String = when {
        isUt(zone) -> "UT"
        zone is ZoneOffset -> utcOffset(zone.totalSeconds)
        zone.normalized().id == siteZone.normalized().id -> "local"
        else -> zone.id.substringAfterLast('/').replace('_', ' ')
    }

    fun airmassLimit(x: Double): String =
        if (Math.round(x * 100) % 10 == 0L) String.format(Locale.US, "%.1f", x) else String.format(Locale.US, "%.2f", x)

    /**
     * Airmass shown at the night-diagram cursor, with two decimals; null below the horizon. Next to a target's point it
     * is written as a plain number, read against the airmass axis (left, or right when the diagram shows altitude).
     */
    fun airmassValue(alt: Double, airmass: Double): String? =
        if (alt > 0.0 && airmass.isFinite() && airmass <= 99.0) String.format(Locale.US, "%.2f", airmass) else null

    /**
     * Read-out of one target in the cursor box. The airmass is written next to the target's point on the curve, so it
     * is added here only when that label could not be drawn ([withAirmass]: the point is off the diagram or too close
     * to another one). [compact] splits the text over two lines for narrow screens.
     */
    fun cursorLines(name: String, alt: Double, airmass: Double, az: Double, moonSep: Double, withAirmass: Boolean,
                    compact: Boolean): List<String> {
        val am = if (withAirmass) airmassValue(alt, airmass)?.let { "  airmass $it" } ?: "" else ""
        val first = String.format(Locale.US, "alt %.1f°", alt) + am
        val rest = String.format(Locale.US, "az %.0f°  Moon %.0f°", az, moonSep)
        val n = name.take(16)
        return if (compact) listOf("$n  $first", "    $rest") else listOf("$n  $first  $rest")
    }

    /** Largest airmass shown: the chosen axis maximum, but never less than the observability limit. */
    fun axisMaxAirmass(chosen: Double, limit: Double): Double = kotlin.math.max(chosen, limit)

    /**
     * Airmass ticks from 1 to [xMax] inclusive: the finest of 0.1, 0.2, 0.25, 0.5, 1 that divides the
     * range exactly and leaves at least [minSpacingPx] between ticks on a [lengthPx] axis.
     */
    fun airmassTicks(xMax: Double, lengthPx: Float, minSpacingPx: Float): List<Double> {
        val span = xMax - 1.0
        if (span <= 0.0) return listOf(1.0)
        val steps = listOf(0.1, 0.2, 0.25, 0.5, 1.0)
        fun exact(st: Double) = abs(span / st - Math.round(span / st)) < 1e-6
        val step = steps.firstOrNull { exact(it) && lengthPx / (span / it).toFloat() >= minSpacingPx }
            ?: steps.lastOrNull { exact(it) } ?: 0.5
        val n = kotlin.math.floor(span / step + 1e-6).toInt()
        return (0..n).map { Math.round((1.0 + it * step) * 1000.0) / 1000.0 }
    }
}

/**
 * Single-night observability diagram: airmass (or altitude) of one or several targets and of the Moon
 * against time, on a background shaded by the Sun's altitude (day, civil/nautical/astronomical
 * twilight, night). All [NightResult]s must be for the same site, night and options.
 */
class NightChartRenderer(var palette: ChartPalette, private val dp: Float, private val sp: Float) {
    var plotL = 0f
        private set
    var plotT = 0f
        private set
    var plotR = 0f
        private set
    var plotB = 0f
        private set
    private var tStart = 0L
    private var tEnd = 1L

    /** Largest airmass shown in airmass mode (raised to the airmass limit if that is larger). */
    var maxAirmass = 2.5

    fun xOf(ms: Long): Float = plotL + (plotR - plotL) * ((ms - tStart).toDouble() / (tEnd - tStart)).toFloat()

    fun timeAt(x: Float): Long? =
        if (plotR > plotL && x >= plotL - 1 && x <= plotR + 1) {
            tStart + ((x.coerceIn(plotL, plotR) - plotL) / (plotR - plotL) * (tEnd - tStart)).toLong()
        } else {
            null
        }

    private class LegendItem(val width: Float, val draw: (Float, Float) -> Unit)

    fun draw(
        sink: DrawSink,
        width: Float,
        height: Float,
        results: List<NightResult>,
        active: Int,
        mode: YAxisMode,
        displayZone: ZoneId,
        cursor: Long? = null,
        now: Long? = null,
    ) {
        val p = palette
        sink.fillRect(0f, 0f, width, height, p.surface)
        if (results.isEmpty()) return
        val r = results[0] // Sun, Moon and time grid are common to all targets
        val model = r.options.airmassModel
        val fs = 11f * sp
        val fsSmall = 9.5f * sp
        val fsTitle = 13f * sp
        val illum = r.moonPhase.illuminated
        val pct = "${Math.round(illum * 100)}%"
        val eph = Ephemeris(r.site, r.target, model)

        // ---- legend items (laid out first: their height sets the top of the plot)
        val items = ArrayList<LegendItem>()
        val swatch = 18f * dp
        val lineY = -fsSmall * 0.35f
        for ((i, t) in results.withIndex()) {
            val name = ChartText.ellipsize(sink, t.target.name, width * 0.4f, fsSmall, i == active)
            val w = swatch + 4f * dp + sink.textWidth(name, fsSmall, i == active)
            items.add(LegendItem(w) { x, y ->
                sink.line(x, y + lineY, x + swatch, y + lineY, p.seriesColor(i), 2.2f * dp, p.seriesDash(i, dp))
                sink.text(name, x + swatch + 4f * dp, y, fsSmall, if (i == active) p.textPrimary else p.textSecondary,
                    TextAlign.LEFT, i == active)
            })
        }
        val obsLabel = "observable: dark, X ≤ ${ChartText.airmassLimit(r.options.airmassLimit)}"
        items.add(LegendItem(swatch + 4f * dp + sink.textWidth(obsLabel, fsSmall)) { x, y ->
            sink.line(x, y + lineY, x + swatch, y + lineY, p.textSecondary, 4.5f * dp)
            sink.text(obsLabel, x + swatch + 4f * dp, y, fsSmall, p.textSecondary)
        })
        val moonText = String.format(Locale.US, "Moon %s, %s, age %.1f d", pct, r.moonPhase.phaseName, r.moonAgeDays) +
            if (r.moonAlt.slice(r.plotFrom..r.plotTo).all { it < Levels.MOON_RISE_SET }) " (down)" else ""
        items.add(LegendItem(swatch + 18f * dp + sink.textWidth(moonText, fsSmall)) { x, y ->
            sink.line(x, y + lineY, x + swatch, y + lineY, p.moon, 2.2f * dp, moonDash())
            MoonGlyph.draw(sink, x + swatch + 8f * dp, y + lineY, 6f * dp, illum,
                eph.moonBrightLimbFromZenithDeg(r.midnight), p.moonLit, p.moonDark, p.moon)
            sink.text(moonText, x + swatch + 18f * dp, y, fsSmall, p.textSecondary)
        })
        val dateStr = "Night of " + DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.US).format(r.date)
        items.add(LegendItem(sink.textWidth(dateStr, fsSmall)) { x, y -> sink.text(dateStr, x, y, fsSmall, p.textMuted) })

        val rowH = 18f * dp
        val legendRows = ArrayList<ArrayList<LegendItem>>()
        var cur = ArrayList<LegendItem>()
        var used = 0f
        val avail = width - 16f * dp
        for (it in items) {
            if (cur.isNotEmpty() && used + 14f * dp + it.width > avail) {
                legendRows.add(cur)
                cur = ArrayList()
                used = 0f
            }
            used += (if (cur.isEmpty()) 0f else 14f * dp) + it.width
            cur.add(it)
        }
        if (cur.isNotEmpty()) legendRows.add(cur)

        plotL = 44f * dp
        plotR = width - 44f * dp
        plotT = 34f * dp + legendRows.size * rowH + 16f * dp
        plotB = height - 38f * dp
        tStart = r.plotStart
        tEnd = r.plotEnd
        if (plotR - plotL < 40f || plotB - plotT < 40f) return
        val ph = plotB - plotT
        val xMax = ChartText.axisMaxAirmass(maxAirmass, r.options.airmassLimit)

        fun yOfAlt(alt: Double): Float = when (mode) {
            YAxisMode.AIRMASS -> {
                val x = model.airmass(alt)
                val xx = if (x.isInfinite() || x > 40.0) 40.0 else x
                plotT + ((xx - 1.0) / (xMax - 1.0) * ph).toFloat()
            }
            YAxisMode.ALTITUDE -> plotB - (alt / 90.0 * ph).toFloat()
        }

        val from = r.plotFrom
        val to = r.plotTo
        val t = r.times

        // ---- twilight background
        sink.save()
        sink.clipRect(plotL, plotT, plotR, plotB)
        fun shade(alt: Double): Int = when {
            alt >= Levels.SUN_RISE_SET -> p.day
            alt >= Levels.CIVIL -> p.civil
            alt >= Levels.NAUTICAL -> p.nautical
            alt >= Levels.ASTRONOMICAL -> p.astro
            else -> p.night
        }
        var runStart = from
        for (i in from + 1..to + 1) {
            if (i > to || shade(r.sunAlt[i]) != shade(r.sunAlt[runStart])) {
                val x0 = if (runStart == from) plotL else (xOf(t[runStart - 1]) + xOf(t[runStart])) / 2
                val x1 = if (i > to) plotR else (xOf(t[i - 1]) + xOf(t[i])) / 2
                sink.fillRect(x0, plotT, x1 + 0.5f, plotB, shade(r.sunAlt[runStart]))
                runStart = i
            }
        }

        // ---- grid
        val hourStep = pickHourStep((plotR - plotL) / ((tEnd - tStart) / MS_PER_HOUR.toFloat()))
        val hourTicks = hourTicks(tStart, tEnd, displayZone, hourStep)
        for (h in hourTicks) sink.line(xOf(h), plotT, xOf(h), plotB, p.grid, 1f)
        val leftTicks: List<Pair<Float, String>>
        val rightTicks: List<Pair<Float, String>>
        when (mode) {
            YAxisMode.AIRMASS -> {
                leftTicks = ChartText.airmassTicks(xMax, ph, 20f * dp).map { x ->
                    (plotT + ((x - 1.0) / (xMax - 1.0) * ph).toFloat()) to ChartText.airmassLimit(x)
                }
                rightTicks = listOf(90, 60, 50, 40, 35, 30, 25, 20).map { yOfAlt(it.toDouble()) to "$it°" }
            }
            YAxisMode.ALTITUDE -> {
                val step = if (ph / 9f >= 16f * dp) 10 else 30
                leftTicks = (0..90 step step).map { yOfAlt(it.toDouble()) to "$it°" }
                rightTicks = listOf(1.0, 1.1, 1.2, 1.5, 2.0, 3.0, 5.0).map {
                    yOfAlt(model.altitudeForAirmass(it)) to (if (it < 5.0) String.format(Locale.US, "%.1f", it) else "5")
                }
            }
        }
        for ((y, _) in leftTicks) sink.line(plotL, y, plotR, y, p.grid, 1f)

        // ---- Sun events (vertical lines with rotated labels)
        val ev = r.events
        val (darkStart, darkEnd) = when (r.options.dark) {
            DarkLimit.ASTRONOMICAL -> ev.astroDusk to ev.astroDawn
            DarkLimit.NAUTICAL -> ev.nauticalDusk to ev.nauticalDawn
            DarkLimit.CIVIL -> ev.civilDusk to ev.civilDawn
        }
        val dash = floatArrayOf(3f * dp, 3f * dp)
        for ((ms, name) in listOf(ev.sunset to "sunset", darkStart to "dark", darkEnd to "dark", ev.sunrise to "sunrise")) {
            if (ms == null || ms < tStart || ms > tEnd) continue
            val x = xOf(ms)
            sink.line(x, plotT, x, plotB, p.axis, 1f, dash)
            sink.textUp("$name ${ChartText.hm(ms, displayZone)}", x - 3f * dp, plotB - 4f * dp, fsSmall, p.textMuted)
        }

        // ---- airmass limit: thin solid line, labelled just inside the end of dark time, left of the rotated
        // "dark" label, so that it cannot run into the sunrise / dark event labels near the right edge
        val yLim = yOfAlt(r.options.altitudeLimitDeg)
        sink.line(plotL, yLim, plotR, yLim, Colors.alpha(p.limit, 0.75f), 1f * dp)
        val limLabel = "X = ${ChartText.airmassLimit(r.options.airmassLimit)}"
        val limW = sink.textWidth(limLabel, fsSmall)
        val eventsRight = listOfNotNull(darkEnd, ev.sunrise).filter { it in tStart..tEnd }.map { xOf(it) }
        var limX = plotR - 4f * dp
        val firstEvent = eventsRight.minOrNull()
        if (firstEvent != null && firstEvent - 3f * dp - fsSmall - 6f * dp - limW > plotL + 4f * dp) {
            limX = firstEvent - 3f * dp - fsSmall - 6f * dp
        }
        sink.text(limLabel, limX, yLim - 3f * dp, fsSmall, p.textSecondary, TextAlign.RIGHT)

        // ---- Moon track (dotted)
        val n = to - from + 1
        val xs = FloatArray(n) { xOf(t[from + it]) }
        val ysMoon = FloatArray(n) { yOfAlt(r.moonAlt[from + it]) }
        sink.polyline(xs, ysMoon, 0, n, p.moon, 2.2f * dp, moonDash())

        // ---- target tracks (active one last, on top); observable stretches thicker
        val order = results.indices.filter { it != active } + listOf(active).filter { it in results.indices }
        val ys = Array(results.size) { k -> FloatArray(n) { yOfAlt(results[k].objAlt[from + it]) } }
        for (k in order) {
            val rk = results[k]
            val c = p.seriesColor(k)
            val d = p.seriesDash(k, dp)
            sink.polyline(xs, ys[k], 0, n, c, if (k == active) 2.2f * dp else 1.8f * dp, d)
            for (iv in rk.observable) {
                val a = (rk.indexAt(iv.start) - from).coerceIn(0, n - 1)
                val b = (rk.indexAt(iv.end) - from).coerceIn(0, n - 1)
                if (b > a) sink.polyline(xs, ys[k], a, b + 1, c, 4.5f * dp, d)
            }
        }
        sink.restore()

        // ---- direct labels at each target's highest visible point (up to 4 targets), avoiding overlaps
        val placed = ArrayList<FloatArray>()
        if (results.size <= 4) {
            for (k in order.reversed()) {
                val rk = results[k]
                var iTop = from
                for (i in from..to) if (rk.objAlt[i] > rk.objAlt[iTop]) iTop = i
                val yTop = yOfAlt(rk.objAlt[iTop])
                if (yTop !in plotT..plotB) continue
                val label = ChartText.ellipsize(sink, rk.target.name, (plotR - plotL) / 3f, fs, true)
                val w = sink.textWidth(label, fs, true)
                val lx = xOf(t[iTop]).coerceIn(plotL + w / 2 + 2f, plotR - w / 2 - 2f)
                var ly = if (yTop - 8f * dp < plotT + fs) yTop + fs + 8f * dp else yTop - 8f * dp
                var tries = 0
                while (tries < 6 && placed.any { b -> lx + w / 2 > b[0] && lx - w / 2 < b[2] && ly > b[1] && ly - fs < b[3] }) {
                    ly += fs * 1.2f
                    tries++
                }
                placed.add(floatArrayOf(lx - w / 2, ly - fs, lx + w / 2, ly))
                sink.text(label, lx, ly, fs, p.seriesColor(k), TextAlign.CENTER, true)
            }
        }

        // ---- Moon glyph on its track at the highest point (skipped in the bottom fifth, where the event
        // labels are; the legend always carries the phase)
        var iMoon = from
        for (i in from..to) if (r.moonAlt[i] > r.moonAlt[iMoon]) iMoon = i
        val moonR = 9f * dp
        val yMoonTop = yOfAlt(r.moonAlt[iMoon])
        if (r.moonAlt[iMoon] > 0 && yMoonTop in plotT..(plotB - 0.2f * ph)) {
            val cx = xOf(t[iMoon]).coerceIn(plotL + moonR + 2f, plotR - moonR - 2f)
            val cy = yMoonTop.coerceIn(plotT + moonR + 2f, plotB - moonR - 2f)
            MoonGlyph.draw(sink, cx, cy, moonR, illum, eph.moonBrightLimbFromZenithDeg(t[iMoon]), p.moonLit, p.moonDark, p.moon)
        }

        // ---- now marker
        if (now != null && now in tStart..tEnd) {
            val x = xOf(now)
            sink.line(x, plotT, x, plotB, p.now, 1.5f * dp)
            sink.text("now", x + 3f * dp, plotB - 4f * dp, fsSmall, p.now)
        }

        // ---- frame and axes
        sink.strokeRect(plotL, plotT, plotR, plotB, p.axis, 1f)
        for ((y, s) in leftTicks) {
            sink.line(plotL - 4f * dp, y, plotL, y, p.axis, 1f)
            sink.text(s, plotL - 6f * dp, y + fs * 0.35f, fs, p.textSecondary, TextAlign.RIGHT)
        }
        var lastY = Float.NEGATIVE_INFINITY
        for ((y, s) in rightTicks) {
            if (y < plotT - 1 || y > plotB + 1 || y - lastY < fs * 1.05f) continue
            sink.line(plotR, y, plotR + 4f * dp, y, p.axis, 1f)
            sink.text(s, plotR + 6f * dp, y + fs * 0.35f, fs, p.textSecondary)
            lastY = y
        }
        val leftTitle = mode.label
        val rightTitle = if (mode == YAxisMode.AIRMASS) "Altitude" else "Airmass"
        sink.textUp(leftTitle, 12f * dp, (plotT + plotB) / 2 + sink.textWidth(leftTitle, fs) / 2, fs, p.textMuted)
        sink.textUp(rightTitle, width - 4f * dp, (plotT + plotB) / 2 + sink.textWidth(rightTitle, fs) / 2, fs, p.textMuted)

        for (h in hourTicks) {
            val x = xOf(h)
            sink.line(x, plotB, x, plotB + 4f * dp, p.axis, 1f)
            sink.text(ChartText.hm(h, displayZone).substring(0, 2), x, plotB + 4f * dp + fs, fs, p.textSecondary, TextAlign.CENTER)
        }
        val tz = ChartText.zoneLabel(displayZone, r.midnight)
        sink.text(when {
            tz == "UT" -> "Universal time (UT)"
            ChartText.shortZone(displayZone, r.site.zone) == "local" -> "Local time ($tz)"
            else -> "Time ($tz)"
        }, (plotL + plotR) / 2, height - 6f * dp,
            fs, p.textMuted, TextAlign.CENTER)

        for (ms in lstTicks(r, hourStep)) {
            val x = xOf(ms.first)
            sink.line(x, plotT - 4f * dp, x, plotT, p.axis, 1f)
            sink.text(ms.second, x, plotT - 6f * dp, fsSmall, p.textMuted, TextAlign.CENTER)
        }
        sink.text("LST", plotL - 6f * dp, plotT - 6f * dp, fsSmall, p.textMuted, TextAlign.RIGHT)

        // ---- title and legend rows
        val title = if (results.size == 1) "${r.target.name} · ${r.site.name}" else "${r.site.name} · ${results.size} targets"
        sink.text(ChartText.ellipsize(sink, title, width - 16f * dp, fsTitle, true), 8f * dp, 18f * dp, fsTitle,
            p.textPrimary, TextAlign.LEFT, true)
        for ((ri, row) in legendRows.withIndex()) {
            var x = 8f * dp
            val y = 38f * dp + ri * rowH
            for (it in row) {
                it.draw(x, y)
                x += it.width + 14f * dp
            }
        }

        // ---- cursor read-out
        if (cursor != null && cursor in tStart..tEnd) {
            drawCursor(sink, results, active, cursor, ::yOfAlt, fsSmall, displayZone, mode, width)
        }
    }

    private fun moonDash() = floatArrayOf(1.2f * dp, 3.2f * dp)

    /**
     * Cursor read-out: a vertical line, a point on every curve with the target's airmass written next to it, and a box
     * with the time and each target's altitude, azimuth and Moon distance. A target whose point cannot be labelled
     * (off the diagram, or too close to another point) gets its airmass in the box instead, so every airmass is shown
     * exactly once.
     */
    private fun drawCursor(
        sink: DrawSink,
        results: List<NightResult>,
        active: Int,
        cursor: Long,
        yOfAlt: (Double) -> Float,
        fsz: Float,
        displayZone: ZoneId,
        mode: YAxisMode,
        width: Float,
    ) {
        val p = palette
        val r = results[0]
        val i = r.indexAt(cursor)
        val ms = r.times[i]
        val x = xOf(ms)
        sink.line(x, plotT, x, plotB, Colors.alpha(p.textPrimary, 0.7f), 1f * dp)
        val dots = ArrayList<Pair<Int, Float>>() // (target, y) of the points shown on the curves
        for ((k, rk) in results.withIndex()) {
            val yo = yOfAlt(rk.objAlt[i])
            if (yo in plotT..plotB) {
                sink.fillCircle(x, yo, if (k == active) 4f * dp else 3f * dp, p.seriesColor(k))
                dots.add(k to yo)
            }
        }
        val ym = yOfAlt(r.moonAlt[i])
        val moonShown = ym in plotT..plotB
        if (moonShown) sink.fillCircle(x, ym, 3.5f * dp, p.moon)

        // ---- box text
        val utc = ZoneId.of("UTC")
        // The chosen time scale first, then UT (unless that is the chosen one) and LST.
        val chosen = "${ChartText.hm(ms, displayZone)} ${ChartText.shortZone(displayZone, r.site.zone)}"
        val head = if (ChartText.isUt(displayZone)) chosen else "$chosen · ${ChartText.hm(ms, utc)} UT"
        val headLine = "$head · LST ${ChartText.lstHm(r.lst[i])}"
        val foot = String.format(Locale.US, "Moon alt %.1f°  ·  Sun alt %.1f°", r.moonAlt[i], r.sunAlt[i])
        fun build(withAirmass: Set<Int>, compact: Boolean): List<Pair<String, Int>> {
            val out = ArrayList<Pair<String, Int>>()
            out.add(headLine to p.textPrimary)
            for ((k, rk) in results.withIndex()) {
                for (line in ChartText.cursorLines(rk.target.name, rk.objAlt[i], rk.airmass[i], rk.objAz[i], rk.moonSep[i],
                        k in withAirmass, compact)) {
                    out.add(line to p.seriesColor(k))
                }
            }
            out.add(foot to p.textSecondary)
            return out
        }

        // ---- box geometry for a given content
        val pad = 6f * dp
        val availW = width - 4f * dp
        val availH = plotB - plotT - 8f * dp
        val labelTexts = dots.mapNotNull { (k, _) -> ChartText.airmassValue(results[k].objAlt[i], results[k].airmass[i]) }
        val zone = 12f * dp + (labelTexts.maxOfOrNull { sink.textWidth(it, fsz, true) } ?: 0f)
        class Box(val lines: List<Pair<String, Int>>, val fs: Float, val l: Float, val t: Float, val w: Float, val h: Float) {
            val rect get() = floatArrayOf(l, t, l + w, t + h)
        }
        val gap = 8f * dp
        val edge = 2f * dp
        // Left edge of a box of width w: beside the cursor, inside the diagram if possible, else reaching over the
        // axis labels (null if it fits on neither side).
        fun besideX(w: Float): Float? = when {
            x + gap + w <= plotR -> x + gap
            x - gap - w >= plotL -> x - gap - w
            x + gap + w <= width - edge -> x + gap
            x - gap - w >= edge -> x - gap - w
            else -> null
        }
        fun geometry(ls: List<Pair<String, Int>>): Pair<Box, Boolean> {
            val textW = ls.maxOf { sink.textWidth(it.first, fsz) }
            // Narrow screens or large system fonts: shrink the text so that the whole box stays on screen ...
            var scale = minOf(1f, (availW - 2 * pad) / textW, (availH - 8f * dp) / (ls.size * fsz * 1.35f)).coerceAtLeast(0.65f)
            // ... and a little more if that lets it sit beside the cursor instead of over the points.
            if (besideX(textW * scale + 2 * pad) == null) {
                val side = maxOf(x - gap - edge, width - edge - x - gap)
                val s2 = (side - 2 * pad) / textW
                if (s2 >= 0.7f) scale = minOf(scale, s2 - 0.001f)
            }
            val f = fsz * scale
            val w = textW * scale + 2 * pad
            val h = ls.size * f * 1.35f + 8f * dp
            val near = besideX(w)
            val bx = near ?: run {
                val fits = w <= plotR - plotL - 4f
                val lo = if (fits) plotL + 2f else edge
                val hi = if (fits) plotR - 2f - w else width - edge - w
                (x - w / 2).coerceIn(lo, maxOf(lo, hi))
            }
            // At the top of the diagram, unless the box reaches over the cursor and would hide the points there:
            // then where it hides the fewest of them (the active target's point counts double). A box taller than
            // the diagram ends at its bottom and reaches up over the legend.
            val by = when {
                h > plotB - plotT - 8f * dp -> (plotB - 4f * dp - h).coerceAtLeast(edge)
                bx > x + zone || bx + w < x - zone -> plotT + 4f * dp
                else -> listOf(plotT + 4f * dp, plotB - 4f * dp - h, (plotT + plotB - h) / 2f).minByOrNull { t ->
                    dots.sumOf { (k, yo) -> if (yo > t - fsz - 8f * dp && yo < t + h + fsz + 8f * dp) (if (k == active) 2 else 1) else 0 }
                } ?: (plotT + 4f * dp)
            }
            return Box(ls, f, bx, by, w, h) to (near != null)
        }
        // One line per target if that fits beside the cursor; otherwise two lines per target, narrower.
        fun layout(withAirmass: Set<Int>): Box {
            val wide = build(withAirmass, false)
            val oneLine = if (wide.maxOf { sink.textWidth(it.first, fsz) } + 2 * pad <= availW) geometry(wide) else null
            if (oneLine != null && oneLine.second) return oneLine.first
            val twoLines = geometry(build(withAirmass, true))
            return if (twoLines.second || oneLine == null) twoLines.first else oneLine.first
        }

        // ---- labels next to the points (active target first), clear of the box, the other points and each other.
        // A target whose point cannot be labelled has its airmass in the box; that widens the box, which may then
        // displace other labels, so this is repeated until it settles (it ends: the box content only ever grows).
        fun overlaps(a: FloatArray, b: FloatArray) = a[0] < b[2] && a[2] > b[0] && a[1] < b[3] && a[3] > b[1]
        val dotR = 4f * dp
        val fixed = ArrayList<FloatArray>()
        if (moonShown) fixed.add(floatArrayOf(x - dotR, ym - dotR, x + dotR, ym + dotR))
        val labels = LinkedHashMap<Int, FloatArray>() // target -> (x, baseline, rect l, t, r, b)
        val labelText = HashMap<Int, String>()
        fun place(k: Int, yo: Float, box: FloatArray): Boolean {
            val v = labelText[k] ?: return false
            val tw = sink.textWidth(v, fsz, true)
            // Beside the point, right then left; if those are taken, one or two label heights higher or lower
            // (the colour still ties the label to its curve).
            val step = fsz + 5f * dp
            val candidates = ArrayList<Pair<Float, Float>>()
            for (shift in listOf(0f, -1f, 1f, -2f, 2f)) {
                candidates.add(x + 7f * dp to yo + fsz * 0.35f + shift * step)
                candidates.add(x - 7f * dp - tw to yo + fsz * 0.35f + shift * step)
            }
            val others = dots.filter { it.first != k }.map { floatArrayOf(x - dotR, it.second - dotR, x + dotR, it.second + dotR) } +
                labels.filterKeys { it != k }.values.map { floatArrayOf(it[2], it[3], it[4], it[5]) } + fixed + listOf(box)
            for ((lx, base) in candidates) {
                val rect = floatArrayOf(lx - 3f * dp, base - fsz - 1f * dp, lx + tw + 3f * dp, base + 3f * dp)
                if (rect[0] < plotL || rect[2] > plotR || rect[1] < plotT || rect[3] > plotB) continue
                if (others.any { overlaps(it, rect) }) continue
                labels[k] = floatArrayOf(lx, base, rect[0], rect[1], rect[2], rect[3])
                return true
            }
            labels.remove(k)
            return false
        }
        for ((k, _) in dots) ChartText.airmassValue(results[k].objAlt[i], results[k].airmass[i])?.let { labelText[k] = it }
        val ordered = dots.sortedBy { if (it.first == active) -1 else it.first }
        fun unlabelled() = results.indices.filter { k ->
            ChartText.airmassValue(results[k].objAlt[i], results[k].airmass[i]) != null && k !in labels
        }.toSet()
        var content = results.indices.filter { k ->
            ChartText.airmassValue(results[k].objAlt[i], results[k].airmass[i]) != null && k !in labelText
        }.toSet()
        var box = layout(content)
        for ((k, yo) in ordered) place(k, yo, box.rect)
        var rounds = 0
        while (rounds++ <= results.size) {
            val need = unlabelled()
            if (need == content) break
            content = need
            box = layout(content)
            for ((k, yo) in ordered) {
                val g = labels[k] ?: continue
                if (overlaps(box.rect, floatArrayOf(g[2], g[3], g[4], g[5]))) place(k, yo, box.rect)
            }
        }

        for ((k, g) in labels) {
            sink.fillRect(g[2], g[3], g[4], g[5], p.tooltip)
            sink.text(labelText[k]!!, g[0], g[1], fsz, p.seriesColor(k), TextAlign.LEFT, true)
        }
        sink.fillRect(box.l, box.t, box.l + box.w, box.t + box.h, p.tooltip)
        sink.strokeRect(box.l, box.t, box.l + box.w, box.t + box.h, Colors.alpha(p.textPrimary, 0.3f), 1f)
        for ((k, s) in box.lines.withIndex()) {
            sink.text(s.first, box.l + pad, box.t + 4f * dp + box.fs * (1.0f + 1.35f * k), box.fs,
                if (k == 0 || k == box.lines.size - 1) s.second else Colors.mix(s.second, p.textPrimary, 0.35f))
        }
    }

    private fun pickHourStep(pxPerHour: Float): Int = when {
        pxPerHour >= 26f * dp -> 1
        pxPerHour >= 13f * dp -> 2
        pxPerHour >= 9f * dp -> 3
        else -> 6
    }

    /** Whole hours of the display zone in [t0, t1], every [step] hours. */
    private fun hourTicks(t0: Long, t1: Long, zone: ZoneId, step: Int): List<Long> {
        val out = ArrayList<Long>()
        var z = Instant.ofEpochMilli(t0).atZone(zone).withMinute(0).withSecond(0).withNano(0)
        if (z.toInstant().toEpochMilli() < t0) z = z.plusHours(1)
        while (z.toInstant().toEpochMilli() <= t1) {
            if (z.hour % step == 0) out.add(z.toInstant().toEpochMilli())
            z = z.plusHours(1)
        }
        return out
    }

    /** Instants at which the local sidereal time passes whole hours (every [step]). */
    private fun lstTicks(r: NightResult, step: Int): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        for (i in r.plotFrom + 1..r.plotTo) {
            val a = r.lst[i - 1] / 15.0
            var b = r.lst[i] / 15.0
            if (b < a) b += 24.0
            val h = ceil(a)
            if (h <= b && floor(a) != floor(b)) {
                val hh = (h.toInt() % 24)
                if (hh % step != 0) continue
                val f = (h - a) / (b - a)
                val ms = r.times[i - 1] + ((r.times[i] - r.times[i - 1]) * f).toLong()
                out.add(ms to String.format(Locale.US, "%02d", hh))
            }
        }
        return out
    }
}
