# ClusterNav — Rule bắt buộc cho mọi phiên làm việc

> Viết ngày 2026-07-21, sau một phiên làm nhanh-ẩu: nhiều bản vá phải gỡ lại, có bản gây đơ launcher trên
> xe thật, có bản suýt ghim GPS toàn hệ thống. **Mọi rule dưới đây đều sinh ra từ một lỗi CÓ THẬT trong phiên đó**,
> không phải lý thuyết. Đọc trước khi sửa dòng code đầu tiên.

Đây là app chạy trên **xe đang lăn bánh ngoài đường**. Một regression không phải là bug — nó là một người
đang lái phải dừng xe khởi động lại đầu máy. Ưu tiên: **đúng > an toàn > nhanh**. Không có ngoại lệ vì "gấp".

---

## 1. Trước khi sửa: phải có spec

Repo này có `docs/specs/`. Task mới hoặc thay đổi lớn → **viết spec trước, user duyệt rồi mới code**
(xem rule global §1). Phiên 21/07 bỏ qua bước này vì "đang gấp" và trả giá bằng 3 vòng vá-rồi-gỡ.

Vá nóng < 20 dòng, một file, không đổi hành vi → được bỏ spec. Còn lại thì không.

---

## 2. Phân biệt CƠ CHẾ và QUY KẾT — không được trộn

Sai lầm điển hình phiên 21/07: chứng minh được *"`wm overscan` không đổi khung cửa sổ với app khai
`FLAG_LAYOUT_IN_OVERSCAN`"* (đúng, có source), rồi phát biểu luôn *"Android Auto chính là loại đó"* (chưa
có một mẩu bằng chứng nào — grep cả 4 file dump không có chữ `androidauto` nào).

Khi báo cáo, **luôn tách ba mức**:

| Mức | Nghĩa | Được phép nói |
|---|---|---|
| Đã chứng minh | Đọc source AOSP / dump thật | "là như thế" |
| Nhiều khả năng | Suy luận khớp hiện tượng, chưa có dữ liệu trực tiếp | "nghi là", kèm cách chốt |
| Giả thuyết | Mới chỉ hợp lý | "đoán", nêu rõ chưa kiểm |

Không có dữ liệu thì nói **"chưa biết"** và nêu đúng một lệnh/quan sát để chốt. Đoán mò rồi ship là cách
nhanh nhất để sửa nhầm bệnh.

---

## 3. Framework Android: đọc source TRƯỚC khi ship, không dựa trí nhớ

Ba bản vá phải gỡ trong phiên 21/07 đều vì tin trí nhớ về AOSP:

- `MockLoc.pause()` = `setTestProviderEnabled(false)` để "tạm ngưng mà không gỡ" → **sai**: `addTestProvider`
  gỡ provider GPS thật khỏi `mProviders`, **chỉ `removeTestProvider` mới lắp lại**. Nếu ship, COLD_SEED
  (được miễn failsafe) sẽ ghim GPS của cả xe ở một toạ độ đóng băng suốt chuyến.
- Cổng `sats >= 4` để "bỏ qua peek khi còn trong hầm" → **tự khoá vĩnh viễn**: `addTestProvider` đã
  `native_stop()` GNSS nên `sats` đóng băng, mà chỉ peek mới bật lại được engine. Phụ thuộc vòng tròn.
- Tầng `am stack resize` → chết ở `TaskRecord.resolveOverrideConfiguration` (`computeFullscreenBounds()`
  mở đầu bằng `outBounds.setEmpty()`), lại còn kèm `am task resizeable` sửa vĩnh viễn task của app khác.

**Rule:** mọi khẳng định về hành vi framework phải fetch source `android-10.0.0_r47` (và DL5 = Android 12)
rồi trích dẫn `file:line`. Chưa fetch thì chưa được ship.

**Rule đi kèm:** không bao giờ gate một đường phục hồi bằng dữ liệu mà chỉ chính đường đó mới làm mới được.

---

## 4. Lệnh đổi state hệ thống phải có phạm vi TƯỜNG MINH

