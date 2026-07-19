# ClusterNav

> Mod **no-root** đưa dẫn đường **Google Maps** lên cụm đồng hồ xe **BYD** (DiLink 3.0, Android 10) — và chiếu app (Maps / VietMap / Waze / Apple CarPlay) lên cụm **mà vẫn giữ phiên dẫn đường**. Không root, không flash, hoàn tác được.
>
> *No-root Android app that pushes Google Maps navigation onto the BYD instrument cluster and projects apps to it while keeping the active route.*

⚠️ **Thử nghiệm — dùng tự chịu rủi ro.** Đây là dự án hobby, KHÔNG liên kết với BYD. Chỉ can thiệp kiểu no-root (qua ADB nội bộ) và đảo ngược được, nhưng bạn tự chịu trách nhiệm khi cài lên xe thật.

## Xe hỗ trợ
Kiểm chứng trên **BYD Seal EU** (DiLink 3.0). Kiến trúc generic (auto-detect + override) cho các dòng DiLink 3 cùng cụm XDJA — **SL6 / Han / Tang** dự kiến chạy được nhưng **cần cộng đồng test** (báo bug + screenshot). Chưa xác nhận = chưa đảm bảo.

## Tính năng
- **Nav-lane lên cụm** — mũi tên rẽ + khoảng cách + tên đường, **GIỮ NGUYÊN đồng hồ gốc** (đọc qua NotificationListener của Google Maps, không cần quyền đặc biệt).
- **Chiếu app lên cụm (giữ dẫn)** — bê app đang chạy sang cụm ở chế độ freeform + resize (**T1**), giữ phiên dẫn đường: Google Maps, VietMap, Waze, Apple CarPlay.
- **Scale per-app** — chỉnh cao/thấp/rộng/hẹp + DPI riêng cho từng app bằng nút mũi tên, lưu lại.
- **GPS hầm (dead-reckon)** — vá vị trí khi mất GPS trong hầm bằng tốc độ + góc lái (mock location).
- **Đa-model** — `ClusterProfile` auto-detect kích thước cụm + cho phép override, xuất/nhập cấu hình để share trong nhóm.

## Cách hoạt động (tóm tắt)
App không có quyền hệ thống. Mọi lệnh đặc quyền chạy qua **dadb** (ADB client thuần JVM nhúng trong app, tự nối `localhost:5555` → uid shell) + service **AutoContainer** của cụm + **NotificationListener**. Cần bật USB debugging + adb-over-network 5555 trên xe một lần.

## Build
```bash
# Yêu cầu: JDK 17, Android SDK (platform 34, build-tools 34)
./gradlew :app:assembleDebug     # APK debug (ký bằng debug key của máy bạn)
./gradlew :app:assembleRelease   # release
```
> **Ký release:** repo KHÔNG kèm khoá ký. Nếu không có `keystore.properties`, bản release tự **fallback ký bằng debug key** (build/cài thử được ngay). Muốn build "chữ ký chung của nhóm" thì xin `keystore.properties` + `release.keystore` từ maintainer, đặt vào thư mục gốc + `app/`.

## Trên xe (một lần)
1. Bật **Developer options → USB debugging** + mở **adb tcp 5555**.
2. Cấp **Notification access** cho ClusterNav.
3. (Tuỳ chọn) chọn ClusterNav làm **mock location app** cho tính năng GPS hầm.

## Kiến trúc
Core `com.byd.clusternav` + các module thí nghiệm trong `modules/*` (đăng ký qua `ModuleRegistry` — thêm/bớt module không đụng core). Xem `docs/` cho chi tiết recipe chiếu + nav-lane.

## Credits
Xem [CREDITS.md](CREDITS.md). Chính: [`dadb`](https://github.com/mobile-dev-inc/dadb) (Apache-2.0).

## License
[MIT](LICENSE) — ai muốn sửa gì thì sửa, thoải mái fork/PR.
