# HANDOFF — phiên 2026-08-17 · chuẩn bị 1.31 + điều tra classify + RE OpenBYD + v2 spec

> Trạng thái: **ĐÃ commit + push `main`** (`c424059`, author `dangkhoi`). **1.31 CHUẨN BỊ, CHƯA release OTA**
> (apk/ vẫn `ClusterNav-1.30-release.apk` → OTA không mời; xe vẫn chạy 1.30). No-assumptions: mỗi claim trace
> `[source:file]`/`[RE:path]`/`[emulator]`/`[test]`.

## TL;DR
Bắt đầu từ owner báo on-car "GMaps quẹo trái mà strip cụm đi thẳng". Điều tra → **dựng capture GMaps thật trên
emulator** (đồ nghề `scripts/emulator/`) → tìm + fix **2 bug off-car** (ArrowClassifier fallback #B, OTA cài-lại-
cùng-version #A) + **corpus test arrow thật** + **doc mapping GMaps→cụm (ảnh thật)** + **RE OpenBYD** (cách lấy
số-lối-ra qua accessibility) → **spec v2** + **rule quản-lý-issue**. Full suite xanh (core 770 · app 381 ·
car-integration 28 · offcar-planner 99, 0 fail). Đã push; **chưa release** (gôm issue rồi release 1 lần).

## 1. Điều tra bug "trái→thẳng" (#C) — kiến trúc + 4 lớp classify
- **3 mặt cùng 1 nguồn `Maneuver` trung lập** `[nav-output-architecture-2026-08-16.html]`: STRIP=broadcast AUTONAVI `toAmapIcon`; HUD+CENTRE(Giữa+ETA)=INSTRUMENT HAL `toHudIcon`. Sửa ở `Maneuver` → cả 3 cùng đúng/sai. Owner đính chính: phiên on-car 2026-08-16 test **centre**, KHÔNG phải strip → không có bằng chứng strip.
- Listener classify 4 lớp `[NavNotificationListener.handle]`: small-icon → **ManeuverSignature (chữ ký ảnh)** → verb-text → **ArrowClassifier (pixel COM)**. Rồi `maneuver = classifyManeuver(arrow) ?: fromAmapIcon(classifiedIcon)`.

## 2. Emulator capture (đồ nghề mới, `scripts/emulator/`)
AVD `clusternav` (android-34 google_apis arm64) + JDK17 Homebrew. Cài **debug APK** (run-as set prefs enabled+verbose) → grant listener SHORT-FORM `com.byd.clusternav/.NavNotificationListener` (full-form fail âm thầm) → lái GPS mock `adb emu geo fix` khắp HCM → `NavArrowLog` ghi CSV + PNG. Scripts: `drive-corpus.sh` (routes stdin, đã fix `</dev/null` cho adb khỏi nuốt stdin), `capture-gmaps-noti.sh` (dumpsys text), `analyze-arrow-png.py` (PNG decode + ASCII + COM), `arrow-silhouette.py`, `build-mapping-doc.py`.

**Dữ liệu thật GMaps notification** `[emulator dumpsys]`: `title`=cự ly, `subText`=ETA, `text`=**CHỈ tên đường** (không verb, không "Nth exit"), `largeIcon`=bitmap 72×72, `contentView=null`. ⇒ số-lối-ra KHÔNG có trong notification (chỉ trong ảnh mũi tên = HƯỚNG, không SỐ).

**Bắt sống 14 loại arrow** (lưu `core/src/test/resources/arrows/`): depart, straight, normal L/R, slight L/R, sharp L/R, u_turn_left, fork_right, merge, roundabout ccw straight/slight-left/exit.

## 3. Fix #B — ArrowClassifier fallback (đã fix, off-car)
**Root cause thấy tận mắt** `[emulator ASCII]`: mũi tên rẽ GMaps có **thân dọc** → trọng-tâm-TOÀN-ẢNH ≈ giữa (normal-left comOff≈+0.027 triệt tiêu headOff≈−0.075) → blend cũ `0.65*head+0.35*com` = ±0.039 < ngưỡng → **đọc "đi thẳng"**. Fix `[core ArrowClassifier.kt]`: `off = headOff` (đầu mũi tên quyết định). Khoá bằng **`RealGmapsArrowCorpusTest`** (14 arrow thật): ManeuverSignature đọc ĐÚNG cả 14; ArrowClassifier đúng hướng sau fix.
> ⚠ **Defensive**: trên emulator signature (lớp chính) vốn đọc đúng cua → fix chỉ ăn KHI signature trượt. **CHƯA confirm** đây là nguyên nhân #C on-car. #B giữ Open tới khi #C confirm.

## 4. Fix #A — OTA cài lại cùng version vô hạn (đã fix, off-car)
**Root cause** `[app UpdateChecker.kt]`: `hasUpdate = cmp(bestVer[TÊN FILE], cur[versionName cài]) > 0`; nếu versionName cài đọc thấp hơn tên file (build sớm sai / `getPackageInfo`→"?" → `cmp("1.30","?")>0` luôn) → mời mãi, và `pm install -r` cùng version VẪN success → không tự thoát. (Artifact hiện tại ĐÚNG: aapt2 versionName=1.30/vc130.)
**Fix**: gate INSTALL theo **versionCODE thật** — `apkVersionCode` (getPackageArchiveInfo) vs `installedVersionCode` (getPackageInfo.longVersionCode); chỉ cài khi tải > cài (`shouldInstall`). `check()` FAIL-CLOSED khi cur="?" (`shouldOffer`). +4 unit test `[UpdateCheckerTest]`.

