package com.byd.clusternav.modules.deadreckon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import com.byd.clusternav.SpeedProvider
import com.byd.clusternav.modules.hal.BydHal
import com.byd.clusternav.modules.mockloc.MockLoc

/**
 * Service NỀN dead-reckoning vá mất-GPS-trong-hầm. Máy trạng thái REAL↔DEAD_RECKON, CHẠY TRÊN HandlerThread
 * riêng (không đụng Main Looper → không ANR). Callback gyro/gnss/loc cũng đăng ký trên thread đó.
 *
 *  REAL: không mock; lắng GPS thật (lọc isFromMockProvider) lưu lastFix + bearing-khi-di-chuyển; raw GNSS đếm usedInFix.
 *  Vào DR khi: (usedInFix==0 / acc>50m / fix stale>3s) LIÊN TỤC 1.5s (hysteresis) VÀ có lastFix + heading hợp lệ → seed, bật mock, tích phân bơm 5Hz.
 *  Thoát DR khi: usedInFix>=4 ổn định 2s (hysteresis 3↔4) HOẶC failsafe quá MAX_DR (chống kẹt mock vĩnh viễn).
 * Heading: gyro Z (chỉ khi useGyro BẬT + đã xác minh trục bằng sensorscan; mặc định TẮT cho an toàn) propagate từ
 *  bearing GPS cuối; không gyro → giữ bearing thẳng. KHÔNG vào DR nếu chưa từng có bearing (gyro không bootstrap được hướng tuyệt đối).
 *
 * KHÔNG đụng nav path cụm (broadcast) — chỉ feed GMaps app. XOÁ: xoá modules/deadreckon/ + <service> Manifest + dòng Registry.
 */
class DeadReckonService : Service() {

    private var worker: HandlerThread? = null
    private var wh: Handler? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null   // giữ CPU thức khi màn tắt (tick postDelayed = uptimeMillis, dừng khi deep-sleep)
    private var sm: SensorManager? = null
    private var lm: LocationManager? = null
    private var hasGyro = false
    @Volatile private var bodyDev: Any? = null   // BYDAutoBodyworkDevice cho getSteeringWheelValue(1) = góc lái

    @Volatile private var gyroZ = 0f
    private var gyroBias = 0f

    @Volatile private var fixLat = 0.0
    @Volatile private var fixLon = 0.0
    @Volatile private var fixBearing = Float.NaN
    @Volatile private var fixAcc = 999f
    @Volatile private var fixAt = 0L
    @Volatile private var hasFix = false
    @Volatile private var lastGoodBearing = -1.0     // sentinel: chưa từng có bearing khi di chuyển
    // #2: ring N vị trí fix tốt gần nhất (đang chạy) → hồi quy hướng seed cho DR (bền hơn 1 bearing GPS cuối, vốn nhiễu).
    private val trkLat = DoubleArray(TRACK_N); private val trkLon = DoubleArray(TRACK_N)
    @Volatile private var trkCount = 0; @Volatile private var trkIdx = 0

    @Volatile private var usedInFix = 0
    @Volatile private var sats = 0
    @Volatile private var gnssEverUsed = false   // callback ĐÃ TỪNG báo usedInFix≥1 → chỉ khi đó mới tin usedInFix==0 là 'mất'

    private var stateReal = true
    private var drLat = 0.0
    private var drLon = 0.0
    private var heading = 0.0
    private var lastTickNs = 0L
    private var lostSince = 0L        // hysteresis VÀO DR
    private var gpsBackSince = 0L     // hysteresis RA DR
    private var drStartedAt = 0L      // failsafe chống kẹt mock
    private var lastMockTry = 0L      // backoff khi MockLoc.start lỗi (chống spam addTestProvider)
    // ── PEEK: trên head unit này, BẬT mock GPS làm ĐÓNG BĂNG GnssStatus (usedInFix=0) + chặn fix thật → recovery mù
    //    → kẹt mock cả chuyến (đo được 53' trôi 10km). Vá: định kỳ TẠM GỠ mock vài giây cho GPS thật/GnssStatus sống
    //    lại → phát hiện GPS về (fix thật hoặc usedInFix≥4) → nhả hẳn; chưa về → mock lại, tiếp tục.
    private var peekStartedAt = 0L    // !=0 = đang trong cửa peek (mock đã tạm gỡ)
    private var lastPeekAt = 0L       // lần peek gần nhất (để giãn cách)
    private var calPrevBearing = Float.NaN   // bearing GPS lần calib trước (tự hiệu chỉnh tỉ số lái)
    private var calPrevAt = 0L
    private var flipVote = 0          // bỏ phiếu dấu lái: >0 = cần đảo dấu (tự dò khi calib)

