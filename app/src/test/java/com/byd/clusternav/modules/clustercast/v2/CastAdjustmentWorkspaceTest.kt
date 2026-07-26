package com.byd.clusternav.modules.clustercast.v2

import java.io.IOException
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CastAdjustmentWorkspaceTest {
    private val target = CastTarget("com.example.maps", 7, 2)
    private val entry = AcceptedGeometry(CastRect(0, 0, 1920, 720), 160, "seal")
    private val changed = AcceptedGeometry(CastRect(96, 36, 1824, 684), 180, "seal")

    @Test
    fun `verified apply survives process recreation but accepted geometry changes only on Done`() {
        val atomic = MemoryAtomicBytes()
        val store = activeStore(atomic)
        val workspace = CastAdjustmentWorkspace(store)
        assertTrue(workspace.open(observed(entry)) is AdjustmentResult.Ready)
        workspace.edit(changed)
        val applying = (workspace.beginApply(observed(entry)) as AdjustmentResult.Ready).draft
        assertEquals(AdjustmentApplyState.APPLYING, applying.applyState)
        assertNull(applying.operationId)
        val operationId = id("apply")
        store.locked { bumpEpoch { envelope -> envelope.copy(transaction = geometryTransaction(operationId, envelope.durableEpoch)) } }
        workspace.bindExecution(operationId, 1)
        store.locked { update { it.copy(transaction = null) } }

        val verified = (workspace.recordVerifiedApply(observed(changed), observed(changed)) as AdjustmentResult.Ready).draft
        assertEquals(AdjustmentApplyState.APPLIED_VERIFIED, verified.applyState)
        assertEquals(changed, verified.lastVerifiedApply)
        assertEquals(entry, loaded(store).stableSession!!.acceptedGeometry, "Apply must not promote accepted geometry")

        val recreated = CastAdjustmentWorkspace(CastSessionStore(atomic))
        val rehydrated = recreated.rehydrate(observed(changed)) as AdjustmentResult.Ready
        assertEquals(changed, rehydrated.draft.localDraft)
        recreated.done(observed(changed), observed(changed))
        val completed = loaded(CastSessionStore(atomic))
        assertEquals(changed, completed.stableSession!!.acceptedGeometry)
        assertNull(completed.adjustmentDraft)
    }

    @Test
    fun `unverified apply cannot commit and target change freezes without replay`() {
        val atomic = MemoryAtomicBytes()
        val store = activeStore(atomic)
        val workspace = CastAdjustmentWorkspace(store)
        workspace.open(observed(entry))
        workspace.edit(changed)
        workspace.beginApply(observed(entry))
        val staleId = id("stale")
        store.locked { bumpEpoch { envelope -> envelope.copy(transaction = geometryTransaction(staleId, envelope.durableEpoch)) } }
        workspace.bindExecution(staleId, 1)
        store.locked { update { it.copy(transaction = null) } }
        val failed = workspace.recordVerifiedApply(observed(changed), observed(entry)) as AdjustmentResult.Ready
        assertEquals(AdjustmentApplyState.FAILED_UNVERIFIED, failed.draft.applyState)
        assertTrue(workspace.done(observed(changed), observed(changed)) is AdjustmentResult.Rejected)
        assertEquals(entry, loaded(store).stableSession!!.acceptedGeometry)

        val differentTarget = observed(changed).copy(target = target.copy(taskId = 99))
        val frozen = workspace.rehydrate(differentTarget) as AdjustmentResult.Ready
        assertEquals(AdjustmentApplyState.FROZEN, frozen.draft.applyState)
        assertNull(frozen.draft.operationId)
    }

    @Test
    fun `undo returns previous verified geometry and cancel requires verified entry restoration`() {
        val atomic = MemoryAtomicBytes()
        val store = activeStore(atomic)
        val workspace = CastAdjustmentWorkspace(store)
        workspace.open(observed(entry))
        workspace.edit(changed)
        workspace.beginApply(observed(entry))
        val firstId = id("first")
        store.locked { bumpEpoch { envelope -> envelope.copy(transaction = geometryTransaction(firstId, envelope.durableEpoch)) } }
        workspace.bindExecution(firstId, 1)
        store.locked { update { it.copy(transaction = null) } }
        workspace.recordVerifiedApply(observed(changed), observed(changed))

        val undo = workspace.undoLast() as AdjustmentResult.Ready
        assertEquals(entry, undo.draft.localDraft)
        workspace.beginApply(observed(changed))
        val undoId = id("undo")
        store.locked { bumpEpoch { envelope -> envelope.copy(transaction = geometryTransaction(undoId, envelope.durableEpoch)) } }
        workspace.bindExecution(undoId, 2)
        store.locked { update { it.copy(transaction = null) } }
        workspace.recordVerifiedApply(observed(entry), observed(entry))
        val cancelled = workspace.cancelAfterRestore(observed(entry), observed(entry)) as AdjustmentResult.Ready
        assertEquals(AdjustmentApplyState.FAILED_RESTORED, cancelled.draft.applyState)
        assertNull(loaded(store).adjustmentDraft)
        assertEquals(entry, loaded(store).stableSession!!.acceptedGeometry)
    }

    private fun activeStore(atomic: MemoryAtomicBytes): CastSessionStore = CastSessionStore(atomic).also { store ->
        store.locked {
            initialize("boot")
            update { it.copy(
                effectiveUiVersion = EngineVersion.V2,
                stableSession = StableCastSession(
                    StableState.ACTIVE_VERIFIED, EngineVersion.V2, "test", null, "display-2",
                    CastBaseline(geometry = entry), target, null, entry, 1,
                ),
            ) }
        }
    }

    private fun observed(geometry: AcceptedGeometry) = ObservedState(
        ObservedCoarseState.ACTIVE_SINGLE, "display-2", target,
        setOf(target.packageName), null, geometry,
    )

    private fun loaded(store: CastSessionStore) =
        (store.locked { read() } as StoreRead.Loaded).envelope

    private fun id(value: String): UUID = UUID.nameUUIDFromBytes(value.toByteArray())

    private fun geometryTransaction(operationId: UUID, epoch: Long) = CastTransaction(
        operationId, epoch, CastOperation.APPLY_GEOMETRY, OperationPhase.VERIFYING,
        target.packageName, target.packageName, TargetClass.NORMAL.name, "display-2",
        CastBaseline(geometry = entry), emptyList(), 0, 10_000, null, "geometry", false,
    )

    private class MemoryAtomicBytes : AtomicBytes {
        var bytes: ByteArray? = null
        override fun exists() = bytes != null
        override fun read(): ByteArray = bytes?.copyOf() ?: throw IOException("missing")
        override fun write(bytes: ByteArray) { this.bytes = bytes.copyOf() }
    }
}
