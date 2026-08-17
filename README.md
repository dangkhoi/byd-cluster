# ClusterNav

> Song ngữ: các mục hướng đến người dùng viết tiếng Việt trước, English sau. Changelog theo phiên bản giữ nguyên tiếng Anh, có một dòng dẫn tiếng Việt.
> Bilingual: user-facing sections are Vietnamese first, then English. Per-version changelog entries stay in English, with a one-line Vietnamese intro.

> [!CAUTION]
> **(VI) TRẠNG THÁI HIỆN TẠI: `1.30` (versionCode 130) — bản tự-cập-nhật OTA để thử nghiệm (đóng dự án).** Các bộ test core/app/car-integration + offcar-planner build **xanh off-car** và release APK build sạch, đã ký; mọi bề mặt test/ghi-thiết-bị (bộ dò T10) chỉ nằm trong build type `vehicleTest` — release APK **không có bề mặt test nào được export/tiếp cận được** (xác minh bằng `aapt2` trên bản build này). Từ `1.11`, chủ sở hữu đăng mỗi `apk/ClusterNav-<ver>-release.apk` lên `main` để app **tự cập nhật qua mạng (OTA)** xuống xe để thử — không cần ADB/laptop. **Đây là kênh thử nghiệm trên xe của riêng chủ sở hữu, KHÔNG phải bản phát hành công khai được hỗ trợ, và tách biệt với quy trình ứng viên exact-source `collectAuthorizedApk` / Stage-11 (vốn là một cổng riêng).** Đây là một thử nghiệm sở thích, không cam kết an toàn lái xe, tương thích, khả năng hoàn tác hay mức độ sẵn sàng sản xuất — cài đặt tự chịu rủi ro. Ứng viên vehicle-test `1.04` vẫn bị **blocklist theo SHA-256** trong on-car guard (nó từng export bề mặt T10 `TEST_ADAS_*` / `TEST_SPEED_LIMIT`) và **không còn được giữ trong `apk/`**; bản release hiện tại không export bề mặt nào như vậy.
>
> **(EN) CURRENT STATUS: `1.30` (versionCode 130) — OTA self-test build (project closeout).** The core/app/car-integration + offcar-planner suites **build green off-car** and the release APK builds cleanly and is signed, with all test/instrument-write surfaces (the T10 probe harness) confined to the `vehicleTest` build type — the release APK has **no exported/reachable test surface** (verified with `aapt2` on this build). From `1.11` the owner publishes each plain `apk/ClusterNav-<ver>-release.apk` to `main` so the app self-updates **over-the-air (OTA)** onto the car for testing — no ADB/laptop needed. **This is the owner's own iterative on-car test channel, not a supported public release, and is separate from the formal exact-source `collectAuthorizedApk` / Stage-11 candidate process (which remains its own distinct gate).** It is a hobby experiment with no driving-safety, compatibility, reversibility, or production-readiness claim — install at your own risk. The `1.04` vehicle-test candidate stays **blocklisted by SHA-256** in the on-car guard (it exported the T10 `TEST_ADAS_*` / `TEST_SPEED_LIMIT` surface) and is **no longer kept in `apk/`**; the current release exports no such surface.

**(VI)** ClusterNav là một thử nghiệm cá nhân mang tính sở thích của **Đăng Khôi · `dangkhoi`**, để khám phá dẫn đường và chiếu màn hình lên cụm đồng hồ trên phần cứng BYD DiLink. Dự án không liên kết với BYD và không đưa ra cam kết nào về an toàn lái xe, tương thích, khả năng hoàn tác hay mức độ sẵn sàng sản xuất.

**(EN)** ClusterNav is a personal hobby experiment by **Đăng Khôi · `dangkhoi`** for exploring navigation and cluster projection on BYD DiLink hardware. It is not affiliated with BYD and makes no driving-safety, compatibility, reversibility, or production-readiness claim.