    // ── SEED COLD-START: vị trí LƯU lần trước (sống qua tắt máy) để mở máy trong hầm KHÔNG có GPS vẫn có toạ độ ──
    @Volatile private var savedLat = 0.0
    @Volatile private var savedLon = 0.0
    @Volatile private var savedBearing = -1.0    // độ; <0 = chưa biết
    @Volatile private var savedValid = false     // có vị trí lưu hợp lệ + chưa quá cũ
    @Volatile private var savedAgeMs = 0L        // tuổi seed lúc nạp → cold-seed cũ thì đẩy accuracy lớn (GMaps hiện vòng mờ, không chấm chắc ở nơi cũ)
    private var coldSeeded = false               // đã seed cold-start (mock từ vị trí lưu) THÀNH CÔNG — 1 lần/phiên, reset khi GPS về
    private var coldSeedStationary = false        // B5: COLD_SEED mà KHÔNG có bearing → giữ ĐỨNG YÊN (đừng tích phân đi Bắc)
    private var lastSaveAt = 0L                  // throttle ghi vị trí (SAVE_INTERVAL_MS)
    // Snapshot vị trí NGUYÊN TỬ (1 volatile ref) để onDestroy (MAIN thread) đọc an toàn — tránh torn-read
    // lat/lon (2 field volatile riêng, ghi bởi worker thread trong tick → có thể đọc lat mới + lon cũ).
    @Volatile private var posSnap: PosSnap? = null
    private class PosSnap(val lat: Double, val lon: Double, val brg: Double)

    // ── SHADOW DR: chạy song song GPS (KHÔNG mock) để ĐO sai số mô hình + ghi log ──
    private var shSeeded = false
    private var shLat = 0.0; private var shLon = 0.0; private var shHeading = 0.0
    private var lastLoggedFixAt = 0L
    private var prevLogBearing = Float.NaN
    private var prevLogAt = 0L
    private var drLogTick = 0
    private var logWriter: java.io.BufferedWriter? = null

