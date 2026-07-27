package com.byd.clusternav.carexec

import java.nio.file.Files
import java.nio.file.Paths
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Hợp đồng giữa vỏ shell và CLI.
 *
 * Vì sao cần: tôi thêm lệnh `plan` vào CLI mà quên whitelist trong vỏ, và để hai chế độ off-car
 * (`--recorded`, `--dry-run`) bị chặn vì thiếu CAR_HOST. Cả ba chỉ lộ ra khi **chạy thử thật** — nên
 * biến việc chạy thử đó thành test.
 */
class WrapperContractTest {

    private fun read(vararg candidates: String): String =
        candidates.map(Paths::get).first(Files::exists).toFile().readText()

    private val wrapper by lazy { read("scripts/vehicle/carexec.sh", "../scripts/vehicle/carexec.sh") }
    private val cli by lazy {
        read(
            "car-integration/src/main/kotlin/com/byd/clusternav/carexec/CarExecCli.kt",
            "src/main/kotlin/com/byd/clusternav/carexec/CarExecCli.kt",
            "../car-integration/src/main/kotlin/com/byd/clusternav/carexec/CarExecCli.kt",
        )
    }

    @Test
    fun `moi lenh cua CLI deu duoc vo cho phep`() {
        val commands = Regex(""""([a-z0-9]+)" ->""").findAll(cli).map { it.groupValues[1] }.toSet() - "observe"
        (commands + "observe").forEach { command ->
            assertTrue(wrapper.contains(command), "vỏ shell chưa biết lệnh '$command' của CLI")
        }
    }

    @Test
    fun `che do off-car khong bi doi CAR_HOST`() {
        listOf("--recorded", "--dry-run").forEach { flag ->
            assertTrue(wrapper.contains(flag), "vỏ shell phải cho '$flag' chạy mà không cần CAR_HOST")
        }
    }

    @Test
    fun `vo shell khong chua logic thiet bi`() {
        // Ranh giới đã tuyên bố: vỏ chỉ lo tham số, evidence, in ấn. Lệnh gửi xuống xe do catalog khai báo.
        listOf("am start --display", "am task resize", "service call", "wm density", "appops set").forEach {
            assertTrue(!wrapper.contains(it), "vỏ shell không được chứa lệnh thiết bị: $it")
        }
    }

    @Test
    fun `phieu chay tren xe phai nhac moi lenh va che do`() {
        // Tài liệu lệch với công cụ là cách chắc chắn để người vận hành không dùng được tính năng vừa làm.
        val sheet = read("docs/refactor-car-execution/run-on-car.md", "../docs/refactor-car-execution/run-on-car.md")
        listOf("--recorded", "--dry-run", "plan", "capture-state", "observable-hunt", "--from").forEach {
            assertTrue(sheet.contains(it), "phiếu chạy chưa nhắc '$it'")
        }
    }

    @Test
    fun `moi lan chay deu de lai bang chung`() {
        assertTrue(wrapper.contains("tee"), "phải lưu output ra file evidence")
        assertTrue(wrapper.contains("EVIDENCE_DIR"), "phải có thư mục evidence")
    }
}
