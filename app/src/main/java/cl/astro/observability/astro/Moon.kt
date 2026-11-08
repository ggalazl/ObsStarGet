package cl.astro.observability.astro

import kotlin.math.abs

/** Apparent geocentric place of the Moon, true equator and equinox of date. */
class MoonPosition(
    val raDeg: Double,
    val decDeg: Double,
    val distanceKm: Double,
    /** Apparent ecliptic longitude and latitude of date. */
    val lonDeg: Double,
    val latDeg: Double,
) {
    /** Geocentric position vector in km (true equator of date). */
    val vectorKm: Vec3 get() = Vec3.fromSpherical(raDeg, decDeg, distanceKm)
}

/**
 * Moon position from the truncated ELP-2000/82 theory given in Meeus, "Astronomical Algorithms",
 * ch. 47 (tables 47.A and 47.B). Accuracy ≈10" in longitude, 4" in latitude.
 */
object Moon {
    // Table 47.A: multiples of D, M, M', F; coefficient of sin (Σl, 1e-6 deg) and cos (Σr, 1e-3 km).
    private val LR = intArrayOf(
        0, 0, 1, 0, 6288774, -20905355,
        2, 0, -1, 0, 1274027, -3699111,
        2, 0, 0, 0, 658314, -2955968,
        0, 0, 2, 0, 213618, -569925,
        0, 1, 0, 0, -185116, 48888,
        0, 0, 0, 2, -114332, -3149,
        2, 0, -2, 0, 58793, 246158,
        2, -1, -1, 0, 57066, -152138,
        2, 0, 1, 0, 53322, -170733,
        2, -1, 0, 0, 45758, -204586,
        0, 1, -1, 0, -40923, -129620,
        1, 0, 0, 0, -34720, 108743,
        0, 1, 1, 0, -30383, 104755,
        2, 0, 0, -2, 15327, 10321,
        0, 0, 1, 2, -12528, 0,
        0, 0, 1, -2, 10980, 79661,
        4, 0, -1, 0, 10675, -34782,
        0, 0, 3, 0, 10034, -23210,
        4, 0, -2, 0, 8548, -21636,
        2, 1, -1, 0, -7888, 24208,
        2, 1, 0, 0, -6766, 30824,
        1, 0, -1, 0, -5163, -8379,
        1, 1, 0, 0, 4987, -16675,
        2, -1, 1, 0, 4036, -12831,
        2, 0, 2, 0, 3994, -10445,
        4, 0, 0, 0, 3861, -11650,
        2, 0, -3, 0, 3665, 14403,
        0, 1, -2, 0, -2689, -7003,
        2, 0, -1, 2, -2602, 0,
        2, -1, -2, 0, 2390, 10056,
        1, 0, 1, 0, -2348, 6322,
        2, -2, 0, 0, 2236, -9884,
        0, 1, 2, 0, -2120, 5751,
        0, 2, 0, 0, -2069, 0,
        2, -2, -1, 0, 2048, -4950,
        2, 0, 1, -2, -1773, 4130,
        2, 0, 0, 2, -1595, 0,
        4, -1, -1, 0, 1215, -3958,
        0, 0, 2, 2, -1110, 0,
        3, 0, -1, 0, -892, 3258,
        2, 1, 1, 0, -810, 2616,
        4, -1, -2, 0, 759, -1897,
        0, 2, -1, 0, -713, -2117,
        2, 2, -1, 0, -700, 2354,
        2, 1, -2, 0, 691, 0,
        2, -1, 0, -2, 596, 0,
        4, 0, 1, 0, 549, -1423,
        0, 0, 4, 0, 537, -1117,
        4, -1, 0, 0, 520, -1571,
        1, 0, -2, 0, -487, -1739,
        2, 1, 0, -2, -399, 0,
        0, 0, 2, -2, -381, -4421,
        1, 1, 1, 0, 351, 0,
        3, 0, -2, 0, -340, 0,
        4, 0, -3, 0, 330, 0,
        2, -1, 2, 0, 327, 0,
        0, 2, 1, 0, -323, 1165,
        1, 1, -1, 0, 299, 0,
        2, 0, 3, 0, 294, 0,
        2, 0, -1, -2, 0, 8752,
    )

