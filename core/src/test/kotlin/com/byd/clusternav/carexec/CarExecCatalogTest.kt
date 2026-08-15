package com.byd.clusternav.carexec

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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

    @Test
    fun `steps output giu nguyen golden truoc khi tach catalog`() {
        val expectedStepIds = listOf(
            "observe", "place", "open-projection", "teardown", "restore", "switch",
            "adjust-geometry", "adjust-dpi", "set-style",
            "nav-listener", "nav-source", "nav-render-gate", "nav-cluster-lane",
            "bootstrap-cold", "probe-target", "resume-protected", "return-protected",
            "pip-guard", "animation-quiesce", "orphan-inspect", "target-process",
            "power-state", "capture-state", "probe-profile",
            "reissue-policy",
            "hud-probe", "cluster-overlay-toggles",
        )
        val expectedCandidateIds = listOf(
            "observe.dumpsys", "place.freeform-then-resize", "place.freeform-only", "place.movestack",
            "open.seal-30-16-35", "open.seal-16-only", "teardown.18-then-0", "teardown.0-only",
            "restore.main-standard", "restore.globals", "switch.reparent-warm", "switch.place-then-fit",
            "geometry.task-resize", "geometry.overscan", "dpi.wm-density", "dpi.reset",
            "style.curved-30", "style.flat-31", "style.probe-screen-size-29", "nav.listener-allow",
            "nav.listener-read", "nav.notification-dump", "gate.probe", "gate.baseline-broadcast-only",
            "gate.broadcast-full-render", "gate.setprop-navi-protect", "gate.setprop-whitelist",
            "gate.setprop-change-auth", "gate.navopen-probe", "gate.navopen-open", "gate.navopen-close",
            "nav.cluster-lane-visual", "bootstrap.seal-cold", "target.package-info", "protected.resume-no-kill",
            "return.gentle-main", "return.movestack-main", "pip.block", "pip.restore", "animation.disable",
            "animation.restore", "orphan.list", "process.force-stop", "power.sleep-wake",
            "capture.full-surface-flinger", "probe.services-and-display", "probe.autocontainer-whitelist",
            "probe.magicwindow-service", "probe.trafficmonitor-service", "probe.naviserviceapi-service",
            "probe.vehiclesettings-installed", "reissue.full-while-warm",
            "reissue.16-only-while-warm", "reissue.35-only-while-warm", "reissue.return-then-recast",
            "reissue.task-placed-projection-closed", "hud.config-read", "hud.switch-feedback-read",
            "hud.switch-on", "hud.switch-off", "hud.nav-content-toggle-on", "hud.adas-content-toggle-on",
            "overlay.adas-window-show", "overlay.adas-window-hide", "overlay.warning-lamps-on",
            "overlay.warning-lamps-off", "overlay.adas-debug-legacy-on", "overlay.adas-debug-legacy-off",
        )
        val actualCandidateIds = CarExecCatalog.steps.flatMap { step -> step.candidates.map { it.id } }

        assertEquals(expectedStepIds, CarExecCatalog.steps.map { it.id })
        assertEquals(expectedCandidateIds, actualCandidateIds)
        assertEquals(27, CarExecCatalog.steps.size)
        assertEquals(68, actualCandidateIds.size)

        val bytes = (CarExecCommands.steps() + "\n").toByteArray(StandardCharsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(43_182, bytes.size)
        assertEquals(433, bytes.count { it == '\n'.code.toByte() })
        assertEquals("aadb008d0319a70204eb643cb128cdf8f8473dd9e8795f0bd8671aeaf914247d", digest)
    }
}
