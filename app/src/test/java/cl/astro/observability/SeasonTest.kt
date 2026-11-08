package cl.astro.observability

import cl.astro.observability.astro.DarkLimit
import cl.astro.observability.astro.ObservabilityCalculator
import cl.astro.observability.astro.ObservabilityOptions
import cl.astro.observability.astro.SeasonPlanner
import cl.astro.observability.astro.SeasonResult
import cl.astro.observability.astro.Site
import cl.astro.observability.astro.Target
import cl.astro.observability.report.Summaries
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** Best-observability planning over a year, checked against the detailed per-night computation. */
class SeasonTest {
    private val lasilla = Site("lasilla", "La Silla", -29.256667, -70.73, 2347.0, "America/Santiago")
    private val ondrejov = Site("ondrejov", "Ondrejov", 49.9106, 14.7808, 528.0, "Europe/Prague")
    private val maunakea = Site("maunakea", "Mauna Kea", 19.8207, -155.4681, 4205.0, "Pacific/Honolulu")
    private val ngc7531 = Target("NGC 7531", 348.702083, -43.599944)
    private val opts = ObservabilityOptions()

    companion object {
        // One scan shared by several tests (a year scan takes about a second).
        private var cached: SeasonResult? = null
    }

    private fun ngc7531Year(): SeasonResult {
        cached?.let { return it }
        val t0 = System.nanoTime()
        val r = SeasonPlanner(lasilla, ngc7531, opts).scan(LocalDate.of(2026, 1, 1), 365)!!
        println("    year scan: ${(System.nanoTime() - t0) / 1_000_000} ms")
        cached = r
        return r
    }

    @Test
    fun nightlyFiguresMatchTheDetailedComputation() {
        val year = ngc7531Year()
        assertEquals(365, year.nights.size)
        val calc = ObservabilityCalculator(lasilla, ngc7531, opts)
        val eph = cl.astro.observability.astro.Ephemeris(lasilla, ngc7531, opts.airmassModel)
        var worstHours = 0.0
        var worstX = 0.0
        for (k in listOf(0, 40, 95, 150, 200, 230, 255, 280, 300, 330, 364)) {
            val s = year.nights[k]
            val d = calc.night(s.date)
            assertEquals(d.date, s.date)
            worstHours = maxOf(worstHours, abs(s.darkHours - d.darkHours), abs(s.observableHours - d.observableHours),
                abs(s.moonDownHours - d.observableMoonDownHours))
            // Brute-force minimum airmass in dark time on a 30-second grid.
            var bf = Double.POSITIVE_INFINITY
            var t = d.windowStart
            while (t <= d.windowEnd) {
                val smp = eph.sample(t)
                if (smp.sunAlt < opts.dark.sunAltDeg && smp.objAlt > 0.0) bf = minOf(bf, smp.airmass)
                t += 30_000L
            }
            if (bf.isFinite() && bf < 6.0) worstX = maxOf(worstX, abs(s.bestAirmass - bf))
            if (!bf.isFinite()) assertTrue(!s.bestAirmass.isFinite())
            assertEquals(d.moonPhase.illuminated, s.moonIllumination, 1e-9)
        }
        println("    worst hour difference ${"%.3f".format(worstHours)} h, worst airmass ${"%.4f".format(worstX)}")
        // 10-minute vs 2-minute sampling, both with interpolated edges.
        assertTrue("hours differ by $worstHours", worstHours < 0.05)
        assertTrue("airmass differs by $worstX", worstX < 0.02)
    }

    @Test
    fun midnightTransitIsTheOppositionNight() {
        val year = ngc7531Year()
        val k = year.midnightTransit!!
        val night = year.nights[k]
        val d = ObservabilityCalculator(lasilla, ngc7531, opts).night(night.date)
        // The target culminates within a few minutes of local mean midnight (it moves 4 min/day).
        val minutes = abs(d.events.transit - night.midnight) / 60_000.0
        assertTrue("transit $minutes min from midnight", minutes < 4.0)
        // RA 23.25 h: opposition in early/mid September.
        assertEquals(9, night.date.monthValue)
    }

