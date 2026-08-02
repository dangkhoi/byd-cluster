package com.byd.clusternav.modules.clustercast.v2

/**
 * Các bộ đọc output thiết bị, tách khỏi CastAndroidRuntime ngày 2026-07-27.
 *
 * Tất cả đều là parse chuỗi thuần — 0 lần dùng Context, 0 lần dùng dadb — nhưng trước đó chúng nằm
 * trong file Android nên không thể test mà không dựng runtime, và transport thì không dùng được chúng
 * nếu không kéo theo cả Android.
 *
 * Ngày 2026-07-31 thêm [CastAmTaskBoundsParser] (task T6/B3): khung của TỪNG TASK trên cụm, thứ mà
 * phép xác minh geometry vẫn thiếu — xem KDoc của nó và của `ObservedState.taskBounds`.
 */

/**
 * Đọc `bounds=[l,t][r,b]` của TỪNG DÒNG TASK trong `am stack list`.
 *
 * ★ VÌ SAO TÁCH RIÊNG, VÀ VÌ SAO KHÔNG PHẢI PARSER THỨ HAI (CLAUDE.md §7 + bài học "hai parser lệch nhau"):
 *   [CastAmStackParser] vẫn là NGUỒN DUY NHẤT quyết định *dòng nào là dòng task*, *task đó id bao nhiêu*,
 *   *thuộc stack/display nào*, *visible ra sao* — kể cả việc từ chối dump dị dạng. Object này KHÔNG tự
 *   nhận diện topology: nó chỉ chạy SAU khi [CastAmStackParser] đã parse thành công, và chỉ gắn thêm một
 *   trường cho những `taskId` mà parser kia ĐÃ chấp nhận ([keep]). Task nào parser kia không thấy thì ở
 *   đây cũng không tồn tại ⇒ hai bên không thể "bất đồng ý kiến" về topology, chỉ có thể cùng đúng hoặc
 *   cùng không thấy gì.
 *
 *   Lý tưởng thì trường này nằm thẳng trong `CastAmTaskRecord`. Không làm vậy trong đợt này vì
 *   `CastAmStackParser.kt` đang được một agent khác sửa song song trên cùng cây làm việc — đụng vào là
 *   mất bài của nhau. Đây là **nợ có chủ ý**: khi hai nhánh nhập xong, gộp [BOUNDS] vào
 *   `CastAmStackParser` và cho `CastAmTaskRecord` mang luôn `bounds`.
 *
 * ĐỊNH DẠNG (nguyên văn dump DiLink3 tối 2026-07-31, rút gọn phần topActivity):
 *   `  taskId=12: app.revanced.android.apps.maps/com.google.android.maps.MapsActivity bounds=[0,90][1920,810] userId=0 visible=true topActivity=ComponentInfo{...}`
 *   Có dòng task HỢP LỆ mà KHÔNG in `bounds=` — khi đó task ấy vắng mặt trong map, không bịa rect.
 *
 * [BOUNDS] cố ý giống HỆT `StackParse.RE_BOUNDS` (StackParse.kt:57) — cùng một cú pháp dump thì phải
 * cùng một cách đọc. Sửa một bên nhớ sửa bên kia; `CastTaskBoundsObservationTest` khoá việc hai bên đọc
 * ra cùng bốn con số trên cùng một dòng thật.
 */
internal object CastAmTaskBoundsParser {
    /** Cùng chuỗi mẫu với `StackParse.RE_BOUNDS` (StackParse.kt:57) — xem KDoc của object. */
    private val BOUNDS = Regex("bounds=\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]")
    private val TASK_ID = Regex("^(?:taskId|Task\\s+id)\\s*[=:]\\s*(\\d+)\\s*:", RegexOption.IGNORE_CASE)

