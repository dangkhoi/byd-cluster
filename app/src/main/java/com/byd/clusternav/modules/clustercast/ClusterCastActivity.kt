package com.byd.clusternav.modules.clustercast

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.byd.clusternav.Lang
import com.byd.clusternav.Prefs
import com.byd.clusternav.R

/** Debounce áp-live (ms): gộp loạt nhấn nút scale dồn dập thành 1 lần [ClusterCast.applyScaleLive]. */
private const val SCALE_APPLY_DEBOUNCE_MS = 350L

/**
 * MÀN "CHIẾU APP LÊN CỤM" — 2 tầng:
 *  • Tầng ĐIỀU KHIỂN (khi lái): trạng thái + CHIẾU / TẮT.
 *  • Tầng CÀI ĐẶT (khi đỗ): tick app cho menu nút nổi · kiểu cong-km/h ↔ thẳng · SCALE PER-APP (nút mũi tên, T-C) ·
 *    Hồ sơ cụm đa-model (export/import, T-F) · log (gập).
 * Chế độ chiếu per-app (long-press tile): T1 mặc định (giữ dẫn) ↔ ⊞ T3 (daemon, chắc-ăn).
 * exported=false; mở từ Card "Chiếu app lên cụm" hoặc giữ nút nổi (khi chưa có app nào).
 */
class ClusterCastActivity : Activity() {

