package com.byd.clusternav.modules.clustercast.v2

import java.util.Collections

sealed interface CastDumpParse<out T> {
    data class Known<T>(val value: T) : CastDumpParse<T>
    data class Malformed(val reason: String) : CastDumpParse<Nothing>
}

internal object CastLogicalDisplayParser {
    private val DISPLAY_HEADING = Regex("^Display\\s+([^:]+):\\s*$", RegexOption.IGNORE_CASE)
    private val DISPLAY_CANDIDATE = Regex("^Display\\s+([^\\s:]+).*$", RegexOption.IGNORE_CASE)
    private val NUMERIC_ID = Regex("^[0-9]+$")

    fun parseHeaders(raw: String): CastDumpParse<List<Int>> {
        val ids = ArrayList<Int>()
        raw.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            val exact = DISPLAY_HEADING.matchEntire(line)
            val token = exact?.groupValues?.get(1)?.trim()
            if (token == null) {
                val candidate = DISPLAY_CANDIDATE.matchEntire(line)?.groupValues?.get(1)
                if (candidate?.firstOrNull()?.let { it.isDigit() || it == '-' || it == '+' } == true) {
                    return CastDumpParse.Malformed("malformed logical display header at line ${index + 1}")
                }
                return@forEachIndexed
            }
            if (token.firstOrNull()?.let { it.isDigit() || it == '-' || it == '+' } != true) {
                return@forEachIndexed
            }
            if (!NUMERIC_ID.matches(token)) {
                return CastDumpParse.Malformed("malformed logical display header at line ${index + 1}")
            }
            val id = token.toIntOrNull()
                ?: return CastDumpParse.Malformed("logical display id overflow at line ${index + 1}")
            ids += id
        }
        return CastDumpParse.Known(Collections.unmodifiableList(ids))
    }
}

data class CastAmStackRecord(val stackId: Int, val displayId: Int)

data class CastAmTaskRecord(
    val stackId: Int,
    val displayId: Int,
    val taskId: Int,
    val component: String,
    val packageName: String,
    val visible: Boolean?,
)

class CastAmStackSnapshot(
    stacks: Collection<CastAmStackRecord>,
    tasks: Collection<CastAmTaskRecord>,
) {
    val stacks: List<CastAmStackRecord> = Collections.unmodifiableList(ArrayList(stacks))
    val tasks: List<CastAmTaskRecord> = Collections.unmodifiableList(ArrayList(tasks))
}

object CastAmStackParser {
    private val HEADER_CANDIDATE = Regex("^(?:Stack|RootTask)\\s+", RegexOption.IGNORE_CASE)
    private val HEADER_MENTION = Regex("^(?:Stack|RootTask)\\b", RegexOption.IGNORE_CASE)
    private val STACK_ID = Regex("\\bid\\s*=\\s*([^\\s,}]+)", RegexOption.IGNORE_CASE)
    private val DISPLAY_ID = Regex("\\bdisplayId\\s*[=: ]\\s*([^\\s,}]+)", RegexOption.IGNORE_CASE)
    private val DISPLAY_ID_MENTION = Regex("\\bdisplayId\\b", RegexOption.IGNORE_CASE)
    private val TASK_CANDIDATE = Regex("^(?:taskId|Task\\s+id)\\s*[=:]", RegexOption.IGNORE_CASE)
    private val TASK_MENTION = Regex("\\b(?:taskId|Task\\s+id)\\b", RegexOption.IGNORE_CASE)
    private val TASK = Regex(
        "^(?:taskId|Task\\s+id)\\s*[=:]\\s*([^\\s:]+)\\s*:\\s*([^\\s]+)",
        RegexOption.IGNORE_CASE,
    )
    private val COMPONENT = Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")
    private val VISIBLE = Regex("\\bvisible\\s*=\\s*(true|false)\\b", RegexOption.IGNORE_CASE)
    private val VISIBLE_MENTION = Regex("\\bvisible\\s*=", RegexOption.IGNORE_CASE)

