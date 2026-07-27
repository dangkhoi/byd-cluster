# Chạy đánh giá trên xe — không cần APK

Mọi lệnh chạy từ laptop, không cài gì lên xe. Verdict ghi vào
`docs/refactor-car-execution/verdicts.tsv` (append-only). Mọi lần chạy tự lưu log vào
`oncar-carexec-<ngày>/`.

```bash
export CAR_HOST=<ip-xe>
scripts/vehicle/carexec.sh <lệnh>
```

## Chuẩn bị off-car (không cần xe)

| Lệnh | Làm gì |
|---|---|
| `carexec.sh steps` | liệt kê 24 step và 37 candidate |
| `carexec.sh scenarios` | 23 kịch bản, cái nào ráp được |
| `carexec.sh scenario <id>` | từng bước, state phải đúng, ai kiểm |
| `carexec.sh plan <id>` | in đúng chuỗi lệnh sẽ gửi, **không gửi gì** |
| `carexec.sh run <candidate> --dry-run` | in lệnh của một candidate, không gửi |
| `carexec.sh observe --recorded` | quan sát trên fixture đã ghi của xe thật |

Duyệt `plan` trước khi lên xe. Thiếu tham số nó nói ngay — rẻ hơn nhiều so với phát hiện lúc đang trong xe.

## Trên xe: chạy và đánh dấu

```bash
carexec.sh scenario cast.cold-first --run \
  --pkg vn.vietmap.live --comp vn.vietmap.live/vn.vietmap.live.MainActivity \
  --display 1 --task <taskId>
```

Nó chạy tới mốc cần nhìn cụm rồi **dừng**, in ra điều cần nhìn và hai câu lệnh sẵn:

```bash
carexec.sh verdict open.seal-30-16-35 ok   --note "cụm hiện VietMap"
carexec.sh verdict open.seal-30-16-35 fail --note "cụm vẫn hiện đồng hồ"
carexec.sh scenario cast.cold-first --run --from 5     # chạy tiếp
```

Lệnh lỗi hoặc step chưa OK thì nó dừng, không chạy bừa.

## Thứ tự phiên tới (≤ 10 phút, xe đang chạy)

| # | Kịch bản | Được gì |
|---|---|---|
| 1 | `cast.observable-hunt` | **trả lời Q1** — chụp 4 mốc: đồng hồ → có task chưa mở chiếu → chiếu mở → sau khi đóng. Trường nào đổi đúng lúc cụm đổi là observable cần tìm |
| 2 | `cast.cold-first` | đánh OK cho 5 step nền: probe-profile, probe-target, bootstrap-cold, place, open-projection |
| 3 | `cast.stop-paths` | teardown + restore |
| 4 | `cast.rotate-a-b-c-a` | switch ba chiều, soát mồ côi |
| 5 | `cast.geometry-persist` | bốn cạnh + DPI, và kiểm **giữ thiết lập** sau khi cast lại |

Bước 1 chạy step `capture-state` bốn lần (đó là cách nó chụp bốn mốc), và cũng lấp fixture còn thiếu: `appops get` — thiếu nó nên `observe --recorded` hiện dừng ở
`APP_OPS_STATE`. Sau phiên này quan sát chạy trọn vẹn off-car.

## Bẫy đã biết

- Runner nối bằng dadb nên `service call ... s16 ""` gửi trực tiếp. Gõ tay qua `adb shell` thì phải bọc cả
  lệnh trong nháy kép, không thì nhận `Parcel(fffffffc ...)`.
- Máy phải nổ trong phiên dài: 26/7 mất cả phiên vì ACC standby.
- Phiên trên xe **chỉ đo và ghi**. Không sửa code rồi cài trong phiên.
