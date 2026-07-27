package com.byd.clusternav.carexec

import com.byd.clusternav.modules.clustercast.v2.CommandKind
import com.byd.clusternav.modules.clustercast.v2.ReadOnlyShellRequest
import com.byd.clusternav.modules.clustercast.v2.ShellGateway
import com.byd.clusternav.modules.clustercast.v2.ShellResult

/**
 * Transport phát lại output đã ghi của xe thật.
 *
 * Đây là mảnh còn thiếu để bộ quan sát kiểm được **off-car trên dữ liệu thật**. Trước đó parser chỉ được
 * kiểm bằng chuỗi do người viết test tự bịa — và ngày 2026-07-27 chính tôi đã đọc sai output thật
 * (`Stack id=26 ... displayId=1` nằm ngay dòng đầu mà tôi kết luận là cụm rỗng). Chuỗi tự bịa không bắt
 * được loại sai đó; output thật thì bắt được.
 *
 * Thiếu bản ghi cho một lệnh thì trả [ShellResult.Failure] thay vì trả rỗng: rỗng sẽ bị parser hiểu là
 * "đọc được và không có gì", tức lại biến thiếu dữ liệu thành một kết luận sai.
 */
class RecordedDevice(private val recordings: Map<CommandKind, String>) : ShellGateway {

    override fun execute(request: ReadOnlyShellRequest): ShellResult {
        val recorded = recordings[request.kind]
            ?: return ShellResult.Failure(null, "không có bản ghi cho ${request.kind}", 0)
        return ShellResult.Success(recorded, "", 0)
    }

    override fun close() = Unit

    companion object {
        /**
         * Bản ghi lấy từ docs/refactor-car-execution/fixtures — output thật của head unit DiLink3.
         * [read] do caller cấp để :core không cần biết đọc file bằng gì.
         */
        fun fromFixtures(read: (String) -> String?): RecordedDevice {
            val map = LinkedHashMap<CommandKind, String>()
            fun put(kind: CommandKind, vararg names: String) {
                names.firstNotNullOfOrNull(read)?.let { map[kind] = it }
            }
            put(CommandKind.AM_STACK_LIST, "am-stack-list-occupied.txt")
            put(CommandKind.WM_DISPLAYS, "dumpsys-display-occupied.txt")
            put(CommandKind.DISPLAY_STATE, "dumpsys-display-occupied.txt")
            put(CommandKind.PROFILE_STATE, "globals-occupied.txt")
            put(CommandKind.ANIMATION_STATE, "globals-occupied.txt")
            // Chụp được ở phiên 27/7 chiều (`appops get com.byd.clusternav` trên xe thật). Trước đó
            // quan sát off-car dừng ở đây và nói rõ thiếu gì, thay vì bịa một chuỗi rỗng rồi để parser
            // kết luận "đọc được và không có gì".
            put(CommandKind.APP_OPS_STATE, "appops-get-clusternav.txt")
            return RecordedDevice(map)
        }
    }
}
