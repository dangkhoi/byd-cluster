# ClusterNav

> [!CAUTION]
> **CURRENT STATUS: `1.12` (versionCode 112) — OTA self-test build.** The complete JVM suite is green off-car and the release APK builds cleanly and is signed, with all test/instrument-write surfaces (the T10 probe harness) confined to the `vehicleTest` build type and **absent from the release APK** (verified with `aapt2` on this build). From `1.11` the owner publishes each plain `apk/ClusterNav-<ver>-release.apk` to `main` so the app self-updates **over-the-air (OTA)** onto the car for testing — no ADB/laptop needed. **This is the owner's own iterative on-car test channel, not a supported public release, and is separate from the formal exact-source `collectAuthorizedApk` / Stage-11 candidate process (which remains its own distinct gate).** It is a hobby experiment with no driving-safety, compatibility, reversibility, or production-readiness claim — install at your own risk. The archived `1.04` vehicle-test candidate stays **blocklisted** in the on-car scripts (it exported the T10 `TEST_ADAS_*` / `TEST_SPEED_LIMIT` surface); the current release exports none.

ClusterNav is a personal hobby experiment by **Đăng Khôi · `dangkhoi`** for exploring navigation and cluster projection on BYD DiLink hardware. It is not affiliated with BYD and makes no driving-safety, compatibility, reversibility, or production-readiness claim.

## Target product baseline — exactly two tracks

1. **Navigation + HUD** — one authoritative navigation source/session with independent Cluster-lane and HUD outputs.
2. **Cluster Cast** — an independent durable state, journal, execution, recovery, UI and rollout pipeline.

The tracks may share one APK as packaging, but they must not share runtime control, mutable state, live transport, executor, journal, lifecycle or recovery. Home is a renderer/dispatcher, not an orchestrator.

**GPS Dead Reckon and mock-location are removed.** On 2026-07-27 the owner ended the experiment: it failed too often to keep, and a future attempt should start from a new approach rather than this source. The six files (1,096 lines) are deleted from the working tree; git history remains the only record, which is where rollback belongs. Do not select ClusterNav as the mock-location app — it can no longer act as one.

## Downloads and installation

**Current version: 1.12 (versionCode 112).** `1.12` adds an in-app **cluster nav-display mode selector** (Đơn giản / Toàn màn hình / Màn hình nhỏ / OFF) that drives the OEM nav-on-cluster setting (`SET_NAVI_SCREEN_STATUS_SET`, `0x4C10E015`) over the BYDAuto HAL, so navigation renders in the cluster **centre** ("Giữa + ETA") instead of only the small top strip — replacing the clusterDebug op39 path (a no-op for the centre view on this trim). The app self-updates **over-the-air**: it polls this repo's `apk/` folder on `main` for a newer `ClusterNav-<ver>-release.apk` and installs it via the on-device dadb loopback (`-r`, same signing key) — no ADB/laptop. To build the same release from source: `./gradlew :app:assembleRelease`. The formal exact-source vehicle candidate is a separate flow (the authorized `collectAuthorizedApk` pipeline; see the build context below).

> ⚠️ The archived `apk/ClusterNav-1.04-v104-527589f2d16a-release.apk` predates the WARN-1 hardening and still exports the T10 `TEST_ADAS_*` / `TEST_SPEED_LIMIT` ADAS/instrument-write surface — **do not install it** (its SHA-256 is now blocklisted in the on-car install guard, which refuses it). The current `1.12` release built from `main` exports no test surface.

Features:
- **Cluster Cast** — projection-first: open app → cluster ready instantly; tap floating button to cast foreground app to cluster; tap again to return.
- **CarPlay / Android Auto** — always full-screen, no resize.
- **Regular apps** — full or split, adjustable size.
- **Navigation + HUD** — one navigation source with independent cluster-lane and HUD outputs.

> ⚠️ This is a hobby experiment. No driving-safety, compatibility, reversibility, or production-readiness claim. Install at your own risk. Not affiliated with BYD.

## Documentation

- [Two-track final plan](docs/specs/clusternav-two-track-final-plan.html) — derived orchestration and evidence gates.
- [Cluster Cast re-baseline](docs/specs/cluster-cast-rebaseline.html) — canonical Cast contracts.
- [Navigation/UX re-baseline](docs/specs/clusternav-uxui-rebaseline.html) — two-card target UX and Navigation contracts.
- [Dead Reckon revalidation](docs/specs/dead-reckon-revalidation.html) — REMOVE decision and deferred review debt.
- [Historical UI guide](docs/HUONG-DAN.md) — quarantined screenshot/archive reference, not installation guidance.
- [Vehicle Test V2 checklist](docs/diagnostics/VEHICLE-TEST-V2.md) — prepared operator scripts and Stage 11 matrix; execution remains NOT STARTED.

Older files under `docs/review/`, `docs/diagnostics/`, `docs/reference/`, and previous specs describe historical builds or investigations. They are context only unless a current spec explicitly promotes an item into a new exact-source/exact-build gate.

## Developer build context

The Android project uses JDK 17 and Android SDK platform/build-tools 34. The Cast subsystem uses a simplified projection-first architecture: 4-state model (IDLE → PROJECTING → CASTING → RETURNING), single floating button for cast/return, no complex state machines or recovery pipelines. Build with `./gradlew :app:assembleRelease`.

## Safety and evidence boundaries

- Physical power-button reboot is required when a test calls for a real head-unit reboot; `adb reboot` is not accepted as equivalent evidence.
- No merge to `main` before final exact-build on-car PASS and explicit merge authorization.
- No commit/push without the mandatory public-repository sensitive-data scan.
- Historical helper/unit results cannot close current V2, UX, release or vehicle gates.

## Credits

See [CREDITS.md](CREDITS.md). The project uses [`dadb`](https://github.com/mobile-dev-inc/dadb) under Apache-2.0.

## License

[MIT](LICENSE).
