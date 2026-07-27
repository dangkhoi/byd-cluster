package com.byd.clusternav.modules.clustercast.v2

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * R14 — quét vét cạn: mọi trạng thái phải có đường ra bằng hành động trong app.
 *
 * Bài kiểm mà nếu có từ đầu thì sự cố 2026-07-27 không xảy ra. Hôm đó journal kẹt ở `phase=RECOVERING`
 * với `durableEpoch` lệch `tx.epoch` và ledger có bước `ISSUED`; ba điều kiện đó, mỗi cái độc lập, đều làm
 * `CastRecovery.decide()` từ chối bù trừ, còn `completeVerification` chỉ nhận `VERIFYING`. Kết quả:
 * `mode=READ_ONLY`, khoá cả việc chọn app, và cách hồi phục duy nhất là cắm adb vào xe.
 *
 * Quét trên tích Descartes của chính các chiều đã gây ra ngõ cụt đó, không phải trên vài ca chọn tay.
 */
class NoDeadEndStateTest {

    private fun input(
        substate: RecoverySubstate?,
        stopRequested: Boolean = false,
        observedNonIdle: Boolean = false,
        epoch: Long = 5,
    ) = CastProjectionInput(
        decodeValid = true,
        engineVersion = EngineVersion.V2,
        observedNonIdle = observedNonIdle,
        stopRequested = stopRequested,
        recoverySubstate = substate,
        transaction = null,
        destructiveRecoveryEligible = null,
        stableState = null,
        stableConverged = false,
        interactionContext = InteractionContext(
            InteractionContextValue.UNKNOWN, "test", Instant.ofEpochMilli(1_000), Instant.ofEpochMilli(2_000), null,
        ),
        target = null,
        protectedResidue = null,
        acceptedGeometry = null,
        durableEpoch = epoch,
        now = Instant.ofEpochMilli(1_500),
    )

    @Test
    fun `moi to hop trang thai va su co deu con duong ra`() {
        val stuck = mutableListOf<String>()
        var combinations = 0
        val substates: List<RecoverySubstate?> = listOf(null) + RecoverySubstate.entries
        substates.forEach { substate ->
            listOf(false, true).forEach { stopRequested ->
                listOf(false, true).forEach { nonIdle ->
                    combinations++
                    val state = CastUiStateProjector.project(
                        input(substate, stopRequested = stopRequested, observedNonIdle = nonIdle),
                    )
                    if (state.allowedActions.isEmpty()) {
                        stuck += "substate=$substate stop=$stopRequested nonIdle=$nonIdle"
                    }
                }
            }
        }
        assertTrue(combinations >= (RecoverySubstate.entries.size + 1) * 4, "quét chưa vét cạn: $combinations")
        assertTrue(stuck.isEmpty(), "tổ hợp không còn hành động nào — người dùng bị kẹt: ${stuck.take(6)}")
    }

    @Test
    fun `moi trang thai deu giu duoc Chan doan`() {
        // Chẩn đoán là đường duy nhất để biết VÌ SAO. Khoá nó thì người dùng không còn gì ngoài việc gọi
        // lập trình viên — đúng tình trạng sáng 27/7.
        val missing = (listOf<RecoverySubstate?>(null) + RecoverySubstate.entries).filter { substate ->
            CastAction.OPEN_DIAGNOSTICS !in CastUiStateProjector.project(input(substate)).allowedActions
        }
        assertTrue(missing.isEmpty(), "mất Chẩn đoán ở: $missing")
    }

    @Test
    fun `trang thai dang cho luon co duong thu lai`() {
        val waiting = RecoverySubstate.entries.filter {
            CastUiStateProjector.project(input(it)).nextSafeAction == NextSafeAction.WAIT_AND_OBSERVE
        }
        assertTrue(waiting.isNotEmpty(), "phải có trạng thái chờ để bài kiểm có nghĩa")
        waiting.forEach { substate ->
            assertTrue(
                CastAction.RETRY_CONNECT in CastUiStateProjector.project(input(substate)).allowedActions,
                "$substate chỉ biết chờ mà không có cách thử lại",
            )
        }
    }
}
