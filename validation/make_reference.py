#!/usr/bin/env python3
"""
Generates independent reference values with astropy (ERFA) for the app's unit tests:

    python3 validation/make_reference.py app/src/test/resources/astropy_reference.tsv

Rows (tab separated):
  POS  site  target  epoch_ms  alt  az            target apparent alt/az (no refraction)
  SUN  site  -       epoch_ms  alt  az
  MOON site  -       epoch_ms  alt  az  illum     topocentric Moon, geocentric illuminated fraction
  GAST -     -       epoch_ms  gast_deg  ut1_minus_utc_s
  EVT  site  name    epoch_ms  level  rising       Sun / Moon / target altitude crossings found by bisection
  NEWMOON -  -       epoch_ms                     geocentric conjunctions in apparent ecliptic longitude

Astropy is run offline with its bundled IERS tables (UT1-UTC is exported with the GAST rows; the app
assumes UT1 = UTC, a < 0.9 s approximation).
"""
import sys
import warnings

import numpy as np
import astropy.units as u
from astropy.coordinates import (AltAz, EarthLocation, SkyCoord, get_body, Distance, GeocentricTrueEcliptic)
from astropy.time import Time
from astropy.utils import iers

iers.conf.auto_download = False
iers.conf.iers_degraded_accuracy = 'warn'
warnings.simplefilter('ignore')

SITES = {
    'lasilla': (-29.256667, -70.730000, 2347.0),
    'lapalma': (28.758333, -17.880000, 2327.0),
    'tromso': (69.6496, 18.9560, 100.0),
}
TARGETS = {
    'M31': (10.684792, 41.269056, 0.0, 0.0),
    'NGC7531': (348.702083, -43.599944, 0.0, 0.0),
    'Vega': (279.234600, 38.783692, 201.02, 287.46),
    'Polaris': (37.946250, 89.264109, 44.22, -11.74),
}
DISTANCE_PC = {'Vega': 7.68, 'Polaris': 132.6}


def loc(key):
    lat, lon, h = SITES[key]
    return EarthLocation.from_geodetic(lon * u.deg, lat * u.deg, h * u.m)


def target_coord(name, t):
    ra, dec, pmra, pmdec = TARGETS[name]
    if pmra == 0.0 and pmdec == 0.0:
        return SkyCoord(ra * u.deg, dec * u.deg, frame='icrs')
    c = SkyCoord(ra * u.deg, dec * u.deg, frame='icrs', pm_ra_cosdec=pmra * u.mas / u.yr,
                 pm_dec=pmdec * u.mas / u.yr, distance=Distance(DISTANCE_PC[name] * u.pc),
                 radial_velocity=0 * u.km / u.s, obstime=Time('J2000'))
    return c.apply_space_motion(new_obstime=t)


def altaz_frame(t, key):
    return AltAz(obstime=t, location=loc(key), pressure=0 * u.hPa)


def ms(t):
    return int(round((t.utc.jd - 2440587.5) * 86400000.0))


def moon_illum(t):
    sun = get_body('sun', t)
    moon = get_body('moon', t)
    elong = sun.separation(moon)
    r = sun.distance
    d = moon.distance
    i = np.arctan2(r * np.sin(elong), d - r * np.cos(elong))
    return float((1 + np.cos(i)) / 2)


def crossing(f, t0, t1, level):
    """Bisection between two Times bracketing a crossing of f(t) = level."""
    a, b = t0, t1
    fa = f(a) - level
    for _ in range(40):
        m = a + (b - a) / 2
        fm = f(m) - level
        if (fa < 0) == (fm < 0):
            a, fa = m, fm
        else:
            b = m
        if (b - a).sec < 0.2:
            break
    return a + (b - a) / 2


