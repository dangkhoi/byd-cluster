# Track 2 — Kịch bản dò nguồn AUTONAVI trên xe (SẠCH)

> Đi kèm script `scripts/on-car-navprobe.sh` + bản debug `com.byd.clusternav.debug` (nhánh `debug/navprobe-clean`).
> Mục tiêu: **trả lời DỨT KHOÁT** câu "app nào phát `AUTONAVI_STANDARD_BROADCAST_SEND`" — để nếu là Vietmap
> thì viết bộ rút turn-by-turn bắn lên cụm (như đã làm với Google Maps).
>
> ⚠ Bài học 23/07 (phải khắc cốt): kết luận trace cũ bị loại vì **log trộn phiên 07-22 + hôm nay**, và vì
> **quy kết nguồn khi CHƯA cô lập**. Kịch bản này thiết kế để KHÔNG lặp lại 2 lỗi đó.

---

## 0. Câu hỏi & tiêu chí thành công

| | |
|---|---|
| **Câu hỏi** | App nào là **SENDER** của broadcast `AUTONAVI_STANDARD_BROADCAST_SEND` (mang `NEXT_ROAD_NAME`, `SEG_REMAIN_DIS`, `ROUTE_REMAIN_DIS/TIME`, icon rẽ)? |
| **Thành công** | Có **≥ 2 bằng chứng độc lập** cùng chỉ về 1 app: (a) isolation (tắt app → AUTONAVI dừng) + (b) callerPackage trong `dumpsys activity broadcasts` HOẶC ⟨fg⟩ nhất quán. |
| **Thất bại chấp nhận được** | Kết luận "nguồn = hệ thống, không rút được từ app thứ ba" — vẫn là kết luận SẠCH nếu có bằng chứng isolation. |
| **KHÔNG chấp nhận** | Quy kết chỉ dựa ⟨fg⟩ mà không cô lập; hoặc đọc file navprobe bị trộn phiên. |

---

## 1. Protocol đã xác nhận (soi tĩnh firmware — trước khi lên xe)

Từ `byd/jadx-amap2/sources/com/example/amapservice/AmapService.java` (line 132–137, 299+):

```
[ App dẫn đường thật ]
      │  sendBroadcast("AUTONAVI_STANDARD_BROADCAST_SEND", extras: KEY_TYPE=10001, NEXT_ROAD_NAME, …)
      ▼
[ com.example.amapservice · AmapBroadReceiver ]   ← BÊN NHẬN (registerReceiver + addAction SEND_ACTION)
      │  đọc extras → GuideInfo → sendNaviToCluster()
      ▼
[ Cụm đồng hồ BYD (HAL: BYDAutoInstrumentDevice) ]
```

Hệ quả cho việc dò:
- `com.example.amapservice` là **CẦU NỐI/RECEIVER**, **KHÔNG phải nguồn** → **TUYỆT ĐỐI KHÔNG force-stop nó** để coi là "nguồn". (Tắt nó chỉ làm cụm ngừng hiện nav, KHÔNG làm broadcast ngừng phát.)
- AmapService phân biệt `mIsBydMap` (BYD map) vs `mIsGAODENaving` (高德/AutoNavi bên thứ ba) → **có ≥ 2 loại sender khả dĩ**.
- NavProbe (kênh 4) đăng ký **receiver riêng** cho cùng action → thấy y hệt broadcast mà amapservice thấy → bắt được turn-by-turn. Nhưng Android **giấu sender** khỏi receiver → phải cô lập.

---

## 2. Giả thuyết & danh sách ứng viên

| Ứng viên | Package | Vai trò dự đoán | Ưu tiên |
|----------|---------|-----------------|---------|
| **Vietmap Live** | `vn.vietmap.live` | Nghi CHÍNH — app chủ xe hay dùng; nếu dùng AutoNavi SDK sẽ phát AUTONAVI | 🔴 cao |
| **BYD map** | `com.tmap.auto.byd` | Nhánh `mIsBydMap` — map cài sẵn của xe | 🟠 vừa |
| GAODE/AutoNavi nền | (chưa thấy app độc lập) | Có thể là service nền phát sẵn | 🟡 kiểm nếu ΔC>0 |
| ~~amapservice~~ | `com.example.amapservice` | **RECEIVER** — loại khỏi ứng viên nguồn | ⛔ không tắt |
| ~~Waze~~ | `com.waze` | Video-surface, không nói AUTONAVI | ⛔ đối chứng âm |
| ~~CarPlay / AA~~ | `com.byd.carplay.ui` / `com.byd.androidauto` | Video-surface (đã xác nhận `uiautomator dump`=null) | ⛔ đối chứng âm |

**Giả thuyết H1**: Vietmap phát AUTONAVI → ΔB>0 (còn phát khi chỉ Vietmap) và ΔC=0 (dừng khi giết Vietmap).
**Giả thuyết H0 (null)**: nguồn là hệ thống → ΔC>0 (vẫn phát sau khi giết Vietmap).

---

## 3. Tiền điều kiện (làm TRƯỚC khi lăn bánh)

1. **Cài bản debug** cạnh bản release (2 icon riêng — bản debug hiện nhãn **"ClusterNav DEBUG"**):
   ```bash
   adb install -r apks/ClusterNav-debug.apk        # com.byd.clusternav.debug, v0.60-debug
   ```
2. **Cấp 2 quyền** cho máy dò (hoặc để app tự cấp qua dadb): ĐỌC MÀN HÌNH (accessibility) + ĐỌC THÔNG BÁO.
   Mở app DEBUG → 2 nút xanh; hoặc auto-arm sẽ tự cấp khi nổ máy.
