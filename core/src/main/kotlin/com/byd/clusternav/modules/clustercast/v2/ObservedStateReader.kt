package com.byd.clusternav.modules.clustercast.v2

/** Pure parser seam. Stage 3 will supply the vehicle-profile parser without changing gateway ownership. */
fun interface ObservedStateParser {
    fun parse(raw: RawObservation): ObservationValue<ObservedState>
}

class ObservedStateReader(
    private val gateway: ShellGateway,
    private val parser: ObservedStateParser,
) {
    private var nowEpochMillis: () -> Long = System::currentTimeMillis

    constructor(
        gateway: ShellGateway,
        parser: ObservedStateParser,
        nowEpochMillis: () -> Long,
    ) : this(gateway, parser) {
        this.nowEpochMillis = nowEpochMillis
    }
    fun read(deadlineAtEpochMillis: Long = Long.MAX_VALUE): ObservationValue<ObservedState> =
        when (val raw = inspectRaw(deadlineAtEpochMillis)) {
            is ObservationValue.Known -> parser.parse(raw.value)
            is ObservationValue.Unknown -> raw
            is ObservationValue.Unsupported -> raw
        }

    /** Returns ordered raw output; every command shares the caller's absolute deadline. */
    fun inspectRaw(deadlineAtEpochMillis: Long = Long.MAX_VALUE): ObservationValue<RawObservation> {
        val values = ArrayList<String>(OBSERVATION_ORDER.size)
        for (kind in OBSERVATION_ORDER) {
            when (val value = observe(kind, deadlineAtEpochMillis)) {
                is ObservationValue.Known -> values += value.value
                is ObservationValue.Unknown -> return value
                is ObservationValue.Unsupported -> return value
            }
        }
        return ObservationValue.Known(
            RawObservation(
                amStacks = values[0],
                wmDisplays = values[1],
                displays = values[2],
                profile = values[3],
                animations = values[4],
                appOps = values[5],
            ),
        )
    }

    private fun observe(kind: CommandKind, deadlineAtEpochMillis: Long): ObservationValue<String> {
        val remaining = if (deadlineAtEpochMillis == Long.MAX_VALUE) {
            MAX_OBSERVATION_MILLIS
        } else {
            deadlineAtEpochMillis - nowEpochMillis()
        }
        if (remaining <= 0) return ObservationValue.Unknown("observation deadline exceeded before $kind")
        val result = gateway.execute(
            ReadOnlyShellRequest.observation(kind, remaining.coerceAtMost(MAX_OBSERVATION_MILLIS)),
        )
        if (deadlineAtEpochMillis != Long.MAX_VALUE && nowEpochMillis() > deadlineAtEpochMillis) {
            return ObservationValue.Unknown("observation deadline exceeded during $kind")
        }
        return when (result) {
            is ShellResult.Success -> if (result.stdout.isBlank()) {
                ObservationValue.Unknown("$kind returned an empty observation")
            } else {
                ObservationValue.Known(result.stdout)
            }
            is ShellResult.Failure -> ObservationValue.Unknown("$kind failed: ${result.stderr.take(160)}")
            is ShellResult.Timeout -> ObservationValue.Unknown("$kind timed out after ${result.elapsedMillis}ms")
            is ShellResult.Closed -> ObservationValue.Unknown("$kind gateway closed")
        }
    }

    private companion object {
        const val MAX_OBSERVATION_MILLIS = 15_000L
        val OBSERVATION_ORDER = listOf(
            CommandKind.AM_STACK_LIST,
            CommandKind.WM_DISPLAYS,
            CommandKind.DISPLAY_STATE,
            CommandKind.PROFILE_STATE,
            CommandKind.ANIMATION_STATE,
            CommandKind.APP_OPS_STATE,
        )
    }
}
