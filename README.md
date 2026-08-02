# ClusterNav

> [!CAUTION]
> **CURRENT STATUS: OFF-CAR CANDIDATE `0.72` — FIELD-EXECUTION CORRECTION.** The 2026-07-25 vehicle run of the previous source failed on every case; the cluster placement recipe, bootstrap preconditions and verification constants have been corrected against the field-proven V1 behaviour (canonical D10) and the complete JVM suite is green off-car. The authorized vehicle-test candidate now exists and is recorded in [`docs/_handoff/vehicle-candidate.json`](docs/_handoff/vehicle-candidate.json) (apk path, SHA-256, exact-source identity); it passes the off-car emulator pre-check but **Stage 11 on-car execution has not started**. No APK in this repository is a supported public release until that exact candidate passes Stage 11 on-car testing and owner sign-off.

ClusterNav is a personal hobby experiment by **Đăng Khôi · `dangkhoi`** for exploring navigation and cluster projection on BYD DiLink hardware. It is not affiliated with BYD and makes no driving-safety, compatibility, reversibility, or production-readiness claim.

## Target product baseline — exactly two tracks

1. **Navigation + HUD** — one authoritative navigation source/session with independent Cluster-lane and HUD outputs.
2. **Cluster Cast** — an independent durable state, journal, execution, recovery, UI and rollout pipeline.

The tracks may share one APK as packaging, but they must not share runtime control, mutable state, live transport, executor, journal, lifecycle or recovery. Home is a renderer/dispatcher, not an orchestrator.

**GPS Dead Reckon and mock-location are removed.** On 2026-07-27 the owner ended the experiment: it failed too often to keep, and a future attempt should start from a new approach rather than this source. The six files (1,096 lines) are deleted from the working tree; git history remains the only record, which is where rollback belongs. Do not select ClusterNav as the mock-location app — it can no longer act as one.

## Downloads and installation

**Current version: 0.99** — [`apk/ClusterNav-0.99-release.apk`](apk/ClusterNav-0.99-release.apk)

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
