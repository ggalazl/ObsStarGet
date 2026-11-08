# Installing ObsStarGet on a Samsung phone (development builds)

This page is for building the app from source on the Mac. Colleagues install the published app from GitHub instead (see the README).

The app is a plain Android Studio project. As with the aviation app, it is built from a copy outside Dropbox: Gradle cannot copy files out of Dropbox's macOS folder and fails with "Operation not permitted". Android Studio builds the copy and installs it on the phone over USB.

Requirements:
* **Phone:** Android 8.0 or newer.
* **Mac:** Android Studio, Android SDK 36 and a Java 21. All three are already installed.

## 1. Prepare the phone (once)

1. **Turn on developer options.** Go to **Settings → About phone → Software information** and tap **Build number** seven times, entering your PIN when asked. You will see "Developer mode has been turned on".
2. **Turn on USB debugging.** Go to **Settings → Developer options** (at the bottom of Settings) and switch on **USB debugging**.
3. **If you use Auto Blocker,** go to **Settings → Security and privacy → Auto Blocker** and switch it off while installing. Auto Blocker blocks installs over a USB cable. You can switch it back on afterwards.

If the aviation app is already on the phone, steps 1–2 are already done.

## 2. Copy the project out of Dropbox, then open it in Android Studio

1. Run **`prepare_android.command`** from this Dropbox folder.
   * Double-click it, or paste this line in Terminal:
     ```
     cd ~/Library/CloudStorage/Dropbox/Science/"Android Astronomy tools"/Observability && chmod +x *.command && ./prepare_android.command
     ```
   * It copies the project to **`~/Observability-android`**, like `npm run android:prepare` did for the aviation app.
2. In Android Studio, choose **File → Open…** and select **`~/Observability-android`** (your home folder → `Observability-android`).
3. Choose **Trust Project** if asked.
4. Wait for **Gradle sync** to finish (progress bar at the bottom). The first sync downloads the Kotlin plugin, so it can take a few minutes.

Gradle runs on Java 21, set in `gradle/gradle-daemon-jvm.properties`. The Java 25 bundled with Android Studio is too new for Gradle 8.14.3.

**Edit the code in Dropbox, not in the copy.** Running `prepare_android.command` again refreshes `~/Observability-android` with your changes. It keeps the build outputs and Android Studio's settings in the copy, but it overwrites any source file you edited there.

## 3. Connect the phone and run

1. Connect the phone with a USB **data** cable and unlock it.
2. On the phone, accept **"Allow USB debugging?"**. Tick *Always allow from this computer*.
3. In Android Studio's toolbar, the device menu shows the phone (e.g. *Samsung SM-S9xx*). The run configuration next to it should be **app**.
4. Press **Run ▶** (or Ctrl+R).
   * Android Studio builds the APK, installs it and starts **ObsStarGet** on the phone.
   * The first time, the phone may show a Play Protect dialog. Choose **More details → Install anyway**. This only appears because the app is signed with your development key.

The app then stays on the phone like any other app, and the USB cable is no longer needed. After any change to the code, press Run again to update it. Your saved targets and settings are kept.

### Without a cable: Wi-Fi debugging

The Mac and the phone must be on the same Wi-Fi network.
1. On the phone, open **Developer options → Wireless debugging** and switch it on.
2. In Android Studio, open the device menu and choose **Pair Devices Using Wi-Fi**.
3. On the phone, go to **Wireless debugging → Pair device with QR code** and scan the QR code that Android Studio shows.

### Without opening Android Studio

Double-click **`install_on_phone.command`** in this folder, with the phone connected and USB debugging on. It refreshes the copy in `~/Observability-android`, then builds the app with Java 21 and installs it with `./gradlew installDebug`. **`test_build.command`** does the same build without a phone, runs the unit tests, and writes `test_build.log` next to itself.

If macOS says the file cannot be opened:
* **Unidentified developer:** right-click the file, choose **Open**, then confirm.
* **"You don't have the necessary access privileges", or *permission denied*:** run the `chmod +x *.command` line from step 2 once.

## Updating to a new version

