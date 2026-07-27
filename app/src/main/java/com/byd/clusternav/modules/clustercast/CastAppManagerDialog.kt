package com.byd.clusternav.modules.clustercast

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.byd.clusternav.modules.clustercast.v2.AppProtectionSource
import com.byd.clusternav.modules.clustercast.v2.AppRowAction
import com.byd.clusternav.modules.clustercast.v2.AppUnavailableReason
import com.byd.clusternav.modules.clustercast.v2.AutomationDisposition
import com.byd.clusternav.cast.platform.CastAppEntry
import com.byd.clusternav.modules.clustercast.v2.CastAppPresentation
import com.byd.clusternav.modules.clustercast.v2.CastAppRow

/**
 * Preference-only app policy editor. It never observes or mutates Cast runtime state: Cast requests
 * are delegated to [Actions.cast], and every durable write goes through the injected callbacks that
 * the Activity runs off the main thread.
 */
object CastAppManagerDialog {

    /** Durable side effects owned by the caller; the dialog only renders exported controls. */
    interface Actions {
        fun setFavorite(packageName: String, enabled: Boolean)
        fun setUserProtection(packageName: String, enabled: Boolean)
        fun setDefault(packageName: String?)
        fun enableAutoCast()
        fun disableAutoCast()
        fun cast(packageName: String)
        fun setClusterDensityDpi(packageName: String, densityDpi: Int?)
        fun setClusterStyle(packageName: String, rect: Boolean)
        fun setBubbleEnabled(enabled: Boolean)
        fun openOverlaySettings()
    }

    data class Model(
        val entries: List<CastAppEntry>,
        val defaultPackage: String?,
        val disposition: AutomationDisposition,
        val castExported: Boolean,
        val bubbleEnabled: Boolean,
        val overlayPermitted: Boolean,
        val densityByPackage: Map<String, Int> = emptyMap(),
        val rectStylePackages: Set<String> = emptySet(),
    )

