# ClusterNav

> [!CAUTION]
> **CURRENT STATUS: OFF-CAR CANDIDATE `0.72` — FIELD-EXECUTION CORRECTION.** The 2026-07-25 vehicle run of the previous source failed on every case; the cluster placement recipe, bootstrap preconditions and verification constants have been corrected against the field-proven V1 behaviour (canonical D10) and the complete JVM suite is green off-car. The authorized vehicle-test candidate now exists and is recorded in [`docs/_handoff/vehicle-candidate.json`](docs/_handoff/vehicle-candidate.json) (apk path, SHA-256, exact-source identity); it passes the off-car emulator pre-check but **Stage 11 on-car execution has not started**. No APK in this repository is a supported public release until that exact candidate passes Stage 11 on-car testing and owner sign-off.

ClusterNav is a personal hobby experiment by **Đăng Khôi · `dangkhoi`** for exploring navigation and cluster projection on BYD DiLink hardware. It is not affiliated with BYD and makes no driving-safety, compatibility, reversibility, or production-readiness claim.

## Target product baseline — exactly two tracks

1. **Navigation + HUD** — one authoritative navigation source/session with independent Cluster-lane and HUD outputs.
2. **Cluster Cast** — an independent durable state, journal, execution, recovery, UI and rollout pipeline.

The tracks may share one APK as packaging, but they must not share runtime control, mutable state, live transport, executor, journal, lifecycle or recovery. Home is a renderer/dispatcher, not an orchestrator.

**GPS Dead Reckon and mock-location are quiesced from the active product baseline.** Their permissions, manifest service, module registration, boot/update paths, preference and Home controls are removed. Historical source remains rollback-readable but unreachable from active product wiring; do not select ClusterNav as the mock-location app.

## Downloads and installation

Public installation guidance is intentionally withdrawn during the re-baseline:

- Existing APKs under `apk/` are immutable **historical/unsupported evidence artifacts**.
- Their filenames do not prove source provenance, enabled flags, vehicle compatibility or on-car validation.
- Do not install or relabel them as a current release.
- A future release link may be published only with exact source identity, unique APK SHA-256/signature/version/flags, off-car evidence and exact-build on-car PASS.

See [`docs/HISTORICAL-ARTIFACTS.md`](docs/HISTORICAL-ARTIFACTS.md) for the quarantine inventory.

## Documentation

- [Two-track final plan](docs/specs/clusternav-two-track-final-plan.html) — derived orchestration and evidence gates.
- [Cluster Cast re-baseline](docs/specs/cluster-cast-rebaseline.html) — canonical Cast contracts.
- [Navigation/UX re-baseline](docs/specs/clusternav-uxui-rebaseline.html) — two-card target UX and Navigation contracts.
- [Dead Reckon revalidation](docs/specs/dead-reckon-revalidation.html) — REMOVE decision and deferred review debt.
- [Historical UI guide](docs/HUONG-DAN.md) — quarantined screenshot/archive reference, not installation guidance.
- [Vehicle Test V2 checklist](docs/diagnostics/VEHICLE-TEST-V2.md) — prepared operator scripts and Stage 11 matrix; execution remains NOT STARTED.

Older files under `docs/review/`, `docs/diagnostics/`, `docs/reference/`, and previous specs describe historical builds or investigations. They are context only unless a current spec explicitly promotes an item into a new exact-source/exact-build gate.

## Developer build context

The Android project historically uses JDK 17 and Android SDK platform/build-tools 34. A local build is not release evidence and must not overwrite any existing APK. Under the current execution plan, the project owner requires separate build authorization naming a unique output path and exact-source identity before an agent builds an APK.

## Safety and evidence boundaries

- Physical power-button reboot is required when a test calls for a real head-unit reboot; `adb reboot` is not accepted as equivalent evidence.
- No merge to `main` before final exact-build on-car PASS and explicit merge authorization.
- No commit/push without the mandatory public-repository sensitive-data scan.
- Historical helper/unit results cannot close current V2, UX, release or vehicle gates.

## Credits

See [CREDITS.md](CREDITS.md). The project uses [`dadb`](https://github.com/mobile-dev-inc/dadb) under Apache-2.0.

## License

[MIT](LICENSE).
