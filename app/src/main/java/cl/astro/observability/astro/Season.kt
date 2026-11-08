package cl.astro.observability.astro

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.min

/** One night of the year scan. Hours are dark time (Sun below the chosen limit). */
class SeasonNight(
    val date: LocalDate,
    /** Local-mean-time midnight of the night. */
    val midnight: Long,
    val darkHours: Double,
    /** Dark time with airmass ≤ limit. */
    val observableHours: Double,
    /** Same, with the Moon below the horizon. */
    val moonDownHours: Double,
    /** Lowest airmass during dark time (∞ if the target stays below the horizon). */
    val bestAirmass: Double,
    /** Instant of [bestAirmass], null if never above the horizon in the dark. */
    val bestTime: Long?,
    /** Illuminated fraction of the Moon at midnight. */
    val moonIllumination: Double,
    /** Hour angle of the target at local-mean-time midnight, −180..180°. */
    val midnightHourAngleDeg: Double,
)

/**
 * A stretch of consecutive nights. [first]/[last] index the scan; [lastDate] can lie beyond the scan
 * when a season that is in progress today comes round again at the end of the scanned year.
 */
class NightRun(
    val first: Int,
    val last: Int,
    val firstDate: LocalDate,
    val lastDate: LocalDate,
    /** True for the run that includes the first night of the scan (in progress "now"). */
    val ongoing: Boolean,
) {
    val nights: Int get() = (lastDate.toEpochDay() - firstDate.toEpochDay()).toInt() + 1
}

/**
 * When the target is best observed from the site over the coming year.
 *
 * - [observableSeasons]: runs of nights with at least [seasonHours] h at X ≤ limit in dark time;
 * - [bestPeriods]: the best observability windows, runs with at least [BEST_FRACTION] of the year's
 *   maximum hours (the app's "Compute Best Observability Window" uses the first one);
 * - [darkRuns]: nights inside the best periods where the Moon is down for at least
 *   [MOON_DOWN_FRACTION] of the observable time (the best nights for faint objects);
 * - [peak]: the first night that comes within 10 min of the most Moon-free observable hours;
 * - [midnightTransit]: the night when the target culminates at local midnight ("opposition");
 * - [lowestAirmassNight]: the night with the lowest airmass in the dark (useful if never observable).
 *
 * The observable hours depend only on the Sun and the target, so they repeat from year to year: a
 * season that is in progress today reappears at the end of the scanned year, and the two pieces
 * are reported as the current season (ending soon) and the next one.
 */
