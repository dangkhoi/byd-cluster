# ClusterNav

> [!CAUTION]
> **CURRENT STATUS: `1.18` (versionCode 118) — OTA self-test build.** The complete JVM suite is green off-car and the release APK builds cleanly and is signed, with all test/instrument-write surfaces (the T10 probe harness) confined to the `vehicleTest` build type and **absent from the release APK** (verified with `aapt2` on this build). From `1.11` the owner publishes each plain `apk/ClusterNav-<ver>-release.apk` to `main` so the app self-updates **over-the-air (OTA)** onto the car for testing — no ADB/laptop needed. **This is the owner's own iterative on-car test channel, not a supported public release, and is separate from the formal exact-source `collectAuthorizedApk` / Stage-11 candidate process (which remains its own distinct gate).** It is a hobby experiment with no driving-safety, compatibility, reversibility, or production-readiness claim — install at your own risk. The archived `1.04` vehicle-test candidate stays **blocklisted** in the on-car scripts (it exported the T10 `TEST_ADAS_*` / `TEST_SPEED_LIMIT` surface); the current release exports none.

ClusterNav is a personal hobby experiment by **Đăng Khôi · `dangkhoi`** for exploring navigation and cluster projection on BYD DiLink hardware. It is not affiliated with BYD and makes no driving-safety, compatibility, reversibility, or production-readiness claim.

## Target product baseline — exactly two tracks

1. **Navigation + HUD** — one authoritative navigation source/session with independent Cluster-lane and HUD outputs.
2. **Cluster Cast** — an independent durable state, journal, execution, recovery, UI and rollout pipeline.

The tracks may share one APK as packaging, but they must not share runtime control, mutable state, live transport, executor, journal, lifecycle or recovery. Home is a renderer/dispatcher, not an orchestrator.

**GPS Dead Reckon and mock-location are removed.** On 2026-07-27 the owner ended the experiment: it failed too often to keep, and a future attempt should start from a new approach rather than this source. The six files (1,096 lines) are deleted from the working tree; git history remains the only record, which is where rollback belongs. Do not select ClusterNav as the mock-location app — it can no longer act as one.

## Downloads and installation

**Current version: 1.18 (versionCode 118).** `1.18` adds a physical-button → Kiki mapping and a split-cast re-pin watchdog, plus two carried-in fixes:

