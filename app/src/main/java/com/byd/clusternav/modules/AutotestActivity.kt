package com.byd.clusternav.modules

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Criteria
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import com.byd.clusternav.AmapFrameBuilder
import com.byd.clusternav.ClusterBroadcaster
import com.byd.clusternav.NavDiag
import com.byd.clusternav.NavRepository
import com.byd.clusternav.NavState
import com.byd.clusternav.Prefs
import com.byd.clusternav.modules.hal.BydHal
import com.byd.clusternav.modules.navremoteviews.RemoteViewsModule
import java.io.File

/**
 * AUTOMATION 1 lượt cho ADB (cắm máy → 1 lệnh). Headless: chạy self-test MỌI module + recon HAL + bơm 1 frame
 * mẫu lên cụm, ghi logcat (tag CLNAV_AUTO) + file getExternalFilesDir/autotest-report.txt.
 *
 * Khởi: adb shell am start -n com.byd.clusternav/.modules.AutotestActivity
 * Kéo:  adb pull /sdcard/Android/data/com.byd.clusternav/files/autotest-report.txt
 * (xem docs/diagnostics/autotest.ps1)
 *
 * INFRA test — xoá không ảnh hưởng module nào. Không nằm trên đường nav.
 */
class AutotestActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply { textSize = 11f; setPadding(24, 24, 24, 24); setTextIsSelectable(true) }
        setContentView(ScrollView(this).apply { addView(tv) })

        val sb = StringBuilder()
        fun out(s: String) {
            Log.i(TAG, s); sb.append(s).append('\n')
            runOnUiThread { tv.text = sb.toString() }
        }

        Thread {
            // (tiếng pô hãng — mode enginesim — đã tách sang app com.byd.posound; ClusterNav không còn xử lý tiếng pô)
            // Chế độ in-proc SẠCH: tắt broadcaster (khỏi đè), bơm in-proc LẶP để nhìn rõ. (am start ... --es mode inproc)
            if (intent.getStringExtra("mode") == "inproc") {
                out("===== IN-PROC ONLY (sustained, KHÔNG broadcast) =====")
                runCatching { ClusterBroadcaster.stop(applicationContext) }
                out("đã stop broadcaster")
                repeat(14) { i ->
                    val rc = BydHal.writeNavFrame(applicationContext, 2, 300, "InProc test")
                    if (i == 0 || i == 13) out("lần ${i + 1}: $rc")
                    try { Thread.sleep(700) } catch (_: InterruptedException) {}
                }
                out("===== DONE ===== NHÌN CỤM: 'InProc test' + 300m + mũi tên TRÁI? (in-proc render = bỏ dadb)")
                return@Thread
            }
            // Chế độ ETA: CHỈ ghi trip/ETA (KHÔNG maneuver band) để cô lập xem vùng ETA có tự render. (--es mode eta)
            if (intent.getStringExtra("mode") == "eta") {
                out("===== ETA-ONLY (sustained, KHÔNG broadcast, KHÔNG maneuver band) =====")
                runCatching { ClusterBroadcaster.stop(applicationContext) }
                val sys = BydHal.systemBypassContext()
                val instr = BydHal.device(BydHal.INSTRUMENT, sys, BydHal.bypass(applicationContext))
                val setting = BydHal.device(BydHal.SETTING, sys, BydHal.bypass(applicationContext))
                if (instr == null) { out("InstrumentDevice null"); return@Thread }
                val remainH = intent.getStringExtra("h")?.toIntOrNull() ?: 1
                val remainM = intent.getStringExtra("m")?.toIntOrNull() ?: 20
                val mileageM = intent.getStringExtra("km")?.toFloatOrNull()?.let { (it * 1000).toInt() } ?: 11000
                val arriveH = intent.getStringExtra("ah")?.toIntOrNull() ?: 9
                val arriveM = intent.getStringExtra("am")?.toIntOrNull() ?: 45
                fun wI(name: String, v: Int) {
                    val id = BydHal.featureId(name) ?: run { out("  $name=<không có id>"); return }
                    out("  $name=" + runCatching { BydHal.setInt(instr, id, v) }.getOrElse { BydHal.root(it) })
                }
                fun wS(name: String, v: Int) {
                    val id = BydHal.featureId(name) ?: run { out("  $name=<không có id>"); return }
                    if (setting == null) { out("  $name=<setting null>"); return }
                    out("  $name=" + runCatching { BydHal.setInt(setting, id, v) }.getOrElse { BydHal.root(it) })
                }
                out("giá trị: còn ${remainH}h${remainM}m · ${mileageM / 1000f}km · tới $arriveH:${String.format("%02d", arriveM)}")
                repeat(20) { i ->
                    wI("INSTRUMENT_SEND_NAVI_STATUS_SET", 2)            // GATE nav active
                    wS("SET_NAVI_SCREEN_STATUS_SET", 3)                 // GATE layout nav
                    wI("INSTRUMENT_NAVI_TRIP_INFO_HOUR_SET", remainH)   // >0 để không bị skip
                    wI("INSTRUMENT_NAVI_TRIP_INFO_MINUTE_SET", remainM)
                    wI("INSTRUMENT_NAVI_TRIP_REMAINING_SECOND_SET", 0)
                    wI("INSTRUMENT_REMAIN_DRIVING_TIME_DAY_SET", 1)
                    wI("INSTRUMENT_NAVI_TRIP_INFO_MILEAGE_SET", mileageM)
                    wI("INSTRUMENT_EXPECTED_ARRIVE_DAY_SET", 1)
                    wI("INSTRUMENT_EXPECTED_ARRIVE_HOUR_SET", arriveH)
                    wI("INSTRUMENT_EXPECTED_ARRIVE_MINUTE_SET", arriveM)
                    wI("INSTRUMENT_EXPECTED_ARRIVE_SECOND_SET", 0)
                    if (i == 0) out("  READBACK: " + BydHal.readGetters(instr, listOf(
                        "getCurrentJourneyDriveTime" to null, "getTravelTime" to null,
                        "getCurrentJourneyDriveMileage" to null)).joinToString(" | "))
                    try { Thread.sleep(700) } catch (_: InterruptedException) {}
                }
                out("===== DONE ===== NHÌN CỤM vùng TRIP/ETA: có 'còn ${remainH}h${remainM}m · ${mileageM / 1000f}km · tới $arriveH:${String.format("%02d", arriveM)}'?")
                return@Thread
            }
            // Chế độ etabroadcast: gửi frame có ETA qua AmapService (đường render được), thử IS_BYD_MAP=true để mở
            // layout giàu (có map/ETA như OEM). (--es mode etabroadcast [--es byd true|false])
            if (intent.getStringExtra("mode") == "etabroadcast") {
                val byd = intent.getStringExtra("byd")?.toBoolean() ?: true
                out("===== BROADCAST + ETA, IS_BYD_MAP=$byd (thử layout giàu như OEM) =====")
                runCatching { ClusterBroadcaster.resetBydNaving(applicationContext) }   // khử cờ kẹt trước
                val st = NavState(active = true, distance = "500 m", road = "Nguyễn Huệ",
                    maneuverText = "Rẽ phải vào Nguyễn Huệ", maneuverIcon = 3,
                    eta = "15:45 · 11 km · 75 phút", updatedAt = System.currentTimeMillis())
                out("frame: 500m + Nguyễn Huệ + eta='${st.eta}' (còn 1h15m / 11km / tới 15:45)")
                repeat(22) { i ->
                    runCatching { AmapFrameBuilder.buildGuidanceFrame(st, byd) }.getOrNull()?.let { sendBroadcast(it) }
                    if (i == 0) out("đang gửi broadcast IS_BYD_MAP=$byd...")
                    try { Thread.sleep(700) } catch (_: InterruptedException) {}
                }
                if (byd) runCatching { ClusterBroadcaster.resetBydNaving(applicationContext) }   // true->false khử cờ kẹt
                out("===== DONE ===== NHÌN CỤM (byd=$byd): có ETA / km còn lại / layout giàu hơn EASY band không?")
                return@Thread
            }
            // Chế độ vdmap: mở VdMapActivity in-app (qua tên class -> không couple module). (--es mode vdmap [--es pkg ..] [--ez full ..])
            if (intent.getStringExtra("mode") == "vdmap") {
                val pkg = intent.getStringExtra("pkg") ?: "app.revanced.android.apps.maps"
                val full = intent.getBooleanExtra("full", true)
                val manual = intent.getBooleanExtra("manual", false)
                runCatching {
                    startActivity(android.content.Intent()
                        .setClassName(packageName, "com.byd.clusternav.modules.vdmap.VdMapActivity")
                        .putExtra("pkg", pkg).putExtra("full", full).putExtra("manual", manual))
                    out("===== DONE ===== mở VdMapActivity ($pkg, full=$full, manual=$manual) — NHÌN MÀN GIỮA (manual: chờ am start --display)")
                }.onFailure { out("===== DONE ===== mở vdmap lỗi: ${it.javaClass.simpleName}: ${it.message}") }
                return@Thread
            }
            // Chế độ mapmode: thử kích MAP WINDOW / mini-map cụm (mapSendStatus 1/2/3 + naviScreen=3). (--es mode mapmode)
            if (intent.getStringExtra("mode") == "mapmode") {
                out("===== MAP-MODE cụm (thử kích map window / mini-map) =====")
                runCatching { ClusterBroadcaster.stop(applicationContext) }
                val sys = BydHal.systemBypassContext()
                val instr = BydHal.device(BydHal.INSTRUMENT, sys, BydHal.bypass(applicationContext))
                val setting = BydHal.device(BydHal.SETTING, sys, BydHal.bypass(applicationContext))
                instr?.let { d -> BydHal.featureId("INSTRUMENT_SEND_NAVI_STATUS_SET")?.let { id -> runCatching { BydHal.setInt(d, id, 2) } } }
                val mapSendId = 0x4C10E01D   // SET_MAP_SENDING_STATUS
                for (v in 1..3) {
                    setting?.let { d -> runCatching { BydHal.setInt(d, mapSendId, v) } }
                    setting?.let { d -> BydHal.featureId("SET_NAVI_SCREEN_STATUS_SET")?.let { id -> runCatching { BydHal.setInt(d, id, 3) } } }
                    out("mapSendStatus=$v + naviScreen=3 → NHÌN CỤM (mini-map / map window?)")
                    try { Thread.sleep(3500) } catch (_: InterruptedException) {}
                }
                setting?.let { d -> runCatching { BydHal.setInt(d, mapSendId, 0) } }
                out("===== DONE ===== mode nào hiện map/mini-map không?")
                return@Thread
            }
            // Chế độ clearnav: tắt sạch nav cụm (status=4, screen=0, mapSend=0). (--es mode clearnav)
            if (intent.getStringExtra("mode") == "clearnav") {
                runCatching { ClusterBroadcaster.stop(applicationContext) }
                val sys = BydHal.systemBypassContext()
                BydHal.device(BydHal.INSTRUMENT, sys, BydHal.bypass(applicationContext))?.let { d ->
                    BydHal.featureId("INSTRUMENT_SEND_NAVI_STATUS_SET")?.let { id -> runCatching { BydHal.setInt(d, id, 4) } }
                }
                BydHal.device(BydHal.SETTING, sys, BydHal.bypass(applicationContext))?.let { d ->
                    BydHal.featureId("SET_NAVI_SCREEN_STATUS_SET")?.let { id -> runCatching { BydHal.setInt(d, id, 0) } }
                    runCatching { BydHal.setInt(d, 0x4C10E01D, 0) }
                }
                out("===== DONE ===== đã clear nav cụm")
                return@Thread
            }
            // (tiếng pô đã tách sang app riêng com.byd.posound — mode sound/soundstop/enginesim bỏ khỏi ClusterNav)
            // ===== RECON DEAD-RECKONING (Phase 0) — trả lời 4 ẩn-số phần cứng trước khi build module thật =====
            // sensorscan: head unit có gyro/IMU không?
            if (intent.getStringExtra("mode") == "sensorscan") {
                out("===== SENSOR SCAN — head unit có gyro/IMU không? =====")
                val sm = getSystemService(SENSOR_SERVICE) as SensorManager
                val all = sm.getSensorList(Sensor.TYPE_ALL)
                out("Tổng ${all.size} sensor:")
                all.forEach { out("  · ${it.name} (type=${it.type}, vendor=${it.vendor})") }
                val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
                out("GYROSCOPE: ${gyro?.name ?: "KHÔNG CÓ ❌"} · ROTATION_VECTOR: ${sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.name ?: "—"} · ACCEL: ${sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.name ?: "—"}")
                if (gyro != null) {
                    val v = FloatArray(3)
                    val lis = object : SensorEventListener {
                        override fun onSensorChanged(e: SensorEvent) { v[0] = e.values[0]; v[1] = e.values[1]; v[2] = e.values[2] }
                        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
                    }
                    val h = Handler(Looper.getMainLooper())
                    h.post { sm.registerListener(lis, gyro, SensorManager.SENSOR_DELAY_GAME) }
                    out("Đọc gyro 12s — ĐỨNG YÊN vài giây (đo bias) rồi RẼ TRÁI/PHẢI (tìm trục yaw + dấu):")
                    repeat(12) { i -> out("  t=${i}s gx=${"%.3f".format(v[0])} gy=${"%.3f".format(v[1])} gz=${"%.3f".format(v[2])} rad/s"); try { Thread.sleep(1000) } catch (_: InterruptedException) {} }
                    h.post { sm.unregisterListener(lis) }
                }
                out("===== DONE ===== Trục đổi rõ khi rẽ = YAW (lúc đứng = bias). Có gyro → fallback heading OK; không → dựa HAL (steerscan).")
                return@Thread
            }
            // steerscan: HAL có góc lái / wheel-speed (nguồn yaw) không?
            if (intent.getStringExtra("mode") == "steerscan") {
                out("===== STEER/WHEEL SCAN — HAL có góc lái / wheel-speed không? =====")
                val sys = BydHal.systemBypassContext()
                val body = BydHal.device(BydHal.BODYWORK, sys, BydHal.bypass(applicationContext))
                val special = BydHal.device(BydHal.SPECIAL, sys, BydHal.bypass(applicationContext))
                out("Bodywork getInstance: ${if (body != null) "OK ✓" else "NULL ❌"}")
                if (body != null) out("  get*: " + BydHal.methods(body, "get").joinToString(" | "))
                out("Special getInstance: ${if (special != null) "OK ✓" else "NULL ❌"}")
                if (special != null) out("  get*: " + BydHal.methods(special, "get").joinToString(" | "))
                out("Đọc 15s — QUAY VÔ-LĂNG trái/phải xem giá trị nào đổi:")
                repeat(15) { i ->
                    val sb = StringBuilder("t=${i}s ")
                    if (body != null) for (a in 0..3) BydHal.callGetter(body, "getSteeringWheelValue", a)?.let { sb.append("steer($a)=$it ") }
                    if (special != null) {
                        BydHal.callGetter(special, "getWheelSpeed")?.let { sb.append("wheelSpeed=$it ") }
                        BydHal.callGetter(special, "getWheelDirection")?.let { sb.append("wheelDir=$it ") }
                    }
                    out("  $sb"); try { Thread.sleep(1000) } catch (_: InterruptedException) {}
                }
                out("===== DONE ===== Getter đổi theo vô-lăng = nguồn heading TỐT NHẤT (góc lái) hoặc wheelDir/Speed.")
                return@Thread
            }
            // mockprobe: GMaps có ĐÈ được bằng mock-location không (GO/NO-GO cả kế hoạch)
            if (intent.getStringExtra("mode") == "mockprobe") {
                out("===== MOCK PROBE — GMaps có ĐÈ được bằng mock-location không (GO/NO-GO) =====")
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    runOnUiThread { requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 7) }
                    out("ĐÃ XIN quyền Location — cấp trong popup rồi CHẠY LẠI mode này."); return@Thread
                }
                val lm = getSystemService(LOCATION_SERVICE) as LocationManager
                val lat = intent.getStringExtra("lat")?.toDoubleOrNull() ?: 21.028511    // Hồ Gươm HN (xa HCM → nhảy rõ)
                val lon = intent.getStringExtra("lon")?.toDoubleOrNull() ?: 105.854167
                val provs = listOf(LocationManager.GPS_PROVIDER)   // chỉ GPS (khớp MockLoc — tránh đóng băng raw GNSS)
                val added = ArrayList<String>()
                try {
                    for (p in provs) {
                        try {
                            runCatching { lm.removeTestProvider(p) }
                            lm.addTestProvider(p, false, false, false, false, true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE)
                            lm.setTestProviderEnabled(p, true); added.add(p)
                        } catch (e: SecurityException) {
                            out("❌ CHƯA chọn ClusterNav làm 'mock location app': ${BydHal.root(e)}")
                            out("→ Cài đặt > Tuỳ chọn nhà phát triển > Chọn ứng dụng vị trí mô phỏng = ClusterNav, rồi chạy lại."); return@Thread
                        } catch (e: Exception) { out("addTestProvider $p lỗi: ${BydHal.root(e)}") }
                    }
                    out("đã add mock: ${added.joinToString()}")
                    out("→ MỞ Google Maps trên head unit NGAY: chấm xe có NHẢY về Hồ Gươm Hà Nội không? (bơm 60s)")
                    repeat(120) {
                        for (p in added) {
                            val loc = Location(p).apply {
                                latitude = lat; longitude = lon; altitude = 10.0; accuracy = 5f
                                time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(); speed = 0f; bearing = 0f
                                if (Build.VERSION.SDK_INT >= 26) { bearingAccuracyDegrees = 5f; speedAccuracyMetersPerSecond = 1f; verticalAccuracyMeters = 5f }
                            }
                            runCatching { lm.setTestProviderLocation(p, loc) }
                        }
                        try { Thread.sleep(500) } catch (_: InterruptedException) {}
                    }
                } finally {
                    for (p in added) runCatching { lm.removeTestProvider(p) }   // LUÔN gỡ mock dù lỗi/thoát sớm
                }
                out("===== DONE ===== Chấm NHẢY về HN = mock ĐÈ được → CẢ KẾ HOẠCH ĐI ✓. Đứng nguyên = head unit dùng GPS OEM riêng.")
                return@Thread
            }
            // gnsslog: usedInFix có tụt ~0 trong hầm không (để chốt ngưỡng trigger dead-reckon)
            if (intent.getStringExtra("mode") == "gnsslog") {
                val secs = intent.getStringExtra("secs")?.toIntOrNull() ?: 120
                out("===== GNSS LOG (${secs}s) — usedInFix có tụt ~0 trong hầm không =====")
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    runOnUiThread { requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 7) }
                    out("ĐÃ XIN quyền Location — cấp rồi chạy lại."); return@Thread
                }
                val lm = getSystemService(LOCATION_SERVICE) as LocationManager
                val used = intArrayOf(0); val total = intArrayOf(0); val maxCn0 = floatArrayOf(0f)
                val cb = object : GnssStatus.Callback() {
                    override fun onSatelliteStatusChanged(s: GnssStatus) {
                        var u = 0; var mc = 0f
                        for (i in 0 until s.satelliteCount) { if (s.usedInFix(i)) u++; if (s.getCn0DbHz(i) > mc) mc = s.getCn0DbHz(i) }
                        used[0] = u; total[0] = s.satelliteCount; maxCn0[0] = mc
                    }
                }
                val locLis = object : LocationListener { override fun onLocationChanged(location: Location) {} }
                val h = Handler(Looper.getMainLooper())
                h.post {   // try-catch TRONG lambda (exception fire trên handler thread, không bắt được ở ngoài)
                    runCatching {
                        lm.registerGnssStatusCallback(cb, h)
                        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locLis, Looper.getMainLooper())
                    }.onFailure { out("đăng ký GNSS/location lỗi: ${BydHal.root(it)}") }
                }
                out("LÁI qua hầm/gầm cầu. Log mỗi giây:")
                repeat(secs) { i -> out("  t=${i}s used=${used[0]}/${total[0]} maxCn0=${"%.0f".format(maxCn0[0])}dBHz"); try { Thread.sleep(1000) } catch (_: InterruptedException) {} }
                h.post { lm.unregisterGnssStatusCallback(cb); lm.removeUpdates(locLis) }
                out("===== DONE ===== used tụt ~0 trong hầm + hồi khi ra = trigger tin cậy (ngưỡng used==0 >3s bật dead-reckon).")
                return@Thread
            }
            // drsim: kiểm toán công thức tích phân vị trí (đứng yên vẫn test)
            if (intent.getStringExtra("mode") == "drsim") {
                val kmh = intent.getStringExtra("kmh")?.toDoubleOrNull() ?: 50.0
                val secs = intent.getStringExtra("secs")?.toIntOrNull() ?: 20
                val heading = intent.getStringExtra("hdg")?.toDoubleOrNull() ?: 45.0
                out("===== DR SIM (${secs}s @ ${kmh}km/h, hướng ${heading}°) — kiểm công thức mét→độ =====")
                var lat = 21.0285; var lon = 105.8542; val mps = kmh / 3.6; val dt = 0.2
                val hRad = Math.toRadians(heading); var dist = 0.0
                repeat(secs * 5) {
                    val d = mps * dt; dist += d
                    lat += d * Math.cos(hRad) / 111320.0
                    lon += d * Math.sin(hRad) / (111320.0 * Math.cos(Math.toRadians(lat)))
                }
                out("sau ${secs}s: đi ${"%.0f".format(dist)}m → vị trí (${"%.6f".format(lat)}, ${"%.6f".format(lon)})")
                out("kỳ vọng ~${"%.0f".format(mps * secs)}m → ${if (Math.abs(dist - mps * secs) < 1) "KHỚP ✓" else "lệch"}")
                out("===== DONE ===== công thức OK; trên xe heading lấy từ gyro/HAL, speed từ SpeedProvider.")
                return@Thread
            }

            // (T2 gỡ 2026-07-19: mode 'clustersurface' + 'clustermap' + module ClusterMapModule đã xoá — XDJA mirror
            //  chết vì SELinux untrusted_app. Chiếu-cụm giờ đi T1/T3 qua ClusterCast, dò VD fission vẫn giữ.)
            // mapmode2: thử NAVI_TYPE + DYNAMIC_NAVI_FUNCTION (chưa thử) đánh thức map cụm. (--es mode mapmode2)
            if (intent.getStringExtra("mode") == "mapmode2") {
                out("===== MAP MODE 2 — sweep NAVI_TYPE + DYNAMIC_NAVI_FUNCTION (mảnh hy vọng cuối) =====")
                val sys = BydHal.systemBypassContext()
                val instr = BydHal.device(BydHal.INSTRUMENT, sys, BydHal.bypass(applicationContext))
                val setting = BydHal.device(BydHal.SETTING, sys, BydHal.bypass(applicationContext))
                runCatching { ClusterBroadcaster.resetBydNaving(applicationContext) }
                val st = NavState(active = true, distance = "300 m", road = "Map Test",
                    maneuverText = "đi thẳng", eta = "15:30 · 5 km · 10 phút", updatedAt = System.currentTimeMillis())
                fun feed(n: Int) { repeat(n) { runCatching { AmapFrameBuilder.buildGuidanceFrame(st, false) }.getOrNull()?.let { sendBroadcast(it) }; try { Thread.sleep(500) } catch (_: InterruptedException) {} } }
                feed(3)
                if (instr != null) for (v in 0..5) {
                    BydHal.featureId("INSTRUMENT_NAVI_TYPE_SET")?.let { id -> out("NAVI_TYPE=$v -> " + runCatching { BydHal.setInt(instr, id, v) }.getOrElse { BydHal.root(it) }) }
                    feed(4); out("  ↑ nhìn cụm 2s — có map/đổi layout?"); try { Thread.sleep(2000) } catch (_: InterruptedException) {}
                }
                if (setting != null) {
                    BydHal.featureId("SET_DYNAMIC_NAVI_FUNCTION_STATUS_SET")?.let { id -> out("DYNAMIC_NAVI_FUNCTION=1 -> " + runCatching { BydHal.setInt(setting, id, 1) }.getOrElse { BydHal.root(it) }) }
                    for (v in 1..3) { runCatching { BydHal.setInt(setting, 0x4c10e01d, v) }; out("MAP_SENDING=$v + DYNAMIC=1 (nhìn cụm 2.5s)"); feed(2); try { Thread.sleep(2500) } catch (_: InterruptedException) {} }
                    BydHal.featureId("SET_DYNAMIC_NAVI_FUNCTION_STATUS_SET")?.let { id -> runCatching { BydHal.setInt(setting, id, 0) } }
                    runCatching { BydHal.setInt(setting, 0x4c10e01d, 0) }
                }
                runCatching { ClusterBroadcaster.resetBydNaving(applicationContext) }
                out("===== DONE ===== có BẤT KỲ pixel map / mini-map / đổi layout nào không? (dự đoán: không — map cần Car API privileged)")
                return@Thread
            }
            // roundabout: test glyph vòng xuyến + số nhánh-ra (ép icon 11 + ROUNG_ABOUT_NUM). (--es mode roundabout [--es exit 3])
            if (intent.getStringExtra("mode") == "roundabout") {
                val exit = intent.getStringExtra("exit")?.toIntOrNull() ?: 3
                out("===== ROUNDABOUT — vòng xuyến lối ra thứ $exit =====")
                runCatching { ClusterBroadcaster.resetBydNaving(applicationContext) }
                val st = NavState(active = true, distance = "200 m", road = "Vong xoay Test",
                    maneuverText = "Tại vòng xuyến đi theo lối ra thứ $exit vào Đường Test",
                    eta = "", updatedAt = System.currentTimeMillis())
                repeat(22) { runCatching { AmapFrameBuilder.buildGuidanceFrame(st, false) }.getOrNull()?.let { sendBroadcast(it) }; try { Thread.sleep(700) } catch (_: InterruptedException) {} }
                runCatching { ClusterBroadcaster.resetBydNaving(applicationContext) }
                out("===== DONE ===== NHÌN CỤM: glyph vòng xuyến + nhánh ra thứ $exit? Thử --es exit 1..5 xem glyph đổi.")
                return@Thread
            }
            // kmtest: thử NHIỀU cách gửi 1300m, nhìn cụm cách nào ra 'km' (đóng/mở case #3). (--es mode kmtest)
            if (intent.getStringExtra("mode") == "kmtest") {
                out("===== KM TEST — thử 4 cách, NHÌN CỤM cách nào ra 'km' =====")
                fun frame(seg: Int, auto: String?): android.content.Intent {
                    val i = android.content.Intent("AUTONAVI_STANDARD_BROADCAST_SEND")
                    i.putExtra("KEY_TYPE", 10001); i.putExtra("TYPE", 1); i.putExtra("EXTRA_STATE", 1)
                    i.putExtra("EXTRA_IS_FOREGROUND", 0); i.putExtra("IS_BYD_MAP", false); i.putExtra("IS_BYD_BAIDU_MAP", false)
                    i.putExtra("NEW_ICON", 9); i.putExtra("SEG_REMAIN_DIS", seg)
                    if (auto != null) i.putExtra("SEG_REMAIN_DIS_AUTO", auto)
                    i.putExtra("NEXT_ROAD_NAME", "KM Test"); i.putExtra("ROUTE_REMAIN_DIS", 8000)
                    return i
                }
                fun variant(label: String, seg: Int, auto: String?) {
                    out(">>> $label — nhìn cụm ~6s")
                    runCatching { ClusterBroadcaster.resetBydNaving(applicationContext) }
                    repeat(10) { sendBroadcast(frame(seg, auto)); try { Thread.sleep(600) } catch (_: InterruptedException) {} }
                }
                variant("V1: 1300m, AUTO='1300 m'", 1300, "1300 m")
                variant("V2: 1300m, AUTO='1.3 km'", 1300, "1.3 km")
                variant("V3: 1300m, KHÔNG AUTO", 1300, null)
                variant("V4: 5200m, AUTO='5.2 km'", 5200, "5.2 km")
                runCatching { ClusterBroadcaster.resetBydNaving(applicationContext) }
                out("===== DONE ===== Cách nào (V1-V4) cụm hiện 'km'? Nếu V2/V4 ra km -> cụm ĐỌC AUTO, sửa được. Nếu cả 4 đều 'm' -> firmware cứng mét.")
                return@Thread
            }
            // navids: dump feature-id liên quan nav (cho vụ KM-unit #3 + mini-map #4). (--es mode navids)
            if (intent.getStringExtra("mode") == "navids") {
                out("===== NAV FEATURE-IDS =====")
                fun dump(label: String, vararg subs: String) {
                    val list = BydHal.featureIdsMatching(*subs)
                    out("· $label (${list.size}):")
                    list.forEach { out("    ${it.first} = 0x${Integer.toHexString(it.second)}") }
                }
                dump("DIST/SEG/CROSS/GUIDE/REMAIN", "NAVI", "SEG", "CROSS", "GUIDE", "DISTANCE", "ROUTE", "REMAIN", "TRIP")
                dump("UNIT/KM/METER/MILEAGE", "UNIT", "MILEAGE", "_KM", "METER")
                dump("SCREEN/MAP/MODE/WINDOW/MINI", "SCREEN", "MAP", "WINDOW", "MINI", "LAYOUT", "EASY", "STYLE")
                out("===== DONE =====")
                return@Thread
            }
            // Chế độ holddist: GIỮ cự ly CỐ ĐỊNH (nội suy off) để test FIRMWARE có tự đếm xuống không. LÁI XE.
            // (--es mode holddist [--es m 800] [--es secs 90])
            if (intent.getStringExtra("mode") == "holddist") {
                val m = intent.getStringExtra("m")?.toIntOrNull() ?: 800
                val secs = intent.getStringExtra("secs")?.toIntOrNull() ?: 90
                out("===== HOLD DIST ${m}m trong ${secs}s — TEST FIRMWARE TỰ ĐẾM =====")
                out("LÁI XE đi, giữ ga đều. Mình gửi CỐ ĐỊNH ${m}m, nhìn cụm:")
                out(" • Cụm ĐỨNG YÊN ${m}m suốt -> firmware KHÔNG tự đếm -> NỘI SUY có lý do (GIỮ).")
                out(" • Cụm TỰ TỤT ${m}->${m - 10}->... -> firmware/cluster TỰ đếm -> nội suy THỪA (BỎ, kẻo đếm đôi).")
                runCatching { ClusterBroadcaster.stop(applicationContext) }
                runCatching { ClusterBroadcaster.resetBydNaving(applicationContext) }
                val st = NavState(active = true, distance = "$m m", road = "GIU ${m}m",
                    maneuverText = "Đi thẳng", maneuverIcon = 11, eta = "", updatedAt = System.currentTimeMillis())
                val ticks = (secs * 1000) / 500
                repeat(ticks) { i ->
                    runCatching { AmapFrameBuilder.buildGuidanceFrame(st, false, null, m) }.getOrNull()?.let { sendBroadcast(it) }
                    if (i == 0) out("đang gửi cố định ${m}m mỗi 500ms... (lái đi)")
                    try { Thread.sleep(500) } catch (_: InterruptedException) {}
                }
                runCatching { ClusterBroadcaster.resetBydNaving(applicationContext) }
                out("===== DONE ===== Cụm đứng yên hay tự tụt? -> quyết keep/kill nội suy.")
                return@Thread
            }
            // Chế độ notiftrace: dump nhịp noti dẫn đường (chứng minh noti SỐNG khi bị che). (--es mode notiftrace)
            if (intent.getStringExtra("mode") == "notiftrace") {
                out("===== NOTI TRACE — nhịp cập nhật noti dẫn đường =====")
                val (n, avg) = NavDiag.cadence()
                out("60s gần nhất: $n mẫu · TB ${if (avg >= 0) "${avg}ms/lần" else "—"} · lần cuối ${NavDiag.sinceLastMs()}ms trước")
                out("--- mới → cũ (Δ = cách dòng trước) ---")
                var prev = -1L
                NavDiag.snapshot().take(30).forEach { h ->
                    val d = if (prev < 0) 0L else prev - h.atMs; prev = h.atMs
                    out("Δ${if (d > 0) "${d}ms" else "  ·  "}  [${h.pkg.substringAfterLast('.')}] '${h.title}' | ${h.text.take(24)}${if (h.hasIcon) " ◆" else ""}")
                }
                out("===== DONE ===== nếu lúc che YouTube vẫn có dòng mốc đều -> noti SỐNG khi che (kênh duy nhất sống).")
                return@Thread
            }
            // Chế độ audiocue: nghe luồng audio Ns xem usage dẫn đường (=12) có lộ trên ROM BYD không. (--es mode audiocue [--es secs 15])
            if (intent.getStringExtra("mode") == "audiocue") {
                val secs = intent.getStringExtra("secs")?.toIntOrNull() ?: 15
                out("===== AUDIO CUE (${secs}s) — usage dẫn đường có lộ không =====")
                val amgr = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
                val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
                val navCount = java.util.concurrent.atomic.AtomicInteger(0)
                val wasNav = booleanArrayOf(false)
                val cb = object : android.media.AudioManager.AudioPlaybackCallback() {
                    override fun onPlaybackConfigChanged(cfgs: MutableList<android.media.AudioPlaybackConfiguration>?) {
                        cfgs?.forEach { seen.add(it.audioAttributes.usage) }
                        val nav = cfgs?.any { it.audioAttributes.usage == 12 } ?: false
                        if (nav && !wasNav[0]) navCount.incrementAndGet()
                        wasNav[0] = nav
                    }
                }
                val h = android.os.Handler(android.os.Looper.getMainLooper())
                h.post { runCatching { amgr.registerAudioPlaybackCallback(cb, h) } }
                out("đang nghe ${secs}s... để GMaps đọc vài câu dẫn đường (lái hoặc chờ prompt).")
                repeat(secs) { try { Thread.sleep(1000) } catch (_: InterruptedException) {} }
                h.post { runCatching { amgr.unregisterAudioPlaybackCallback(cb) } }
                try { Thread.sleep(200) } catch (_: InterruptedException) {}
                out("usage đã thấy: " + seen.joinToString { "u$it" }.ifEmpty { "(im lặng)" })
                out("usage=12 (NAV) lộ: ${seen.contains(12)} · nav cue đếm: ${navCount.get()}")
                out("===== DONE ===== usage=12 CÓ -> dùng xung được; toàn UNKNOWN -> ROM ẩn, bỏ hướng audio.")
                return@Thread
            }
            // Chế độ remoteviews: bóc field ẩn noti GMaps gần nhất (inflate trên main thread). (--es mode remoteviews)
            if (intent.getStringExtra("mode") == "remoteviews") {
                out("===== REMOTEVIEWS INTROSPECTION — noti GMaps gần nhất =====")
                if (NavDiag.lastRaw == null) { out("chưa có noti thô — mở GMaps dẫn đường trước"); return@Thread }
                val latch = java.util.concurrent.CountDownLatch(1)
                val res = arrayOf("")
                runOnUiThread {
                    res[0] = runCatching { RemoteViewsModule.introspect(applicationContext) }.getOrElse { "lỗi: ${it.javaClass.simpleName}: ${it.message}" }
                    latch.countDown()
                }
                latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                out(res[0])
                out("===== DONE ===== có field cự-ly-mịn / làn / 'then...' nào ngoài title/text không?")
                return@Thread
            }
            // Chế độ navrealtime: CHỨNG MINH toán nội suy end-to-end (xe đứng yên vẫn test) — feed 2 anchor
            // (cùng maneuver) để KÍCH bộ ước lượng closingRate, rồi project từng nhịp, in closingRate + cự ly. (--es mode navrealtime [--es kmh 54])
            if (intent.getStringExtra("mode") == "navrealtime") {
                val kmh = intent.getStringExtra("kmh")?.toDoubleOrNull() ?: 54.0
                val mps = kmh / 3.6
                out("===== NAV REALTIME (nội suy headless) — 2 anchor để tính closingRate, giả ${kmh} km/h =====")
                com.byd.clusternav.TurnDistanceInterpolator.reset()
                // Anchor #1: 300m @ t=0 — mới 1 mốc nên CHƯA có closingRate (project sẽ fallback speed×0.93).
                com.byd.clusternav.TurnDistanceInterpolator.anchor(300, "test|rẽ phải", 0L)
                out("anchor#1: 300m @ t=0  closingRate=${"%.2f".format(com.byd.clusternav.TurnDistanceInterpolator.closingRate())} m/s (chưa có, cần 2 mốc → fallback)")
                // Anchor #2: 280m @ t=1s — CÙNG key → closingRate = (300-280)/1s = 20 m/s (≈72 km/h).
                com.byd.clusternav.TurnDistanceInterpolator.anchor(280, "test|rẽ phải", 1000L)
                out("anchor#2: 280m @ t=1s  closingRate=${"%.2f".format(com.byd.clusternav.TurnDistanceInterpolator.closingRate())} m/s (=(300-280)/1s; ưu tiên hơn tốc-độ-thô)")
                out("speed HAL thật bây giờ = ${"%.1f".format(com.byd.clusternav.SpeedProvider.mps() * 3.6)} km/h (đỗ P nên ~0)")
                out("project (trừ dần theo closingRate, clamp [0,1.2×${"%.1f".format(mps)}+3]):")
                for (i in 1..10) {
                    val now = 1000L + i * 1000L
                    val proj = com.byd.clusternav.TurnDistanceInterpolator.project(mps, now)
                    out("  t=${1 + i}s  → cụm nhận ${proj} m")
                }
                out("===== DONE ===== closingRate>0 → cự ly giảm theo TỐC-ĐỘ-TIẾP-CẬN (không phải speed thô), monotonic, chạm 0 không âm.")
                out("Lái thật: mở NavRealtimeModule xem anchor(noti thô) vs CỤM(nội suy) bám sát thực tế hơn.")
                return@Thread
            }
            // Chế độ accstatus: soi trạng thái booster accessibility (đã bật quyền? connected? đọc được gì?). (--es mode accstatus)
            if (intent.getStringExtra("mode") == "accstatus") {
                val now = android.os.SystemClock.elapsedRealtime()
                val src = com.byd.clusternav.modules.navaccess.NavAccessibilitySource
                val flat = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
                val enabled = flat.split(":").any { it.contains("NavAccessibilityService") && it.contains(packageName) }
                out("===== ACCESSIBILITY BOOSTER STATUS =====")
                out("quyền Hỗ trợ bật : $enabled")
                out("service connected: ${src.connected}")
                out("booster pref     : ${Prefs.accBooster(applicationContext)}")
                out("GMaps foreground : ${src.foreground(now)} (lastEvent ${if (src.lastEventAt > 0) "${now - src.lastEventAt}ms trước" else "chưa có"})")
                out("đọc màn fresh    : ${src.fresh(now)} · turn=${src.turnMeters}m · road='${src.road}'")
                out("info đáy         : '${src.bottomInfo}' · refines=${src.refines}")
                out("===== DONE ===== nếu enabled=false: mở module 'Booster đọc màn' → bấm mở Cài đặt Hỗ trợ.")
                return@Thread
            }
            out("===== ClusterNav AUTOTEST =====")
            out("app=$packageName · android=${Build.VERSION.SDK_INT} · model=${Build.MODEL}")
            out("notif-listener bật: ${notifConnected()}")
            out("nav enabled=${Prefs.enabled(applicationContext)} source=${Prefs.sourceMode(applicationContext)} marquee=${Prefs.marquee(applicationContext)}")
            val st = NavRepository.state
            out("NavState: active=${st.active} road='${st.road}' dist='${st.distance}' eta='${st.eta}' iconAmap=${st.maneuverIcon}")

            out("")
            out("----- SELF-TEST từng module (PASS=giữ, FAIL=xem chi tiết) -----")
            ModuleRegistry.MODULES.forEach { m ->
                val r = runCatching { m.selfTest(applicationContext) }
                    .getOrElse { SelfTest.fail("EXC ${it.javaClass.simpleName}: ${it.message}") }
                out("[${if (r.ok) "PASS" else "FAIL"}] ${m.title}")
                out("       ${r.detail}")
            }

            out("")
            out("----- RECON HAL (gửi em để wire listener đọc TPMS/telemetry) -----")
            halRecon(::out)

            out("")
            out("----- LIVE READ (getter trực tiếp — KHÔNG cần listener) -----")
            runCatching {
                val sys2 = BydHal.systemBypassContext()
                BydHal.device(BydHal.TYRE, sys2, BydHal.bypass(applicationContext))?.let { d ->
                    out("Tyre: " + BydHal.readGetters(d, listOf(
                        "getTyrePressureValue" to 1, "getTyrePressureValue" to 2, "getTyrePressureValue" to 3, "getTyrePressureValue" to 4,
                        "getTyreTemperatureState" to null, "getTyreSystemState" to null)).joinToString(" | "))
                }
                BydHal.device(BydHal.SPEED, sys2, BydHal.bypass(applicationContext))?.let { d ->
                    out("Speed: " + BydHal.readGetters(d, listOf("getCurrentSpeed" to null, "getSpeedFromGateway" to null, "getAccelerateValue" to null)).joinToString(" | "))
                }
                BydHal.device(BydHal.GEARBOX, sys2, BydHal.bypass(applicationContext))?.let { d ->
                    out("Gear: " + BydHal.readGetters(d, listOf("getCurrentGear" to null, "getGearboxState" to null, "getEPBState" to null)).joinToString(" | "))
                }
                BydHal.device(BydHal.CHARGING, sys2, BydHal.bypass(applicationContext))?.let { d ->
                    out("Charge: " + BydHal.readGetters(d, listOf("getChargingState" to null, "getChargingPower" to null, "getChargingCapacity" to null)).joinToString(" | "))
                }
                BydHal.device(BydHal.INSTRUMENT, sys2, BydHal.bypass(applicationContext))?.let { d ->
                    out("Instr: " + BydHal.readGetters(d, listOf(
                        "getBatteryPercent" to null, "getChargePercent" to null, "getTotalMileage" to null,
                        "getOdometerDisplay" to null, "getOutCarTemperature" to null, "getCurrentJourneyDriveMileage" to null,
                        "getTravelTime" to null, "getWheelPressure" to 1, "getWheelPressure" to 2,
                        "getWheelPressure" to 3, "getWheelPressure" to 4)).joinToString(" | "))
                }
                BydHal.device(BydHal.STATISTIC, sys2, BydHal.bypass(applicationContext))?.let { d ->
                    // SOC/range — tên getter chưa chắc, thử nhiều (xem recon Statistic để biết tên đúng).
                    out("Statistic: " + BydHal.readGetters(d, listOf(
                        "getSocBatteryPercentage" to null, "getEstimateSoc" to null, "getBatteryPercentage" to null,
                        "getElecDrivingRange" to null, "getFuelDrivingRange" to null, "getTotalMileage" to null,
                        "getBatteryHealthyIndex" to null, "getRemainingBatteryPower" to null)).joinToString(" | ").ifEmpty { "(getter không khớp — xem recon Statistic)" })
                }
            }.onFailure { out("live read lỗi: ${it.javaClass.simpleName}: ${it.message}") }

            out("")
            out("----- BƠM frame lên cụm (2 đường, NHÌN CỤM xem path nào render) -----")
            runCatching {
                ClusterBroadcaster.emit(applicationContext, NavState(
                    active = true, distance = "250 m", road = "Nguyễn Huệ",
                    maneuverText = "Rẽ phải vào Nguyễn Huệ", maneuverIcon = 3,
                    eta = "10:32 · 5.2 km · 8 phút", updatedAt = System.currentTimeMillis()))
                out("(1) BROADCAST (đã chứng minh) → cụm nên hiện: phải + 250m + 'Nguyễn Huệ'")
            }.onFailure { out("(1) broadcast lỗi: ${it.javaClass.simpleName}: ${it.message}") }
            Thread.sleep(2500)   // để mắt kịp thấy frame broadcast trước khi in-proc đè
            runCatching {
                val rc = BydHal.writeNavFrame(applicationContext, 2, 300, "InProc")
                out("(2) IN-PROC (KHÔNG dadb) → cụm nên đổi thành: trái + 300m + 'InProc'\n    rc: $rc")
                out("    → CỤM đổi sang 'InProc 300m' = in-proc GHI ĐƯỢC (bỏ dadb!). Vẫn 'Nguyễn Huệ' = in-proc không render.")
            }.onFailure { out("(2) in-proc lỗi: ${it.javaClass.simpleName}: ${it.message}") }

            val path = runCatching {
                File(getExternalFilesDir(null), "autotest-report.txt").apply { writeText(sb.toString()) }.absolutePath
            }.getOrElse { "ghi file lỗi: ${it.javaClass.simpleName} (dùng logcat -s CLNAV_AUTO)" }

            out("")
            out("===== DONE ===== report: $path")
        }.start()
    }

    private fun notifConnected(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.split(":").any { it.contains(packageName) }
    }

    private fun halRecon(out: (String) -> Unit) {
        runCatching { BydHal.exemptHiddenApis() }
        val sys = BydHal.systemBypassContext()
        out("systemBypassContext dựng được: ${sys != null}")
        listOf(
            "Instrument" to BydHal.INSTRUMENT, "Setting" to BydHal.SETTING, "Tyre" to BydHal.TYRE,
            "Speed" to BydHal.SPEED, "Gearbox" to BydHal.GEARBOX, "Charging" to BydHal.CHARGING,
            "Energy" to BydHal.ENERGY, "Statistic" to BydHal.STATISTIC,
        ).forEach { (label, fqn) ->
            val dev = BydHal.device(fqn, sys, BydHal.bypass(applicationContext))
            if (dev == null) { out("· $label: NULL (getInstance bị chặn)"); return@forEach }
            out("· $label: OK · syncGet(int[])=${BydHal.hasSyncGet(dev)}")
            out("    methods: " + BydHal.methods(dev, "get", "set", "register", "add", "remove", "on", "listen", "subscribe", "enable").joinToString(" | "))
        }
        out("· feature-id TYRE/PRESSURE: " + BydHal.featureIdsMatching("TYRE", "TIRE", "PRESSURE").joinToString { it.first })
        out("· feature-id SPEED/GEAR/SOC/RANGE: " + BydHal.featureIdsMatching("SPEED", "GEAR", "SOC", "RANGE", "BATTERY", "MILEAGE").joinToString { it.first })
    }

    private companion object { const val TAG = "CLNAV_AUTO" }
}
