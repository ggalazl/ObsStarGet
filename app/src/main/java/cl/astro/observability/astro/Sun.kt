package cl.astro.observability.astro

/** Apparent geocentric place of the Sun, equator and equinox of date. */
class SunPosition(
    val raDeg: Double,
    val decDeg: Double,
    val distanceAu: Double,
    /** Apparent ecliptic longitude (includes aberration and nutation). */
    val apparentLonDeg: Double,
    /** Geometric ("true") ecliptic longitude, mean equinox of date. */
    val trueLonDeg: Double,
)

/** Low-precision solar coordinates, Meeus ch. 25 (accuracy ~0.01°). */
object Sun {
    fun position(t: Double, nut: Nutation): SunPosition {
        val l0 = 280.46646 + 36000.76983 * t + 0.0003032 * t * t
        val m = 357.52911 + 35999.05029 * t - 0.0001537 * t * t
        val e = 0.016708634 - 0.000042037 * t - 0.0000001267 * t * t
        val c = (1.914602 - 0.004817 * t - 0.000014 * t * t) * sinD(m) +
            (0.019993 - 0.000101 * t) * sinD(2 * m) + 0.000289 * sinD(3 * m)
        val trueLon = l0 + c
        val nu = m + c
        val r = 1.000001018 * (1 - e * e) / (1 + e * cosD(nu))
        val omega = 125.04 - 1934.136 * t
        val appLon = trueLon - 0.00569 - 0.00478 * sinD(omega)
        val eps = nut.meanObliquityDeg + 0.00256 * cosD(omega)
        val ra = norm360(atan2D(cosD(eps) * sinD(appLon), cosD(appLon)))
        val dec = asinD(sinD(eps) * sinD(appLon))
        return SunPosition(ra, dec, r, norm360(appLon), norm360(trueLon))
    }
}
