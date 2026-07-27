package com.byd.clusternav.modules.clustercast.v2

import com.byd.clusternav.cast.platform.CastAndroidLifecycle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * V1 parity for the vehicle-facing details that made casting usable: per-app cluster style, per-app
 * density, an in-process measurement fallback and one labeled tap for the default app.
 */
class CastFieldParityTest {

    /**
     * Giải đường dẫn source theo cả hai module.
     *
     * Từ 2026-07-27 một phần Cast sống trong module :core (Kotlin JVM thuần), nên test quét source phải
     * tìm ở cả hai gốc thay vì giả định mọi thứ nằm dưới app/. Thứ tự thử phản ánh cả hai kiểu working
     * directory mà Gradle có thể dùng.
     */
    private fun source(relative: String): String {
        val kotlinRelative = relative
            .replace("main/java/", "main/kotlin/")
            .replace("test/java/", "test/kotlin/")
        val candidates = listOf(
            Paths.get("app/src/$relative"),
            Paths.get("src/$relative"),
            Paths.get("core/src/$kotlinRelative"),
            Paths.get("../core/src/$kotlinRelative"),
            Paths.get("car-integration/src/$kotlinRelative"),
            Paths.get("../car-integration/src/$kotlinRelative"),
        )
        val found = candidates.firstOrNull { Files.exists(it) }
            ?: error("không tìm thấy source cho $relative trong: ${candidates.joinToString()}")
        return found.toFile().readText()
    }

    @Test
    fun `only the style opcode differs between curved and rect bootstrap`() {
        assertEquals(
            listOf(
                CommandKind.SEAL_DL3_BOOTSTRAP_30,
                CommandKind.SEAL_DL3_BOOTSTRAP_16,
                CommandKind.SEAL_DL3_BOOTSTRAP_35,
            ),
            SealDl3BootstrapProfile.forwardKindsFor(ClusterStyle.CURVED),
        )
        assertEquals(
            listOf(
                CommandKind.SEAL_DL3_BOOTSTRAP_31,
                CommandKind.SEAL_DL3_BOOTSTRAP_16,
                CommandKind.SEAL_DL3_BOOTSTRAP_35,
            ),
            SealDl3BootstrapProfile.forwardKindsFor(ClusterStyle.RECT),
        )
        assertEquals(SealDl3BootstrapProfile.forwardKinds, SealDl3BootstrapProfile.forwardKindsFor(ClusterStyle.CURVED))
        assertEquals(
            setOf(CommandKind.SEAL_DL3_BOOTSTRAP_30, CommandKind.SEAL_DL3_BOOTSTRAP_31),
            SealDl3BootstrapProfile.styleKinds,
        )
        assertEquals(
            "service call AutoContainer 2 i32 1000 i32 31 s16 \"\"",
            fixedSealDl3BootstrapCommand(CommandKind.SEAL_DL3_BOOTSTRAP_31),
        )
    }

    @Test
    fun `the rect style reaches the vehicle through the bootstrap ledger`() {
        val store = CastSessionStore(object : AtomicBytes {
            private var bytes: ByteArray? = null
            override fun exists() = bytes != null
            override fun read(): ByteArray = bytes ?: error("empty")
            override fun write(value: ByteArray) { bytes = value }
        })
        store.locked {
            initialize("boot")
            update { it.copy(effectiveUiVersion = EngineVersion.V2) }
        }
        val calls = mutableListOf<CommandKind>()
        val executor = CastExecutor(
            store,
            CastMutationGateway { request -> calls += request.kind; MutationResult.Observed("ok") },
            nowEpochMillis = { 1_000L },
            sleeper = CastSleeper { },
        )
        val observations = ArrayDeque(listOf(clusterIdle(), clusterIdle()))
        val result = executor.bootstrap(
            SealDl3BootstrapProfile.exactFacts,
            { _ -> ObservationValue.Known(mainOnlyRaw()) },
            { _ -> observations.removeFirst() },
            ClusterStyle.RECT,
        )
        assertTrue(result is ColdBootstrapResult.Succeeded, result.toString())
        assertEquals(SealDl3BootstrapProfile.forwardKindsFor(ClusterStyle.RECT), calls)
    }

