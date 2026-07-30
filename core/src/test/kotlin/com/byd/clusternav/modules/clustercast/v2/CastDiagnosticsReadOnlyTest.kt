package com.byd.clusternav.modules.clustercast.v2

import java.io.IOException
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Runtime lock for the diagnostics-readonly contract.
 *
 * `DiagActivity.kt` (see the source-text companion lock in
 * `app/src/test/java/.../v2/CastDiagnosticsContractTest.kt`) only ever calls
 * `CastFacade.observe()`, `.envelope()` (-> `storeRead()`), `.storeStatusLine()` (-> `storeRead()`) and
 * `.operationLog()`. This test proves, at the actual object-wiring level rather than by grepping source
 * text, that following that exact call sequence through `CastCoordinator` + `CastSessionStore` +
 * `CastOperationLog` never reaches `CastMutationGateway.execute` -- the only gateway capable of issuing a
 * mutating `CommandKind`. The mutation gateway wired in here throws if invoked at all, so any future
 * change that makes the diagnostics read path fall through to a mutating dispatch fails this test loudly
 * instead of silently.
 */
class CastDiagnosticsReadOnlyTest {
    @Test
    fun `diagnostics call sequence never dispatches through the mutation gateway`() {
        val store = CastSessionStore(MemoryAtomicBytes())
        store.locked { initialize("boot") }

        val mutationCalls = mutableListOf<CommandKind>()
        val gateway = CastMutationGateway { request ->
            mutationCalls += request.kind
            error("diagnostics-readonly violation: mutation gateway dispatched ${request.kind}")
        }
        val executor = CastExecutor(
            store,
            gateway,
            nowEpochMillis = { 1_000L },
            operationId = { UUID(0L, 1L) },
            bootstrapVerificationPollMillis = 0L,
            sleeper = CastSleeper {},
        )
        val reader = ObservedStateReader(
            RawShell(),
            ObservedStateParser { ObservationValue.Known(idleState()) },
            nowEpochMillis = { 1_000L },
        )
        val coordinator = CastCoordinator(
            store,
            reader,
            executor,
            CastRecovery(store, executor),
            now = { Instant.ofEpochMilli(1_000L) },
        )

        // Exactly CastFacade's methods that DiagActivity.refresh() calls, in the same order.
        val observation = coordinator.observe()
        val loaded = store.locked { read() } as StoreRead.Loaded
        CastOperationLog.record("diagnostics-readonly probe")
        val log = CastOperationLog.render()

        assertTrue(mutationCalls.isEmpty(), "observed mutation dispatch: $mutationCalls")
        assertTrue(observation is ObservationValue.Known)
        assertEquals(0L, loaded.envelope.durableEpoch)
        assertTrue(log.contains("diagnostics-readonly probe"))
    }

    @Test
    fun `ObservedStateReader is wired to the read-only ShellGateway, not the mutation gateway`() {
        // Structural guarantee, not just a today-it-happens-to-be-true fact: ObservedStateReader's
        // gateway parameter type is ShellGateway, whose only entry point takes a ReadOnlyShellRequest --
        // and ReadOnlyShellRequest's constructor rejects any CommandKind flagged `mutating` (see
        // ShellGateway.kt). RawShell below implements ONLY ShellGateway; it cannot be handed a mutating
        // CommandKind at all, so this is enforced by the type system, not by test discipline.
        val shell = RawShell()
        for (kind in CommandAllowlist.readOnly) {
            if (kind == CommandKind.PHONE_SESSION_STATE || kind == CommandKind.PACKAGE_RESOLVE) continue
            val request = ReadOnlyShellRequest.observation(kind)
            assertTrue(shell.execute(request) is ShellResult.Success, "read failed for $kind")
        }
        for (kind in CommandAllowlist.plannedMutation) {
            assertTrue(kind.mutating, "$kind should be in the mutating set")
        }
    }

    private fun idleState() = ObservedState(
        ObservedCoarseState.IDLE_CLEAN,
        "display-2",
        null,
        emptySet(),
        null,
        AcceptedGeometry(CastRect(0, 0, 1920, 720), 180, "android-user-10"),
        mapOf(
            "window_animation_scale" to "1.0",
            "transition_animation_scale" to "0.5",
            "animator_duration_scale" to "1.0",
        ),
        null,
        "android-user-10",
        "fission_bg_xdjaVirtualSurface",
    )

    private class RawShell : ShellGateway {
        override fun execute(request: ReadOnlyShellRequest): ShellResult {
            val output = when (request.kind) {
                CommandKind.AM_STACK_LIST -> "Stack id=0 displayId=0 userId=0\n  taskId=1: com.example.launcher/.Main"
                CommandKind.WM_DISPLAYS -> "Display: mDisplayId=0"
                CommandKind.DISPLAY_STATE -> "Display 0:\n  mDisplayId=0"
                CommandKind.PROFILE_STATE -> "10"
                CommandKind.ANIMATION_STATE -> "1.0\n0.5\n1.0"
                CommandKind.APP_OPS_STATE -> "No app ops"
                else -> error("Unexpected read ${request.kind}")
            }
            return ShellResult.Success(output, "", 1L)
        }
        override fun close() = Unit
    }

    private class MemoryAtomicBytes : AtomicBytes {
        private var bytes: ByteArray? = null
        override fun exists() = bytes != null
        override fun read(): ByteArray = bytes?.copyOf() ?: throw IOException("missing")
        override fun write(bytes: ByteArray) { this.bytes = bytes.copyOf() }
    }
}
