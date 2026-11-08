package cl.astro.observability

import cl.astro.observability.data.Catalog
import cl.astro.observability.data.Json
import cl.astro.observability.data.NedLookup
import cl.astro.observability.data.ObjectTypes
import cl.astro.observability.data.ResolvedCache
import cl.astro.observability.data.SesameLookup
import cl.astro.observability.data.ServiceReply
import cl.astro.observability.data.TargetResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Online name resolution (NED ObjectLookup, CDS Sesame) on recorded-style replies, the decision
 * between the two services, the cache, and the offline common-name matching.
 * The NED replies follow the examples of https://ned.ipac.caltech.edu/Documents/Guides/Interface/ObjectLookup
 */
class ResolverTest {
    private val catalog by lazy { Catalog.parse(File("src/main/assets/objects.tsv").bufferedReader()) }

    private val nedM31 = """{"QueryTime": "Fri Feb 24 17:34:40 2017", "ResultCode": 3, "Copyright": "(C) 2017 California
        Institute of Technology", "Version": "2.0", "Supplied": "m31", "Interpreted": {"Name": "MESSIER 031"},
        "StatusCode": 100, "Preferred": {"Redshift": {"Value": -0.00100100006, "Uncertainty": 1.29999999e-05,
        "RefCode": "1991RC3.9.C...0000d", "QualityFlag": null}, "Name": "MESSIER 031", "Position": {"RA": 10.68479292,
        "Dec": 41.269065, "PosAngle": 0, "UncSemiMinor": 2.222222222e-05, "UncSemiMajor": 2.222222222e-05,
        "RefCode": "2010ApJS..189...37E"}, "ObjType": {"RefCode": null, "Value": "G"}}, "NameResolver": "NED-Egret"}"""
            .replace("\n", " ")

    private val nedCartwheel = """{"QueryTime":"Sat Nov  7 10:00:00 2026","Copyright":"(C) 2026 California Institute of
        Technology","Version":"2.0","Supplied":"Cartwheel Galaxy","Interpreted":{"Name":"Cartwheel Galaxy"},
        "NameResolver":"NED-Egret","StatusCode":100,"ResultCode":3,"Preferred":{"Name":"ESO 350-G 040",
        "Position":{"RA":9.4214,"Dec":-33.7161,"PosAngle":0,"UncSemiMajor":1e-4,"UncSemiMinor":1e-4,"RefCode":"x"},
        "ObjType":{"Value":"G","RefCode":null},"Redshift":{"Value":0.0298,"Uncertainty":1e-4,"RefCode":"y",
        "QualityFlag":null}}}""".replace("\n", " ")

    private val nedAmbiguous = """{"QueryTime": "Fri Feb 24 17:30:09 2017", "Copyright": "(C) 2017 California Institute
        of Technology", "Supplied": "andromeda", "Version": "2.0", "Interpreted": {"Aliases": ["ANDROMEDA GALAXY",
        "Andromeda GROUP", "ANDROMEDA I", "ANDROMEDA II", "ANDROMEDA GALAXY"]}, "NameResolver": "NED-Egret",
        "StatusCode": 100, "ResultCode": 1}""".replace("\n", " ")

    private val nedUnknown = """{"QueryTime": "Fri Feb 24 17:32:51 2017", "Interpreted": {"Name": "SN 1999"},
        "Copyright": "(C) 2017 California Institute of Technology", "Message": "Unknown name", "Supplied": "SN1999",
        "Version": "2.0", "NameResolver": "NED-Egret", "StatusCode": 100, "ResultCode": 2}""".replace("\n", " ")

    private val nedNotAName = """{"QueryTime": "Fri Feb 24 17:30:00 2017", "NameResolver": "NED-Egret", "Copyright":
        "(C) 2017 California Institute of Technology", "Supplied": "abc", "Version": "2.0", "StatusCode": 100,
        "ResultCode": 0}""".replace("\n", " ")

    private val sesameCartwheel = """
        # Cartwheel Galaxy	#Q2224301
        #=S=Simbad (CDS, via client/server):    1
        %@ 3017823
        %I.0 ESO 350-40
        %C.0 GiG
        %J 009.4212500 -33.7163889 = 00:37:41.10 -33:42:59.0
        %J.E [5000.00 5000.00 0] D 2003A&A...412...45P
        %V z 0.03000 D [0.00001] 1996ApJS..102..269P
        %I NAME Cartwheel Galaxy
        #====Done (2026-Nov-07,10:00:00z)====
    """.trimIndent()

