# HANDOFF — ClusterNav **1.30** closeout (FINAL) · 2026-08-16→17

> Bản ghi bàn giao cho phiên đóng dự án. Trạng thái: **ĐÃ SHIP + PUSH** lên `main` (repo `dangkhoi/byd-cluster`). Dự án đóng.
> Nguyên tắc dự án: no-assumptions — mỗi claim trace được về test/readback/lệnh đã chạy.

## TL;DR
ClusterNav khép ở **1.30 (versionCode 130)** — đây là bản closeout `1.28` được **đổi số lên 1.30 làm final** (lý do đổi số: xem §Version). Gồm **6 fix on-car** + **1 đợt hardening** (perf/log/docs/prod-readiness). Toàn bộ **4 suite build xanh (1648 test)**, release APK ký, không có test surface. Đã **commit (author `dangkhoi`) + force-push** + **rewrite history** (redact rò rỉ cũ). Còn lại thuần **on-car** (không chặn).

## 1. Sáu fix (nguồn: phiên on-car 2026-08-16 · HANDOFF A/B đã archive)
- **TASK 1 — Vòng xuyến hiện HƯỚNG RA + SỐ LỐI RA.** Thêm 8 member `Maneuver` CÓ HƯỚNG (encode-only): `ROUNDABOUT_LEFT`→CAN 15, `_RIGHT`→18, `_STRAIGHT`→20, `_UTURN`→22, + CW 16/17/19/21; `toAmapIcon`=11 (cụm-strip generic), KHÔNG vào `fromAmapIcon`. `ManeuverSignature.classifyManeuver`/`nameToManeuver` (bám OpenBYD `w40`/`HudController`). Wiring: `NavNotificationListener.handle` set `NavState.maneuver = classifyManeuver(arrow) ?: fromAmapIcon(classifiedIcon)`; ưu tiên số lối ra `24+N` giữ nguyên. `[core Maneuver.kt · RoundaboutManeuverWiringTest]`
- **TASK 2 — keep-alive (option B).** `HudKeepAlivePolicy.DEFAULT_INTERVAL_MS` 400→**250ms** (giữ maxAge 180s); keep-alive re-assert **content-only** (bỏ status/screen-mode/SDK mỗi tick) qua `writeNavFrame(keepAlive=true)`. Nháy còn lại = **OEM render-layer** (đã loại 4/5 nguyên nhân bằng đọc code; không đo được từ app).
- **TASK 3 — voice-key sau reboot.** `NavConnect.grantAccessibility(reset=Boolean)` reset single-flight + fresh grant + force-rebind; `doGrantAccessibilityWithTimeout` (join 9s → interrupt + nhả cờ). `MainActivity` OFF→ON gọi `reset=true`.
- **TASK 4 — selector cụm → ON/OFF.** Bỏ 3 mode layout chết. **ON = `NAV_SCREEN_FULL`(3, PROVEN rc=0)** — KHÔNG dùng SIMPLE(1, đoán). Prefs default + migration non-OFF→FULL.
- **TASK 5 — rejection cache.** `BydHal` cache per-feature (sentinel **-2147482648**, KHÔNG phải Int.MIN_VALUE) → hết spam `no permission 1007`; xe provision oversea (Sealion 6, rc=0) vẫn ghi. KHÔNG hard-remove oversea.
- **TASK 6 — naviState boot.** VERIFIED NO-GAP: `emitLane` (broadcast naviState=1) synchronous, happens-before HAL write mỗi frame. Không cần prime.

## 2. Đợt hardening (cùng bản)
- **Perf:** `BydHal` cache reflection (featureId/EventValue ctor+fields/set() Method/SDK handles — trước ~20+15×/frame).
- **Log/stability:** `NavArrowLog`/`NavDistanceLog` gate sau `Prefs.navVerboseLog` (mặc định TẮT) + chạy off-main; 3 log per-frame → **log-on-change**; W/E + state-change giữ. Toggle ẩn: long-press nhãn version.
- **Prod-readiness:** disclaimer first-launch (no-warranty/không liên kết BYD); CREDITS + okio/kotlin-stdlib/Bouncy Castle.
- **Docs:** README/HUONG-DAN/CLOSEOUT song ngữ VI+EN 1.30; historical → `docs/archive/` (96 _handoff + 12 review + 51 diagnostics); `apk/` prune còn `ClusterNav-1.30-release.apk`. Chi tiết: `docs/CLOSEOUT-2026-08-16.md` + spec `docs/specs/clusternav-closeout-1.28.html` (slug giữ -1.28; nội dung/version = 1.30).