    @Test
    fun `per-app density and style are preferences that reach the runtime call`() {
        val activity = source("main/java/com/byd/clusternav/modules/clustercast/ClusterCastActivity.kt")
        val bubble = source("main/java/com/byd/clusternav/modules/clustercast/FloatingBubbleService.kt")
        val placement = source("main/java/com/byd/clusternav/cast/transport/CastPlacementCommands.kt")
        assertTrue(activity.contains("preferredDensityDpi = catalog.clusterDensityDpi(pkg)"))
        assertTrue(activity.contains("clusterStyle = catalog.clusterStyle(pkg)"))
        assertTrue(bubble.contains("preferredDensityDpi = catalog.clusterDensityDpi(packageName)"))
        assertTrue(bubble.contains("clusterStyle = catalog.clusterStyle(packageName)"))
        assertTrue(placement.contains("wm density \$density -d \$display; "))
        // Freeform may not be alive; the overscan frame keeps a landed cast usable (V1 fallback).
        assertTrue(placement.contains(" || wm overscan \$OVERSCAN_FALLBACK -d \$display"))
        assertTrue(placement.contains("OVERSCAN_FALLBACK = \"0,90,0,90\""))
        // Stop resets overscan together with the rest of the display state.
        val geometry = source("main/java/com/byd/clusternav/modules/clustercast/v2/CastGeometry.kt")
        assertTrue(geometry.contains("wm overscan reset -d \$displayId"))
    }

    @Test
    fun `the app manager exposes both cluster shape and cluster text size`() {
        val dialog = source("main/java/com/byd/clusternav/modules/clustercast/CastAppManagerDialog.kt")
        assertTrue(dialog.contains("Kiểu cụm: "))
        assertTrue(dialog.contains("Dùng kiểu thẳng (full khung)"))
        assertTrue(dialog.contains("Dùng kiểu cong (giữ km/h)"))
        assertTrue(dialog.contains("Cỡ chữ trên cụm (DPI)"))
        assertTrue(dialog.contains("DPI mặc định"))
    }

    @Test
    fun `the bubble offers one labeled tap for the default app and no hidden gesture`() {
        val bubble = source("main/java/com/byd/clusternav/modules/clustercast/FloatingBubbleService.kt")
        assertTrue(bubble.contains("primaryButton"))
        assertTrue(bubble.contains("\"Chiếu \${it.label.removeSuffix(\" · mặc định\")}\""))
        assertFalse(bubble.contains("setOnLongClickListener"))
    }

    @Test
    fun `the primary action only appears for an idle cluster with an eligible default`() {
        val model = CastRenderModel(
            "Cluster Cast sẵn sàng", "sẵn sàng", true, false, false,
            CastAction.entries.map { CastRenderAction(it, it == CastAction.CAST, null) },
        )
        val rows = listOf(
            CastAppRow("Google Maps", "com.example.maps", false, false, AppProtectionSource.NORMAL, AppIconState.LOADED),
            CastAppRow("VietMap", "com.example.vietmap", true, false, AppProtectionSource.NORMAL, AppIconState.LOADED),
        )
        val idle = CastBubbleProjection.project(model, rows, "com.example.maps")
        assertEquals("com.example.maps", idle.primary?.packageName)

        val active = CastBubbleProjection.project(model, rows, "com.example.maps", activeTargetPackage = "com.example.vietmap")
        assertNull(active.primary)

        val stopping = CastBubbleProjection.project(
            CastRenderModel(
                "Đang chiếu", "sẵn sàng", true, false, false,
                CastAction.entries.map { CastRenderAction(it, it == CastAction.STOP || it == CastAction.CAST, null) },
            ),
            rows, "com.example.maps",
        )
        assertNull(stopping.primary)
        assertNull(CastBubbleProjection.project(model, rows, null).primary)
    }

