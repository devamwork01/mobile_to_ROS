# Premium Mobile UI — Phase 1 (Design System + Navigation + Home + Sensor Selection) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Recompose the Android app's presentation layer into a premium, theme-aware product — a reusable design system, simple bottom navigation, a hero Home screen (Canvas pseudo-3D phone reacting to live orientation), and a categorized Sensor Selection screen — reading only from the existing `StreamViewModel`, with zero backend changes.

**Architecture:** The current UI is a single `StreamScreen.kt` on a bare Material3 dark default. This phase introduces `ui/theme/` design tokens (dark default + light, both premium), a `ui/signal/` UI-facing mapping layer (human-readable names, descriptions, categories, icons, units, formatting) over the existing `SensorTypes`/`Units`, reusable `ui/components/`, a Canvas pseudo-3D `Phone3DView` driven by the existing rotation-vector telemetry, a minimal in-app navigator (no new nav dependency), and two screens (Home, Sensors). All state comes from `StreamViewModel` (`engineState`, `catalog`, `sel`, `live`, `presets`, `toggle`, `setPeriod`, `toggleStreaming`, `discover`, `discovering`). `StreamScreen.kt` is retired at the end of the phase.

**Tech Stack:** Kotlin, Jetpack Compose (Material3, BOM already present), Compose Canvas for pseudo-3D. Pure helpers unit-tested on the JVM (JUnit). No new heavy dependency. Build with **JBR 21**.

**Spec:** `Mobile UI Change Only — Premium SensorStream Mobile Application.md` (repo root) + the two reference images `Gemini_Generated_Image_dark_theme.png` / `_light.png`. This plan implements the spec's Priorities 1–4 / Screens 1–2; Screens 3–17 are Phases 2–4 (roadmap at end).

## Global Constraints

- **UI ONLY. Do not modify** sensor acquisition, `SensorManager`/listeners/sampling/timestamps, fusion/quaternion/coordinate math, network (`UDP`/`WS`/discovery/serialization), `StreamController`/`StreamEngine`/`StreamingService`/`LocalRecorder`, threading/coroutines, or any telemetry-timing code. The UI reads existing state; if something isn't exposed, add the **smallest read-only** accessor — never change behavior. (Spec §§3, 51, 56.)
- **Consume the existing state layer:** all screen data comes from `StreamViewModel`. No screen touches `SensorManager`, sockets, or raw sensor events. (Spec §51.)
- **Axis colors are universal and fixed:** X=`#ff5c5c` (red), Y=`#3fd07a` (green), Z=`#4c8dff` (blue) — identical in both themes, everywhere. (Spec §7.)
- **Status colors:** green=connected/streaming/active/healthy, amber=warning/reduced accuracy, red=error/disconnected. Never communicate state by color alone — always pair with text/icon. (Spec §§7, 12, 47.)
- **Dark theme is the default**; light theme is a real re-design (warm off-white, soft gray surfaces, dark type), not an inversion. (Spec §§5, 6.)
- **Human-readable sensor names as primary labels** ("Acceleration", "Angular Velocity"); raw `TYPE_*` only in technical info. Always show units; tabular/monospace numerals; engineering precision (e.g. 3 decimals, not 12). (Spec §§8, 18, 32, 34.)
- **Performance:** never rebuild whole screens per sample; drive visuals from the ViewModel's already-decimated `live`/`engineState` flows (~12 Hz snapshot / 1 s state). No per-sample recomposition; target ~60 fps; never block acquisition/network. (Spec §§43, 44.)
- **Build/tests with JBR 21:** prefix Gradle with `JAVA_HOME="/c/Users/devam/.jdks/jbr-21.0.11"`. `adb` = `/c/Users/devam/AppData/Local/Android/Sdk/platform-tools/adb.exe`. Device serials: S25 Ultra `RZGL31H3N4V`, M36 5G `RZGL50Z0GYJ`. On-device screenshot: `adb -s <serial> exec-out screencap -p > out.png`.
- **Reuse, don't duplicate** (Spec §62): build the design system + components first, then compose screens from them.