## Target product baseline — exactly two tracks · Mục tiêu sản phẩm — đúng hai nhánh

**(VI)** Sản phẩm chốt ở đúng hai nhánh:

1. **Navigation + HUD** — một nguồn/phiên dẫn đường có thẩm quyền duy nhất, với đầu ra Cluster-lane và HUD độc lập.
2. **Cluster Cast** — trạng thái bền, nhật ký (journal), thực thi, khôi phục, UI và pipeline rollout độc lập.

Hai nhánh có thể dùng chung một APK để đóng gói, nhưng không được dùng chung điều khiển runtime, trạng thái thay đổi được, transport live, executor, journal, vòng đời hay khôi phục. Home là bộ render/dispatcher, không phải orchestrator.

**(EN)** The product settles on exactly two tracks:

1. **Navigation + HUD** — one authoritative navigation source/session with independent Cluster-lane and HUD outputs.
2. **Cluster Cast** — an independent durable state, journal, execution, recovery, UI and rollout pipeline.

The tracks may share one APK as packaging, but they must not share runtime control, mutable state, live transport, executor, journal, lifecycle or recovery. Home is a renderer/dispatcher, not an orchestrator.

**(VI) GPS Dead Reckon và mock-location đã bị gỡ bỏ.** Ngày 2026-07-27 chủ sở hữu kết thúc thử nghiệm này: nó hỏng quá thường xuyên nên không giữ, và một lần thử trong tương lai nên bắt đầu từ một cách tiếp cận mới thay vì nguồn này. Sáu file (1.096 dòng) đã bị xoá khỏi working tree; lịch sử git là bản ghi duy nhất còn lại, và đó cũng là nơi để rollback. Đừng chọn ClusterNav làm app mock-location — nó không còn đóng vai trò đó được nữa.

**(EN) GPS Dead Reckon and mock-location are removed.** On 2026-07-27 the owner ended the experiment: it failed too often to keep, and a future attempt should start from a new approach rather than this source. The six files (1,096 lines) are deleted from the working tree; git history remains the only record, which is where rollback belongs. Do not select ClusterNav as the mock-location app — it can no longer act as one.

## Downloads and installation · Tải về và cài đặt

**(VI)** Cài đặt qua **OTA**: khi Nav+HUD được bật, app tự dò thư mục `apk/` của repo này trên `main`, và nếu có `ClusterNav-<ver>-release.apk` mới hơn thì tự cài qua dadb loopback trên xe (`-r`, cùng khoá ký) — không cần ADB/laptop. Để build cùng bản release từ nguồn: `./gradlew :app:assembleRelease`.

**(EN)** Install via **OTA**: with Nav+HUD enabled, the app polls this repo's `apk/` folder on `main` and, if a newer `ClusterNav-<ver>-release.apk` exists, installs it over the on-device dadb loopback (`-r`, same signing key) — no ADB/laptop. To build the same release from source: `./gradlew :app:assembleRelease`.

**(VI)** Changelog theo phiên bản dưới đây giữ nguyên tiếng Anh (mô tả kỹ thuật từng bản sửa).

**Bản kế đã CHUẨN BỊ: `1.31` (versionCode 131) — CHƯA release OTA · Prepared next: `1.31` (versionCode 131) — NOT yet OTA-released.** **(VI)** Xe vẫn chạy `1.30`; `apk/` trên `main` vẫn là `ClusterNav-1.30-release.apk` (chưa đẩy apk 1.31 → OTA không mời cập nhật). Mã nguồn + versionCode đã ở 1.31; gôm nhiều issue rồi release một lần (tránh release lắt nhắt). **(EN)** Cars still run `1.30`; `apk/` on `main` is still `ClusterNav-1.30-release.apk` (no 1.31 apk pushed → OTA offers nothing). Source + versionCode are at 1.31; batching issues for one future release. Two fixes done off-car (suites green):