    @Test
    fun bestPeriodsAndDarkNightsAreConsistent() {
        val year = ngc7531Year()
        assertTrue(year.everObservable)
        assertTrue(year.maxObservableHours > 5.0)
        assertTrue(year.bestPeriods.isNotEmpty())
        for (p in year.bestPeriods) {
            for (i in p.first..p.last) {
                assertTrue(year.nights[i].observableHours >= SeasonResult.BEST_FRACTION * year.maxObservableHours - 0.3)
            }
        }
        // Nights outside the best and observable periods are worse.
        val inBest = year.bestPeriods.flatMap { (it.first..it.last).toList() }.toSet()
        for (i in year.nights.indices) {
            if (i !in inBest) assertTrue(year.nights[i].observableHours < SeasonResult.BEST_FRACTION * year.maxObservableHours + 0.3)
        }
        val inSeason = year.observableSeasons.flatMap { (it.first..it.last).toList() }.toSet()
        assertTrue(inSeason.containsAll(inBest))
        // The opposition night lies in (or very near) the best period.
        val opp = year.midnightTransit!!
        assertTrue(inBest.any { abs(it - opp) <= 45 })
        // Dark runs: inside the best period, the Moon mostly down, around new Moon.
        assertTrue(year.darkRuns.size >= 2)
        for (r in year.darkRuns) {
            for (i in r) {
                assertTrue(i in inBest)
                val n = year.nights[i]
                assertTrue(n.moonDownHours >= SeasonResult.MOON_DOWN_FRACTION * n.observableHours - 0.3)
            }
            val illum = r.map { year.nights[it].moonIllumination }.average()
            assertTrue("mean illumination $illum in a dark run", illum < 0.6)
        }
        // The peak night has the most Moon-free observable time (to 10 min) and is a dark-run night.
        val peak = year.peak!!
        val maxMd = year.nights.maxOf { it.moonDownHours }
        assertTrue(year.nights[peak].moonDownHours >= maxMd - SeasonResult.PEAK_TOLERANCE_H)
        assertTrue(year.darkRuns.any { peak in it })
    }

    @Test
    fun seasonInProgressComesRoundAgain() {
        val year = ngc7531Year()
        val p = year.bestPeriods.first()
        // Start the scan in the middle of the best period: the current season is "ongoing" and the
        // next one starts on the same calendar day as in the first scan (the hours repeat every year).
        val mid = year.nights[(p.first + p.last) / 2].date
        val r2 = SeasonPlanner(lasilla, ngc7531, opts).scan(mid, 365)!!
        val now = r2.bestPeriods.first()
        assertTrue(now.ongoing)
        assertEquals(0, now.first)
        assertEquals(p.lastDate.toEpochDay().toDouble(), now.lastDate.toEpochDay().toDouble(), 2.0)
        val next = r2.bestPeriods.last()
        assertFalse(next.ongoing)
        assertEquals(p.firstDate.plusYears(1).toEpochDay().toDouble(), next.firstDate.toEpochDay().toDouble(), 2.0)
        assertEquals(now.lastDate.plusYears(1), next.lastDate)
        assertTrue(ChronoUnit.DAYS.between(next.firstDate, next.lastDate) in 30..250)
    }

    @Test
    fun targetThatNeverRises() {
        // Dec +80° from La Silla (latitude −29°) never rises.
        val r = SeasonPlanner(lasilla, Target("North", 10.0, 80.0), opts).scan(LocalDate.of(2026, 3, 1), 60)!!
        assertFalse(r.everObservable)
        assertTrue(r.bestPeriods.isEmpty())
        assertNull(r.lowestAirmassNight)
        assertNull(r.peak)
    }

