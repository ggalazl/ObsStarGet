# Third-party data and services

ObsStarGet's own source code is under the MIT licence (see `LICENSE`). The app also bundles data derived from the
catalogues below and queries the online services listed at the end. Their licences and requested acknowledgements
are given here.

## Bundled data

### OpenNGC (NGC/IC/Messier objects and addendum)
* Author: Mattia Verga. Source: <https://github.com/mattiaverga/OpenNGC> (`database_files/NGC.csv`, `addendum.csv`).
* Licence: Creative Commons Attribution-ShareAlike 4.0 International (CC BY-SA 4.0),
  <https://creativecommons.org/licenses/by-sa/4.0/>.

### HYG star database (named bright stars)
* Author: David Nash (astronexus). Source: <https://github.com/astronexus/HYG-Database> (`hyg/CURRENT/hygdata_v41.csv`).
* Licence: CC BY-SA 4.0, <https://creativecommons.org/licenses/by-sa/4.0/>.

**`app/src/main/assets/objects.tsv`** is an adapted database built from OpenNGC and HYG by `tools/build_assets.py`
(selected columns, normalised names, added common names and aliases). As required by the ShareAlike terms, this file
is distributed under **CC BY-SA 4.0**, with attribution to the authors above. Changes from the originals: columns
were selected and reformatted, names normalised and aliases added; positions and proper motions are as published.

### Observatory sites
* Source: the astropy-data site registry, <https://github.com/astropy/astropy-data> (`coordinates/sites.json`),
  maintained by the Astropy Project. The repository has no separate licence file; the Astropy Project's software is
  licensed under a 3-clause BSD licence ("Copyright (c) 2011-2026, Astropy Developers").
* **`app/src/main/assets/sites.tsv`** keeps the registry's coordinates and time zones as published, plus a few sites
  added by hand and marked "approx." (good to about 1 km).

## Online services

Names that are not in the built-in catalogue are resolved online. The app sends the name typed by the user and nothing
else.

* **NED.** This research has made use of the NASA/IPAC Extragalactic Database (NED), which is funded by the National
  Aeronautics and Space Administration and operated by the California Institute of Technology.
  <https://ned.ipac.caltech.edu>
* **CDS Sesame / SIMBAD / VizieR.** This research has made use of the SIMBAD database, CDS, Strasbourg Astronomical
  Observatory, France (Wenger et al. 2000, A&AS 143, 9), and of the VizieR catalogue access tool, CDS, Strasbourg
  Astronomical Observatory, France (Ochsenbein et al. 2000, A&AS 143, 23). Name resolution uses the CDS Sesame service
  <https://cds.unistra.fr>.
