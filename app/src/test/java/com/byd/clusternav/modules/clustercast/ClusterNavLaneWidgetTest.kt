package com.byd.clusternav.modules.clustercast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Decision gate for the OEM "simple navigation" cluster widget (op 39).
 *
 * Contract (two-track + display mode): assert op 39 ONLY in nav-only mode (Cast master switch OFF)
 * while nav is active AND the user chose the centre+ETA display mode. When Cast is ON the Cast track
 * owns the cluster and the widget must not fight it; when the user chose "small/top" the broadcast
 * default is left to draw and op 39 is withheld (provisional pending an on-car opcode probe).
 */
class ClusterNavLaneWidgetTest {

    @Test fun `asserts in nav-only centre mode (nav active, cast off, centre)`() {
        assertTrue(ClusterNavLaneWidget.shouldAssert(navActive = true, castEnabled = false, centerMode = true))
    }

    @Test fun `does not assert while casting is enabled`() {
        assertFalse(ClusterNavLaneWidget.shouldAssert(navActive = true, castEnabled = true, centerMode = true))
    }

    @Test fun `does not assert when nav is idle`() {
        assertFalse(ClusterNavLaneWidget.shouldAssert(navActive = false, castEnabled = false, centerMode = true))
        assertFalse(ClusterNavLaneWidget.shouldAssert(navActive = false, castEnabled = true, centerMode = true))
    }

    @Test fun `does not assert in small-top mode even when nav active and cast off`() {
        // "small/top" leaves the broadcast default to draw — op 39 (centre overlay) is withheld
        // until an on-car opcode probe identifies the small-variant opcode.
        assertFalse(ClusterNavLaneWidget.shouldAssert(navActive = true, castEnabled = false, centerMode = false))
        // And of course still off when casting owns the cluster.
        assertFalse(ClusterNavLaneWidget.shouldAssert(navActive = true, castEnabled = true, centerMode = false))
    }

    @Test fun `opcode is 39 simple navigation`() {
        assertEquals(39, ClusterNavLaneWidget.OP_SIMPLE_NAV)
    }
}
