package com.byd.clusternav.modules.clustercast.v2

import java.util.ArrayDeque
import java.util.Collections

/**
 * Process-wide, in-memory trace of what the Cast runtime actually did.
 *
 * V1's most valuable field asset was a visible step-by-step log: on the vehicle you can see which
 * rung of the ladder ran, the exact shell command, and why a step was refused. This is that log,
 * bounded and read-only for every UI surface. It holds no durable state and never affects policy.
 */
object CastOperationLog {

    data class Entry(val atEpochMillis: Long, val text: String)

    private const val CAPACITY = 240
    private val entries = ArrayDeque<Entry>()
    private var clock: () -> Long = System::currentTimeMillis

    fun record(text: String) {
        val trimmed = text.trim().take(400)
        if (trimmed.isEmpty()) return
        synchronized(entries) {
            while (entries.size >= CAPACITY) entries.removeFirst()
            entries.addLast(Entry(clock(), trimmed))
        }
    }

    fun snapshot(): List<Entry> = synchronized(entries) {
        Collections.unmodifiableList(ArrayList(entries))
    }

    fun render(limit: Int = CAPACITY): String = snapshot().takeLast(limit)
        .joinToString("\n") { "${stamp(it.atEpochMillis)}  ${it.text}" }

    fun clear() = synchronized(entries) { entries.clear() }

    fun useClock(source: () -> Long) {
        clock = source
    }

    private fun stamp(atEpochMillis: Long): String {
        val seconds = atEpochMillis / 1000
        return "%02d:%02d:%02d".format((seconds / 3600) % 24, (seconds / 60) % 60, seconds % 60)
    }
}
