package com.byd.clusternav.modules.clustercast.v2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CastGeometryCommandEncoderTest {
    private val target = CastTarget("com.example.maps", 17, 2)
    private val geometry = AcceptedGeometry(CastRect(10, 20, 1810, 680), 180, "seal")

    @Test
    fun `task bounds and density bind exact observed target display`() {
        assertEquals(
            "am task resize 17 10 20 1810 680",
            CastGeometryCommandEncoder.taskBounds(target, "display-2", geometry),
        )
        assertEquals(
            "wm density 180 -d 2",
            CastGeometryCommandEncoder.displayDensity(target, "display-2", geometry),
        )
        assertEquals(
            "wm size 1810x680 -d 2; wm density 180 -d 2; wm overscan reset -d 2",
            CastGeometryCommandEncoder.restoreDisplay(
                2, "display-2", geometry.copy(bounds = CastRect(0, 0, 1810, 680)),
            ),
        )
    }

    @Test
    fun `stale identity invalid bounds and invalid density emit no command`() {
        assertNull(CastGeometryCommandEncoder.taskBounds(target, "display-3", geometry))
        assertNull(CastGeometryCommandEncoder.taskBounds(
            target, "display-2", geometry.copy(bounds = CastRect(5, 0, 4, 10)),
        ))
        assertNull(CastGeometryCommandEncoder.displayDensity(
            target, "display-2", geometry.copy(densityDpi = 700),
        ))
        assertNull(CastGeometryCommandEncoder.resetDisplay(2, "display-3"))
        assertNull(CastGeometryCommandEncoder.restoreDisplay(0, "display-0", geometry))
        assertNull(CastGeometryCommandEncoder.restoreDisplay(2, "display-2", geometry.copy(densityDpi = null)))
    }
}
