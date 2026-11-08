package cl.astro.observability.data

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/**
 * Parses equatorial coordinates typed by the user (ICRS/J2000 assumed).
 *
 * Accepted forms (separators: blanks, commas, ':' or h/m/s d/'/" symbols):
 *   00:42:44.3 +41:16:09        00 42 44.3 +41 16 09        00h42m44.3s +41d16m09s
 *   10.6847 41.2692             (two plain decimals → both in degrees)
 *   0.71231h +41.269            (decimal RA with an explicit 'h' → hours)
 *   00:42.7 +41:16              (fewer sexagesimal fields are fine)
 */
object CoordinateParser {
    class Result(val raDeg: Double, val decDeg: Double)

    fun parse(input: String): Result? {
        val s = input.trim()
            .replace('−', '-').replace('–', '-')
            .replace('°', 'd').replace('′', '\'').replace('″', '"')
        if (s.isEmpty() || s.any { it.isLetter() && it.lowercaseChar() !in "hmsd" }) return null
        val raHoursHint = Regex("""\d\s*h""", RegexOption.IGNORE_CASE).containsMatchIn(s)
        // Tokenize into signed numbers.
        val cleaned = s.replace(Regex("""[hHmMsSdD:'",;]"""), " ")
        val tokens = Regex("""[+-]?\d+(?:\.\d*)?|[+-]?\.\d+""").findAll(cleaned).map { it.value }.toList()
        if (tokens.size < 2) return null
        // The declination starts at the first explicitly signed token after the first one, otherwise
        // the tokens are split evenly.
        val signedIdx = (1 until tokens.size).firstOrNull { tokens[it].startsWith("+") || tokens[it].startsWith("-") }
        val split = signedIdx ?: if (tokens.size % 2 == 0) tokens.size / 2 else return null
        val raT = tokens.subList(0, split)
        val decT = tokens.subList(split, tokens.size)
        if (raT.size > 3 || decT.size > 3 || raT.isEmpty() || decT.isEmpty()) return null
        val sexagesimal = raT.size > 1 || decT.size > 1 || s.contains(':')
        val ra = sexa(raT) ?: return null
        val dec = sexa(decT) ?: return null
        val raDeg = if (sexagesimal || raHoursHint) ra * 15.0 else ra
        if (raDeg < 0.0 || raDeg >= 360.0 || abs(dec) > 90.0) return null
        return Result(raDeg, dec)
    }

    private fun sexa(t: List<String>): Double? {
        val neg = t[0].startsWith("-")
        val v = t.map { it.trimStart('+', '-').toDoubleOrNull() ?: return null }
        if (v.size > 1 && (v[1] >= 60.0 || (v.size > 2 && v[2] >= 60.0))) return null
        var x = v[0]
        if (v.size > 1) x += v[1] / 60.0
        if (v.size > 2) x += v[2] / 3600.0
        return if (neg) -x else x
    }

    fun formatRa(raDeg: Double, decimals: Int = 1): String {
        val totalSec = norm(raDeg) / 15.0 * 3600.0
        val scale = Math.pow(10.0, decimals.toDouble())
        var sec = Math.round(totalSec * scale) / scale
        if (sec >= 86400.0) sec -= 86400.0
        val h = floor(sec / 3600.0).toInt()
        val m = floor((sec - h * 3600) / 60.0).toInt()
        val ss = sec - h * 3600 - m * 60
        return String.format(Locale.US, "%02dh%02dm%0${3 + decimals}.${decimals}fs", h, m, ss)
    }

    fun formatDec(decDeg: Double, decimals: Int = 0): String {
        val sign = if (decDeg < 0) "-" else "+"
        val scale = Math.pow(10.0, decimals.toDouble())
        val totalSec = Math.round(abs(decDeg) * 3600.0 * scale) / scale
        val d = floor(totalSec / 3600.0).toInt()
        val m = floor((totalSec - d * 3600) / 60.0).toInt()
        val ss = totalSec - d * 3600 - m * 60
        val secStr = if (decimals == 0) String.format(Locale.US, "%02d", Math.round(ss))
        else String.format(Locale.US, "%0${3 + decimals}.${decimals}f", ss)
        return String.format(Locale.US, "%s%02d°%02d′%s″", sign, d, m, secStr)
    }

    private fun norm(x: Double): Double {
        val r = x % 360.0
        return if (r < 0) r + 360.0 else r
    }
}
