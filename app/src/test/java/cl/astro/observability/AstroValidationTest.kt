package cl.astro.observability

import cl.astro.observability.astro.AirmassModel
import cl.astro.observability.astro.Ephemeris
import cl.astro.observability.astro.EarthOrientation
import cl.astro.observability.astro.Moon
import cl.astro.observability.astro.ObservabilityCalculator
import cl.astro.observability.astro.ObservabilityOptions
import cl.astro.observability.astro.Site
import cl.astro.observability.astro.SkyContext
import cl.astro.observability.astro.Sun
import cl.astro.observability.astro.Target
import cl.astro.observability.astro.TimeScales
import cl.astro.observability.astro.angularSeparationDeg
import cl.astro.observability.astro.norm180
import cl.astro.observability.astro.norm360
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * Validates the astronomy engine against (1) the worked examples of Meeus, "Astronomical Algorithms"
 * (2nd ed.) and (2) independent values computed with astropy/ERFA (validation/make_reference.py).
 */
class AstroValidationTest {

    private val sites = mapOf(
        "lasilla" to Site("lasilla", "La Silla", -29.256667, -70.730000, 2347.0, "America/Santiago"),
        "lapalma" to Site("lapalma", "La Palma", 28.758333, -17.880000, 2327.0, "Atlantic/Canary"),
        "tromso" to Site("tromso", "Tromsø", 69.6496, 18.9560, 100.0, "Europe/Oslo"),
    )
    private val targets = mapOf(
        "M31" to Target("M31", 10.684792, 41.269056),
        "NGC7531" to Target("NGC 7531", 348.702083, -43.599944),
        "Vega" to Target("Vega", 279.234600, 38.783692, 201.02, 287.46),
        "Polaris" to Target("Polaris", 37.946250, 89.264109, 44.22, -11.74),
    )

    private fun reference(): List<List<String>> =
        javaClass.classLoader!!.getResourceAsStream("astropy_reference.tsv")!!.bufferedReader()
            .readLines().filter { it.isNotBlank() }.map { it.split('\t') }

    // ---------------------------------------------------------------- Meeus worked examples

    @Test
    fun moonMatchesMeeusExample47a() {
        val t = (2448724.5 - 2451545.0) / 36525.0 // 1992 April 12, 0h TD
        val s = Moon.series(t)
        assertEquals(-1127527.0, s.sumL, 0.5)
        assertEquals(-3229126.0, s.sumB, 0.5)
        assertEquals(-16590875.0, s.sumR, 0.5)
        val m = Moon.geocentric(t, EarthOrientation.nutation(t))
        // Meeus: λ (apparent) 133.167265°, β −3.229126°, Δ 368409.7 km, α 134.688470°, δ 13.768368°.
        // Our low-precision nutation differs from Meeus' full series by 0.11" in Δψ.
        assertEquals(133.167265, m.lonDeg, 0.0001)
        assertEquals(-3.229126, m.latDeg, 0.000001)
        assertEquals(368409.7, m.distanceKm, 0.1)
        assertEquals(134.688470, m.raDeg, 0.0001)
        assertEquals(13.768368, m.decDeg, 0.0001)
    }

    @Test
    fun sunMatchesMeeusExample25a() {
        val t = (2448908.5 - 2451545.0) / 36525.0 // 1992 October 13.0 TD
        val s = Sun.position(t, EarthOrientation.nutation(t))
        assertEquals(198.38083, s.raDeg, 0.00002)
        assertEquals(-7.78507, s.decDeg, 0.00002)
        assertEquals(0.99766, s.distanceAu, 0.00001)
    }

    @Test
    fun siderealTimeMatchesMeeusExamples12() {
        // 1987 April 10, 0h UT: 13h10m46.3668s; 19h21m00s UT: 8h34m57.0896s.
        assertEquals((13 + 10 / 60.0 + 46.3668 / 3600) * 15, TimeScales.gmstDeg(2446895.5), 1e-6)
        assertEquals((8 + 34 / 60.0 + 57.0896 / 3600) * 15, TimeScales.gmstDeg(2446896.30625), 1e-6)
    }

    // ---------------------------------------------------------------- astropy comparisons

    @Test
    fun apparentSiderealTimeMatchesAstropy() {
        var worst = 0.0
        var worstNoDut1 = 0.0
        for (r in reference().filter { it[0] == "GAST" }) {
            val ms = r[3].toLong()
            val dut1Ms = Math.round(r[5].toDouble() * 1000.0)
            // With astropy's UT1−UTC applied the formulae agree to < 0.5".
            worst = maxOf(worst, abs(norm180(SkyContext(ms + dut1Ms).gastDeg - r[4].toDouble())))
            // The app assumes UT1 = UTC: |UT1−UTC| < 0.9 s ⇒ < 0.00375°.
            worstNoDut1 = maxOf(worstNoDut1, abs(norm180(SkyContext(ms).gastDeg - r[4].toDouble())))
        }
        assertTrue("GAST worst $worst deg", worst < 0.00015)
        assertTrue("GAST (UT1=UTC) worst $worstNoDut1 deg", worstNoDut1 < 0.00375)
    }