    fun parse(raw: String): CastDumpParse<CastAmStackSnapshot> {
        val stacks = ArrayList<CastAmStackRecord>()
        val tasks = ArrayList<CastAmTaskRecord>()
        val seenStackIds = HashSet<Int>()
        val seenTaskIds = HashSet<Int>()
        var parent: CastAmStackRecord? = null
        var parentIndent = -1

        raw.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) {
                parent = null
                parentIndent = -1
                return@forEachIndexed
            }
            val indent = rawLine.length - rawLine.trimStart().length
            if (HEADER_CANDIDATE.containsMatchIn(line)) {
                val stackIds = STACK_ID.findAll(line).map { it.groupValues[1] }.toList()
                val displayIds = DISPLAY_ID.findAll(line).map { it.groupValues[1] }.toList()
                if (stackIds.size != 1 || displayIds.size != 1 || TASK_MENTION.containsMatchIn(line)) {
                    return malformed(index, "stack header requires exactly one stack id and display id")
                }
                val stackId = stackIds.single().toIntOrNull()?.takeIf { it >= 0 }
                    ?: return malformed(index, "stack id is malformed or overflowing")
                val displayId = displayIds.single().toIntOrNull()?.takeIf { it >= 0 }
                    ?: return malformed(index, "stack display id is malformed or overflowing")
                if (!seenStackIds.add(stackId)) return malformed(index, "stack id is duplicated")
                parent = CastAmStackRecord(stackId, displayId).also(stacks::add)
                parentIndent = indent
                return@forEachIndexed
            }
            if (HEADER_MENTION.containsMatchIn(line)) {
                return malformed(index, "unrecognized stack header candidate")
            }

            if (TASK_CANDIDATE.containsMatchIn(line)) {
                val current = parent ?: return malformed(index, "task has no parent stack")
                if (indent <= parentIndent) return malformed(index, "task is not nested under its parent stack")
                if (DISPLAY_ID_MENTION.containsMatchIn(line)) {
                    return malformed(index, "task display id must be inherited from its parent stack")
                }
                if (TASK_MENTION.findAll(line).count() != 1) {
                    return malformed(index, "task line has ambiguous task identity")
                }
                val match = TASK.find(line) ?: return malformed(index, "task line is malformed")
                val taskId = match.groupValues[1].toIntOrNull()?.takeIf { it >= 0 }
                    ?: return malformed(index, "task id is malformed or overflowing")
                if (!seenTaskIds.add(taskId)) return malformed(index, "task id is duplicated")
                val component = match.groupValues[2]
                if (!COMPONENT.matches(component)) return malformed(index, "task component is malformed")
                val visibleMentions = VISIBLE_MENTION.findAll(line).count()
                val visibleMatches = VISIBLE.findAll(line).map { it.groupValues[1] }.toList()
                if (visibleMentions != visibleMatches.size || visibleMatches.size > 1) {
                    return malformed(index, "task visibility is malformed or ambiguous")
                }
                tasks += CastAmTaskRecord(
                    current.stackId,
                    current.displayId,
                    taskId,
                    component,
                    component.substringBefore('/'),
                    visibleMatches.singleOrNull()?.equals("true", ignoreCase = true),
                )
                return@forEachIndexed
            }

            if (TASK_MENTION.containsMatchIn(line)) {
                return malformed(index, "unrecognized task candidate")
            }
            if (parent != null && indent <= parentIndent) {
                parent = null
                parentIndent = -1
            }
        }
        return CastDumpParse.Known(CastAmStackSnapshot(stacks, tasks))
    }

    fun isKnownCleanDisplay(raw: String, displayId: Int): Boolean {
        if (displayId <= 0) return false
        val snapshot = (parse(raw) as? CastDumpParse.Known)?.value ?: return false
        return snapshot.stacks.isNotEmpty() && snapshot.tasks.none { it.displayId == displayId }
    }

    private fun malformed(index: Int, reason: String) =
        CastDumpParse.Malformed("$reason at line ${index + 1}")
}
