package cl.astro.observability

import cl.astro.observability.data.Catalog
import cl.astro.observability.data.CoordinateParser
import cl.astro.observability.data.Names
import cl.astro.observability.data.SiteList
import cl.astro.observability.data.TargetResolver
import cl.astro.observability.data.SesameLookup
import cl.astro.observability.data.ServiceReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

class DataTest {
    private fun assetReader(name: String) = File("src/main/assets/$name").bufferedReader()

    @Test
    fun coordinateParsing() {
        val forms = listOf(
            "00:42:44.3 +41:16:09", "00 42 44.3 +41 16 09", "00h42m44.3s +41d16m09s",
            "00h42m44.3s, +41°16′09″", "10.684583 41.269167", "0.7123056h +41.269167",
        )
        for (f in forms) {
            val r = CoordinateParser.parse(f)
            assertNotNull(f, r)
            assertEquals(f, 10.684583, r!!.raDeg, 2e-4)
            assertEquals(f, 41.269167, r.decDeg, 2e-4)
        }
        val south = CoordinateParser.parse("23:14:48.5 -43:35:59.8")!!
        assertEquals(348.702083, south.raDeg, 1e-5)
        assertEquals(-43.599944, south.decDeg, 1e-5)
        val minusZero = CoordinateParser.parse("05 35 17 −00 25 00")!!
        assertEquals(-0.416667, minusZero.decDeg, 1e-5)
        assertNull(CoordinateParser.parse("25:00:00 +10:00:00"))
        assertNull(CoordinateParser.parse("10:00:00 +95:00:00"))
        assertNull(CoordinateParser.parse("10:61:00 +10:00:00"))
        assertNull(CoordinateParser.parse("NGC 7531"))
        assertEquals("00h42m44.3s", CoordinateParser.formatRa(10.684583))
        assertEquals("+41°16′09″", CoordinateParser.formatDec(41.269167))
        assertEquals("-43°36′00″", CoordinateParser.formatDec(-43.599944))
    }

    @Test
    fun nameNormalisation() {
        assertEquals("NGC224", Names.normalize("NGC 0224"))
        assertEquals("M31", Names.normalize("Messier 31"))
        assertEquals("M31", Names.normalize("m 031"))
        assertEquals(Names.normalize("NGC7531"), Names.normalize("NGC 7531"))
    }

    @Test
    fun catalogueLookups() {
        val cat = Catalog.parse(assetReader("objects.tsv"))
        assertTrue(cat.size > 13000)
        val m31 = cat.lookup("Andromeda Galaxy")!!
        assertEquals("M31", m31.name)
        assertEquals(10.684792, m31.raDeg, 1e-5)
        assertEquals(m31, cat.lookup("NGC 224"))
        assertEquals(m31, cat.lookup("m31"))
        assertEquals(m31, cat.lookup("Messier 31"))
        assertEquals("M82", cat.lookup("NGC3034")!!.name)
        assertEquals("M82", cat.lookup("Cigar Galaxy")!!.name)
        val n7531 = cat.lookup("NGC7531")!!
        assertEquals(-43.599944, n7531.decDeg, 1e-5)
        assertEquals("M45", cat.lookup("Pleiades")!!.name)
        assertEquals("Betelgeuse", cat.lookup("alpha Ori")!!.name)
        assertEquals("Vega", cat.lookup("alf Lyr")!!.name)
        assertEquals("Vega", cat.lookup("HD 172167")!!.name)
        assertNotNull(cat.lookup("Large Magellanic Cloud"))
        for (m in 1..110) assertNotNull("M$m", cat.lookup("M$m"))
        assertEquals("M31", cat.suggest("androm").first().second.name)
        assertEquals("M8", cat.suggest("M8").first().second.name)
    }

    @Test
    fun observatoryList() {
        val sites = SiteList.parse(assetReader("sites.tsv"))
        assertTrue(sites.size > 90)
        for (s in sites) {
            assertTrue(s.key, s.latDeg in -90.0..90.0 && s.lonDeg in -180.0..180.0)
            ZoneId.of(s.zoneId) // throws if invalid
        }
        val ls = sites.first { it.key == "lasilla" }
        assertEquals(-29.2567, ls.latDeg, 1e-3)
        assertEquals(-70.73, ls.lonDeg, 1e-3)
        assertTrue(sites.any { it.key == "lapalma" })
        assertTrue(sites.any { it.key == "picdumidi" })
    }

    @Test
    fun resolverOfflineAndSesameParsing() {
        val cat = Catalog.parse(assetReader("objects.tsv"))
        val res = TargetResolver(cat)
        fun found(q: String) = (res.resolveOffline(q) as TargetResolver.Outcome.Found).target
        assertEquals("M82", found("M 82").name)
        val c = found("23:14:48.5 -43:35:59.8")
        assertEquals(348.702083, c.raDeg, 1e-5)
        assertNull(res.resolveOffline("HD 12345678"))

        val sesame = """
            # HD 172167	#Q123
            #=S=Simbad (via url):    1
            %@ 1234567
            %I.0 * alf Lyr
            %C.0 dS*
            %J 279.23473479 +38.78368896 = 18:36:56.33 +38:47:01.2
            %J.E [3.11 3.22 90] A 2007A&A...474..653V
            %P +200.94 +286.23 [0.32 0.40 0] A 2007A&A...474..653V
        """.trimIndent()
        val h = SesameLookup.parse(sesame) as ServiceReply.Hit
        assertEquals("alf Lyr", h.designation)
        assertEquals("CDS Sesame / SIMBAD", h.service)
        assertEquals(279.23473479, h.raDeg, 1e-9)
        assertEquals(38.78368896, h.decDeg, 1e-9)
        assertEquals(200.94, h.pmRa, 1e-9)
        assertEquals(286.23, h.pmDec, 1e-9)
        val t = TargetResolver.hitToTarget("HD 172167", h)
        assertEquals("HD 172167", t.name)
        assertEquals(listOf("alf Lyr"), t.aliases)
        assertTrue(SesameLookup.parse("# xyz\n#! *** Nothing found ***") is ServiceReply.Miss)
    }
}
