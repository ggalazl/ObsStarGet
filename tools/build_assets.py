#!/usr/bin/env python3
"""
Builds the offline data assets shipped with the app:

  app/src/main/assets/objects.tsv  - deep-sky objects (OpenNGC) + named bright stars (HYG)
  app/src/main/assets/sites.tsv    - observatories (astropy site registry + additions)

Inputs (download once, then run this script):
  NGC.csv, addendum.csv : https://github.com/mattiaverga/OpenNGC (database_files/), CC BY-SA 4.0
  hygdata_v41.csv       : https://github.com/astronexus/HYG-Database (hyg/CURRENT/), CC BY-SA 4.0
  sites.json            : https://github.com/astropy/astropy-data (coordinates/sites.json); no separate licence file
                          (the Astropy Project's software is BSD-3); see THIRD_PARTY_NOTICES.md

Usage:  python3 tools/build_assets.py <input_dir> <assets_dir>

objects.tsv columns (tab separated, one object per line):
  display_name  type  ra_deg(ICRS/J2000)  dec_deg  pm_ra_cosdec(mas/yr)  pm_dec(mas/yr)  vmag  aliases('|' separated)
sites.tsv columns:
  key  name  latitude_deg(+N)  longitude_deg(+E, -180..180)  height_m  iana_timezone  aliases('|')
"""
import csv
import json
import os
import re
import sys

TYPES = {
    'G': 'Galaxy', 'GPair': 'Galaxy pair', 'GTrpl': 'Galaxy triplet', 'GGroup': 'Galaxy group',
    'OCl': 'Open cluster', 'GCl': 'Globular cluster', 'Cl+N': 'Cluster + nebula', '*Ass': 'Stellar association',
    'PN': 'Planetary nebula', 'Neb': 'Nebula', 'HII': 'HII region', 'EmN': 'Emission nebula',
    'RfN': 'Reflection nebula', 'SNR': 'Supernova remnant', 'DrkN': 'Dark nebula', 'Nova': 'Nova',
    '*': 'Star', '**': 'Double star', 'Other': 'Other',
}


def sexa_to_deg(s, hours):
    s = s.strip()
    sign = -1.0 if s.startswith('-') else 1.0
    parts = [float(p) for p in s.lstrip('+-').split(':')]
    while len(parts) < 3:
        parts.append(0.0)
    v = parts[0] + parts[1] / 60.0 + parts[2] / 3600.0
    return sign * v * (15.0 if hours else 1.0)


def pretty(name):
    """NGC0224 -> NGC 224, IC0434 -> IC 434, B033 -> B 33, ESO056-115 -> ESO 56-115"""
    m = re.match(r'^([A-Za-z]+)0*(\d.*)$', name)
    return f"{m.group(1)} {m.group(2)}" if m else name


