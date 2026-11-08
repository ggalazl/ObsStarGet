package cl.astro.observability.data

import java.util.Locale

/** What one online service said about a name. */
sealed class ServiceReply {
    /** Position found. [extragalactic] is true when the service classifies the object as such. */
    class Hit(
        val designation: String,
        val raDeg: Double,
        val decDeg: Double,
        val type: String,
        val service: String,
        val pmRa: Double = 0.0,
        val pmDec: Double = 0.0,
        val extragalactic: Boolean = false,
    ) : ServiceReply()

    /** The name fits several objects (NED "ambiguous name"): candidate names to choose from. */
    class Candidates(val names: List<String>) : ServiceReply()

    /** The service answered but does not know the object. */
    class Miss(val reason: String) : ServiceReply()

    /** The service could not be reached or answered with something unreadable. */
    class Failed(val reason: String) : ServiceReply()
}

/**
 * NED ObjectLookup (https://ned.ipac.caltech.edu/srs/ObjectLookup, JSON, version 2.x).
 *
 * ResultCode 3 = known object (Preferred.Name, Preferred.Position.RA/Dec in degrees J2000,
 * Preferred.ObjType.Value); 1 = ambiguous name (Interpreted.Aliases lists candidates);
 * 2 = valid name syntax but unknown to NED; 0 = not an object name.
 */
object NedLookup {
    const val URL = "https://ned.ipac.caltech.edu/srs/ObjectLookup?name="

    fun parse(json: String): ServiceReply {
        val root = try {
            Json.parse(json) as? Map<*, *>
        } catch (e: Json.ParseException) {
            null
        } ?: return ServiceReply.Failed("NED sent an unreadable reply")
        val status = Json.number(root["StatusCode"])?.toInt()
        if (status != null && status != 100) {
            val msg = Json.string(root["Message"]) ?: "status $status"
            return ServiceReply.Failed("NED: $msg")
        }
        return when (val code = Json.number(root["ResultCode"])?.toInt()) {
            3 -> {
                val pos = Json.path(root, "Preferred", "Position")
                val ra = Json.number(Json.path(pos, "RA"))
                val dec = Json.number(Json.path(pos, "Dec"))
                if (ra == null || dec == null || ra !in 0.0..360.0 || dec !in -90.0..90.0) {
                    ServiceReply.Miss("NED knows the name but gives no position")
                } else {
                    val name = Json.string(Json.path(root, "Preferred", "Name"))
                        ?: interpretedName(root) ?: Json.string(root["Supplied"]) ?: ""
                    val code3 = Json.string(Json.path(root, "Preferred", "ObjType", "Value")) ?: ""
                    ServiceReply.Hit(
                        designation = ObjectTypes.cleanDesignation(name),
                        raDeg = ra,
                        decDeg = dec,
                        type = ObjectTypes.describeNed(code3),
                        service = "NED",
                        extragalactic = ObjectTypes.nedIsExtragalactic(code3),
                    )
                }
            }
            1 -> {
                val list = (Json.path(root, "Interpreted", "Aliases") as? List<*>)
                    ?: (root["Aliases"] as? List<*>) ?: emptyList<Any?>()
                val names = list.mapNotNull { Json.string(it)?.trim() }.filter { it.isNotEmpty() }.distinct()
                if (names.isEmpty()) ServiceReply.Miss("NED: ambiguous name") else ServiceReply.Candidates(names)
            }
            2 -> ServiceReply.Miss("NED: no object of that name")
            0 -> ServiceReply.Miss("NED: not recognised as an object name")
            else -> ServiceReply.Failed("NED: unexpected reply (ResultCode $code)")
        }
    }

    private fun interpretedName(root: Map<*, *>): String? = when (val i = root["Interpreted"]) {
        is String -> i
        is Map<*, *> -> Json.string(i["Name"])
        else -> null
    }
}

/**
 * CDS Sesame plain-text output (-oI): "%J ra dec = ..." gives ICRS J2000 degrees, "%I.0" the main
 * identifier, "%C.0" the object type and "%P" the proper motion in mas/yr; "#=S=Simbad",
 * "#=N=NED", "#=V=VizieR" open the block of each database. The first block with a position is used.
 */
object SesameLookup {
    val MIRRORS = listOf(
        "https://cds.unistra.fr/cgi-bin/nph-sesame/-oI/SNV?",
        "https://vizier.cfa.harvard.edu/viz-bin/nph-sesame/-oI/SNV?",
    )

    private val jLine = Regex("""^%J\s+([+-]?\d+(?:\.\d+)?)\s+([+-]?\d+(?:\.\d+)?)""")
    private val pLine = Regex("""^%P\s+([+-]?\d+(?:\.\d+)?)\s+([+-]?\d+(?:\.\d+)?)""")
    private val block = Regex("""^#=(\w)=([A-Za-z]+)""")

    private class Block(val db: String) {
        var ra: Double? = null
        var dec: Double? = null
        var id: String? = null
        var type = ""
        var pmRa = 0.0
        var pmDec = 0.0
        var hasPm = false
    }