    /**
     * [keep] = tập `taskId` mà [CastAmStackParser] đã chấp nhận VÀ ta còn quan tâm (thường là các task
     * trên đúng display cụm). Dòng nào có id ngoài tập này thì bỏ qua — phạm vi tường minh, không quét mù.
     */
    fun parse(raw: String, keep: Set<Int>): Map<Int, CastRect> {
        if (keep.isEmpty()) return emptyMap()
        val out = LinkedHashMap<Int, CastRect>()
        for (rawLine in raw.lineSequence()) {
            val line = rawLine.trim()
            val taskId = TASK_ID.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (taskId !in keep) continue
            val matches = BOUNDS.findAll(line).take(2).toList()
            // Không có `bounds=` → task này chỉ đơn giản là chưa biết khung (dump thật có ca đó).
            // Có HAI `bounds=` trên cùng dòng → firmware in kiểu ta chưa từng thấy; trả về "chưa biết"
            // thay vì đoán cái đầu tiên (CLAUDE.md §2: không có dữ liệu thì nói chưa biết).
            val single = matches.singleOrNull() ?: continue
            val n = single.groupValues
            val left = n[1].toIntOrNull() ?: continue
            val top = n[2].toIntOrNull() ?: continue
            val right = n[3].toIntOrNull() ?: continue
            val bottom = n[4].toIntOrNull() ?: continue
            out[taskId] = CastRect(left, top, right, bottom)
        }
        return out
    }
}

object DumpObservedStateParser : ObservedStateParser {
    private val BOUNDS = Regex("(?:mBounds|bounds)\\s*=\\s*(?:Rect\\()?\\[?(\\d+)[, ]+(\\d+)\\]?[ ,\\-]+\\[?(\\d+)[, ]+(\\d+)\\]?\\)?", RegexOption.IGNORE_CASE)
    private val DENSITY = Regex("(?:densityDpi|density)\\s*[=: ]\\s*(\\d+)", RegexOption.IGNORE_CASE)
    private val PROJECTION_HINTS = setOf("carplay", "androidauto", "android.auto", "projection")
    private val APP_OPS_PACKAGE = Regex("(?im)^\\s*Package\\s+([A-Za-z][A-Za-z0-9_.]+)\\s*:")
    private val PIP_MODE = Regex(
        "PICTURE_IN_PICTURE\\s*:\\s*(allow|ignore|deny|default|foreground)",
        RegexOption.IGNORE_CASE,
    )

    /** A measured fallback keeps the runtime usable when the dump wording changes. */
    fun withFallback(measured: () -> NamedClusterDisplay?): ObservedStateParser =
        ObservedStateParser { raw -> parse(raw, measured) }

    override fun parse(raw: RawObservation): ObservationValue<ObservedState> = parse(raw) { null }

