# Research Backlog — 2026-08-03

## 1. SL6 split cast: cả 2 app đều full, không chia đôi

### Triệu chứng
- Xe Sealion 6 (12.8 inch, màn to hơn Seal 10.8 inch)
- Cast trái, phải → cả 2 app đều lên FULL màn, đè lên nhau
- Trên Seal: split trái/phải work ngon

### Chẩn đoán (từ log V2 cũ)
```
mode=READ_ONLY
observation=Known(
  coarseState=ACTIVE_MULTI,
  displayIdentity=display-1,
  target=CastTarget(packageName=com.google.android.apps.maps, taskId=37, displayId=1),
  occupants=[com.google.android.apps.maps, vn.vietmap.live, com.byd.clusternav],
  taskBounds={
    37=CastRect(left=0, top=0, right=1920, bottom=800),
    39=CastRect(left=0, top=0, right=1920, bottom=800),
    36=CastRect(left=0, top=0, right=1920, bottom=800)
  }
)
```

### Phân tích
- Cả 3 task đều bounds `[0, 0, 1920, 800]` = FULL display → không split
- SL6 dùng CHUNG firmware với Seal (DiLink3, Android 10)
- Cast FULL work → display ID đúng, freeform sống
- Log có `mode=READ_ONLY`, `observation=Known(...)`, `store=EMPTY` → đây là **V2 code cũ** chạy trên SL6
- V2 code CÓ split logic KHÁC simplified code (dùng CastPlacementCommands FIT_CLUSTER_COMPOSITE)
- Khả năng cao: SL6 chưa cài bản simplified mới → split fail là bug của V2, không phải simplified
- Nếu đúng: cài bản simplified mới lên SL6 → test lại split → có thể work luôn

### Cần làm (không cần xe)
1. Xác nhận: SL6 đang chạy version nào? V2 hay simplified?
2. Nếu V2: cài bản simplified mới (đã proven split trên Seal) → test
3. Nếu simplified mà vẫn fail: thêm logging vào fitToCluster() → gửi APK, nhờ test, đọc log

### Lý thuyết
Log chẩn đoán từ V2 pipeline (có observation/store/mode). Simplified code mới đã bỏ hết V2, dùng trực tiếp `am task resize`. Trên Seal split work → cùng firmware SL6 cũng phải work nếu chạy cùng code.

---

## 2. Autostart split: chọn app trái + phải tự động

### Hiện trạng
- Đang có 1 spinner autostart cho FULL cluster (1 app)
- Muốn thêm: autostart trái + autostart phải (2 app cùng lúc)

### UX logic
- 3 dòng trong cài đặt:
  - Autostart Full: [dropdown app] — cast 1 app chiếm toàn cụm
  - Autostart Trái: [dropdown app] — cast vào slot trái
  - Autostart Phải: [dropdown app] — cast vào slot phải
- **Constraint:** Full vs Trái/Phải loại trừ nhau:
  - Chọn Full → disable Trái/Phải
  - Chọn Trái hoặc Phải → disable Full
  - Trái + Phải chọn cùng lúc OK (start 2 app split)
  - Cùng app cho Trái và Phải → cần validate? Hay cho phép (user tự chịu)?

### Implementation (SimpleCastPrefs)
```kotlin
// Thêm vào interface:
fun autoStartLeftPackage(): String?
fun setAutoStartLeftPackage(pkg: String?)
fun autoStartRightPackage(): String?
fun setAutoStartRightPackage(pkg: String?)
fun autoStartMode(): AutoStartMode  // FULL, SPLIT, OFF
fun setAutoStartMode(mode: AutoStartMode)

enum class AutoStartMode { OFF, FULL, SPLIT }
```

### Flow khi boot
```
if (autoStartMode == FULL) {
    dispatch(CastFull(autoStartPackage))
} else if (autoStartMode == SPLIT) {
    autoStartLeftPackage?.let { dispatch(CastSlot(it, LEFT)) }
    autoStartRightPackage?.let { dispatch(CastSlot(it, RIGHT)) }
}
```

### Effort: ~1h (prefs + UI + boot logic)

---

## 3. VietMap HUD Bluetooth: sniff speed limit / nav signal

### Bối cảnh
- VietMap bán cục HUD kết nối qua Bluetooth
- Pair vào app VietMap trên xe → hiển thị: dẫn đường, ETA, cảnh báo tốc độ (speed limit)
- Bên thứ 3 cũng bán mini HUD pair từ VietMap → lấy tín hiệu hiển thị
- Tức là protocol KHÔNG phải proprietary bí mật — có thể mò được

### Mục tiêu
ClusterNav giả lập pair/handshake với VietMap để lấy:
- **Speed limit** hiện tại (50/60/80/120 km/h)
- **Hướng dẫn dẫn đường** (rẽ trái 200m, vòng xuyến...)
- **ETA** (còn bao lâu tới đích)

→ Hiển thị lên cluster overlay (SYSTEM_ALERT_WINDOW trên display 1) hoặc HUD

### Hướng nghiên cứu

#### A. Sniff Bluetooth protocol
1. Pair cục HUD VietMap với xe → bật Bluetooth HCI snoop log
2. `adb pull /data/misc/bluetooth/logs/btsnoop_hci.log`
3. Mở bằng Wireshark → filter SPP/RFCOMM/BLE GATT
4. Quan sát: data format (JSON? binary? protobuf?)
5. Identify: service UUID, characteristic UUID (nếu BLE), RFCOMM channel (nếu classic)

#### B. Decompile VietMap APK
1. `jadx vietmap.apk` → tìm Bluetooth service class
2. Grep: `BluetoothGatt`, `BluetoothSocket`, `UUID`, `RFCOMM`
3. Tìm data format gửi ra HUD: serialization, field names
4. Identify: handshake sequence, auth (nếu có)

#### C. Reverse từ thiết bị bên thứ 3
- Tìm mua/mượn mini HUD bên thứ 3 kết nối VietMap
- Pair → sniff → compare với cục chính hãng
- Nếu giống = protocol chuẩn, dễ replicate

### Khả thi?
- **Cao**: bên thứ 3 đã làm được → protocol không quá bí mật
- **Rủi ro**: VietMap update app → đổi protocol → break
- **Effort**: 2-4h sniff + reverse, 2-4h implement pair/read

### Deliverable
- `core/.../hud/VietMapBtBridge.kt` — pair + read speed limit + nav
- Hiển thị lên cluster overlay hoặc expose qua broadcast cho HUD riêng
