package com.byd.clusternav.carexec

import com.byd.clusternav.modules.clustercast.v2.DumpObservedStateParser
import com.byd.clusternav.cast.transport.CastAdbGateway
import com.byd.clusternav.modules.clustercast.v2.ObservationValue
import com.byd.clusternav.modules.clustercast.v2.ObservedStateReader
import dadb.AdbKeyPair
import java.io.File

/**
 * Runner headless để đánh giá capability trên xe **không cần APK**.
 *
 * Đây là entry point thứ hai của cùng một hiện thực: app gọi các lớp này qua Android, runner gọi chúng
 * từ laptop qua adb. Nhờ vậy không cần bản chép thứ hai bằng shell script — thứ mà ngày 27/7 đã cho thấy
 * tác hại: logic đặt/tháo cụm tồn tại ở hai nơi và không ai biết bản nào đúng.
 *
 * Chỉ đọc. Không lệnh nào ở đây làm biến đổi trạng thái xe. Các capability gây biến đổi chỉ được thêm
 * sau khi có observable phân biệt "cụm đang hiện app" với "cụm đang hiện đồng hồ" (Q1 trong spec).
 *
 * Dùng: ./gradlew :car-integration:run --args="observe --host <ip-head-unit>"
 */
object CarExecCli {

    private const val DEFAULT_PORT = 5555

    @JvmStatic
    fun main(args: Array<String>) {
        val command = args.firstOrNull()
        if (command == null || command in setOf("-h", "--help", "help")) {
            printUsage()
            return
        }
        val host = args.value("--host") ?: "localhost"
        val port = args.value("--port")?.toIntOrNull() ?: DEFAULT_PORT
        val keyDir = args.value("--keys") ?: "${System.getProperty("user.home")}/.android"

        val ledgerPath = args.value("--ledger") ?: CarExecCommands.DEFAULT_LEDGER
        when (command) {
            "observe" -> if (args.contains("--recorded")) observeRecorded() else observe(host, port, keyDir)
            "steps" -> println(CarExecCommands.steps())
            "run" -> run(args, host, port, keyDir)
            "verdict" -> verdict(args, ledgerPath, "$host:$port")
            "e2e" -> println(CarExecCommands.e2e(ledgerPath))
            "scenarios" -> println(CarExecCommands.scenarios(ledgerPath))
            "plan" -> println(CarExecCommands.planScenario(args.getOrNull(1) ?: "", placeholderValues(args)))
            "scenario" -> if (args.contains("--run")) {
                runScenario(args, host, port, keyDir, ledgerPath)
            } else {
                println(CarExecCommands.scenario(ledgerPath, args.getOrNull(1) ?: ""))
            }
            else -> {
                println("""{"error":"lệnh không biết: $command"}""")
                printUsage()
            }
        }
    }

    private fun run(args: Array<String>, host: String, port: Int, keyDir: String) {
        val candidate = args.getOrNull(1)
        if (candidate == null || candidate.startsWith("--")) {
            println("cần tên candidate. Xem 'steps'.")
            return
        }
        val keys = CarExecShell.keysFrom(keyDir) ?: run {
            println("""{"error":"không thấy cặp khoá adb tại $keyDir"}""")
            return
        }
        val values = buildMap {
            args.value("--pkg")?.let { put(CarExecCatalog.PLACEHOLDER_PACKAGE, it) }
            args.value("--comp")?.let { put(CarExecCatalog.PLACEHOLDER_COMPONENT, it) }
            args.value("--display")?.let { put(CarExecCatalog.PLACEHOLDER_DISPLAY, it) }
            args.value("--task")?.let { put(CarExecCatalog.PLACEHOLDER_TASK, it) }
            put(CarExecCatalog.PLACEHOLDER_SERVICE, args.value("--svc") ?: "AutoContainer")
        }
        if (args.contains("--dry-run")) {
            println(CarExecCommands.dryRunCandidate(candidate, values))
            return
        }
        CarExecShell(host, port, keys).use { shell ->
            println(CarExecCommands.runCandidate(shell::run, candidate, values))
        }
    }

    private fun runScenario(args: Array<String>, host: String, port: Int, keyDir: String, ledgerPath: String) {
        val id = args.getOrNull(1)
        if (id == null || id.startsWith("--")) {
            println("cần tên kịch bản. Xem 'scenarios'.")
            return
        }
        val keys = CarExecShell.keysFrom(keyDir) ?: run {
            println("""{"error":"không thấy cặp khoá adb tại $keyDir"}""")
            return
        }
        val from = args.value("--from")?.toIntOrNull() ?: 1
        CarExecShell(host, port, keys).use { shell ->
            println(CarExecCommands.runScenario(ledgerPath, id, placeholderValues(args), from, shell::run))
        }
    }