    private lateinit var log: TextView
    private lateinit var statusLine: TextView
    private lateinit var kmhLabel: TextView
    private var profileLabel: TextView? = null
    private var profileEdit: EditText? = null
    private var logBtn: Button? = null
    private val chosen = HashSet<String>()
    // ★ DEBOUNCE áp-live (fix LAG trên xe): nhấn nút scale → chỉ ClusterCast.setScale local + cập nhật nhãn tức thì;
    //   hoãn applyScaleLive SCALE_APPLY_DEBOUNCE_MS, gộp loạt nhấn dồn dập thành 1 lần áp qua dadb (tránh mở N kết
    //   nối + `am task resize`/`wm density` mạng ~1-2s bị single-flight DROP → hết lag/nhấn-không-ăn/CarPlay-nháy-đen).
    private val scaleApplyHandler = Handler(Looper.getMainLooper())
    /** Vẽ lại các ô app khi trạng thái chiếu đổi (đánh dấu app ĐANG chiếu). */
    private val repaintTiles = mutableListOf<() -> Unit>()

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        Lang.load(this)   // nạp ngôn ngữ TRƯỚC khi dựng UI để t() trả đúng tiếng
        ClusterCast.loadPrefs(this)
        // ★ W2-5: đo kích cụm THẬT ngay khi mở màn — để nút chỉnh khung không tính theo con số đoán
        ClusterCast.measureClusterInProcess(this)
        ClusterCast.repairLegacyAnim(applicationContext) { s -> runOnUiThread { logln(s) } }
        ClusterCast.reconcileOnStart(applicationContext) { s -> runOnUiThread { logln(s) } }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)); setBackgroundColor(0xFFF4F6F8.toInt()) }
        setContentView(ScrollView(this).apply { addView(root) })

        // ★ v0.38: DÁN SỐ HIỆU BUILD lên tiêu đề. Bài học 21/07: cùng một tên "v0.37" từng được dùng cho 3 bản
        //   khác nhau, và một lần đoán nhầm phiên bản xe đang chạy đã làm cả buổi chẩn đoán đi sai hướng.
        //   Ảnh chụp màn hình hay log hiện trường từ giờ luôn tự mang theo version.
        val ver = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        root.addView(TextView(this).apply { text = Lang.t("Chiếu app lên cụm  ·  v$ver", "Cast app to cluster  ·  v$ver"); textSize = 20f; setTextColor(0xFF1A1F24.toInt()) })
        root.addView(TextView(this).apply { text = Lang.t("Mở app + đưa về trạng thái cần xem ở màn giữa TRƯỚC (vd Maps: chọn tuyến), rồi CHIẾU. Cụm không cảm ứng nên chỉ để NGÓ.", "Open the app and set it to the state you want to view on the center screen FIRST (e.g. Maps: pick a route), then CAST. The cluster is not touch, so it is for viewing only."); textSize = 13f; setTextColor(0xFF5B6470.toInt()); setPadding(0, dp(4), 0, dp(10)) })

        // ── TẦNG ĐIỀU KHIỂN ──
        statusLine = TextView(this).apply { textSize = 15f; setTextColor(0xFF1A1F24.toInt()); setPadding(0, dp(2), 0, dp(8)) }
        root.addView(statusLine)
        root.addView(primaryBtn(Lang.t("CHIẾU LÊN CỤM", "CAST TO CLUSTER")) {
            logln("=== CHIẾU (v$ver) ==="); showLog()   // chuỗi chiếu mất ~10-15s: không mở log thì người thử tưởng máy treo
            ClusterCast.cast(applicationContext, "") { s -> runOnUiThread { logln(s); refresh() } }
        })
        root.addView(warnBtn(Lang.t("TẮT — trả đồng hồ", "STOP — restore gauges")) {
            logln("=== TẮT (v$ver) ==="); showLog()
            ClusterCast.stop(applicationContext) { s -> runOnUiThread { logln(s); refresh() } }
        })

        // ── TẦNG CÀI ĐẶT ──
        root.addView(sectionTitle(Lang.t("Cài đặt chiếu (khi đỗ)", "Cast settings (when parked)")))

        // kiểu cong / thẳng
        kmhLabel = TextView(this).apply { textSize = 13f; setTextColor(0xFF5B6470.toInt()); setPadding(0, dp(4), 0, dp(2)) }
        // ★ v0.37: kiểu cụm giờ theo TỪNG APP (nút nằm trong panel kích thước của app). Cờ toàn cục cũ làm
        //   app sau dính kiểu của app trước — đúng lỗi "chuyển app không clear mode".
        root.addView(kmhLabel)

        // "Mượt UI head-unit": set 3 animation scale = 0.5 global (tweak hội BYD). Bật/tắt, tự áp qua dadb.
        root.addView(outlineBtn(Lang.t("Mượt UI head-unit (animation 0.5) — bật/tắt", "Smooth head-unit UI (animation 0.5) — on/off")) {
            val v = !Prefs.animOpt(applicationContext); Prefs.setAnimOpt(applicationContext, v)
            ClusterCast.applyGlobalAnim(applicationContext, if (v) "0.5" else "1.0") { s -> runOnUiThread { logln(s) } }
            logln(if (v) "Mượt UI: BẬT (animation 0.5)" else "Mượt UI: TẮT (về 1.0)")
        })

        // ── DANH SÁCH APP (1 CỘT): tick app cho menu nút nổi · panel scale hiện INLINE ngay dưới app đã tick ──
        root.addView(sectionTitle(Lang.t("App được chiếu (hiện trong menu nút nổi)", "Apps to cast (shown in the floating-button menu)")))
        root.addView(TextView(this).apply { text = Lang.t("Tick app muốn chiếu lên cụm (nên chọn app ĐỂ NGÓ: nav, nhạc, đồng hồ, video). Tick xong → nút chỉnh kích thước hiện gọn ngay dưới app đó.", "Tick the apps you want to cast to the cluster (pick apps for VIEWING: nav, music, clock, video). After ticking → the adjust-size button appears neatly right under that app."); textSize = 12f; setTextColor(0xFF5B6470.toInt()); setPadding(0, 0, 0, dp(6)) })
        chosen.clear(); chosen.addAll(ClusterCast.castableApps)
        val gridBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = card(); setPadding(dp(6), dp(6), dp(6), dp(6)) }
        val loadingTv = TextView(this).apply { text = Lang.t("Đang tải danh sách app…", "Loading app list…"); textSize = 14f; setTextColor(0xFF5B6470.toInt()); setPadding(dp(10), dp(12), dp(10), dp(12)) }
        gridBox.addView(loadingTv)
        root.addView(gridBox)
        root.addView(TextView(this).apply { text = Lang.t("Giữ nhấn 1 app = xoay vòng chế độ: (trống) chạy đủ 3 rung, rung cuối được mở lại app → ◈ GIỮ PHIÊN, không bao giờ force-stop (chọn cho Android Auto/CarPlay/app đang dẫn) → ⊞ cho daemon ép freeform sau khi đã bám cụm", "Long-press an app = cycle modes: (empty) runs all 3 shakes, the last shake may reopen the app → ◈ KEEP SESSION, never force-stop (pick for Android Auto/CarPlay/an app that is navigating) → ⊞ let the daemon force freeform after it has attached to the cluster"); textSize = 12f; setTextColor(0xFF5B6470.toInt()); setPadding(0, dp(4), 0, dp(2)) })
        // ★ đọc app + loadLabel/loadIcon CHẠY NỀN (nhiều app → tránh giật/ANR trong onCreate), dựng block trên UI thread
        Thread {
            val apps = ClusterCast.listInstalledApps(applicationContext)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                gridBox.removeView(loadingTv)
                if (apps.isEmpty()) { gridBox.addView(TextView(this).apply { text = Lang.t("(không đọc được danh sách app)", "(could not read app list)"); textSize = 13f; setPadding(dp(10), dp(10), dp(10), dp(10)) }); return@runOnUiThread }
                apps.forEach { a -> gridBox.addView(appBlock(a)) }   // 1 CỘT: mỗi app = block dọc [tile full-width] + [holder scale inline]
            }
        }.start()

        // ── HỒ SƠ CỤM ĐA-MODEL (T-F): auto-detect + override + export/import ──
        root.addView(sectionTitle(Lang.t("Hồ sơ cụm (đa-model — chia sẻ nhóm)", "Cluster profile (multi-model — share with group)")))
        profileLabel = TextView(this).apply { textSize = 12f; setTextColor(0xFF5B6470.toInt()); setPadding(0, 0, 0, dp(4)) }
        root.addView(profileLabel)
        root.addView(outlineBtn(Lang.t("Xuất hồ sơ (copy để chia sẻ)", "Export profile (copy to share)")) {
            val p = ClusterProfile.resolve(this); logln("Hồ sơ hiện tại (copy dòng dưới để share):"); logln(p.export()); showLog()
        })
        profileEdit = EditText(this).apply {
            hint = Lang.t("dán chuỗi hồ sơ để nhập (id;diLink;W;H;cast;tear;hint)", "paste a profile string to import (id;diLink;W;H;cast;tear;hint)"); textSize = 13f
            setBackgroundColor(Color.WHITE); setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        root.addView(profileEdit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        val profRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        profRow.addView(smallBtn(Lang.t("Nhập & áp hồ sơ", "Import & apply profile")) {
            // ★ v0.36: ô TRỐNG không còn báo "sai định dạng" (dọa người dùng vô cớ) — điền sẵn hồ sơ hiện tại để
            //   thấy mẫu. Và MỌI nhánh đều showLog(), vì panel log mặc định ẩn → trước đây bấm xong tưởng máy đơ.
            val raw = profileEdit?.text?.toString().orEmpty()
            if (raw.isBlank()) {
                val cur = ClusterProfile.resolve(this).export()
                profileEdit?.setText(cur)
                logln("Ô nhập đang trống — đã điền sẵn hồ sơ HIỆN TẠI: $cur\n(chỉ dán vào đây khi nhận được hồ sơ từ nhóm)")
                showLog(); return@smallBtn
            }
            val p = ClusterProfile.parse(raw)
            if (p == null) logln("❌ hồ sơ sai định dạng: '${raw.trim().take(60)}' — cần đúng 7 phần id;diLink;W;H;cast;tear;hint")
            else { ClusterProfile.saveOverride(applicationContext, p); logln("✅ đã áp hồ sơ override: ${p.summary()}"); refresh() }
            showLog()
        }.apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        profRow.addView(smallBtn(Lang.t("Về auto-detect", "Back to auto-detect")) {
            ClusterProfile.clearOverride(applicationContext); logln("đã xoá override — về auto-detect theo model xe"); showLog(); refresh()
        }.apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        root.addView(profRow)

        // ── CHẨN ĐOÁN (tách hẳn ra màn riêng) ──
        root.addView(outlineBtn(Lang.t("🩺 Chẩn đoán · lấy log gửi dev", "🩺 Diagnostics · grab log for devs")) {
            startActivity(android.content.Intent(this, DiagActivity::class.java))
        })

        // ── LOG (gập) ──
        val lb = outlineBtn(Lang.t("Chi tiết kỹ thuật ▾", "Technical details ▾")) {}
        logBtn = lb; root.addView(lb)
        log = TextView(this).apply {
            textSize = 12f; setTextColor(Color.parseColor("#1A1F24")); setBackgroundColor(Color.parseColor("#F1EFE8"))
            setPadding(dp(12), dp(12), dp(12), dp(12)); setTextIsSelectable(true); visibility = View.GONE
            movementMethod = android.text.method.ScrollingMovementMethod()   // hộp log cao cố định 240dp → phải tự cuộn được
        }
        lb.setOnClickListener { log.visibility = if (log.visibility == View.GONE) View.VISIBLE else View.GONE; lb.text = if (log.visibility == View.VISIBLE) Lang.t("Chi tiết kỹ thuật ▴", "Technical details ▴") else Lang.t("Chi tiết kỹ thuật ▾", "Technical details ▾") }
        root.addView(log, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240)).apply { topMargin = dp(6) })

        refresh()
        // auto-cast nếu gọi kèm --ez cast true
        if (intent.getBooleanExtra("cast", false)) ClusterCast.cast(applicationContext, "") { s -> runOnUiThread { logln(s); refresh() } }
    }

    override fun onResume() { super.onResume(); refresh() }
    override fun onDestroy() { super.onDestroy(); scaleApplyHandler.removeCallbacksAndMessages(null) }   // hủy debounce đang chờ khi thoát màn

    /** 1 BLOCK dọc (lưới 1 cột): [tile app full-width] + [holder panel scale inline]. Tick app → holder hiện panel. */
    private fun appBlock(a: ClusterCast.AppInfo): View {
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        block.addView(pickTile(a, holder))
        block.addView(holder)
        refreshScaleHolder(a.pkg, holder)   // app đã tick từ trước → hiện panel ngay (giữ lựa chọn cũ)
        return block
    }

    /** Hiện/ẩn panel scale INLINE trong holder của 1 block: tick → thêm panel · bỏ tick → xoá. Chỉ đụng block đó (giữ scroll). */
    private fun refreshScaleHolder(pkg: String, holder: LinearLayout) {
        holder.removeAllViews()
        if (chosen.contains(pkg)) holder.addView(scalePanel(pkg))
    }

    /** 1 ô app (full-width, lưới 1 cột): icon + tên, chạm = chọn/bỏ (nền xanh + ✓ khi chọn). Marker ⊞ = T3. */
    private fun pickTile(a: ClusterCast.AppInfo, scaleHolder: LinearLayout): View {
        val lbl = TextView(this).apply {
            textSize = 15f; maxLines = 2; setPadding(dp(10), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val icon = ImageView(this).apply {
            val s = dp(40); layoutParams = LinearLayout.LayoutParams(s, s)
            a.icon?.let { setImageDrawable(it) } ?: setImageResource(android.R.drawable.sym_def_app_icon)
        }
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(64)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
            addView(icon); addView(lbl)
        }
        fun paint() {
            val on = chosen.contains(a.pkg)
            // marker chế độ chiếu: ◈ = giữ phiên (cấm force-stop) · ⊞ = cho daemon ép freeform · (không) = đầy đủ
            val marker = when {
                ClusterCast.isKeepSession(a.pkg) -> "◈ "
                ClusterCast.isT3(a.pkg) -> "⊞ "
                else -> ""
            }
            tile.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; cornerRadius = dp(12).toFloat()
                setColor(if (on) 0xFFE6F1FB.toInt() else Color.WHITE); setStroke(dp(if (on) 2 else 1), if (on) 0xFF378ADD.toInt() else 0xFFE3E6EB.toInt())
            }
            val live = ClusterCast.casting && ClusterCast.lastCastApp == a.pkg
            lbl.setTextColor(if (live) 0xFF1B7A34.toInt() else if (on) 0xFF185FA5.toInt() else 0xFF1A1F24.toInt())
            lbl.text = (if (live) Lang.t("● ĐANG CHIẾU · ", "● NOW CASTING · ") else if (on) "✓ " else "") + marker + a.label
        }
        paint()
        repaintTiles.add { paint() }
        tile.setOnClickListener {
            if (!chosen.remove(a.pkg)) chosen.add(a.pkg)
            paint(); ClusterCast.setCastableApps(applicationContext, chosen.toList())
            refreshScaleHolder(a.pkg, scaleHolder)   // chỉ cập nhật block này (đừng rebuild cả lưới → giữ scroll)
        }
        // long-press: XOAY VÒNG 3 chế độ — (trống) đầy đủ ↔ ◈ giữ phiên ↔ ⊞ ép freeform
        tile.setOnLongClickListener {
            val keep = ClusterCast.isKeepSession(a.pkg); val t3 = ClusterCast.isT3(a.pkg)
            when {
                !keep && !t3 -> { ClusterCast.setKeepSession(applicationContext, a.pkg, true) }
                keep -> { ClusterCast.setKeepSession(applicationContext, a.pkg, false); ClusterCast.setT3(applicationContext, a.pkg, true) }
                else -> { ClusterCast.setT3(applicationContext, a.pkg, false) }
            }
            logln("${a.label}: " + when {
                ClusterCast.isKeepSession(a.pkg) -> "◈ GIỮ PHIÊN — không bao giờ force-stop (thà không lên cụm còn hơn mất phiên Android Auto/dẫn đường)"
                ClusterCast.isT3(a.pkg) -> "⊞ cho phép daemon ép freeform SAU KHI đã bám VD (chỉ có tác dụng sau khi tắt-mở máy xe)"
                else -> "mặc định — chạy đủ 3 rung, rung cuối được phép mở lại app (mất phiên)"
            })
            showLog(); paint(); true
        }
        return tile
    }

    /** DEBOUNCE áp-live: hoãn SCALE_APPLY_DEBOUNCE_MS rồi gọi 1 lần [ClusterCast.applyScaleLive] cho [pkg] (nó tự đọc scale mới nhất). */
    private fun scheduleApplyLive(pkg: String) {
        // ★ v0.37: debounce theo TOKEN = tên gói. Bản cũ dùng removeCallbacksAndMessages(null) — xoá SẠCH hàng đợi,
        //   nên chạm panel app A rồi chạm panel app B trong 350ms là lệnh của A BỊ HUỶ và không bao giờ chạy.
        //   Đúng hiện tượng "bấm app này app kia lần lượt thì loạn".
        scaleApplyHandler.removeCallbacksAndMessages(pkg)
        scaleApplyHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            ClusterCast.applyScaleLive(applicationContext, pkg) { s ->
                runOnUiThread {
                    logln(s)
                    // ★ mọi kết quả KHÔNG-phải-đã-áp phải hiện ra ngoài, không được chỉ nằm trong panel log đang gập
                    if (!s.startsWith("đã áp scale")) {
                        android.widget.Toast.makeText(this, s.take(120), android.widget.Toast.LENGTH_LONG).show()
                        showLog()
                    }
                }
            }
        }, pkg, SCALE_APPLY_DEBOUNCE_MS)
    }

    /**
     * Panel scale INLINE (dưới tile app đã tick) — UXUI GỌN 1 HÀNG, nút LỚN dễ nhấn (tận dụng bề ngang màn 15.6").
     * Mô hình TRỰC QUAN move+resize (thay 8 nút cạnh khó hiểu Trái◀/Phải▶…): 4 nhóm nút lớn có nhãn —
     *   • Kích thước: Hẹp/Rộng/Thấp/Cao  → [AppScale.nudgeRect] nới/thu QUANH TÂM (±2·STEP_WH mỗi nhấn).
     *   • Vị trí:     ◀ ▲ ▼ ▶            → [AppScale.nudgeMove] dời khung GIỮ CỠ (±STEP_WH).
     *   • Chữ:  nhỏ / to  → [AppScale.nudgeDpi] (±STEP_DPI). ⚠ ĐÚNG CHIỀU: px = dp × density/160 nên
     *     **DPI CAO = chữ/nội dung TO hơn** (ít nội dung lọt màn). Ba chỗ tài liệu cũ ghi ngược, đã sửa.
     *   • Khôi phục:  ↺                  → [AppScale] auto (full cụm).
     * Mỗi nhấn = [ClusterCast.setScale] + cập nhật nhãn TỨC THÌ + DEBOUNCE áp-live ([scheduleApplyLive]) —
     * KHÔNG gọi applyScaleLive ngay (fix lag/nhấn-không-ăn/CarPlay-nháy-đen). Áp-live path GIỮ NGUYÊN.
     */
    private fun scalePanel(pkg: String): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = card(); setPadding(dp(12), dp(10), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(4), 0, dp(4), dp(6)) }
        }
        val info = TextView(this).apply { textSize = 13f; setTextColor(0xFF5B6470.toInt()); text = scaleSummary(pkg); setPadding(dp(2), 0, dp(2), dp(4)) }
        col.addView(info)
        // ★ v0.37 — GỐC CỦA "LOẠN XẠ": panel hiện dưới MỌI app đã tick, nhưng applyScaleLive chỉ áp cho app ĐANG
        //   CHIẾU rồi lặng lẽ bỏ qua phần còn lại. Người dùng bấm nút ở panel app KHÔNG chiếu → nhãn vẫn đổi (vì
        //   nhãn đọc từ prefs) mà cụm không nhúc nhích → tưởng "nút chết / app này không chỉnh được".
        //   Giờ nói thẳng panel này đang có tác dụng hay không, và cho chiếu ngay tại chỗ.
        val liveNote = TextView(this).apply { textSize = 12f; setPadding(dp(2), 0, dp(2), dp(8)) }
        col.addView(liveNote)
        fun paintLive() {
            val live = ClusterCast.casting && ClusterCast.lastCastApp == pkg
            liveNote.text = if (live) Lang.t("● Đang chiếu app này — nút bên dưới áp thẳng lên cụm", "● Casting this app — the buttons below apply straight to the cluster")
                else Lang.t("Chưa chiếu app này — chỉnh ở đây chỉ LƯU LẠI, bấm \"CHIẾU APP NÀY\" để thấy trên cụm", "Not casting this app — changes here are only SAVED, press \"CAST THIS APP\" to see it on the cluster")
            liveNote.setTextColor(if (live) 0xFF1B7A34.toInt() else 0xFFB25000.toInt())
        }
        paintLive(); repaintTiles.add { paintLive(); info.text = scaleSummary(pkg) }
        // ★ v0.42 — TỰ CHIẾU KHI NỔ MÁY (chỉ MỘT app duy nhất trong toàn app).
        val autoBtn = outlineBtn("") {}
        fun paintAuto() {
            val on = ClusterCast.isAutoCast(pkg)
            autoBtn.text = if (on) Lang.t("⏱ ĐANG tự chiếu app này khi nổ máy — chạm để tắt", "⏱ NOW auto-casting this app on engine start — tap to turn off")
                           else Lang.t("⏱ Tự chiếu app này khi nổ máy", "⏱ Auto-cast this app on engine start")
            autoBtn.setTextColor(if (on) 0xFF1B7A34.toInt() else 0xFF1565C0.toInt())
        }
        autoBtn.setOnClickListener {
            val turnOn = !ClusterCast.isAutoCast(pkg)
            val prev = ClusterCast.autoCastPkg
            ClusterCast.setAutoCast(applicationContext, if (turnOn) pkg else "")
            logln(if (!turnOn) "⏱ đã tắt tự chiếu khi nổ máy"
                  else "⏱ nổ máy sẽ tự mở ${ClusterCast.labelOf(this, pkg)} rồi đẩy sang cụm với kích thước đã lưu" +
                       (if (prev.isNotBlank() && prev != pkg) "\n   (thay cho ${ClusterCast.labelOf(this, prev)} — chỉ đặt được 1 app)" else ""))
            showLog(); refresh()
        }
        paintAuto(); repaintTiles.add { paintAuto() }
        col.addView(autoBtn.apply { minHeight = dp(44); textSize = 13f })
        col.addView(outlineBtn(Lang.t("🩺 Chụp chẩn đoán app này (không cần WiFi)", "🩺 Capture diagnostics for this app (no WiFi needed)")) {
            // ★ Chạy được NGAY CẢ KHI đang cắm CarPlay/AA (lúc đó xe tắt WiFi, adb ngoài vào không được):
            //   app nối dadb qua localhost, loopback trong máy, không đụng mạng.
            logln("🩺 đang chụp chẩn đoán ${ClusterCast.labelOf(this, pkg)}…"); showLog()
            val stamp = java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
            Thread {
                val vd = ClusterCast.lastDisplayId
                val (path, sum) = ClusterDiag.capture(applicationContext, pkg, vd, stamp)
                runOnUiThread {
                    logln(sum); logln("→ đã lưu: $path")
                    android.widget.Toast.makeText(this, Lang.t("Đã lưu chẩn đoán:\n$path", "Diagnostics saved:\n$path"), android.widget.Toast.LENGTH_LONG).show()
                }
            }.start()
        }.apply { minHeight = dp(44); textSize = 13f })
        col.addView(primaryBtn(Lang.t("CHIẾU APP NÀY LÊN CỤM", "CAST THIS APP TO CLUSTER")) {
            logln("=== CHIẾU ${ClusterCast.labelOf(this, pkg)} (v${appVersion()}) ==="); showLog()
            ClusterCast.cast(applicationContext, pkg) { m -> runOnUiThread { logln(m); refresh() } }
        }.apply { minHeight = dp(48); textSize = 14f })

        fun after() { info.text = scaleSummary(pkg); scheduleApplyLive(pkg) }   // cập nhật nhãn local + áp-live qua DEBOUNCE
        fun resize(dW: Int, dH: Int) { val (w, h) = clusterRef(); ClusterCast.setScale(applicationContext, pkg, seedOf(pkg).nudgeRect(w, h, dW, dH)); after() }
        fun move(dx: Int, dy: Int) { val (w, h) = clusterRef(); ClusterCast.setScale(applicationContext, pkg, seedOf(pkg).nudgeMove(w, h, dx, dy)); after() }
        fun dpi(d: Int) { ClusterCast.setScale(applicationContext, pkg, ClusterCast.scaleOf(pkg).nudgeDpi(d)); after() }
        // ★ v0.42: preset theo PHẦN TRĂM cụm THẬT, không theo tỉ lệ TV. Cụm là dải 2.667:1 nên 16:9/21:9 chỉ biết
        //   cắt hai bên, không bao giờ tạo viền trên/dưới → anh em phản ánh đúng là vô nghĩa.
        fun preset(pct: Int) { val (w, h) = clusterRef(); ClusterCast.setScale(applicationContext, pkg, ClusterCast.scaleOf(pkg).scaled(w, h, pct)); after() }

        val S = AppScale.STEP_WH
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        // ★ v0.36: KHUNG SẴN đứng ĐẦU — 1 chạm là ra tỉ lệ dùng được, khỏi bấm "Hẹp" 20 lần.
        //   16:9 cho video (YouTube khỏi bị bóp thành mini-player) · 21:9 cho nav/dashboard cần bề ngang.
        row.addView(ctrlGroup(clusterCaption(), listOf(
            "100%" to { ClusterCast.setScale(applicationContext, pkg, AppScale(dpi = ClusterCast.scaleOf(pkg).dpi)); after() },
            "90%" to { preset(90) }, "80%" to { preset(80) }, "70%" to { preset(70) })), groupLp(4f))
        row.addView(ctrlGroup(Lang.t("Kích thước", "Size"), listOf(
            Lang.t("Hẹp", "Narrow") to { resize(-2 * S, 0) }, Lang.t("Rộng", "Wide") to { resize(2 * S, 0) },
            Lang.t("Thấp", "Short") to { resize(0, -2 * S) }, Lang.t("Cao", "Tall") to { resize(0, 2 * S) })), groupLp(4f))
        row.addView(ctrlGroup(Lang.t("Vị trí", "Position"), listOf(
            "◀" to { move(-S, 0) }, "▲" to { move(0, -S) },
            "▼" to { move(0, S) }, "▶" to { move(S, 0) })), groupLp(4f))
        // nhãn theo Ý ĐỊNH thay vì số kỹ thuật — "－/＋" trên một đại lượng mà tài liệu từng ghi ngược chiều
        // là công thức chắc chắn gây nhầm.
        row.addView(ctrlGroup(Lang.t("Chữ", "Text"), listOf(
            Lang.t("nhỏ", "small") to { dpi(-AppScale.STEP_DPI) }, Lang.t("to", "large") to { dpi(AppScale.STEP_DPI) })), groupLp(2f))
        // ★ W2-6: đời xe không đổi kiểu được thì ẨN hẳn nút — thà không có còn hơn có mà bấm không ăn.
        if (ClusterProfile.resolve(this).supportsStyle) row.addView(ctrlGroup(Lang.t("Kiểu", "Style"), listOf(
            (if (ClusterCast.isRectProfile(pkg)) "▭" else "◠") to {
                ClusterCast.setRectProfile(applicationContext, pkg, !ClusterCast.isRectProfile(pkg))
                info.text = scaleSummary(pkg)
                logln("${ClusterCast.labelOf(this, pkg)}: kiểu cụm = " +
                    if (ClusterCast.isRectProfile(pkg)) "▭ THẲNG (ảnh full, mất km/h)" else "◠ CONG (giữ km/h gốc)")
                showLog()
            })), groupLp(1.4f))
        row.addView(ctrlGroup(Lang.t("Khôi phục", "Reset"), listOf(
            "↺" to { ClusterCast.setScale(applicationContext, pkg, AppScale()); after() })), groupLp(1.4f))
        col.addView(row)
        return col
    }

    /**
     * ★ AppScale để NUDGE (v0.36). Khung đang AUTO thì [AppScale.nudgeRect] materialize từ FULL VD [0,0,w,h] —
     * tức là nhấn "Hẹp" phát đầu tiên VỨT LUÔN inset dọc 90px đang hiển thị, cửa sổ đột nhiên CAO LÊN rồi chui
     * xuống dưới thanh OEM. Ở đây seed đúng khung ĐANG NHÌN THẤY (insetH/insetV) để nhấn phát đầu không nhảy.
     */
    private fun seedOf(pkg: String): AppScale {
        val s = ClusterCast.scaleOf(pkg)
        if (!s.isAuto) return s
        val (w, h) = clusterRef()
        return s.copy(
            rectL = ClusterCast.insetH.coerceIn(0, w), rectT = ClusterCast.insetV.coerceIn(0, h),
            rectR = (w - ClusterCast.insetH).coerceIn(0, w), rectB = (h - ClusterCast.insetV).coerceIn(0, h),
        )
    }

    /** Nhãn nhóm preset: nói rõ đang tính theo cụm NÀO, và cụm đó đã ĐO được hay mới chỉ theo hồ sơ. */
    private fun clusterCaption(): String {
        val (w, h) = clusterRef()
        val g = gcd(w, h)
        val measured = ClusterCast.lastClusterW > 0
        return Lang.t("Khung · ${w}×$h (${w / g}:${h / g})", "Frame · ${w}×$h (${w / g}:${h / g})") + if (measured) "" else Lang.t(" — chưa đo", " — not measured")
    }
    private fun gcd(a: Int, b: Int): Int = if (b == 0) a.coerceAtLeast(1) else gcd(b, a % b)

    /** Kích thước cụm tham chiếu để tính rect quanh tâm: ưu tiên VD THẬT auto-detect (lần chiếu gần nhất), else profile. */
    private fun clusterRef(): Pair<Int, Int> {
        if (ClusterCast.lastClusterW > 0 && ClusterCast.lastClusterH > 0) return ClusterCast.lastClusterW to ClusterCast.lastClusterH
        return ClusterProfile.resolve(this).let { it.clusterW to it.clusterH }
    }

    private fun scaleSummary(pkg: String): String {
        val s = ClusterCast.scaleOf(pkg)
        val kieu = if (ClusterCast.isRectProfile(pkg)) Lang.t("▭ thẳng", "▭ flat") else Lang.t("◠ cong (giữ km/h)", "◠ curved (keep km/h)")
        return Lang.t("Đã lưu — ", "Saved — ") + (if (s.isAuto) Lang.t("kích thước auto (full cụm) · DPI ${s.dpi}", "auto size (full cluster) · DPI ${s.dpi}")
        else Lang.t("khung ${s.rectR - s.rectL}×${s.rectB - s.rectT} tại (${s.rectL},${s.rectT}) · DPI ${s.dpi}", "frame ${s.rectR - s.rectL}×${s.rectB - s.rectT} at (${s.rectL},${s.rectT}) · DPI ${s.dpi}")) + " · $kieu"
    }

    private fun refresh() {
        repaintTiles.forEach { runCatching(it) }
        val casting = ClusterCast.casting
        val app = ClusterCast.lastCastApp
        statusLine.text = "● " + if (casting) Lang.t("Đang chiếu: ${if (app.isBlank()) "app" else ClusterCast.labelOf(this, app)}", "Casting: ${if (app.isBlank()) "app" else ClusterCast.labelOf(this, app)}") else Lang.t("Chưa chiếu", "Not casting")
        statusLine.setTextColor(if (casting) 0xFF2E7D32.toInt() else 0xFF5B6470.toInt())
        kmhLabel.text = Lang.t("Kiểu cụm đặt riêng cho từng app — xem nút \"Kiểu\" trong phần chỉnh kích thước của app đó.", "Cluster style is set per app — see the \"Style\" button in that app's size-adjust section.")
        profileLabel?.text = Lang.t("Hồ sơ: ", "Profile: ") + ClusterProfile.resolve(this).summary()
    }

    private fun appVersion(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"

    private fun logln(s: String) {
        if (isFinishing || isDestroyed || !::log.isInitialized) return
        log.append(s + "\n")
        // luôn cuộn tới dòng mới nhất — ladder in cả chục dòng trong hộp cao cố định, không tự cuộn thì người thử
        // chỉ đọc được mấy dòng đầu và không bao giờ thấy kết luận R1/R2/R3.
        log.post {
            val lay = log.layout ?: return@post
            val d = lay.getLineBottom(log.lineCount - 1) - (log.height - log.paddingTop - log.paddingBottom)
            log.scrollTo(0, d.coerceAtLeast(0))
        }
    }
    private fun showLog() {
        if (!::log.isInitialized) return
        log.visibility = View.VISIBLE
        logBtn?.text = Lang.t("Chi tiết kỹ thuật ▴", "Technical details ▴")
    }

    // ── helpers dựng nút (dùng drawable/màu app cho nhất quán) ──
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun card() = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(Color.WHITE); cornerRadius = dp(12).toFloat(); setStroke(dp(1), 0xFFE3E6EB.toInt()) }
    private fun sectionTitle(t: String) = TextView(this).apply { text = t; textSize = 13f; setTextColor(0xFF5B6470.toInt()); setPadding(0, dp(16), 0, dp(6)) }

    private fun baseBtn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 15f; minHeight = dp(56)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        setOnClickListener { runCatching(onClick) }
    }
    private fun primaryBtn(t: String, onClick: () -> Unit) = baseBtn(t, onClick).apply { setBackgroundResource(R.drawable.btn_primary); setTextColor(Color.WHITE) }
    private fun warnBtn(t: String, onClick: () -> Unit) = baseBtn(t, onClick).apply { setBackgroundResource(R.drawable.btn_warning_outline); setTextColor(0xFFE08600.toInt()) }
    private fun outlineBtn(t: String, onClick: () -> Unit) = baseBtn(t, onClick).apply { setBackgroundResource(R.drawable.btn_outline); setTextColor(0xFF1565C0.toInt()); textSize = 14f; minHeight = dp(48) }
    private fun smallBtn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 13f; minHeight = dp(48); setBackgroundResource(R.drawable.btn_outline); setTextColor(0xFF1565C0.toInt())
        setOnClickListener { runCatching(onClick) }
    }
    /** Nút điều khiển scale LỚN, dễ nhấn trên màn 15.6" (cao ≥54dp, chữ 16f, bo tròn) — thay tinyBtn cũ (bé, khó nhấn). */
    private fun bigBtn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 16f; minHeight = dp(54); minWidth = 0; minimumWidth = 0
        setPadding(dp(2), dp(8), dp(2), dp(8)); setBackgroundResource(R.drawable.btn_outline); setTextColor(0xFF1565C0.toInt())
        setOnClickListener { runCatching(onClick) }
    }

    /** LayoutParams cho 1 nhóm nút trong hàng điều khiển (weight = tỉ lệ bề ngang, margin tách nhóm). */
    private fun groupLp(weight: Float) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply { setMargins(dp(3), 0, dp(3), 0) }

    /** 1 NHÓM nút điều khiển: caption nhỏ ở trên + hàng nút LỚN (chia đều bằng weight), nền bo tròn để tách nhóm rõ ràng. */
    private fun ctrlGroup(caption: String, items: List<Pair<String, () -> Unit>>): View {
        val g = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(14).toFloat(); setColor(0xFFF7F9FB.toInt()); setStroke(dp(1), 0xFFE3E6EB.toInt()) }
            setPadding(dp(8), dp(6), dp(8), dp(8))
        }
        g.addView(TextView(this).apply { text = caption; textSize = 11f; setTextColor(0xFF8A929C.toInt()); gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(5)) })
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((lbl, act) in items)
            btnRow.addView(bigBtn(lbl, act), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        g.addView(btnRow)
        return g
    }
}
