package cl.astro.observability

import cl.astro.observability.astro.ObservabilityCalculator
import cl.astro.observability.astro.ObservabilityOptions
import cl.astro.observability.astro.Site
import cl.astro.observability.astro.Target
import cl.astro.observability.chart.ChartText
import cl.astro.observability.report.Summaries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** The text shown to the user must agree with the computed numbers. */
class ReportTest {
    private val lasilla = Site("lasilla", "La Silla", -29.256667, -70.73, 2347.0, "America/Santiago")
    private val tromso = Site("tromso", "Tromsø", 69.6496, 18.9560, 100.0, "Europe/Oslo")

    @Test
    fun nightSummaryMatchesResult() {
        val calc = ObservabilityCalculator(lasilla, Target("NGC 7531", 348.702083, -43.599944), ObservabilityOptions())
        val r = calc.night(LocalDate.of(2026, 11, 7))
        val s = Summaries.night(r, lasilla.zone)
        assertTrue(s, s.contains(String.format(java.util.Locale.US, "%.1f h", r.observableHours)))
        assertTrue(s, s.contains("UTC−3"))
        assertTrue(s, s.contains("Sun: sets 20:") && s.contains("rises 06:"))
        assertTrue(s, !s.contains("NaN") && !s.contains("Infinity"))
        val utc = Summaries.night(r, ZoneId.of("UTC"))
        assertTrue(utc, utc.contains("Times in UT") && utc.contains("Sun: sets 23:"))
    }

    @Test
    fun polarAndNeverObservableCases() {
        val polaris = Target("Polaris", 37.946250, 89.264109, 44.22, -11.74)
        val winter = ObservabilityCalculator(tromso, polaris, ObservabilityOptions()).night(LocalDate.of(2026, 12, 21))
        val s1 = Summaries.night(winter, tromso.zone)
        assertTrue(s1, s1.contains("polar night") && s1.contains("always above the horizon"))
        val summer = ObservabilityCalculator(tromso, polaris, ObservabilityOptions()).night(LocalDate.of(2027, 6, 21))
        val s2 = Summaries.night(summer, tromso.zone)
        assertTrue(s2, s2.contains("midnight Sun") && s2.contains("Not observable"))
        val m31 = Target("M31", 10.684792, 41.269056)
        val low = ObservabilityCalculator(lasilla, m31, ObservabilityOptions()).night(LocalDate.of(2026, 11, 7))
        val s3 = Summaries.night(low, lasilla.zone)
        assertTrue(s3, s3.contains("Not observable at X ≤ 2 in dark time (best airmass in the dark 2.99)"))
    }

    @Test
    fun windowSummaryMatchesResult() {
        val calc = ObservabilityCalculator(lasilla, Target("NGC 7531", 348.702083, -43.599944), ObservabilityOptions())
        val w = calc.window(LocalDate.of(2026, 11, 7), 90, lasilla.zone)!!
        val s = Summaries.window(w)
        val nGood = w.nights.count { it.observableHours > 0.05 }
        assertTrue(s, s.contains("on $nGood nights"))
        assertTrue(s, s.contains(String.format(java.util.Locale.US, "%.1f h in total", w.nights.sumOf { it.observableHours })))
        // Observability of NGC 7531 from La Silla shrinks after November as it moves into the evening sky.
        assertTrue(w.nights.first().observableHours > w.nights.last().observableHours)
    }

    @Test
    fun airmassAxisTicks() {
        // The axis is never shorter than the observability limit.
        assertEquals(2.5, ChartText.axisMaxAirmass(2.5, 2.0), 0.0)
        assertEquals(3.0, ChartText.axisMaxAirmass(2.0, 3.0), 0.0)
        val choices = listOf(1.5, 2.0, 2.5, 3.0)
        val limits = listOf(1.2, 1.3, 1.5, 1.75, 2.0, 2.5, 3.0)
        for (c in choices) for (lim in limits) for (len in listOf(300f, 700f, 1600f)) {
            val xMax = ChartText.axisMaxAirmass(c, lim)
            val t = ChartText.airmassTicks(xMax, len, 42f)
            // From 1 to the bottom of the axis exactly, evenly spaced, labels readable.
            assertEquals(1.0, t.first(), 1e-9)
            assertEquals("axis $xMax", xMax, t.last(), 1e-9)
            val step = t[1] - t[0]
            for (i in 1 until t.size) assertEquals(step, t[i] - t[i - 1], 1e-9)
            if (len >= 700f) assertTrue("ticks too dense for $xMax on $len px", len / (t.size - 1) >= 42f)
        }
        // A phone-sized plot (680 px high, ticks at least 20 dp = 52.5 px apart).
        assertEquals(listOf(1.0, 1.25, 1.5, 1.75, 2.0, 2.25, 2.5), ChartText.airmassTicks(2.5, 680f, 52.5f))
        assertEquals(listOf(1.0, 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9, 2.0), ChartText.airmassTicks(2.0, 680f, 52.5f))
        assertEquals(11, ChartText.airmassTicks(3.0, 680f, 52.5f).size) // 0.2 steps, as before
        assertEquals("1.25", ChartText.airmassLimit(1.25))
        assertEquals("2.0", ChartText.airmassLimit(2.0))
    }

    @Test
    fun fixedUtcOffsetTimeScale() {
        assertEquals("UTC−3", ChartText.utcOffset(-3 * 3600))
        assertEquals("UTC+5:30", ChartText.utcOffset(5 * 3600 + 1800))
        val m = java.time.LocalDate.of(2026, 11, 7).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals("UT", ChartText.zoneLabel(ZoneId.of("UTC"), m))
        assertEquals("UTC−2", ChartText.zoneLabel(java.time.ZoneOffset.of("-02:00"), m))
        assertEquals("America/Santiago, UTC−3", ChartText.zoneLabel(ZoneId.of("America/Santiago"), m))
        val site = ZoneId.of("America/Santiago")
        assertEquals("local", ChartText.shortZone(site, site))
        assertEquals("UTC+5:30", ChartText.shortZone(java.time.ZoneOffset.of("+05:30"), site))
        assertEquals("Paris", ChartText.shortZone(ZoneId.of("Europe/Paris"), site))
        assertEquals("UT", ChartText.shortZone(ZoneId.of("UTC"), site))

        // A night and a window drawn in UTC−2: midnight of that scale, same physics.
        val calc = ObservabilityCalculator(lasilla, Target("NGC 7531", 348.702083, -43.599944), ObservabilityOptions())
        val off = java.time.ZoneOffset.of("-02:00")
        val date = java.time.LocalDate.of(2026, 11, 7)
        val ref = calc.displayMidnight(date, off)
        assertEquals(java.time.LocalDate.of(2026, 11, 8).atStartOfDay(off).toInstant().toEpochMilli(), ref)
        val r = calc.night(date)
        assertTrue(Summaries.night(r, off).contains("Times in UTC−2"))
        val w1 = calc.window(date, 3, off)!!
        val w2 = calc.window(date, 3, ZoneId.of("America/Santiago"))!!
        for (k in 0 until 3) {
            assertEquals(w2.nights[k].observableHours, w1.nights[k].observableHours, 0.05)
            // Santiago is on UTC−3 in November, so midnight of UTC−2 comes one hour earlier.
            assertEquals(-3_600_000L, w1.nights[k].refMidnight - w2.nights[k].refMidnight)
        }
    }
}