    @Test
    fun `display identity falls back to in-process measurement instead of blocking`() {
        val measured = NamedClusterDisplay(2, "fission_bg_xdjaVirtualSurface", 1920, 720, 180)
        val raw = RawObservation(
            amStacks = "Stack id=0 displayId=0 userId=0\n taskId=1: com.example.launcher/.Main",
            wmDisplays = "Display: mDisplayId=0",
            displays = "Display 0:\n  mDisplayId=0",
            profile = "10",
            animations = "1.0\n1.0\n1.0",
            appOps = "No app ops",
        )
        assertTrue(DumpObservedStateParser.parse(raw) is ObservationValue.Unknown)
        val recovered = DumpObservedStateParser.parse(raw) { measured }
        assertTrue(recovered is ObservationValue.Known, recovered.toString())
        assertEquals("display-2", (recovered as ObservationValue.Known).value.displayIdentity)
        assertEquals(ObservedCoarseState.IDLE_CLEAN, recovered.value.coarseState)
    }

    @Test
    fun `the watchdog restores gauges only on two identical clean observations`() {
        val lifecycle = source("main/java/com/byd/clusternav/cast/platform/CastAndroidLifecycle.kt")
        assertTrue(lifecycle.contains("private fun vanished("))
        assertTrue(lifecycle.contains("if (a != b) return false"))
        assertTrue(lifecycle.contains("a.coarseState == ObservedCoarseState.IDLE_CLEAN && a.target == null"))
        assertTrue(lifecycle.contains("a.displayIdentity == stable.expectedDisplayIdentity"))
        assertTrue(lifecycle.contains("if (envelope.transaction != null || envelope.stopRequested) return false"))
        // The only action is the canonical restorative Stop: no re-cast, no force-stop, no replay.
        val restore = lifecycle.substringAfter("private fun restoreVanishedCluster").substringBefore("/**")
        assertTrue(restore.contains("CastIntentKind.STOP"))
        assertFalse(restore.contains("CastIntentKind.CAST"))
        assertFalse(restore.contains("FORCE_STOP"))
        assertTrue(restore.contains("runtime.coordinator.requestStop() == null"))
        // A vanished target must not be relaunched on the centre screen by the restore.
        assertTrue(restore.contains("projectionComponent = null"))
        assertEquals(
            TargetClass.UNKNOWN_PROTECTED,
            CastPolicy.classify(TargetEvidence(null, null, false)),
        )
    }

