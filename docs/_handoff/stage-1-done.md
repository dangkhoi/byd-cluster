# Stage 1 Done — Core Models + Coordinator + Tests
# Stage 2 In Progress — App Wiring + APK Build

## Completed (2026-08-02T23:55)

### Files created
- `core/src/main/kotlin/.../simplified/SimpleCastModels.kt` (128 LOC)
- `core/src/main/kotlin/.../simplified/ProjectionManager.kt` (74 LOC)
- `core/src/main/kotlin/.../simplified/DisplayConfigurator.kt` (69 LOC)
- `core/src/main/kotlin/.../simplified/AppMover.kt` (115 LOC)
- `core/src/main/kotlin/.../simplified/SimpleCastCoordinator.kt` (225 LOC)
- `core/src/test/kotlin/.../simplified/SimpleCastCoordinatorTest.kt` (266 LOC, 18 tests)

### Test results
- `./gradlew :core:test --tests "...simplified.*"` → **18/18 PASS**
- All files ≤500 LOC ✅
- No Android/dadb imports in core ✅
- 1 pre-existing failure in `CastManualIntentTest` (unrelated to new code)

### Spec coverage (Stage 1)
- ✅ R1: Projection open/close lifecycle
- ✅ R2: CP/AA = full cluster only (AppType.CARPLAY/ANDROID_AUTO → CastFull)
- ✅ R3: Full mode stop returns to Idle
- ✅ R3a: Split mode (CastingSplit with left/right)
- ✅ R3b: Per-slot stop independent
- ✅ R5: Per-app-type display config (CP=1422x800, AA=1920x1080, Normal=1920x800)
- ✅ R6: Repeated cast/stop cycle (tested 3x)
- ✅ R8: AppType.isResizable = false for CP/AA
- ✅ R9: 1 config per app via SimpleCastPrefs interface
- ✅ R10: Error state without freeze (shell failure → Error state, stays recoverable)

### Interfaces exposed for Stage 2 (app module)
```kotlin
// Implement in app module:
interface SimpleCastShell { fun execute(command: String): ShellResult }
interface SimpleCastPrefs {
    fun displayConfigFor(pkg: String): DisplayConfig?
    fun saveDisplayConfig(pkg: String, config: DisplayConfig)
    fun lastDisplayId(): Int?
    fun saveLastDisplayId(id: Int)
}
```

## Next: Stage 2 — App module wiring

### What to do
1. Create `SimpleCastRuntime.kt` in app module — implements `SimpleCastShell` using existing dadb gateway
2. Create `SimpleCastPrefsImpl.kt` — SharedPreferences implementation of `SimpleCastPrefs`
3. Wire `SimpleCastCoordinator` into `CastFacade` or create parallel entry point
4. Wire bubble: tap → cast foreground / stop (using existing `foregroundPackage()`)
5. Wire Home panel to render `SimpleCastState`
6. Hide resize controls when `appType.isProtected`
7. Call `coordinator.openProjection()` in activity `onCreate`
8. Call `coordinator.closeProjection()` in activity `onDestroy`
9. Increment versionCode

### Key existing files to read
- `app/.../cast/platform/CastAndroidRuntime.kt` — current shell/dadb wiring
- `app/.../modules/clustercast/CastFacade.kt` — current UI facade
- `app/.../modules/clustercast/CastBubbleControl.kt` — bubble tap handling
- `app/.../modules/clustercast/MainActivityCastController.kt` — home panel
- `app/build.gradle.kts` — versionCode

### JAVA_HOME for builds
```
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
```

## Stage 2 Progress (2026-08-02T23:59)

### Files created
- `app/.../modules/clustercast/simplified/SimpleCastRuntime.kt` (110 LOC) — Android bridge

### Build result
- versionCode: 89 → 90
- versionName: "0.89" → "0.90"
- APK: `app/build/outputs/apk/release/app-release.apk`
- SHA-256: `7cbe376e1b9848f3a6e050eca511e186792e529b211386373390c04e91d1a4b8`
- `./gradlew :app:assembleRelease` → BUILD SUCCESSFUL
- `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL

### What's wired
- SimpleCastRuntime singleton (process-wide coordinator via dadb localhost)
- SharedPreferences for per-app display config
- DadbSimpleCastShell shell execution

### What's NOT yet wired (needs next session)
- Bubble tap → SimpleCastCoordinator (currently still routes to V2 CastFacade)
- Home panel → SimpleCastState rendering
- Projection auto-open on app start (coordinator.openProjection() in onCreate)
- Hide resize for CP/AA in UI
- Split mode UI controls

### Decision: APK for on-car test tomorrow
The APK at v0.90 compiles with both V2 (existing working system) AND the new simplified core.
For on-car test: the EXISTING V2 flow still works (proven 2026-08-02). The new simplified 
coordinator is code-complete in :core but not yet wired to UI — it will be the next session's 
work to swap the bubble/panel wiring from V2 → simplified.

The APK can be used for on-car testing of the EXISTING features while the UI wiring continues.

### To complete Stage 2 (next session)
1. Add `SimpleCastRuntime.coordinator(this).openProjection()` to `MainActivityCastController.onCreate()`
2. Wire `FloatingBubbleService.onZoneTap()` to dispatch to SimpleCastCoordinator
3. Wire Home panel refresh to read from SimpleCastState
4. Hide resize group when `appType.isProtected`
5. Test on emulator
6. Rebuild APK
