package com.byd.clusternav.modules.clustercast

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.byd.clusternav.Prefs
import com.byd.clusternav.R

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
    private var scaleBox: LinearLayout? = null
    private var profileLabel: TextView? = null
    private var profileEdit: EditText? = null
    private val chosen = HashSet<String>()

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        ClusterCast.loadPrefs(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)); setBackgroundColor(0xFFF4F6F8.toInt()) }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(TextView(this).apply { text = "Chiếu app lên cụm"; textSize = 20f; setTextColor(0xFF1A1F24.toInt()) })
        root.addView(TextView(this).apply { text = "Mở app + đưa về trạng thái cần xem ở màn giữa TRƯỚC (vd Maps: chọn tuyến), rồi CHIẾU. Cụm không cảm ứng nên chỉ để NGÓ."; textSize = 13f; setTextColor(0xFF5B6470.toInt()); setPadding(0, dp(4), 0, dp(10)) })

        // ── TẦNG ĐIỀU KHIỂN ──
        statusLine = TextView(this).apply { textSize = 15f; setTextColor(0xFF1A1F24.toInt()); setPadding(0, dp(2), 0, dp(8)) }
        root.addView(statusLine)
        root.addView(primaryBtn("CHIẾU LÊN CỤM") {
            logln("=== CHIẾU ===")
            ClusterCast.cast(applicationContext, "") { s -> runOnUiThread { logln(s); refresh() } }
        })
        root.addView(warnBtn("TẮT — trả đồng hồ") {
            logln("=== TẮT ===")
            ClusterCast.stop(applicationContext) { s -> runOnUiThread { logln(s); refresh() } }
        })

        // ── TẦNG CÀI ĐẶT ──
        root.addView(sectionTitle("Cài đặt chiếu (khi đỗ)"))

        // kiểu cong / thẳng
        kmhLabel = TextView(this).apply { textSize = 13f; setTextColor(0xFF5B6470.toInt()); setPadding(0, dp(4), 0, dp(2)) }
        root.addView(outlineBtn("Đổi kiểu: cong (giữ km/h) ↔ thẳng (full)") { ClusterCast.setKeepKmh(applicationContext, !ClusterCast.keepKmh); refresh() })
        root.addView(kmhLabel)

        // "Mượt UI head-unit": set 3 animation scale = 0.5 global (tweak hội BYD). Bật/tắt, tự áp qua dadb.
        root.addView(outlineBtn("Mượt UI head-unit (animation 0.5) — bật/tắt") {
            val v = !Prefs.animOpt(applicationContext); Prefs.setAnimOpt(applicationContext, v)
            ClusterCast.applyGlobalAnim(applicationContext, if (v) "0.5" else "1.0") { s -> runOnUiThread { logln(s) } }
            logln(if (v) "Mượt UI: BẬT (animation 0.5)" else "Mượt UI: TẮT (về 1.0)")
        })

        // ── DANH SÁCH APP: tick app cho menu nút nổi ──
        root.addView(sectionTitle("App được chiếu (hiện trong menu nút nổi)"))
        root.addView(TextView(this).apply { text = "Tick app nào muốn chiếu lên cụm. Nên chọn app ĐỂ NGÓ (nav, nhạc, đồng hồ, video)."; textSize = 12f; setTextColor(0xFF5B6470.toInt()); setPadding(0, 0, 0, dp(6)) })
        chosen.clear(); chosen.addAll(ClusterCast.castableApps)
        val gridBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = card(); setPadding(dp(6), dp(6), dp(6), dp(6)) }
        val loadingTv = TextView(this).apply { text = "Đang tải danh sách app…"; textSize = 14f; setTextColor(0xFF5B6470.toInt()); setPadding(dp(10), dp(12), dp(10), dp(12)) }
        gridBox.addView(loadingTv)
        root.addView(gridBox)
        root.addView(TextView(this).apply { text = "Giữ nhấn 1 app = đổi chế độ chiếu: T1 mặc định (move+freeform+resize giữ dẫn) ↔ ⊞ T3 (daemon app_process, chắc-ăn khi T1 hụt)"; textSize = 12f; setTextColor(0xFF5B6470.toInt()); setPadding(0, dp(4), 0, dp(2)) })
        // ★ đọc app + loadLabel/loadIcon CHẠY NỀN (nhiều app → tránh giật/ANR trong onCreate), dựng tile trên UI thread
        Thread {
            val apps = ClusterCast.listInstalledApps(applicationContext)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                gridBox.removeView(loadingTv)
                if (apps.isEmpty()) { gridBox.addView(TextView(this).apply { text = "(không đọc được danh sách app)"; textSize = 13f; setPadding(dp(10), dp(10), dp(10), dp(10)) }); return@runOnUiThread }
                var row: LinearLayout? = null
                apps.forEachIndexed { i, a ->
                    if (i % 2 == 0) { row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; gridBox.addView(row) }
                    row!!.addView(pickTile(a))
                }
                if (apps.size % 2 == 1) row!!.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })   // ô trống cân cột lẻ
            }
        }.start()

        // ── SCALE PER-APP (T-C): panel nút mũi tên cho từng app đã tick ──
        root.addView(sectionTitle("Kích thước từng app (đang chiếu app nào = áp ngay lên cụm)"))
        root.addView(TextView(this).apply { text = "Chỉnh TỪNG CẠNH: Trái/Phải ◀▶ · Trên/Dưới ▲▼ (đẩy cạnh ra/vào — size TỰ TÍNH lại, chỉnh lệch được, không bắt căn giữa) · DPI −＋ = độ lớn · Reset = auto. Mỗi nhấn 1 nấc, tự lưu."; textSize = 12f; setTextColor(0xFF5B6470.toInt()); setPadding(0, 0, 0, dp(6)) })
        scaleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(scaleBox)
        rebuildScalePanels()

        // ── HỒ SƠ CỤM ĐA-MODEL (T-F): auto-detect + override + export/import ──
        root.addView(sectionTitle("Hồ sơ cụm (đa-model — chia sẻ nhóm)"))
        profileLabel = TextView(this).apply { textSize = 12f; setTextColor(0xFF5B6470.toInt()); setPadding(0, 0, 0, dp(4)) }
        root.addView(profileLabel)
        root.addView(outlineBtn("Xuất hồ sơ (copy để chia sẻ)") {
            val p = ClusterProfile.resolve(this); logln("Hồ sơ hiện tại (copy dòng dưới để share):"); logln(p.export()); showLog()
        })
        profileEdit = EditText(this).apply {
            hint = "dán chuỗi hồ sơ để nhập (id;diLink;W;H;cast;tear;hint)"; textSize = 13f
            setBackgroundColor(Color.WHITE); setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        root.addView(profileEdit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        val profRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        profRow.addView(smallBtn("Nhập & áp hồ sơ") {
            val s = profileEdit?.text?.toString()?.trim().orEmpty()
            val p = ClusterProfile.parse(s)
            if (p == null) logln("❌ chuỗi hồ sơ sai định dạng (cần id;diLink;W;H;cast;tear;hint)")
            else { ClusterProfile.saveOverride(applicationContext, p); logln("✅ đã áp hồ sơ override: ${p.summary()}"); refresh() }
        }.apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        profRow.addView(smallBtn("Về auto-detect") {
            ClusterProfile.clearOverride(applicationContext); logln("đã xoá override — về auto-detect theo model xe"); refresh()
        }.apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        root.addView(profRow)

        // ── LOG (gập) ──
        val logBtn = outlineBtn("Chi tiết kỹ thuật ▾") {}
        root.addView(logBtn)
        log = TextView(this).apply {
            textSize = 12f; setTextColor(Color.parseColor("#1A1F24")); setBackgroundColor(Color.parseColor("#F1EFE8"))
            setPadding(dp(12), dp(12), dp(12), dp(12)); setTextIsSelectable(true); visibility = View.GONE
        }
        logBtn.setOnClickListener { log.visibility = if (log.visibility == View.GONE) View.VISIBLE else View.GONE; logBtn.text = if (log.visibility == View.VISIBLE) "Chi tiết kỹ thuật ▴" else "Chi tiết kỹ thuật ▾" }
        root.addView(log, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240)).apply { topMargin = dp(6) })

        refresh()
        // auto-cast nếu gọi kèm --ez cast true
        if (intent.getBooleanExtra("cast", false)) ClusterCast.cast(applicationContext, "") { s -> runOnUiThread { logln(s); refresh() } }
    }

    override fun onResume() { super.onResume(); refresh(); rebuildScalePanels() }

    /** 1 ô app trong lưới 2 cột: icon + tên, chạm = chọn/bỏ (nền xanh + ✓ khi chọn). Marker ⊞ = T3. */
    private fun pickTile(a: ClusterCast.AppInfo): View {
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
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
            addView(icon); addView(lbl)
        }
        fun paint() {
            val on = chosen.contains(a.pkg)
            // marker chế độ chiếu: ⊞ = daemon T3 · (không) = T1 mặc định (freeform+resize giữ dẫn)
            val marker = if (ClusterCast.isT3(a.pkg)) "⊞ " else ""
            tile.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; cornerRadius = dp(12).toFloat()
                setColor(if (on) 0xFFE6F1FB.toInt() else Color.WHITE); setStroke(dp(if (on) 2 else 1), if (on) 0xFF378ADD.toInt() else 0xFFE3E6EB.toInt())
            }
            lbl.setTextColor(if (on) 0xFF185FA5.toInt() else 0xFF1A1F24.toInt())
            lbl.text = (if (on) "✓ " else "") + marker + a.label
        }
        paint()
        tile.setOnClickListener {
            if (!chosen.remove(a.pkg)) chosen.add(a.pkg)
            paint(); ClusterCast.setCastableApps(applicationContext, chosen.toList()); rebuildScalePanels()
        }
        // long-press: cycle chế độ chiếu — T1 mặc định ↔ ⊞ T3 (daemon)
        tile.setOnLongClickListener {
            ClusterCast.setT3(applicationContext, a.pkg, !ClusterCast.isT3(a.pkg))
            logln("${a.label}: " + if (ClusterCast.isT3(a.pkg)) "⊞ GIỮ state qua DAEMON (T3 — chắc-ăn, nặng)" else "T1 mặc định (move+freeform+resize giữ dẫn)")
            paint(); true
        }
        return tile
    }

    /** Dựng lại panel scale cho MỌI app đã tick (gọi khi tick đổi / onResume). Toạ độ theo cluster W×H của profile. */
    private fun rebuildScalePanels() {
        val box = scaleBox ?: return
        box.removeAllViews()
        val apps = chosen.toList().sortedBy { ClusterCast.labelOf(this, it).lowercase() }
        if (apps.isEmpty()) {
            box.addView(TextView(this).apply { text = "(tick app ở trên để hiện panel chỉnh kích thước riêng từng app)"; textSize = 12f; setTextColor(0xFF5B6470.toInt()); setPadding(dp(4), dp(6), dp(4), dp(6)) })
            return
        }
        for (pkg in apps) box.addView(scalePanel(pkg))
    }

    /** Panel nút mũi tên cho 1 app: Cao ▲▼ · Ngang ◀▶ · DPI −＋ · Reset. Mỗi nhấn cập nhật AppScale + lưu + áp live. */
    private fun scalePanel(pkg: String): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = card(); setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        }
        col.addView(TextView(this).apply { text = ClusterCast.labelOf(this@ClusterCastActivity, pkg); textSize = 15f; setTextColor(0xFF1A1F24.toInt()) })
        val info = TextView(this).apply { textSize = 12f; setTextColor(0xFF5B6470.toInt()); text = scaleSummary(pkg) }
        col.addView(info)

        fun applyEdge(edge: AppScale.Edge, delta: Int) {
            val (w, h) = clusterRef()
            ClusterCast.setScale(applicationContext, pkg, ClusterCast.scaleOf(pkg).nudgeEdge(w, h, edge, delta))
            info.text = scaleSummary(pkg)
            ClusterCast.applyScaleLive(applicationContext, pkg) { s -> runOnUiThread { logln(s) } }
        }
        fun applyDpi(d: Int) {
            ClusterCast.setScale(applicationContext, pkg, ClusterCast.scaleOf(pkg).nudgeDpi(d))
            info.text = scaleSummary(pkg)
            ClusterCast.applyScaleLive(applicationContext, pkg) { s -> runOnUiThread { logln(s) } }
        }
        val S = AppScale.STEP_WH

        // hàng chỉnh cạnh NGANG: cạnh Trái + cạnh Phải (mỗi cạnh đẩy ◀ trái / ▶ phải)
        val rowLR = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((lbl, act) in listOf<Pair<String, () -> Unit>>(
            "Trái ◀" to { applyEdge(AppScale.Edge.LEFT, -S) }, "Trái ▶" to { applyEdge(AppScale.Edge.LEFT, S) },
            "Phải ◀" to { applyEdge(AppScale.Edge.RIGHT, -S) }, "Phải ▶" to { applyEdge(AppScale.Edge.RIGHT, S) }))
            rowLR.addView(smallBtn(lbl, act).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        col.addView(rowLR)

        // hàng chỉnh cạnh DỌC: cạnh Trên + cạnh Dưới (mỗi cạnh đẩy ▲ lên / ▼ xuống)
        val rowTB = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((lbl, act) in listOf<Pair<String, () -> Unit>>(
            "Trên ▲" to { applyEdge(AppScale.Edge.TOP, -S) }, "Trên ▼" to { applyEdge(AppScale.Edge.TOP, S) },
            "Dưới ▲" to { applyEdge(AppScale.Edge.BOTTOM, -S) }, "Dưới ▼" to { applyEdge(AppScale.Edge.BOTTOM, S) }))
            rowTB.addView(smallBtn(lbl, act).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        col.addView(rowTB)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((lbl, act) in listOf<Pair<String, () -> Unit>>(
            "DPI −" to { applyDpi(-AppScale.STEP_DPI) }, "DPI ＋" to { applyDpi(AppScale.STEP_DPI) },
            "Reset (auto)" to {
                ClusterCast.setScale(applicationContext, pkg, AppScale()); info.text = scaleSummary(pkg)
                ClusterCast.applyScaleLive(applicationContext, pkg) { s -> runOnUiThread { logln(s) } }
            }))
            row2.addView(smallBtn(lbl, act).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        col.addView(row2)
        return col
    }

    /** Kích thước cụm tham chiếu để tính rect quanh tâm: ưu tiên VD THẬT auto-detect (lần chiếu gần nhất), else profile. */
    private fun clusterRef(): Pair<Int, Int> {
        if (ClusterCast.lastClusterW > 0 && ClusterCast.lastClusterH > 0) return ClusterCast.lastClusterW to ClusterCast.lastClusterH
        return ClusterProfile.resolve(this).let { it.clusterW to it.clusterH }
    }

    private fun scaleSummary(pkg: String): String {
        val s = ClusterCast.scaleOf(pkg)
        return if (s.isAuto) "Kích thước: auto (full cụm) · DPI ${s.dpi}"
        else "Khung: ${s.rectR - s.rectL}×${s.rectB - s.rectT} tại (${s.rectL},${s.rectT}) · DPI ${s.dpi}"
    }

    private fun refresh() {
        val casting = ClusterCast.casting
        val app = ClusterCast.lastCastApp
        statusLine.text = "● " + if (casting) "Đang chiếu: ${if (app.isBlank()) "app" else ClusterCast.labelOf(this, app)}" else "Chưa chiếu"
        statusLine.setTextColor(if (casting) 0xFF2E7D32.toInt() else 0xFF5B6470.toInt())
        kmhLabel.text = "Kiểu: " + if (ClusterCast.keepKmh) "CONG SL6 — giữ km/h gốc" else "THẲNG — ảnh full (mất km/h)"
        profileLabel?.text = "Hồ sơ: " + ClusterProfile.resolve(this).summary()
    }

    private fun logln(s: String) { if (!isFinishing && !isDestroyed && ::log.isInitialized) log.append(s + "\n") }
    private fun showLog() { if (::log.isInitialized) log.visibility = View.VISIBLE }

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
}
