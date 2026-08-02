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

/**
 * Cảnh báo `am start` ghi ra stderr khi task đích ĐÃ tồn tại và lệnh chỉ mang nó lên trước / gửi lại
 * đúng intent cho instance đang chạy — cả hai đều là ca BÌNH THƯỜNG của cast (app cần chiếu, hoặc
 * REASSERT_ON_CLUSTER xác nhận lại, thường đã có task sẵn), không phải lỗi.
 *
 * Danh sách này CHỈ được thêm bằng bằng chứng đo thật, từng dòng một — không đoán trước cả họ cảnh báo
 * "Warning: Activity not started, ...":
 *   - "its current task has been brought to the front" — đo trên DiLink3 2026-07-30, cast VietMap
 *     (docs/diagnostics/cast-stop-recovering-stuck-2026-07-30.md).
 *   - "intent has been delivered to currently running top-most instance." — đo trên DiLink3 2026-07-31,
 *     REASSERT_ON_CLUSTER gửi lại intent cho Google Maps khi task đã nằm sẵn trên cụm (ledger thật:
 *     bước `reassert-composite` ISSUED, `am stack list` xác nhận task ĐÃ visible=true trên display-1
 *     đúng lúc "unknown effect" này xảy ra) — đúng câu hỏi mở mà review 2026-07-30 nêu ("chưa biết"),
 *     giờ đã có bằng chứng.
 *
 * Trước khi có whitelist này, MỌI stderr không rỗng — kể cả các cảnh báo lành trên — đều bị coi là
 * UnknownEffect, đẩy một transaction vốn ĐÃ thành công (cửa sổ đã lên cụm thật) sang RECOVERING; nếu
 * epoch bền trôi qua trước khi recovery kịp chạy thì transaction đó kẹt vĩnh viễn, mọi cú bấm Dừng sau
 * đó chỉ thấy lại "Đang xử lý" vì transaction cũ chưa từng đóng.
 *
 * So khớp bằng CHÍNH XÁC (trim rồi so bằng tập hợp), không phải `contains` lỏng lẻo: mỗi phần tử đã có
 * bằng chứng lành từ dump thật, không phải đoán trước mọi cảnh báo mà `am` có thể in ra.
 */
private val BENIGN_LAUNCH_WARNINGS = setOf(
    "Warning: Activity not started, its current task has been brought to the front",
    "Warning: Activity not started, intent has been delivered to currently running top-most instance.",
)

fun classifyMutationShellResult(exitCode: Int, stdout: String, stderr: String): MutationResult {
    val trimmed = stderr.trim()
    val benign = trimmed in BENIGN_LAUNCH_WARNINGS
    if (exitCode != 0 || !(stderr.isBlank() || benign)) {
        return MutationResult.UnknownEffect(
            stderr.takeIf(String::isNotBlank)?.take(200) ?: "shell exit $exitCode after dispatch",
        )
    }
    // Cảnh báo lành vẫn phải NHÌN THẤY được, chỉ là không còn bị coi là lỗi: nó là dấu duy nhất phân
    // biệt "task cũ được mang lên trước/gửi lại intent" với "khởi tạo mới". Chính các chuỗi này là đầu
    // mối duy nhất tìm ra bug 2026-07-30/31; nuốt sạch nó khỏi evidence là tự bịt mắt cho lần điều tra
    // sau (evidence chỉ đi vào CastOperationLog qua CastExecutor.resultLabel, không nằm trong ledger bền).
    val evidence = if (benign) {
        listOf(stdout.trim(), trimmed).filter(String::isNotBlank).joinToString(" | ")
    } else stdout
    return MutationResult.Observed(evidence.take(200))
}

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