    /**
     * Any durable change closes this immutable view; the list is rebuilt from fresh truth on the next
     * open, so no control can act on stale rows. The handle is per-show, never shared state.
     */
    fun show(context: Context, model: Model, actions: Actions, onChanged: () -> Unit) {
        var dismiss: () -> Unit = {}
        val invalidate: (() -> Unit) -> Unit = { changed -> changed(); dismiss() }
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + .5f).toInt()
        val icons = model.entries.associate { it.packageName to it.icon }
        val labels = model.entries.associate { it.packageName to it.label }
        val rows = CastAppPresentation.rows(model.entries.map { it.row() }, model.defaultPackage)

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), dp(16))
        }
        val empty = TextView(context).apply {
            text = "Không có ứng dụng khớp tìm kiếm"
            visibility = View.GONE
            setPadding(0, dp(18), 0, dp(18))
        }
        val search = EditText(context).apply {
            hint = "Tìm theo tên hoặc package"
            contentDescription = "Tìm ứng dụng"
            setSingleLine()
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), 0)
            addView(autoCastCard(context, ::dp, model, labels, actions) { invalidate(onChanged) })
            addView(bubbleCard(context, ::dp, model, actions) { invalidate(onChanged) })
            addView(search)
            addView(empty)
        }

        fun render(query: String?) {
            list.removeAllViews()
            val visible = CastAppPresentation.search(rows, query)
            empty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
            visible.forEach { row ->
                list.addView(rowView(context, ::dp, row, icons[row.packageName]) {
                    showDetails(context, ::dp, row, icons[row.packageName], model, actions) { invalidate(onChanged) }
                })
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = render(s?.toString())
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
        render(null)

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(container)
            addView(list)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle("Quản lý ứng dụng")
            .setView(ScrollView(context).apply {
                addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            })
            .setPositiveButton("Xong", null)
            .show()
        dismiss = { runCatching { dialog.dismiss() }; Unit }
        dialog.setOnDismissListener { dismiss = {} }
    }

    private fun autoCastCard(
        context: Context,
        dp: (Int) -> Int,
        model: Model,
        labels: Map<String, String>,
        actions: Actions,
        invalidate: () -> Unit,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(12))
        val target = model.defaultPackage
        addView(TextView(context).apply {
            text = "Tự động chiếu sau khi khởi động"
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(context).apply {
            text = when {
                target == null -> "Chưa chọn ứng dụng mặc định"
                else -> "Mặc định: ${labels[target] ?: target} · ${dispositionText(model.disposition)}"
            }
            textSize = 13f
        })
        val enabled = model.disposition != AutomationDisposition.DISABLED &&
            model.disposition != AutomationDisposition.REVIEW_REQUIRED
        addView(Button(context).apply {
            text = if (enabled) "Tắt tự động chiếu" else "Bật tự động chiếu"
            isEnabled = target != null
            contentDescription = text
            setOnClickListener {
                isEnabled = false
                if (enabled) {
                    actions.disableAutoCast()
                    invalidate()
                } else {
                    confirmConsent(context, labels[target] ?: target.orEmpty(), onCancel = { isEnabled = true }) {
                        actions.enableAutoCast()
                        invalidate()
                    }
                }
            }
        })
    }

    /** Bubble desired-enabled is independent from auto-cast and never sends a Cast Stop. */
    private fun bubbleCard(
        context: Context,
        dp: (Int) -> Int,
        model: Model,
        actions: Actions,
        invalidate: () -> Unit,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(12))
        addView(TextView(context).apply {
            text = "Nút nổi (bubble)"
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(context).apply {
            text = when {
                !model.overlayPermitted -> "Cần quyền vẽ trên ứng dụng khác để hiện nút nổi"
                model.bubbleEnabled -> "Đang bật · chạm để mở menu, kéo để di chuyển"
                else -> "Đang tắt"
            }
            textSize = 13f
        })
        addView(Button(context).apply {
            text = if (model.bubbleEnabled) "Tắt nút nổi" else "Bật nút nổi"
            contentDescription = text
            minimumHeight = dp(56)
            setOnClickListener {
                isEnabled = false
                actions.setBubbleEnabled(!model.bubbleEnabled)
                invalidate()
            }
        })
        if (!model.overlayPermitted) {
            addView(Button(context).apply {
                text = "Mở cài đặt quyền hiển thị"
                contentDescription = text
                minimumHeight = dp(56)
                setOnClickListener { actions.openOverlaySettings() }
            })
        }
    }

    private fun dispositionText(disposition: AutomationDisposition): String = when (disposition) {
        AutomationDisposition.DISABLED -> "đang tắt"
        AutomationDisposition.REVIEW_REQUIRED -> "cần xem lại thông báo hệ quả"
        AutomationDisposition.ARMED -> "đã bật, chờ lần khởi động sau"
        AutomationDisposition.PENDING -> "đã ghi yêu cầu cho lần khởi động này"
        AutomationDisposition.DEFERRED -> "hoãn vì còn việc dở"
        AutomationDisposition.CLAIMED -> "đang thực hiện"
        AutomationDisposition.COMPLETED -> "đã hoàn tất và xác minh"
        AutomationDisposition.BLOCKED -> "đã chặn, cần thao tác tay"
        AutomationDisposition.SUPERSEDED -> "đã bị thay thế bởi lựa chọn khác"
    }

    /** Versioned consequence disclosure. Cancel writes nothing; only Accept enables automation. */
    private fun confirmConsent(
        context: Context,
        label: String,
        onCancel: () -> Unit = {},
        onAccept: () -> Unit,
    ) {
        AlertDialog.Builder(context)
            .setTitle("Xác nhận tự động chiếu")
            .setMessage(
                "Sau khi xe khởi động và mở khoá, Cluster Cast có thể chạy một service hiển thị " +
                    "và tự chiếu \"$label\" lên cụm đồng hồ mà không cần bạn bấm thêm.\n\n" +
                    "Hệ thống chỉ chiếu sau khi kiểm tra Dừng, thao tác tay, phục hồi, journal và " +
                    "ứng dụng đích. Bạn có thể tắt bất cứ lúc nào."
            )
            .setNegativeButton("Huỷ") { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .setPositiveButton("Đồng ý bật") { _, _ -> onAccept() }
            .show()
    }

    private fun rowView(
        context: Context,
        dp: (Int) -> Int,
        row: CastAppRow,
        icon: android.graphics.drawable.Drawable?,
        onOpen: () -> Unit,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        setPadding(0, dp(8), 0, dp(8))
        isClickable = true
        isFocusable = true
        contentDescription = "${row.label}. ${protectionText(row.protection)}." +
            if (row.isDefault) " Đang là mặc định." else ""
        setOnClickListener { onOpen() }
        addView(ImageView(context).apply {
            if (icon != null) setImageDrawable(icon) else setImageResource(android.R.drawable.sym_def_app_icon)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(12) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = if (row.isDefault) "${row.label} · mặc định" else row.label
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = buildString {
                    append(row.packageName)
                    append(" · ")
                    append(protectionText(row.protection))
                    row.unavailableReason?.let { append(" · ").append(unavailableText(it)) }
                }
                textSize = 12f
            })
        })
    }

    private fun showDetails(
        context: Context,
        dp: (Int) -> Int,
        row: CastAppRow,
        icon: android.graphics.drawable.Drawable?,
        model: Model,
        actions: Actions,
        invalidate: () -> Unit,
    ) {
        val exported = CastAppPresentation.actions(
            row,
            castExported = model.castExported,
            hasOtherDefault = model.defaultPackage != null && !row.isDefault,
        )
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(TextView(context).apply {
                text = buildString {
                    append(row.packageName).append('\n')
                    append("Phân loại: ").append(protectionText(row.protection)).append('\n')
                    append(row.unavailableReason?.let(::unavailableText) ?: "Có thể dùng")
                    if (row.protectionLocked) append("\nPhân loại này bị khoá theo bằng chứng hệ thống.")
                }
                textSize = 13f
            })
        }
        fun action(label: String, description: String, run: () -> Unit) {
            body.addView(Button(context).apply {
                text = label
                contentDescription = description
                minimumHeight = dp(56)
                setOnClickListener { isEnabled = false; run(); invalidate() }
            })
        }
        if (AppRowAction.CAST in exported) {
            action("Chiếu lên cụm", "Chiếu ${row.label} lên cụm") { actions.cast(row.packageName) }
        }
        if (AppRowAction.ADD_FAVORITE in exported) {
            action("Thêm vào yêu thích", "Thêm ${row.label} vào yêu thích") { actions.setFavorite(row.packageName, true) }
        }
        if (AppRowAction.REMOVE_FAVORITE in exported) {
            action("Bỏ khỏi yêu thích", "Bỏ ${row.label} khỏi yêu thích") { actions.setFavorite(row.packageName, false) }
        }
        if (AppRowAction.SET_DEFAULT in exported) {
            action("Đặt làm mặc định", "Đặt ${row.label} làm ứng dụng mặc định") { actions.setDefault(row.packageName) }
        }
        if (AppRowAction.REPLACE_DEFAULT in exported) {
            action("Thay ứng dụng mặc định", "Thay mặc định bằng ${row.label}") { actions.setDefault(row.packageName) }
        }
        if (AppRowAction.CLEAR_DEFAULT in exported) {
            action("Bỏ ứng dụng mặc định", "Bỏ mặc định ${row.label}") { actions.setDefault(null) }
        }
        if (row.available && row.protection != AppProtectionSource.UNKNOWN_PROTECTED) {
            val rect = row.packageName in model.rectStylePackages
            body.addView(TextView(context).apply {
                text = "Kiểu cụm: " + if (rect) "thẳng · dùng hết khung" else "cong · giữ đồng hồ km/h"
                textSize = 13f
            })
            action(
                if (rect) "Dùng kiểu cong (giữ km/h)" else "Dùng kiểu thẳng (full khung)",
                "Đổi kiểu cụm cho ${row.label}",
            ) { actions.setClusterStyle(row.packageName, !rect) }
            val current = model.densityByPackage[row.packageName]
            body.addView(TextView(context).apply {
                text = "Cỡ chữ trên cụm (DPI): " + (current?.toString() ?: "theo mặc định của cụm")
                textSize = 13f
            })
            listOf(null, 160, 180, 200, 240).forEach { dpi ->
                action(
                    dpi?.let { "DPI $it" } ?: "DPI mặc định",
                    "Đặt DPI cụm cho ${row.label}: " + (dpi?.toString() ?: "mặc định"),
                ) { actions.setClusterDensityDpi(row.packageName, dpi) }
            }
        }
        if (AppRowAction.ADD_USER_PROTECTION in exported) {
            action("Nâng cao · giữ phiên", "Giữ phiên cho ${row.label}, không force-stop") {
                actions.setUserProtection(row.packageName, true)
            }
        }
        if (AppRowAction.REMOVE_USER_PROTECTION in exported) {
            action("Nâng cao · bỏ giữ phiên", "Bỏ giữ phiên cho ${row.label}") {
                actions.setUserProtection(row.packageName, false)
            }
        }
        AlertDialog.Builder(context)
            .setTitle(row.label)
            .setIcon(icon)
            .setView(ScrollView(context).apply { addView(body) })
            .setPositiveButton("Đóng", null)
            .show()
    }

    private fun protectionText(source: AppProtectionSource): String = when (source) {
        AppProtectionSource.NORMAL -> "Ứng dụng thường"
        AppProtectionSource.SYSTEM_PROJECTION -> "Phiên hệ thống · khoá"
        AppProtectionSource.USER_KEEP_SESSION -> "Giữ phiên theo lựa chọn"
        AppProtectionSource.UNKNOWN_PROTECTED -> "Chưa xác định · khoá an toàn"
    }

    private fun unavailableText(reason: AppUnavailableReason): String = when (reason) {
        AppUnavailableReason.NOT_INSTALLED -> "Không còn cài đặt · hãy bỏ hoặc thay mặc định"
        AppUnavailableReason.NO_LAUNCHER -> "Không có launcher · hãy bỏ hoặc thay mặc định"
        AppUnavailableReason.PROTECTION_UNKNOWN -> "Chưa đủ bằng chứng phân loại"
    }
}