    private val sesameSirius = """
        # Sirius	#Q1
        #=S=Simbad (via url):    1
        %@ 2
        %I.0 * alf CMa
        %C.0 SB*
        %J 101.28715533 -16.71611586 = 06:45:08.91 -16:42:58.0
        %P -546.01 -1223.07 [0.33 0.29 0] A 2007A&A...474..653V
    """.trimIndent()

    private val sesameNedBlock = """
        # UGC 10214	#Q9
        #!S=Simbad (CDS, via client/server):    *** Nothing found ***
        #=N=NED (local):    1
        %I.0 UGC 10214
        %C.0 G
        %J 241.5537500 +55.4253333 = 16:06:12.90 +55:25:31.2
    """.trimIndent()

    private val sesameNothing = """
        # qwertyuiop	#Q5
        #! *** Nothing found ***
    """.trimIndent()

    // ------------------------------------------------------------------ parsers

    @Test
    fun jsonParser() {
        val v = Json.parse("""{"a": [1, -2.5e3, true, null, "x\"yé\n"], "b": {"c": {}}, "d": []}""") as Map<*, *>
        val a = v["a"] as List<*>
        assertEquals(1.0, a[0])
        assertEquals(-2500.0, a[1])
        assertEquals(true, a[2])
        assertNull(a[3])
        assertEquals("x\"yé\n", a[4])
        assertTrue(Json.path(v, "b", "c") is Map<*, *>)
        assertNull(Json.path(v, "b", "missing", "x"))
        for (bad in listOf("{", "{\"a\" 1}", "[1,]x", "\"abc", "{\"a\":tru}")) {
            try {
                Json.parse(bad)
                throw AssertionError("parsed invalid JSON: $bad")
            } catch (e: Json.ParseException) {
                // expected
            }
        }
    }

    @Test
    fun nedReplies() {
        val m31 = NedLookup.parse(nedM31) as ServiceReply.Hit
        assertEquals("M31", m31.designation)
        assertEquals(10.68479292, m31.raDeg, 1e-9)
        assertEquals(41.269065, m31.decDeg, 1e-9)
        assertEquals("Galaxy", m31.type)
        assertEquals("NED", m31.service)
        assertTrue(m31.extragalactic)

        val amb = NedLookup.parse(nedAmbiguous) as ServiceReply.Candidates
        assertEquals(listOf("ANDROMEDA GALAXY", "Andromeda GROUP", "ANDROMEDA I", "ANDROMEDA II"), amb.names)

        assertTrue(NedLookup.parse(nedUnknown) is ServiceReply.Miss)
        assertTrue(NedLookup.parse(nedNotAName) is ServiceReply.Miss)
        assertTrue(NedLookup.parse("<html>Service unavailable</html>") is ServiceReply.Failed)
        assertTrue(NedLookup.parse("""{"StatusCode": 500, "Message": "Internal error"}""") is ServiceReply.Failed)
        // Known name without a position, or an impossible one: not usable.
        assertTrue(NedLookup.parse("""{"StatusCode":100,"ResultCode":3,"Preferred":{"Name":"X"}}""") is ServiceReply.Miss)
        assertTrue(NedLookup.parse("""{"StatusCode":100,"ResultCode":3,"Preferred":{"Name":"X",
            "Position":{"RA":400,"Dec":10}}}""") is ServiceReply.Miss)
        // Galactic objects are flagged with "!".
        val pn = NedLookup.parse("""{"StatusCode":100,"ResultCode":3,"Preferred":{"Name":"NGC 6543",
            "Position":{"RA":"269.639","Dec":"66.633"},"ObjType":{"Value":"!PN"}}}""") as ServiceReply.Hit
        assertFalse(pn.extragalactic)
        assertEquals("Planetary nebula", pn.type)
        assertEquals(269.639, pn.raDeg, 1e-9)
    }

    @Test
    fun sesameReplies() {
        val cw = SesameLookup.parse(sesameCartwheel) as ServiceReply.Hit
        assertEquals("ESO 350-40", cw.designation)
        assertEquals(9.42125, cw.raDeg, 1e-9)
        assertEquals(-33.7163889, cw.decDeg, 1e-9)
        assertEquals("Galaxy (in group)", cw.type)
        assertTrue(cw.extragalactic)
        assertEquals("CDS Sesame / SIMBAD", cw.service)

        val sirius = SesameLookup.parse(sesameSirius) as ServiceReply.Hit
        assertEquals("alf CMa", sirius.designation)
        assertEquals(-546.01, sirius.pmRa, 1e-9)
        assertEquals(-1223.07, sirius.pmDec, 1e-9)
        assertFalse(sirius.extragalactic)

        val tadpole = SesameLookup.parse(sesameNedBlock) as ServiceReply.Hit
        assertEquals("CDS Sesame / NED", tadpole.service)
        assertEquals("UGC 10214", tadpole.designation)
        assertEquals(241.55375, tadpole.raDeg, 1e-9)
        assertTrue(tadpole.extragalactic)

        assertTrue(SesameLookup.parse(sesameNothing) is ServiceReply.Miss)
    }

