package com.byd.clusternav.modules.clustercast.v2

/**
 * Bảng lệnh opcode của OEM cho việc mở và đóng đường chiếu.
 *
 * Tách khỏi CastAndroidRuntime ngày 2026-07-27: đây là metadata thiết bị dạng chuỗi thuần, không gọi gì,
 * nên chỗ của nó là :core theo lưới kiến trúc (cột Shared).
 *
 * CHƯA SỬA, cố ý: tên service đang hardcode "AutoContainer", trong khi DiLink5 dùng "auto_container".
 * Đó là Q5 trong docs/refactor-car-execution/spec.html và phải sửa bằng cách lấy tên từ ClusterProfile —
 * nhưng giai đoạn này chỉ dời chỗ, không đổi hành vi.
 */

fun fixedSealDl3BootstrapCommand(kind: CommandKind): String? = when (kind) {
    CommandKind.SEAL_DL3_BOOTSTRAP_30 -> "service call AutoContainer 2 i32 1000 i32 30 s16 \"\""
    CommandKind.SEAL_DL3_BOOTSTRAP_31 -> "service call AutoContainer 2 i32 1000 i32 31 s16 \"\""
    CommandKind.SEAL_DL3_BOOTSTRAP_16 -> "service call AutoContainer 2 i32 1000 i32 16 s16 \"\""
    CommandKind.SEAL_DL3_BOOTSTRAP_35 -> "service call AutoContainer 2 i32 1000 i32 35 s16 \"\""
    CommandKind.SEAL_DL3_COMPENSATE_18 -> "service call AutoContainer 2 i32 1000 i32 18 s16 \"\""
    CommandKind.SEAL_DL3_COMPENSATE_0 -> "service call AutoContainer 2 i32 1000 i32 0 s16 \"\""
    else -> null
}
