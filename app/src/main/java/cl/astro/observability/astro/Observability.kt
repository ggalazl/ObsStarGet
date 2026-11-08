package cl.astro.observability.astro

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Solar depression that defines "dark time". */
enum class DarkLimit(val sunAltDeg: Double, val label: String) {
    ASTRONOMICAL(-18.0, "Astronomical twilight (Sun < −18°)"),
    NAUTICAL(-12.0, "Nautical twilight (Sun < −12°)"),
    CIVIL(-6.0, "Civil twilight (Sun < −6°)"),
}

object Levels {
    /** Sun centre at sunrise/sunset: 34' refraction + 16' semidiameter. */
    const val SUN_RISE_SET = -0.8333
    const val CIVIL = -6.0
    const val NAUTICAL = -12.0
    const val ASTRONOMICAL = -18.0

    /** Topocentric Moon centre at moonrise/moonset: 34' refraction + ~15.5' semidiameter. */
    const val MOON_RISE_SET = -0.8333

    /** Point source at rise/set (refraction only). */
    const val STAR_RISE_SET = -0.5667
}

data class ObservabilityOptions(
    val airmassLimit: Double = 2.0,
    val dark: DarkLimit = DarkLimit.ASTRONOMICAL,
    val airmassModel: AirmassModel = AirmassModel.YOUNG_1994,
    /** Plot the whole local-mean-time noon → noon day instead of sunset − 1 h → sunrise + 1 h. */
    val fullDay: Boolean = false,
) {
    /** Geometric altitude at which the airmass equals the limit. */
    val altitudeLimitDeg: Double get() = airmassModel.altitudeForAirmass(airmassLimit)
}

class Crossing(val epochMillis: Long, val rising: Boolean)

class Interval(val start: Long, val end: Long) {
    val hours: Double get() = (end - start) / 3_600_000.0
}

object Events {
    /** Level crossings of sampled values (linear interpolation between samples). */
    fun crossings(times: LongArray, values: DoubleArray, level: Double): List<Crossing> {
        val out = ArrayList<Crossing>()
        for (i in 1 until times.size) {
            val a = values[i - 1] - level
            val b = values[i] - level
            if (a.isNaN() || b.isNaN()) continue
            if ((a < 0.0 && b >= 0.0) || (a >= 0.0 && b < 0.0)) {
                val f = a / (a - b)
                out.add(Crossing(times[i - 1] + ((times[i] - times[i - 1]) * f).roundToLong(), b > a))
            }
        }
        return out
    }

    /** Level crossings of a function: coarse grid, then bisection to ~0.1 s. */
    fun crossings(t0: Long, t1: Long, stepMs: Long, level: Double, f: (Long) -> Double): List<Crossing> {
        val out = ArrayList<Crossing>()
        var ta = t0
        var fa = f(ta) - level
        while (ta < t1) {
            val tb = min(ta + stepMs, t1)
            val fb = f(tb) - level
            if ((fa < 0.0 && fb >= 0.0) || (fa >= 0.0 && fb < 0.0)) {
                var lo = ta
                var hi = tb
                var flo = fa
                while (hi - lo > 100) {
                    val mid = (lo + hi) / 2
                    val fm = f(mid) - level
                    if ((flo < 0.0) == (fm < 0.0)) {
                        lo = mid
                        flo = fm
                    } else {
                        hi = mid
                    }
                }
                out.add(Crossing((lo + hi) / 2, fb > fa))
            }
            ta = tb
            fa = fb
        }
        return out
    }

    /**
     * Intervals where [slack] > 0, slack sampled at [times]; the edges are located by linear interpolation
     * of the slack between samples.
     */
    fun intervalsWhere(times: LongArray, slack: DoubleArray): List<Interval> {
        val out = ArrayList<Interval>()
        var start: Long? = if (slack.isNotEmpty() && slack[0] > 0.0) times[0] else null
        for (i in 1 until times.size) {
            val a = slack[i - 1]
            val b = slack[i]
            if (a <= 0.0 && b > 0.0) {
                start = times[i - 1] + ((times[i] - times[i - 1]) * (a / (a - b))).roundToLong()
            } else if (a > 0.0 && b <= 0.0) {
                val end = times[i - 1] + ((times[i] - times[i - 1]) * (a / (a - b))).roundToLong()
                if (start != null) out.add(Interval(start, end))
                start = null
            }
        }
        if (start != null) out.add(Interval(start, times.last()))
        return out
    }

