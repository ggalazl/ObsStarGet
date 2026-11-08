package cl.astro.observability.data

import cl.astro.observability.astro.Site
import cl.astro.observability.astro.Target
import cl.astro.observability.astro.angularSeparationDeg
import java.io.BufferedReader

/** Name normalisation shared by the catalogue index and user queries. */
object Names {
    private val leadingZeros = Regex("""(?<=[A-Z])0+(?=\d)""")
    private val wordSplit = Regex("""[^a-z0-9]+""")

    /** Words that do not identify an object by themselves ("the Sombrero galaxy" = "Sombrero"). */
    private val generic = setOf("the", "galaxy", "galaxies", "nebula", "nebulae", "cluster")

    /** "NGC 0224" → "NGC224", "Messier 31" → "M31", "alpha Cen" → "ALPHACEN", "M 031" → "M31". */
    fun normalize(s: String): String {
        var u = s.uppercase().trim()
        if (u.startsWith("MESSIER")) u = "M" + u.removePrefix("MESSIER")
        u = u.filter { it.isLetterOrDigit() }
        return leadingZeros.replace(u, "")
    }

    /**
     * The distinctive part of a common name, normalised: "The Antennae galaxies" → "ANTENNAE",
     * "Sombrero Galaxy" → "SOMBRERO", "Hoag's Object" → "HOAGSOBJECT". Empty if nothing is left.
     */
    fun core(s: String): String {
        val words = s.lowercase().replace("'", "").replace("’", "").split(wordSplit).filter { it.isNotEmpty() }
        return normalize(words.filter { it !in generic }.joinToString(" "))
    }
}

/** Offline object catalogue: OpenNGC (NGC/IC/Messier + addendum) and named bright stars (HYG). */
class Catalog private constructor(private val entries: List<Entry>) {
    class Entry(
        val name: String,
        val type: String,
        val raDeg: Double,
        val decDeg: Double,
        val pmRa: Double,
        val pmDec: Double,
        val mag: String,
        val aliases: List<String>,
    ) {
        val sourceLabel: String get() = if (type == "Star") "built-in (HYG)" else "built-in (OpenNGC)"

        fun toTarget(source: String = sourceLabel): Target =
            Target(name, raDeg, decDeg, pmRa, pmDec, type, source, aliases)

        /** Name plus the most useful aliases, e.g. "M31 · NGC 224 · Andromeda Galaxy". */
        val label: String
            get() {
                val extra = aliases.filter { a ->
                    a.startsWith("NGC ") || a.startsWith("IC ") ||
                        (a.firstOrNull()?.isUpperCase() == true && a.contains(' ') && a.none { it.isDigit() })
                }.take(2)
                return (listOf(name) + extra).joinToString(" · ")
            }
    }

    private val index = HashMap<String, Int>(entries.size * 4)
    private val designation = Regex("""^[A-Za-z]{1,6}\s?[+-]?\d""")

    /** Name-completion candidates: display text, entry index, normalised key, lower-case text. */
    private class Suggestion(val text: String, val entry: Int, val key: String, val lower: String)

    private val suggestions = ArrayList<Suggestion>()

    /** Normalised common names (not catalogue designations) for prefix look-up. */
    private val commonKeys = ArrayList<String>()
    private val commonEntries = ArrayList<Int>()
    private val commonNames = ArrayList<String>()

    /** Common names without generic words ("ANTENNAE" → NGC 4038, NGC 4039), with the full name. */
    private val coreIndex = HashMap<String, MutableList<Pair<Int, String>>>()

    private fun addCommon(key: String, name: String, i: Int) {
        commonKeys.add(key)
        commonEntries.add(i)
        commonNames.add(name)
        val c = Names.core(name)
        if (c.isEmpty()) return
        val l = coreIndex.getOrPut(c) { ArrayList(1) }
        if (l.none { it.first == i }) l.add(i to name)
    }

    init {
        entries.forEachIndexed { i, e ->
            val k = Names.normalize(e.name)
            index.putIfAbsent(k, i)
            suggestions.add(Suggestion(e.name, i, k, e.name.lowercase()))
            if (isCommonName(e.name)) addCommon(k, e.name, i)
            for (a in e.aliases) {
                val ka = Names.normalize(a)
                index.putIfAbsent(ka, i)
                // Offer common names and NGC/IC numbers in completion, not the long survey identifiers.
                if (a.startsWith("NGC ") || a.startsWith("IC ") || a.startsWith("M ") ||
                    a.none { it.isDigit() }
                ) {
                    suggestions.add(Suggestion(a, i, ka, a.lowercase()))
                }
                if (isCommonName(a)) addCommon(ka, a, i)
            }
        }
    }

    /** "47 Tuc Cluster", "Omega Centauri", "alpha Ori" yes; "HD 172167", "2MASX J…", "Mel 22", "NGC 224" no. */
    private fun isCommonName(s: String): Boolean = s.any { it.isLowerCase() } && !designation.containsMatchIn(s)

    val size: Int get() = entries.size

    /** Result of an offline name look-up. */
    sealed class Match {
        class Found(val entry: Entry) : Match()

