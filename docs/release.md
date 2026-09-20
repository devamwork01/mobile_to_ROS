# Cutting a release (maintainer)

Testers who don't want to install the Android build toolchain get the app from GitHub
**Releases**. This is how to publish one.

## 1. Build the APK

```bash
cd android
JAVA_HOME="/path/to/jbr-21" ./gradlew assembleDebug      # Windows: JAVA_HOME=<AndroidStudio>\jbr
#   -> app/build/outputs/apk/debug/app-debug.apk
```

A **debug** APK is fine for testers (it's signed with the debug key and installs via
sideload / `adb install`). For a Play-style signed build, run `assembleRelease` with your
own keystore — never commit the keystore (it's covered by `.gitignore`).

Rename the artifact so the version is obvious, e.g. `sensorstream-vX.Y.Z-debug.apk`.

## 2. Create the GitHub Release

On github.com → **Releases** → **Draft a new release**:

- **Tag:** `vX.Y.Z` (create it on `main`).
- **Title:** `SensorStream vX.Y.Z`.
- **Attach** the renamed APK as a binary asset.
- Paste the notes below.

(With the `gh` CLI authenticated you can instead run:
`gh release create vX.Y.Z app/build/outputs/apk/debug/app-debug.apk -t "SensorStream vX.Y.Z" -F notes.md`.)

## 3. Release-notes template

```markdown
## SensorStream vX.Y.Z

### Install (no build tools needed)
1. Download `sensorstream-vX.Y.Z-debug.apk` below.
2. On the phone, allow installing from your browser/file manager (or `adb install <apk>`).
3. Open **SensorStream**, go to **Connection**, and point it at your laptop
   (Find Laptop Automatically, or LAN IP + control port 8081).

Run the laptop receiver with `python -m sensorstream.app` (see the README). No phone?
Try `python -m sensorstream.app --selftest` or `python tools/fake_phone.py`.

### Changes
- ...

### Known issues
- ...
```

Keep the tag on `main` so the source matches the shipped APK.