    fun totalHours(list: List<Interval>): Double = list.sumOf { it.hours }
}

class NightEvents(
    val sunset: Long?,
    val sunrise: Long?,
    val civilDusk: Long?,
    val civilDawn: Long?,
    val nauticalDusk: Long?,
    val nauticalDawn: Long?,
    val astroDusk: Long?,
    val astroDawn: Long?,
    val moonrises: List<Long>,
    val moonsets: List<Long>,
    val objectRises: List<Long>,
    val objectSets: List<Long>,
    val transit: Long,
    val transitAltDeg: Double,
)

/** Detailed curves and events for one night (local evening of [date] to the next morning). */
class NightResult(
    val site: Site,
    val target: Target,
    val options: ObservabilityOptions,
    val date: LocalDate,
    /** Local-mean-time noon → noon window used for the computation. */
    val windowStart: Long,
    val windowEnd: Long,
    /** Range shown in the diagram (sunset − 1 h → sunrise + 1 h when the Sun sets). */
    val plotStart: Long,
    val plotEnd: Long,
    val times: LongArray,
    val sunAlt: DoubleArray,
    val objAlt: DoubleArray,
    val objAz: DoubleArray,
    val airmass: DoubleArray,
    val moonAlt: DoubleArray,
    val moonAz: DoubleArray,
    val moonSep: DoubleArray,
    val lst: DoubleArray,
    val events: NightEvents,
    /** Dark time (Sun below the chosen limit). */
    val dark: List<Interval>,
    /** Dark time with airmass ≤ limit. */
    val observable: List<Interval>,
    /** Same, with the Moon below the horizon. */
    val observableMoonDown: List<Interval>,
    /** Moon phase at local midnight. */
    val moonPhase: MoonPhase,
    val midnight: Long,
    /** Days since the last new Moon, at local midnight. */
    val moonAgeDays: Double,
) {
    val darkHours: Double get() = Events.totalHours(dark)
    val observableHours: Double get() = Events.totalHours(observable)
    val observableMoonDownHours: Double get() = Events.totalHours(observableMoonDown)

    /** Index range of samples inside the plot range. */
    val plotFrom: Int get() = times.indexOfFirst { it >= plotStart }.coerceAtLeast(0)
    val plotTo: Int get() = times.indexOfLast { it <= plotEnd }.let { if (it < 0) times.size - 1 else it }

    /** Minimum airmass during dark time (∞ if the target is never up in the dark). */
    val bestDarkAirmass: Double
        get() {
            var best = Double.POSITIVE_INFINITY
            val lim = options.dark.sunAltDeg
            for (i in times.indices) if (sunAlt[i] < lim && airmass[i] < best) best = airmass[i]
            return best
        }

    /** Min/max Moon separation while the target is up during dark time (NaN if never). */
    fun moonSeparationRangeInDark(): Pair<Double, Double> {
        var lo = Double.NaN
        var hi = Double.NaN
        val lim = options.dark.sunAltDeg
        for (i in times.indices) {
            if (sunAlt[i] < lim && objAlt[i] > 0.0) {
                val s = moonSep[i]
                lo = if (lo.isNaN()) s else min(lo, s)
                hi = if (hi.isNaN()) s else max(hi, s)
            }
        }
        return lo to hi
    }

    /** Nearest sample index to an instant. */
    fun indexAt(ms: Long): Int {
        if (ms <= times.first()) return 0
        if (ms >= times.last()) return times.size - 1
        val step = (times.last() - times.first()) / (times.size - 1).toDouble()
        return ((ms - times.first()) / step).roundToLong().toInt().coerceIn(0, times.size - 1)
    }

    /**
     * Sample index of the minimum airmass during dark time (the "best moment" used to sort targets,
     * as iObserve does), or null if the target is below the horizon during the whole dark time.
     */
    val bestDarkIndex: Int?
        get() {
            var best = -1
            val lim = options.dark.sunAltDeg
            for (i in times.indices) {
                if (sunAlt[i] < lim && objAlt[i] > 0.0 && (best < 0 || airmass[i] < airmass[best])) best = i
            }
            return if (best < 0) null else best
        }

    /** Minimum Moon separation while the target is up (alt > 0) during dark time and the Moon is up. */
    val minMoonSeparationWithMoonUp: Double
        get() {
            var m = Double.NaN
            val lim = options.dark.sunAltDeg
            for (i in times.indices) {
                if (sunAlt[i] < lim && objAlt[i] > 0.0 && moonAlt[i] > Levels.MOON_RISE_SET) {
                    m = if (m.isNaN()) moonSep[i] else min(m, moonSep[i])
                }
            }
            return m
        }
}

