package com.byd.clusternav.modules.clustercast

import com.byd.clusternav.testsupport.SourceRoots

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `CastAppCatalog.clusterDensityDpi` / `setClusterDensityDpi` need a real Android `Context`
 * (SharedPreferences), and this project has no Robolectric — so, exactly like
 * `CastFavoriteProtectToggleTest`, this reads the real source instead of instantiating the class.
 *
 * This is the cast-time density path (`preferredDensityDpi` passed into `CastFacade.runManualIntent`,
 * dispatched through `CommandKind.FIT_CLUSTER_COMPOSITE`'s inline "wm density" prefix in
 * `CastPlacementCommands.kt`) — a SEPARATE input from `CastRowActions.applyGeometry`'s
 * `CastAppCatalog.scaleOf(pkg).dpi` (gated instead by `CastGeometry.validate`, see `CastGeometryTest`'s
 * "density outside 72 to 640" case). What this locks down:
 *
 * (a) `setClusterDensityDpi` rejects an out-of-range value via `require(...)` BEFORE any prefs write —
 *     an invalid DPI is never even durably stored, so it can never later be read by `clusterDensityDpi`
 *     and handed to `runManualIntent` as `preferredDensityDpi` in the first place.
 * (b) The stored range is exactly `80..640` — the SAME literal `CastPlacementCommands.kt`'s
 *     `FIT_CLUSTER_COMPOSITE` branch uses for its own `takeIf { it in 80..640 }` guard on
 *     `request.geometry?.densityDpi`, so the storage-level gate and the shell-encoding-level
 *     defense-in-depth gate agree.
 * (c) `clusterDensityDpi`'s getter re-validates with the same `80..640` `takeIf`, so even a value that
 *     reached the store some other way (direct prefs edit, migration, corruption) is filtered before it
 *     is ever returned to a caller.
 */
class CastAppCatalogDensityDpiTest {

    private val catalogSource = SourceRoots.text("src/main/java/com/byd/clusternav/cast/platform/CastAppCatalog.kt")
    private val placementSource = SourceRoots.text(
        "src/main/kotlin/com/byd/clusternav/cast/transport/CastPlacementCommands.kt",
    )

    /** Extracts one top-level function's `{ ... }` body by brace-matching from its exact signature. */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "signature not found (source drifted, re-check this test): $signature" }
        val braceStart = source.indexOf('{', start)
        var depth = 0
        var i = braceStart
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(braceStart, i + 1)
                }
            }
            i++
        }
        error("unbalanced braces reading body of: $signature")
    }

    @Test
    fun `setClusterDensityDpi rejects out-of-range value via require before any prefs write`() {
        val body = functionBody(catalogSource, "fun setClusterDensityDpi(packageName: String, densityDpi: Int?) {")

        val requireIndex = body.indexOf("require(densityDpi == null || densityDpi in 80..640)")
        assertTrue(requireIndex >= 0, "range guard text changed -- re-verify this test's claims still hold")

        val editIndex = body.indexOf("prefs.edit()")
        assertTrue(editIndex >= 0, "expected setClusterDensityDpi to still write through prefs.edit()")
        assertTrue(requireIndex < editIndex, "the range guard must run strictly before the prefs write")

        val commitIndex = body.indexOf("editor.commit()")
        assertTrue(commitIndex > editIndex, "expected the edit to be committed after the guarded write")
    }

    @Test
    fun `stored and read-back density ranges agree with CastPlacementCommands' shell-encoding guard`() {
        val setterBody = functionBody(catalogSource, "fun setClusterDensityDpi(packageName: String, densityDpi: Int?) {")

        // clusterDensityDpi is expression-bodied (`fun clusterDensityDpi(...): Int? = prefs.getInt(...)`),
        // so there is no `{ }` to brace-match -- grab the signature line plus the next line instead.
        val getterSignature = "fun clusterDensityDpi(packageName: String): Int? ="
        val getterStart = catalogSource.indexOf(getterSignature)
        require(getterStart >= 0) { "clusterDensityDpi signature not found (source drifted, re-check this test)" }
        val getterLineEnd = catalogSource.indexOf('\n', catalogSource.indexOf('\n', getterStart) + 1)
        val getterBody = catalogSource.substring(getterStart, getterLineEnd)

        assertTrue(setterBody.contains("80..640"), "setter's stored range must be 80..640, found: $setterBody")
        assertTrue(getterBody.contains("80..640"), "getter's defensive filter must also be 80..640, found: $getterBody")

        // The FIT_CLUSTER_COMPOSITE branch is where CastAppCatalog.clusterDensityDpi's value is actually
        // turned into a "wm density" shell fragment (via preferredDensityDpi -> CastMutationRequest.geometry).
        val placementGuard = "val density = request.geometry?.densityDpi?.takeIf { it in 80..640 }"
        assertTrue(
            placementSource.contains(placementGuard),
            "CastPlacementCommands' FIT_CLUSTER_COMPOSITE density guard changed or moved -- " +
                "re-verify it still agrees with CastAppCatalog's 80..640",
        )
    }

    @Test
    fun `density literal appears exactly once as a range in setter, getter and placement guard`() {
        // Guards against a silent drift where only one of the three sites gets bumped (e.g. someone widens
        // the setter to 60..640 for a new vehicle profile but forgets the shell-encoding guard, leaving a
        // value that is stored and read back fine but silently dropped -- not rejected -- at dispatch time).
        val ranges = listOf(
            "prefs.getInt(\"dpi:\$packageName\", 0).takeIf { it in 80..640 }",
            "require(densityDpi == null || densityDpi in 80..640)",
        )
        ranges.forEach {
            assertTrue(catalogSource.contains(it), "expected CastAppCatalog to still contain: $it")
        }
        assertEquals(
            1,
            Regex("takeIf \\{ it in 80\\.\\.640 \\}").findAll(placementSource).count(),
            "expected exactly one 80..640 density guard in CastPlacementCommands' FIT_CLUSTER_COMPOSITE branch",
        )
    }
}
