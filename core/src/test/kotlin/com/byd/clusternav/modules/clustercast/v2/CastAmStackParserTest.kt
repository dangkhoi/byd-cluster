package com.byd.clusternav.modules.clustercast.v2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CastAmStackParserTest {
    @Test
    fun `numeric display parser ignores manager headings and preserves exact logical ids`() {
        val parsed = CastLogicalDisplayParser.parseHeaders(
            """
            DISPLAY MANAGER (dumpsys display)
            Display Adapters: size=4
            Display Devices: size=1
            Display 0:
              mDisplayId=0
            Display Power Controller Locked State:
            Display Power Controller Configuration:
            Display Power Controller Thread State:
            Display Power State:
            """.trimIndent(),
        ) as CastDumpParse.Known
        assertEquals(listOf(0), parsed.value)
    }

    @Test
    fun `numeric display parser returns all ids but rejects malformed and overflow candidates`() {
        val multiple = CastLogicalDisplayParser.parseHeaders("Display 0:\nDisplay 2:") as CastDumpParse.Known
        assertEquals(listOf(0, 2), multiple.value)
        listOf(
            "Display 2x:",
            "Display 2: trailing-garbage",
            "Display -1:",
            "Display 19261206365013889:",
        ).forEach { value ->
            assertTrue(CastLogicalDisplayParser.parseHeaders(value) is CastDumpParse.Malformed, value)
        }
    }

    @Test
    fun `real Stack parent and task child shape inherits display identity`() {
        val parsed = CastAmStackParser.parse(realMainOnly()) as CastDumpParse.Known
        assertEquals(listOf(27, 0, 19), parsed.value.stacks.map { it.stackId })
        assertEquals(listOf(0, 0, 0), parsed.value.stacks.map { it.displayId })
        assertEquals(listOf(31, 12, 23), parsed.value.tasks.map { it.taskId })
        assertEquals(listOf(0, 0, 0), parsed.value.tasks.map { it.displayId })
        assertEquals(
            listOf("com.example.clusternav", "com.android.launcher3", "com.android.systemui"),
            parsed.value.tasks.map { it.packageName },
        )
        assertEquals(listOf(null, true, false), parsed.value.tasks.map { it.visible })
    }

    @Test
    fun `RootTask parent shape is supported without same-line task display`() {
        val parsed = CastAmStackParser.parse(
            """
            RootTask id=4 bounds=[0,0][1920,720] displayId=2 userId=0
              Task id=8: com.example.maps/.Main visible=TRUE
            """.trimIndent(),
        ) as CastDumpParse.Known
        assertEquals(CastAmTaskRecord(4, 2, 8, "com.example.maps/.Main", "com.example.maps", true), parsed.value.tasks.single())
    }

    @Test
    fun `malformed AM candidates never become an empty clean snapshot`() {
        val malformed = listOf(
            "taskId=1: com.example.maps/.Main",
            "Stack id=1 userId=0\n taskId=2: com.example.maps/.Main",
            "Stack id=1 displayId=0 displayId=1\n taskId=2: com.example.maps/.Main",
            "Stack id=19261206365013889 displayId=0\n taskId=2: com.example.maps/.Main",
            "Stack id=1 displayId=19261206365013889\n taskId=2: com.example.maps/.Main",
            "Stack id=1 displayId=0\n taskId=19261206365013889: com.example.maps/.Main",
            "Stack id=1 displayId=0\n taskId=2: not-a-component",
            "Stack id=1 displayId=0\n taskId=2: com.example.maps/.Main displayId=0",
            "Stack id=1 displayId=0\n taskId=2: com.example.maps/.Main displayId?",
            "Stack id=1 displayId=0\ntaskId=2: com.example.maps/.Main",
            "Stack id=1 displayId=0\nSection:\n  taskId=2: com.example.maps/.Main",
            "Stack id=1 displayId=0\n taskId=2: com.example.maps/.Main\nStack id=1 displayId=0\n taskId=3: com.example.other/.Main",
            "Stack id=1 displayId=0\n taskId=2: com.example.maps/.Main\nStack id=3 displayId=0\n taskId=2: com.example.other/.Main",
            "Stack id=1 displayId=0\n taskId=2: com.example.maps/.Main taskId=3:",
            "Stack id=1 displayId=0\n taskId=2: com.example.maps/.Main visible=maybe",
            "Stack list empty on main display",
        )
        malformed.forEach { value ->
            assertTrue(CastAmStackParser.parse(value) is CastDumpParse.Malformed, value)
        }
    }

    @Test
    fun `clean display guard is true only for a parsed snapshot with zero exact-display tasks`() {
        assertTrue(CastAmStackParser.isKnownCleanDisplay(realMainOnly(), 2))
        val occupied = realMainOnly() + "\nStack id=40 displayId=2 userId=0\n" +
            "  taskId=41: com.example.maps/.Main visible=true"
        assertTrue(!CastAmStackParser.isKnownCleanDisplay(occupied, 2))
        assertTrue(!CastAmStackParser.isKnownCleanDisplay("Stack id=1 userId=0", 2))
        assertTrue(!CastAmStackParser.isKnownCleanDisplay(realMainOnly(), 0))
    }

    private fun realMainOnly() = """
        Stack id=27 bounds=[0,0][1920,1080] displayId=0 userId=0
          taskId=31: com.example.clusternav/.MainActivity bounds=[0,0][1920,1080] userId=0

        Stack id=0 bounds=[0,0][1920,1080] displayId=0 userId=0
          taskId=12: com.android.launcher3/.Launcher bounds=[0,0][1920,1080] userId=0 visible=true

        Stack id=19 bounds=[0,0][1920,1080] displayId=0 userId=0
          taskId=23: com.android.systemui/.SystemUI bounds=[0,0][1920,1080] userId=0 visible=false
    """.trimIndent()
}