---

### Task 1: Design-system tokens (theme-aware colors, type, shape, spacing)

**Files:**
- Create: `android/app/src/main/java/com/sensorstream/ui/theme/Color.kt`
- Create: `android/app/src/main/java/com/sensorstream/ui/theme/Type.kt`
- Create: `android/app/src/main/java/com/sensorstream/ui/theme/Dimens.kt`
- Modify: `android/app/src/main/java/com/sensorstream/ui/theme/Theme.kt`

**Interfaces:**
- Produces:
  - `data class SsColors(val bg: Color, val surface: Color, val surface2: Color, val line: Color, val fg: Color, val muted: Color, val faint: Color, val accent: Color, val accentSoft: Color, val ok: Color, val warn: Color, val err: Color, val axisX: Color, val axisY: Color, val axisZ: Color, val isDark: Boolean)`
  - `val LocalSsColors: ProvidableCompositionLocal<SsColors>` and `object Ss { val colors: SsColors @Composable get() = LocalSsColors.current; val dims: SsDims; ... }`
  - `object SsDims { val screenPad = 20.dp; val cardPad = 16.dp; val gap = 12.dp; val radius = 20.dp; val radiusSm = 14.dp }`
  - `SensorStreamTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit)` — provides `LocalSsColors` + a Material3 scheme mapped from `SsColors`, and the typography from `Type.kt`.
  - Typography `SsType` with `display` (large numeric hero), `title`, `body`, `label`, and `mono` (tabular figures via `FontFeatureSettings("tnum")`).

- [ ] **Step 1: Color palettes** — in `Color.kt`, define `darkSsColors()` and `lightSsColors()`. Dark (from the reference + laptop tokens): bg `#0A0D12`, surface `#12161D`, surface2 `#171C25`, line `#272E39`, fg `#E8EDF4`, muted `#8B95A4`, faint `#5A6472`, accent `#3D7BFD`, accentSoft `#17233D`, ok `#3FD07A`, warn `#E3A635`, err `#FF5C5C`. Light: bg `#ECEFF4`, surface `#FFFFFF`, surface2 `#F6F8FB`, line `#D6DCE5`, fg `#1A2029`, muted `#5A6472`, faint `#8A93A1`, accent `#2F6BF0`, accentSoft `#E6EEFE`, ok `#199A54`, warn `#B07714`, err `#DC3B3B`. Axis (both): X `#FF5C5C`, Y `#3FD07A`, Z `#4C8DFF`.

```kotlin
// Color.kt (essentials)
package com.sensorstream.ui.theme
import androidx.compose.ui.graphics.Color

data class SsColors(
    val bg: Color, val surface: Color, val surface2: Color, val line: Color,
    val fg: Color, val muted: Color, val faint: Color,
    val accent: Color, val accentSoft: Color,
    val ok: Color, val warn: Color, val err: Color,
    val axisX: Color, val axisY: Color, val axisZ: Color, val isDark: Boolean,
)
private val AxisX = Color(0xFFFF5C5C); private val AxisY = Color(0xFF3FD07A); private val AxisZ = Color(0xFF4C8DFF)
fun darkSsColors() = SsColors(
    bg = Color(0xFF0A0D12), surface = Color(0xFF12161D), surface2 = Color(0xFF171C25), line = Color(0xFF272E39),
    fg = Color(0xFFE8EDF4), muted = Color(0xFF8B95A4), faint = Color(0xFF5A6472),
    accent = Color(0xFF3D7BFD), accentSoft = Color(0xFF17233D),
    ok = Color(0xFF3FD07A), warn = Color(0xFFE3A635), err = Color(0xFFFF5C5C),
    axisX = AxisX, axisY = AxisY, axisZ = AxisZ, isDark = true,
)
fun lightSsColors() = SsColors(
    bg = Color(0xFFECEFF4), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFF6F8FB), line = Color(0xFFD6DCE5),
    fg = Color(0xFF1A2029), muted = Color(0xFF5A6472), faint = Color(0xFF8A93A1),
    accent = Color(0xFF2F6BF0), accentSoft = Color(0xFFE6EEFE),
    ok = Color(0xFF199A54), warn = Color(0xFFB07714), err = Color(0xFFDC3B3B),
    axisX = AxisX, axisY = AxisY, axisZ = AxisZ, isDark = false,
)
```