3. **Bật ghi + phiên MỚI**: mở app DEBUG → **BẮT ĐẦU DÒ**. Nếu vừa reboot thì phiên tự mới; nếu không chắc,
   **TẮT rồi BẬT lại** để mở file mới (`navprobe_v0.60-debug_<ts>.txt`).
4. **Kết nối adb qua mạng**: `adb connect <IP-xe>:5555` (cùng WiFi). ⚠ **KHÔNG cắm CP/AA** trong các pha đo
   Vietmap — cắm CP/AA làm đầu xe TẮT WiFi → mất adb. (CP/AA chỉ là đối chứng âm, đo sau bằng auto-capture trong app.)

---

## 4. Giao thức thí nghiệm có kiểm soát (khớp `on-car-navprobe.sh`)

> Nguyên tắc: **đổi ĐÚNG 1 biến mỗi pha**, đo delta số bản ghi `AUTONAVI_..._SEND` theo **giờ của XE**.
> Mỗi pha ~90s (chỉnh `PHASE_SEC`). LÁI CÓ DẪN ĐƯỜNG suốt pha để sinh tín hiệu.

| Pha | Thao tác (biến đổi) | Giữ nguyên | Đo |
|-----|--------------------|-----------|-----|
| **[1] Session guard** | — | — | Xác nhận file khớp `@b<boot_count>` hiện tại (không trộn) |
| **[2] Forensics** | — | — | `dumpsys activity broadcasts` → tìm `callerPackage` cạnh AUTONAVI |
| **P0** | baseline (chưa làm gì) | tất cả | tổng send hiện tại |
| **PA** | mở Vietmap dẫn đường, đủ app | tất cả app | ΔA = send tăng thêm |
| **PB** | `am force-stop` Waze/BYD-map/CP/AA | **giữ Vietmap** + bridge | ΔB |
| **PC** | `am force-stop` **Vietmap** (giữa lúc đang dẫn) | bridge | ΔC |
| **PD** *(tuỳ chọn)* | đóng hết, **chỉ mở BYD-map** dẫn đường | bridge | Δ riêng cho BYD-map |

Script tự ghi mỗi mốc vào `phases.log` dạng: `giờ-xe | nhãn-pha | send_total=N | running: <procs>`.

---

## 5. Bảng quyết định (kết quả → kết luận)

| ΔA (đủ app) | ΔB (chỉ Vietmap) | ΔC (giết Vietmap) | KẾT LUẬN |
|:---:|:---:|:---:|---|
| >0 | **>0** | **0** | ✅ **Nguồn = Vietmap.** Còn phát khi chỉ Vietmap, dừng khi giết Vietmap → viết bộ rút dữ liệu. |
| >0 | >0 | **>0** | ⚠ **Nguồn = hệ thống** (không phải Vietmap). Vẫn phát sau khi giết Vietmap → chạy PD + soi callerPackage. |
| >0 | **0** | — | ⚠ **Nguồn trong nhóm tắt ở PB** (khả năng cao BYD-map). Tắt khi bỏ Vietmap → chạy PD xác nhận. |
| ~0 | ~0 | — | ❌ **Không đủ tín hiệu** — kiểm: có đang dẫn đường thật? phiên đúng? quyền đủ? Lặp lại. |

**Bằng chứng thứ hai (bắt buộc để chốt):** callerPackage ở [2] HOẶC ⟨fg⟩ nhất quán trong `autonavi-fg-tally.txt`
phải trùng với kết luận isolation. Hai nguồn lệch nhau → CHƯA chốt, đo lại.

---

## 6. Bài học đã mã hoá vào kịch bản (chống lặp lỗi)

1. **Phiên mới mỗi lần đo** — mục [1] chặn đọc nhầm file trộn (session-key = `versionName@boot_count`).
   *Lỗi cũ:* navprobe tái dùng file 07-22 → digest trộn 2 ngày → quy kết vô nghĩa.
2. **Cô lập TRƯỚC khi quy kết** — ΔB/ΔC là bằng chứng hành vi; ⟨fg⟩ chỉ là proxy.
   *Lỗi cũ:* gán "AA phát AUTONAVI" (12:07) chỉ vì AA đang foreground → thí nghiệm cô lập chứng minh SAI.
3. **Grep log THẬT, đếm delta có mốc thời gian** — không kết luận từ cảm giác.
4. **KHÔNG tắt cầu nối** — `com.example.amapservice` là receiver; tắt nó gây hiểu nhầm "nguồn dừng".
5. **Đổi 1 biến/pha** — mỗi pha chỉ thay đúng một thứ để delta quy được về nguyên nhân.

---

## 7. Đem gì về + báo cáo

Thư mục `on-car-navprobe-<ts>/` gồm:
- `phases.log` — mốc pha + số AUTONAVI mỗi pha (nguồn của bảng ΔA/ΔB/ΔC).
- `navprobe/` — file máy dò kéo về (grep `AUTONAVI_STANDARD_BROADCAST_SEND` để đọc turn-by-turn thật).
- `broadcasts-history.txt` — truy vết callerPackage.
- `autonavi-fg-tally.txt` — ⟨fg⟩ đối chiếu.

Báo cáo 1 dòng cần có: **ΔA/ΔB/ΔC + callerPackage (nếu lộ) + kết luận theo bảng §5**. Nếu H1 đúng
(nguồn = Vietmap) → mở nhánh viết extractor; nếu H0 (hệ thống) → cân nhắc đọc thẳng cụm/HAL thay vì rút từ app.