/** One column of the date-window diagram. */
class WindowNight(
    val date: LocalDate,
    /** Midnight in the display time scale (y origin of this column). */
    val refMidnight: Long,
    val sunset: Long?,
    val sunrise: Long?,
    val darkStart: Long?,
    val darkEnd: Long?,
    val darkHours: Double,
    val observableHours: Double,
    val observableMoonDownHours: Double,
    val moonPhase: MoonPhase,
    val bestDarkAirmass: Double,
    /** Per-row samples (row r is at refMidnight + (yStartHours + (r + 0.5)·step)). */
    val rowSunAlt: FloatArray,
    val rowAirmass: FloatArray,
    val rowMoonAlt: FloatArray,
)

class WindowResult(
    val site: Site,
    val target: Target,
    val options: ObservabilityOptions,
    val displayZone: ZoneId,
    val nights: List<WindowNight>,
    val yStartHours: Double,
    val yEndHours: Double,
    val stepMinutes: Int,
) {
    val rows: Int get() = ((yEndHours - yStartHours) * 60.0 / stepMinutes).toInt()
}

class ObservabilityCalculator(val site: Site, val target: Target, val options: ObservabilityOptions) {
    val ephemeris = Ephemeris(site, target, options.airmassModel)

    /** Local-mean-time midnight that ends the evening of [date]. */
    fun lmtMidnight(date: LocalDate): Long {
        val utcMidnight = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return utcMidnight - (site.lonDeg / 15.0 * MS_PER_HOUR).roundToLong()
    }