- [ ] **Step 2: Typography + dims** — `Type.kt` defines `ssTypography()` (Material3 `Typography` using the default system font — Inter isn't bundled; system font is the acceptable "equivalent high-quality system font" per Spec §8) with a distinct large-number `displayLarge`. Add a `mono` `TextStyle` with `fontFeatureSettings = "tnum"` and `FontFamily.Monospace` for values. `Dimens.kt` defines `SsDims` as above.

- [ ] **Step 3: Theme wiring** — rewrite `Theme.kt`:

```kotlin
package com.sensorstream.ui.theme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color

val LocalSsColors = staticCompositionLocalOf { darkSsColors() }
object Ss { val colors: SsColors @Composable get() = LocalSsColors.current }

@Composable
fun SensorStreamTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) darkSsColors() else lightSsColors()
    val scheme = if (dark)
        darkColorScheme(primary = c.accent, background = c.bg, surface = c.surface, onSurface = c.fg, error = c.err)
    else
        lightColorScheme(primary = c.accent, background = c.bg, surface = c.surface, onSurface = c.fg, error = c.err)
    CompositionLocalProvider(LocalSsColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = ssTypography(), content = content)
    }
}
```

- [ ] **Step 4: Build gate** — `cd /c/mobile_to_ROS_app/android && JAVA_HOME="/c/Users/devam/.jdks/jbr-21.0.11" ./gradlew assembleDebug`. Expected: BUILD SUCCESSFUL. (StreamScreen still uses the old theme call signature `SensorStreamTheme { }` — the new signature has a default `dark`, so it still compiles.)

- [ ] **Step 5: Commit** — `git commit -m "feat(phone-ui): premium theme-aware design tokens (dark + light)"` (+ trailer).

---

### Task 2: UI signal-mapping layer (human names, descriptions, categories, icons, units, format)

**Files:**
- Create: `android/app/src/main/java/com/sensorstream/ui/signal/SignalCatalog.kt`
- Create: `android/app/src/main/java/com/sensorstream/ui/signal/Format.kt`
- Test: `android/app/src/test/java/com/sensorstream/ui/signal/SignalCatalogTest.kt`
- Test: `android/app/src/test/java/com/sensorstream/ui/signal/FormatTest.kt`

**Interfaces:**
- Produces:
  - `enum class SensorCategory { MOTION, ORIENTATION, MAGNETIC, ENVIRONMENT, PROXIMITY, OTHER }`
  - `data class SignalInfo(val humanName: String, val description: String, val category: SensorCategory, val icon: String, val unit: String, val componentLabels: List<String>)`
  - `object SignalCatalog { fun of(type: Int): SignalInfo }` — human-readable mapping (Spec §18/§19), reusing `Units.forType` where sensible. Unknown types → `OTHER`, `humanName = SensorTypes.displayName(type)`.
  - `object Fmt { fun value(x: Float, decimals: Int = 3): String; fun magnitude(v: FloatArray): String; fun hz(x: Float): String }` — engineering precision, fixed decimals (Spec §34).

- [ ] **Step 1: Failing tests**

```kotlin
// SignalCatalogTest.kt
package com.sensorstream.ui.signal
import android.hardware.Sensor
import org.junit.Assert.assertEquals
import org.junit.Test
class SignalCatalogTest {
    @Test fun mapsMotionAndOrientation() {
        assertEquals("Acceleration", SignalCatalog.of(Sensor.TYPE_ACCELEROMETER).humanName)
        assertEquals(SensorCategory.MOTION, SignalCatalog.of(Sensor.TYPE_ACCELEROMETER).category)
        assertEquals("Angular Velocity", SignalCatalog.of(Sensor.TYPE_GYROSCOPE).humanName)
        assertEquals("Orientation", SignalCatalog.of(Sensor.TYPE_ROTATION_VECTOR).humanName)
        assertEquals(SensorCategory.ORIENTATION, SignalCatalog.of(Sensor.TYPE_ROTATION_VECTOR).category)
        assertEquals(SensorCategory.MAGNETIC, SignalCatalog.of(Sensor.TYPE_MAGNETIC_FIELD).category)
        assertEquals(SensorCategory.ENVIRONMENT, SignalCatalog.of(Sensor.TYPE_PRESSURE).category)
    }
    @Test fun unknownFallsBackToOther() {
        val info = SignalCatalog.of(99999)
        assertEquals(SensorCategory.OTHER, info.category)
    }
}
```
```kotlin
// FormatTest.kt
package com.sensorstream.ui.signal
import org.junit.Assert.assertEquals
import org.junit.Test
class FormatTest {
    @Test fun valueUsesFixedDecimals() { assertEquals("9.812", Fmt.value(9.81237f, 3)) }
    @Test fun magnitudeOfVector() { assertEquals("5.000", Fmt.magnitude(floatArrayOf(3f, 4f, 0f))) }
}
```

- [ ] **Step 2: Run — expect FAIL** (`Unresolved reference: SignalCatalog` / `Fmt`).
  `JAVA_HOME=... ./gradlew testDebugUnitTest --tests "com.sensorstream.ui.signal.*"`

- [ ] **Step 3: Implement** `SignalCatalog.kt` (map every type in `SensorTypes.displayName` to a human name + description + category + icon key + unit via `Units.forType` + component labels like `["X","Y","Z"]`) and `Format.kt` (`"%.${decimals}f"` formatting; magnitude = sqrt of sum of squares). Icons are string keys resolved later against a Compose icon set (Task 4 defines the resolver).

- [ ] **Step 4: Run — expect PASS.**

- [ ] **Step 5: Commit** — `"feat(phone-ui): UI signal mapping (human names, categories, units, formatting)"`.

---

### Task 3: Orientation projection math for the pseudo-3D phone (pure, tested)

**Files:**
- Create: `android/app/src/main/java/com/sensorstream/ui/viz/Projection.kt`
- Test: `android/app/src/test/java/com/sensorstream/ui/viz/ProjectionTest.kt`

**Interfaces:**
- Produces:
  - `data class Vec3(val x: Float, val y: Float, val z: Float)`
  - `object Projection { fun rotationVectorToMatrix(rv: FloatArray): FloatArray  // 9-float row-major R; mirrors SensorManager.getRotationMatrixFromVector semantics but pure-Kotlin so it JVM-tests`
  - `fun rotate(r: FloatArray, v: Vec3): Vec3`
  - `fun project(v: Vec3, scale: Float): Pair<Float, Float>  // simple isometric/orthographic: screenX = x*scale; screenY = (-y*0.5 - z... ) — a fixed camera basis`
  - `fun eulerDeg(r: FloatArray): Triple<Float, Float, Float>  // roll, pitch, yaw in degrees` }`
- Consumes: nothing (pure). Note: this **does not change** any acquisition math — it's a display-only projection of the *existing* rotation-vector values the UI already receives.

- [ ] **Step 1: Failing tests** — identity rotation vector `[0,0,0]` (w derived =1) → matrix ≈ identity; rotating the +Y axis by identity stays +Y; a known 90° rotation about Z maps +X→+Y (within 1e-3). Euler of identity ≈ (0,0,0).

```kotlin
// ProjectionTest.kt (essentials)
package com.sensorstream.ui.viz
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs
class ProjectionTest {
    private fun near(a: Float, b: Float) = assertEquals(b.toDouble(), a.toDouble(), 1e-3)
    @Test fun identity() {
        val r = Projection.rotationVectorToMatrix(floatArrayOf(0f, 0f, 0f))
        val v = Projection.rotate(r, Vec3(0f, 1f, 0f))
        near(v.x, 0f); near(v.y, 1f); near(v.z, 0f)
    }
    @Test fun ninetyAboutZ() {
        val s = kotlin.math.sin(Math.PI / 4).toFloat()  // rot vector = axis*sin(theta/2)
        val r = Projection.rotationVectorToMatrix(floatArrayOf(0f, 0f, s))
        val v = Projection.rotate(r, Vec3(1f, 0f, 0f))
        near(v.x, 0f); near(v.y, 1f); near(v.z, 0f)
    }
}
```

- [ ] **Step 2: Run — expect FAIL.**
- [ ] **Step 3: Implement** the quaternion (from rotation vector; derive w = sqrt(1 - x²-y²-z²) clamped) → 3×3 matrix, `rotate`, orthographic `project` (fixed camera looking slightly down the +Z/‑Z with a gentle tilt so the phone reads as floating), and `eulerDeg`.
- [ ] **Step 4: Run — expect PASS.**
- [ ] **Step 5: Commit** — `"feat(phone-ui): pure orientation→matrix/project/euler helpers for pseudo-3D"`.

---

### Task 4: Core reusable components (badges, button, cards, header, icons)

**Files:**
- Create: `android/app/src/main/java/com/sensorstream/ui/components/Components.kt` (StatusBadge, PrimaryActionButton, SsCard, MetricCard, SectionHeader, SamplingRateBadge, SignalValue)
- Create: `android/app/src/main/java/com/sensorstream/ui/components/SsIcons.kt` (maps SignalCatalog icon keys + nav/status icons to Material `Icons.*` — no new dependency)

**Interfaces:**
- Produces (all `@Composable`, styled from `Ss.colors`/`SsDims`):
  - `StatusBadge(state: StreamPhase, modifier)` — dot + text; color per state, never color-only. `enum class StreamPhase { READY, CONNECTING, CONNECTED, STREAMING, RECONNECTING, DISCONNECTED, ERROR }` + `fun EngineState.phase(): StreamPhase` (derive from `connecting/connected/streaming/error`).
  - `PrimaryActionButton(text: String, onClick, enabled, danger: Boolean = false)` — gradient/soft-shadow accent button (danger=err tint for STOP).
  - `SsCard(modifier, content)` — surface + line + rounded + subtle shadow.
  - `MetricCard(label: String, value: String, modifier)` — small stat tile.
  - `SectionHeader(text)`, `SamplingRateBadge(hz: String)`, `SignalValue(label, value, unit, axisColor: Color? = null)`.
  - `object SsIcons { fun forKey(key: String): ImageVector }`.

- [ ] **Step 1: Implement components** using the theme tokens. `phase()` derivation:
```kotlin
fun EngineState.phase(): StreamPhase = when {
    error != null -> StreamPhase.ERROR
    streaming -> StreamPhase.STREAMING
    connected -> StreamPhase.CONNECTED
    connecting -> StreamPhase.CONNECTING
    else -> StreamPhase.READY
}
```
- [ ] **Step 2: Build gate** — `assembleDebug` SUCCESSFUL.
- [ ] **Step 3: Commit** — `"feat(phone-ui): reusable premium components (badge/button/cards/header/icons)"`.

---

### Task 5: Phone3DView — Canvas pseudo-3D phone with live axes/vector

**Files:**
- Create: `android/app/src/main/java/com/sensorstream/ui/viz/Phone3DView.kt`
- Test: `android/app/src/test/java/com/sensorstream/ui/viz/Phone3DConfigTest.kt` (pure helper only)

**Interfaces:**
- Produces:
  - `@Composable fun Phone3DView(rotationVector: FloatArray?, sensorVector: Vec3? = null, sensorVectorColor: Color? = null, showAxes: Boolean = true, showLabels: Boolean = true, modifier: Modifier)` — draws a stylized rounded phone body (metallic vertical gradient, soft contact shadow, subtle glow) rotated by the projection matrix, plus projected X/Y/Z axis arrows (fixed axis colors) with labels, plus an optional sensor vector arrow. Uses `Canvas` + the Task-3 pure math. Recomposes only when its inputs change (driven by the ~12 Hz `live` snapshot, not per sample).
  - Pure helper `object Phone3DConfig { fun axisEndpoints(r: FloatArray, cx: Float, cy: Float, len: Float): Map<String, Pair<Float,Float>> }` (JVM-tested: identity rotation puts +X to the right, +Y up, +Z toward camera per the fixed camera basis).

- [ ] **Step 1: Failing test** for `axisEndpoints` (identity → X endpoint x > cx, Y endpoint y < cy). Run — expect FAIL.
- [ ] **Step 2: Implement** the helper + the Canvas composable. Painter'y drawing only; no bitmap assets. Depth via layered translucent fills + a blurred contact shadow ellipse.
- [ ] **Step 3: Run helper test — expect PASS.**
- [ ] **Step 4: Build gate** — `assembleDebug` SUCCESSFUL.
- [ ] **Step 5: Commit** — `"feat(phone-ui): Canvas pseudo-3D Phone3DView (live axes + sensor vector)"`.

---

### Task 6: Minimal in-app navigation shell

**Files:**
- Create: `android/app/src/main/java/com/sensorstream/ui/nav/AppNav.kt`
- Create: `android/app/src/main/java/com/sensorstream/ui/AppScaffold.kt`
- Modify: `android/app/src/main/java/com/sensorstream/MainActivity.kt` (host `AppScaffold` instead of `StreamScreen` directly; keep the existing FLAG_KEEP_SCREEN_ON + battery-exemption logic untouched)

**Interfaces:**
- Produces:
  - `sealed interface Screen { object Home; object Sensors; object Connection; object Settings; data class Detail(val handle: Int) }` and `class AppNav` — a `rememberSaveable` back stack (`SnapshotStateList<Screen>`) with `current`, `go(Screen)`, `back(): Boolean`, plus `rememberAppNav()`.
  - `@Composable fun AppScaffold(vm: StreamViewModel)` — a `Scaffold` with a bottom navigation bar (Home / Sensors / Connection / Settings) + `BackHandler` wired to `AppNav.back()`, rendering the current screen. Phase 1 implements Home + Sensors; Connection/Settings show a themed placeholder ("Coming in a later phase") so nav is complete without dead crashes.
- Consumes: `StreamViewModel`. Note: MainActivity already owns theme + keep-screen-on; only swap the content composable.

- [ ] **Step 1: Implement** `AppNav` + `AppScaffold` (bottom bar uses `SsIcons`, accent for the selected item). Wire `MainActivity` `setContent { SensorStreamTheme { AppScaffold(vm) } }`, preserving all existing lifecycle/flag code.
- [ ] **Step 2: Build gate** — `assembleDebug` SUCCESSFUL.
- [ ] **Step 3: Commit** — `"feat(phone-ui): minimal bottom-nav shell (no new nav dependency)"`.

---

### Task 7: Home screen (hero 3D + connection + summary + start/stop)

**Files:**
- Create: `android/app/src/main/java/com/sensorstream/ui/screens/HomeScreen.kt`
- Modify: `android/app/src/main/java/com/sensorstream/ui/AppScaffold.kt` (route Home → `HomeScreen`)

**Interfaces:**
- Consumes: `StreamViewModel` (`engineState`, `catalog`, `sel`, `live`, `discover`, `discovering`, `toggleStreaming`), `Phone3DView`, components, `SignalCatalog`.
- Produces: `@Composable fun HomeScreen(vm: StreamViewModel, nav: AppNav)`.

- [ ] **Step 1: Compose the hierarchy** (Spec §9 order): `SensorStreamHeader` (title + settings icon → nav.go(Settings)); hero `Phone3DView` fed the live rotation-vector values (`live[rotVecHandle]?.values`, resolve the rotation-vector handle from `catalog`); `ConnectionCard` from `engineState` (`StatusBadge(phase)`, host from `sel.host`, `Latency = engineState.rttMs ms`); two `MetricCard`s (Active Sensors = `sel.enabled.size`, Total Rate = sum of selected `1e6/period` → "N Hz"); a compact active-sensor summary (top few enabled: human name + rate, "+ n more" → nav.go(Sensors)); `PrimaryActionButton` (START/STOP STREAMING via `toggleStreaming()`, danger when streaming). Empty state when `sel.enabled` is empty (Spec §41): "No active sensors — Configure Sensors" → nav.go(Sensors).
- [ ] **Step 2: Build gate** — `assembleDebug` SUCCESSFUL.
- [ ] **Step 3: Commit** — `"feat(phone-ui): premium Home screen (hero 3D, connection, summary, start/stop)"`.

---

### Task 8: Sensor Selection screen (categorized premium rows)

**Files:**
- Create: `android/app/src/main/java/com/sensorstream/ui/screens/SensorsScreen.kt`
- Create: `android/app/src/main/java/com/sensorstream/ui/components/SensorCard.kt`
- Modify: `android/app/src/main/java/com/sensorstream/ui/AppScaffold.kt` (route Sensors → `SensorsScreen`)

**Interfaces:**
- Consumes: `StreamViewModel` (`catalog`, `sel`, `toggle`, `periodOf`, `presets`, `live`), `SignalCatalog`, components.
- Produces: `@Composable fun SensorsScreen(vm: StreamViewModel)`; `@Composable fun SensorCard(info: SignalInfo, hz: String, enabled: Boolean, onToggle: () -> Unit, onInfo: (() -> Unit)? = null)`.

- [ ] **Step 1: Compose** — group `catalog` by `SignalCatalog.of(type).category`, ordered MOTION → ORIENTATION → MAGNETIC → ENVIRONMENT → PROXIMITY → OTHER, each under a `SectionHeader`. Each row = `SensorCard` (icon, human name, description, `SamplingRateBadge` from `periodOf(handle)`, unit, `Switch` bound to `sel.enabled`/`toggle(handle)`, subtle highlight when enabled). Category order + filtering shows every catalog sensor exactly once. (Config/rate editing is Phase 2 Screen 3; here the badge shows the current rate read-only, tapping the card is a no-op or opens info if `onInfo` wired later.)
- [ ] **Step 2: Build gate** — `assembleDebug` SUCCESSFUL.
- [ ] **Step 3: Commit** — `"feat(phone-ui): categorized Sensor Selection screen + SensorCard"`.

---

### Task 9: Retire StreamScreen + on-device verification (dark + light, real data)

**Files:**
- Delete: `android/app/src/main/java/com/sensorstream/ui/StreamScreen.kt` (superseded by Home + Sensors; confirm nothing else references it).

- [ ] **Step 1: Remove** `StreamScreen.kt`; grep for references (`grep -rn StreamScreen android/app/src`) and fix any (should only be MainActivity, already swapped in Task 6). `assembleDebug` SUCCESSFUL; run full unit suite (`testDebugUnitTest`) — all pure-helper tests green.
- [ ] **Step 2: Install + screenshot** on the S25 (`RZGL31H3N4V`): build, `adb install -r`, launch. Capture Home + Sensors in **dark**; then flip to light (Phase 1 default follows system — toggle the OS dark-mode setting, or temporarily force `SensorStreamTheme(dark=false)` to screenshot light) and re-capture. Verify against the reference images.
- [ ] **Step 3: Functional validation** (Spec Screen-1/2 checklists): connection status + streaming state reflect real `engineState`; Start/Stop actually starts/stops the existing service (stream a few seconds, confirm the laptop receives — reuse the earlier laptop server flow); the hero phone tilts with real device motion (rotation-vector); active count + total rate are real; toggling a sensor in Sensors changes the active set live (existing `toggle`→`updateSelections`). Confirm **no telemetry regression** (packets flow, dropped 0) — the UI change must not affect the pipeline.
- [ ] **Step 4: Record evidence** in `docs/superpowers/plans/2026-09-16-mobile-ui-phase1-verification.md` (screenshots' findings + functional checks) and commit.

---

## Self-Review

**Spec coverage (Phase 1 scope):** Priority 1 design system → Task 1; Priority 2 signal mapping → Task 2; §§7/8/34 color/type/precision → Tasks 1–2; §§10/11 pseudo-3D hero + live axes → Tasks 3, 5, 7; §§12/13/14/15 status/button/connection/summary → Tasks 4, 7; Priority 3 Home → Task 7; Priority 4 Sensor Selection (§§16–20) → Tasks 4, 8; §35 nav → Task 6; dark+light (§§5,6) → Task 1 + Task 9 verification; performance (§§43,44) → Global Constraints + driving from decimated flows. Screens 3–17 (config, sensor-detail, environmental, connection, diagnostics, settings, sheets/overlays/empty/error/loading, consistency, polish) are **deferred to Phases 2–4** (roadmap below) — intentional, not missing.

**Placeholder scan:** Connection/Settings routes render a themed "later phase" placeholder deliberately (nav completeness), not a dead crash; every code step has real code or a precise composition recipe against named ViewModel APIs.

**Type consistency:** `SsColors`/`Ss.colors` used across Tasks 1/4/5/7/8; `SignalInfo`/`SignalCatalog.of` across 2/7/8; `Vec3`/`Projection`/`Phone3DConfig` across 3/5/7; `StreamPhase`/`EngineState.phase()` across 4/7; `AppNav`/`Screen` across 6/7/8. All ViewModel calls (`engineState`, `catalog`, `sel`, `live`, `toggle`, `periodOf`, `presets`, `toggleStreaming`, `discover`, `discovering`) match `StreamViewModel` exactly (verified against the current file).

**Scope:** Foundation + 2 screens — a coherent, independently shippable, on-device-verifiable increment. Good for one plan.

---

## Roadmap (later phases, planned after Phase 1 lands + review)

- **Phase 2 — Sensor detail + config:** reusable `SensorDetailScreen` (hero `Phone3DView` + sensor vector, `SignalValue` X/Y/Z + magnitude, `MiniSignalGraph`, `SensorInfoSheet`) configured per sensor → Magnetic Field, Orientation (Euler/Quaternion toggles + frame toggles + reset), Acceleration, Angular Velocity (rad/s↔deg/s display unit), Gravity; Screen-3 sampling-rate config sheet (presets from `vm.presets`). (Spec Screens 3–8, 13, 28–30, 33.)
- **Phase 3 — Environmental + connection + diagnostics:** Environmental screen (pressure gauge, temp/humidity/light) from available sensors; Connection screen with all states + discovery; Diagnostics from `engineState` (latency, sendBps, dropped, backfillServed, active/rate). (Spec Screens 9–11, 27, 36–38.)
- **Phase 4 — Settings + states + polish:** Settings (theme override incl. light/dark/system, units, precision, 3D toggles); streaming overlay; empty/error/loading states; microinteractions; cross-screen consistency + final visual polish vs the reference. (Spec Screens 12, 14–17, §§42, 58–60.)

---

## Execution Handoff

Plan complete and saved. Two execution options: **Subagent-Driven** (fresh agent per task) or **Inline** (this session, checkpoints). Recommended: **inline** — consistent with how Phase 2A/2B were built cleanly this session, and each task ends in a build/screenshot gate the controller can verify directly.