- **OTA no longer re-installs the same version (issue #A).** `UpdateChecker` now gates the *install* on the APK's real `versionCode` (read via `getPackageArchiveInfo`) vs the installed `versionCode` — not the filename version string — and `check()` fails **closed** when the installed version can't be read (`versionName="?"`). Previously a version-string mismatch (bad build, or `getPackageInfo` returning `"?"`) made "check for update" keep offering + `pm install -r`-ing the same build forever. New unit tests lock the offer/install decision.
- **Turn-arrow pixel fallback no longer reads a normal left/right turn as "straight" (issue #B).** `ArrowClassifier` (the last-ditch fallback, used only when the perceptual signature misses) decided direction from the whole-arrow centre-of-mass, which the arrow's vertical approach-shaft cancels out → normal turns collapsed to "straight". It now decides from the **arrowhead**. Locked by `RealGmapsArrowCorpusTest` (real GMaps arrows captured on an emulator). NOTE: the signature layer already classified these correctly, so this is **defensive hardening** — it is NOT confirmed to fix the on-car "left shows straight" report (issue #C), which still needs on-car capture.

**Latest released (OTA): 1.30 (versionCode 130).** `1.30` is the project-closeout build — six fixes from the 2026-08-16 on-car session, after which the docs were reorganized and the experiment was wrapped up. **(VI)** `1.30` là bản đóng dự án `clusternav-closeout-1.28` được nâng lên bản cuối (đổi số hiệu, giữ nguyên slug/link). **(EN)** `1.30` is that closeout build promoted to final (renumbered; slug/links kept). The six fixes:

- **Roundabout shows the exit direction + exit number** — the cluster/HUD now shows a roundabout's **exit direction** (left / right / straight / u-turn — CCW by default, CW for left-hand-traffic) and **exit number**, instead of a generic "enter roundabout". Uses directional `Maneuver` members; the CAN turn-id map was cross-validated against OpenBYD `w40` / `HudController` and checked on-car.
- **Less HUD/centre keep-alive churn + faster re-assert (400→250 ms)** — the keep-alive now re-asserts **content only** (icon / distance / road), not status / screen-mode / SDK every tick, and re-asserts faster (**400→250 ms**, the 180 s max-age backstop kept); any residual flicker is the OEM render-layer.
- **Voice-key recovers with an OFF→ON toggle** — if the physical-button → assistant ("Nút vật lý → Trợ lý") stops working after a reboot, flipping it **OFF then ON** resets the accessibility grant and force-rebinds (with a grant timeout so it can't hang) — no app restart.
- **Cluster display-mode selector is now just ON/OFF** — reduced to **"Bật (Giữa + ETA) / Tắt"**; the three dead layout modes (Toàn / Nhỏ / OFF-only) were removed since they can't switch live without root.
- **No more oversea-feature log spam** — non-provisioned oversea features are cached after their first runtime rejection so they stop spamming per-frame logs; cars that do provision oversea (e.g. Sealion 6) still write normally.
- **Boot naviState ordering verified** — confirmed the broadcast `naviState=1` happens-before the HAL write on every frame — no gap, no change needed.

See the [project closeout (1.30)](docs/CLOSEOUT-2026-08-16.md) for the final evaluation and known limitations (notably: the windshield HUD needs a **vehicle** coding flag `0x38B00030=1`, not an app change).

`1.18` adds a physical-button → Kiki mapping and a split-cast re-pin watchdog, plus two carried-in fixes:

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

> ⚠️ **(VI)** Ứng viên vehicle-test `1.04` (`ClusterNav-1.04-v104-527589f2d16a-release.apk`) có trước đợt hardening WARN-1 và từng export bề mặt ADAS/ghi-thiết-bị T10 `TEST_ADAS_*` / `TEST_SPEED_LIMIT` — nó bị **blocklist theo SHA-256 trong on-car install guard** (guard sẽ từ chối) và **không còn được giữ trong `apk/`** (các bản cũ đã cất đi; lịch sử git là bản ghi). **Đừng cài nó.** Bản release `1.30` hiện tại không có bề mặt test nào được export/tiếp cận được (xác minh bằng `aapt2` trên bản build này).
>
> ⚠️ **(EN)** The `1.04` vehicle-test candidate (`ClusterNav-1.04-v104-527589f2d16a-release.apk`) predates the WARN-1 hardening and exported the T10 `TEST_ADAS_*` / `TEST_SPEED_LIMIT` ADAS/instrument-write surface — it is **blocklisted by SHA-256 in the on-car install guard** (which refuses it) and is **no longer kept in `apk/`** (older builds are shelved; git history remains the record). **Do not install it.** The current `1.30` release has no exported/reachable test surface (verified with `aapt2` on this build).

## Features · Tính năng

**(VI)**
- **Navigation + HUD** — một nguồn dẫn đường với đầu ra cluster-lane và cluster-centre ("Giữa + ETA") độc lập. Master switch **mặc định TẮT**; bật lên sẽ cấp quyền notification access trong app (qua dadb) và kết nối.
- **Cấp quyền notification access trong app** — không cần laptop/ADB, không cần màn hình system-settings: app tự cấp listener qua dadb uid-shell, màn hình cài đặt chỉ là phương án dự phòng.
- **Nút vật lý → trợ lý giọng nói** *(tuỳ chọn, mặc định TẮT)* — gán một nút cứng + cử chỉ (nhấn / nhấn-giữ) để mở Google/Gemini, BYD 小迪, hoặc speech recognizer, mà không đổi chức năng gốc của nút.
- **Cluster Cast** — projection-first: mở app → cụm sẵn sàng ngay; chạm nút nổi để chiếu app đang mở lên cụm; chạm lại để trả về.
- **CarPlay / Android Auto** — luôn full-screen, không resize.
- **App thường** — full hoặc split, chỉnh được kích thước.

**(EN)**
- **Navigation + HUD** — one navigation source with independent cluster-lane and cluster-centre ("Giữa + ETA") outputs. Master switch **defaults OFF**; turning it on grants notification access in-app (over dadb) and connects.
- **In-app notification-access grant** — no laptop/ADB, no system-settings screen: the app self-grants the listener over the dadb uid-shell, with the settings screen only as a fallback.
- **Physical button → voice assistant** *(optional, default OFF)* — map a hardware button + gesture (press / long-press) to launch Google/Gemini, BYD 小迪, or a speech recognizer, without changing the button's native function.
- **Cluster Cast** — projection-first: open app → cluster ready instantly; tap floating button to cast foreground app to cluster; tap again to return.
- **CarPlay / Android Auto** — always full-screen, no resize.
- **Regular apps** — full or split, adjustable size.

> ⚠️ **(VI)** Đây là một thử nghiệm sở thích. Không cam kết an toàn lái xe, tương thích, khả năng hoàn tác hay sẵn sàng sản xuất. Cài đặt tự chịu rủi ro. Không liên kết với BYD.
>
> ⚠️ **(EN)** This is a hobby experiment. No driving-safety, compatibility, reversibility, or production-readiness claim. Install at your own risk. Not affiliated with BYD.

## Documentation · Tài liệu

**(VI)** Bộ tài liệu canonical (song ngữ khi hướng đến người dùng):

**(EN)** The canonical documentation set (bilingual where user-facing):

- [Project closeout (1.30)](docs/CLOSEOUT-2026-08-16.md) — final evaluation, the six 1.30 fixes, and honest known limitations (VI + EN).
- [Two-track final plan](docs/specs/clusternav-two-track-final-plan.html) — derived orchestration and evidence gates.
- [Cluster Cast re-baseline](docs/specs/cluster-cast-rebaseline.html) — canonical Cast contracts.
- [Navigation/UX re-baseline](docs/specs/clusternav-uxui-rebaseline.html) — two-card target UX and Navigation contracts.
- [Dead Reckon revalidation](docs/specs/dead-reckon-revalidation.html) — REMOVE decision and deferred review debt.
- [User guide (Hướng dẫn sử dụng)](docs/HUONG-DAN.md) — current 1.30 usage: enable Nav+HUD, in-app notification grant, cluster display mode (ON/OFF), roundabout exit direction, physical-button voice trigger (OFF→ON recovery). VI + EN.
- [1.13 spec — notification-grant · docs refresh · voice-key](docs/specs/notif-grant-docs-voicekey-1.13.html) — this cycle's consolidated spec (requirements → design → tasks → verification).
- [Vehicle Test V2 checklist](docs/diagnostics/VEHICLE-TEST-V2.md) — prepared operator scripts and Stage 11 matrix; execution remains NOT STARTED.

**(VI)** Các handoff phiên làm việc và review lịch sử nay nằm trong `docs/archive/` (lịch sử git được giữ nguyên). Các file cũ hơn trong `docs/diagnostics/`, `docs/reference/`, và các spec trước đây mô tả các bản build hoặc điều tra lịch sử — chỉ là ngữ cảnh, trừ khi một spec hiện hành promote một mục thành cổng exact-source/exact-build mới.

**(EN)** Historical session handoffs and reviews now live under `docs/archive/` (git history preserved). Older files under `docs/diagnostics/`, `docs/reference/`, and previous specs describe historical builds or investigations. They are context only unless a current spec explicitly promotes an item into a new exact-source/exact-build gate.

## Developer build context · Ngữ cảnh build cho lập trình viên

**(VI)** Dự án Android dùng JDK 17 và Android SDK compileSdk/targetSdk 37, minSdk 29 (build-tools 36). Hệ Cast dùng kiến trúc projection-first đơn giản hoá: mô hình 4 trạng thái (IDLE → PROJECTING → CASTING → RETURNING), một nút nổi duy nhất để cast/return, không có state machine hay pipeline khôi phục phức tạp. Build bằng `./gradlew :app:assembleRelease`.

**(EN)** The Android project uses JDK 17 and Android SDK compileSdk/targetSdk 37, minSdk 29 (build-tools 36). The Cast subsystem uses a simplified projection-first architecture: 4-state model (IDLE → PROJECTING → CASTING → RETURNING), single floating button for cast/return, no complex state machines or recovery pipelines. Build with `./gradlew :app:assembleRelease`.

## Safety and evidence boundaries · Ranh giới an toàn và bằng chứng

**(VI)**
- Bắt buộc reboot bằng nút nguồn vật lý khi một bài test yêu cầu reboot head-unit thật; `adb reboot` không được chấp nhận là bằng chứng tương đương.
- Không merge vào `main` trước khi có PASS exact-build trên xe và uỷ quyền merge rõ ràng.
- Không commit/push khi chưa chạy quét dữ liệu nhạy cảm bắt buộc cho public-repository.
- Kết quả helper/unit lịch sử không thể đóng các cổng V2, UX, release hay vehicle hiện hành.

**(EN)**
- Physical power-button reboot is required when a test calls for a real head-unit reboot; `adb reboot` is not accepted as equivalent evidence.
- No merge to `main` before final exact-build on-car PASS and explicit merge authorization.
- No commit/push without the mandatory public-repository sensitive-data scan.
- Historical helper/unit results cannot close current V2, UX, release or vehicle gates.

## Credits · Ghi công

**(VI)** Xem [CREDITS.md](CREDITS.md). Dự án dùng [`dadb`](https://github.com/mobile-dev-inc/dadb) theo giấy phép Apache-2.0.

**(EN)** See [CREDITS.md](CREDITS.md). The project uses [`dadb`](https://github.com/mobile-dev-inc/dadb) under Apache-2.0.

## License · Giấy phép

[MIT](LICENSE).