class SeasonResult(
    val site: Site,
    val target: Target,
    val options: ObservabilityOptions,
    val nights: List<SeasonNight>,
) {
    val maxObservableHours: Double = nights.maxOfOrNull { it.observableHours } ?: 0.0
    val maxDarkHours: Double = nights.maxOfOrNull { it.darkHours } ?: 0.0
    val seasonHours: Double = min(1.0, 0.5 * maxObservableHours)
    val everObservable: Boolean = maxObservableHours >= MIN_HOURS

    val observableSeasons: List<NightRun> =
        if (everObservable) runs { nights[it].observableHours >= seasonHours } else emptyList()
    val bestPeriods: List<NightRun> =
        if (everObservable) runs { nights[it].observableHours >= BEST_FRACTION * maxObservableHours } else emptyList()

    private val inBest = BooleanArray(nights.size).also { a ->
        if (everObservable) for (i in nights.indices) a[i] = nights[i].observableHours >= BEST_FRACTION * maxObservableHours
    }

    /** Moon-free stretches inside the best periods (chronological, without wrap-around merging). */
    val darkRuns: List<IntRange> = linearRuns { i ->
        inBest[i] && nights[i].moonDownHours >= MOON_DOWN_FRACTION * nights[i].observableHours
    }

    val peak: Int? = run {
        val best = nights.maxOfOrNull { it.moonDownHours } ?: 0.0
        if (best < MIN_HOURS) null else nights.indexOfFirst { it.moonDownHours >= best - PEAK_TOLERANCE_H }
    }

    val midnightTransit: Int? = run {
        var bestK = -1
        for (k in nights.indices) {
            if (bestK < 0 || abs(nights[k].midnightHourAngleDeg) < abs(nights[bestK].midnightHourAngleDeg)) bestK = k
        }
        // A scan shorter than a year may not contain it; accept only a true minimum (within ~1 day of motion).
        if (bestK < 0 || abs(nights[bestK].midnightHourAngleDeg) > 1.0) null else bestK
    }

    /**
     * Night with the lowest airmass in dark time. The culmination airmass is reached on many nights,
     * so among the nights within 1 % of the minimum the one closest to opposition is taken.
     */
    val lowestAirmassNight: Int? = run {
        val m = nights.minOfOrNull { it.bestAirmass } ?: Double.POSITIVE_INFINITY
        if (!m.isFinite()) return@run null
        var k = -1
        for (i in nights.indices) {
            if (nights[i].bestAirmass <= m * 1.01 &&
                (k < 0 || abs(nights[i].midnightHourAngleDeg) < abs(nights[k].midnightHourAngleDeg))
            ) k = i
        }
        k
    }

    /** Nights on which the lowest airmass in the dark is within 1 % of the year's minimum. */
    val lowestAirmassRuns: List<NightRun> = run {
        val m = nights.minOfOrNull { it.bestAirmass } ?: Double.POSITIVE_INFINITY
        if (!m.isFinite()) emptyList() else runs { nights[it].bestAirmass <= m * 1.01 }
    }

    /** Index of the night of [date], or null if outside the scan. */
    fun indexOf(date: LocalDate): Int? {
        if (nights.isEmpty()) return null
        val k = (date.toEpochDay() - nights.first().date.toEpochDay()).toInt()
        return if (k in nights.indices) k else null
    }

    private fun linearRuns(pred: (Int) -> Boolean): List<IntRange> {
        val out = ArrayList<IntRange>()
        var i = 0
        while (i < nights.size) {
            if (!pred(i)) {
                i++
                continue
            }
            var j = i
            while (j + 1 < nights.size && pred(j + 1)) j++
            out.add(i..j)
            i = j + 1
        }
        return mergeShortGaps(out)
    }

    /** Joins runs separated by gaps of at most [MAX_GAP] nights (threshold chatter near the edges). */
    private fun mergeShortGaps(list: List<IntRange>): List<IntRange> {
        val out = ArrayList<IntRange>()
        for (r in list) {
            val prev = out.lastOrNull()
            if (prev != null && r.first - prev.last - 1 <= MAX_GAP) out[out.size - 1] = prev.first..r.last else out.add(r)
        }
        return out
    }

    private fun runs(pred: (Int) -> Boolean): List<NightRun> {
        val lin = linearRuns(pred)
        if (lin.isEmpty()) return emptyList()
        val n = nights.size
        val date = { i: Int -> nights[i].date }
        val coversYear = n >= 360
        if (lin.size == 1 && lin[0].first == 0 && lin[0].last == n - 1) {
            return listOf(NightRun(0, n - 1, date(0), date(n - 1), ongoing = true))
        }
        val out = ArrayList<NightRun>()
        for (r in lin) {
            val ongoing = r.first == 0
            if (coversYear && r.last == n - 1 && lin.first().first == 0 && lin.size > 1) {
                // The season in progress now comes round again: it ends where the current one ends, a year later.
                val again = lin.first()
                out.add(NightRun(r.first, r.last, date(r.first), date(again.last).plusYears(1), ongoing = false))
            } else {
                out.add(NightRun(r.first, r.last, date(r.first), date(r.last), ongoing))
            }
        }
        return out
    }

    companion object {
        const val BEST_FRACTION = 0.8
        const val MOON_DOWN_FRACTION = 0.8
        const val MIN_HOURS = 0.25
        const val PEAK_TOLERANCE_H = 1.0 / 6.0
        const val MAX_GAP = 3
    }
}

/**
 * Scans [nights] nights from [start] (default a year) and finds the best observing periods.
 * Sampling every [stepMinutes] over local-mean-time noon → noon; the Moon is only computed where
 * the target is observable (or next to such a sample), which keeps a year scan to about a second.
 */
class SeasonPlanner(val site: Site, val target: Target, val options: ObservabilityOptions) {
    private val ephemeris = Ephemeris(site, target, options.airmassModel)
    private val calc = ObservabilityCalculator(site, target, options)