def main(out):
    rows = []
    # Hourly samples through the night of 2026-11-07 (Chile) plus scattered epochs over 2020-2032.
    times = list(Time('2026-11-07T21:00:00', scale='utc') + np.arange(0, 14) * u.hour)
    times += [Time(x, scale='utc') for x in ('2020-03-20T03:49:00', '2024-04-08T18:00:00', '2025-12-24T06:30:00',
                                            '2027-06-15T01:00:00', '2028-09-01T23:15:00', '2030-01-01T00:00:00',
                                            '2032-07-04T12:00:00')]
    for t in times:
        m = ms(t)
        rows.append(f"GAST\t-\t-\t{m}\t{t.sidereal_time('apparent', 0 * u.deg).deg:.7f}\t{t.delta_ut1_utc:.6f}")
        illum = moon_illum(t)
        for sk in SITES:
            fr = altaz_frame(t, sk)
            for tk in TARGETS:
                aa = target_coord(tk, t).transform_to(fr)
                rows.append(f"POS\t{sk}\t{tk}\t{m}\t{aa.alt.deg:.6f}\t{aa.az.deg:.6f}")
            s = get_body('sun', t, location=loc(sk)).transform_to(fr)
            rows.append(f"SUN\t{sk}\t-\t{m}\t{s.alt.deg:.6f}\t{s.az.deg:.6f}")
            mo = get_body('moon', t, location=loc(sk)).transform_to(fr)
            rows.append(f"MOON\t{sk}\t-\t{m}\t{mo.alt.deg:.6f}\t{mo.az.deg:.6f}\t{illum:.6f}")

    # Events for the night of 2026-11-07 at La Silla and La Palma, and 2027-06-15 at La Silla.
    for sk, start in (('lasilla', '2026-11-07T16:00:00'), ('lapalma', '2026-11-07T12:00:00'),
                      ('lasilla', '2027-06-15T16:00:00')):
        t0 = Time(start, scale='utc')
        grid = t0 + np.arange(0, 24 * 6 + 1) * 10 * u.min

        def sun_alt(t, sk=sk):
            return get_body('sun', t, location=loc(sk)).transform_to(altaz_frame(t, sk)).alt.deg

        def moon_alt(t, sk=sk):
            return get_body('moon', t, location=loc(sk)).transform_to(altaz_frame(t, sk)).alt.deg

        def m31_alt(t, sk=sk):
            return target_coord('M31', t).transform_to(altaz_frame(t, sk)).alt.deg

        for name, f, level in (('sun', sun_alt, -0.8333), ('sun', sun_alt, -18.0), ('moon', moon_alt, -0.8333),
                               ('M31', m31_alt, -0.5667)):
            vals = np.array([f(t) for t in grid]) - level
            for i in range(1, len(grid)):
                if (vals[i - 1] < 0) != (vals[i] < 0):
                    tc = crossing(f, grid[i - 1], grid[i], level)
                    rows.append(f"EVT\t{sk}\t{name}\t{ms(tc)}\t{level}\t{int(vals[i] > vals[i - 1])}")

    # New Moons (geocentric conjunction in apparent ecliptic longitude), Oct 2026 - Jul 2027.
    def elong(t):
        frame = GeocentricTrueEcliptic(equinox=t)
        lm = get_body('moon', t).transform_to(frame).lon.deg
        ls = get_body('sun', t).transform_to(frame).lon.deg
        return (lm - ls + 180.0) % 360.0 - 180.0

    grid = Time('2026-10-01T00:00:00', scale='utc') + np.arange(0, 300, 0.5) * u.day
    vals = [elong(t) for t in grid]
    for i in range(1, len(grid)):
        if vals[i - 1] < 0 <= vals[i]:
            tc = crossing(elong, grid[i - 1], grid[i], 0.0)
            rows.append(f"NEWMOON\t-\t-\t{ms(tc)}")
    with open(out, 'w') as fh:
        fh.write('\n'.join(rows) + '\n')
    print(len(rows), 'rows')


if __name__ == '__main__':
    main(sys.argv[1])