    // Table 47.B: multiples of D, M, M', F; coefficient of sin (Σb, 1e-6 deg).
    private val B = intArrayOf(
        0, 0, 0, 1, 5128122,
        0, 0, 1, 1, 280602,
        0, 0, 1, -1, 277693,
        2, 0, 0, -1, 173237,
        2, 0, -1, 1, 55413,
        2, 0, -1, -1, 46271,
        2, 0, 0, 1, 32573,
        0, 0, 2, 1, 17198,
        2, 0, 1, -1, 9266,
        0, 0, 2, -1, 8822,
        2, -1, 0, -1, 8216,
        2, 0, -2, -1, 4324,
        2, 0, 1, 1, 4200,
        2, 1, 0, -1, -3359,
        2, -1, -1, 1, 2463,
        2, -1, 0, 1, 2211,
        2, -1, -1, -1, 2065,
        0, 1, -1, -1, -1870,
        4, 0, -1, -1, 1828,
        0, 1, 0, 1, -1794,
        0, 0, 0, 3, -1749,
        0, 1, -1, 1, -1565,
        1, 0, 0, 1, -1491,
        0, 1, 1, 1, -1475,
        0, 1, 1, -1, -1410,
        0, 1, 0, -1, -1344,
        1, 0, 0, -1, -1335,
        0, 0, 3, 1, 1107,
        4, 0, 0, -1, 1021,
        4, 0, -1, 1, 833,
        0, 0, 1, -3, 777,
        4, 0, -2, 1, 671,
        2, 0, 0, -3, 607,
        2, 0, 2, -1, 596,
        2, -1, 1, -1, 491,
        2, 0, -2, 1, -451,
        0, 0, 3, -1, 439,
        2, 0, 2, 1, 422,
        2, 0, -3, -1, 421,
        2, 1, -1, 1, -366,
        2, 1, 0, 1, -351,
        4, 0, 0, 1, 331,
        2, -1, 1, 1, 315,
        2, -2, 0, -1, 302,
        0, 0, 1, 3, -283,
        2, 1, 1, -1, -229,
        1, 1, 0, -1, 223,
        1, 1, 0, 1, 223,
        0, 1, -2, -1, -220,
        2, 1, -1, -1, -220,
        1, 0, 1, 1, -185,
        2, -1, -2, -1, 181,
        0, 1, 2, 1, -177,
        4, 0, -2, -1, 176,
        4, -1, -1, -1, 166,
        1, 0, 1, -1, -164,
        4, 0, 1, -1, 132,
        1, 0, -1, -1, -119,
        4, -1, 0, -1, 115,
        2, -2, 0, 1, 107,
    )

    /** Intermediate sums, exposed for testing against Meeus example 47.a. */
    class Series(val sumL: Double, val sumB: Double, val sumR: Double, val meanLonDeg: Double)

    fun series(t: Double): Series {
        val lp = 218.3164477 + 481267.88123421 * t - 0.0015786 * t * t + t * t * t / 538841.0 -
            t * t * t * t / 65194000.0
        val d = 297.8501921 + 445267.1114034 * t - 0.0018819 * t * t + t * t * t / 545868.0 -
            t * t * t * t / 113065000.0
        val m = 357.5291092 + 35999.0502909 * t - 0.0001536 * t * t + t * t * t / 24490000.0
        val mp = 134.9633964 + 477198.8675055 * t + 0.0087414 * t * t + t * t * t / 69699.0 -
            t * t * t * t / 14712000.0
        val f = 93.2720950 + 483202.0175233 * t - 0.0036539 * t * t - t * t * t / 3526000.0 +
            t * t * t * t / 863310000.0
        val a1 = 119.75 + 131.849 * t
        val a2 = 53.09 + 479264.290 * t
        val a3 = 313.45 + 481266.484 * t
        val e = 1.0 - 0.002516 * t - 0.0000074 * t * t
        val e2 = e * e

        var sl = 0.0
        var sr = 0.0
        var i = 0
        while (i < LR.size) {
            val cm = LR[i + 1]
            val arg = LR[i] * d + cm * m + LR[i + 2] * mp + LR[i + 3] * f
            val fac = when (abs(cm)) {
                1 -> e
                2 -> e2
                else -> 1.0
            }
            if (LR[i + 4] != 0) sl += LR[i + 4] * fac * sinD(arg)
            if (LR[i + 5] != 0) sr += LR[i + 5] * fac * cosD(arg)
            i += 6
        }
        var sb = 0.0
        i = 0
        while (i < B.size) {
            val cm = B[i + 1]
            val arg = B[i] * d + cm * m + B[i + 2] * mp + B[i + 3] * f
            val fac = when (abs(cm)) {
                1 -> e
                2 -> e2
                else -> 1.0
            }
            sb += B[i + 4] * fac * sinD(arg)
            i += 5
        }
        sl += 3958.0 * sinD(a1) + 1962.0 * sinD(lp - f) + 318.0 * sinD(a2)
        sb += -2235.0 * sinD(lp) + 382.0 * sinD(a3) + 175.0 * sinD(a1 - f) + 175.0 * sinD(a1 + f) +
            127.0 * sinD(lp - mp) - 115.0 * sinD(lp + mp)
        return Series(sl, sb, sr, lp)
    }

    /** Apparent geocentric position; t in Julian centuries of TT since J2000. */
    fun geocentric(t: Double, nut: Nutation): MoonPosition {
        val s = series(t)
        val lon = norm360(s.meanLonDeg + s.sumL / 1e6 + nut.dPsiDeg)
        val lat = s.sumB / 1e6
        val dist = 385000.56 + s.sumR / 1000.0
        val eps = nut.trueObliquityDeg
        val ra = norm360(atan2D(sinD(lon) * cosD(eps) - tanD(lat) * sinD(eps), cosD(lon)))
        val dec = asinD(sinD(lat) * cosD(eps) + cosD(lat) * sinD(eps) * sinD(lon))
        return MoonPosition(ra, dec, dist, lon, lat)
    }
}