    fun scan(
        start: LocalDate,
        nights: Int = 365,
        stepMinutes: Int = 10,
        progress: ((Int, Int) -> Unit)? = null,
        cancelled: (() -> Boolean)? = null,
    ): SeasonResult? {
        val step = stepMinutes * MS_PER_MINUTE
        val n = (24 * 60 / stepMinutes) + 1
        val darkLevel = options.dark.sunAltDeg
        val altLimit = options.altitudeLimitDeg
        val out = ArrayList<SeasonNight>(nights)
        val times = LongArray(n)
        val sunAlt = DoubleArray(n)
        val objAlt = DoubleArray(n)
        val moonAlt = DoubleArray(n)
        val ctxs = arrayOfNulls<SkyContext>(n)
        for (k in 0 until nights) {
            if (cancelled?.invoke() == true) return null
            val date = start.plusDays(k.toLong())
            val mid = calc.lmtMidnight(date)
            val t0 = mid - 12 * MS_PER_HOUR
            val midCtx = SkyContext(mid)
            val midTarget = ephemeris.targetApparent(midCtx)
            val midHa = norm180(midCtx.lastDeg(site.lonDeg) - midTarget.lonDeg)
            for (i in 0 until n) {
                val t = t0 + i * step
                times[i] = t
                val ctx = SkyContext(t)
                ctxs[i] = ctx
                val last = ctx.lastDeg(site.lonDeg)
                val sun = ctx.sun
                sunAlt[i] = Horizon.altAz(sun.raDeg, sun.decDeg, last, site.latDeg).altDeg
                val u = ephemeris.targetApparent(ctx)
                val aa = Horizon.altAz(u.lonDeg, u.latDeg, last, site.latDeg)
                objAlt[i] = aa.altDeg
            }
            val darkSlack = DoubleArray(n) { darkLevel - sunAlt[it] }
            val obsSlack = DoubleArray(n) { min(darkSlack[it], objAlt[it] - altLimit) }
            for (i in 0 until n) {
                val needed = obsSlack[i] > 0.0 || (i > 0 && obsSlack[i - 1] > 0.0) || (i + 1 < n && obsSlack[i + 1] > 0.0)
                moonAlt[i] = if (needed) {
                    val ctx = ctxs[i]!!
                    val m = ephemeris.moonTopocentric(ctx)
                    Horizon.altAz(m.lonDeg, m.latDeg, ctx.lastDeg(site.lonDeg), site.latDeg).altDeg
                } else {
                    Double.NaN
                }
            }
            val mdSlack = DoubleArray(n) {
                if (moonAlt[it].isNaN()) obsSlack[it] else min(obsSlack[it], Levels.MOON_RISE_SET - moonAlt[it])
            }
            var best = Double.POSITIVE_INFINITY
            var bestT: Long? = null
            var bestI = -1
            for (i in 0 until n) {
                if (sunAlt[i] < darkLevel && objAlt[i] > 0.0) {
                    val x = options.airmassModel.airmass(objAlt[i])
                    if (x < best) {
                        best = x
                        bestT = times[i]
                        bestI = i
                    }
                }
            }
            // When the best moment is at the start or end of dark time (target setting at dusk or rising
            // at dawn) the airmass changes fast: refine on a 1-minute grid around the best sample.
            if (bestI >= 0 && (bestI == 0 || bestI == n - 1 || sunAlt[bestI - 1] >= darkLevel || sunAlt[bestI + 1] >= darkLevel ||
                    objAlt[bestI - 1] <= 0.0 || objAlt[bestI + 1] <= 0.0)
            ) {
                for (j in -stepMinutes..stepMinutes) {
                    if (j == 0) continue
                    val t = times[bestI] + j * MS_PER_MINUTE
                    val ctx = SkyContext(t)
                    val last = ctx.lastDeg(site.lonDeg)
                    val sa = Horizon.altAz(ctx.sun.raDeg, ctx.sun.decDeg, last, site.latDeg).altDeg
                    if (sa >= darkLevel) continue
                    val u = ephemeris.targetApparent(ctx)
                    val alt = Horizon.altAz(u.lonDeg, u.latDeg, last, site.latDeg).altDeg
                    if (alt <= 0.0) continue
                    val x = options.airmassModel.airmass(alt)
                    if (x < best) {
                        best = x
                        bestT = t
                    }
                }
            }
            out.add(
                SeasonNight(
                    date = date,
                    midnight = mid,
                    darkHours = Events.totalHours(Events.intervalsWhere(times, darkSlack)),
                    observableHours = Events.totalHours(Events.intervalsWhere(times, obsSlack)),
                    moonDownHours = Events.totalHours(Events.intervalsWhere(times, mdSlack)),
                    bestAirmass = best,
                    bestTime = bestT,
                    moonIllumination = MoonPhase.compute(midCtx.moon, midCtx.sun).illuminated,
                    midnightHourAngleDeg = midHa,
                ),
            )
            progress?.invoke(k + 1, nights)
        }
        return SeasonResult(site, target, options, out)
    }
}
