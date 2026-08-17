# GitHub Issues cần đăng ký — 2026-08-17

> gh CLI chưa cài trên máy → paste tay lên GitHub Issues (hoặc cài `gh` rồi `gh issue create`). Theo
> `.kiro/steering/issue-management.md`. Repo: 1.x/OTA/on-car → `dangkhoi/byd-cluster`; v2 → `byd-cluster-2`.

---

## #A — OTA: nhấn cập nhật cài lại cùng version vô hạn
**Repo:** dangkhoi/byd-cluster · **Labels:** `bug` `ota` `p1` · **Milestone:** 1.31

**Repro:** Đã update lên 1.30. Nhấn "Cập nhật" lại → nó vẫn tải về 1.30 và cài lại; lặp mãi (không nhận ra đã ở bản mới nhất).

**Nguyên nhân (điều tra off-car):** `UpdateChecker.check()` quyết "có bản mới" bằng **versionName lấy từ TÊN FILE** (`ClusterNav-1.30-release.apk` → "1.30") so với **versionName đang cài** `[UpdateChecker.kt: cmp()/check()]`. Nếu versionName cài đọc THẤP hơn tên file (bản 1.30 sớm có versionName nội bộ ≠ "1.30", hoặc `getPackageInfo` trả rỗng → `currentVersion()="?"` → `cmp("1.30","?")>0` luôn), `hasUpdate` true mãi. Thêm: `pm install -r` cùng version **vẫn thành công** → không tự chặn. (Artifact hiện tại ĐÚNG: aapt2 `versionName=1.30 versionCode=130`, GitHub main 1 file — nên loop đến từ bản cài cũ hoặc `currentVersion="?"`.)

**Fix đề xuất:** Gate INSTALL theo **versionCODE thật**: đọc vc APK đã tải (`getPackageArchiveInfo`) vs vc đang cài (`getPackageInfo.longVersionCode`) → chỉ cài khi tải > cài. FAIL-CLOSED ở `check()`: `cur="?"` → không mời update.

**Acceptance:** equal vc → không cài (bỏ qua); cur unknown → không mời; newer → cài. Off-car unit test xanh. **On-car:** xe đang loop, cài bản có fix → hết loop.

---

## #B — ArrowClassifier (fallback) đọc cua thường/■u-turn thành "đi thẳng"
**Repo:** dangkhoi/byd-cluster · **Labels:** `bug` `p2` · **Milestone:** 1.31

**Evidence (corpus GMaps thật, emulator 2026-08-17):** `maneuver_turn_normal_left/right` → `ArrowClassifier` heur=**9 (thẳng)**; u_turn → 5. Root cause: mũi tên rẽ GMaps có **thân dọc** triệt tiêu trọng-tâm ngang → `off` < ngưỡng → đọc thẳng `[ArrowClassifier.kt]`. Là lớp DỰ PHÒNG (khi ManeuverSignature trượt) — cơ chế "rẽ trái mà cụm đi thẳng".

**Fix (ĐÃ làm off-car):** `off = headOff` (đầu mũi tên, bỏ pha loãng bằng thân). Corpus test `RealGmapsArrowCorpusTest` (14 arrow thật) + full suite 1667 test 0 fail.

**Acceptance:** corpus test pass + 0 regression (đạt off-car). **Chưa đóng** — cần on-car confirm bug #C có phải ca này không.

---

## #C — GMaps quẹo trái nhưng strip cụm hiện "đi thẳng" (cần on-car)
**Repo:** dangkhoi/byd-cluster · **Labels:** `bug` `on-car` · **Milestone:** 1.31

**Repro:** Sáng 2026-08-17 owner lái: GMaps chỉ rẽ TRÁI, strip nav (dải nhỏ trên cụm) hiện ĐI THẲNG.

**Trạng thái:** CHƯA tái hiện trên emulator (ManeuverSignature đọc đúng trái/phải/■vòng-xuyến ở đó). Có thể là (1) signature trượt trên style arrow xe → rơi ArrowClassifier (bug #B), hoặc (2) notification hiện đoạn thẳng hiện-tại (chưa tới khúc rẽ). Cần đo.

**Việc on-car:** bật verbose (nhấn-giữ nhãn version) → lái lại đúng khúc → `adb pull /sdcard/Android/data/com.byd.clusternav/files/` → đọc `nav_arrow_log_*.csv`: `maneuver` (text GMaps) vs `final_icon`. `final_icon=9` khi text=rẽ trái → classify miss (xác nhận #B cứu được?).

**Acceptance:** biết nguyên nhân thật + strip hiện đúng hướng khi lái.

---

## #D — Accessibility NavScreenSource đa-app (GMaps/Waze/VietMap) + số lối ra vòng xuyến
**Repo:** byd-cluster-2 · **Labels:** `enhancement` `v2` · **Milestone:** v2

**Context:** Notification GMaps nghèo (không có số-lối-ra vòng xuyến — xác minh emulator; extras chỉ cự-ly/ETA/tên-đường/bitmap). OpenBYD 2.3 (RE) đọc MÀN HÌNH qua accessibility (`getWindowsOnAllDisplays` + view-id per-app) → lấy được số-lối-ra, làn, cự-ly chính xác, đa-app (GMaps/Waze/Yandex).

**Spec sẵn:** `docs/specs/v2-accessibility-navsource-handoff.html` (đủ Requirements/Design/Tasks/■Open Questions).

**Acceptance:** theo spec. **T0 chặn:** verify GMaps `step_instruction_container.contentDescription` có "take the Nth exit" không (chưa xác minh) TRƯỚC khi cam kết phần số-lối-ra GMaps.

---

### Ghi chú lifecycle (theo rule)
- #A, #B: off-car xong → commit `fixes #A` / liên quan `#B`; #A đóng khi on-car hết loop; #B giữ Open tới khi #C confirm.
- #C: on-car only — không đóng bằng suy đoán.
- #D: v2 — để `byd-cluster-2`, approve khi mở repo.
