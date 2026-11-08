package cl.astro.observability.astro

import kotlin.math.cos
import kotlin.math.sqrt

/** Horizontal coordinates: altitude (geometric, no refraction), azimuth from north through east, hour angle. */
class AltAz(val altDeg: Double, val azDeg: Double, val hourAngleDeg: Double)

object Horizon {
    fun altAz(raDeg: Double, decDeg: Double, lastDeg: Double, latDeg: Double): AltAz {
        val h = norm180(lastDeg - raDeg)
        val sinAlt = sinD(latDeg) * sinD(decDeg) + cosD(latDeg) * cosD(decDeg) * cosD(h)
        val alt = asinD(sinAlt)
        // Meeus 13.5 (azimuth from the south, westward) + 180° = azimuth from the north, eastward.
        val az = norm360(
            atan2D(sinD(h) * cosD(decDeg), cosD(h) * cosD(decDeg) * sinD(latDeg) - sinD(decDeg) * cosD(latDeg)) + 180.0,
        )
        return AltAz(alt, az, h)
    }

    /** Parallactic angle q (Meeus 14.1), degrees: position angle of the zenith measured from north through east. */
    fun parallacticAngleDeg(hourAngleDeg: Double, decDeg: Double, latDeg: Double): Double =
        atan2D(sinD(hourAngleDeg), tanD(latDeg) * cosD(decDeg) - sinD(decDeg) * cosD(hourAngleDeg))

    /**
     * Atmospheric refraction (arcmin → degrees) for a geometric altitude, Sæmundsson (1986), scaled to
     * the standard pressure at the site height. Used only for display of apparent altitude.
     */
    fun refractionDeg(trueAltDeg: Double, heightM: Double = 0.0, temperatureC: Double = 10.0): Double {
        if (trueAltDeg < -1.9) return 0.0
        val rArcmin = 1.02 / tanD(trueAltDeg + 10.3 / (trueAltDeg + 5.11))
        val pressure = 1010.0 * kotlin.math.exp(-heightM / 8434.0)
        return rArcmin / 60.0 * (pressure / 1010.0) * (283.0 / (273.0 + temperatureC))
    }
}

/** Airmass models as a function of the geometric (true) altitude. */
enum class AirmassModel(val label: String) {
    /**
     * Young (1994), Applied Optics 33, 1108: rational function of the true zenith angle that includes
     * refraction; max error 0.0037 at the horizon.
     */
    YOUNG_1994("Young (1994)"),

    /** Plane-parallel atmosphere, X = sec z. */
    SEC_Z("sec z"),

    /** Hardie (1962) polynomial in sec z − 1, widely used in photometry (IRAF). */
    HARDIE_1962("Hardie (1962)");

    /** Airmass for a geometric altitude in degrees; +∞ at or below the horizon. */
    fun airmass(altDeg: Double): Double {
        if (altDeg <= 0.0) return Double.POSITIVE_INFINITY
        val c = sinD(altDeg) // cos z
        return when (this) {
            YOUNG_1994 -> (1.002432 * c * c + 0.148386 * c + 0.0096467) /
                (c * c * c + 0.149864 * c * c + 0.0102963 * c + 0.000303978)
            SEC_Z -> 1.0 / c
            HARDIE_1962 -> {
                // Hardie diverges from the true airmass below ~ 10°; fall back to Young there.
                if (altDeg < 10.0) {
                    YOUNG_1994.airmass(altDeg)
                } else {
                    val s = 1.0 / c - 1.0
                    1.0 / c - 0.0018167 * s - 0.002875 * s * s - 0.0008083 * s * s * s
                }
            }
        }
    }

    /** Inverse of [airmass]: the altitude (degrees) at which the airmass equals [x] (x ≥ 1). */
    fun altitudeForAirmass(x: Double): Double {
        if (x <= 1.0) return 90.0
        var lo = 0.0
        var hi = 90.0
        repeat(60) {
            val mid = 0.5 * (lo + hi)
            if (airmass(mid) > x) lo = mid else hi = mid
        }
        return 0.5 * (lo + hi)
    }
}

/** Illuminated fraction and geometry of the Moon (Meeus ch. 48). */
class MoonPhase(
    /** Illuminated fraction 0..1. */
    val illuminated: Double,
    /** Phase angle i, degrees (0 = full, 180 = new). */
    val phaseAngleDeg: Double,
    /** Elongation of the Moon east of the Sun in ecliptic longitude, [0, 360): < 180 waxing. */
    val elongationEastDeg: Double,
    /** Position angle of the midpoint of the bright limb (from north through east), degrees. */
    val brightLimbPaDeg: Double,
) {
    val waxing: Boolean get() = elongationEastDeg < 180.0

    val phaseName: String
        get() {
            val e = elongationEastDeg
            return when {
                e < 6.0 || e > 354.0 -> "new"
                e < 84.0 -> "waxing crescent"
                e < 96.0 -> "first quarter"
                e < 174.0 -> "waxing gibbous"
                e < 186.0 -> "full"
                e < 264.0 -> "waning gibbous"
                e < 276.0 -> "last quarter"
                else -> "waning crescent"
            }
        }

    companion object {
        fun compute(moon: MoonPosition, sun: SunPosition): MoonPhase {
            val r = sun.distanceAu * AU_KM
            val dra = sun.raDeg - moon.raDeg
            val cosPsi = sinD(sun.decDeg) * sinD(moon.decDeg) + cosD(sun.decDeg) * cosD(moon.decDeg) * cosD(dra)
            val sinPsi = sqrt((1.0 - cosPsi * cosPsi).coerceAtLeast(0.0))
            val i = atan2D(r * sinPsi, moon.distanceKm - r * cosPsi)
            val k = (1.0 + cos(i * DEG2RAD)) / 2.0
            val chi = norm360(
                atan2D(
                    cosD(sun.decDeg) * sinD(dra),
                    sinD(sun.decDeg) * cosD(moon.decDeg) - cosD(sun.decDeg) * sinD(moon.decDeg) * cosD(dra),
                ),
            )
            return MoonPhase(k, i, norm360(moon.lonDeg - sun.apparentLonDeg), chi)
        }
    }
}
