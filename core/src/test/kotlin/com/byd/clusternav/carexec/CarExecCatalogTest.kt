package com.byd.clusternav.carexec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CarExecCatalogTest {

    @Test
    fun `moi id la duy nhat`() {
        val stepIds = CarExecCatalog.steps.map { it.id }
        assertEquals(stepIds.size, stepIds.toSet().size, "step id trùng: $stepIds")
        val candidateIds = CarExecCatalog.steps.flatMap { step -> step.candidates.map { it.id } }
        assertEquals(candidateIds.size, candidateIds.toSet().size, "candidate id trùng")
    }

    @Test
    fun `moi candidate co lenh va co dieu kien chung minh`() {
        CarExecCatalog.steps.forEach { step ->
            step.candidates.forEach { candidate ->
                assertTrue(candidate.commands.isNotEmpty(), "${candidate.id} không có lệnh nào")
                assertTrue(candidate.evidence.isNotBlank(), "${candidate.id} không nói cái gì chứng minh nó đạt")
                assertTrue(candidate.purpose.isNotBlank(), candidate.id)
            }
        }
    }

    @Test
    fun `chi dung placeholder da khai bao`() {
        val declared = CarExecCatalog.placeholders
        val pattern = Regex("""\{[a-zA-Z]+\}""")
        CarExecCatalog.steps.forEach { step ->
            step.candidates.forEach { candidate ->
                candidate.commands.forEach { command ->
                    pattern.findAll(command).forEach { match ->
                        assertTrue(
                            match.value in declared,
                            "${candidate.id} dùng placeholder lạ ${match.value}; đã khai báo: $declared",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `mo va dong chieu phai la verdict cua nguoi cho tới khi co observable`() {
        // Đây là bất biến trung thực, không phải hạn chế tạm. Chừng nào chưa đo được "cụm đang hiện app",
        // gán MEASURED cho hai step này là tự lừa: máy sẽ báo đạt trong khi cụm vẫn hiện đồng hồ — đúng
        // chuyện đã xảy ra sáng 2026-07-27.
        listOf("open-projection", "teardown").forEach { id ->
            val step = CarExecCatalog.step(id)
            assertNotNull(step, "thiếu step $id")
            step!!.candidates.forEach {
                assertEquals(VerdictSource.HUMAN, it.verdictSource, "${it.id} không được tự nhận là đo được")
            }
        }
    }

    @Test
    fun `khong lenh nao trong catalog goi adb tu ben ngoai`() {
        CarExecCatalog.steps.forEach { step ->
            step.candidates.forEach { candidate ->
                candidate.commands.forEach { command ->
                    assertFalse(command.startsWith("adb "), "${candidate.id}: lệnh phải là lệnh shell trên xe")
                    assertFalse(command.contains("&&"), "${candidate.id}: mỗi lệnh một dòng để verdict truy được")
                }
            }
        }
    }
}
