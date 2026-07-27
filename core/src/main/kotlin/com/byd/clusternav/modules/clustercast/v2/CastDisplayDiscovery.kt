package com.byd.clusternav.modules.clustercast.v2

/**
 * Đọc dumpsys display để nhận diện display của cụm.
 *
 * Tách khỏi CastAndroidRuntime ngày 2026-07-27. Đây là parse chuỗi thuần, không gọi thiết bị, nên chỗ
 * của nó là :core — và nhờ vậy nó test được off-car trên fixture thật của xe
 * (docs/refactor-car-execution/fixtures/dumpsys-display-*.txt). Trước đây nó nằm lẫn trong file
 * Android + dadb nên không ai test nó mà không dựng cả runtime.
 */

private val DISPLAY_BLOCK_HEADER = Regex("^Display\\s+([^:]+):\\s*$", RegexOption.IGNORE_CASE)
private val DISPLAY_BLOCK_ID = Regex("^mDisplayId\\s*=\\s*([^\\s,}]+)$", RegexOption.IGNORE_CASE)
private val BASE_DISPLAY_INFO = Regex(
    "mBaseDisplayInfo\\s*=\\s*DisplayInfo\\{\\s*\"(.+),\\s*displayId\\s+(\\d+)\"",
    RegexOption.IGNORE_CASE,
)
private val DISPLAY_APP_SIZE = Regex("\\bapp\\s+(\\d+)\\s*x\\s*(\\d+)", RegexOption.IGNORE_CASE)
private val DISPLAY_DENSITY = Regex("\\bdensity\\s+(\\d+)", RegexOption.IGNORE_CASE)
private const val MAX_DISPLAY_DIMENSION = 32_768
private const val MAX_DISPLAY_DENSITY_DPI = 10_000
const val MISSING_NAMED_CLUSTER_DISPLAY_REASON = "expected exactly one named cluster display"

fun classifyMutationShellResult(exitCode: Int, stdout: String, stderr: String): MutationResult =
    if (exitCode == 0 && stderr.isBlank()) MutationResult.Observed(stdout.take(200))
    else MutationResult.UnknownEffect(
        stderr.takeIf(String::isNotBlank)?.take(200) ?: "shell exit $exitCode after dispatch",
    )

data class NamedClusterDisplay(
    val id: Int,
    val name: String,
    val appWidth: Int,
    val appHeight: Int,
    val densityDpi: Int,
)

fun discoverClusterDisplay(dumpsysDisplay: String): NamedClusterDisplay? {
    val headerIds = when (val parsed = CastLogicalDisplayParser.parseHeaders(dumpsysDisplay)) {
        is CastDumpParse.Known -> parsed.value
        is CastDumpParse.Malformed -> return null
    }
    if (headerIds.size != headerIds.toSet().size) return null
    val logicalBlocks = mutableListOf<Pair<Int, List<String>>>()
    var currentDisplayId: Int? = null
    var currentBlock = mutableListOf<String>()

    fun finishBlock() {
        currentDisplayId?.let { logicalBlocks += it to currentBlock.toList() }
        currentBlock = mutableListOf()
    }

    dumpsysDisplay.lineSequence().forEach { raw ->
        val line = raw.trim()
        val header = DISPLAY_BLOCK_HEADER.matchEntire(line)
        if (header != null) {
            finishBlock()
            currentDisplayId = header.groupValues[1].trim().toIntOrNull()
            return@forEach
        }
        if (currentDisplayId != null) currentBlock += line
    }
    finishBlock()

    val candidates = ArrayList<NamedClusterDisplay>()
    logicalBlocks.forEach { (id, lines) ->
        val blockIds = lines.mapNotNull { DISPLAY_BLOCK_ID.matchEntire(it)?.groupValues?.get(1) }
        if (blockIds.size != 1 || blockIds.single().toIntOrNull() != id) return null
        if (id <= 0) return@forEach
        val sameBlock = lines.joinToString("\n")
        val baseInfos = BASE_DISPLAY_INFO.findAll(sameBlock).toList()
        if (baseInfos.isEmpty()) {
            if (sameBlock.contains("fission", ignoreCase = true) || sameBlock.contains("xdja", ignoreCase = true)) {
                return null
            }
            return@forEach
        }
        if (baseInfos.size != 1 || baseInfos.single().groupValues[2].toIntOrNull() != id) return null
        val name = baseInfos.single().groupValues[1].trim()
        if (!name.contains("fission", ignoreCase = true) && !name.contains("xdja", ignoreCase = true)) {
            return@forEach
        }
        val size = DISPLAY_APP_SIZE.find(sameBlock)?.groupValues ?: return null
        val width = size[1].toIntOrNull()?.takeIf { it in 1..MAX_DISPLAY_DIMENSION } ?: return null
        val height = size[2].toIntOrNull()?.takeIf { it in 1..MAX_DISPLAY_DIMENSION } ?: return null
        val density = DISPLAY_DENSITY.find(sameBlock)?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it in 1..MAX_DISPLAY_DENSITY_DPI } ?: return null
        candidates += NamedClusterDisplay(id, name, width, height, density)
    }
    return candidates.singleOrNull()
}

fun discoverClusterDisplayId(dumpsysDisplay: String): Int? =
    discoverClusterDisplay(dumpsysDisplay)?.id
