package cl.astro.observability.astro

import kotlin.math.abs

/** Everything the diagrams need at one instant. Angles in degrees. */
class SkySample(
    val epochMillis: Long,
    val lastDeg: Double,
    val sunAlt: Double,
    val objAlt: Double,
    val objAz: Double,
    val objHourAngle: Double,
    val airmass: Double,
    val moonAlt: Double,
    val moonAz: Double,
    /** Topocentric Moon – target separation. */
    val moonSep: Double,
)

/**
 * Positions of the target, the Sun and the Moon as seen from [site].
 *
 * Target: J2000 catalogue place → proper motion → annual aberration → precession (IAU 1976) →
 * nutation → apparent place of date. Sun: Meeus ch. 25 (apparent). Moon: Meeus ch. 47 (apparent),
 * then rigorously topocentric (observer vector subtracted), which matters: the lunar parallax
 * reaches 1°.
 */
class Ephemeris(val site: Site, val target: Target, val airmassModel: AirmassModel = AirmassModel.YOUNG_1994) {

    /** Apparent (true equator/equinox of date) unit vector of the target. */
    fun targetApparent(ctx: SkyContext): Vec3 {
        val years = (ctx.jdTt - J2000_JD) / 365.25
        var dec = target.decDeg
        var ra = target.raDeg
        if (target.pmDecMasYr != 0.0 || target.pmRaCosDecMasYr != 0.0) {
            val cd = cosD(dec)
            dec += target.pmDecMasYr * years / 3.6e6
            if (abs(cd) > 1e-9) ra += target.pmRaCosDecMasYr * years / 3.6e6 / cd
        }
        val u = Vec3.fromSpherical(ra, dec)
        // Annual aberration: add the Earth's velocity / c (first order), computed from the Sun's
        // true longitude (circular-orbit approximation, error < 0.4").
        val lam = ctx.sun.trueLonDeg
        val vx = ABERRATION_K * sinD(lam)
        val vyEcl = -ABERRATION_K * cosD(lam)
        val v = Vec3(vx, vyEcl * COS_EPS_J2000, vyEcl * SIN_EPS_J2000)
        val ab = (u + v).unit()
        return ctx.precessionNutation * ab
    }

    /** Topocentric apparent RA/Dec of the Moon (degrees) as a vector, true equator of date. */
    fun moonTopocentric(ctx: SkyContext): Vec3 {
        val last = ctx.lastDeg(site.lonDeg)
        return ctx.moon.vectorKm - site.geocentricVectorKm(last)
    }

    fun sunAltitude(ms: Long): Double {
        val ctx = SkyContext(ms)
        val s = ctx.sun
        return Horizon.altAz(s.raDeg, s.decDeg, ctx.lastDeg(site.lonDeg), site.latDeg).altDeg
    }

    fun moonAltitude(ms: Long): Double {
        val ctx = SkyContext(ms)
        val m = moonTopocentric(ctx)
        return Horizon.altAz(m.lonDeg, m.latDeg, ctx.lastDeg(site.lonDeg), site.latDeg).altDeg
    }

    fun targetAltitude(ms: Long): Double {
        val ctx = SkyContext(ms)
        val u = targetApparent(ctx)
        return Horizon.altAz(u.lonDeg, u.latDeg, ctx.lastDeg(site.lonDeg), site.latDeg).altDeg
    }

    /** Geocentric phase of the Moon (illumination does not depend on the site to any visible degree). */
    fun moonPhase(ms: Long): MoonPhase {
        val ctx = SkyContext(ms)
        return MoonPhase.compute(ctx.moon, ctx.sun)
    }

    /** Position angle of the Moon's bright limb measured from the zenith (χ − q), degrees. */
    fun moonBrightLimbFromZenithDeg(ms: Long): Double {
        val ctx = SkyContext(ms)
        val phase = MoonPhase.compute(ctx.moon, ctx.sun)
        val m = moonTopocentric(ctx)
        val aa = Horizon.altAz(m.lonDeg, m.latDeg, ctx.lastDeg(site.lonDeg), site.latDeg)
        val q = Horizon.parallacticAngleDeg(aa.hourAngleDeg, m.latDeg, site.latDeg)
        return norm360(phase.brightLimbPaDeg - q)
    }

    /**
     * Instant of the last new Moon (geocentric conjunction in ecliptic longitude) at or before [ms]:
     * step back a day at a time until the Moon–Sun elongation wraps through 0°, then bisect.
     */
    fun lastNewMoon(ms: Long): Long {
        fun elong(t: Long): Double = moonPhase(t).elongationEastDeg
        var t1 = ms
        var e1 = elong(t1)
        repeat(32) {
            val t0 = t1 - MS_PER_DAY
            val e0 = elong(t0)
            if (e0 > e1) {
                var lo = t0
                var hi = t1
                while (hi - lo > 1000) {
                    val mid = lo + (hi - lo) / 2
                    if (norm180(elong(mid)) < 0.0) lo = mid else hi = mid
                }
                return lo + (hi - lo) / 2
            }
            t1 = t0
            e1 = e0
        }
        return t1
    }

    /** Age of the Moon in days (time since the last new Moon). */
    fun moonAgeDays(ms: Long): Double = (ms - lastNewMoon(ms)) / 86_400_000.0

    fun sample(ms: Long): SkySample {
        val ctx = SkyContext(ms)
        val last = ctx.lastDeg(site.lonDeg)
        val sun = ctx.sun
        val sunAa = Horizon.altAz(sun.raDeg, sun.decDeg, last, site.latDeg)
        val u = targetApparent(ctx)
        val objRa = u.lonDeg
        val objDec = u.latDeg
        val objAa = Horizon.altAz(objRa, objDec, last, site.latDeg)
        val m = moonTopocentric(ctx)
        val moonRa = m.lonDeg
        val moonDec = m.latDeg
        val moonAa = Horizon.altAz(moonRa, moonDec, last, site.latDeg)
        return SkySample(
            epochMillis = ms,
            lastDeg = last,
            sunAlt = sunAa.altDeg,
            objAlt = objAa.altDeg,
            objAz = objAa.azDeg,
            objHourAngle = objAa.hourAngleDeg,
            airmass = airmassModel.airmass(objAa.altDeg),
            moonAlt = moonAa.altDeg,
            moonAz = moonAa.azDeg,
            moonSep = angularSeparationDeg(objRa, objDec, moonRa, moonDec),
        )
    }

    companion object {
        /** Constant of aberration, 20.49552", in radians (= v/c). */
        private const val ABERRATION_K = 20.49552 / 3600.0 * DEG2RAD
        private val COS_EPS_J2000 = cosD(23.4392911)
        private val SIN_EPS_J2000 = sinD(23.4392911)
    }
}
