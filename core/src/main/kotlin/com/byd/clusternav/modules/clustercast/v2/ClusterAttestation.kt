package com.byd.clusternav.modules.clustercast.v2

/**
 * Xác nhận bằng MẮT NGƯỜI rằng cụm đang hiện app.
 *
 * Vì sao phải có kiểu này, thay vì cứ suy từ dumpsys: phiên xe 2026-07-27 19:38–19:40 đã đo trực tiếp
 * với chủ xe xác nhận cả hai đầu — cụm hiện map, rồi cụm hiện đồng hồ — trong khi task nằm y nguyên
 * trên display 1. Không một tín hiệu chỉ-đọc nào của Android khác nhau giữa hai trạng thái đó
 * (`dumpsys display`, `window`, `am stack list`, `SurfaceFlinger`, `service list`, `getprop`,
 * `settings`, `screencap -d 1`, và transaction 1..10 của AutoContainer). Bằng chứng nằm ở
 * `RealFixtureParsingTest` cùng hai fixture `*HUMAN-CONFIRMED*`.
 *
 * Hệ quả: app KHÔNG BAO GIỜ tự biết cụm hiện gì. Nếu không mô hình hoá điều đó, app chỉ còn hai lựa
 * chọn và cả hai đều sai — nói dối "đang chiếu", hoặc chờ mãi một xác nhận không bao giờ tới. Kiểu này
 * biến câu trả lời của người dùng thành dữ liệu hành động được.
 */
enum class AttestationAnswer {
    /** Người dùng nhìn cụm và thấy app. */
    CLUSTER_SHOWS_APP,

    /** Người dùng nhìn cụm và KHÔNG thấy app. Đây là dữ liệu, không phải thất bại im lặng. */
    CLUSTER_DOES_NOT_SHOW_APP,
}

/**
 * Một lần xác nhận, gắn với đúng phiên nào.
 *
 * Gắn theo [epoch] và [targetPackage] để một lời xác nhận cũ không được dùng cho phiên mới: chiếu có
 * thể tự đóng (tắt máy, OEM tự quyết) mà app không đo được, nên xác nhận KHÔNG có giá trị vĩnh viễn.
 */
data class ClusterAttestation(
    val answer: AttestationAnswer,
    val targetPackage: String,
    val epoch: Long,
    val atEpochMillis: Long,
) {
    /** Lời xác nhận chỉ dùng được cho đúng phiên và đúng target đã hỏi. */
    fun appliesTo(targetPackage: String, epoch: Long): Boolean =
        this.targetPackage == targetPackage && this.epoch == epoch
}

/** Việc app nên làm tiếp, suy ra từ phần đo được cộng lời người xác nhận (nếu có). */
enum class AttestationNeed {
    /** Chưa đặt được task lên cụm thì chưa có gì để hỏi. */
    NOTHING_TO_ATTEST,

    /** Đo được đã đủ, nhưng chưa ai nhìn cụm. Phải hỏi, và trong lúc chờ KHÔNG được nói "đang chiếu". */
    ASK_USER,

    /** Người dùng nói không thấy app: phát lại chuỗi mở chiếu, vì đó là việc duy nhất còn nghĩa. */
    REISSUE_PROJECTION,

    /** Người dùng đã xác nhận cụm hiện app cho đúng phiên này. */
    SETTLED,
}

object ClusterAttestations {

    /**
     * Suy ra việc cần làm.
     *
     * `placedOnCluster` là phần ĐO ĐƯỢC: task đúng display, đúng target, đúng khung. Nó không nói gì về
     * hình ảnh trên cụm — đó là lý do tồn tại của hàm này.
     */
    fun need(
        placedOnCluster: Boolean,
        targetPackage: String?,
        epoch: Long,
        attestation: ClusterAttestation?,
    ): AttestationNeed {
        if (!placedOnCluster || targetPackage == null) return AttestationNeed.NOTHING_TO_ATTEST
        val current = attestation?.takeIf { it.appliesTo(targetPackage, epoch) } ?: return AttestationNeed.ASK_USER
        return when (current.answer) {
            AttestationAnswer.CLUSTER_SHOWS_APP -> AttestationNeed.SETTLED
            AttestationAnswer.CLUSTER_DOES_NOT_SHOW_APP -> AttestationNeed.REISSUE_PROJECTION
        }
    }
}