    @Test
    fun lowTargetReportsItsBestAirmass() {
        // Dec −60° from Mauna Kea (+19.8°): culminates at 10° (X ≈ 5.6), never below X = 2.
        val r = SeasonPlanner(maunakea, Target("Low", 120.0, -60.0), opts).scan(LocalDate.of(2026, 1, 1), 365)!!
        assertFalse(r.everObservable)
        val k = r.lowestAirmassNight!!
        val x = r.nights[k].bestAirmass
        assertTrue("airmass $x", x in 4.5..7.0)
        // RA 8 h culminates at midnight in late January.
        assertEquals(1.0, r.nights[k].date.monthValue.toDouble(), 1.0)
    }

    @Test
    fun highLatitudeSummerHasNoAstronomicalDarkness() {
        // Ondřejov (49.9° N): no astronomical darkness around the June solstice; Vega peaks either side.
        val vega = Target("Vega", 279.234735, 38.783689)
        val r = SeasonPlanner(ondrejov, vega, opts).scan(LocalDate.of(2026, 1, 1), 365)!!
        val june21 = r.nights[r.indexOf(LocalDate.of(2026, 6, 21))!!]
        assertEquals(0.0, june21.darkHours, 1e-9)
        assertEquals(0.0, june21.observableHours, 1e-9)
        assertTrue(r.everObservable)
        for (p in r.bestPeriods) assertTrue(LocalDate.of(2026, 6, 21) !in p.firstDate..p.lastDate)
        // With nautical darkness the solstice night is usable.
        val nautical = SeasonPlanner(ondrejov, vega, opts.copy(dark = DarkLimit.NAUTICAL))
            .scan(LocalDate.of(2026, 6, 20), 3)!!
        assertTrue(nautical.nights[1].darkHours > 2.0)
        assertNotNull(nautical.nights[1].bestTime)
    }

    @Test
    fun summaryStatesTheComputedDates() {
        val year = ngc7531Year()
        val txt = Summaries.season(year, lasilla.zone)
        val long = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)
        for (p in year.bestPeriods) {
            assertTrue(txt, if (p.ongoing) txt.contains("now until ${long.format(p.lastDate)}")
            else txt.contains(Summaries.dateRange(p.firstDate, p.lastDate)))
        }
        assertTrue(txt, txt.contains("Opposition (culminates at local midnight): ${long.format(year.nights[year.midnightTransit!!].date)}"))
        val peak = year.nights[year.peak!!]
        assertTrue(txt, txt.contains(DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.US).format(peak.date)))
        assertTrue(txt, txt.contains(String.format(Locale.US, "%.1f h per night", year.maxObservableHours)))
        // The suggested date window is the first best period.
        assertEquals(year.bestPeriods.first(), Summaries.suggestedWindow(year))

        // Never observable at X ≤ 1.5 from La Palma: the lowest airmass and when it is reached are given.
        val lapalma = Site("lapalma", "La Palma", 28.7606, -17.8792, 2327.0, "Atlantic/Canary")
        val low = SeasonPlanner(lapalma, ngc7531, ObservabilityOptions(airmassLimit = 1.5)).scan(LocalDate.of(2026, 11, 7), 365)!!
        val t2 = Summaries.season(low, lapalma.zone)
        assertTrue(t2, t2.contains("never reaches X ≤ 1.5"))
        assertTrue(t2, t2.contains(String.format(Locale.US, "%.2f", low.nights[low.lowestAirmassNight!!].bestAirmass)))
        assertTrue(low.lowestAirmassRuns.isNotEmpty())

        // Always observable whenever it is dark (Dec −80° from latitude −45°).
        val s45 = Site("s45", "Lat -45", -45.0, -70.0, 0.0, "America/Santiago")
        val circ = SeasonPlanner(s45, Target("SCP", 120.0, -80.0), opts).scan(LocalDate.of(2026, 11, 7), 365)!!
        assertTrue(Summaries.season(circ, s45.zone).contains("Observable at least 1.0 h per night: all year."))
        for (n in circ.nights) assertEquals(n.darkHours, n.observableHours, 0.02)
    }
}
