# Build instructions

This repo is authored on Windows; the **laptop receiver runs on Linux** at runtime, and the
**Android app is built on this Windows PC** and installed to the phone over USB.

## What's already installed here
Python 3.10/3.11, Node 26. Missing (needed for the phone app): JDK, Android SDK, `adb`,
Android Studio, Gradle.

---

## Laptop side (Python) — works today, cross-platform

```bash
cd laptop
python -m venv .venv
# Windows:  .venv\Scripts\activate       Linux:  source .venv/bin/activate
pip install -r requirements.txt
python -m pytest            # 14 tests should pass
```

No build step — it runs from source. See `docs/run.md`.

---

## Android side — install the toolchain (one time)

### 1. Install Android Studio (bundles the SDK + a JDK)
Fast path on this PC:
```powershell
winget install --id Google.AndroidStudio -e
```
Launch Android Studio once and complete the **Setup Wizard** — it downloads the Android SDK,
platform-tools (`adb`), and build-tools. Default SDK location: `%LOCALAPPDATA%\Android\Sdk`.

### 2. Open the project
Android Studio → **Open** → select `C:\mobile_to_ROS_app\android`. Let Gradle sync; it will
download the Gradle 8.9 wrapper and dependencies, and generate `local.properties` pointing at
your SDK. (First sync needs internet.)

> If prompted to upgrade AGP/Gradle, accept — the versions in `gradle/libs.versions.toml`
> (AGP 8.7.2 / Kotlin 2.0.21 / Gradle 8.9) are a known-good starting point.

### 3. Run the one automated Android test (no phone needed)
The codec's cross-language golden-vector test runs on the JVM:
```powershell
# from android\, using the Studio-bundled JDK if java isn't on PATH:
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest
```
Or in the IDE: right-click `BinaryPacketCodecTest` → Run. It must pass — it asserts the phone
produces the exact same bytes as `laptop/tests/golden_packet.bin`.

### 4. Build + install to the phone (S25 Ultra / M36)
- On the phone: **Settings → About → tap Build number 7×** to unlock Developer options, then
  **Developer options → USB debugging = ON**. Plug into the PC; accept the RSA prompt.
- Verify: add `%LOCALAPPDATA%\Android\Sdk\platform-tools` to PATH, then `adb devices` lists it.
- In Android Studio press **Run ▶** (or `./gradlew installDebug`). The app installs and launches.

---

## Command-line Gradle (optional)
`adb` and `gradlew` need a JDK 17. The Studio JBR works:
`setx JAVA_HOME "C:\Program Files\Android\Android Studio\jbr"` (new shell after). Then
`gradlew.bat assembleDebug` builds `app/build/outputs/apk/debug/app-debug.apk`.
