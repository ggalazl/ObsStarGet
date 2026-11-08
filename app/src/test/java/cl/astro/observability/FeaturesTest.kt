package cl.astro.observability

import cl.astro.observability.astro.Ephemeris
import cl.astro.observability.astro.ObservabilityCalculator
import cl.astro.observability.astro.ObservabilityOptions
import cl.astro.observability.astro.Site
import cl.astro.observability.astro.Target
import cl.astro.observability.data.Catalog
import cl.astro.observability.report.VisibilityTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import kotlin.math.abs

/** Multi-target comparison, visibility table, Moon age, night range and catalogue look-ups. */
class FeaturesTest {
    private val lasilla = Site("lasilla", "La Silla", -29.256667, -70.73, 2347.0, "America/Santiago")
    private val ngc7531 = Target("NGC 7531", 348.702083, -43.599944)
    private val m42 = Target("M42", 83.818667, -5.389667)
    private val ngc253 = Target("NGC 253", 11.888, -25.288)
    private val m31 = Target("M31", 10.684792, 41.269056)

    @Test
    fun moonAgeMatchesAstropyNewMoons() {
        val rows = javaClass.classLoader!!.getResourceAsStream("astropy_reference.tsv")!!.bufferedReader()
            .readLines().map { it.split('\t') }.filter { it[0] == "NEWMOON" }
        assertTrue(rows.size >= 8)
        val eph = Ephemeris(lasilla, m31)
        var worst = 0.0
        for (r in rows) {
            val tNew = r[3].toLong()
            val found = eph.lastNewMoon(tNew + 3 * 86_400_000L)
            worst = maxOf(worst, abs(found - tNew) / 1000.0)
            assertEquals(3.0, eph.moonAgeDays(tNew + 3 * 86_400_000L), 0.002)
        }
        // Meeus' Moon and Sun reproduce the instant of new Moon to well within a minute.
        assertTrue("worst new-Moon difference $worst s", worst < 60.0)
    }

    @Test
    fun targetsShareSunAndMoonAndTableIsSortedByBestTime() {
        val date = LocalDate.of(2026, 11, 24)
        val nights = listOf(m42, ngc7531, m31, ngc253).map { ObservabilityCalculator(lasilla, it, ObservabilityOptions()).night(date) }
        for (n in nights.drop(1)) {
            assertTrue(n.times.contentEquals(nights[0].times))
            assertTrue(n.sunAlt.contentEquals(nights[0].sunAlt))
            assertTrue(n.moonAlt.contentEquals(nights[0].moonAlt))
        }
        val rows = VisibilityTable.rows(nights)
        assertEquals(4, rows.size)
        val times = rows.mapNotNull { it.bestTime }
        assertEquals(times.sorted(), times)
        // M42 (RA 5.6 h) is best late in the night, NGC 7531 (RA 23.2 h) at the start of dark time.
        assertEquals("NGC 7531", rows.first().result.target.name)
        assertEquals("M42", rows.last().result.target.name)
        for (row in rows) {
            val r = row.result
            val i = r.bestDarkIndex!!
            assertTrue(r.sunAlt[i] < -18.0)
            for (k in r.times.indices) if (r.sunAlt[k] < -18.0 && r.objAlt[k] > 0) assertTrue(r.airmass[k] >= r.airmass[i])
            assertEquals(r.observableHours, row.observableHours, 1e-12)
        }
        // M31 from La Silla never gets below X = 2: no observable time, but a best moment exists.
        val m31row = rows.first { it.result.target.name == "M31" }
        assertEquals(0.0, m31row.observableHours, 0.0)
        assertTrue(m31row.bestAirmass > 2.9 && m31row.firstObservable == null)
        // Moon separation: full Moon on 24 Nov 2026 is within ~40° of M42.
        val m42row = rows.first { it.result.target.name == "M42" }
        assertTrue(m42row.minMoonSeparation in 20.0..50.0)
    }

    @Test
    fun fullDayRange() {
        val n = ObservabilityCalculator(lasilla, ngc7531, ObservabilityOptions(fullDay = true)).night(LocalDate.of(2026, 11, 7))
        assertEquals(n.windowStart, n.plotStart)
        assertEquals(n.windowEnd, n.plotEnd)
        assertEquals(24.0, (n.plotEnd - n.plotStart) / 3.6e6, 1e-9)
        val d = ObservabilityCalculator(lasilla, ngc7531, ObservabilityOptions()).night(LocalDate.of(2026, 11, 7))
        // Same physics, only the plotted range differs.
        assertEquals(d.observableHours, n.observableHours, 1e-12)
        assertTrue(d.plotEnd - d.plotStart < 14 * 3_600_000L)
    }

    @Test
    fun shortenedCommonNames() {
        val cat = Catalog.parse(File("src/main/assets/objects.tsv").bufferedReader())
        assertEquals("M104", cat.lookup("Sombrero")!!.name)
        assertEquals("M51", cat.lookup("Whirlpool")!!.name)
        assertEquals("NGC 104", cat.lookup("47 Tuc")!!.name)
        assertEquals("NGC 5139", cat.lookup("Omega Cen")!!.name)
        assertEquals("ESO 56-115", cat.lookup("LMC")!!.name)
        assertEquals("NGC 292", cat.lookup("SMC")!!.name)
        assertEquals("Sgr A*", cat.lookup("Sgr A*")!!.name)
        assertEquals(266.416837, cat.lookup("Galactic Center")!!.raDeg, 1e-6)
        assertNull(cat.lookup("alpha"))          // ambiguous prefix
        assertNull(cat.lookup("HD 99999999"))    // designations never match by prefix
        assertNull(cat.lookup("NGC 99999"))
    }
}