## 5. RE OpenBYD 2.3 → nguồn số-lối-ra (cho v2)
`[RE:~/Library/Caches/clusternav-re/openbyd-2.3/sources]`: OpenBYD **đọc MÀN HÌNH app nav qua accessibility** (`BydAccessibilityService.getWindowsOnAllDisplays()` — đọc cả khi KHÔNG focus, miễn đang vẽ), **view-id per-app**: GMaps `:id/distance_text`/`step_instruction_container`(contentDescription="take the 2nd exit"), Waze `:id/text_maneuverballoon_*`/`lane`, Yandex `:id/exit_number_text`. 3 manager riêng (GoogleMaps/Waze/Yandex). ⇒ số-lối-ra + cự-ly chính xác đến từ **màn hình**, không phải notification.
- ClusterNav hiện đọc màn qua `NavAccessibilityService` nhưng dùng `rootInActiveWindow` (chỉ focus). App **đã có** đường CAN 25–34 (số-lối-ra) ở `NavRepository`/`AmapFrameBuilder`, chỉ thiếu con số N.
- **Spec v2** `[docs/specs/v2-accessibility-navsource-handoff.html]`: NavScreenSource + adapter per-app (GMaps/Waze/VietMap), đổi `rootInActiveWindow`→`getWindowsOnAllDisplays`, tái dùng ~90% code 1.30. **T0 chặn**: verify GMaps contentDescription có "Nth exit" không (CHƯA đo).

## 6. Doc mapping review (cho owner soi) — GITIGNORE (local)
`docs/diagnostics/gmaps-arrow-mapping-review-2026-08-17.html` (1.8MB): mỗi maneuver = arrow GMaps + **ảnh cụm THẬT** (strip + HUD) từ sweep on-car (`../icon maps/`, 30 glyph CAN) + §B ma trận CAN 1..49. **Gitignore** (nhúng ảnh xe, regenerable qua `build-mapping-doc.py`). Đã verify render (browser screenshot).

## 7. Git / trạng thái
- Commit `c424059` push `main`. **apk/ KHÔNG đụng** (verify `git diff-tree`) → OTA vẫn 1.30, KHÔNG release.
- build.gradle: versionCode 131 / versionName 1.31. README changelog 1.31 (ghi rõ "chưa release OTA").
- Security-scan sub-agent: **CLEAN** (arrow PNG programmatic không EXIF; gitignore chặn capture output nhạy cảm; chỉ dangkhoi noreply).
- Full suite: core 770 · app-debug 381 · car-integration 28 · offcar-planner 99 = 0 fail 0 error `[gradlew 4 suite]`.

## 8. Rule mới
`.kiro/steering/issue-management.md`: mọi issue → đăng ký GitHub Issue (byd-cluster cho 1.x/OTA/on-car; byd-cluster-2 cho v2) → xử lý (commit `#N`) → đóng khi verify (on-car không đóng bằng suy đoán). Labels bug/ota/on-car/enhancement/v2; milestones 1.31/v2.

## 9. Còn lại / phiên sau
- [ ] **Tạo 4 issue GitHub** từ `docs/_handoff/github-issues-2026-08-17.md` (gh CLI chưa cài → paste; hoặc cài gh). #A OTA · #B ArrowClassifier · #C on-car trái→thẳng · #D accessibility v2.
- [ ] **On-car**: (#A) cài bản có fix → confirm hết loop; (#C) bật verbose, lái lại khúc rẽ trái → pull `nav_arrow_log_*.csv` xem `final_icon` vs `maneuver` (classify miss hay timing).
- [ ] **RELEASE 1.31** (khi gôm đủ issue): build `ClusterNav-1.31-release.apk` (vc131) → ký → aapt2 test-surface=0 → apksigner Verifies → đẩy vào `apk/` main = OTA mới mời. (Chưa làm — chủ trương gôm rồi release 1 lần.)
- [ ] **v2 `byd-cluster-2`**: dựng theo spec; T0 verify GMaps contentDescription trước.

## Nguồn
- Code fix: `app/.../UpdateChecker.kt` (+Test) · `core/.../ArrowClassifier.kt` · `core/src/test/.../RealGmapsArrowCorpusTest.kt`.
- Đồ nghề: `scripts/emulator/*`. Corpus: `core/src/test/resources/arrows/*`.
- RE: `~/Library/Caches/clusternav-re/openbyd-2.3/sources/com/sr/openbyd/services/{BydAccessibilityService,GoogleMapsManager,WazeManager,YandexManager}.java`.
- Doc: `docs/specs/v2-accessibility-navsource-handoff.html` · `docs/diagnostics/gmaps-arrow-mapping-review-2026-08-17.html` (gitignore) · `docs/diagnostics/nav-output-architecture-2026-08-16.html` · `re-maneuver-icon-tables-2026-08-14.md`.
- Rule: `.kiro/steering/issue-management.md`. Issue drafts: `docs/_handoff/github-issues-2026-08-17.md`.