    fun parse(
        raw: RawObservation,
        measured: () -> NamedClusterDisplay?,
    ): ObservationValue<ObservedState> {
        val namedDisplay = discoverClusterDisplay(raw.displays)
            ?: measured()?.also { CastOperationLog.record("display identity from DisplayManager fallback: ${it.id}") }
            ?: return ObservationValue.Unknown(MISSING_NAMED_CLUSTER_DISPLAY_REASON)
        val display = namedDisplay.id
        val am = when (val parsed = CastAmStackParser.parse(raw.amStacks)) {
            is CastDumpParse.Known -> parsed.value
            is CastDumpParse.Malformed -> return ObservationValue.Unknown(parsed.reason)
        }
        if (am.stacks.isEmpty()) return ObservationValue.Unknown("AM stack topology is empty")
        val displayTasks = am.tasks.filter { it.displayId == display }
        val occupants = displayTasks.map { it.packageName }.toSet()
        val coarse = when (occupants.size) {
            0 -> ObservedCoarseState.IDLE_CLEAN
            1 -> ObservedCoarseState.ACTIVE_SINGLE
            else -> ObservedCoarseState.ACTIVE_MULTI
        }
        val records = displayTasks.map { Triple(it.packageName, it.taskId, it.visible) }
        // Measured on DiLink3 2026-07-31 (docs/specs/cast-recovery-honesty-and-multi-occupant.html §R2):
        // two distinct packages (app.revanced.android.apps.maps, vn.vietmap.live) both read visible=true
        // on the same display. `coarse` above already names this correctly as ACTIVE_MULTI — returning
        // `Unknown` right after THROWS AWAY that already-known fact. `target == null` here is not "we
        // could not tell": it is the honest answer "more than one app claims the screen, and nothing in
        // this dump says which one truly owns it" — that is real, nameable data for the render layer to
        // act on (see CastUiStateProjector), not a parse failure.
        val targetRecord = when (records.size) {
            0 -> null
            1 -> records.single()
            else -> records.filter { it.third == true }.singleOrNull()
        }
        val target = targetRecord?.let { CastTarget(it.first, it.second, display) }
        val residueRecords = records.filter { it != targetRecord }.filter { record ->
            PROJECTION_HINTS.any { record.first.contains(it, true) }
        }
        if (residueRecords.size > 1) return ObservationValue.Unknown("multiple protected residues")
        val residue = residueRecords.singleOrNull()?.let {
            ProtectedResidue(
                it.first,
                it.second,
                if (it.third == false) ResidueVisibility.HIDDEN else ResidueVisibility.UNKNOWN,
            )
        }
        val activeUser = raw.profile.trim().toIntOrNull()
            ?: return ObservationValue.Unsupported("active Android profile format unsupported")
        val profileId = "android-user-$activeUser"
        // Khung THẬT của từng task đang đậu trên cụm — xem KDoc `ObservedState.taskBounds`. Chỉ lấy task
        // đúng display cụm (`displayTasks`), và topology thì vẫn do CastAmStackParser ở trên quyết định.
        val taskBounds = CastAmTaskBoundsParser.parse(raw.amStacks, displayTasks.map { it.taskId }.toSet())
        // ★★ ĐÂY LÀ NGUỒN DỮ LIỆU CỦA PHÉP XÁC MINH GEOMETRY — đọc trước khi sửa CastCoordinator.
        //
        // `geometry` bên dưới là khung của **DISPLAY** (regex chạy trên `raw.wmDisplays + raw.displays`),
        // còn `CastTransaction.requestedGeometry` mà `CastCoordinator.completeVerificationLocked`
        // (CastCoordinator.kt:379-381) đem so bằng với nó lại là rect của **MỘT TASK** — chính là rect đi
        // vào `am task resize <taskId> l t r b` (CastGeometry.kt:45). Hai đại lượng khác loại: đẳng thức
        // chỉ có thể đúng khi rect yêu cầu tình cờ bằng nguyên khung display, nên MỌI lần resize thật đều
        // ra "verification diverged" rồi ghim transaction vào RECOVERING. Đó là lý do đường chỉnh kích
        // thước chưa từng chạy nổi một lần trên xe
        // (docs/specs/cast-one-mode-and-three-zone-bubble.html §F3, task T6/B3).
        //
        // TỪ NAY CÓ SẴN: `ObservedState.taskBounds[taskId]` và `ObservedState.targetTaskBounds` — khung
        // thật của task, đúng loại để so với `requestedGeometry.bounds`. T6 CỐ Ý CHỈ DỰNG DỮ LIỆU, KHÔNG
        // đổi phép so ở CastCoordinator: T7 mới tiêu thụ. Đổi cả hai trong một lần thì nếu hồi quy trên xe
        // không tách được là do đọc sai hay do so sai (CLAUDE.md §6: đường mới xuống cuối, tự đo rồi mới leo).
        //
        // Mức bằng chứng (CLAUDE.md §2): **đã chứng minh bằng đọc source** — hai file:line ở trên;
        // **chưa đo lại trên xe** sau bản vá. Định dạng dòng task là dump THẬT (DiLink3, 31/07).
        // `geometry` vẫn giữ nguyên nghĩa cũ (khung display) vì STOP/RECOVER và bootstrap đang so nó với
        // `baseline.geometry` — cũng là khung display — và hai đường đó ĐANG CHẠY TỐT ngoài hiện trường.
        val geometry = if (coarse == ObservedCoarseState.IDLE_CLEAN && target == null) {
            AcceptedGeometry(
                CastRect(0, 0, namedDisplay.appWidth, namedDisplay.appHeight),
                namedDisplay.densityDpi,
                profileId,
            )
        } else {
            val geometryText = raw.wmDisplays + "\n" + raw.displays
            val bounds = BOUNDS.find(geometryText)?.groupValues?.let { groups ->
                val left = groups[1].toIntOrNull()
                val top = groups[2].toIntOrNull()
                val right = groups[3].toIntOrNull()
                val bottom = groups[4].toIntOrNull()
                if (left == null || top == null || right == null || bottom == null) {
                    return ObservationValue.Unknown("display bounds numeric format unsupported")
                }
                CastRect(left, top, right, bottom)
            }
            val density = DENSITY.find(geometryText)?.groupValues?.get(1)?.toIntOrNull()
            bounds?.let { AcceptedGeometry(it, density, profileId) }
        }
        val animationLines = raw.animations.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (animationLines.size < 3) return ObservationValue.Unknown("animation settings incomplete")
        val animations = linkedMapOf(
            "window_animation_scale" to animationLines[0],
            "transition_animation_scale" to animationLines[1],
            "animator_duration_scale" to animationLines[2],
        )
        val pipMode = target?.packageName?.let { packageName ->
            val headers = APP_OPS_PACKAGE.findAll(raw.appOps).toList()
            val index = headers.indexOfFirst { it.groupValues[1].equals(packageName, true) }
            if (index < 0) {
                // ĐO TRÊN DiLink3 2026-08-01: `appops get vn.vietmap.live PICTURE_IN_PICTURE` trả
                // `No operations.\nDefault mode: allow` — format khác hoàn toàn với dump đầy đủ
                // (`appops get vn.vietmap.live` có `Package vn.vietmap.live:\n  PICTURE_IN_PICTURE: allow`).
                // Shell gateway trên xe đọc bằng lệnh NÀO phụ thuộc transport config — cả hai đều hợp lệ.
                // Trước fix này: format "No operations" → return Unknown → verification LUÔN fail cho mọi
                // app chưa từng bị đổi app-op. "No operations" có nghĩa chính xác là "không có override
                // nào đang active" = default mode = cho phép PIP.
                val noOps = raw.appOps.contains("No operations", ignoreCase = true) ||
                    raw.appOps.contains("Default mode", ignoreCase = true)
                return@let if (noOps) "default" else return ObservationValue.Unknown("target app-ops block unavailable")
            }
            val block = raw.appOps.substring(
                headers[index].range.first,
                headers.getOrNull(index + 1)?.range?.first ?: raw.appOps.length,
            )
            PIP_MODE.find(block)?.groupValues?.get(1)?.lowercase() ?: "default"
        }
        return ObservationValue.Known(
            ObservedState(
                coarse, "display-$display", target, occupants, residue, geometry,
                animations, pipMode, profileId, namedDisplay.name, taskBounds,
            ),
        )
    }
}

object ProjectionSessionEvidenceParser {
    private val BOOLEAN = Regex(
        "(?:connectedPhoneSession|mPhoneConnected|sessionConnected)\\s*[=:]\\s*(true|false)",
        RegexOption.IGNORE_CASE,
    )
    private val STATE = Regex("(?:connectionState|sessionState)\\s*[=:]\\s*(CONNECTED|DISCONNECTED|2|0)", RegexOption.IGNORE_CASE)

    fun parse(raw: String): Boolean? {
        BOOLEAN.find(raw)?.groupValues?.get(1)?.let { return it.equals("true", true) }
        return when (STATE.find(raw)?.groupValues?.get(1)?.uppercase()) {
            "CONNECTED", "2" -> true
            "DISCONNECTED", "0" -> false
            else -> null
        }
    }
}

