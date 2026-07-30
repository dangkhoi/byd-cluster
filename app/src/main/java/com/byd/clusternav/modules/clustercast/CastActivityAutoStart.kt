package com.byd.clusternav.modules.clustercast

import android.app.Activity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.Spinner
import com.byd.clusternav.cast.platform.CastAppCatalog
import com.byd.clusternav.modules.clustercast.v2.CastAutomationSettings
import com.byd.clusternav.modules.clustercast.v2.CastSessionEnvelope

/**
 * Pure decision for R6 (docs/specs/cast-simplified-active-app-toggle.html): mở ClusterNav, ô tick
 * "Tự khởi động…" ĐANG BẬT, có app mặc định VÀ cụm đang rảnh → tự chiếu ngay. Tách khỏi Activity để
 * giữ controller dưới 500 LOC (CLAUDE.md Code Health Guardrails) và để logic quyết định tự test được.
 *
 * [armed] là `AutomationConfig.armable` — chính vị từ mà tầng tự-chiếu-lúc-khởi-động dùng để quyết
 * "được phép ghi yêu cầu hay không". Nó PHẢI có mặt ở đây: `revoking()` cố ý GIỮ LẠI `defaultPackage`
 * (bỏ tick không xoá app đã chọn), nên bản trước — chỉ đọc `defaultPackage` — vẫn tự chiếu y nguyên
 * sau khi người dùng đã bỏ tick. Ô tick đó là điều khiển DUY NHẤT của đúng tính năng này ("chiếu app
 * này lên cụm khi mở ClusterNav"), nên một ô tick không điều khiển được thứ nó ghi trên nhãn là lỗi,
 * không phải chi tiết. Trước 2026-07-29 cờ một-lần che mất nó (mỗi tiến trình chỉ bắn một lần); bỏ cờ
 * đó đi thì nó bắn mỗi lần mở app.
 *
 * Trả về package cần chiếu, hoặc `null` khi không nên tự chiếu (đã tắt/chưa cấu hình mặc định, chưa
 * đọc được envelope, hoặc cụm đang bận — có transaction hoặc đã có target đang active).
 */
internal fun autoStartTarget(
    defaultPackage: String?,
    armed: Boolean,
    envelope: CastSessionEnvelope?,
): String? {
    if (!armed || defaultPackage == null || envelope == null) return null
    if (envelope.transaction != null || envelope.stableSession?.activeTarget != null) return null
    return defaultPackage
}

/**
 * Ô tick + dropdown "tự chiếu app này khi mở ClusterNav" của panel Cluster Cast trên Home.
 *
 * Ở riêng file vì `MainActivityCastController` có ngưỡng LOC do test canh (cùng lý do `CastRowActions`
 * từng tách ra), và vì nó là chủ sở hữu DUY NHẤT của danh sách app đã cài — panel kích thước hỏi nhãn
 * qua [labelOf] thay vì giữ bản sao thứ hai.
 *
 * Mọi phụ thuộc truyền vào dưới dạng hàm nên lớp này không tự biết gì về vòng đời Activity.
 */