    @Test
    fun `a tolerant rung never turns a landed cast into a failed mutation`() {
        val placement = source("main/java/com/byd/clusternav/cast/transport/CastPlacementCommands.kt")
        // Nothing-to-do must dispatch the success sentinel, never a null that becomes Rejected.
        assertTrue(placement.contains("pkg == null -> NO_OP"))
        assertTrue(placement.contains("?: NO_OP"))
        assertTrue(placement.contains("return@let NO_OP"))
        // Every tolerant path reports success; the count is asserted so a regression to null shows up.
        assertEquals(17, Regex("return@let NO_OP|\\?: NO_OP|-> NO_OP").findAll(placement).count())
        // A fenced or cancelled dispatch must not be rescued by the in-process fallback.
        assertTrue(placement.contains("if (cancelled()) return null"))
        assertTrue(placement.contains("measuredCluster()?.id?.takeUnless { cancelled() }"))
        // 2026-07-26 on-car: 1 is the value that lets an unresizeable activity be placed on a
        // differently sized display; 0 is what blocked com.byd.auto_photo.
        assertTrue(placement.contains("force_resizable_activities 1"))
        assertFalse(placement.contains("force_resizable_activities 0"))
        // Stop's own restore steps must never depend on the cluster display still existing.
        assertTrue(placement.contains("private fun displayFreeCommand("))
        // Structural invariant: every kind produced after discovery must actually use the display,
        // and every kind that does not must be listed as display-free and produced before discovery.
        val declared = placement.substringAfter("private val DISPLAY_FREE_KINDS = setOf(")
            .substringBefore(")\n")
            .let { block -> Regex("CommandKind\\.([A-Z_]+)").findAll(block).map { it.groupValues[1] }.toSet() }
        assertEquals(
                        setOf(
                // The three escalation rungs left this set on 2026-07-26: they must observe whether the
                // target already landed, so they are produced after display discovery. Their RESTORE
                // counterparts stay display-free so Stop works when the cluster has disappeared.
                "FORCE_STOP_NORMAL", "DISCONNECTED_SINK_RECOVERY_ONCE",
                "RESTORE_TRANSITION_ANIMATION", "RESTORE_PIP",
                "RETURN_NORMAL_TO_MAIN", "RETURN_PROTECTED_GENTLY", "PRE_OPEN_ON_MAIN",
            ),
            declared,
        )
        val afterDiscovery = placement.substringAfter("val display = discoverCastDisplay")
            .substringBefore("private val DISPLAY_FREE_KINDS")
        declared.forEach { kind ->
            assertFalse(afterDiscovery.contains("CommandKind.$kind ->"), "$kind must be display-free")
        }
        val displayBound = Regex("CommandKind\\.([A-Z_]+)(?=[ ,]*(?:->|,))").findAll(afterDiscovery)
            .map { it.groupValues[1] }.toSet()
        assertTrue(displayBound.isNotEmpty())
        assertTrue(displayBound.none { it in declared })
        val order = placement.indexOf("if (request.kind in DISPLAY_FREE_KINDS) return displayFreeCommand(")
        assertTrue(order in 1 until placement.indexOf("val display = discoverCastDisplay"))
        assertTrue(placement.indexOf("if (cancelled()) return null") < placement.indexOf("fixedSealDl3BootstrapCommand(request.kind)"))
        // Skipping a re-occupied display is success, not a failed mutation.
        assertTrue(placement.contains("} else NO_OP"))
        // A nested-class launcher activity must survive the device shell.
        assertEquals(0, Regex("-n \\\$it\"").findAll(placement).count())
        assertTrue(placement.contains("-n '\$it'"))
        // Returning a normal app to the centre screen must leave it fullscreen, not floating.
        assertTrue(placement.contains("am start --display 0 --windowingMode \$FULLSCREEN_MODE -n '\$it'"))
        assertTrue(placement.contains("FULLSCREEN_MODE = 1"))
        // A protected sink is returned gently, without a windowing-mode change.
        assertTrue(placement.contains("CommandKind.RETURN_PROTECTED_GENTLY ->"))
    }

    private fun mainOnlyRaw() = RawObservation(
        amStacks = "Stack id=0 bounds=[0,0][1920,1080] displayId=0 userId=0\n" +
            "  taskId=1: com.example.launcher/.Main bounds=[0,0][1920,1080] userId=0",
        wmDisplays = "Display: mDisplayId=0",
        displays = "Display 0:\n  mDisplayId=0\n  DisplayDeviceInfo{Built-in Screen}",
        profile = "10", animations = "1.0\n0.5\n1.0", appOps = "No app ops",
    )

    private fun clusterIdle(): ObservationValue.Known<ObservedState> = ObservationValue.Known(
        ObservedState(
            ObservedCoarseState.IDLE_CLEAN, "display-2", null, emptySet(), null,
            AcceptedGeometry(CastRect(0, 0, 1920, 720), 180, "android-user-10"),
            mapOf(
                "window_animation_scale" to "1.0", "transition_animation_scale" to "0.5",
                "animator_duration_scale" to "1.0",
            ),
            null, "android-user-10", "fission_bg_xdjaVirtualSurface",
        ),
    )
}
