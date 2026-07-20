package com.byd.clusternav.modules.deadreckon

/** Snapshot service ghi → module đọc hiện debug. Thuần volatile. */
object DeadReckonState {
    @Volatile var running = false
    @Volatile var state = "REAL"          // REAL | DEAD_RECKON
    @Volatile var lat = 0.0
    @Volatile var lon = 0.0
    @Volatile var headingDeg = 0.0
    @Volatile var headingSrc = "—"        // "gyro Z" | "GPS bearing (thẳng)"
    @Volatile var speedMps = 0.0
    @Volatile var usedInFix = 0
    @Volatile var sats = 0
    @Volatile var mocking = false
    @Volatile var drEnterCount = 0        // số lần vào DEAD_RECKON (đếm để biết hầm)
    @Volatile var lastError = ""
    // MẶC ĐỊNH TẮT (an toàn): trục gyro Z chỉ đúng nếu sensorscan xác minh; sai trục → heading bậy → GMaps đi lệch.
    @Volatile var useGyro = false
    // Heading từ GÓC LÁI HAL (getSteeringWheelValue(1)) + tốc độ qua bicycle-model — nguồn xác nhận chạy thật trên xe.
    // MẶC ĐỊNH BẬT (an toàn nhờ: chỉ dùng khi đã tự-calib đủ mẫu; trước đó tự đi thẳng — xem gate ở Service).
    @Volatile var useSteer = true
    @Volatile var steerFlip = false
    @Volatile var steerFlipManual = false   // user đã đảo dấu tay → auto-calib KHÔNG đè steerFlip nữa
    // Tỉ số lái TỰ HIỆU CHỈNH online: so yaw-rate GPS (Δbearing/dt khi có GPS tốt) với góc lái HAL để suy ra
    // tỉ số thật của xe, thay cho hằng ước lượng 15.5. Hội tụ dần khi vào cua lúc CÒN GPS.
    @Volatile var steerRatioCal = 15.5
    @Volatile var steerCalSamples = 0     // số mẫu đã dùng để hiệu chỉnh (debug)
    @Volatile var logPath = ""            // đường dẫn file CSV log (adb pull về phân tích)
    @Volatile var savedLoc = "—"          // seed cold-start: mô tả vị trí LƯU lần trước ("có (N phút trước)" | "chưa có" | "quá cũ")
}