## 3. Version — vì sao 1.28 → **1.30** (không phải "1.3")
OTA `UpdateChecker.cmp()` so **theo thành phần số**: `"1.3"`→[1,3] < `"1.28"`→[1,28] (3<28) ⇒ **"1.3" bị coi là CŨ HƠN 1.28 → xe không tự cập nhật**. Dùng **`1.30`** → [1,30] > [1,28] ⇒ OTA nhận đúng bản mới. Quyết định của cmp chạy trên bản ĐANG CÀI (không cứu được bằng sửa code 1.3).

## 4. Bằng chứng verify (orchestrator tự chạy)
- `./gradlew :core:test :app:testDebugUnitTest :car-integration:test :offcar-planner:test` → **xanh**, senior-review rerun-tasks: **1648 test, 0 fail** (core 768 · app 753 · car-integration 28 · offcar-planner 99).
- `assembleRelease` ký; `aapt2`: `versionCode=130 versionName=1.30`, **test-surface-hits=0**; apksigner **Verifies** (CN=ClusterNav).
- Senior review (opus): **APPROVED 0 P0/P1/P2**. Security scan: **CLEAN** (sau redact).

## 5. Git / push / history-rewrite
- Commit final **`6b1e736`** `release(1.30): project closeout — final`, author+committer = `Đăng Khôi <dangkhoi@users.noreply.github.com>` (KHÔNG dùng email công ty). 228 file, apk prune (1.26→1.30 rename, 19 apk cũ xoá khỏi tracking; `apk/_archive-old-builds/` gitignore).
- **filter-repo rewrite 105 commit**: redact `<vehicle-ip>`/`<vehicle-ip>`/`<redacted-email>` khỏi content lịch sử (đếm sau = 0/0/0). Author/committer **vốn đã** là dangkhoi (email công ty chỉ ở `.git/logs` local, không push). Force-push `--force-with-lease` → remote main = local.
- **Backup trước rewrite:** `~/clusternav-pre-rewrite-20260816T215752.bundle` (44M). Khôi phục: `git clone <bundle>`. Giữ tới khi chắc.
- ⚠️ History đã rewrite → clone/máy khác phải clone lại.

## 6. Giới hạn đã biết (từ CLOSEOUT — trung thực)
- **HUD kính không lên nav** trên xe owner = cờ coding xe `0x38B00030` chưa provisioned (đọc `-2147482648`) — KHÔNG phải bug app; cần coding tool BYD (OBD/UDS). So sánh Sealion 6 (HUD chạy với domestic-only) ⇒ firmware auto-mirror khi cờ bật.
- **Layout 4-mode cụm** không đổi live không root (no-root wall) — chỉ ON/OFF.
- **Nháy HUD/centre** còn = OEM render-layer (ngoài tầm app; app đã re-assert 250ms + backstop 180s).
- **v2 Cast** (~28 core-main + 46 test + 10 app importer) orphaned, để lại (không xoá lúc đóng) — future cleanup.
- `com.byd.*` namespace = trademark, không rename (phá OTA `-r`). GPS dead-reckon đã gỡ (2026-07-27).

## 7. Còn lại — thuần ON-CAR (không chặn đóng dự án)
- [ ] Xác nhận hướng vòng xuyến khi lái (GMaps VN CCW).
- [ ] TASK 2 còn nháy không — 1 câu yes/no trực quan (sau interval 250 + dọn churn). Nếu còn → OEM-internal.
- [ ] `getraw instr 38B00030` xe **Sealion 6** để chốt giả thuyết HUD 100% (kỳ vọng `=1`). Kit: `scripts/vehicle/hud-provisioning-compare.sh` + `apks/navopen-v4.jar`.

## Nguồn
- `docs/CLOSEOUT-2026-08-16.md` (đánh giá đóng dự án, song ngữ) · `docs/specs/clusternav-closeout-1.28.html` (spec) · `docs/HUONG-DAN.md` · README.
- HANDOFF gốc phiên on-car: `docs/_handoff/next-session-A-coding-2026-08-16.md` (coding) + `next-session-B-research-hud-2026-08-16.md` (HUD research).
- Tiếp nối: repo mới **byd-cluster-2** (revive Waze/VietMap) — xem handoff repo-split.