After the project in Dropbox has changed, run `prepare_android.command` again (or `install_on_phone.command`, which does it for you). Then press *Run* in Android Studio. The new version installs over the old one and keeps your targets, site and settings.

From version 1.5 the app is published on GitHub (see [PUBLISHING.md](PUBLISHING.md)), and the builds made here are development builds: they install as a separate app, **ObsStarGet dev** (package id `cl.astro.observability.debug`), next to the published **ObsStarGet**, with their own settings. Before installing the published APK the first time, uninstall the old ObsStarGet once: it was signed with the development key, so Android cannot update it with the release.

From version 1.2 the app is called **ObsStarGet** (title: *Astronomical object Observability*). Only the name changed: the package id is the same, so 1.2 replaces the installed app and keeps your settings, and the folders keep their names (`Observability` in Dropbox, `~/Observability-android` for the build). Exported images now go to *Pictures/ObsStarGet*.

In 1.4 the page has a new look: the name **ObsStarGet** in colour with the full title under it, coloured lines that divide the page into numbered sections, and a large blue **Compute Observability** button.

Things to try in 1.3:
* The amber **Compute Best Observability Window** button, right under **Compute Observability**: it finds the best months for the active target, fills in the date window (now just below the two buttons) and computes it.
* *Options → Time axis*: besides the site's local time and UT, the phone's time zone or a fixed offset such as UTC−3, UTC−2 or UTC+5.

Things to try in 1.2:
* The *Instructions* and *Disclaimer* buttons at the top (also in the ⋮ menu).
* The green **⤢ FULL SCREEN** buttons above the diagrams.

Things to try after updating to 1.1:
* Add *Cartwheel Galaxy*, *Hoag's Object* or *Arp 220*: these come from NED and SIMBAD, so the phone must be online the first time.
* Add *Andromeda*, *Cat's Eye* or *Sculptor* to see an offline match or a list of candidates.
* Scroll to **Best time of year** to see the year chart of the active target.
* Double-tap a diagram for full screen, then ⟳ for landscape.
* Under *Options*, set *Airmass axis* to 1.0 – 2.0 or 1.0 – 2.5.
* In **Best time of year**, tap a night on the chart, then *Go to …*: a calendar opens at that night.

## Where the build files go

Builds run in `~/Observability-android`, outside Dropbox. The installable APK, if you want to copy it to the phone by hand, is:

```
~/Observability-android/app/build/outputs/apk/debug/app-debug.apk
```

## If something goes wrong

| Symptom | Fix |
|---|---|
| The phone is not in the device menu | Use a data cable (not a charge-only cable), unlock the phone, accept the USB-debugging prompt. Or pull down the notification shade, tap the USB notification and choose *Transferring files*. |
| `INSTALL_FAILED_USER_RESTRICTED` or the install is silently blocked | Switch off Auto Blocker (step 1.3) and run again. |
| Gradle sync fails with "SDK location not found" | Check that `local.properties` contains `sdk.dir=/Users/gaspargalaz/Library/Android/sdk`, or open **Tools → SDK Manager** once. |
| Gradle sync asks to upgrade the Android Gradle Plugin | It is safe to decline. If you accept, the upgrade assistant updates the versions in `build.gradle.kts`. |
| Gradle fails at once with "What went wrong: 25.0.3", or with "Unsupported class file major version 69" | Gradle 8.14.3 cannot run on Java 25, the Java bundled with recent Android Studio versions. `gradle/gradle-daemon-jvm.properties` already asks for Java 21, and the two `.command` scripts pick Java 21 themselves. If Android Studio still fails, go to **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK** and choose **jbr-21** (in `~/Library/Java/JavaVirtualMachines`). |
| "Unable to download toolchain matching … languageVersion=21" | No Java 21 is installed. In the same **Gradle JDK** menu, choose **Download JDK… → version 21**. |
| "No defined toolchain download url" | Same cause as the row above: no Java 21 is installed. Install Java 21 the same way, through **Download JDK… → version 21**. |
| "Operation not permitted" while packaging resources | The build was started inside the Dropbox folder. Build from `~/Observability-android` (step 2) instead. |
| An app with the same name but a different signature is installed | Uninstall the old copy from the phone, then press Run again. |