        /** Several distinct objects fit a shortened or partial common name; [names] is the name that matched. */
        class Ambiguous(val entries: List<Entry>, val names: List<String>) : Match()
        object None : Match()
    }

    fun lookup(query: String): Entry? = (match(query) as? Match.Found)?.entry

    /**
     * Exact designation or name first. Otherwise common names are matched without the generic words
     * ("Antennae galaxy" → "Antennae Galaxies", "the Crab" → "Crab Nebula") and by prefix ("Sombrero",
     * "Omega Cen", "47 Tuc"). Catalogue designations never match by prefix, so "NGC 12" cannot turn
     * into NGC 1232: a missing designation is left to the online resolvers. Matches that are all
     * within half a degree of each other (the two Antennae galaxies, the Siamese Twins) count as one.
     */
    fun match(query: String): Match {
        val key = Names.normalize(query)
        if (key.isEmpty()) return Match.None
        index[key]?.let { return Match.Found(entries[it]) }
        // entry index → the common name that matched
        val hits = LinkedHashMap<Int, String>()
        val core = Names.core(query)
        if (core.count { it.isLetter() } >= 3) coreIndex[core]?.forEach { (i, n) -> hits.putIfAbsent(i, n) }
        if (key.length >= 5 && key.count { it.isLetter() } >= 3) {
            for (j in commonKeys.indices) if (commonKeys[j].startsWith(key)) hits.putIfAbsent(commonEntries[j], commonNames[j])
            if (core.length >= 5 && core != key) {
                for (j in commonKeys.indices) if (commonKeys[j].startsWith(core)) hits.putIfAbsent(commonEntries[j], commonNames[j])
            }
        }
        if (hits.isEmpty()) return Match.None
        // Best-known first: Messier, then NGC/IC, then the rest.
        val ranked = hits.keys.sortedBy { rank(entries[it]) }
        val first = entries[ranked.first()]
        if (ranked.all { val e = entries[it]; angularSeparationDeg(e.raDeg, e.decDeg, first.raDeg, first.decDeg) <= 0.5 }) {
            return Match.Found(first)
        }
        val distinct = ArrayList<Int>()
        for (i in ranked) {
            val e = entries[i]
            if (distinct.none { val d = entries[it]; angularSeparationDeg(d.raDeg, d.decDeg, e.raDeg, e.decDeg) <= 0.5 }) distinct.add(i)
        }
        val keep = distinct.take(12)
        return Match.Ambiguous(keep.map { entries[it] }, keep.map { hits[it]!! })
    }

    private fun rank(e: Entry): Int = when {
        e.name.length in 2..4 && e.name[0] == 'M' && e.name.drop(1).all { it.isDigit() } -> 0
        e.name.startsWith("NGC ") || e.name.startsWith("IC ") -> 1
        else -> 2
    }

    /**
     * Completion candidates: exact-prefix matches on normalised names first, then word matches
     * inside common names ("andromeda" → "Andromeda Galaxy").
     */
    fun suggest(query: String, limit: Int = 30): List<Pair<String, Entry>> {
        val key = Names.normalize(query)
        if (key.isEmpty()) return emptyList()
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        val prefix = ArrayList<Suggestion>()
        val inside = ArrayList<Suggestion>()
        for (s in suggestions) {
            if (s.key.startsWith(key)) {
                prefix.add(s)
            } else if (words.isNotEmpty() && words.all { w -> s.lower.contains(w) }) {
                inside.add(s)
            }
        }
        // Shorter names first so that "M8" comes before "M81".
        prefix.sortWith(compareBy({ it.key.length }, { it.text }))
        val seen = HashSet<Int>()
        val out = ArrayList<Pair<String, Entry>>()
        for (s in prefix + inside) {
            if (out.size >= limit) break
            if (seen.add(s.entry)) {
                val e = entries[s.entry]
                val text = if (s.text == e.name) e.label else "${s.text} (${e.name})"
                out.add(text to e)
            }
        }
        return out
    }

    companion object {
        fun parse(reader: BufferedReader): Catalog {
            val list = ArrayList<Entry>(14000)
            reader.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val f = line.split('\t')
                if (f.size < 7) return@forEachLine
                list.add(
                    Entry(
                        f[0], f[1], f[2].toDouble(), f[3].toDouble(), f[4].toDouble(), f[5].toDouble(), f[6],
                        if (f.size > 7 && f[7].isNotEmpty()) f[7].split('|') else emptyList(),
                    ),
                )
            }
            return Catalog(list)
        }
    }
}

/** Observatory list. */
object SiteList {
    fun parse(reader: BufferedReader): List<Site> {
        val out = ArrayList<Site>()
        reader.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val f = line.split('\t')
            if (f.size < 6) return@forEachLine
            out.add(
                Site(
                    f[0], f[1], f[2].toDouble(), f[3].toDouble(), f[4].toDouble(), f[5],
                    if (f.size > 6 && f[6].isNotEmpty()) f[6].split('|') else emptyList(),
                ),
            )
        }
        return out
    }
}
