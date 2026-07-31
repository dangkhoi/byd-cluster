package com.byd.clusternav.modules.clustercast.v2

import java.io.IOException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Khoá việc nối dây `RecoverySubstate.OBSERVATION_DIVERGED_STOP_AVAILABLE`/`_WAITING` — hai substate ĐÃ
 * có sẵn hàng trong `CastUiStateProjector.recoveryRows` từ trước nhưng chưa từng được `CastRuntimeUi`
 * gán, nên mọi ca "bền ghi rảnh/đã xác minh, quan sát thật lại thấy khác" đều rơi vào
 * `failClosed`/`CONTRACT_UNMAPPED` — một câu SAI ("chưa nhận diện được"), vì hệ thống thực ra đã đúng khi
 * phát hiện ra sự lệch, chỉ là không có nhánh nào nói ra điều đó.
 *
 * Đo trên DiLink3 2026-07-31 (docs/specs/cast-recovery-honesty-and-multi-occupant.html §R2): `stable=`
 * ghi `IDLE_VERIFIED` (từ cold-bootstrap, cụm thật sự trống lúc đó), nhưng lần quan sát kế tiếp thấy
 * `app.revanced.android.apps.maps` VÀ `vn.vietmap.live` cùng chiếm cụm — không do transaction nào của
 * ClusterNav gây ra. Bubble lúc đó hiện "Không xác định được app đang mở" khi bấm Cast, và hoàn toàn
 * không có Dừng nào khả dụng để dọn lại.
 */
class CastObservationDivergedTest {
    private fun store(stopRequested: Boolean = false): CastSessionStore {
        val store = CastSessionStore(MemoryAtomicBytes())
        val stable = StableCastSession(
            StableState.IDLE_VERIFIED, EngineVersion.V2, "runtime-migration", null, "display-2",
            CastBaseline(
                geometry = AcceptedGeometry(CastRect(0, 0, 1920, 720), 180, "android-user-0"),
                profile = "android-user-0",
            ),
            null, null, null, 1,
        )
        store.locked {
            initialize("boot")
            update { it.copy(effectiveUiVersion = EngineVersion.V2, stableSession = stable, stopRequested = stopRequested) }
        }
        return store
    }

    private fun multiOccupantObservation() = ObservationValue.Known(
        ObservedState(
            ObservedCoarseState.ACTIVE_MULTI, "display-2", null,
            setOf("app.revanced.android.apps.maps", "vn.vietmap.live"), null,
            null, emptyMap(), null, "android-user-0", "fission_bg_xdja",
        ),
    )

    @Test
    fun `an idle-verified claim contradicted by a live multi-occupant observation offers a real Stop, not CONTRACT_UNMAPPED`() {
        val storeRead = store().locked { read() }
        val model = CastRuntimeUi.render(storeRead, multiOccupantObservation())

        assertTrue(
            model.actions.any { it.action == CastAction.STOP && it.enabled },
            "expected STOP to be enabled so the driver can reclaim the cluster, actions=${model.actions}",
        )
        assertTrue(
            model.title != "Cần xử lý thủ công" && model.status != "Chưa nhận diện được trạng thái cụm",
            "must not fall back to the generic unmapped-contract message when the mismatch IS identified: title='${model.title}' status='${model.status}'",
        )
    }

    @Test
    fun `the same divergence with a Stop already requested waits instead of re-offering Stop`() {
        val storeRead = store(stopRequested = true).locked { read() }
        val model = CastRuntimeUi.render(storeRead, multiOccupantObservation())

        assertTrue(
            model.actions.none { it.action == CastAction.STOP && it.enabled },
            "a Stop already in flight must not offer a second one, actions=${model.actions}",
        )
    }

    @Test
    fun `a converged idle-verified claim is unaffected — no false divergence`() {
        val storeRead = store().locked { read() }
        val trulyIdle = ObservationValue.Known(
            ObservedState(
                ObservedCoarseState.IDLE_CLEAN, "display-2", null, emptySet(), null,
                AcceptedGeometry(CastRect(0, 0, 1920, 720), 180, "android-user-0"),
                emptyMap(), null, "android-user-0", "fission_bg_xdja",
            ),
        )
        val model = CastRuntimeUi.render(storeRead, trulyIdle)
        assertEquals("Cluster Cast sẵn sàng", model.title)
    }

    private class MemoryAtomicBytes : AtomicBytes {
        private var bytes: ByteArray? = null
        override fun exists() = bytes != null
        override fun read(): ByteArray = bytes?.copyOf() ?: throw IOException("missing")
        override fun write(bytes: ByteArray) { this.bytes = bytes.copyOf() }
    }
}