    /** Detailed computation for the night starting on the evening of [date]. */
    fun night(date: LocalDate, stepMinutes: Int = 2): NightResult {
        val mid = lmtMidnight(date)
        val t0 = mid - 12 * MS_PER_HOUR
        val t1 = mid + 12 * MS_PER_HOUR
        val step = stepMinutes * MS_PER_MINUTE
        val n = ((t1 - t0) / step).toInt() + 1
        val times = LongArray(n) { t0 + it * step }
        val sunAlt = DoubleArray(n)
        val objAlt = DoubleArray(n)
        val objAz = DoubleArray(n)
        val airmass = DoubleArray(n)
        val moonAlt = DoubleArray(n)
        val moonAz = DoubleArray(n)
        val moonSep = DoubleArray(n)
        val lst = DoubleArray(n)
        for (i in 0 until n) {
            val s = ephemeris.sample(times[i])
            sunAlt[i] = s.sunAlt
            objAlt[i] = s.objAlt
            objAz[i] = s.objAz
            airmass[i] = s.airmass
            moonAlt[i] = s.moonAlt
            moonAz[i] = s.moonAz
            moonSep[i] = s.moonSep
            lst[i] = s.lastDeg
        }

        fun firstDown(level: Double) = Events.crossings(times, sunAlt, level).firstOrNull { !it.rising }?.epochMillis
        fun lastUp(level: Double) = Events.crossings(times, sunAlt, level).lastOrNull { it.rising }?.epochMillis

        val sunset = firstDown(Levels.SUN_RISE_SET)
        val sunrise = lastUp(Levels.SUN_RISE_SET)
        val moonX = Events.crossings(times, moonAlt, Levels.MOON_RISE_SET)
        val objX = Events.crossings(times, objAlt, Levels.STAR_RISE_SET)

        // Upper culmination: highest sample, refined with a parabola through its neighbours.
        var iMax = 0
        for (i in 1 until n) if (objAlt[i] > objAlt[iMax]) iMax = i
        var transit = times[iMax]
        var transitAlt = objAlt[iMax]
        if (iMax in 1 until n - 1) {
            val ya = objAlt[iMax - 1]
            val yb = objAlt[iMax]
            val yc = objAlt[iMax + 1]
            val den = ya - 2 * yb + yc
            if (den < 0.0) {
                val off = 0.5 * (ya - yc) / den
                transit = times[iMax] + (off * step).roundToLong()
                transitAlt = yb - 0.25 * (ya - yc) * off
            }
        }

        val events = NightEvents(
            sunset = sunset,
            sunrise = sunrise,
            civilDusk = firstDown(Levels.CIVIL),
            civilDawn = lastUp(Levels.CIVIL),
            nauticalDusk = firstDown(Levels.NAUTICAL),
            nauticalDawn = lastUp(Levels.NAUTICAL),
            astroDusk = firstDown(Levels.ASTRONOMICAL),
            astroDawn = lastUp(Levels.ASTRONOMICAL),
            moonrises = moonX.filter { it.rising }.map { it.epochMillis },
            moonsets = moonX.filter { !it.rising }.map { it.epochMillis },
            objectRises = objX.filter { it.rising }.map { it.epochMillis },
            objectSets = objX.filter { !it.rising }.map { it.epochMillis },
            transit = transit,
            transitAltDeg = transitAlt,
        )

        val darkLevel = options.dark.sunAltDeg
        val altLimit = options.altitudeLimitDeg
        val darkSlack = DoubleArray(n) { darkLevel - sunAlt[it] }
        val obsSlack = DoubleArray(n) { min(darkSlack[it], objAlt[it] - altLimit) }
        val obsMoonDownSlack = DoubleArray(n) { min(obsSlack[it], Levels.MOON_RISE_SET - moonAlt[it]) }

        val margin = MS_PER_HOUR
        val nightOnly = !options.fullDay && sunset != null && sunrise != null && sunrise > sunset
        val plotStart = if (nightOnly) max(t0, sunset!! - margin) else t0
        val plotEnd = if (nightOnly) min(t1, sunrise!! + margin) else t1

        return NightResult(
            site, target, options, date, t0, t1, plotStart, plotEnd,
            times, sunAlt, objAlt, objAz, airmass, moonAlt, moonAz, moonSep, lst, events,
            Events.intervalsWhere(times, darkSlack),
            Events.intervalsWhere(times, obsSlack),
            Events.intervalsWhere(times, obsMoonDownSlack),
            ephemeris.moonPhase(mid),
            mid,
            ephemeris.moonAgeDays(mid),
        )
    }

    /** Midnight of the display time scale that is closest to the local-mean-time midnight of a night. */
    fun displayMidnight(date: LocalDate, zone: ZoneId): Long {
        val mid = lmtMidnight(date)
        return if (zone.id == "UTC" || zone.normalized().id == "Z") {
            floor(mid / MS_PER_DAY.toDouble() + 0.5).toLong() * MS_PER_DAY
        } else {
            val local = Instant.ofEpochMilli(mid).atZone(zone)
            val candidates = listOf(local.toLocalDate(), local.toLocalDate().plusDays(1))
                .map { ZonedDateTime.of(it, LocalTime.MIDNIGHT, zone).toInstant().toEpochMilli() }
            candidates.minByOrNull { kotlin.math.abs(it - mid) }!!
        }
    }

