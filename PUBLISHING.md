# Publishing ObsStarGet on GitHub

How the app is shared with colleagues: the source code is in a public GitHub repository, and each version is a
GitHub *release* with the signed APK attached. Colleagues download the APK on their phones from the release page
(see the README) and report problems or suggestions as GitHub *issues*. All of this is free.

## Where things live

| What | Where | On GitHub? |
|---|---|---|
| Master copy of the project, edited here | Dropbox: `Science/Android Astronomy tools/Observability` | no (copied) |
| Build copy, which is also the folder GitHub Desktop publishes | `~/Observability-android` | yes |
| Release key (signs every published APK) | `~/ObsStarGet-signing/obsstarget-release.jks` | **never** |
| Finished APKs | Dropbox: `Observability/release-apk/ObsStarGet-<version>.apk` | attached to releases only |

Every `.command` script refreshes `~/Observability-android` from Dropbox. Do not edit files in the build copy: the
next refresh overwrites them. The copy's `.git` folder (its GitHub history) is kept by the refresh.

## The release key

`make_release_key.command` makes it once. Every update must be signed with the same key, otherwise colleagues cannot
install it over the version they have (they would have to uninstall first and lose their settings), and in 2027 the
key is also what registers the app with Google (below). So:
* keep a backup copy of `obsstarget-release.jks` (USB stick, or a private folder of your own) and keep its password;
* never make a second key (the script refuses if one exists);
* never put the key or the password in the project folder, in GitHub, or in an e-mail. `.gitignore` excludes
  `*.jks`, `*.keystore`, `*.apk` and `release-apk/`.

## First publication (once)

1. Double-click `make_release_key.command` and choose the password.
2. The GitHub user name goes into the app (Feedback button) and the README.
3. Double-click `build_release.command`: it asks for the key's password and leaves `release-apk/ObsStarGet-1.5.apk`.
4. Install **GitHub Desktop** (<https://desktop.github.com>) and sign in with your GitHub account.
5. In GitHub Desktop: *File → Add Local Repository…* → choose `~/Observability-android` → click **create a
   repository** → leave the name and the other fields as they are (Git ignore and License: *None*) → **Create
   Repository**. Then **Publish repository** → name **ObsStarGet** → untick **Keep this code private** → **Publish**.
6. On github.com, in the repository: **Releases → Create a new release** → tag `v1.5` → title *ObsStarGet 1.5* →
   drag `ObsStarGet-1.5.apk` into the box → **Publish release**.
7. On your own phone, uninstall the old ObsStarGet once (it was signed with the development key), then install the
   app from the release page exactly as colleagues will (README). From now on Android Studio's *Run* and
   `install_on_phone.command` install a separate app, *ObsStarGet dev*, next to it.
8. Send colleagues the link `https://github.com/ggalazl/ObsStarGet/releases/latest` and point them to the README.

## Publishing an update

1. The project in Dropbox is changed and the version raised in `app/build.gradle.kts`
   (`versionCode` +1 and `versionName`, e.g. 6 → 7 and "1.5" → "1.6").
2. Double-click `build_release.command`.
3. GitHub Desktop now lists the changed files: write a summary such as *Version 1.6* → **Commit to main** →
   **Push origin**.
4. On github.com: **Releases → Draft a new release** → tag `v1.6` → attach `ObsStarGet-1.6.apk` → describe what
   changed → **Publish release**. The *latest* link then points to it.

## Feedback

* Issues: the forms *Report a problem* and *Suggest an improvement* (kept in `github_forms/`, copied to
  `.github/ISSUE_TEMPLATE/` in the build copy). GitHub e-mails you
  for each new issue. Close an issue once it is fixed or answered.
* Optional: *Settings → General → Features → Discussions* adds a forum for general impressions.

## Android developer verification

Google now requires apps installed on certified Android phones to be registered by a verified developer:
since 30 September 2026 in Brazil, Indonesia, Singapore and Thailand, and worldwide from 2027. Unregistered apps
can still be installed with Android's "advanced flow" (a one-time setting with a 24-hour wait) or with adb.
Options before the global rollout:
* a free *limited distribution* account in the Android Developer Console (no fee, no ID): installs only on a
  limited number of devices that each user authorises (Google's pages give 20 or 50);
* a *full distribution* account: one-time USD 25 fee and identity verification, no limit on installs;
* nothing: colleagues use the advanced flow.

Both accounts register the package name `cl.astro.observability` together with the release key, which is one more
reason never to lose the key or change the package name. The choice of account type cannot be changed later.
