package com.byd.clusternav.modules.clustercast.v2

import com.byd.clusternav.testsupport.SourceRoots
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * The field log is what makes a failed cast diagnosable on the vehicle: every journaled step, its
 * exact shell command and the refusal reason must be visible without a host attached.
 */
class CastOperationLogTest {

    private fun source(relative: String): String {
        val direct = Paths.get("app/src/$relative")
        val nested = SourceRoots.path("src/$relative")
        return (if (Files.exists(direct)) direct else nested).toFile().readText()
    }

    @BeforeEach
    fun reset() {
        CastOperationLog.clear()
        CastOperationLog.useClock { 3_600_000L + 61_000L }
    }

    @Test
    fun `entries are ordered timestamped and bounded`() {
        repeat(300) { CastOperationLog.record("step $it") }
        val snapshot = CastOperationLog.snapshot()
        assertEquals(240, snapshot.size)
        assertEquals("step 60", snapshot.first().text)
        assertEquals("step 299", snapshot.last().text)
        assertTrue(CastOperationLog.render(3).startsWith("01:01:01  step 297"))
    }

    @Test
    fun `blank entries are ignored and long entries are truncated`() {
        CastOperationLog.record("   ")
        CastOperationLog.record("")
        assertEquals(0, CastOperationLog.snapshot().size)
        CastOperationLog.record("x".repeat(900))
        assertEquals(400, CastOperationLog.snapshot().single().text.length)
    }

    @Test
    fun `the executor records every step and its outcome`() {
        val executor = source("main/java/com/byd/clusternav/modules/clustercast/v2/CastExecutor.kt")
        assertTrue(executor.contains("CastOperationLog.record(\"\${plan.operation} ▸ \${step.stepId}"))
        assertTrue(executor.contains("resultLabel(result)"))
        assertTrue(executor.contains("\"REFUSED \${result.reason.take(120)}\""))
        assertTrue(executor.contains("\"UNKNOWN \${result.reason.take(120)}\""))
    }

    @Test
    fun `the gateway records the exact dispatched command`() {
        val gateway = source("main/java/com/byd/clusternav/cast/transport/CastAdbGateway.kt")
        assertTrue(gateway.contains("CastOperationLog.record(\"   $ \$command\")"))
        assertTrue(gateway.indexOf("CastOperationLog.record(\"   $ \$command\")") < gateway.indexOf("val result = adb.shell(command)"))
    }

    @Test
    fun `diagnostics surfaces the log read-only`() {
        val diag = source("main/java/com/byd/clusternav/modules/clustercast/DiagActivity.kt")
        // DiagActivity now uses SimpleCastRuntime directly (V2 facade removed)
        assertTrue(diag.contains("SimpleCastRuntime"))
        assertFalse(diag.contains("CastOperationLog.clear"))
        // CastFacade may appear in comments but must not be imported/instantiated
        assertFalse(diag.lines().any { it.trimStart().startsWith("import") && "CastFacade" in it })
    }
}
