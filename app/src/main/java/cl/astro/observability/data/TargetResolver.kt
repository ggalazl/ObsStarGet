package cl.astro.observability.data

import cl.astro.observability.astro.Target
import cl.astro.observability.astro.angularSeparationDeg
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Turns user input into a [Target]: explicit coordinates, the offline catalogue, names resolved
 * online earlier (cache), and then, in parallel, NED (NASA/IPAC Extragalactic Database) and CDS
 * Sesame (SIMBAD → NED → VizieR).
 *
 * When both services find the object, NED's position is used for extragalactic objects and
 * SIMBAD's (with proper motion) for the others. If they disagree by more than 15′ the user chooses.
 */
class TargetResolver(
    private val catalog: Catalog?,
    private val cache: ResolvedCache? = null,
    private val http: (String) -> String = ::httpGet,
) {
    /** One entry of a choice list: either a ready target or a name to resolve. */
    class Choice(val label: String, val target: Target? = null, val query: String? = null)

    sealed class Outcome {
        class Found(val target: Target) : Outcome()
        class Ambiguous(val query: String, val choices: List<Choice>, val message: String) : Outcome()
        class NotFound(val message: String) : Outcome()
    }

    /** Coordinates, catalogue and cache only (fast; safe on the UI thread). Null if a network look-up is needed. */
    fun resolveOffline(input: String): Outcome? {
        val q = input.trim()
        if (q.isEmpty()) return null
        val first = q.first()
        if (first.isDigit() || first == '+' || first == '-' || first == '.' || first == '−') {
            CoordinateParser.parse(q)?.let {
                val name = "${CoordinateParser.formatRa(it.raDeg)} ${CoordinateParser.formatDec(it.decDeg)}"
                return Outcome.Found(Target(name, it.raDeg, it.decDeg, type = "Coordinates", source = "entered (ICRS/J2000)"))
            }
        }
        when (val m = catalog?.match(q)) {
            is Catalog.Match.Found -> return Outcome.Found(m.entry.toTarget())
            is Catalog.Match.Ambiguous -> return Outcome.Ambiguous(
                q,
                m.entries.mapIndexed { i, e ->
                    val n = m.names[i]
                    val label = if (e.label.contains(n)) e.label else "${e.name} · $n"
                    Choice(if (e.type.isNotEmpty()) "$label  —  ${e.type}" else label, target = e.toTarget())
                },
                "Several objects in the built-in catalogue match \"$q\":",
            )
            else -> {}
        }
        cache?.get(q)?.let { return Outcome.Found(it) }
        return null
    }

    /** Full resolution including the network (call from a background thread). */
    fun resolve(input: String): Outcome {
        val q = input.trim()
        if (q.isEmpty()) return Outcome.NotFound("Enter an object name or coordinates.")
        resolveOffline(q)?.let { return it }
        val (ned, ses) = queryOnline(q)
        val outcome = decide(q, ned, ses)
        if (outcome is Outcome.Found) cache?.put(q, outcome.target)
        return outcome
    }

    private fun queryOnline(q: String): Pair<ServiceReply, ServiceReply> {
        val enc = URLEncoder.encode(q, "UTF-8").replace("+", "%20")
        val pool = Executors.newFixedThreadPool(2)
        try {
            val ned = pool.submit(Callable { askNed(enc) })
            val ses = pool.submit(Callable { askSesame(enc) })
            return await(ned, "NED") to await(ses, "Sesame")
        } finally {
            pool.shutdownNow()
        }
    }

    private fun await(f: Future<ServiceReply>, service: String): ServiceReply = try {
        f.get(45, TimeUnit.SECONDS)
    } catch (e: TimeoutException) {
        ServiceReply.Failed("$service: no answer")
    } catch (e: ExecutionException) {
        ServiceReply.Failed("$service: ${e.cause?.message ?: "error"}")
    } catch (e: InterruptedException) {
        ServiceReply.Failed("$service: interrupted")
    }

    private fun askNed(enc: String): ServiceReply = try {
        NedLookup.parse(http(NedLookup.URL + enc))
    } catch (e: IOException) {
        ServiceReply.Failed("NED: ${describe(e)}")
    }

    private fun askSesame(enc: String): ServiceReply {
        var last: ServiceReply = ServiceReply.Failed("Sesame: not reached")
        for (mirror in SesameLookup.MIRRORS) {
            try {
                return SesameLookup.parse(http(mirror + enc))
            } catch (e: IOException) {
                last = ServiceReply.Failed("Sesame: ${describe(e)}")
            }
        }
        return last
    }

    companion object {
        /** NED and SIMBAD positions further apart than this are taken to be different objects. */
        const val SAME_OBJECT_DEG = 0.25

        fun hitToTarget(query: String, h: ServiceReply.Hit): Target {
            val (name, aliases) = ObjectTypes.displayName(query, h.designation)
            return Target(name, h.raDeg, h.decDeg, h.pmRa, h.pmDec, h.type, "${h.service} (online)", aliases)
        }

        /** Combines the two replies (pure function, unit-tested). */
        fun decide(query: String, ned: ServiceReply, ses: ServiceReply): Outcome {
            val n = ned as? ServiceReply.Hit
            val s = ses as? ServiceReply.Hit
            if (n != null && s != null &&
                angularSeparationDeg(n.raDeg, n.decDeg, s.raDeg, s.decDeg) > SAME_OBJECT_DEG
            ) {
                fun label(h: ServiceReply.Hit) = buildString {
                    append(h.designation.ifEmpty { query })
                    if (h.type.isNotEmpty()) append("  —  ").append(h.type)
                    append("  (").append(h.service).append(")")
                }
                return Outcome.Ambiguous(
                    query,
                    listOf(Choice(label(n), target = hitToTarget(query, n)), Choice(label(s), target = hitToTarget(query, s))),
                    "NED and SIMBAD give different objects for \"$query\":",
                )
            }
            return when {
                n != null && (n.extragalactic || s == null) -> Outcome.Found(hitToTarget(query, n))
                s != null -> Outcome.Found(hitToTarget(query, s))
                ned is ServiceReply.Candidates -> Outcome.Ambiguous(
                    query,
                    ned.names.take(40).map { Choice(it, query = it) },
                    "NED knows several objects called \"$query\". Choose one:",
                )
                else -> Outcome.NotFound(notFoundMessage(query, ned, ses))
            }
        }

        private fun reason(r: ServiceReply): String = when (r) {
            is ServiceReply.Miss -> r.reason
            is ServiceReply.Failed -> r.reason
            is ServiceReply.Candidates -> "NED: ambiguous name"
            is ServiceReply.Hit -> r.service
        }

        private fun notFoundMessage(query: String, ned: ServiceReply, ses: ServiceReply): String =
            if (ned is ServiceReply.Failed && ses is ServiceReply.Failed) {
                "\"$query\" is not in the built-in catalogue, and the online resolvers could not be reached " +
                    "(${reason(ned)}; ${reason(ses)}). Check the internet connection, or enter coordinates, " +
                    "e.g. 23:14:48.5 -43:35:60."
            } else {
                "\"$query\" was not found (built-in catalogue; ${reason(ned)}; ${reason(ses)}). Try another spelling " +
                    "or a catalogue designation (NGC, UGC, PGC, ESO, Arp…), or enter coordinates."
            }

        private fun describe(e: IOException): String = when (e) {
            is UnknownHostException -> "no connection (host not found)"
            is SocketTimeoutException -> "timed out"
            else -> e.message ?: e.javaClass.simpleName
        }

        fun httpGet(url: String): String {
            val c = URI(url).toURL().openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", "ObsStarGet (Android app)")
            c.setRequestProperty("Accept", "application/json, text/plain, */*")
            try {
                if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
                return c.inputStream.bufferedReader().use { it.readText() }
            } finally {
                c.disconnect()
            }
        }
    }
}