    @Test
    fun namesForDisplay() {
        assertEquals("M31", ObjectTypes.cleanDesignation("MESSIER 031"))
        assertEquals("M31", ObjectTypes.cleanDesignation("M  31"))
        assertEquals("NGC 224", ObjectTypes.cleanDesignation("NGC 0224"))
        assertEquals("IC 342", ObjectTypes.cleanDesignation("IC 0342"))
        assertEquals("Cartwheel Galaxy", ObjectTypes.cleanDesignation("NAME Cartwheel Galaxy"))
        assertEquals("UGC 02188", ObjectTypes.cleanDesignation("UGC 02188"))
        assertEquals("Cartwheel Galaxy", ObjectTypes.tidyName("cartwheel   galaxy"))
        assertEquals("Leo Triplet", ObjectTypes.tidyName("LEO TRIPLET"))
        assertEquals("Leo II", ObjectTypes.tidyName("leo ii"))
        assertEquals("Hercules A", ObjectTypes.tidyName("hercules a"))
        assertEquals("Hoag's Object", ObjectTypes.tidyName("Hoag's Object"))
        assertEquals("ARP 220", ObjectTypes.tidyName("arp220"))
        assertEquals("3C 273", ObjectTypes.tidyName("3c 273"))
        assertEquals("ESO 350-40", ObjectTypes.tidyName("eso350-40"))
        assertEquals("M31" to emptyList<String>(), ObjectTypes.displayName("messier 31", "M31"))
        assertEquals("Cartwheel Galaxy" to listOf("ESO 350-G 040"), ObjectTypes.displayName("cartwheel galaxy", "ESO 350-G 040"))
    }

    // ------------------------------------------------------------------ choosing between the services

    private fun hit(ra: Double, dec: Double, service: String, extragalactic: Boolean, pm: Double = 0.0) =
        ServiceReply.Hit("X 1", ra, dec, "t", service, pm, pm, extragalactic)

    @Test
    fun decisionBetweenNedAndSesame() {
        val q = "test object"
        // Galaxy: NED's position is used even when SIMBAD also knows it.
        var o = TargetResolver.decide(q, hit(10.0, 20.0, "NED", true), hit(10.001, 20.0, "CDS Sesame / SIMBAD", true))
        assertEquals("NED (online)", (o as TargetResolver.Outcome.Found).target.source)
        // Star: SIMBAD (with proper motion) is preferred.
        o = TargetResolver.decide(q, hit(10.0, 20.0, "NED", false), hit(10.0, 20.0, "CDS Sesame / SIMBAD", false, 50.0))
        val t = (o as TargetResolver.Outcome.Found).target
        assertEquals("CDS Sesame / SIMBAD (online)", t.source)
        assertEquals(50.0, t.pmRaCosDecMasYr, 0.0)
        // Only one service knows it.
        o = TargetResolver.decide(q, ServiceReply.Miss("no"), hit(1.0, 2.0, "CDS Sesame / SIMBAD", false))
        assertEquals(1.0, (o as TargetResolver.Outcome.Found).target.raDeg, 0.0)
        o = TargetResolver.decide(q, hit(3.0, 4.0, "NED", false), ServiceReply.Failed("down"))
        assertEquals(3.0, (o as TargetResolver.Outcome.Found).target.raDeg, 0.0)
        // Different objects under the same name: the user chooses.
        o = TargetResolver.decide(q, hit(10.0, 20.0, "NED", true), hit(10.0, 21.0, "CDS Sesame / SIMBAD", true))
        assertEquals(2, (o as TargetResolver.Outcome.Ambiguous).choices.size)
        assertTrue(o.choices.all { it.target != null })
        // Ambiguous NED name and nothing from Sesame: NED's candidates are offered.
        o = TargetResolver.decide(q, NedLookup.parse(nedAmbiguous), ServiceReply.Miss("no"))
        assertEquals("ANDROMEDA GALAXY", (o as TargetResolver.Outcome.Ambiguous).choices.first().query)
        // Nothing anywhere / no network.
        o = TargetResolver.decide(q, ServiceReply.Miss("NED: no object of that name"), ServiceReply.Miss("Sesame: nothing found"))
        assertTrue((o as TargetResolver.Outcome.NotFound).message.contains("was not found"))
        o = TargetResolver.decide(q, ServiceReply.Failed("NED: no connection"), ServiceReply.Failed("Sesame: no connection"))
        assertTrue((o as TargetResolver.Outcome.NotFound).message.contains("could not be reached"))
    }

