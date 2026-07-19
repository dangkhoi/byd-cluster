# Credits — ClusterNav

## dadb — embedded ADB client
[`dev.mobile:dadb`](https://github.com/mobile-dev-inc/dadb) — ADB client thuần JVM, nhúng trong app để tự nối
`localhost:5555` → chạy lệnh đặc quyền dưới uid shell (no-root). **License: Apache-2.0.**

## navopen.jar
`app/src/main/assets/navopen.jar` — code của chính dự án (HAL writer ghi frame nav xuống cụm qua reflection),
chạy bằng `app_process` phía uid shell. Không phải thư viện bên thứ ba.

---
> Tiếng động cơ giả (engine sound) đã tách sang app riêng **PoSound** (`com.byd.posound`) — không nằm trong repo này.
> Attribution cho tài nguyên âm thanh (CryHam / Stunt Rally 3, CC-BY-4.0) thuộc repo PoSound.
