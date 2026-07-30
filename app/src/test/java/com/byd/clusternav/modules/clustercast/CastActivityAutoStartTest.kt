package com.byd.clusternav.modules.clustercast

import com.byd.clusternav.modules.clustercast.v2.CastOperation
import com.byd.clusternav.modules.clustercast.v2.CastSessionEnvelope
import com.byd.clusternav.modules.clustercast.v2.CastTarget
import com.byd.clusternav.modules.clustercast.v2.CastTransaction
import com.byd.clusternav.modules.clustercast.v2.CastBaseline
import com.byd.clusternav.modules.clustercast.v2.EngineVersion
import com.byd.clusternav.modules.clustercast.v2.OperationPhase
import com.byd.clusternav.modules.clustercast.v2.StableCastSession
import com.byd.clusternav.modules.clustercast.v2.StableState
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Locks R6 (docs/specs/cast-simplified-active-app-toggle.html): auto-cast on open, never while busy. */
class CastActivityAutoStartTest {

    private fun idle() = CastSessionEnvelope(
        durableEpoch = 1, bootId = "boot", stopRequested = false, pendingIntent = null,
        effectiveUiVersion = EngineVersion.V2, pendingUiRollback = false,
        stableSession = null, transaction = null,
    )

    @Test
    fun `no default package configured means never auto-start`() {
        assertNull(autoStartTarget(null, armed = true, envelope = idle()))
    }

    @Test
    fun `envelope unavailable means never auto-start`() {
        assertNull(autoStartTarget("com.example.maps", armed = true, envelope = null))
    }

    @Test
    fun `idle cluster with a configured default starts it`() {
        assertEquals("com.example.maps", autoStartTarget("com.example.maps", armed = true, envelope = idle()))
    }

    /**
     * 2026-07-29 review vòng 2. `AutomationConfig.revoking()` CỐ Ý giữ lại `defaultPackage` (bỏ tick
     * không xoá app đã chọn), nên nếu quyết định này chỉ đọc `defaultPackage` thì bỏ tick vẫn tự chiếu
     * y nguyên — một ô tick không điều khiển được đúng thứ ghi trên nhãn của nó
     * ("Tự khởi động: chiếu app này lên cụm khi mở ClusterNav"). Trước đó cờ một-lần che mất triệu
     * chứng (mỗi tiến trình chỉ bắn một lần); bỏ cờ ⇒ nó bắn mỗi lần mở app.
     */
    @Test
    fun `unticking auto-start really stops it, even though the chosen app is deliberately kept`() {
        assertNull(autoStartTarget("com.example.maps", armed = false, envelope = idle()))
    }

    @Test
    fun `an unresolved transaction blocks auto-start`() {
        val busy = idle().copy(
            transaction = CastTransaction(
                UUID(0L, 1L), 0L, CastOperation.CAST, OperationPhase.VERIFYING,
                null, "com.example.other", "NORMAL", "display-1", CastBaseline(),
                emptyList(), 0, 60_000L, null, "expected", false,
            ),
        )
        assertNull(autoStartTarget("com.example.maps", armed = true, envelope = busy))
    }

    @Test
    fun `an already-active target blocks auto-start`() {
        val active = idle().copy(
            stableSession = StableCastSession(
                StableState.ACTIVE_VERIFIED, EngineVersion.V2, "test", null, "display-1",
                CastBaseline(), CastTarget("com.example.other", 1, 1), null, null, 1,
            ),
        )
        assertNull(autoStartTarget("com.example.maps", armed = true, envelope = active))
    }
}