Lỗi đơ Dudu launcher: `stop()` bê **mọi** stack có `displayId >= 1` về display 0 — không khớp đúng display,
không lọc loại stack, không lọc app. Kéo nhầm stack `home`/`pinned` là `addStackReferenceIfNeeded` ném
exception **sau khi** `removeFromDisplay()` đã chạy → stack mồ côi, launcher không bao giờ được resume,
`am stack list` cũng không thấy → **chỉ còn khởi động lại đầu xe**.

Trước mỗi lệnh `am`/`wm`/`service call`, trả lời được cả bốn:

1. Nhắm đúng **display nào**? (`vd < 1` → không làm gì, không bao giờ quét mù)
2. Nhắm đúng **app nào**? (allow-list, không phải "mọi thứ trừ…")
3. Nhắm đúng **loại stack nào**? (chỉ `standard`; `home`/`recents`/`pinned` là vùng cấm)
4. **Hoàn tác kiểu gì** nếu nửa chừng hỏng?

Không trả lời được câu nào thì chưa được viết lệnh đó.

---

## 5. State đổi ngoài hệ thống thì SỐNG DAI hơn tiến trình

`casting` / `lastDisplayId` nằm trong RAM, chết theo process. Còn `wm density` / `wm overscan` / `wm size`
được WM ghi vào `/data/system/display_settings.xml` theo `uniqueId` của display — **sống qua cả reboot**.
App-op PIP, animation scale, chế độ AutoContainer cũng vậy.

- Mỗi thứ đổi ra ngoài phải có **đường trả lại**, và đường đó phải chạy được cả khi tiến trình đã chết
  (→ ghi marker vào prefs *trước* khi đổi, dọn lúc khởi động).
- **Cấm** quyết định bằng cờ RAM. Kiểm bằng sự thật (`am stack list`, `dumpsys`). Cờ chỉ để hiển thị.
- Guard cứng đặt ở tầng **thi hành** (`applyBounds` từ chối task không đúng VD), không đặt ở tầng UI.

---

## 6. Không đảo thứ tự đường đã chạy tốt ngoài hiện trường

`wm size` được thêm vào và đặt **trước** `wm overscan` — trong khi overscan đang chạy tốt cho CarPlay và
Vietmap. Suýt làm hỏng hai app đang ổn để chữa cho một app.

**Rule:** đường mới **luôn xuống cuối**, và phải **tự đo** xem đường cũ có thật sự hụt không rồi mới leo
(`overscanVerified` đọc lại khung cửa sổ thật). Không hardcode tên gói để rẽ nhánh — để code tự đo và tự chọn.

---

## 7. Generic, không case-by-case

Không `if (pkg == "com.byd.androidauto")`. Khác biệt giữa các app phải lộ ra qua **đo đạc** (app có tôn
trọng inset không? task có ở trên VD không? freeform sống chưa?) rồi rẽ nhánh theo kết quả đo.

Khác biệt giữa các **đời xe** phải nằm trong `ClusterProfile` (tên service, chuỗi lệnh, gợi ý tên VD, kích
cụm), không rải rác trong code. DiLink5 dùng `auto_container` còn DL2/3/4 dùng `AutoContainer` — hardcode
một cái là dòng xe kia câm lặng, không báo lỗi gì.

---

## 8. Sau khi sửa: kiểm HÀM MỚI CÓ ĐƯỢC GỌI KHÔNG

`CastShell.evictVd` viết cẩn thận, có KDoc, compile sạch — và **chưa từng được gọi lần nào**, vì lần viết lại
`applyBounds` theo dải dòng đã nuốt mất call site. Compile xanh không có nghĩa là code chạy.

Sau mỗi lần sửa lớn (đặc biệt là thay theo dải dòng / regex):

```bash
grep -rn "<tênHàmMới>" app/src/main/java/    # phải thấy ÍT NHẤT 1 call site ngoài định nghĩa
```

Và ưu tiên `Edit` với chuỗi khớp chính xác hơn là thay theo số dòng.

---

## 9. Phiên bản: mỗi bản build đã báo cho user = một số hiệu riêng