    private val gyroLis = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) { gyroZ = e.values[2] }   // Z = yaw khi nằm ngang (sensorscan xác minh)
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }
    private val gnssCb = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(s: GnssStatus) {
            var u = 0
            for (i in 0 until s.satelliteCount) if (s.usedInFix(i)) u++
            usedInFix = u; sats = s.satelliteCount
            if (u >= 1) gnssEverUsed = true   // callback SỐNG + có vệ tinh dùng-trong-fix → giờ mới tin usedInFix
        }
    }
    private val locLis = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            // bỏ chính mock của mình (chống feedback). isMock (API31+) thay isFromMockProvider (deprecated từ 31).
            val fromMock = if (Build.VERSION.SDK_INT >= 31) loc.isMock else @Suppress("DEPRECATION") loc.isFromMockProvider
            if (fromMock) return
            fixLat = loc.latitude; fixLon = loc.longitude; fixAcc = loc.accuracy
            fixAt = SystemClock.elapsedRealtime(); hasFix = true
            if (loc.hasBearing() && loc.speed > 1.5f) { fixBearing = loc.bearing; lastGoodBearing = loc.bearing.toDouble() }
            // #2: gom vị trí fix TỐT (đang chạy) vào ring để hồi quy HƯỚNG — seed DR bền hơn 1 bearing GPS cuối.
            if (loc.speed > 1.5f && loc.accuracy < 30f) {
                trkLat[trkIdx] = loc.latitude; trkLon[trkIdx] = loc.longitude
                trkIdx = (trkIdx + 1) % TRACK_N; if (trkCount < TRACK_N) trkCount++
            } else if (loc.speed < 0.5f) {
                trkCount = 0; trkIdx = 0   // #2-fix: DỪNG → xóa ring (hết stale) → trackHeading()=NaN → fallback bearing tươi
            }
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @Volatile private var stopping = false   // R6: dừng loop + dọn trên worker thread (không đua với tick đang chạy)
    private val loop = object : Runnable {
        override fun run() { if (stopping) return; runCatching { tick() }; if (!stopping) wh?.postDelayed(this, 200) }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    // START_STICKY: bị OOM-kill (đỗ lâu, vd chơi golf) thì Android TỰ dựng lại → service sống suốt chuyến mà KHÔNG cần
    // mở app → lưu vị trí dọc đường về → vào hầm là DEAD_RECKON từ gần-nhà (đúng), KHÔNG cold-seed vị trí cũ (Himlam).
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        // #1 FIX (chống ZOMBIE + R7 crash API34): CHECK QUYỀN TRƯỚC khi startForeground. Trên API34+, startForeground
        // với foregroundServiceType=location NÉM SecurityException nếu THIẾU quyền Location → thứ tự cũ (startForeground
        // trước) crash TRƯỚC khi kịp chạy nhánh stopSelf graceful. Service không tự xin quyền được (không phải Activity)
        // → thoát sạch + ghi lỗi để MainActivity gánh việc xin (đừng để 'running=true' giả).
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            DeadReckonState.lastError = "thiếu quyền Vị trí — DR chưa chạy (mở app cấp quyền rồi bật lại)"
            stopSelf(); return
        }
        try { startForeground(NOTIF_ID, buildNotif()) }
        catch (e: Exception) { DeadReckonState.lastError = "startForeground lỗi: ${e.message}"; stopSelf(); return }
        // WAKE_LOCK partial: tick() dùng Handler.postDelayed (uptimeMillis) → dừng khi CPU deep-sleep. HU có thể ngủ CPU
        // lúc màn tắt giữa hầm dài → DR đóng băng. Giữ suốt đời service (HU luôn có nguồn, không lo pin), nhả onDestroy;
        // process bị kill thì OS tự nhả (wakelock gắn theo process).
        wakeLock = (getSystemService(POWER_SERVICE) as? android.os.PowerManager)
            ?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "clusternav:deadreckon")
            ?.apply { setReferenceCounted(false); runCatching { acquire() } }
        // #1 FIX: TỰ CẤP quyền mock qua dadb (mock_location reset DENY sau cài lại, app không tự xin runtime được).
        // Chạy nền, idempotent; grant thường xong trong ~1-2s → trước khi vào hầm cần mock. Mock lỗi lần đầu thì DR
        // retry (backoff 2s) — lúc đó grant đã tới. Popup Allow USB chưa bấm → nuốt, user bật tay được.
        MockLoc.selfGrant(applicationContext)
        sm = getSystemService(SENSOR_SERVICE) as? SensorManager
        lm = getSystemService(LOCATION_SERVICE) as? LocationManager
        if (lm == null) { DeadReckonState.lastError = "không có LocationManager"; stopSelf(); return }
        // ★ dọn test-provider MỒ CÔI từ phiên bị KILL giữa lúc mock (OOM/swipe/crash → onDestroy KHÔNG chạy). Nếu không,
        // GPS_PROVIDER kẹt là mock chết (enabled, không ai push) → GMaps + MỌI app mất GPS thật tới tận reboot. stop() an toàn nếu không có mock.
        runCatching { MockLoc.stop(applicationContext) }
        val t = HandlerThread("deadreckon").also { it.start() }
        worker = t
        val h = Handler(t.looper); wh = h
        val gyro = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        hasGyro = gyro != null
        DeadReckonState.headingSrc = if (hasGyro) "gyro Z (xác minh trục!)" else "GPS bearing (thẳng)"
        gyro?.let { sm?.registerListener(gyroLis, it, SensorManager.SENSOR_DELAY_GAME, h) }
        runCatching {
            lm?.registerGnssStatusCallback(gnssCb, h)
            lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locLis, t.looper)
        }.onFailure { DeadReckonState.lastError = "requestLocationUpdates (thiếu quyền Location?): ${it.message}" }
        h.post { runCatching { SpeedProvider.mps() } }   // prime device cache off-main
        h.post { runCatching { bodyDev = BydHal.device(BydHal.BODYWORK, BydHal.systemBypassContext(), BydHal.bypass(applicationContext)) } }
        // NẠP vị trí LƯU lần trước (seed cold-start) — hợp lệ nếu chưa quá cũ (xe có thể đã bị di chuyển khi tắt app).
        h.post {
            runCatching {
                val s = LastLocationStore.load(applicationContext, System.currentTimeMillis())
                // ageMs ÂM = đồng hồ máy TỤT (cold boot dưới hầm chưa có giờ GPS/mạng) → seed vẫn mới → VẪN NHẬN.
                // Chỉ bỏ khi CŨ hơn 7 ngày (xe có thể đã bị di chuyển khi app tắt). (BUG4: đừng hard-reject age<0.)
                if (s != null && s.ageMs <= MAX_SAVED_AGE_MS) {
                    savedLat = s.lat; savedLon = s.lon; savedBearing = s.bearingDeg; savedValid = true
                    savedAgeMs = s.ageMs.coerceAtLeast(0L)   // age<0 (đồng hồ tụt) coi như MỚI
                    DeadReckonState.savedLoc = if (s.ageMs < 0) "có (đồng hồ tụt)" else "có (${s.ageMs / 60000} phút trước)"
                } else DeadReckonState.savedLoc = if (s == null) "chưa có" else "quá cũ (${s.ageMs / 3600000}h)"
            }
        }
        // mở file log CSV (so shadow-DR vs GPS) — TRÊN WORKER (khỏi disk-I/O trên main) + rotate giữ 5 file mới nhất.
        h.post {
            runCatching {
                val dir = getExternalFilesDir(null)
                rotateLogs(dir)
                val f = java.io.File(dir, "dr_log_${System.currentTimeMillis()}.csv")
                logWriter = f.bufferedWriter().also {
                    it.appendLine("t_ms,state,used,sats,acc_m,gpsLat,gpsLon,gpsBrg,spd_kmh,posLat,posLon,hdg,posErr_m,hdgErr_deg,gpsYaw_dps,steerYaw_dps,ratioCal,calN,flip")
                }
                DeadReckonState.logPath = f.absolutePath
            }.onFailure { DeadReckonState.lastError = "mở log lỗi: ${it.message}" }
        }
        lastTickNs = SystemClock.elapsedRealtimeNanos()
        DeadReckonState.running = true
        h.post(loop)
    }

    override fun onDestroy() {
        // R6: dọn TRÊN worker thread (sau tick đang chạy) để không đua với tick (lưu vị trí + đóng logWriter khi
        // tick đang ghi CSV → IOException/vỡ dòng). stopping=true chặn tick repost; teardown post vào cuối hàng đợi
        // (Handler xử tuần tự → chạy sau tick hiện tại), rồi quitSafely.
        stopping = true
        val h = wh
        if (h != null) {
            h.removeCallbacks(loop)
            h.post {
                runCatching { sm?.unregisterListener(gyroLis) }
                runCatching { lm?.unregisterGnssStatusCallback(gnssCb) }
                runCatching { lm?.removeUpdates(locLis) }
                runCatching { MockLoc.stop(applicationContext) }
                // BUG3: đọc SNAPSHOT nguyên tử (posSnap = 1 volatile ref, worker ghi trọn cặp) — không torn-read.
                runCatching { posSnap?.let { LastLocationStore.save(applicationContext, it.lat, it.lon, it.brg, System.currentTimeMillis()) } }
                runCatching { logWriter?.flush(); logWriter?.close(); logWriter = null }
                runCatching { worker?.quitSafely() }
            }
        } else {
            runCatching { MockLoc.stop(applicationContext) }
        }
        runCatching { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }
        DeadReckonState.running = false; DeadReckonState.mocking = false; DeadReckonState.state = "REAL"
        super.onDestroy()
    }

    private fun tick() {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val dt = ((nowNs - lastTickNs) / 1e9).coerceIn(0.0, 5.0); lastTickNs = nowNs   // cap 5s (GC pause) — heading constant trong tick nên straight-segment đúng
        val now = SystemClock.elapsedRealtime()
        val speed = SpeedProvider.mps()
        if (speed < 0.5) gyroBias += 0.02f * (gyroZ - gyroBias)
        val steerReady = DeadReckonState.steerCalSamples >= MIN_CAL_SAMPLES
        val yawRate = when {
            DeadReckonState.useSteer && steerReady -> steerYawRate(speed)                     // chỉ bám cua khi đã calib đủ (an toàn)
            DeadReckonState.useGyro && hasGyro -> (gyroZ - gyroBias).toDouble().coerceIn(-1.2, 1.2)
            else -> 0.0                                                                       // DR thẳng (an toàn): chưa calib / không bật
        }

        if (stateReal) {
            calibrateSteerRatio(speed, now)          // còn GPS tốt → tự hiệu chỉnh tỉ số lái khi vào cua
            // ★ FIX "mất tín hiệu đường thường" (review v0.18): usedInFix==0 MỘT MÌNH KHÔNG đủ coi là mất — head-unit
            // OEM thường KHÔNG set usedInFix dù fix GPS vẫn tốt → usedInFix==0 KINH NIÊN → trước đây vào DR OAN, mock
            // đè GPS tốt. 'lost' giờ dựa CHÍNH vào fix thật (stale/acc); usedInFix chỉ là tín-hiệu-PHỤ, chỉ tin sau khi
            // callback ĐÃ chứng minh sống (gnssEverUsed) VÀ acc đã chớm tệ (>30m).
            val fixStale = !hasFix || now - fixAt > 4000
            val lost = fixStale || fixAcc > 75f || (gnssEverUsed && usedInFix == 0 && fixAcc > 30f)
            // VETO cứng: đang có fix thật TƯƠI (<2s) + CHÍNH XÁC (<30m) → KHÔNG bao giờ 'lost' (không để mock đè GPS đang tốt).
            val goodRealFix = hasFix && now - fixAt < 2000 && fixAcc < 30f
            lostSince = if (lost && !goodRealFix) (if (lostSince == 0L) now else lostSince) else 0L
            val haveHeading = lastGoodBearing >= 0.0 || !fixBearing.isNaN()
            val sustained = lostSince != 0L && now - lostSince > 1500
            val ready = sustained && now - lastMockTry > 2000
            if (hasFix && ready && haveHeading) {
                // DR bình thường: đã CÓ fix thật phiên này rồi mất giữa đường → seed từ fix vừa mất.
                lastMockTry = now
                drLat = fixLat; drLon = fixLon
                coldSeedStationary = false   // B5-leak: DR từ FIX THẬT luôn phải tích phân (chống rò cờ đứng-yên từ cold-seed hụt)
                // #2: ưu tiên hướng HỒI QUY track (bền), fallback bearing GPS cuối, rồi lastGoodBearing.
                heading = trackHeading().let { if (!it.isNaN()) it else if (!fixBearing.isNaN()) fixBearing.toDouble() else lastGoodBearing }
                enterMock(now, "DEAD_RECKON")
            } else if (!hasFix && savedValid && !coldSeeded && ready && speed < 2.0) {
                // ★ CHỈ cold-seed khi xe ĐỨNG YÊN (speed<2): cold-seed = "mở máy TẠI vị trí lưu, trong hầm". Nếu app
                // restart lúc ĐANG CHẠY (đã rời vị trí lưu) mà cold-seed → đóng băng GMaps ở toạ độ nhà cũ = sai bét.
                // SEED COLD-START: CHƯA TỪNG có GPS phiên này (mới mở máy, đang ở hầm/bãi ngầm) nhưng có vị trí LƯU
                // lần trước → xe vẫn đỗ đúng chỗ đó → mock vị trí lưu để GMaps có toạ độ hiện tại + cho DR chỗ bắt đầu.
                // GPS thật quay lại (ra khỏi hầm) → recovery bên dưới tự nhả mock, vị trí tự chỉnh.
                lastMockTry = now
                drLat = savedLat; drLon = savedLon
                heading = if (savedBearing >= 0.0) savedBearing else 0.0
                if (lastGoodBearing < 0.0 && savedBearing >= 0.0) lastGoodBearing = savedBearing
                // #fix ("trôi 10km, nhảy loạn"): COLD_SEED LUÔN ĐỨNG YÊN — cold-seed không có fix/heading tin cậy phiên
                // này, tích phân từ seed cũ chỉ tạo rác (đo được: dead-reckon 10km sai bét). Giữ nguyên vị trí seed;
                // peek/GPS-về sẽ nhả và snap sang thật. (Trước: chỉ đứng yên khi thiếu bearing → có bearing là trôi.)
                coldSeedStationary = true
                enterMock(now, "COLD_SEED")   // coldSeeded set BÊN TRONG enterMock (chỉ khi mock OK) → mock lỗi còn retry
            }
        } else {
            // LUÔN cập nhật heading + tích phân vị trí (để resume ĐÚNG chỗ sau peek). COLD_SEED = ĐỨNG YÊN
            // (fix "trôi 10km": cold-seed không có fix/heading tin cậy → tích phân là rác; giữ nguyên seed, chờ GPS về).
            heading += Math.toDegrees(yawRate) * dt
            if (heading.isNaN() || heading.isInfinite()) heading = if (lastGoodBearing >= 0.0) lastGoodBearing else 0.0
            heading = (heading % 360 + 360) % 360
            val dist = if (coldSeedStationary) 0.0 else speed * SPEEDO_CORRECTION * dt   // bù over-read đồng hồ ~5-8%
            val hRad = Math.toRadians(heading)
            drLat += dist * Math.cos(hRad) / 111320.0
            drLon += dist * Math.sin(hRad) / (111320.0 * cosLatGuard(drLat))

            if (peekStartedAt != 0L) {
                // ── ĐANG PEEK (mock tạm gỡ) ── chờ GPS thật về. KHÔNG push mock (để provider trả fix THẬT + GnssStatus sống).
                // R#1: CHỈ nhả khi có FIX THẬT (không nhả theo mỗi usedInFix — 1 mẫu vệ tinh nhiễu sẽ nhả về null-island
                //      0,0 cho cold-seed). usedInFix≥4 = GPS CHỚM về → GIA HẠN cửa peek tới MAX chờ fix thật tới.
                // COLD_SEED: BẤT KỲ fix thật nào (acc<75, = ngưỡng 'lost') cũng hơn seed cũ đông cứng → nhả. DEAD_RECKON:
                // đòi chặt hơn (acc<50) vì vị trí nội suy từ fix thật có thể tốt hơn 1 fix yếu.
                val relAcc = if (DeadReckonState.state == "COLD_SEED") 75f else 50f
                val realBack = hasFix && fixAt > peekStartedAt && fixAcc < relAcc
                val peekEl = now - peekStartedAt
                val remockNow = peekEl > PEEK_WINDOW_MAX_MS || (peekEl > PEEK_WINDOW_MS && usedInFix < 4)
                when {
                    realBack -> {                                      // GPS THẬT ĐÃ VỀ → nhả HẲN (mock đang tắt sẵn)
                        stateReal = true; peekStartedAt = 0L; gpsBackSince = 0L; lostSince = 0L
                        coldSeeded = false; coldSeedStationary = false
                        DeadReckonState.state = "REAL"; DeadReckonState.mocking = false
                    }
                    remockNow && now - lastMockTry > 2000L -> {         // hết cửa, vẫn mất → mock LẠI (backoff 2s chống spam)
                        lastMockTry = now
                        if (MockLoc.start(applicationContext).isEmpty()) { DeadReckonState.mocking = true; peekStartedAt = 0L; lastPeekAt = now }
                        // R#2: start LỖI → GIỮ peekStartedAt (còn trong peek) → tự retry start sau 2s (KHÔNG rơi ra 20s);
                        //      realBack vẫn kiểm mỗi tick; selfGrant đã kích trong MockLoc.start().
                    }
                }
            } else {
                // ── ĐANG MOCK bình thường ──
                // COLD_SEED: accuracy TĂNG theo tuổi seed → seed cũ (vd Himlam vài giờ) hiện VÒNG MỜ, GMaps không tin là
                // vị trí chắc. Seed mới (vài phút) vẫn ~50m dùng được ("bạn đang ở nhà"). DR-từ-fix-thật giữ 6m.
                val acc = if (coldSeedStationary) (50f + (savedAgeMs / 60000f) * 20f).coerceIn(50f, 600f) else 6f
                MockLoc.push(applicationContext, drLat, drLon, heading, speed, acc)
                // recovery "cổ điển" (ăn trên head unit KHÔNG đóng băng GnssStatus) — giữ làm bonus; head unit này băng nên
                // đường THẬT là PEEK bên dưới.
                if (usedInFix >= 4) { if (gpsBackSince == 0L) gpsBackSince = now } else if (usedInFix < 3) gpsBackSince = 0L
                val backAcc = if (DeadReckonState.state == "COLD_SEED") 50f else 30f
                val realFixBack = hasFix && fixAt > drStartedAt && now - fixAt < 1500 && fixAcc < backAcc
                val recovered = realFixBack || (gpsBackSince != 0L && now - gpsBackSince > GPS_BACK_MS)
                // R#3: failsafe KHÔNG áp COLD_SEED (giữ exempt). Cold-seed nay ĐỨNG YÊN (0 drift) + peek dò GPS-về mỗi
                //      20s → không còn kẹt/trôi 53' như cũ; áp failsafe cho cold-seed chỉ gây thrash nhả↔reseed mỗi 5'.
                //      DEAD_RECKON (có tích phân → trôi) VẪN giữ failsafe làm backstop.
                val failsafe = DeadReckonState.state != "COLD_SEED" && now - drStartedAt > MAX_DR_MS
                when {
                    recovered || failsafe -> {
                        runCatching { MockLoc.stop(applicationContext) }
                        stateReal = true; gpsBackSince = 0L; lostSince = 0L
                        coldSeeded = false; coldSeedStationary = false
                        DeadReckonState.state = "REAL"; DeadReckonState.mocking = false
                        if (failsafe && !recovered) DeadReckonState.lastError = "DR failsafe timeout (gỡ mock an toàn)"
                    }
                    now - lastPeekAt > PEEK_INTERVAL_MS -> {           // tới hạn → BẮT ĐẦU peek (tạm gỡ mock để dò GPS thật)
                        runCatching { MockLoc.stop(applicationContext) }
                        DeadReckonState.mocking = false
                        peekStartedAt = now
                    }
                }
            }
        }
        // ── SHADOW DR (song song, KHÔNG mock) + GHI LOG so sánh với GPS ──
        if (shSeeded) {
            shHeading += Math.toDegrees(yawRate) * dt
            shHeading = (shHeading % 360 + 360) % 360
            val sd = speed * SPEEDO_CORRECTION * dt
            val shr = Math.toRadians(shHeading)
            shLat += sd * Math.cos(shr) / 111320.0
            shLon += sd * Math.sin(shr) / (111320.0 * cosLatGuard(shLat))
        }
        val steerYawDps = Math.toDegrees(steerYawRate(speed))   // mô hình lái (log kể cả khi chưa gate)
        if (hasFix && fixAt != lastLoggedFixAt) {
            val gpsYawDps = if (!fixBearing.isNaN() && !prevLogBearing.isNaN() && prevLogAt != 0L) {
                var db = (fixBearing - prevLogBearing).toDouble(); while (db > 180) db -= 360; while (db < -180) db += 360
                val pdt = (fixAt - prevLogAt) / 1000.0; if (pdt > 0.05) db / pdt else 0.0
            } else 0.0
            val posErr = if (shSeeded) haversine(shLat, shLon, fixLat, fixLon) else -1.0
            val hdgErr = if (shSeeded && !fixBearing.isNaN()) angDiff(shHeading, fixBearing.toDouble()) else -1.0
            logRow(now, posErr, hdgErr, gpsYawDps, steerYawDps, speed)
            // reseed shadow từ GPS → đo sai số DỰ ĐOÁN mỗi khoảng cập nhật GPS (~1s)
            shLat = fixLat; shLon = fixLon
            if (!fixBearing.isNaN()) shHeading = fixBearing.toDouble() else if (lastGoodBearing >= 0.0) shHeading = lastGoodBearing
            shSeeded = true
            prevLogBearing = fixBearing; prevLogAt = fixAt; lastLoggedFixAt = fixAt
        } else if (!stateReal && ++drLogTick % 5 == 0) {
            logRow(now, -1.0, -1.0, 0.0, steerYawDps, speed)   // trong hầm: không có GPS truth, log vị trí DR ~1Hz
        }

        DeadReckonState.lat = if (stateReal) fixLat else drLat
        DeadReckonState.lon = if (stateReal) fixLon else drLon
        DeadReckonState.headingDeg = heading
        DeadReckonState.speedMps = speed
        DeadReckonState.usedInFix = usedInFix
        DeadReckonState.sats = sats

        // LƯU vị trí định kỳ (survive tắt máy → seed cold-start lần sau). CHỈ vị trí NEO VÀO FIX THẬT: real-fix HOẶC
        // DR-từ-fix-thật (cả hai đều hasFix=true; DR entry đòi hasFix). KHÔNG lưu vị trí COLD_SEED (hasFix=false) →
        // seed sai KHÔNG tự nuôi mình bằng timestamp mới → vẫn age-out được sau 7 ngày (TUNING A).
        // R#4: KHÔNG lưu vị trí COLD_SEED (coldSeedStationary=true) — 1 fix 50-75m lọt lúc peek có thể latch hasFix mà
        // KHÔNG nhả (realBack đòi <50m) → nếu lưu, seed CŨ tự làm mới timestamp = không age-out được (phá TUNING A).
        // Chỉ lưu khi neo vào fix THẬT: REAL / DR-từ-fix-thật (cả hai coldSeedStationary=false).
        if (hasFix && !coldSeedStationary && (Math.abs(DeadReckonState.lat) > 1e-4 || Math.abs(DeadReckonState.lon) > 1e-4)) {
            val pBrg = if (lastGoodBearing >= 0.0) lastGoodBearing
                       else if (!fixBearing.isNaN()) fixBearing.toDouble() else -1.0
            posSnap = PosSnap(DeadReckonState.lat, DeadReckonState.lon, pBrg)   // snapshot nguyên tử cho onDestroy
            if (now - lastSaveAt > SAVE_INTERVAL_MS) {
                lastSaveAt = now
                runCatching { LastLocationStore.save(applicationContext, DeadReckonState.lat, DeadReckonState.lon, pBrg, System.currentTimeMillis()) }
            }
        }
    }

    /** Bật mock + chuyển sang DR/COLD_SEED. Ghi lỗi vào DeadReckonState nếu MockLoc.start thất bại (thiếu quyền mock). */
    private fun enterMock(now: Long, stateName: String) {
        val err = MockLoc.start(applicationContext)
        if (err.isEmpty()) {
            stateReal = false; drStartedAt = now; gpsBackSince = 0L
            lastPeekAt = now; peekStartedAt = 0L   // peek đầu tiên sau PEEK_INTERVAL kể từ lúc vào mock
            if (stateName == "COLD_SEED") coldSeeded = true   // BUG1: latch CHỈ khi mock THÀNH CÔNG (mock lỗi → còn retry lần tick sau)
            DeadReckonState.drEnterCount++; DeadReckonState.state = stateName
            DeadReckonState.mocking = true; DeadReckonState.lastError = ""
        } else {
            // enterMock FAIL (mock chưa cấp quyền…) → RESET coldSeedStationary=false. Cold-seed entry đã set nó =true
            // TRƯỚC khi gọi đây; nếu để rò, nó dính vào REAL → chặn vĩnh viễn việc lưu vị trí (save-gate !coldSeedStationary).
            DeadReckonState.lastError = err; coldSeedStationary = false
        }
    }

    /** #2: hướng seed từ HỒI QUY track (bearing điểm cũ nhất→mới nhất trong ring, nếu đã đi đủ xa). NaN nếu chưa đủ. */
    private fun trackHeading(): Double {
        if (trkCount < 3) return Double.NaN
        val newest = (trkIdx - 1 + TRACK_N) % TRACK_N
        val oldest = if (trkCount < TRACK_N) 0 else trkIdx
        if (haversine(trkLat[oldest], trkLon[oldest], trkLat[newest], trkLon[newest]) < 8.0) return Double.NaN
        val dLon = Math.toRadians(trkLon[newest] - trkLon[oldest])
        val y = Math.sin(dLon) * Math.cos(Math.toRadians(trkLat[newest]))
        val x = Math.cos(Math.toRadians(trkLat[oldest])) * Math.sin(Math.toRadians(trkLat[newest])) -
            Math.sin(Math.toRadians(trkLat[oldest])) * Math.cos(Math.toRadians(trkLat[newest])) * Math.cos(dLon)
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360
    }

    /** cos(lat) chặn dưới để tích phân kinh độ không chia ~0 gần cực (blowup NaN/inf). VN không chạm nhưng rẻ. */
    private fun cosLatGuard(latDeg: Double): Double {
        val c = Math.cos(Math.toRadians(latDeg))
        return if (Math.abs(c) < 1e-6) 1e-6 else c
    }

    /** Giữ 5 file dr_log_*.csv mới nhất, xoá phần cũ (chống phình getExternalFilesDir theo thời gian). */
    private fun rotateLogs(dir: java.io.File?) {
        dir ?: return
        runCatching {
            dir.listFiles { f -> f.name.startsWith("dr_log_") && f.name.endsWith(".csv") }
                ?.sortedByDescending { it.name }
                ?.drop(4)                          // giữ 4 cũ + 1 sắp tạo = 5
                ?.forEach { runCatching { it.delete() } }
        }
    }

    /** Khoảng cách Haversine (m) giữa 2 toạ độ. */
    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).let { it * it }
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    /** Sai lệch góc tuyệt đối (độ) trong [0,180]. */
    private fun angDiff(a: Double, b: Double): Double {
        var d = Math.abs(a - b) % 360.0
        if (d > 180) d = 360 - d
        return d
    }

    /** Ghi 1 dòng CSV log (shadow-DR vs GPS). flush mỗi dòng (~1Hz). */
    private fun logRow(now: Long, posErr: Double, hdgErr: Double, gpsYaw: Double, steerYaw: Double, speed: Double) {
        val w = logWriter ?: return
        val pLat = if (stateReal) shLat else drLat
        val pLon = if (stateReal) shLon else drLon
        val pHdg = if (stateReal) shHeading else heading
        // LUÔN dùng Locale.US: nếu không, locale VN dùng dấu phẩy thập phân (10,792096) trùng dấu phân tách CSV → vỡ cột.
        val L = java.util.Locale.US
        fun f(fmt: String, v: Double) = String.format(L, fmt, v)
        runCatching {
            w.append("$now,${DeadReckonState.state},$usedInFix,$sats,${f("%.0f", fixAcc.toDouble())},")
            w.append("${f("%.6f", fixLat)},${f("%.6f", fixLon)},${if (fixBearing.isNaN()) "" else f("%.0f", fixBearing.toDouble())},${f("%.0f", speed * 3.6)},")
            w.append("${f("%.6f", pLat)},${f("%.6f", pLon)},${f("%.0f", pHdg)},")
            w.append("${if (posErr < 0) "" else f("%.1f", posErr)},${if (hdgErr < 0) "" else f("%.1f", hdgErr)},")
            w.appendLine("${f("%.1f", gpsYaw)},${f("%.1f", steerYaw)},${f("%.1f", DeadReckonState.steerRatioCal)},${DeadReckonState.steerCalSamples},${DeadReckonState.steerFlip}")
            w.flush()
        }
    }

    /** Yaw-rate (rad/s) từ GÓC LÁI HAL + tốc độ (bicycle-model). 0 nếu không đọc được/đứng yên. */
    private fun steerYawRate(speed: Double): Double {
        val d = bodyDev ?: return 0.0
        val raw = runCatching { BydHal.callGetter(d, "getSteeringWheelValue", 1)?.toDoubleOrNull() }.getOrNull() ?: return 0.0
        if (Math.abs(raw) > 1000.0) return 0.0   // sentinel -2.14e9 / vô hiệu
        val delta = Math.toRadians(raw / DeadReckonState.steerRatioCal)   // góc bánh = góc vô-lăng / tỉ số lái (đã calib)
        var yr = speed * Math.tan(delta) / WHEELBASE           // rad/s
        if (DeadReckonState.steerFlip) yr = -yr
        return yr.coerceIn(-1.2, 1.2)
    }

    /**
     * TỰ HIỆU CHỈNH tỉ số lái khi CÒN GPS tốt + đang cua: so yaw-rate GPS (Δbearing/dt) với góc lái HAL,
     * suy ra tỉ số thật rồi low-pass. Nhờ vậy khi vào hầm cong, bicycle-model bám đúng (thay hằng ước lượng).
     */
    private fun calibrateSteerRatio(speed: Double, now: Long) {
        val d = bodyDev ?: return
        val b = fixBearing
        if (b.isNaN() || speed < 5.0) return
        if (!calPrevBearing.isNaN() && b != calPrevBearing) {
            val dt = (now - calPrevAt) / 1000.0
            if (dt in 0.2..2.0) {
                var dBear = (b - calPrevBearing).toDouble()
                while (dBear > 180) dBear -= 360
                while (dBear < -180) dBear += 360
                val gpsYaw = Math.toRadians(dBear) / dt          // rad/s
                if (Math.abs(gpsYaw) > 0.05) {                   // đang cua đủ (~3°/s) mới đáng tin
                    val raw = runCatching { BydHal.callGetter(d, "getSteeringWheelValue", 1)?.toDoubleOrNull() }.getOrNull()
                    if (raw != null && Math.abs(raw) in 5.0..1000.0) {
                        val deg = Math.toDegrees(Math.atan(gpsYaw * WHEELBASE / speed))   // góc bánh quan sát
                        if (Math.abs(deg) > 0.05) {
                            val est = Math.abs(raw / deg)        // tỉ số lái ước lượng = |góc vô-lăng| / |góc bánh|
                            if (est in 8.0..25.0) {
                                DeadReckonState.steerRatioCal += 0.1 * (est - DeadReckonState.steerRatioCal)
                                DeadReckonState.steerCalSamples++
                                // TỰ DÒ DẤU: yaw dự đoán (không đảo) cùng dấu với raw; nếu ngược dấu gpsYaw → cần đảo.
                                val agree = (raw >= 0) == (gpsYaw >= 0)
                                flipVote = (flipVote + if (agree) -1 else 1).coerceIn(-8, 8)
                                if (!DeadReckonState.steerFlipManual) DeadReckonState.steerFlip = flipVote > 0   // tôn trọng đảo-dấu thủ công
                            }
                        }
                    }
                }
            }
        }
        if (b != calPrevBearing) { calPrevBearing = b; calPrevAt = now }
    }

    private fun buildNotif(): Notification {
        val ch = "deadreckon"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(ch) == null)
                nm.createNotificationChannel(NotificationChannel(ch, "Dead-reckoning GPS", NotificationManager.IMPORTANCE_LOW))
        }
        return Notification.Builder(this, ch)
            .setContentTitle("ClusterNav — GPS hầm")
            .setContentText("dead-reckoning sẵn sàng (vá GPS trong hầm)")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true).build()
    }

    companion object {
        private const val NOTIF_ID = 4711
        private const val MAX_DR_MS = 300000L   // 5 phút: failsafe — KHÔNG BAO GIỜ kẹt mock đè GPS thật quá lâu (áp CẢ cold-seed)
        private const val GPS_BACK_MS = 800L     // nhả DR khi GPS đủ tốt giữ 0.8s (trước 2s) → lên GPS thật nhanh hơn
        private const val PEEK_INTERVAL_MS = 20000L  // mock ~20s rồi PEEK (GnssStatus băng khi mock → phải tạm gỡ mới dò được GPS về)
        private const val PEEK_WINDOW_MS = 3000L     // cửa peek ~3s: đủ cho 1 fix GPS thật (1Hz) tới nếu đã ra khỏi hầm
        private const val PEEK_WINDOW_MAX_MS = 8000L  // usedInFix≥4 (GPS chớm về) → gia hạn cửa tới 8s chờ fix thật (tránh nhả non)
        private const val STEER_RATIO = 15.5    // tỉ số lái Seal (giá trị KHỞI ĐẦU — sau đó tự calib online)
        private const val WHEELBASE = 2.92       // chiều dài cơ sở Seal (m)
        private const val SPEEDO_CORRECTION = 0.93   // getCurrentSpeed (đồng hồ) đọc CAO hơn thực ~5-8% → bù khi tích phân quãng DR
        private const val MIN_CAL_SAMPLES = 4    // cần ≥4 mẫu calib (cua lúc còn GPS) mới tin heading lái
        private const val TRACK_N = 6            // #2: số fix gần nhất giữ để hồi quy hướng seed DR
        private const val SAVE_INTERVAL_MS = 5000L                 // ghi vị trí lưu mỗi 5s (throttle I/O)
        private const val MAX_SAVED_AGE_MS = 7L * 24 * 3600 * 1000 // 7 ngày: seed cũ hơn thì bỏ (xe có thể đã bị di chuyển khi app tắt)
    }
}
