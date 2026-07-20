package com.byd.clusternav.modules.deadreckon

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.TextView
import com.byd.clusternav.modules.ClusterModule
import com.byd.clusternav.modules.ModuleUi
import com.byd.clusternav.modules.SelfTest
import com.byd.clusternav.modules.mockloc.MockLoc

/**
 * MODULE: bật/tắt service dead-reckoning + soi LIVE máy trạng thái. Chạy nền (foreground service) nên vá GPS
 * cả khi màn tắt / YouTube che. Lái qua hầm: state phải nhảy REAL→DEAD_RECKON khi mất sat, GMaps đi tiếp, ra hầm về REAL.
 *
 * Yêu cầu: (1) quyền Location (cấp runtime), (2) chọn ClusterNav làm mock-location app (Developer Options).
 * XOÁ: xoá modules/deadreckon/ + <service> Manifest + dòng Registry.
 */
object DeadReckonModule : ClusterModule {
    override val title = "Dead-reckoning GPS hầm"

    private var view: TextView? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var polling = false

    override fun selfTest(ctx: Context): SelfTest {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val gyro = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val loc = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val mockErr = MockLoc.start(ctx).also { if (it.isEmpty()) MockLoc.stop(ctx) }
        val parts = mutableListOf<String>()
        parts += "gyro=${if (gyro != null) "CÓ (${gyro.name})" else "KHÔNG → heading=GPS bearing thẳng"}"
        parts += "quyền Location=${if (loc) "OK" else "CHƯA (mode mockprobe để xin)"}"
        parts += if (mockErr.isEmpty()) "mock-app=OK" else "mock-app=CHƯA chọn"
        val ok = loc && mockErr.isEmpty()
        return if (ok) SelfTest.pass(parts.joinToString(" · ")) else SelfTest.fail(parts.joinToString(" · "))
    }

    override fun buildView(ctx: Context, parent: ViewGroup) {
        val ui = ModuleUi(ctx, parent)
        ui.text("Bật để service nền vá GPS trong hầm cho GMaps. Lái qua hầm: state REAL→DEAD_RECKON khi mất vệ tinh, " +
            "GMaps đi tiếp theo nội suy, ra hầm về REAL. Cần quyền Location + chọn ClusterNav làm mock-location app.")
        ui.btn("▶ BẬT service dead-reckoning") {
            ctx.applicationContext.startForegroundService(Intent(ctx.applicationContext, DeadReckonService::class.java))
            ui.log("BẬT — lái thử qua hầm")
        }
        ui.btn("■ TẮT service") {
            ctx.applicationContext.stopService(Intent(ctx.applicationContext, DeadReckonService::class.java))
            ui.log("TẮT")
        }
        ui.btn("Heading lái-HAL: BẬT ↔ TẮT") {
            DeadReckonState.useSteer = !DeadReckonState.useSteer
            ui.log("heading lái-HAL → ${if (DeadReckonState.useSteer) "BẬT (bám cua bằng góc lái)" else "TẮT (DR thẳng theo bearing cuối — an toàn)"}")
        }
        ui.btn("Đảo dấu lái (nếu DR đi lệch chiều cua)") {
            DeadReckonState.steerFlip = !DeadReckonState.steerFlip
            DeadReckonState.steerFlipManual = true   // user chỉnh tay → khoá auto-calib khỏi đè
            ui.log("đảo dấu lái → ${DeadReckonState.steerFlip} (khoá auto-calib dấu)")
        }
        val dp = ctx.resources.displayMetrics.density
        view = TextView(ctx).apply {
            textSize = 13f; typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#1A1F24")); setBackgroundColor(Color.parseColor("#F1EFE8"))
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt()); text = "..."
        }
        parent.addView(view)
    }

    override fun onShow(ctx: Context) {
        if (polling) return
        polling = true
        Thread {
            while (polling) {
                val s = DeadReckonState
                val t = buildString {
                    append("service : ${if (s.running) "ĐANG CHẠY" else "tắt"}\n")
                    append("STATE   : ${s.state}${if (s.mocking) "  (đang BƠM mock)" else ""}\n")
                    append("vệ tinh : usedInFix=${s.usedInFix}/${s.sats}\n")
                    val src = if (s.useSteer) "lái-HAL${if (s.steerFlip) "(đảo dấu)" else ""}" else if (s.useGyro) "gyro" else "thẳng (bearing cuối)"
                    append("heading : ${"%.0f".format(s.headingDeg)}° · nguồn=$src\n")
                    append("tỉ số lái: ${"%.1f".format(s.steerRatioCal)} (calib ${s.steerCalSamples} mẫu)\n")
                    append("tốc độ  : ${"%.0f".format(s.speedMps * 3.6)} km/h\n")
                    append("vị trí  : ${"%.5f".format(s.lat)}, ${"%.5f".format(s.lon)}\n")
                    append("vào DR  : ${s.drEnterCount} lần (mỗi hầm +1)\n")
                    append(if (s.logPath.isNotEmpty()) "log     : ${s.logPath}\n" else "")
                    append(if (s.lastError.isNotEmpty()) "LỖI: ${s.lastError}" else "")
                }
                main.post { view?.text = t }
                try { Thread.sleep(500) } catch (_: InterruptedException) {}
            }
        }.start()
    }

    override fun onHide(ctx: Context) { polling = false }
}