Phiên 21/07 có **ba bản nội dung khác nhau cùng tên "v0.37"**, cộng thêm một lần đoán nhầm xe đang chạy
0.35 (thực tế 0.36) làm cả buổi chẩn đoán đi sai hướng.

- Sửa code sau khi đã báo APK cho user → **bump versionCode + versionName**, không tái dùng số cũ.
- **Không bao giờ đoán** xe đang chạy bản nào. Đọc từ máy (`dumpsys package … versionName`) hoặc hỏi.
- Version phải hiện trong app (tiêu đề màn Chiếu), trong log phiên, và trong tên file log.

---

## 10. Test là thứ khoá lại bài học, không phải thủ tục

Mỗi lỗi hiện trường đã root-cause → **một test hồi quy** dựng từ dump thật, kèm comment nói rõ nó khoá cái gì.
Xem `StackParseTest.evictableOnVd*` (khoá lỗi đơ launcher), `AppScaleTest.nudgeRect cham san*` (khoá lỗi trôi khung).

Parser (`StackParse`, `DisplayParse`, `AppScale`, `ClusterProfile`) là code **thuần**, test off-device được —
mọi hành vi phụ thuộc chuỗi output của `dumpsys`/`am` phải có fixture lấy nguyên văn từ
`docs/diagnostics/carlog-*/`.

---

## 11. Lấy log từ xe: app tự chụp, không bắt user gõ adb

Cắm CarPlay/Android Auto là đầu xe **tắt WiFi** → adb từ ngoài không vào được, mà đó đúng là lúc cần dữ liệu.
Nhưng app chạy **trên** đầu xe và nối dadb qua `localhost:5555` (loopback, không cần mạng).

⇒ Cần dữ liệu gì thì thêm vào `ClusterDiag`, để app tự chụp và ghi file. Màn `DiagActivity` gom hết phần kỹ
thuật — anh em chỉ cần **chụp màn hình gửi về**. Không hướng dẫn user gõ lệnh.

---

## 12. Nguồn RE có sẵn trong workspace — dùng trước khi đoán

Đừng nói "không tìm được thông tin" trước khi đọc những thứ này (`../` từ repo):

| Thư mục | Nội dung |
|---|---|
| `jadx-dashcast/` `dashcast-src/` | DashCast v1.5.4 đã decompile + source + CHANGELOG rất chi tiết (có log field-test thật theo từng đời DiLink) |
| `jadx-openbyd/` `jadx-openbyd24/` | OpenBYD |
| `jadx-amap/` `jadx-amap2/` `jadx-tmap/` `jadx-kim/` | app nav OEM |
| `firmware/` `BYDUpdatePackage/` `byd-fw-scratch/` | firmware BYD |
| `apks/` | DashCast, ClusterDemo, AmapService, BydAutoTMap… |
| `docs/diagnostics/carlog-*/` | dump thật lấy từ xe |

`dashcast-src/CHANGELOG.md` đặc biệt giá trị: ghi lại kết quả test THẬT trên từng đời DiLink, kèm lệnh và
kết luận (vd: ROM DL5 cắt bỏ `cmd activity set-task-windowing-mode`; `cmd activity task resize` trả exit 0
mà không có tác dụng).

---

## 13. Quy trình mỗi lần chạm code

1. Đọc rule này + spec liên quan trong `docs/specs/`.
2. Root-cause tới tận source, ghi rõ mức bằng chứng.
3. Sửa **generic** (đo đạc, không hardcode tên gói; khác biệt đời xe vào `ClusterProfile`).
4. Thêm test hồi quy.
5. `./gradlew clean assembleRelease testDebugUnitTest` (JAVA_HOME=`/opt/homebrew/opt/openjdk@17`; nhớ trả
   `local.properties` về `sdk.dir` Windows sau khi build — xem memory `clusternav-build-on-mac`).
6. Grep xem hàm mới có call site chưa.
7. Bump version nếu đã từng báo APK cho user.
8. Senior review (Opus, rule global §5) + security scan trước commit (§6).
9. Ghi phát hiện vào `docs/diagnostics/` và cập nhật spec.
