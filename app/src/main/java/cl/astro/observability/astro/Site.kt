package cl.astro.observability.astro

import java.time.ZoneId
import kotlin.math.atan

/**
 * An observing site. Longitude is east-positive, latitude geodetic (WGS84), height above the ellipsoid
 * (sea level is fine: 100 m changes nothing visible here).
 */
data class Site(
    val key: String,
    val name: String,
    val latDeg: Double,
    val lonDeg: Double,
    val heightM: Double,
    val zoneId: String,
    val aliases: List<String> = emptyList(),
) {
    val zone: ZoneId get() = runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.of("UTC"))

    /** ρ cos φ' and ρ sin φ' (geocentric coordinates in units of the equatorial radius; Meeus ch. 11). */
    val rhoCosPhi: Double
    val rhoSinPhi: Double

    init {
        val ba = 1.0 - WGS84_FLATTENING
        val u = atan(ba * tanD(latDeg)) * RAD2DEG
        val h = heightM / (EARTH_EQUATORIAL_RADIUS_KM * 1000.0)
        rhoSinPhi = ba * sinD(u) + h * sinD(latDeg)
        rhoCosPhi = cosD(u) + h * cosD(latDeg)
    }

    /** Geocentric position of the observer in km, true equator of date, for a local apparent sidereal time. */
    fun geocentricVectorKm(lastDeg: Double): Vec3 = Vec3(
        EARTH_EQUATORIAL_RADIUS_KM * rhoCosPhi * cosD(lastDeg),
        EARTH_EQUATORIAL_RADIUS_KM * rhoCosPhi * sinD(lastDeg),
        EARTH_EQUATORIAL_RADIUS_KM * rhoSinPhi,
    )
}

/**
 * A fixed celestial target: ICRS (≈J2000) coordinates at epoch J2000, with optional proper motion
 * (μα* = μα cos δ and μδ, in mas/yr).
 */
data class Target(
    val name: String,
    val raDeg: Double,
    val decDeg: Double,
    val pmRaCosDecMasYr: Double = 0.0,
    val pmDecMasYr: Double = 0.0,
    val type: String = "",
    val source: String = "",
    val aliases: List<String> = emptyList(),
)