    @Test
    fun targetAltAzMatchesAstropy() {
        var worst = 0.0
        var count = 0
        for (r in reference().filter { it[0] == "POS" }) {
            val e = Ephemeris(sites.getValue(r[1]), targets.getValue(r[2]))
            val s = e.sample(r[3].toLong())
            val sep = angularSeparationDeg(s.objAz, s.objAlt, r[5].toDouble(), r[4].toDouble())
            worst = maxOf(worst, sep)
            count++
        }
        assertTrue(count > 100)
        // 0.0015° = 5.4"; observed agreement is better than 3" (the remainder is UT1−UTC).
        assertTrue("target worst separation $worst deg", worst < 0.0015)
    }

    @Test
    fun sunAltAzMatchesAstropy() {
        var worst = 0.0
        for (r in reference().filter { it[0] == "SUN" }) {
            val e = Ephemeris(sites.getValue(r[1]), targets.getValue("M31"))
            val ctx = SkyContext(r[3].toLong())
            val site = sites.getValue(r[1])
            val aa = cl.astro.observability.astro.Horizon.altAz(
                ctx.sun.raDeg, ctx.sun.decDeg, ctx.lastDeg(site.lonDeg), site.latDeg,
            )
            assertEquals(e.sunAltitude(r[3].toLong()), aa.altDeg, 1e-9)
            worst = maxOf(worst, angularSeparationDeg(aa.azDeg, aa.altDeg, r[5].toDouble(), r[4].toDouble()))
        }
        // Meeus' low-precision Sun is good to 0.01°; the 8.8" solar parallax is not applied.
        assertTrue("sun worst separation $worst deg", worst < 0.012)
    }

    @Test
    fun moonAltAzAndIlluminationMatchAstropy() {
        var worst = 0.0
        var worstK = 0.0
        for (r in reference().filter { it[0] == "MOON" }) {
            val e = Ephemeris(sites.getValue(r[1]), targets.getValue("M31"))
            val s = e.sample(r[3].toLong())
            worst = maxOf(worst, angularSeparationDeg(s.moonAz, s.moonAlt, r[5].toDouble(), r[4].toDouble()))
            worstK = maxOf(worstK, abs(e.moonPhase(r[3].toLong()).illuminated - r[6].toDouble()))
        }
        // Topocentric Moon to better than 0.003° (11"; observed 4"); illuminated fraction to 0.2 %.
        assertTrue("moon worst separation $worst deg", worst < 0.003)
        assertTrue("moon illumination worst $worstK", worstK < 0.002)
    }

    @Test
    fun riseSetAndTwilightTimesMatchAstropy() {
        val rows = reference().filter { it[0] == "EVT" }
        assertTrue(rows.size >= 20)
        val cache = HashMap<String, cl.astro.observability.astro.NightResult>()
        for (r in rows) {
            val site = sites.getValue(r[1])
            val t = r[3].toLong()
            val rising = r[5] == "1"
            val level = r[4].toDouble()
            // The night whose local-mean-time noon→noon window contains the event.
            val lmt = Instant.ofEpochMilli(t + (site.lonDeg / 15.0 * 3.6e6).toLong()).atZone(ZoneOffset.UTC)
            val date = if (lmt.hour < 12) lmt.toLocalDate().minusDays(1) else lmt.toLocalDate()
            val night = cache.getOrPut("${r[1]}$date") {
                ObservabilityCalculator(site, targets.getValue("M31"), ObservabilityOptions()).night(date)
            }
            val ev = night.events
            val candidates: List<Long> = when (r[2]) {
                "sun" -> if (level < -10) listOfNotNull(if (rising) ev.astroDawn else ev.astroDusk)
                else listOfNotNull(if (rising) ev.sunrise else ev.sunset)
                "moon" -> if (rising) ev.moonrises else ev.moonsets
                else -> if (rising) ev.objectRises else ev.objectSets
            }
            val best = candidates.minByOrNull { abs(it - t) }
            assertTrue("no ${r[2]} event near ${Instant.ofEpochMilli(t)} at ${r[1]}", best != null)
            val dtSec = abs(best!! - t) / 1000.0
            val tol = 5.0 // observed agreement ≤ 1.2 s
            assertTrue("${r[2]} ${if (rising) "rise" else "set"} at ${r[1]} off by $dtSec s", dtSec < tol)
        }
    }

