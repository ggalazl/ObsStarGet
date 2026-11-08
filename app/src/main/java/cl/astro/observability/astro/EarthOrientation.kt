package cl.astro.observability.astro

/**
 * Time scales and Earth orientation.
 *
 * Conventions: UT1 is taken equal to UTC (|UT1-UTC| < 0.9 s, i.e. < 14" of sidereal rotation),
 * TT = UT + ΔT. Precession is IAU 1976 (Lieske), nutation is the low-precision IAU 1980
 * series of Meeus, "Astronomical Algorithms" ch. 22 (0.5" in Δψ, 0.1" in Δε), mean sidereal
 * time is Meeus eq. 12.4. Altogether the apparent places are good to ~1 arcsec, far below
 * what matters for an observability diagram.
 */
object TimeScales {
    fun jdFromEpochMillis(ms: Long): Double = ms / 86_400_000.0 + 2440587.5

    fun epochMillisFromJd(jd: Double): Long = Math.round((jd - 2440587.5) * 86_400_000.0)

    /** ΔT = TT − UT1 in seconds (Espenak & Meeus polynomials, adjusted to the measured value after 2015). */
    fun deltaTSeconds(jdUt: Double): Double {
        val y = 2000.0 + (jdUt - J2000_JD) / 365.25
        return when {
            y < 1961.0 -> {
                val u = (y - 1820.0) / 100.0
                -20.0 + 32.0 * u * u
            }
            y < 1986.0 -> {
                val t = y - 1975.0
                45.45 + 1.067 * t - t * t / 260.0 - t * t * t / 718.0
            }
            y < 2005.0 -> {
                val t = y - 2000.0
                63.86 + 0.3345 * t - 0.060374 * t * t + 0.0017275 * t * t * t +
                    0.000651814 * t * t * t * t + 0.00002373599 * t * t * t * t * t
            }
            y < 2015.0 -> 64.69 + 0.293 * (y - 2005.0)
            y < 2017.0 -> 67.6 + 0.75 * (y - 2015.0)
            y < 2035.0 -> 69.1            // measured ΔT has stayed at 69.0–69.4 s since 2017
            else -> 69.1 + 0.5 * (y - 2035.0)
        }
    }

    /** Greenwich mean sidereal time in degrees (Meeus 12.4). */
    fun gmstDeg(jdUt: Double): Double {
        val d = jdUt - J2000_JD
        val t = d / DAYS_PER_JULIAN_CENTURY
        return norm360(280.46061837 + 360.98564736629 * d + 0.000387933 * t * t - t * t * t / 38710000.0)
    }
}

/** Nutation angles and obliquity, all in degrees. */
class Nutation(val dPsiDeg: Double, val dEpsDeg: Double, val meanObliquityDeg: Double) {
    val trueObliquityDeg: Double get() = meanObliquityDeg + dEpsDeg
}

object EarthOrientation {
    /** Mean obliquity of the ecliptic (Meeus 22.2); t in Julian centuries of TT from J2000. */
    fun meanObliquityDeg(t: Double): Double =
        23.0 + 26.0 / 60.0 + (21.448 - 46.8150 * t - 0.00059 * t * t + 0.001813 * t * t * t) / 3600.0

    /** Low-precision nutation (Meeus ch. 22). */
    fun nutation(t: Double): Nutation {
        val omega = 125.04452 - 1934.136261 * t + 0.0020708 * t * t + t * t * t / 450000.0
        val lSun = 280.4665 + 36000.7698 * t
        val lMoon = 218.3165 + 481267.8813 * t
        val dPsi = -17.20 * sinD(omega) - 1.32 * sinD(2 * lSun) - 0.23 * sinD(2 * lMoon) + 0.21 * sinD(2 * omega)
        val dEps = 9.20 * cosD(omega) + 0.57 * cosD(2 * lSun) + 0.10 * cosD(2 * lMoon) - 0.09 * cosD(2 * omega)
        return Nutation(dPsi / 3600.0, dEps / 3600.0, meanObliquityDeg(t))
    }

    /** IAU 1976 precession matrix, mean J2000 equator/equinox → mean equator/equinox of date. */
    fun precessionMatrix(t: Double): Mat3 {
        val zeta = (2306.2181 * t + 0.30188 * t * t + 0.017998 * t * t * t) / 3600.0
        val z = (2306.2181 * t + 1.09468 * t * t + 0.018203 * t * t * t) / 3600.0
        val theta = (2004.3109 * t - 0.42665 * t * t - 0.041833 * t * t * t) / 3600.0
        return Mat3.rotZ(-z) * Mat3.rotY(theta) * Mat3.rotZ(-zeta)
    }

    /** Nutation matrix, mean equator/equinox of date → true equator/equinox of date. */
    fun nutationMatrix(n: Nutation): Mat3 =
        Mat3.rotX(-n.trueObliquityDeg) * Mat3.rotZ(-n.dPsiDeg) * Mat3.rotX(n.meanObliquityDeg)
}

/**
 * Everything that depends only on the instant: TT, nutation, precession-nutation matrix and
 * apparent sidereal time. Sun and Moon positions are computed lazily.
 */
class SkyContext(val epochMillis: Long) {
    val jdUt: Double = TimeScales.jdFromEpochMillis(epochMillis)
    val jdTt: Double = jdUt + TimeScales.deltaTSeconds(jdUt) / 86400.0

    /** Julian centuries of TT since J2000. */
    val t: Double = (jdTt - J2000_JD) / DAYS_PER_JULIAN_CENTURY
    val nutation: Nutation = EarthOrientation.nutation(t)
    val precessionNutation: Mat3 = EarthOrientation.nutationMatrix(nutation) * EarthOrientation.precessionMatrix(t)

    /** Greenwich apparent sidereal time, degrees. */
    val gastDeg: Double = norm360(TimeScales.gmstDeg(jdUt) + nutation.dPsiDeg * cosD(nutation.trueObliquityDeg))

    val sun: SunPosition by lazy(LazyThreadSafetyMode.NONE) { Sun.position(t, nutation) }
    val moon: MoonPosition by lazy(LazyThreadSafetyMode.NONE) { Moon.geocentric(t, nutation) }

    /** Local apparent sidereal time in degrees for an east-positive longitude. */
    fun lastDeg(lonEastDeg: Double): Double = norm360(gastDeg + lonEastDeg)
}
