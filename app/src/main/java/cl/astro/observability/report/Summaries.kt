package cl.astro.observability.report

import cl.astro.observability.astro.DarkLimit
import cl.astro.observability.astro.Interval
import cl.astro.observability.astro.Levels
import cl.astro.observability.astro.NightResult
import cl.astro.observability.astro.NightRun
import cl.astro.observability.astro.SeasonResult
import cl.astro.observability.astro.Target
import cl.astro.observability.astro.WindowResult
import cl.astro.observability.chart.ChartText
import cl.astro.observability.data.CoordinateParser
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Plain-text summaries shown under the diagrams. */
object Summaries {
    private val dateFmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.US)

    fun describeTarget(t: Target): String {
        val coords = "RA ${CoordinateParser.formatRa(t.raDeg)}  Dec ${CoordinateParser.formatDec(t.decDeg)} (J2000)"
        val parts = mutableListOf(t.name)
        if (t.type.isNotEmpty()) parts.add(t.type)
        val others = t.aliases.filter { it != t.name }.take(3)
        val head = if (others.isEmpty()) parts.joinToString(" · ") else parts.joinToString(" · ") + " · " + others.joinToString(", ")
        val pm = if (t.pmRaCosDecMasYr != 0.0 || t.pmDecMasYr != 0.0) {
            String.format(Locale.US, "\nProper motion %.1f, %.1f mas/yr", t.pmRaCosDecMasYr, t.pmDecMasYr)
        } else {
            ""
        }
        return "$head\n$coords$pm\nSource: ${t.source}"
    }

    private fun hours(h: Double) = String.format(Locale.US, "%.1f h", h)

    private fun intervals(list: List<Interval>, zone: ZoneId): String =
        list.joinToString(", ") { "${ChartText.hm(it.start, zone)}–${ChartText.hm(it.end, zone)}" }

    fun night(r: NightResult, zone: ZoneId): String {
        fun t(ms: Long?) = if (ms == null) "—" else ChartText.hm(ms, zone)
        fun inWindow(list: List<Long>) = list.filter { it in r.windowStart..r.windowEnd }
        val ev = r.events
        val (ds, de) = when (r.options.dark) {
            DarkLimit.ASTRONOMICAL -> ev.astroDusk to ev.astroDawn
            DarkLimit.NAUTICAL -> ev.nauticalDusk to ev.nauticalDawn
            DarkLimit.CIVIL -> ev.civilDusk to ev.civilDawn
        }
        val sb = StringBuilder()
        sb.append("Night of ${dateFmt.format(r.date)} · ${r.site.name}\n")
        sb.append("Times in ${ChartText.zoneLabel(zone, r.midnight)}\n\n")

        // Sun
        when {
            ev.sunset != null && ev.sunrise != null -> {
                sb.append("Sun: sets ${t(ev.sunset)}, rises ${t(ev.sunrise)}. ")
                if (ds != null && de != null) {
                    sb.append("Dark (Sun < ${r.options.dark.sunAltDeg.toInt()}°) ${t(ds)}–${t(de)}, ${hours(r.darkHours)}.\n")
                } else {
                    sb.append("The Sun does not reach ${r.options.dark.sunAltDeg.toInt()}°: no dark time.\n")
                }
            }
            r.sunAlt.all { it > Levels.SUN_RISE_SET } -> sb.append("Sun: above the horizon all night (midnight Sun).\n")
            else -> sb.append("Sun: below the horizon all day (polar night); dark ${hours(r.darkHours)}.\n")
        }

        // Target
        val tr = r.events.transit
        val trX = r.options.airmassModel.airmass(ev.transitAltDeg)
        val trXs = if (trX.isInfinite()) "below horizon" else String.format(Locale.US, "X %.2f", trX)
        sb.append(String.format(Locale.US, "%s: highest at %s, altitude %.1f° (%s)", r.target.name, t(tr), ev.transitAltDeg, trXs))
        val rises = inWindow(ev.objectRises)
        val sets = inWindow(ev.objectSets)
        when {
            rises.isEmpty() && sets.isEmpty() && r.objAlt.all { it > Levels.STAR_RISE_SET } -> sb.append("; always above the horizon")
            rises.isEmpty() && sets.isEmpty() -> sb.append("; never rises")
            else -> {
                if (rises.isNotEmpty()) sb.append("; rises ${rises.joinToString { t(it) }}")
                if (sets.isNotEmpty()) sb.append("; sets ${sets.joinToString { t(it) }}")
            }
        }
        sb.append(".\n")
        val lim = String.format(Locale.US, "%.2f", r.options.airmassLimit).trimEnd('0').trimEnd('.')
        if (r.observable.isNotEmpty()) {
            sb.append("Observable (dark, X ≤ $lim): ${intervals(r.observable, zone)} → ${hours(r.observableHours)}")
            sb.append("; with the Moon down: ${hours(r.observableMoonDownHours)}.\n")
        } else {
            val best = r.bestDarkAirmass
            sb.append("Not observable at X ≤ $lim in dark time")
            if (best.isFinite()) sb.append(String.format(Locale.US, " (best airmass in the dark %.2f)", best))
            else sb.append(" (below the horizon during dark time)")
            sb.append(".\n")
        }

        // Moon
        val ph = r.moonPhase
        sb.append("Moon: ${Math.round(ph.illuminated * 100)}% illuminated, ${ph.phaseName}")
        sb.append(String.format(Locale.US, ", age %.1f days", r.moonAgeDays))
        val mr = inWindow(ev.moonrises)
        val ms = inWindow(ev.moonsets)
        if (mr.isEmpty() && ms.isEmpty()) {
            sb.append(if (r.moonAlt.all { it > Levels.MOON_RISE_SET }) "; up all night" else "; below the horizon all night")
        } else {
            if (mr.isNotEmpty()) sb.append("; rises ${mr.joinToString { t(it) }}")
            if (ms.isNotEmpty()) sb.append("; sets ${ms.joinToString { t(it) }}")
        }
        val (sMin, sMax) = r.moonSeparationRangeInDark()
        if (!sMin.isNaN()) {
            sb.append(String.format(Locale.US, ". Separation from %s %.0f°–%.0f° while it is up in the dark", r.target.name, sMin, sMax))
        }
        sb.append(".")
        return sb.toString()
    }

    fun window(r: WindowResult): String {
        val n = r.nights
        if (n.isEmpty()) return ""
        val good = n.filter { it.observableHours > 0.05 }
        val lim = String.format(Locale.US, "%.2f", r.options.airmassLimit).trimEnd('0').trimEnd('.')
        val sb = StringBuilder()
        sb.append("${n.size} nights from ${dateFmt.format(n.first().date)} to ${dateFmt.format(n.last().date)}. ")
        if (good.isEmpty()) {
            sb.append("${r.target.name} never reaches X ≤ $lim during dark time in this window.")
            return sb.toString()
        }
        val total = n.sumOf { it.observableHours }
        val totalMd = n.sumOf { it.observableMoonDownHours }
        sb.append("Observable (dark, X ≤ $lim) on ${good.size} nights, ${hours(total)} in total, ${hours(totalMd)} with the Moon down.")
        val best = n.maxByOrNull { it.observableHours }!!
        val bestMd = n.maxByOrNull { it.observableMoonDownHours }!!
        sb.append("\nLongest: ${dateFmt.format(best.date)} (${hours(best.observableHours)}).")
        if (bestMd.observableMoonDownHours > 0.05) {
            sb.append(" Longest with the Moon down: ${dateFmt.format(bestMd.date)} (${hours(bestMd.observableMoonDownHours)}).")
        }
        return sb.toString()
    }

    private val shortFmt = DateTimeFormatter.ofPattern("d MMM", Locale.US)
    private val longFmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)

    /** "20 Aug – 14 Nov 2026", "10 Nov 2026 – 25 Feb 2027". */
    fun dateRange(a: java.time.LocalDate, b: java.time.LocalDate): String =
        if (a.isEqual(b)) longFmt.format(a)
        else if (a.year == b.year) "${shortFmt.format(a)} – ${longFmt.format(b)}"
        else "${longFmt.format(a)} – ${longFmt.format(b)}"

    private fun runText(run: NightRun): String =
        if (run.ongoing) "now until ${longFmt.format(run.lastDate)}" else dateRange(run.firstDate, run.lastDate)

    /** The period to use as the date window: the first best observability window (from today if it is in progress). */
    fun suggestedWindow(r: SeasonResult): NightRun? = r.bestPeriods.firstOrNull()

    /** Text summary of the year scan. */
    fun season(r: SeasonResult, zone: ZoneId): String {
        val n = r.nights
        if (n.isEmpty()) return ""
        val lim = ChartText.airmassLimit(r.options.airmassLimit)
        val dark = "Sun < ${r.options.dark.sunAltDeg.toInt()}°"
        val sb = StringBuilder()
        if (!r.everObservable) {
            sb.append("${r.target.name} never reaches X ≤ $lim in dark time ($dark) at ${r.site.name} during the next ${n.size} nights.")
            val k = r.lowestAirmassNight
            if (k != null) {
                val nt = n[k]
                val alt = r.options.airmassModel.altitudeForAirmass(nt.bestAirmass)
                sb.append(String.format(Locale.US, " Lowest airmass in the dark: %.2f (altitude %.0f°)", nt.bestAirmass, alt))
                val runs = r.lowestAirmassRuns
                if (runs.isNotEmpty()) sb.append(", on nights ").append(runs.joinToString("; ") { runText(it) })
                sb.append(". Choose a larger airmass limit to see when it can be observed.")
            } else {
                val maxAlt = 90.0 - Math.abs(r.site.latDeg - r.target.decDeg)
                sb.append(if (maxAlt < 0.0) " It never rises at this latitude." else " It is below the horizon whenever it is dark.")
            }
            return sb.toString()
        }
        val best = r.bestPeriods
        val whole = best.size == 1 && best[0].first == 0 && best[0].last == n.size - 1
        sb.append("Best observability window (≥ ${(SeasonResult.BEST_FRACTION * 100).toInt()}% of the year's maximum of ")
        sb.append(String.format(Locale.US, "%.1f h per night at X ≤ %s, %s): ", r.maxObservableHours, lim, dark))
        if (whole) {
            sb.append("all year.")
        } else {
            sb.append(best.joinToString("; ") { runText(it) }).append(".")
        }
        val seasons = r.observableSeasons
        val wholeSeason = seasons.size == 1 && seasons[0].first == 0 && seasons[0].last == n.size - 1
        sb.append(String.format(Locale.US, "\nObservable at least %.1f h per night: ", r.seasonHours))
        sb.append(if (wholeSeason) "all year." else seasons.joinToString("; ") { runText(it) } + ".")
        r.midnightTransit?.let { k ->
            sb.append("\nOpposition (culminates at local midnight): ${longFmt.format(n[k].date)}.")
        }
        val upcoming = r.darkRuns.take(4)
        if (upcoming.isNotEmpty()) {
            sb.append("\nDarkest nights in the best window (Moon down most of the time): ")
            sb.append(upcoming.joinToString(", ") { dateRange(n[it.first].date, n[it.last].date) }).append(".")
        }
        r.peak?.let { k ->
            val nt = n[k]
            sb.append("\nBest single night: ${dateFmt.format(nt.date)} — ")
            sb.append(String.format(Locale.US, "%.1f h at X ≤ %s, %.1f h of them with the Moon down", nt.observableHours, lim,
                nt.moonDownHours))
            if (nt.bestAirmass.isFinite() && nt.bestTime != null) {
                sb.append(String.format(Locale.US, "; lowest airmass %.2f at %s", nt.bestAirmass, ChartText.hm(nt.bestTime, zone)))
            }
            sb.append(".")
        }
        return sb.toString()
    }
}