def build_objects(indir):
    rows = list(csv.DictReader(open(os.path.join(indir, 'NGC.csv'), encoding='utf-8'), delimiter=';'))
    rows += list(csv.DictReader(open(os.path.join(indir, 'addendum.csv'), encoding='utf-8'), delimiter=';'))
    by_name = {}
    by_messier = {}
    objects = []
    dups = []
    for r in rows:
        t = r['Type']
        if t == 'NonEx':
            continue
        if t == 'Dup':
            dups.append(r)
            continue
        o = {
            'type': TYPES.get(t, t),
            'ra': sexa_to_deg(r['RA'], True),
            'dec': sexa_to_deg(r['Dec'], False),
            'vmag': r['V-Mag'] or r['B-Mag'] or '',
            'aliases': [],
        }
        al = o['aliases']
        if r['M']:
            mnum = int(r['M'])
            o['display'] = f"M{mnum}"
            al += [f"M {mnum}", f"Messier {mnum}", pretty(r['Name'])]
            by_messier[mnum] = o
        else:
            o['display'] = pretty(r['Name'])
        for col, prefix in (('NGC', 'NGC'), ('IC', 'IC')):
            for x in filter(None, (v.strip() for v in r[col].split(','))):
                al.append(f"{prefix} {int(x) if x.isdigit() else x.lstrip('0')}")
        for x in filter(None, (v.strip() for v in r['Common names'].split(','))):
            al.append(x)
        for x in filter(None, (v.strip() for v in r['Identifiers'].split(','))):
            al.append(x)
        by_name[r['Name']] = o
        objects.append(o)
    # Duplicate entries become aliases of the object they duplicate (OpenNGC convention).
    for r in dups:
        target = None
        if r['M'] and int(r['M']) in by_messier:
            target = by_messier[int(r['M'])]
        for col, prefix in (('NGC', 'NGC'), ('IC', 'IC')):
            if target is None and r[col].strip():
                key = prefix + r[col].split(',')[0].strip().zfill(4)
                target = by_name.get(key)
        if target is not None:
            target['aliases'].append(pretty(r['Name']))
        else:
            objects.append({'display': pretty(r['Name']), 'type': 'Duplicate entry',
                            'ra': sexa_to_deg(r['RA'], True), 'dec': sexa_to_deg(r['Dec'], False),
                            'vmag': '', 'aliases': []})
    out = []
    for o in objects:
        aliases = []
        seen = {o['display']}
        for a in o['aliases']:
            if a and a not in seen:
                seen.add(a)
                aliases.append(a)
        out.append((o['display'], o['type'], o['ra'], o['dec'], 0.0, 0.0, o['vmag'], aliases))
    return out


GREEK = {'Alp': 'alpha', 'Bet': 'beta', 'Gam': 'gamma', 'Del': 'delta', 'Eps': 'epsilon', 'Zet': 'zeta',
         'Eta': 'eta', 'The': 'theta', 'Iot': 'iota', 'Kap': 'kappa', 'Lam': 'lambda', 'Mu': 'mu', 'Nu': 'nu',
         'Xi': 'xi', 'Omi': 'omicron', 'Pi': 'pi', 'Rho': 'rho', 'Sig': 'sigma', 'Tau': 'tau', 'Ups': 'upsilon',
         'Phi': 'phi', 'Chi': 'chi', 'Psi': 'psi', 'Ome': 'omega'}
SIMBAD_GREEK = {'Alp': 'alf', 'Bet': 'bet', 'Gam': 'gam', 'Del': 'del', 'Eps': 'eps', 'Zet': 'zet', 'Eta': 'eta',
                'The': 'tet', 'Iot': 'iot', 'Kap': 'kap', 'Lam': 'lam', 'Mu': 'mu.', 'Nu': 'nu.', 'Xi': 'ksi',
                'Omi': 'omi', 'Pi': 'pi.', 'Rho': 'rho', 'Sig': 'sig', 'Tau': 'tau', 'Ups': 'ups', 'Phi': 'phi',
                'Chi': 'chi', 'Psi': 'psi', 'Ome': 'ome'}


def build_stars(indir):
    out = []
    for r in csv.DictReader(open(os.path.join(indir, 'hygdata_v41.csv'), encoding='utf-8')):
        name = r['proper'].strip()
        if not name or name == 'Sol':
            continue
        al = []
        bf = r['bf'].strip()            # e.g. "58Alp Ori", "Alp1Cen"
        m = re.match(r'^(\d*)([A-Z][a-z]{1,2})(\d?)\s*([A-Z][a-z]{2})$', bf)
        if m:
            flam, gr, sup, con = m.groups()
            if gr in GREEK:
                al += [f"{GREEK[gr]}{sup} {con}", f"{gr}{sup} {con}", f"{SIMBAD_GREEK[gr]}{sup} {con}"]
            if flam:
                al.append(f"{flam} {con}")
        else:
            m2 = re.match(r'^(\d+)\s*([A-Z][a-z]{2})$', bf)
            if m2:
                al.append(f"{m2.group(1)} {m2.group(2)}")
        for col, prefix in (('hip', 'HIP'), ('hd', 'HD'), ('hr', 'HR')):
            if r[col].strip():
                al.append(f"{prefix} {r[col].strip()}")
        if r['gl'].strip():
            al.append(r['gl'].strip())
        out.append((name, 'Star', float(r['ra']) * 15.0, float(r['dec']),
                    float(r['pmra'] or 0.0), float(r['pmdec'] or 0.0), r['mag'], al))
    return out