    @Test
    fun fullResolutionWithFakeNetworkAndCache() {
        val calls = AtomicInteger(0)
        val cache = ResolvedCache()
        val net: (String) -> String = { url ->
            calls.incrementAndGet()
            when {
                url.startsWith(NedLookup.URL) -> {
                    assertTrue(url, url.endsWith("Cartwheel%20Galaxy"))
                    nedCartwheel
                }
                url.startsWith(SesameLookup.MIRRORS[0]) -> throw UnknownHostException("cds.unistra.fr")
                url.startsWith(SesameLookup.MIRRORS[1]) -> sesameCartwheel
                else -> throw IOException("unexpected $url")
            }
        }
        val r = TargetResolver(catalog, cache, net)
        val t = (r.resolve("Cartwheel Galaxy") as TargetResolver.Outcome.Found).target
        assertEquals("Cartwheel Galaxy", t.name)
        assertEquals(listOf("ESO 350-G 040"), t.aliases)
        assertEquals("NED (online)", t.source)
        assertEquals(9.4214, t.raDeg, 1e-9)
        assertEquals(3, calls.get()) // NED + both Sesame mirrors
        // Afterwards the name is known offline (any spelling that normalises the same way).
        val offline = TargetResolver(catalog, cache) { throw IOException("offline") }
        assertEquals(9.4214, (offline.resolve("cartwheel galaxy") as TargetResolver.Outcome.Found).target.raDeg, 1e-9)
        // The cache survives serialisation.
        val copy = ResolvedCache.parse(cache.serialize())
        assertEquals(t, copy.get("CARTWHEEL GALAXY"))
        // Built-in names never touch the network.
        val noNet = TargetResolver(catalog, null) { throw AssertionError("network used for $it") }
        assertEquals("M104", (noNet.resolve("the Sombrero galaxy") as TargetResolver.Outcome.Found).target.name)
    }

    @Test
    fun cacheKeepsTheMostRecentNames() {
        val c = ResolvedCache(max = 3)
        val t = cl.astro.observability.astro.Target("a", 1.0, 2.0)
        for (n in listOf("one", "two", "three")) c.put(n, t.copy(name = n))
        c.get("one") // touch: "two" is now the oldest
        c.put("four", t.copy(name = "four"))
        assertEquals(3, c.size())
        assertNull(c.get("two"))
        assertEquals("one", c.get("ONE")!!.name)
    }

    // ------------------------------------------------------------------ offline common names

    @Test
    fun offlineCommonNames() {
        fun name(q: String) = (catalog.match(q) as? Catalog.Match.Found)?.entry?.name
        assertEquals("M104", name("the Sombrero galaxy"))
        assertEquals("M104", name("Sombrero"))
        assertEquals("M1", name("Crab"))
        assertEquals("M51", name("Whirlpool"))
        assertEquals("M31", name("Andromeda"))
        assertEquals("M101", name("Pinwheel Galaxy"))
        assertEquals("NGC 253", name("Sculptor Galaxy"))
        assertEquals("NGC 5128", name("Centaurus A"))
        assertEquals("NGC 104", name("47 Tuc"))
        assertEquals("ESO 56-115", name("LMC"))
        // The two Antennae galaxies are one target for observability purposes.
        assertTrue(name("Antennae galaxy") in setOf("NGC 4038", "NGC 4039"))
        // Several distinct objects: the user chooses.
        val catsEye = catalog.match("Cat's Eye") as Catalog.Match.Ambiguous
        assertEquals(setOf("M94", "NGC 6543"), catsEye.entries.map { it.name }.toSet())
        val sculptor = catalog.match("Sculptor") as Catalog.Match.Ambiguous
        assertTrue(sculptor.entries.any { it.name == "NGC 253" })
        assertTrue(sculptor.entries.size >= 2)
        // Designations are never completed by prefix.
        assertTrue(catalog.match("NGC 99999") is Catalog.Match.None)
        assertTrue(catalog.match("UGC 99999") is Catalog.Match.None)
        assertTrue(catalog.match("Cartwheel Galaxy") is Catalog.Match.None)
    }
}