/**
 * Names resolved online, kept on the phone so that they work offline afterwards
 * (most recently used first, at most [max] entries).
 */
class ResolvedCache(private val max: Int = 200) {
    private val map = LinkedHashMap<String, Target>(32, 0.75f, true)

    @Synchronized
    fun get(query: String): Target? = map[Names.normalize(query)]

    @Synchronized
    fun put(query: String, t: Target) {
        val k = Names.normalize(query)
        if (k.isEmpty()) return
        map[k] = t
        while (map.size > max) map.remove(map.keys.first())
    }

    @Synchronized
    fun size(): Int = map.size

    @Synchronized
    fun serialize(): String = map.entries.joinToString("\n") { (k, t) ->
        listOf(k, t.name, t.raDeg.toString(), t.decDeg.toString(), t.pmRaCosDecMasYr.toString(), t.pmDecMasYr.toString(),
            t.type, t.source, t.aliases.joinToString("|") { it.replace('|', '/') })
            .joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }
    }

    companion object {
        fun parse(text: String?, max: Int = 200): ResolvedCache {
            val c = ResolvedCache(max)
            text?.lineSequence()?.forEach { line ->
                val f = line.split('\t')
                if (f.size < 8) return@forEach
                val ra = f[2].toDoubleOrNull() ?: return@forEach
                val dec = f[3].toDoubleOrNull() ?: return@forEach
                val t = Target(f[1], ra, dec, f[4].toDoubleOrNull() ?: 0.0, f[5].toDoubleOrNull() ?: 0.0, f[6], f[7],
                    if (f.size > 8 && f[8].isNotEmpty()) f[8].split('|') else emptyList())
                synchronized(c) { c.map[f[0]] = t }
            }
            return c
        }
    }
}