    // ---------------------------------------------------------------- internal consistency

    @Test
    fun airmassModels() {
        for (m in AirmassModel.values()) {
            assertEquals(1.0, m.airmass(90.0), 1e-3)
            for (x in listOf(1.1, 1.5, 2.0, 3.0)) assertEquals(x, m.airmass(m.altitudeForAirmass(x)), 1e-9)
        }
        assertEquals(2.0, AirmassModel.SEC_Z.airmass(30.0), 1e-12)
        assertEquals(1.9917, AirmassModel.YOUNG_1994.airmass(30.0), 1e-3)
        assertTrue(AirmassModel.YOUNG_1994.airmass(0.0).isInfinite())
    }

    @Test
    fun windowAgreesWithDetailedNights() {
        val calc = ObservabilityCalculator(sites.getValue("lasilla"), targets.getValue("NGC7531"), ObservabilityOptions())
        val start = LocalDate.of(2026, 11, 7)
        val w = calc.window(start, 10, sites.getValue("lasilla").zone)!!
        assertEquals(10, w.nights.size)
        for (wn in w.nights) {
            val n = calc.night(wn.date)
            assertEquals("dark hours ${wn.date}", n.darkHours, wn.darkHours, 0.05)
            assertEquals("observable hours ${wn.date}", n.observableHours, wn.observableHours, 0.05)
            assertEquals("moon-down hours ${wn.date}", n.observableMoonDownHours, wn.observableMoonDownHours, 0.05)
            assertEquals(n.events.sunset!!.toDouble(), wn.sunset!!.toDouble(), 5_000.0)
            assertEquals(n.events.astroDawn!!.toDouble(), wn.darkEnd!!.toDouble(), 5_000.0)
            assertEquals(n.moonPhase.illuminated, wn.moonPhase.illuminated, 1e-9)
            // The display midnight in local time is local civil midnight.
            val local = Instant.ofEpochMilli(wn.refMidnight).atZone(sites.getValue("lasilla").zone)
            assertEquals(0, local.hour)
            assertEquals(wn.date.plusDays(1), local.toLocalDate())
        }
    }

    @Test
    fun circumpolarAndNeverRisingTargets() {
        val calc = ObservabilityCalculator(sites.getValue("tromso"), targets.getValue("Polaris"), ObservabilityOptions())
        val n = calc.night(LocalDate.of(2026, 12, 21))
        assertTrue(n.events.objectRises.isEmpty() && n.events.objectSets.isEmpty())
        assertTrue(n.objAlt.all { abs(it - 69.6) < 1.5 })
        // Polar night: the Sun never rises; no sunset/sunrise but dark time exists.
        assertTrue(n.events.sunset == null && n.events.sunrise == null)
        val south = ObservabilityCalculator(sites.getValue("lapalma"), targets.getValue("NGC7531"), ObservabilityOptions())
            .night(LocalDate.of(2026, 11, 7))
        assertTrue(south.objAlt.all { it < 25.0 }) // δ ≈ −43.5° at φ = +28.8° culminates at ≈ 17.8°
        // Upper culmination altitude = 90° − φ + δ, with δ the apparent declination of date
        // (precession has moved it by +0.15° since J2000).
        val decOfDate = Ephemeris(sites.getValue("lapalma"), targets.getValue("NGC7531"))
            .targetApparent(SkyContext(south.events.transit)).latDeg
        assertEquals(-43.453, decOfDate, 0.01)
        assertEquals(90.0 - 28.758333 + decOfDate, south.events.transitAltDeg, 0.002)
    }

    @Test
    fun lmtWindowCoversTheNight() {
        val site = sites.getValue("lasilla")
        val calc = ObservabilityCalculator(site, targets.getValue("M31"), ObservabilityOptions())
        val n = calc.night(LocalDate.of(2026, 11, 7))
        val sunset = n.events.sunset!!
        val sunrise = n.events.sunrise!!
        assertTrue(sunset in n.windowStart..n.windowEnd && sunrise > sunset)
        assertTrue(n.plotStart == sunset - 3_600_000L && n.plotEnd == sunrise + 3_600_000L)
        // Sunset in Chile local time (UTC−3 in November) is in the evening of the 7th.
        val local = Instant.ofEpochMilli(sunset).atZone(site.zone)
        assertEquals(LocalDate.of(2026, 11, 7), local.toLocalDate())
        assertTrue(local.hour in 19..21)
        assertEquals(0.0, norm360(n.lst[0] - SkyContext(n.times[0]).lastDeg(site.lonDeg)), 1e-9)
    }
}