- **Steering mic button (long-press = keycode 328) → Kiki** — the "Nút vật lý → Trợ lý" feature gains **Kiki (`ai.zalo.kiki.car`)** as a launch target and makes it the default (default keycode **328**, gesture **Press**); short-press still opens the car's own assistant (小迪). Like the earlier Gemini path this opens the Kiki app — whether it auto-listens is being confirmed on-car.
- **Split-cast re-pin watchdog** — when a cast app is pulled off the cluster (e.g. asking Kiki to navigate with Google Maps launches GMaps' nav on the main display, blanking its cluster slot), ClusterNav now re-casts it back to its slot from the 2 s bubble loop, **debounced + cooldown-guarded** so driving is never yanked on a transient read; CarPlay/Android Auto are skipped. Whether the relaunch preserves the active GMaps navigation (vs. showing the app home) is being confirmed on-car.
- **Accessibility booster self-grant on Nav+HUD** — a reboot clears `enabled_accessibility_services`; turning Nav+HUD on (or opening the app while it is on) now re-grants the screen-read booster over dadb when missing, so distance-tuning ground-truth is no longer silently lost.
- **Marquee-off road names abbreviate** — with the marquee toggle off, long road names are shortened via `NavFormat.fitRoadName` (e.g. "Trần Trọng Kim" → "T.T.Kim") instead of a hard firmware cut.

`1.17` fixes the physical-button → Gemini path found on-car:

- **"Google / Gemini" voice-key target opens Gemini directly** — it now launches the Gemini app (`com.google.android.apps.bard`, which brings up the in-car voice surface) instead of a generic `ACTION_ASSIST` intent that hit a chooser and opened Bluetooth on this head unit. Combined with a long-press-mic mapping (learn the button, gesture **Press** — the firmware emits a distinct code for the hold), the steering-wheel voice button can open Gemini while short-press still opens the car's own assistant. Enabling Gemini as the *system* assistant is a separate device setting; see `docs/diagnostics/gemini-assistant-voicekey-oncar-2026-08-13.md`.

`1.16` applies the first data-driven interp fix from on-car `1.15` logs:

- **Distance-to-turn now rounds like Google** — the cluster distance quantizer **rounds to the nearest step** instead of flooring. On-car data (n=3239 moving samples) showed flooring made the cluster read **~34 m less** than Google Maps (bias piled exactly on the floor buckets −10/−25/−100 m); rounding removes that downward half. The interpolation FACTOR is left unchanged pending the on-screen Google distance now being captured as ground-truth for the next tuning pass.

`1.15` adds two fixes from on-car `1.14` testing:

- **HUD keep-alive** — the windshield HUD / cluster centre ("Giữa + ETA") no longer blanks for ~1s on long straights with no turn. The HAL nav path now has a 400 ms heartbeat that re-asserts the last frame (bypassing dedup), so the OEM display never times out — matching the cluster-lane path which already had one.
- **Turn-distance comparison log** — the nav CSV now records the on-screen Google Maps distance (accessibility ground-truth) next to our interpolation, so the km→turn algorithm can be tuned from data (offline analyzer: `scripts/analyze-nav-distance-log.py`). No interpolation parameters changed yet.

`1.14` fixes five issues found testing 1.12 on the car:

- **HUD turn arrows no longer mirrored.** The windshield HUD reads the CAN turn-id table while the cluster lane uses the AMAP table; the app was sending the AMAP code to the HUD feature, flipping left↔right. It now sends `Maneuver.toHudIcon()` (CAN) to `INSTRUMENT_GUIDE_INFO_SIMPLE_SET`. (Cluster arrows were and stay correct; on-car re-confirms the centre view.)
- **Smooth marquee for long road names** — re-enabled (default on, with a toggle); the scroll offset is now time-based (even, slow) instead of the old uneven per-emission stepping that looked jerky.
- **Interpolated distance steps by 10 m** (was 5 m) to match Google Maps' granularity.
- **Cluster display-mode selector applies immediately** (re-asserts nav status 4→2 on change) and **OFF** now clears the centre-nav instead of writing an ineffective `screen=0`. (Exact value↔menu mapping is still being confirmed on-car.)
- **App auto-opens on car boot** (not just the floating button); the floating bubble starts only when Cluster Cast is enabled.

`1.13` (included) fixed notification-permission granting on the locked IVI and added an optional physical-button voice-assistant trigger:

- **In-app notification-access grant.** The head unit can't open Android's "Notification access" settings screen — a locked-IVI `startActivity` just shows the system toast *"Hệ thống IVI không hỗ trợ hoạt động này."* The listener permission is really an ADB permission (`settings secure enabled_notification_listeners`), so the app now grants it itself over the dadb uid-shell (`cmd notification allow_listener`), the same proven path used for reconnect. The system-settings screen remains only as a last-resort fallback.
- **Nav+HUD defaults OFF.** The master switch now starts **OFF**, so opening the app touches no ADB; the grant + connect run only when you turn Nav+HUD on (fewer concurrent dadb sessions). Once granted, the permission persists across reboots.
- **Physical button → voice assistant (optional, default OFF).** Map a hardware button + gesture (nhấn / nhấn-giữ) to launch a voice assistant (Google/Gemini · BYD 小迪 · speech recognizer). The existing accessibility service captures the key via `onKeyEvent` and **only** consumes the exact configured combo, so the button's native function is preserved; a "learn key" mode captures an unknown keycode on-car.

`1.12` earlier added the in-app **cluster nav-display mode selector** (Đơn giản / Toàn màn hình / Màn hình nhỏ / OFF) that drives the OEM nav-on-cluster setting (`SET_NAVI_SCREEN_STATUS_SET`, `0x4C10E015`) over the BYDAuto HAL, so navigation renders in the cluster **centre** ("Giữa + ETA") instead of only the small top strip — replacing the clusterDebug op39 path (a no-op for the centre view on this trim). The app self-updates **over-the-air**: it polls this repo's `apk/` folder on `main` for a newer `ClusterNav-<ver>-release.apk` and installs it via the on-device dadb loopback (`-r`, same signing key) — no ADB/laptop. To build the same release from source: `./gradlew :app:assembleRelease`. The formal exact-source vehicle candidate is a separate flow (the authorized `collectAuthorizedApk` pipeline; see the build context below).

> ⚠️ The archived `apk/ClusterNav-1.04-v104-527589f2d16a-release.apk` predates the WARN-1 hardening and still exports the T10 `TEST_ADAS_*` / `TEST_SPEED_LIMIT` ADAS/instrument-write surface — **do not install it** (its SHA-256 is now blocklisted in the on-car install guard, which refuses it). The current `1.18` release exports no test surface (verified with `aapt2` on this build).

Features:
- **Navigation + HUD** — one navigation source with independent cluster-lane and cluster-centre ("Giữa + ETA") outputs. Master switch **defaults OFF**; turning it on grants notification access in-app (over dadb) and connects.
- **In-app notification-access grant** — no laptop/ADB, no system-settings screen: the app self-grants the listener over the dadb uid-shell, with the settings screen only as a fallback.
- **Physical button → voice assistant** *(optional, default OFF)* — map a hardware button + gesture (press / long-press) to launch Google/Gemini, BYD 小迪, or a speech recognizer, without changing the button's native function.
- **Cluster Cast** — projection-first: open app → cluster ready instantly; tap floating button to cast foreground app to cluster; tap again to return.
- **CarPlay / Android Auto** — always full-screen, no resize.
- **Regular apps** — full or split, adjustable size.

> ⚠️ This is a hobby experiment. No driving-safety, compatibility, reversibility, or production-readiness claim. Install at your own risk. Not affiliated with BYD.

## Documentation

- [Two-track final plan](docs/specs/clusternav-two-track-final-plan.html) — derived orchestration and evidence gates.
- [Cluster Cast re-baseline](docs/specs/cluster-cast-rebaseline.html) — canonical Cast contracts.
- [Navigation/UX re-baseline](docs/specs/clusternav-uxui-rebaseline.html) — two-card target UX and Navigation contracts.
- [Dead Reckon revalidation](docs/specs/dead-reckon-revalidation.html) — REMOVE decision and deferred review debt.
- [User guide (Hướng dẫn sử dụng)](docs/HUONG-DAN.md) — current 1.13 usage: enable Nav+HUD, in-app notification grant, cluster display mode, physical-button voice trigger. VI + EN.
- [1.13 spec — notification-grant · docs refresh · voice-key](docs/specs/notif-grant-docs-voicekey-1.13.html) — this cycle's consolidated spec (requirements → design → tasks → verification).
- [Vehicle Test V2 checklist](docs/diagnostics/VEHICLE-TEST-V2.md) — prepared operator scripts and Stage 11 matrix; execution remains NOT STARTED.

Older files under `docs/review/`, `docs/diagnostics/`, `docs/reference/`, and previous specs describe historical builds or investigations. They are context only unless a current spec explicitly promotes an item into a new exact-source/exact-build gate.

## Developer build context

The Android project uses JDK 17 and Android SDK compileSdk/targetSdk 37, minSdk 29 (build-tools 36). The Cast subsystem uses a simplified projection-first architecture: 4-state model (IDLE → PROJECTING → CASTING → RETURNING), single floating button for cast/return, no complex state machines or recovery pipelines. Build with `./gradlew :app:assembleRelease`.

## Safety and evidence boundaries

- Physical power-button reboot is required when a test calls for a real head-unit reboot; `adb reboot` is not accepted as equivalent evidence.
- No merge to `main` before final exact-build on-car PASS and explicit merge authorization.
- No commit/push without the mandatory public-repository sensitive-data scan.
- Historical helper/unit results cannot close current V2, UX, release or vehicle gates.

## Credits

See [CREDITS.md](CREDITS.md). The project uses [`dadb`](https://github.com/mobile-dev-inc/dadb) under Apache-2.0.

## License

[MIT](LICENSE).
