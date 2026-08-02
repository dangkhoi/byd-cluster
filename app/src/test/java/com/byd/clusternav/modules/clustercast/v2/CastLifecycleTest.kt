package com.byd.clusternav.modules.clustercast.v2

import com.byd.clusternav.cast.platform.CastAndroidLifecycle
import com.byd.clusternav.cast.platform.CastLifecycleMigration
import java.nio.file.Files
import java.nio.file.Path

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CastLifecycleTest {
    @Test
    fun `pristine observed-clean store migrates once to rollback-readable idle V2`() {
        val migrated = CastLifecycleMigration.migratePristine(pristine(), knownIdle(), 1234)!!
        assertEquals(EngineVersion.V2, migrated.effectiveUiVersion)
        assertEquals(StableState.IDLE_VERIFIED, migrated.stableSession!!.state)
        assertEquals(EngineVersion.V2, migrated.stableSession!!.engineVersion)
        assertEquals(1234, migrated.stableSession!!.lastVerifiedAtEpochMillis)
        assertEquals("display-2", migrated.stableSession!!.expectedDisplayIdentity)
    }

    @Test
    fun `unknown or already-owned truth never migrates, an occupied cluster is adopted reclaimable`() {
        val active = ObservationValue.Known(
            ObservedState(
                ObservedCoarseState.ACTIVE_SINGLE,
                "display-2",
                CastTarget("com.example.maps", 1, 2),
                setOf("com.example.maps"),
                null,
                null,
            ),
        )
        // 2026-07-26 on-car: returning null here dropped a pristine install into read-only legacy
        // mode whenever anything occupied the cluster, and that state never recovered. An occupied
        // cluster is now adopted as RECOVERY_PENDING: truthful (never claims idle or verified) and
        // reclaimable, because Stop stays available.
        val adopted = CastLifecycleMigration.migratePristine(pristine(), active, 1)
        assertNotNull(adopted)
        assertEquals(StableState.RECOVERY_PENDING, adopted!!.stableSession?.state)
        assertEquals("display-2", adopted.stableSession?.expectedDisplayIdentity)
        assertEquals("com.example.maps", adopted.stableSession?.activeTarget?.packageName)

        // Genuinely unknown truth still refuses to invent a session.
        assertNull(CastLifecycleMigration.migratePristine(pristine(), ObservationValue.Unknown("partial"), 1))
        val unknownCoarse = ObservationValue.Known(
            ObservedState(ObservedCoarseState.UNKNOWN, null, null, emptySet(), null, null),
        )
        assertNull(CastLifecycleMigration.migratePristine(pristine(), unknownCoarse, 1))

        // An envelope we already own is never re-migrated.
        val owned = pristine().copy(stableSession = idleSession())
        assertNull(CastLifecycleMigration.migratePristine(owned, knownIdle(), 1))
    }


    @Test
    fun `same boot revalidation has a Cast owned watchdog with no mutation dispatch`() {
        val receiver = source("main/java/com/byd/clusternav/modules/clustercast/CastLifecycleReceiver.kt")
        val navReceiver = source("main/java/com/byd/clusternav/RebindReceiver.kt")
        val manifest = source("main/AndroidManifest.xml")
        assertTrue(receiver.contains("CastAndroidLifecycle.revalidate"))
        assertTrue(receiver.contains("ACTION_REVALIDATE"))
        assertFalse(receiver.contains("coordinator.execute"))
        assertFalse(receiver.contains("requestStop"))
        assertFalse(navReceiver.contains("ACTION_WATCHDOG -> rehydrateCast"))
        assertTrue(navReceiver.contains("CastLifecycleReceiver.schedule"))
        assertTrue(manifest.contains(".modules.clustercast.CastLifecycleReceiver"))
    }

    @Test
    fun `same boot revalidation preserves converged state and fences divergence without mutation`() {
        val owned = pristine().copy(effectiveUiVersion = EngineVersion.V2, stableSession = idleSession())
        val converged = CastLifecycleMigration.revalidateStable(owned, knownIdle(), knownIdle(), 99)
        assertEquals(StableState.IDLE_VERIFIED, converged.stableSession!!.state)
        assertEquals(99, converged.stableSession!!.lastVerifiedAtEpochMillis)

        val divergent = ObservationValue.Known(
            ObservedState(
                ObservedCoarseState.ACTIVE_SINGLE, "display-2",
                CastTarget("com.example.other", 9, 2), setOf("com.example.other"), null, null,
            ),
        )
        val fenced = CastLifecycleMigration.revalidateStable(owned, divergent, divergent, 100)
        assertEquals(StableState.RECOVERY_PENDING, fenced.stableSession!!.state)
        assertNull(fenced.transaction)
        assertEquals(1, fenced.stableSession!!.lastVerifiedAtEpochMillis)
    }

    @Test
    fun `V2 app catalog migrates legacy preferences without deleting rollback data`() {
        val catalog = source("main/java/com/byd/clusternav/cast/platform/CastAppCatalog.kt")
        // 2026-07-29: màn Cast riêng (ClusterCastActivity) bị xoá; wiring này giờ sống trong
        // MainActivityCastController (docs/specs/cast-simplified-active-app-toggle.html).
        val controller = source("main/java/com/byd/clusternav/modules/clustercast/MainActivityCastController.kt")
        assertTrue(catalog.contains("migrationVersion"))
        assertTrue(catalog.contains("getSharedPreferences(\"clustercast\""))
        assertTrue(catalog.contains("castable"))
        assertTrue(catalog.contains("keepSession"))
        assertFalse(catalog.contains("legacy.edit()"))
        assertTrue(controller.contains("CastAppCatalog"))
        assertTrue(catalog.contains("fun setProtected"))
        // 2026-08-01: ba hộp thoại chỉ-đọc chuyển sang `CastGuidanceDialogs` và nút cứu hộ sang
        // `CastClearClusterAction` để controller ở dưới ngưỡng 500 dòng mà `CastRendererContractTest`
        // canh. Ý của khẳng định này KHÔNG đổi — mỗi nút khắc phục phải nối tới một handler thật, không
        // được là nút chết — chỉ có chỗ đặt handler là đổi.
        listOf("retryConnect()", "CastGuidanceDialogs.phoneDisconnect(", "confirmEligibleRecovery()",
            "CastGuidanceDialogs.physicalRecovery(", "CastGuidanceDialogs.openProfileSetup(",
            "CastClearClusterAction(")
            .forEach { assertTrue(controller.contains(it), it) }
        listOf("ClusterCast.loadPrefs", "ClusterCast.listInstalledApps", "ClusterCast.isKeepSession", "ClusterCast.isPhoneProjection")
            .forEach { assertFalse(controller.contains(it), it) }
    }
    @Test
    fun `canonical runtime UI exports idle actions and keeps legacy active Stop disabled`() {
        val migrated = CastLifecycleMigration.migratePristine(pristine(), knownIdle(), 1)!!
        val idle = CastRuntimeUi.render(StoreRead.Loaded(migrated), knownIdle())
        assertTrue(idle.actions.single { it.action == CastAction.CAST }.enabled)
        assertNull(idle.actions.single { it.action == CastAction.CAST }.disabledReason)
        assertFalse(idle.actions.single { it.action == CastAction.STOP }.enabled)

        val legacyActive = CastRuntimeUi.render(
            StoreRead.Loaded(pristine()),
            ObservationValue.Known(
                ObservedState(
                    ObservedCoarseState.ACTIVE_SINGLE,
                    "display-2",
                    CastTarget("com.example.legacy", 4, 2),
                    setOf("com.example.legacy"),
                    null,
                    null,
                ),
            ),
        )
        assertEquals("Phiên cũ · chỉ đọc", legacyActive.title)
        assertFalse(legacyActive.actions.single { it.action == CastAction.STOP }.enabled)
        assertTrue(legacyActive.actions.single { it.action == CastAction.OPEN_DIAGNOSTICS }.enabled)
    }

    @Test
    fun `runtime UI never renders stable idle from unknown or divergent observation`() {
        val migrated = CastLifecycleMigration.migratePristine(pristine(), knownIdle(), 1)!!
        val unknown = CastRuntimeUi.render(StoreRead.Loaded(migrated), ObservationValue.Unknown("partial"))
        assertEquals("Cần xử lý thủ công", unknown.title)
        assertFalse(unknown.actions.single { it.action == CastAction.CAST }.enabled)
        assertTrue(unknown.actions.single { it.action == CastAction.OPEN_DIAGNOSTICS }.enabled)

        val unsupported = CastRuntimeUi.render(
            StoreRead.Loaded(migrated), ObservationValue.Unsupported("active Android profile format unsupported"),
        )
        assertTrue(unsupported.actions.single { it.action == CastAction.OPEN_PROFILE_SETUP }.enabled)
        assertFalse(unsupported.actions.single { it.action == CastAction.CAST }.enabled)
    }

    @Test
    fun `runtime UI maps connected and proven disconnected sink evidence to canonical actions`() {
        val target = CastTarget("com.example.carplay", 7, 2)
        val stable = idleSession().copy(
            state = StableState.RECOVERY_PENDING,
            activeTarget = target,
            acceptedGeometry = null,
        )
        val envelope = pristine().copy(effectiveUiVersion = EngineVersion.V2, stableSession = stable)
        val observed = ObservationValue.Known(
            ObservedState(
                ObservedCoarseState.ACTIVE_SINGLE, "display-2", target,
                setOf(target.packageName), null, null,
            ),
        )
        val connected = CastRuntimeUi.render(
            StoreRead.Loaded(envelope), observed, phoneSessionConnected = true,
        )
        assertTrue(connected.actions.single { it.action == CastAction.REQUEST_PHONE_DISCONNECT }.enabled)

        val disconnected = CastRuntimeUi.render(
            StoreRead.Loaded(envelope), observed,
            phoneSessionConnected = false, destructiveRecoveryEligible = true,
        )
        assertTrue(disconnected.actions.single { it.action == CastAction.TRY_ELIGIBLE_RECOVERY_ONCE }.enabled)
    }

    private fun pristine() = CastSessionEnvelope(
        durableEpoch = 0,
        bootId = "boot-a",
        stopRequested = false,
        pendingIntent = null,
        effectiveUiVersion = EngineVersion.LEGACY,
        pendingUiRollback = false,
        stableSession = null,
        transaction = null,
    )

    private fun idleSession() = StableCastSession(
        StableState.IDLE_VERIFIED,
        EngineVersion.V2,
        "test",
        null,
        "display-2",
        CastBaseline(),
        null,
        null,
        null,
        1,
    )

    private fun source(relative: String): String {
        val current = Path.of(System.getProperty("user.dir"))
        val app = if (Files.exists(current.resolve("src"))) current else current.resolve("app")
        return app.resolve("src").resolve(relative).toFile().readText()
    }

    private fun knownIdle() = ObservationValue.Known(
        ObservedState(ObservedCoarseState.IDLE_CLEAN, "display-2", null, emptySet(), null, null),
    )
}
