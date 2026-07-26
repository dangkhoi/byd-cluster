package com.byd.clusternav.modules.clustercast

import com.byd.clusternav.modules.clustercast.v2.CastBaseline
import com.byd.clusternav.modules.clustercast.v2.CastManualIntentResult
import com.byd.clusternav.modules.clustercast.v2.EngineVersion
import com.byd.clusternav.modules.clustercast.v2.StableCastSession
import com.byd.clusternav.modules.clustercast.v2.StableState
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CastOperationStatusTest {
    @Test
    fun `in flight text has no 500 millisecond timeout`() {
        val status = CastOperationStatus()
        status.begin("Đang chạy preflight chính xác…")

        assertEquals(
            CastOperationStatusSnapshot(
                "Đang chạy preflight chính xác…",
                CastOperationStatusPhase.IN_FLIGHT,
            ),
            status.snapshot(instant(60_000)),
        )
        assertEquals(
            "Đang chạy preflight chính xác…",
            status.visibleText("projected", durablePriority = false, instant(600_000)),
        )
    }

    @Test
    fun `exact completion is visible before 4000 milliseconds and expires at boundary`() {
        val status = CastOperationStatus()
        val token = status.begin("initial")
        assertTrue(status.complete(token, "exact result", instant(1_000)))

        assertEquals("exact result", status.snapshot(instant(1_000))!!.message)
        assertEquals("exact result", status.snapshot(instant(4_999))!!.message)
        assertNull(status.snapshot(instant(5_000)))
        assertEquals("projected", status.visibleText("projected", false, instant(5_001)))
    }

    @Test
    fun `stale token cannot overwrite a newer operation`() {
        val status = CastOperationStatus()
        val old = status.begin("old initial")
        val current = status.begin("new initial")

        assertFalse(status.complete(old, "stale result", instant(1_000)))
        assertEquals("new initial", status.snapshot(instant(1_000))!!.message)
        assertTrue(status.complete(current, "new exact result", instant(1_001)))
        assertEquals("new exact result", status.snapshot(instant(1_001))!!.message)
        assertFalse(status.complete(current, "duplicate", instant(1_002)))
    }

    @Test
    fun `durable Stop recovery or manual text overrides local success`() {
        val status = CastOperationStatus()
        val token = status.begin("initial")
        status.complete(token, "local success", instant(1_000))

        assertEquals("local success", status.visibleText("durable recovery", false, instant(2_000)))
        assertEquals("durable recovery", status.visibleText("durable recovery", true, instant(2_000)))
    }

    @Test
    fun `clear invalidates an in flight token when Stop supersedes it`() {
        val status = CastOperationStatus()
        val token = status.begin("cast in flight")
        status.clearAll()

        assertNull(status.snapshot(instant(1_000)))
        assertFalse(status.complete(token, "late cast success", instant(1_000)))
    }

    @Test
    fun `token scoped clear cannot erase newer work`() {
        val status = CastOperationStatus()
        val old = status.begin("old")
        val current = status.begin("current")

        assertFalse(status.clear(old))
        assertEquals("current", status.snapshot(instant(1_000))!!.message)
        assertTrue(status.clear(current))
        assertNull(status.snapshot(instant(1_000)))
    }

    @Test
    fun `token scoped delivery rejects replacement clear and exact expiry`() {
        val status = CastOperationStatus()
        val first = status.begin("first")
        assertTrue(status.complete(first, "first result", instant(1_000)))
        assertEquals(instant(5_000), status.snapshot(first, instant(4_999))!!.expiresAt)

        val second = status.begin("second")
        assertNull(status.snapshot(first, instant(2_000)))
        assertFalse(status.expire(first, instant(5_000)))
        assertEquals("second", status.snapshot(instant(5_000))!!.message)
        status.clearAll()
        assertFalse(status.isCurrent(second, instant(2_000)))

        val expiring = status.begin("expiring")
        assertTrue(status.complete(expiring, "done", instant(10_000)))
        assertTrue(status.isCurrent(expiring, instant(13_999)))
        assertFalse(status.isCurrent(expiring, instant(14_000)))
    }

    @Test
    fun `status timer delay rounds up and reaches zero only at absolute expiry`() {
        val now = instant(1_000)
        assertEquals(4_000L, statusTimerDelayMillis(now, now.plusMillis(4_000)))
        assertEquals(1L, statusTimerDelayMillis(now, now.plusNanos(1)))
        assertEquals(1L, statusTimerDelayMillis(now, now.plusNanos(999_999)))
        assertEquals(0L, statusTimerDelayMillis(now, now))
        assertEquals(0L, statusTimerDelayMillis(now.plusNanos(1), now))
    }

    @Test
    fun `manual intent results retain exact user facing outcome`() {
        assertEquals(
            "Bị chặn: package removed",
            CastManualIntentResult.Blocked("package removed").statusMessage(),
        )
        assertEquals(
            "Cần phục hồi: unknown effect",
            CastManualIntentResult.RecoveryRequired(UUID(0, 1), "unknown effect").statusMessage(),
        )
        assertEquals(
            "Đã chiếu và xác minh",
            CastManualIntentResult.Succeeded(activeSession()).statusMessage(),
        )
    }

    private fun activeSession() = StableCastSession(
        StableState.ACTIVE_VERIFIED,
        EngineVersion.V2,
        "test",
        null,
        "display-2",
        CastBaseline(),
        null,
        null,
        null,
        1L,
    )

    private fun instant(value: Long) = Instant.ofEpochMilli(value)
}