    fun parse(text: String): ServiceReply {
        val blocks = ArrayList<Block>()
        var cur = Block("")
        blocks.add(cur)
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            val b = block.find(line)
            if (b != null) {
                cur = Block(b.groupValues[2])
                blocks.add(cur)
                continue
            }
            when {
                cur.ra == null && line.startsWith("%J ") -> jLine.find(line)?.let {
                    cur.ra = it.groupValues[1].toDouble()
                    cur.dec = it.groupValues[2].toDouble()
                }
                cur.id == null && line.startsWith("%I.0 ") -> cur.id = line.removePrefix("%I.0 ").trim()
                cur.type.isEmpty() && line.startsWith("%C.0 ") -> cur.type = line.removePrefix("%C.0 ").trim()
                !cur.hasPm && line.startsWith("%P ") -> pLine.find(line)?.let {
                    cur.pmRa = it.groupValues[1].toDouble()
                    cur.pmDec = it.groupValues[2].toDouble()
                    cur.hasPm = true
                }
            }
        }
        val hit = blocks.firstOrNull { it.ra != null && it.dec != null }
            ?: return ServiceReply.Miss("Sesame: nothing found in SIMBAD, NED or VizieR")
        val r = hit.ra!!
        val d = hit.dec!!
        if (r !in 0.0..360.0 || d !in -90.0..90.0) return ServiceReply.Failed("Sesame sent an invalid position")
        val db = when (hit.db.lowercase(Locale.US)) {
            "simbad" -> "SIMBAD"
            "ned" -> "NED"
            "vizier" -> "VizieR"
            else -> hit.db.ifEmpty { "Sesame" }
        }
        val fromNed = db == "NED"
        return ServiceReply.Hit(
            designation = ObjectTypes.cleanDesignation(hit.id ?: ""),
            raDeg = r,
            decDeg = d,
            type = if (fromNed) ObjectTypes.describeNed(hit.type) else ObjectTypes.describeSimbad(hit.type),
            service = "CDS Sesame / $db",
            pmRa = hit.pmRa,
            pmDec = hit.pmDec,
            extragalactic = if (fromNed) ObjectTypes.nedIsExtragalactic(hit.type) else ObjectTypes.simbadIsExtragalactic(hit.type),
        )
    }
}

/** Object-type codes of NED and SIMBAD in words, and tidy designations. */
object ObjectTypes {
    private val ned = mapOf(
        "G" to "Galaxy", "GPair" to "Galaxy pair", "GTrpl" to "Galaxy triple", "GGroup" to "Galaxy group",
        "GClstr" to "Galaxy cluster", "QSO" to "Quasar", "QGroup" to "Quasar group", "Q_Lens" to "Lensed quasar",
        "G_Lens" to "Lensed galaxy", "AbLS" to "Absorption-line system", "EmLS" to "Emission-line source",
        "RadioS" to "Radio source", "SmmS" to "Submillimetre source", "IrS" to "Infrared source",
        "VisS" to "Optical source", "UvES" to "UV-excess source", "UvS" to "UV source", "XrayS" to "X-ray source",
        "GammaS" to "Gamma-ray source", "*" to "Star", "**" to "Double star", "*Cl" to "Star cluster",
        "V*" to "Variable star", "WD*" to "White dwarf", "PN" to "Planetary nebula", "SNR" to "Supernova remnant",
        "HII" to "HII region", "DNe" to "Dark nebula", "RfN" to "Reflection nebula", "EmObj" to "Emission object",
        "Neb" to "Nebula", "MCld" to "Molecular cloud", "Nova" to "Nova", "SN" to "Supernova", "Other" to "",
    )

    // SIMBAD short types (current and pre-2022 codes) for the most common objects.
    private val simbad = mapOf(
        "G" to "Galaxy", "GiG" to "Galaxy (in group)", "GiC" to "Galaxy (in cluster)", "GiP" to "Galaxy (in pair)",
        "IG" to "Interacting galaxies", "PaG" to "Galaxy pair", "GrG" to "Galaxy group", "CGG" to "Compact galaxy group",
        "ClG" to "Galaxy cluster", "SCG" to "Supercluster", "AGN" to "Active galaxy", "SyG" to "Seyfert galaxy",
        "Sy1" to "Seyfert 1 galaxy", "Sy2" to "Seyfert 2 galaxy", "LIN" to "LINER galaxy", "SBG" to "Starburst galaxy",
        "H2G" to "HII galaxy", "EmG" to "Emission-line galaxy", "rG" to "Radio galaxy", "BiC" to "Brightest cluster galaxy",
        "LSB" to "Low-surface-brightness galaxy", "bCG" to "Blue compact galaxy", "QSO" to "Quasar", "Bla" to "Blazar",
        "BLL" to "BL Lac object", "LeG" to "Gravitationally lensed galaxy", "LeQ" to "Lensed quasar",
        "*" to "Star", "**" to "Double star", "V*" to "Variable star", "PM*" to "High-proper-motion star",
        "WD*" to "White dwarf", "Psr" to "Pulsar", "PN" to "Planetary nebula", "HII" to "HII region",
        "SNR" to "Supernova remnant", "Cl*" to "Star cluster", "GlC" to "Globular cluster", "OpC" to "Open cluster",
        "As*" to "Stellar association", "MoC" to "Molecular cloud", "DNe" to "Dark nebula", "RNe" to "Reflection nebula",
        "GNe" to "Nebula", "Cld" to "Cloud", "EmO" to "Emission object", "XB" to "X-ray binary", "Rad" to "Radio source",
        "X" to "X-ray source", "IR" to "Infrared source",
    )