# Canonical IANA ids for the legacy/link ids used in the astropy registry.
ZONE_FIX = {'Chile/Continental': 'America/Santiago', 'US/Hawaii': 'Pacific/Honolulu',
            'US/Pacific': 'America/Los_Angeles', 'US/Mountain': 'America/Denver', 'US/Arizona': 'America/Phoenix',
            'US/Central': 'America/Chicago', 'US/Eastern': 'America/New_York', 'Canada/Eastern': 'America/Toronto',
            'Canada/Pacific': 'America/Vancouver', 'Asia/Calcutta': 'Asia/Kolkata', 'Greenwich': 'Europe/London',
            'Asia/Tel_Aviv': 'Asia/Jerusalem', 'NZ': 'Pacific/Auckland'}
# Particle-physics / gravitational-wave detectors in the registry are not sky-observing sites.
SKIP_SITES = {'ARCA', 'HALO', 'HyperK', 'IceCube', 'NOvA', 'ORCA', 'SNO+', 'SuperK', 'geo_600', 'kagra',
              'lho_4k', 'llo_4k', 'virgo'}
# Corrections to registry entries (key -> field overrides).
SITE_FIX = {'tona': {'elevation': 2150, 'timezone': 'America/Mexico_City'},
            'spm': {'timezone': 'America/Tijuana'},
            'alma': {'name': 'ALMA, Llano de Chajnantor'},
            'gbt': {'name': 'Green Bank Telescope'},
            'vla': {'name': 'Very Large Array (VLA)'},
            'ohp': {'aliases': ['OHP', 'Haute-Provence']},
            'irtf': {'aliases': ['IRTF']},
            'sirene': {'aliases': []}}
# Sites missing from the registry: (key, name, lat, lon_east, height_m, zone, aliases).
# Entries marked "approx." have coordinates good to ~1 km, far below what matters for observability.
EXTRA_SITES = [
    ('picdumidi', 'Pic du Midi Observatory', 42.9364, 0.1425, 2877, 'Europe/Paris', ['Pic du Midi', 'OMP']),
    ('saao', 'SAAO Sutherland', -32.3794, 20.8107, 1798, 'Africa/Johannesburg', ['Sutherland']),
    ('maunakea', 'Mauna Kea Observatories (summit)', 19.8207, -155.4681, 4205, 'Pacific/Honolulu', ['Mauna Kea']),
    ('casleo', 'CASLEO, El Leoncito (approx.)', -31.7986, -69.2956, 2552, 'America/Argentina/San_Juan',
     ['El Leoncito']),
    ('santamartina', 'Observatorio UC Santa Martina (approx.)', -33.2692, -70.5344, 1450, 'America/Santiago',
     ['Santa Martina']),
    ('cerrocalan', 'Observatorio Cerro Calan (approx.)', -33.3961, -70.5367, 860, 'America/Santiago',
     ['Cerro Calan']),
    ('skinakas', 'Skinakas Observatory, Crete (approx.)', 35.2119, 24.8992, 1750, 'Europe/Athens', ['Skinakas']),
    ('ondrejov', 'Ondrejov Observatory (approx.)', 49.9106, 14.7808, 528, 'Europe/Prague', ['Ondrejov']),
    ('sao_ras', 'Special Astrophysical Observatory, BTA (approx.)', 43.6468, 41.4405, 2070, 'Europe/Moscow',
     ['BTA', 'SAO RAS']),
]


def build_sites(indir):
    d = json.load(open(os.path.join(indir, 'sites.json'), encoding='utf-8'))
    out = []
    for key, v in d.items():
        if key in SKIP_SITES:
            continue
        v = dict(v)
        v.update(SITE_FIX.get(key, {}))
        lon = float(v['longitude'])
        lon = (lon + 180.0) % 360.0 - 180.0
        tz = v.get('timezone') or 'UTC'
        zone = ZONE_FIX.get(tz, tz)
        aliases = [a for a in v.get('aliases', []) if a and a != 'example_site']
        name = v['name']
        if name.lower() == key.lower() and aliases:
            name = aliases[0]
        out.append((key, name, float(v['latitude']), lon, float(v['elevation']), zone, aliases))
    out.extend(EXTRA_SITES)
    out.sort(key=lambda s: s[1].lower())
    return out


