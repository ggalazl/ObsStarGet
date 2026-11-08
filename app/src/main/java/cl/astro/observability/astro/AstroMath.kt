package cl.astro.observability.astro

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

const val DEG2RAD = PI / 180.0
const val RAD2DEG = 180.0 / PI
const val J2000_JD = 2451545.0
const val DAYS_PER_JULIAN_CENTURY = 36525.0
const val AU_KM = 149_597_870.7
const val EARTH_EQUATORIAL_RADIUS_KM = 6378.137
const val WGS84_FLATTENING = 1.0 / 298.257223563
const val MS_PER_DAY = 86_400_000L
const val MS_PER_HOUR = 3_600_000L
const val MS_PER_MINUTE = 60_000L

fun sinD(x: Double) = sin(x * DEG2RAD)
fun cosD(x: Double) = cos(x * DEG2RAD)
fun tanD(x: Double) = tan(x * DEG2RAD)
fun asinD(x: Double) = asin(x.coerceIn(-1.0, 1.0)) * RAD2DEG
fun atan2D(y: Double, x: Double) = atan2(y, x) * RAD2DEG

/** Reduces an angle to [0, 360). */
fun norm360(x: Double): Double {
    val r = x % 360.0
    return if (r < 0.0) r + 360.0 else r
}

/** Reduces an angle to (-180, 180]. */
fun norm180(x: Double): Double {
    val r = norm360(x)
    return if (r > 180.0) r - 360.0 else r
}

/** Cartesian 3-vector. */
class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    val norm: Double get() = sqrt(x * x + y * y + z * z)
    fun unit(): Vec3 {
        val n = norm
        return Vec3(x / n, y / n, z / n)
    }

    /** Longitude-like angle (right ascension) in degrees, [0, 360). */
    val lonDeg: Double get() = norm360(atan2D(y, x))

    /** Latitude-like angle (declination) in degrees. */
    val latDeg: Double get() = atan2D(z, hypot(x, y))

    companion object {
        fun fromSpherical(lonDeg: Double, latDeg: Double, r: Double = 1.0): Vec3 {
            val cl = cosD(latDeg)
            return Vec3(r * cl * cosD(lonDeg), r * cl * sinD(lonDeg), r * sinD(latDeg))
        }
    }
}

/**
 * 3x3 matrix (row-major). The rotation factories build *frame* rotations, the IAU/SOFA convention:
 * rotZ(a) applied to a vector gives its components in a frame rotated by +a about z.
 */
class Mat3(private val a: DoubleArray) {
    operator fun times(v: Vec3) = Vec3(
        a[0] * v.x + a[1] * v.y + a[2] * v.z,
        a[3] * v.x + a[4] * v.y + a[5] * v.z,
        a[6] * v.x + a[7] * v.y + a[8] * v.z,
    )

    operator fun times(o: Mat3): Mat3 {
        val r = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) {
            var s = 0.0
            for (k in 0..2) s += a[3 * i + k] * o.a[3 * k + j]
            r[3 * i + j] = s
        }
        return Mat3(r)
    }

    companion object {
        fun rotX(angleDeg: Double): Mat3 {
            val c = cosD(angleDeg)
            val s = sinD(angleDeg)
            return Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, c, s, 0.0, -s, c))
        }

        fun rotY(angleDeg: Double): Mat3 {
            val c = cosD(angleDeg)
            val s = sinD(angleDeg)
            return Mat3(doubleArrayOf(c, 0.0, -s, 0.0, 1.0, 0.0, s, 0.0, c))
        }

        fun rotZ(angleDeg: Double): Mat3 {
            val c = cosD(angleDeg)
            val s = sinD(angleDeg)
            return Mat3(doubleArrayOf(c, s, 0.0, -s, c, 0.0, 0.0, 0.0, 1.0))
        }
    }
}

/** Great-circle separation in degrees (Vincenty form, stable at all separations). */
fun angularSeparationDeg(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
    val dra = (ra2 - ra1) * DEG2RAD
    val s1 = sinD(dec1)
    val c1 = cosD(dec1)
    val s2 = sinD(dec2)
    val c2 = cosD(dec2)
    val num = hypot(c2 * sin(dra), c1 * s2 - s1 * c2 * cos(dra))
    val den = s1 * s2 + c1 * c2 * cos(dra)
    return atan2(num, den) * RAD2DEG
}
