# Emulator GMaps notification capture — ground truth cho classifier hướng rẽ

> Mục tiêu: bắt **notification GMaps THẬT** (cái ClusterNav nhận), off-car, có thể lặp lại — để biết chắc
> GMaps nhét gì vào noti (text có động từ rẽ không? field nào? mũi tên large-icon trông ra sao?), thay vì
> suy từ doc. Kết quả dùng để sửa `ManeuverSignature`/`ArrowClassifier`/`NavFormat.maneuverVerbIcon` theo
> dữ liệu thật + dựng corpus test.
>
> **No-assumptions:** đây là thứ dự án CHƯA từng bắt. `NavDiag` chỉ giữ RAM, không persist; repo không có
> dump GMaps nào. Registry 38 mũi tên của `ManeuverSignature` là port từ OpenBYD, chưa đối chiếu GMaps bao giờ.

## Đã sẵn trên máy (đã verify)
- AVD `clusternav` = android-34 `google_apis` arm64 (có Play **Services**, KHÔNG có Play **Store** → GMaps phải **sideload**).
- `emulator` 36.6, `adb` tại `~/Library/Android/sdk/platform-tools/adb`.
- JDK 17 (Homebrew) chạy Gradle — build được nếu cần.
- `apk/ClusterNav-1.30-release.apk` = pure-JVM (không `.so`) → cài chạy trên emulator arm64, khỏi build. `BydHal` degrade sạch (reflection null, không crash) nên `NavArrowLog` vẫn ghi được arrow.

Toàn bộ lệnh dưới đây đặt biến cho gọn:
```bash
export ADB=~/Library/Android/sdk/platform-tools/adb
export EMU=~/Library/Android/sdk/emulator/emulator
```

---

## A. Khởi động emulator
> ✅ **Đã kiểm chứng trên máy này (2026-08-17):** AVD `clusternav` boot ~23s; `apk/ClusterNav-1.30-release.apk`
> cài `Success`; app launch KHÔNG crash trên emulator non-BYD (MainActivity resumed, `BydHal` degrade sạch);
> listener grant short-form ăn; script capture chạy sạch. **Chỉ còn phần GMaps (B–D) cần bạn** (sideload +
> đăng nhập + Start nav) vì cần cửa sổ GUI + tài khoản Google. Boot KÈM cửa sổ (bỏ `-no-window`) để thao tác:
```bash
"$EMU" -avd clusternav -no-snapshot-load &     # cửa sổ emulator hiện lên
"$ADB" wait-for-device
"$ADB" devices                                  # thấy emulator-5554 = device
```
Ghi serial (thường `emulator-5554`) để truyền `--serial` cho script capture.

## B. Cài GMaps (sideload — vì image không có Play Store)
Không có Play Store nên cần file APK GMaps. Hai cách:
1. **Pull từ điện thoại/xe** đang có GMaps (chắc-ăn khớp bản):
   ```bash
   # trên thiết bị nguồn: lấy path
   "$ADB" -s <SRC_SERIAL> shell pm path com.google.android.apps.maps
   # pull mọi split (base + config.*), rồi install-multiple lên emulator
   "$ADB" -s <SRC_SERIAL> pull <đường-dẫn-base.apk> ./maps-base.apk
   "$ADB" -s emulator-5554 install-multiple ./maps-*.apk   # hoặc install ./maps-base.apk nếu 1 file
   ```
2. Hoặc tải 1 bản GMaps APK (arm64 / universal) từ nguồn APK uy tín rồi `"$ADB" install`.

> GMaps cần Play Services (image này CÓ). Nếu GMaps than "Google Play services out of date" vẫn thường
> điều hướng được; nếu chặn hẳn thì cân nhắc image `google_apis_playstore` (cần cmdline-tools `sdkmanager`
> để tải — hiện máy chưa có; nói mình nếu cần, mình tải cmdline-tools rồi dựng AVD Play Store).

## C. Đăng nhập Google (INTERACTIVE — cần bạn)
Settings → Passwords & accounts → Add account → Google → đăng nhập. (GMaps điều hướng ổn định hơn khi đã sign-in.)
> Đây là bước duy nhất mình không làm thay được (tài khoản của bạn).

## D. Route có nhiều loại rẽ + bật điều hướng
Mũi tên/verb chỉ xuất hiện khi GMaps điều hướng THẬT dọc một route. Cách khớp GPS↔route:
1. Emulator → **⋮ (Extended controls) → Location → Routes**: nhập **điểm đầu + điểm đến** ở khu phố dày (có
   ngã rẽ trái/phải + vòng xuyến) → emulator tự tính route (Google Directions) → chỉnh tốc độ → **Play Route**.