    private fun placeholderValues(args: Array<String>): Map<String, String> = buildMap {
        args.value("--pkg")?.let { put(CarExecCatalog.PLACEHOLDER_PACKAGE, it) }
        args.value("--comp")?.let { put(CarExecCatalog.PLACEHOLDER_COMPONENT, it) }
        args.value("--display")?.let { put(CarExecCatalog.PLACEHOLDER_DISPLAY, it) }
        args.value("--task")?.let { put(CarExecCatalog.PLACEHOLDER_TASK, it) }
        args.value("--left")?.let { put(CarExecCatalog.PLACEHOLDER_LEFT, it) }
        args.value("--top")?.let { put(CarExecCatalog.PLACEHOLDER_TOP, it) }
        args.value("--right")?.let { put(CarExecCatalog.PLACEHOLDER_RIGHT, it) }
        args.value("--bottom")?.let { put(CarExecCatalog.PLACEHOLDER_BOTTOM, it) }
        args.value("--dpi")?.let { put(CarExecCatalog.PLACEHOLDER_DPI, it) }
        put(CarExecCatalog.PLACEHOLDER_SERVICE, args.value("--svc") ?: "AutoContainer")
    }

    private fun verdict(args: Array<String>, ledgerPath: String, endpoint: String) {
        val candidate = args.getOrNull(1)
        val verdict = args.getOrNull(2)?.uppercase()?.let { runCatching { Verdict.valueOf(it) }.getOrNull() }
        if (candidate == null || verdict == null) {
            println("dùng: verdict <candidate> <ok|fail|skipped> [--note \"...\"]")
            return
        }
        println(
            CarExecCommands.recordVerdict(
                ledgerPath, candidate, verdict, endpoint, args.value("--note") ?: "",
            ),
        )
    }

    /**
     * Quan sát off-car bằng output đã ghi của xe thật.
     *
     * Có mặt để kiểm cả chuỗi quan sát mà không cần xe: cùng parser, cùng reader, chỉ khác transport.
     * Nếu đường này sai thì trên xe cũng sai, và biết trước thì rẻ hơn nhiều.
     */
    private fun observeRecorded() {
        val roots = listOf("docs/refactor-car-execution/fixtures", "../docs/refactor-car-execution/fixtures")
        val root = roots.map(::File).firstOrNull { it.isDirectory }
        if (root == null) {
            println("""{"error":"không thấy thư mục fixture"}""")
            return
        }
        val device = RecordedDevice.fromFixtures { name ->
            File(root, name).takeIf { it.isFile }?.readText()
        }
        val reader = ObservedStateReader(device, DumpObservedStateParser.withFallback { null })
        val observation = reader.read()
        val ok = observation is ObservationValue.Known
        println(
            """{"capability":"observe","source":"recorded-fixtures","ok":$ok,""" +
                """"observation":"${observation.toString().replace("\"", "'")}"}""",
        )
    }

    private fun observe(host: String, port: Int, keyDir: String) {
        val keys = loadKeys(keyDir) ?: return
        // Ngoài thiết bị không có DisplayManager, nên bản đo dự phòng trả null: runner chỉ dựa vào dumpsys.
        val gateway = CastAdbGateway(keys = { keys }, measureCluster = { null }, host = host, port = port)
        val reader = ObservedStateReader(gateway, DumpObservedStateParser.withFallback { null })
        val started = System.currentTimeMillis()
        val observation = runCatching { reader.read() }.getOrElse {
            println("""{"capability":"observe","ok":false,"error":"${it.javaClass.simpleName}: ${it.message}"}""")
            return
        }
        val elapsed = System.currentTimeMillis() - started
        val ok = observation is ObservationValue.Known
        println(
            """{"capability":"observe","ok":$ok,"elapsedMs":$elapsed,"endpoint":"$host:$port",""" +
                """"observation":"${observation.toString().replace("\"", "'")}"}""",
        )
        runCatching { gateway.close() }
    }

    private fun loadKeys(keyDir: String): AdbKeyPair? {
        val priv = File(keyDir, "adbkey")
        val pub = File(keyDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) {
            println("""{"error":"không thấy cặp khoá adb tại $keyDir (cần adbkey và adbkey.pub)"}""")
            return null
        }
        return runCatching { AdbKeyPair.read(priv, pub) }.getOrElse {
            println("""{"error":"đọc khoá adb thất bại: ${it.message}"}""")
            null
        }
    }

    private fun Array<String>.value(flag: String): String? {
        val index = indexOf(flag)
        return if (index >= 0 && index + 1 < size) this[index + 1] else null
    }

    private fun printUsage() {
        println(
            """
            car-exec runner — đánh giá capability trên xe, không cần APK

              steps                                   liệt kê step và candidate
              run <candidate> [--pkg P] [--comp C] [--display D] [--task T] [--svc S]
              verdict <candidate> <ok|fail|skipped> [--note "..."]
              scenarios                               các kịch bản E2E và cái nào chạy được
              scenario <id>                           chi tiết từng bước và state mong đợi
              plan <id>                               in chuỗi lệnh sẽ gửi, KHÔNG chạy — duyệt trước khi lên xe
              scenario <id> --run [--from N]           chạy tuần tự, dừng ở mốc cần nhìn cụm
              e2e                                     chuỗi E2E ghép từ các step đã OK
              observe [--host H] [--port P] [--keys DIR]
              observe --recorded                      quan sát off-car trên fixture đã ghi
              run <candidate> --dry-run               in lệnh sẽ gửi, không gửi

            Mặc định: host=localhost port=$DEFAULT_PORT keys=~/.android
            Từ laptop phải truyền --host là IP của head unit.
            """.trimIndent(),
        )
    }
}
