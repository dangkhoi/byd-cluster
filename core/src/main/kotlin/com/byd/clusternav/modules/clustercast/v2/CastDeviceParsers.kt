package com.byd.clusternav.modules.clustercast.v2

/**
 * Hai bộ đọc output thiết bị, tách khỏi CastAndroidRuntime ngày 2026-07-27.
 *
 * Cả hai đều là parse chuỗi thuần — 0 lần dùng Context, 0 lần dùng dadb — nhưng trước đó chúng nằm
 * trong file Android nên không thể test mà không dựng runtime, và transport thì không dùng được chúng
 * nếu không kéo theo cả Android.
 */

object DumpObservedStateParser : ObservedStateParser {
    private val BOUNDS = Regex("(?:mBounds|bounds)\\s*=\\s*(?:Rect\\()?\\[?(\\d+)[, ]+(\\d+)\\]?[ ,\\-]+\\[?(\\d+)[, ]+(\\d+)\\]?\\)?", RegexOption.IGNORE_CASE)
    private val DENSITY = Regex("(?:densityDpi|density)\\s*[=: ]\\s*(\\d+)", RegexOption.IGNORE_CASE)
    private val PROJECTION_HINTS = setOf("carplay", "androidauto", "android.auto", "projection")
    private val APP_OPS_PACKAGE = Regex("(?im)^\\s*Package\\s+([A-Za-z][A-Za-z0-9_.]+)\\s*:")
    private val PIP_MODE = Regex(
        "PICTURE_IN_PICTURE\\s*:\\s*(allow|ignore|deny|default|foreground)",
        RegexOption.IGNORE_CASE,
    )

    /** A measured fallback keeps the runtime usable when the dump wording changes. */
    fun withFallback(measured: () -> NamedClusterDisplay?): ObservedStateParser =
        ObservedStateParser { raw -> parse(raw, measured) }

    override fun parse(raw: RawObservation): ObservationValue<ObservedState> = parse(raw) { null }

    fun parse(
        raw: RawObservation,
        measured: () -> NamedClusterDisplay?,
    ): ObservationValue<ObservedState> {
        val namedDisplay = discoverClusterDisplay(raw.displays)
            ?: measured()?.also { CastOperationLog.record("display identity from DisplayManager fallback: ${it.id}") }
            ?: return ObservationValue.Unknown(MISSING_NAMED_CLUSTER_DISPLAY_REASON)
        val display = namedDisplay.id
        val am = when (val parsed = CastAmStackParser.parse(raw.amStacks)) {
            is CastDumpParse.Known -> parsed.value
            is CastDumpParse.Malformed -> return ObservationValue.Unknown(parsed.reason)
        }
        if (am.stacks.isEmpty()) return ObservationValue.Unknown("AM stack topology is empty")
        val displayTasks = am.tasks.filter { it.displayId == display }
        val occupants = displayTasks.map { it.packageName }.toSet()
        val coarse = when (occupants.size) {
            0 -> ObservedCoarseState.IDLE_CLEAN
            1 -> ObservedCoarseState.ACTIVE_SINGLE
            else -> ObservedCoarseState.ACTIVE_MULTI
        }
        val records = displayTasks.map { Triple(it.packageName, it.taskId, it.visible) }
        // Measured on DiLink3 2026-07-31 (docs/specs/cast-recovery-honesty-and-multi-occupant.html §R2):
        // two distinct packages (app.revanced.android.apps.maps, vn.vietmap.live) both read visible=true
        // on the same display. `coarse` above already names this correctly as ACTIVE_MULTI — returning
        // `Unknown` right after THROWS AWAY that already-known fact. `target == null` here is not "we
        // could not tell": it is the honest answer "more than one app claims the screen, and nothing in
        // this dump says which one truly owns it" — that is real, nameable data for the render layer to
        // act on (see CastUiStateProjector), not a parse failure.
        val targetRecord = when (records.size) {
            0 -> null
            1 -> records.single()
            else -> records.filter { it.third == true }.singleOrNull()
        }
        val target = targetRecord?.let { CastTarget(it.first, it.second, display) }
        val residueRecords = records.filter { it != targetRecord }.filter { record ->
            PROJECTION_HINTS.any { record.first.contains(it, true) }
        }
        if (residueRecords.size > 1) return ObservationValue.Unknown("multiple protected residues")
        val residue = residueRecords.singleOrNull()?.let {
            ProtectedResidue(
                it.first,
                it.second,
                if (it.third == false) ResidueVisibility.HIDDEN else ResidueVisibility.UNKNOWN,
            )
        }
        val activeUser = raw.profile.trim().toIntOrNull()
            ?: return ObservationValue.Unsupported("active Android profile format unsupported")
        val profileId = "android-user-$activeUser"
        val geometry = if (coarse == ObservedCoarseState.IDLE_CLEAN && target == null) {
            AcceptedGeometry(
                CastRect(0, 0, namedDisplay.appWidth, namedDisplay.appHeight),
                namedDisplay.densityDpi,
                profileId,
            )
        } else {
            val geometryText = raw.wmDisplays + "\n" + raw.displays
            val bounds = BOUNDS.find(geometryText)?.groupValues?.let { groups ->
                val left = groups[1].toIntOrNull()
                val top = groups[2].toIntOrNull()
                val right = groups[3].toIntOrNull()
                val bottom = groups[4].toIntOrNull()
                if (left == null || top == null || right == null || bottom == null) {
                    return ObservationValue.Unknown("display bounds numeric format unsupported")
                }
                CastRect(left, top, right, bottom)
            }
            val density = DENSITY.find(geometryText)?.groupValues?.get(1)?.toIntOrNull()
            bounds?.let { AcceptedGeometry(it, density, profileId) }
        }
        val animationLines = raw.animations.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (animationLines.size < 3) return ObservationValue.Unknown("animation settings incomplete")
        val animations = linkedMapOf(
            "window_animation_scale" to animationLines[0],
            "transition_animation_scale" to animationLines[1],
            "animator_duration_scale" to animationLines[2],
        )
        val pipMode = target?.packageName?.let { packageName ->
            val headers = APP_OPS_PACKAGE.findAll(raw.appOps).toList()
            val index = headers.indexOfFirst { it.groupValues[1].equals(packageName, true) }
            if (index < 0) return ObservationValue.Unknown("target app-ops block unavailable")
            val block = raw.appOps.substring(
                headers[index].range.first,
                headers.getOrNull(index + 1)?.range?.first ?: raw.appOps.length,
            )
            PIP_MODE.find(block)?.groupValues?.get(1)?.lowercase() ?: "default"
        }
        return ObservationValue.Known(
            ObservedState(
                coarse, "display-$display", target, occupants, residue, geometry,
                animations, pipMode, profileId, namedDisplay.name,
            ),
        )
    }
}

object ProjectionSessionEvidenceParser {
    private val BOOLEAN = Regex(
        "(?:connectedPhoneSession|mPhoneConnected|sessionConnected)\\s*[=:]\\s*(true|false)",
        RegexOption.IGNORE_CASE,
    )
    private val STATE = Regex("(?:connectionState|sessionState)\\s*[=:]\\s*(CONNECTED|DISCONNECTED|2|0)", RegexOption.IGNORE_CASE)

    fun parse(raw: String): Boolean? {
        BOOLEAN.find(raw)?.groupValues?.get(1)?.let { return it.equals("true", true) }
        return when (STATE.find(raw)?.groupValues?.get(1)?.uppercase()) {
            "CONNECTED", "2" -> true
            "DISCONNECTED", "0" -> false
            else -> null
        }
    }
}