internal class CastAutoStartBinding(
    private val activity: Activity,
    private val catalog: CastAppCatalog,
    private val automation: CastAutomationSettings,
    private val background: (() -> Unit) -> Unit,
    private val postUi: (() -> Unit) -> Unit,
    private val toast: (String) -> Unit,
    /** Gọi khi nhãn app đã nạp xong — panel dựng trước đó đang hiện tên gói thô, cần vẽ lại. */
    private val onAppsLoaded: () -> Unit,
) {
    /** Ghi từ làn nền, đọc trên luồng vẽ — `@Volatile` để lần đọc kia thấy được danh sách đã nạp. */
    @Volatile private var installedApps: List<Pair<String, String>> = emptyList() // package to label
    private var checkbox: CheckBox? = null
    private var spinner: Spinner? = null

    /** Nhãn người đọc được của một gói, hoặc chính tên gói khi chưa nạp xong/không còn cài. */
    fun labelOf(packageName: String): String =
        installedApps.firstOrNull { it.first == packageName }?.second ?: packageName

    fun bind(checkbox: CheckBox, spinner: Spinner) {
        this.checkbox = checkbox
        this.spinner = spinner
        background {
            val entries = runCatching { catalog.installed(null) }.getOrDefault(emptyList())
            installedApps = entries.map { it.packageName to it.label }
            // Đọc cấu hình NGAY TẠI LÀN NỀN: `automation.config()` đọc store bền (file), không phải
            // thứ được phép chạy trên luồng vẽ.
            val config = runCatching { automation.config() }.getOrNull()
            postUi {
                onAppsLoaded()
                spinner.adapter = ArrayAdapter(
                    activity, android.R.layout.simple_spinner_dropdown_item, installedApps.map { it.second },
                )
                if (config == null) return@postUi
                checkbox.isChecked = config.autoCastEnabled
                config.defaultPackage?.let { pkg ->
                    val index = installedApps.indexOfFirst { it.first == pkg }
                    if (index >= 0) spinner.setSelection(index)
                }
                checkbox.setOnCheckedChangeListener { _, checked -> apply(checked) }
                spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        if (checkbox.isChecked) apply(true)
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            }
        }
    }

    /**
     * Checkbox + dropdown LÀ sự đồng ý — không còn hộp thoại xác nhận riêng (màn Quản lý app cũ đã bỏ).
     * Bỏ tick → thu hồi tự động chiếu, không xoá app mặc định đã chọn.
     *
     * KHÔNG ghi lại một cấu hình y hệt cấu hình đang có. Đây là sửa một lỗi thật, không phải tối ưu:
     * `AutomationConfig.withDefault`/`accepting` đều tăng `revision` VÔ ĐIỀU KIỆN, còn
     * `CastAutomationPolicy.claimAllowed` chỉ cho claim một yêu cầu tự-chiếu-lúc-khởi-động khi
     * `config.revision == request.configRevision`, và `supersessionReason` trả `CONFIG_CHANGED` khi
     * lệch. `Spinner` của Android bắn `onItemSelected` một lần cho lựa chọn đặt bằng code (lần layout
     * đầu sau `setAdapter`, `mOldSelectedPosition` còn là `INVALID_POSITION`) — nên nếu ghi vô điều
     * kiện thì CHỈ MỞ APP thôi đã đủ tăng revision hai bậc và giết yêu cầu tự chiếu của lần khởi động
     * xe đó (R7), lặng lẽ, mỗi lần mở.
     */
    private fun apply(checked: Boolean) {
        val box = checkbox ?: return
        val pkg = installedApps.getOrNull(spinner?.selectedItemPosition ?: -1)?.first
        if (checked && pkg == null) {
            // Không có app nào để gán mà vẫn để ô tick là nói dối trạng thái thật; trả ô về đúng sự thật.
            box.isChecked = false
            toast("Chưa có ứng dụng nào để tự chiếu")
            return
        }
        background { commitConfig(checked, pkg) }
    }

    /**
     * Đọc-sửa-ghi cấu hình tự động hoá, tuần tự hoá bằng MỘT khoá.
     *
     * `background` ở đây là `CastActivityWork.misc`, tức `Executors.newFixedThreadPool(2)` — KHÔNG
     * serialize. Tick rồi bỏ tick nhanh tay là hai lượt chạy thật sự song song trên hai luồng, mỗi lượt
     * đọc `automation.config()` rồi ghi đè theo ảnh chụp của riêng nó: xen kẽ `setDefault`+`accept` với
     * `revoke` để lại cấu hình bền BẬT trong khi thao tác cuối của người dùng là TẮT, và ô tick chỉ được
     * đồng bộ lại ở lần `bind()` sau (tức lần mở Home kế tiếp). Đúng lớp lỗi vòng 2 đã sửa một lần —
     * một ô tick không điều khiển được thứ ghi trên nhãn nó. Review vòng 3, 2026-07-30.
     */
    private fun commitConfig(checked: Boolean, pkg: String?): Unit = synchronized(configWriteLock) {
        val current = runCatching { automation.config() }.getOrNull() ?: return
        if (checked && pkg != null) {
            if (current.defaultPackage == pkg && current.autoCastEnabled && current.consentCurrent) return
            automation.setDefault(pkg)
            automation.accept()
        } else {
            if (!current.autoCastEnabled && current.consentVersion == null) return
            automation.revoke()
        }
    }

    private companion object {
        /** Một khoá cho mọi phép ghi cấu hình của bề mặt này. */
        val configWriteLock = Any()
    }
}
