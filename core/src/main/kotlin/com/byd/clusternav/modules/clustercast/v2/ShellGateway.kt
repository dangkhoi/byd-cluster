package com.byd.clusternav.modules.clustercast.v2

import java.util.Collections

private val ANDROID_PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

/** Stage 2 exposes observation only. A future executor must own any mutating gateway. */
interface ShellGateway : AutoCloseable {
    fun execute(request: ReadOnlyShellRequest): ShellResult
    override fun close()
}

data class ReadOnlyShellRequest private constructor(
    val kind: CommandKind,
    val command: String,
    val deadlineMillis: Long,
) {
    init {
        require(!kind.mutating) { "Stage-2 ShellGateway rejects mutating command kind $kind" }
        require(command.isNotBlank())
        require(deadlineMillis in 1..15_000)
    }

    companion object {
        fun observation(kind: CommandKind, deadlineMillis: Long = 15_000): ReadOnlyShellRequest {
            require(kind in CommandAllowlist.readOnly) { "Command kind $kind is not an observation command" }
            require(kind !in setOf(CommandKind.PACKAGE_RESOLVE, CommandKind.PHONE_SESSION_STATE)) {
                "$kind requires a validated package name"
            }
            return ReadOnlyShellRequest(kind, ShellCommandEncoder.encode(kind), deadlineMillis)
        }

        fun phoneSession(packageName: String, deadlineMillis: Long = 15_000): ReadOnlyShellRequest {
            require(ANDROID_PACKAGE_NAME.matches(packageName)) { "Invalid Android package name" }
            return ReadOnlyShellRequest(
                CommandKind.PHONE_SESSION_STATE,
                ShellCommandEncoder.encodePhoneSession(packageName),
                deadlineMillis,
            )
        }

        fun packageResolution(packageName: String, deadlineMillis: Long = 15_000): ReadOnlyShellRequest {
            require(ANDROID_PACKAGE_NAME.matches(packageName)) { "Invalid Android package name" }
            return ReadOnlyShellRequest(
                CommandKind.PACKAGE_RESOLVE,
                ShellCommandEncoder.encodePackageResolution(packageName),
                deadlineMillis,
            )
        }
    }
}

sealed interface ShellResult {
    val elapsedMillis: Long
    data class Success(val stdout: String, val stderr: String, override val elapsedMillis: Long) : ShellResult
    data class Failure(val exitCode: Int?, val stderr: String, override val elapsedMillis: Long) : ShellResult
    data class Timeout(override val elapsedMillis: Long) : ShellResult
    data class Closed(override val elapsedMillis: Long) : ShellResult
}

object CommandAllowlist {
    val readOnly: Set<CommandKind> = Collections.unmodifiableSet(linkedSetOf(
        CommandKind.AM_STACK_LIST,
        CommandKind.WM_DISPLAYS,
        CommandKind.DISPLAY_STATE,
        CommandKind.PROFILE_STATE,
        CommandKind.ANIMATION_STATE,
        CommandKind.APP_OPS_STATE,
        CommandKind.PHONE_SESSION_STATE,
        CommandKind.PACKAGE_RESOLVE,
    ))

    val plannedMutation: Set<CommandKind> = Collections.unmodifiableSet(
        CommandKind.entries.filterTo(linkedSetOf()) { it.mutating }
    )

    init {
        check(readOnly.intersect(plannedMutation).isEmpty())
        check(readOnly + plannedMutation == CommandKind.entries.toSet())
    }
}

/**
 * The only raw command encoding for the fixed read/mutation kinds. Placement rungs that need a
 * resolved stack or display id — including the D10 move-stack rung — are encoded in
 * [CastPlacementCommands], never here.
 */
object ShellCommandEncoder {
    fun encode(kind: CommandKind): String = when (kind) {
        CommandKind.AM_STACK_LIST -> "am stack list"
        CommandKind.WM_DISPLAYS -> "dumpsys window displays"
        CommandKind.DISPLAY_STATE -> "dumpsys display"
        CommandKind.PROFILE_STATE -> "am get-current-user"
        CommandKind.ANIMATION_STATE -> "settings get global window_animation_scale; settings get global transition_animation_scale; settings get global animator_duration_scale"
        CommandKind.APP_OPS_STATE -> "dumpsys appops"
        CommandKind.PHONE_SESSION_STATE -> error("PHONE_SESSION_STATE requires a validated package name")
        CommandKind.PACKAGE_RESOLVE -> error("PACKAGE_RESOLVE requires a validated package name")
        else -> error("Mutating command $kind has no Stage-2 encoder")
    }

    fun encodePhoneSession(packageName: String): String {
        require(ANDROID_PACKAGE_NAME.matches(packageName)) { "Invalid Android package name" }
        return "dumpsys activity services $packageName"
    }

    fun encodePackageResolution(packageName: String): String {
        require(ANDROID_PACKAGE_NAME.matches(packageName)) { "Invalid Android package name" }
        return "cmd package resolve-activity --brief -a android.intent.action.MAIN " +
            "-c android.intent.category.LAUNCHER $packageName"
    }
}
