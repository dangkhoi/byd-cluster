package com.byd.clusternav.modules.clustercast.v2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Mắt người là cảm biến hạng nhất, nên đường đi của nó phải được kiểm như mọi máy móc khác.
 *
 * Nền của bài kiểm: phiên xe 2026-07-27 chứng minh không tín hiệu Android nào phân biệt "cụm hiện app"
 * và "cụm hiện đồng hồ". Vì thế mọi trạng thái ở đây phải có việc làm tiếp — không ca nào được rơi vào
 * "chờ mãi", đúng tinh thần R14.
 */
class ClusterAttestationTest {

    private val pkg = "vn.vietmap.live"

    @Test
    fun `chua dat duoc task thi khong co gi de hoi`() {
        assertEquals(
            AttestationNeed.NOTHING_TO_ATTEST,
            ClusterAttestations.need(placedOnCluster = false, targetPackage = pkg, epoch = 3, attestation = null),
        )
    }

    @Test
    fun `dat duoc nhung chua ai nhin cum thi phai hoi`() {
        assertEquals(
            AttestationNeed.ASK_USER,
            ClusterAttestations.need(placedOnCluster = true, targetPackage = pkg, epoch = 3, attestation = null),
        )
    }

    @Test
    fun `nguoi dung noi khong thay thi phat lai chuoi mo chieu`() {
        val said = ClusterAttestation(AttestationAnswer.CLUSTER_DOES_NOT_SHOW_APP, pkg, 3, 1_000)
        assertEquals(
            AttestationNeed.REISSUE_PROJECTION,
            ClusterAttestations.need(true, pkg, 3, said),
        )
    }

    @Test
    fun `nguoi dung noi thay thi phien coi nhu xong`() {
        val said = ClusterAttestation(AttestationAnswer.CLUSTER_SHOWS_APP, pkg, 3, 1_000)
        assertEquals(AttestationNeed.SETTLED, ClusterAttestations.need(true, pkg, 3, said))
    }

    @Test
    fun `xac nhan cua phien cu khong dung cho phien moi`() {
        // Chiếu có thể tự đóng (tắt máy, OEM tự quyết) mà app không đo được. Một lời xác nhận cũ vì thế
        // KHÔNG có giá trị vĩnh viễn — nếu không chặn, app sẽ nói "đang chiếu" cho một phiên đã chết.
        val old = ClusterAttestation(AttestationAnswer.CLUSTER_SHOWS_APP, pkg, epoch = 3, atEpochMillis = 1_000)
        assertEquals(AttestationNeed.ASK_USER, ClusterAttestations.need(true, pkg, epoch = 4, attestation = old))
        assertEquals(AttestationNeed.ASK_USER, ClusterAttestations.need(true, "com.other.app", 3, old))
    }

    @Test
    fun `moi to hop deu co viec lam tiep`() {
        // Quét vét cạn: đặt/không đặt × có/không target × ba khả năng xác nhận × hai epoch.
        var checked = 0
        listOf(false, true).forEach { placed ->
            listOf(null, pkg).forEach { target ->
                listOf(
                    null,
                    ClusterAttestation(AttestationAnswer.CLUSTER_SHOWS_APP, pkg, 3, 1_000),
                    ClusterAttestation(AttestationAnswer.CLUSTER_DOES_NOT_SHOW_APP, pkg, 3, 1_000),
                ).forEach { attestation ->
                    listOf(3L, 4L).forEach { epoch ->
                        checked++
                        val need = ClusterAttestations.need(placed, target, epoch, attestation)
                        assertTrue(need in AttestationNeed.entries, "$need không thuộc tập đã khai báo")
                    }
                }
            }
        }
        assertEquals(24, checked)
    }
}