# Common abbreviations added to existing entries (display name -> extra aliases).
EXTRA_ALIASES = {
    'ESO 56-115': ['LMC'],                    # Large Magellanic Cloud
    'NGC 292': ['SMC'],                       # Small Magellanic Cloud
    'NGC 104': ['47 Tuc', '47 Tucanae'],
    'NGC 5139': ['Omega Cen', 'omega Cen'],
    'NGC 3372': ['Eta Carinae Nebula'],
    # Popular names that OpenNGC does not list.
    'M101': ['Pinwheel Galaxy'],
    'M74': ['Phantom Galaxy'],
    'M77': ['Cetus A', 'Squid Galaxy'],
    'M94': ["Cat's Eye Galaxy", "Croc's Eye Galaxy"],
    'M87': ['Virgo A', 'Virgo Cluster'],
    'NGC 253': ['Sculptor Galaxy', 'Silver Dollar Galaxy'],
    'NGC 3628': ['Hamburger Galaxy', "Sarah's Galaxy"],
    'NGC 4151': ['Eye of Sauron'],
    'NGC 1365': ['Great Barred Spiral Galaxy'],
    'IC 342': ['Hidden Galaxy'],
    'NGC 5907': ['Splinter Galaxy', 'Knife Edge Galaxy'],
    # Galaxy groups and clusters, placed at their brightest (central) galaxy: well within 0.5 deg of
    # the centre, which is irrelevant for airmass and visibility.
    'M66': ['Leo Triplet'],
    'NGC 4874': ['Coma Cluster', 'Abell 1656'],
    'NGC 1399': ['Fornax Cluster'],
    'NGC 1275': ['Perseus Cluster', 'Abell 426'],
    'NGC 4696': ['Centaurus Cluster', 'Abell 3526'],
    'NGC 3311': ['Hydra Cluster', 'Abell 1060'],
}
# A few famous objects outside OpenNGC/HYG proper names (ICRS J2000, from SIMBAD).
EXTRA_OBJECTS = [
    ('Sgr A*', 'Radio source', 266.416837, -29.007811, 0.0, 0.0, '', ['Sagittarius A*', 'Galactic Centre', 'Galactic Center']),
    ('Eta Carinae', 'Star', 161.264775, -59.684431, 0.0, 0.0, '4.3', ['eta Car', 'HD 93308', 'HR 4210']),
]


def main():
    indir, assets = sys.argv[1], sys.argv[2]
    objs = build_objects(indir) + build_stars(indir)
    objs = [(d, t, ra, dec, pa, pd, m, al + [a for a in EXTRA_ALIASES.get(d, []) if a not in al])
            for (d, t, ra, dec, pa, pd, m, al) in objs]
    missing = set(EXTRA_ALIASES) - {o[0] for o in objs}
    assert not missing, f"EXTRA_ALIASES for unknown objects: {missing}"
    objs += EXTRA_OBJECTS
    with open(os.path.join(assets, 'objects.tsv'), 'w', encoding='utf-8') as f:
        for (disp, typ, ra, dec, pmra, pmdec, mag, al) in objs:
            clean = [a.replace('\t', ' ').replace('|', '/') for a in al]
            f.write(f"{disp}\t{typ}\t{ra:.6f}\t{dec:.6f}\t{pmra:.2f}\t{pmdec:.2f}\t{mag}\t{'|'.join(clean)}\n")
    sites = build_sites(indir)
    with open(os.path.join(assets, 'sites.tsv'), 'w', encoding='utf-8') as f:
        for (key, name, lat, lon, h, zone, al) in sites:
            f.write(f"{key}\t{name}\t{lat:.6f}\t{lon:.6f}\t{h:.0f}\t{zone}\t{'|'.join(al)}\n")
    print(f"{len(objs)} objects, {len(sites)} sites")


if __name__ == '__main__':
    main()