    private val simbadExtragalactic = setOf(
        "G", "GiG", "GiC", "GiP", "IG", "PaG", "GrG", "CGG", "ClG", "SCG", "AGN", "SyG", "Sy1", "Sy2", "LIN", "SBG",
        "H2G", "EmG", "rG", "BiC", "LSB", "bCG", "QSO", "Bla", "BLL", "LeG", "LeQ", "AG?", "Q?", "G?",
    )

    private val nedGalactic = setOf("*", "**", "*Cl", "V*", "WD*", "PN", "SNR", "HII", "DNe", "RfN", "Neb", "MCld",
        "Nova", "EmObj")

    /** NED marks objects in the Milky Way with a leading "!" (e.g. "!PN", "!*"). */
    fun nedIsExtragalactic(code: String): Boolean = code.isNotEmpty() && !code.startsWith("!") && code !in nedGalactic

    fun simbadIsExtragalactic(code: String): Boolean = code in simbadExtragalactic

    fun describeNed(code: String): String {
        val c = code.trim()
        if (c.isEmpty()) return ""
        val galactic = c.startsWith("!")
        val base = c.removePrefix("!")
        val words = ned[base] ?: base
        return if (galactic && words.isNotEmpty() && words == base) "$words (Galactic)" else words
    }

    fun describeSimbad(code: String): String = code.trim().let { simbad[it] ?: it }

    /**
     * Tidies identifiers from the services: "MESSIER 031" → "M31", "NGC 0224" → "NGC 224",
     * "NAME Cartwheel Galaxy" → "Cartwheel Galaxy", "M  31" → "M31", "* alf Lyr" → "alf Lyr"; other
     * names keep their form.
     */
    fun cleanDesignation(raw: String): String {
        var s = raw.trim().replace(Regex("""\s+"""), " ")
        // SIMBAD prefixes: "NAME …", "* alf Lyr", "** STF 2272", "V* R Leo".
        s = s.replace(Regex("""^(?:NAME|\*\*?|V\*) """), "")
        Regex("""^(?:MESSIER|M) ?0*(\d{1,3})$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { return "M" + it.groupValues[1] }
        Regex("""^(NGC|IC) ?0*(\d+)(.*)$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let {
            return it.groupValues[1].uppercase(Locale.US) + " " + it.groupValues[2] + it.groupValues[3]
        }
        return s
    }

    private val romanNumeral = Regex("""^(?:i|ii|iii|iv|v|vi|vii|viii|ix|x)$""", RegexOption.IGNORE_CASE)
    private val letterPrefix = Regex("""^([A-Z]{2,6})(\d.*)$""")

    /**
     * The user's text as a display name: whitespace collapsed; all-lower-case designations upper-cased
     * ("arp220" → "ARP 220"), all-lower-case or all-upper-case common names title-cased
     * ("cartwheel galaxy" → "Cartwheel Galaxy", "LEO TRIPLET" → "Leo Triplet", "leo ii" → "Leo II").
     * Text with mixed case is kept as typed.
     */
    fun tidyName(query: String): String {
        val q = query.trim().replace(Regex("""\s+"""), " ")
        val hasLower = q.any { it.isLowerCase() }
        val hasUpper = q.any { it.isUpperCase() }
        if (hasLower && hasUpper) return q
        if (q.any { it.isDigit() }) {
            val up = q.uppercase(Locale.US)
            return up.split(' ').joinToString(" ") { w -> letterPrefix.matchEntire(w)?.let { "${it.groupValues[1]} ${it.groupValues[2]}" } ?: w }
        }
        return q.split(' ').joinToString(" ") { w ->
            when {
                romanNumeral.matches(w) || w.length == 1 -> w.uppercase(Locale.US)
                else -> w.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }
            }
        }
    }

    /**
     * Name to show for an object found online: the service's designation when it is what the user
     * typed (in another spelling), otherwise the user's own name, with the designation as an alias.
     */
    fun displayName(query: String, designation: String): Pair<String, List<String>> {
        if (designation.isEmpty()) return tidyName(query) to emptyList()
        if (Names.normalize(query) == Names.normalize(designation)) return designation to emptyList()
        return tidyName(query) to listOf(designation)
    }
}