2. Trong GMaps: đặt **cùng điểm đến đó** → Start (bấm Start để vào turn-by-turn). GPS emulator sẽ chạy dọc route → GMaps đọc từng khúc rẽ.
   - Mẹo chọn route: cố ý đi qua ≥1 rẽ trái, ≥1 rẽ phải, ≥1 chếch (slight/keep), ≥1 vòng xuyến để corpus đủ loại.
   - GPX thay thế: có thể **Load GPX/KML** thay vì Routes (xem `route-example.gpx` — chỉ là mẫu định dạng; muốn đúng thì record theo route thật hoặc dùng Routes ở trên).

---

## E1. Bắt TEXT (ưu tiên 1 — zero build, quyết định "có động từ không")
Khi GMaps ĐANG điều hướng (thấy mũi tên + "xxx m" trên noti), chạy:
```bash
scripts/emulator/capture-gmaps-noti.sh --serial emulator-5554 --seconds 0
# Ctrl-C khi chạy hết vài khúc rẽ. Output: ./gmaps-noti-capture-<stamp>/
```
Mỗi lần field noti đổi, nó append snapshot (title/text/subText/bigText/template/smallIcon) + in 1 dòng
`verb-token: <...>` để bạn thấy ngay GMaps có nhét "turn left/rẽ trái/roundabout..." hay không.

## E2. Bắt ARROW BITMAP + verdict từng lớp (ưu tiên 2 — dùng APK sẵn)
```bash
"$ADB" -s emulator-5554 install -r apk/ClusterNav-1.30-release.apk
# cấp quyền listener (thay cho dadb self-grant vốn fail trên emulator).
# ⚠ PHẢI dùng SHORT-FORM component; full-form "com.byd.clusternav/com.byd.clusternav.NavNotificationListener"
#   trả rc=0 nhưng KHÔNG ăn (đã kiểm chứng). Short-form ăn ngay:
"$ADB" -s emulator-5554 shell cmd notification allow_listener \
  "com.byd.clusternav/.NavNotificationListener"
# verify: lệnh dưới phải in ra ...:com.byd.clusternav/com.byd.clusternav.NavNotificationListener
"$ADB" -s emulator-5554 shell settings get secure enabled_notification_listeners
```
Rồi trong app ClusterNav (mở trên emulator):
1. Bật **Nav+HUD ON** (bắt buộc — listener chỉ xử lý khi Prefs.enabled). Trên emulator nó thử self-grant qua
   dadb sẽ fail nhưng vô hại (đã bọc runCatching); quyền đã cấp tay ở trên.
2. **Nhấn-giữ nhãn phiên bản** để bật verbose → `NavArrowLog` mới ghi CSV + PNG.
3. Chạy route (bước D). `NavArrowLog` ghi vào `/sdcard/Android/data/com.byd.clusternav/files/`:
   - `nav_arrow_log_<stamp>.csv` — cột: `maneuver,small_amap,sig_name,sig_amap,verb_amap,heuristic_amap,final_icon,arrow_src,...`
   - `nav_arrow_pngs_<stamp>/` — ẢNH mũi tên từng frame.

## F. Kéo artifacts về + gửi mình
```bash
"$ADB" -s emulator-5554 pull /sdcard/Android/data/com.byd.clusternav/files/ ./clusternav-files
```
Gửi mình: `gmaps-noti-capture-*/noti-history.txt` + `first-full-dump.txt` (E1) và `nav_arrow_log_*.csv` +
vài PNG mũi tên (E2). Mình đối chiếu với `:core` classifiers → biết chính xác lớp nào đúng/sai → dựng corpus
test → fix theo dữ liệu thật.

---

## Ghi chú
- **Riêng tư:** dump chứa route/địa chỉ thật của bạn. Giữ local, **đừng commit**. (Output `gmaps-noti-capture-*/`
  và `clusternav-files/` nên cho vào `.gitignore`; nói mình nếu muốn mình thêm.)
- **Emulator caveat:** HAL/broadcast lên cụm no-op (không có phần cứng BYD) — không sao, ta chỉ cần input noti
  + output classifier, cả hai chạy được trên emulator.
- Bước cần bạn: C (đăng nhập Google) và bấm Start điều hướng ở D. Còn lại mình script/tự chạy được.