    /**
     * Date-window overview: one column per night, sampled every [stepMinutes] between yStart and yEnd
     * hours around midnight of [displayZone] (the site zone for local time, UTC for UT).
     */
    fun window(
        start: LocalDate,
        nights: Int,
        displayZone: ZoneId,
        stepMinutes: Int = if (nights > 120) 10 else 5,
        progress: ((Int, Int) -> Unit)? = null,
        cancelled: (() -> Boolean)? = null,
    ): WindowResult? {
        data class SunNight(val date: LocalDate, val mid: Long, val ref: Long, val set: Long?, val rise: Long?,
                            val dStart: Long?, val dEnd: Long?)

        val darkLevel = options.dark.sunAltDeg
        val sunNights = ArrayList<SunNight>(nights)
        for (k in 0 until nights) {
            if (cancelled?.invoke() == true) return null
            val date = start.plusDays(k.toLong())
            val mid = lmtMidnight(date)
            val t0 = mid - 12 * MS_PER_HOUR
            val t1 = mid + 12 * MS_PER_HOUR
            val f: (Long) -> Double = { ephemeris.sunAltitude(it) }
            val rs = Events.crossings(t0, t1, 20 * MS_PER_MINUTE, Levels.SUN_RISE_SET, f)
            val dk = Events.crossings(t0, t1, 20 * MS_PER_MINUTE, darkLevel, f)
            sunNights.add(
                SunNight(
                    date, mid, displayMidnight(date, displayZone),
                    rs.firstOrNull { !it.rising }?.epochMillis, rs.lastOrNull { it.rising }?.epochMillis,
                    dk.firstOrNull { !it.rising }?.epochMillis, dk.lastOrNull { it.rising }?.epochMillis,
                ),
            )
        }

        // Vertical range: from the earliest sunset to the latest sunrise (+30 min), whole hours.
        var yStart = -12.0
        var yEnd = 12.0
        if (sunNights.all { it.set != null && it.rise != null && it.rise > it.set }) {
            val lo = sunNights.minOf { (it.set!! - it.ref) / 3_600_000.0 } - 0.5
            val hi = sunNights.maxOf { (it.rise!! - it.ref) / 3_600_000.0 } + 0.5
            yStart = floor(lo).coerceAtLeast(-12.0)
            yEnd = kotlin.math.ceil(hi).coerceAtMost(12.0)
        }
        val rows = ((yEnd - yStart) * 60.0 / stepMinutes).toInt()
        val stepMs = stepMinutes * MS_PER_MINUTE

        val altLimit = options.altitudeLimitDeg
        val out = ArrayList<WindowNight>(nights)
        for ((k, sn) in sunNights.withIndex()) {
            if (cancelled?.invoke() == true) return null
            val base = sn.ref + (yStart * MS_PER_HOUR).roundToLong()
            val times = LongArray(rows + 1) { base + it * stepMs }
            val sunA = DoubleArray(rows + 1)
            val airm = DoubleArray(rows + 1)
            val moonA = DoubleArray(rows + 1)
            val objA = DoubleArray(rows + 1)
            for (i in 0..rows) {
                val s = ephemeris.sample(times[i])
                sunA[i] = s.sunAlt
                airm[i] = s.airmass
                moonA[i] = s.moonAlt
                objA[i] = s.objAlt
            }
            val darkSlack = DoubleArray(rows + 1) { darkLevel - sunA[it] }
            val obsSlack = DoubleArray(rows + 1) { min(darkSlack[it], objA[it] - altLimit) }
            val obsMdSlack = DoubleArray(rows + 1) { min(obsSlack[it], Levels.MOON_RISE_SET - moonA[it]) }
            var best = Double.POSITIVE_INFINITY
            for (i in 0..rows) if (sunA[i] < darkLevel && airm[i] < best) best = airm[i]
            // Row values at row centres (average of the two bounding samples).
            val rSun = FloatArray(rows) { ((sunA[it] + sunA[it + 1]) / 2).toFloat() }
            val rAir = FloatArray(rows) {
                val a = airm[it]
                val b = airm[it + 1]
                if (a.isInfinite() || b.isInfinite()) Float.POSITIVE_INFINITY else ((a + b) / 2).toFloat()
            }
            val rMoon = FloatArray(rows) { ((moonA[it] + moonA[it + 1]) / 2).toFloat() }
            out.add(
                WindowNight(
                    sn.date, sn.ref, sn.set, sn.rise, sn.dStart, sn.dEnd,
                    Events.totalHours(Events.intervalsWhere(times, darkSlack)),
                    Events.totalHours(Events.intervalsWhere(times, obsSlack)),
                    Events.totalHours(Events.intervalsWhere(times, obsMdSlack)),
                    ephemeris.moonPhase(sn.mid), best, rSun, rAir, rMoon,
                ),
            )
            progress?.invoke(k + 1, nights)
        }
        return WindowResult(site, target, options, displayZone, out, yStart, yEnd, stepMinutes)
    }
}
