package com.byd.clusternav.carexec

/**
 * Một hành động trong kịch bản: chạy step nào, với ý gì, và **state nào phải đúng sau đó**.
 *
 * [expect] là phần quan trọng nhất. Một chuỗi lệnh chạy không lỗi vẫn có thể sai hoàn toàn — sáng
 * 2026-07-27 mọi lệnh đều trả về thành công trong khi cụm hiện đồng hồ. Nên mỗi bước phải nói rõ điều gì
 * phải đúng, và ai kiểm được điều đó.
 */
data class ScenarioAction(
    val stepId: String,
    val intent: String,
    val expect: String,
    val checkedBy: VerdictSource,
    /**
     * Giá trị riêng của hành động này, đè lên giá trị chung của phiên.
     *
     * Cần thiết vì một step lặp lại nhiều lần với ý khác nhau: `adjust-geometry` xuất hiện bốn lần trong
     * `cast.geometry-persist` cho bốn cạnh. Không có trường này thì bốn bước gửi cùng một lệnh và kịch
     * bản chỉ trông như đang kiểm bốn cạnh.
     */
    val values: Map<String, String> = emptyMap(),
)

/**
 * Kịch bản E2E có tên.
 *
 * [coveredCases] trỏ tới số thứ tự ca trong bảng 32 ca của docs/specs/cluster-cast-rebaseline.html. Có
 * trường này để "đủ hay chưa" là câu hỏi kiểm được bằng test, chứ không phải cảm giác của tôi.
 */
data class CarScenario(
    val id: String,
    val feature: CarFeature,
    val purpose: String,
    val coveredCases: List<Int>,
    val actions: List<ScenarioAction>,
) {
    val stepIds: List<String> get() = actions.map { it.stepId }.distinct()
}

data class ScenarioReadiness(
    val scenario: CarScenario,
    val readySteps: List<String>,
    val blockedSteps: List<String>,
) {
    val runnable: Boolean get() = blockedSteps.isEmpty()
}

/**
 * Kịch bản E2E, phủ đủ 32 ca canonical.
 *
 * Nguyên tắc ráp: **từng step OK trước, rồi mới ghép**. Kịch bản còn step chưa chứng minh thì không được
 * coi là chạy được — nếu cho chạy, lỗi ở bước ba sẽ bị hiểu là lỗi cả chuỗi và ta lại mất buổi truy nguyên
 * đúng thứ đã biết là chưa xong.
 */
object CarExecScenarios {

    private fun m(step: String, intent: String, expect: String, values: Map<String, String> = emptyMap()) =
        ScenarioAction(step, intent, expect, VerdictSource.MEASURED, values)

    private fun h(step: String, intent: String, expect: String, values: Map<String, String> = emptyMap()) =
        ScenarioAction(step, intent, expect, VerdictSource.HUMAN, values)

    private fun edges(left: Int, top: Int, right: Int, bottom: Int) = mapOf(
        CarExecCatalog.PLACEHOLDER_LEFT to "$left",
        CarExecCatalog.PLACEHOLDER_TOP to "$top",
        CarExecCatalog.PLACEHOLDER_RIGHT to "$right",
        CarExecCatalog.PLACEHOLDER_BOTTOM to "$bottom",
    )

    val all: List<CarScenario> = listOf(
        CarScenario(
            id = "cast.cold-first",
            feature = CarFeature.CLUSTER_CAST,
            purpose = "Lần chiếu đầu từ đồng hồ, app thường",
            coveredCases = listOf(1, 28, 29),
            actions = listOf(
                m("probe-profile", "nhận diện đời máy", "biết svcName và thông số cụm"),
                m("probe-target", "kiểm app đích", "đã cài, có launcher, biết cờ resizeable"),
                m("bootstrap-cold", "tạo/đánh thức display cụm", "display cụm 1920x720 tồn tại, state ON"),
                m("place", "đặt app lên cụm", "occupant đúng app, bounds đúng khung"),
                h("open-projection", "mở đường chiếu", "CỤM hiện app"),
            ),
        ),
        CarScenario(
            id = "cast.recast-same",
            feature = CarFeature.CLUSTER_CAST,
            purpose = "Chiếu lại đúng app đang chiếu",
            coveredCases = listOf(4),
            actions = listOf(
                m("observe", "xác định occupant hiện tại", "biết app nào đang trên cụm"),
                m("place", "chiếu lại chính app đó", "không sinh task thứ hai, không nhấp nháy"),
                h("open-projection", "giữ chiếu", "CỤM vẫn hiện app, không tắt giữa lúc thao tác"),
            ),
        ),
        CarScenario(
            id = "cast.rotate-a-b-c-a",
            feature = CarFeature.CLUSTER_CAST,
            purpose = "Chiếu A, về màn chính, chiếu lại A, rồi B, C, về A",
            coveredCases = listOf(6, 31),
            actions = listOf(
                m("observe", "mốc đầu phiên", "biết rõ cụm đang thế nào"),
                m("place", "chiếu A", "occupant = A"),
                h("open-projection", "mở chiếu", "CỤM hiện A"),
                m("return-protected", "trả A về màn chính", "cụm rỗng, A còn sống, pid không đổi"),
                h("teardown", "đóng chiếu", "CỤM về đồng hồ"),
                m("place", "chiếu lại A", "occupant = A, lần hai giống lần đầu"),
                m("switch", "đổi sang B", "occupant = B, A còn sống, chiếu không tắt"),
                m("switch", "đổi sang C", "occupant = C, B còn sống"),
                m("switch", "đổi lại A", "occupant = A, ba lần đổi không sinh orphan"),
                m("orphan-inspect", "soát mồ côi", "không stack nào trỏ tiến trình đã chết"),
                h("teardown", "kết phiên", "CỤM về đồng hồ"),
            ),
        ),
        CarScenario(
            id = "cast.protected-matrix",
            feature = CarFeature.CLUSTER_CAST,
            purpose = "Sáu chiều đổi giữa app thường, CarPlay và Android Auto",
            coveredCases = listOf(2, 3, 5, 7, 8, 9, 10, 11, 12),
            actions = listOf(
                m("observe", "xác định sink đang kết nối", "biết CP hay AA đang giữ phiên"),
                h("resume-protected", "đưa phiên được bảo vệ lên cụm", "phiên điện thoại KHÔNG bị ngắt, pid không đổi"),
                m("switch", "đổi sang app thường", "occupant đổi, phiên điện thoại vẫn sống"),
                h("resume-protected", "đổi ngược lại phiên được bảo vệ", "vẫn không phải tắt app của người ta"),
                m("orphan-inspect", "soát sau chuỗi đổi", "không mồ côi"),
            ),
        ),
        CarScenario(
            id = "cast.landing-faults",
            feature = CarFeature.CLUSTER_CAST,
            purpose = "App không bám cụm, hoặc bám rồi bật ra",
            coveredCases = listOf(13, 14, 15),
            actions = listOf(
                m("probe-target", "xác định cờ resizeable", "biết trước sẽ cần candidate nào"),
                m("place", "thử đặt lên cụm", "hoặc bám đúng khung, hoặc thất bại có lý do đọc được"),
                m("observe", "đọc lại sau 2 giây", "occupant giữ nguyên chứ không bật ra"),
                m("return-protected", "trả về màn chính kể cả khi app cưỡng lại", "cụm rỗng, app còn sống"),
            ),
        ),
        CarScenario(
            id = "cast.sink-disconnect",
            feature = CarFeature.CLUSTER_CAST,
            purpose = "Rút cáp hoặc mất phiên điện thoại giữa lúc chiếu",
            coveredCases = listOf(16),
            actions = listOf(
                m("observe", "mốc trước khi rút", "biết owner và trạng thái phiên"),
                h("observe", "rút cáp rồi đọc lại", "hệ thống nhận ra mất phiên chứ không giữ trạng thái cũ"),
                m("orphan-inspect", "soát mồ côi sau khi mất phiên", "không stack trỏ tiến trình đã chết"),
            ),
        ),
        CarScenario(
            id = "cast.stop-paths",
            feature = CarFeature.CLUSTER_CAST,
            purpose = "Dừng bình thường, và dừng khi thao tác đang treo",
            coveredCases = listOf(17, 18),
            actions = listOf(
                m("observe", "mốc trước Stop", "biết ai đang chiếm"),
                h("teardown", "dừng bình thường", "CỤM về đồng hồ, app còn sống ở màn giữa"),
                m("restore", "trả globals về mốc", "bốn global khớp mốc đã journal"),
                h("teardown", "dừng khi thao tác trước chưa xong", "vẫn về được đồng hồ, không cần reboot"),
            ),
        ),
        CarScenario(
            id = "cast.target-process-death",
            feature = CarFeature.CLUSTER_CAST,
            purpose = "App đích chết hoặc tự khởi động lại giữa lúc chiếu",
            coveredCases = listOf(19),
            actions = listOf(
                m("place", "đặt app lên cụm", "occupant đúng"),
                h("open-projection", "mở chiếu", "CỤM hiện app"),
                m("target-process", "giết app đích có chủ ý", "app chết"),
                m("observe", "đọc lại ngay sau đó", "trạng thái xác định, không treo, không mồ côi kéo dài"),
                h("teardown", "dọn", "CỤM về đồng hồ"),
            ),
        ),
        CarScenario(
            id = "cast.sleep-wake",
            feature = CarFeature.CLUSTER_CAST,
            purpose = "Head unit ngủ rồi thức khi đang chiếu",
            coveredCases = listOf(20),
            actions = listOf(
                m("place", "đặt app lên cụm", "occupant đúng"),
                h("power-state", "ngủ rồi thức", "sau khi thức, cụm ở trạng thái xác định chứ không nửa vời"),
                m("observe", "đọc lại", "display cụm và occupant khớp thực tế"),
            ),
        ),
        CarScenario(
            id = "cast.orphan-heal",
            feature = CarFeature.CLUSTER_CAST,
            purpose = "Mồ côi có trước, và mồ côi sinh ra trong lúc chiếu",
            coveredCases = listOf(21, 22),
            actions = listOf(
                m("orphan-inspect", "soát trước khi làm gì", "biết có mồ côi sẵn hay không"),
                m("place", "chiếu lên cụm đang có mồ côi", "hoặc bám được, hoặc bị chặn có lý do"),
                m("orphan-inspect", "soát lại", "biết mồ côi mới sinh ra hay không"),
                h("teardown", "dọn bằng đường không phá", "CỤM về đồng hồ mà không cần giết app"),
            ),
        ),
        CarScenario(
            id = "cast.geometry-persist",
            feature = CarFeature.CLUSTER_CAST,
            purpose = "Chỉnh từng cạnh và DPI, kiểm render, rồi kiểm giữ thiết lập sau khi cast lại",
            coveredCases = listOf(23),
            actions = listOf(
                m("place", "chiếu A", "occupant = A"),
                h("open-projection", "mở chiếu", "CỤM hiện A"),
                h("adjust-geometry", "co cạnh trên 40px", "bounds đúng [0,40,1920,720], nội dung không bị cắt", edges(0, 40, 1920, 720)),
                h("adjust-geometry", "co cạnh dưới 40px", "bounds đúng [0,40,1920,680], render vẫn đủ", edges(0, 40, 1920, 680)),
                h("adjust-geometry", "co cạnh trái 30px", "bounds đúng [30,40,1920,680], render vẫn đủ", edges(30, 40, 1920, 680)),
                h("adjust-geometry", "co cạnh phải 30px", "bounds đúng [30,40,1890,680], render vẫn đủ", edges(30, 40, 1890, 680)),
                h("adjust-dpi", "đổi density xuống 280", "chữ và icon đổi cỡ, layout không cắt", mapOf(CarExecCatalog.PLACEHOLDER_DPI to "280")),
                m("return-protected", "trả A về màn chính", "cụm rỗng, A còn sống"),
                m("place", "chiếu lại A", "occupant = A"),
                m("observe", "kiểm giữ thiết lập", "bounds phải là [30,40,1890,680] VÀ density phải là 280 — đúng giá trị đã chỉnh trước khi về màn chính"),
                h("teardown", "kết phiên", "CỤM về đồng hồ"),
            ),
        ),
        CarScenario(
            id = "cast.geometry-stale",
            feature = CarFeature.CLUSTER_CAST,
            purpose = "Chỉnh khung khi target trong state đã cũ",
            coveredCases = listOf(24),
            actions = listOf(
                m("place", "chiếu A", "occupant = A"),
                m("target-process", "làm cho state cũ đi bằng cách giết app", "app chết, state trong journal thành cũ"),
                m("adjust-geometry", "thử chỉnh khung", "bị chặn có lý do, KHÔNG áp lên task của app khác"),
            ),
        ),
        CarScenario(
            id = "cast.pip-coexist",
            feature = CarFeature.CLUSTER_CAST,
            purpose = "App có PIP, trước và sau khi chiếu",
            coveredCases = listOf(25),
            actions = listOf(
                m("pip-guard", "chặn PIP trước khi chiếu", "app-op đã đổi và được journal"),
                m("place", "chiếu app", "app không nhảy PIP khi bị reparent"),
                m("pip-guard", "trả app-op về mốc", "appops đọc lại đúng mốc"),
            ),
        ),
        CarScenario(
            id = "cast.target-missing",
            feature = CarFeature.CLUSTER_CAST,
            purpose = "App chưa cài, hoặc không có activity khởi chạy",
            coveredCases = listOf(26),
            actions = listOf(
                m("probe-target", "kiểm app đích", "biết chắc thiếu gì"),
                m("place", "thử đặt lên cụm", "thất bại có lý do đọc được, không treo, không để lại mồ côi"),
            ),
        ),
        CarScenario(
            id = "cast.transport-fault",
            feature = CarFeature.CLUSTER_CAST,
            purpose = "adb/dadb chết hoặc không nối được",
            coveredCases = listOf(27),
            actions = listOf(
                m("observe", "gọi vào endpoint sai", "fail trong thời gian có biên kèm lý do, không treo"),
                m("observe", "gọi lại vào endpoint đúng", "đọc được trạng thái, không cần khởi động lại gì"),
            ),
        ),
        CarScenario(
            id = "cast.display-missing",
            feature = CarFeature.CLUSTER_CAST,
            purpose = "Display cụm thiếu hoặc lên chậm",
            coveredCases = listOf(28),
            actions = listOf(
                m("observe", "đọc khi chưa có display cụm", "nói rõ không tìm thấy chứ không đoán"),
                m("bootstrap-cold", "đánh thức display cụm", "display xuất hiện, hoặc thất bại có lý do"),
            ),
        ),
        CarScenario(
            id = "cast.boot-automation",
            feature = CarFeature.CLUSTER_CAST,
            purpose = "Tự động chiếu sau khi khởi động máy",
            coveredCases = listOf(30),
            actions = listOf(
                m("observe", "mốc ngay sau khi máy lên", "biết cụm đang thế nào trước khi tự động làm gì"),
                m("place", "tự động chiếu app mặc định", "hoặc chiếu đúng app, hoặc không làm gì cả — không có trạng thái nửa vời"),
                h("open-projection", "mở chiếu", "CỤM hiện app mặc định"),
            ),
        ),
        CarScenario(
            id = "cast.animation-guard",
            feature = CarFeature.CLUSTER_CAST,
            purpose = "Reparent giữa lúc transition — nguồn gây NPE của V1",
            coveredCases = listOf(14, 19),
            actions = listOf(
                m("animation-quiesce", "tắt animation", "ba global bằng 0"),
                m("place", "đặt app lên cụm", "bám được, không NPE trong logcat"),
                m("animation-quiesce", "trả animation về mốc", "ba global khớp mốc 0.5/0.5/1.0"),
            ),
        ),
        CarScenario(
            id = "cast.style-toggle",
            feature = CarFeature.CLUSTER_CAST,
            purpose = "Đổi kiểu cong ↔ phẳng khi đang chiếu và sau Stop",
            coveredCases = listOf(23),
            actions = listOf(
                m("place", "chiếu A", "occupant = A"),
                h("open-projection", "mở chiếu", "CỤM hiện A"),
                h("set-style", "sang kiểu phẳng", "CỤM đổi hình, app vẫn hiện"),
                h("set-style", "về kiểu cong", "CỤM đổi lại, vẫn thấy km/h"),
                h("teardown", "đóng chiếu", "CỤM về đồng hồ ở kiểu đã journal"),
            ),
        ),
        CarScenario(
            id = "cast.observable-hunt",
            feature = CarFeature.CLUSTER_CAST,
            purpose = "Trả lời Q1: có tín hiệu đọc-được nào phân biệt cụm-hiện-app với cụm-hiện-đồng-hồ",
            coveredCases = listOf(1),
            actions = listOf(
                m("capture-state", "mốc lúc cụm hiện đồng hồ", "dump đầy đủ, ghi lại numLayers của display cụm"),
                m("place", "đặt app lên cụm mà CHƯA mở chiếu", "task ở display cụm"),
                m("capture-state", "mốc có task nhưng chưa mở chiếu", "nếu tín hiệu vẫn như lúc đồng hồ thì tiêu chí cũ sai — đã đo 27/7: numLayers=2 mà cụm vẫn đồng hồ"),
                h("open-projection", "mở chiếu", "CỤM hiện app"),
                m("capture-state", "mốc lúc chiếu đang mở", "so ba mốc: trường nào đổi ĐÚNG lúc cụm đổi thì đó là observable cần tìm"),
                h("teardown", "đóng chiếu", "CỤM về đồng hồ"),
                m("capture-state", "mốc sau khi đóng", "trường đó phải trở lại giá trị lúc đầu — nếu không thì loại"),
            ),
        ),
        CarScenario(
            id = "nav.cluster-and-hud",
            feature = CarFeature.NAVIGATION,
            purpose = "Dẫn đường ra làn cụm và HUD từ một nguồn duy nhất",
            coveredCases = listOf(32),
            actions = listOf(
                m("nav-listener", "cấp và xác nhận quyền", "listener có trong settings secure"),
                m("nav-source", "app dẫn đường phát dữ liệu", "đọc được notification chỉ đường"),
                h("nav-cluster-lane", "làn cụm hiện hướng rẽ", "CỤM hiện mũi tên và khoảng cách khớp app"),
            ),
        ),
        CarScenario(
            id = "both.pipeline-independence",
            feature = CarFeature.NAVIGATION,
            purpose = "Hai đường độc lập: Cast không làm chết Navigation và ngược lại",
            coveredCases = listOf(32),
            actions = listOf(
                m("nav-listener", "xác nhận nav đang chạy", "listener còn quyền"),
                m("place", "chiếu một app lên cụm", "occupant đúng"),
                m("nav-source", "kiểm nav còn phát", "dữ liệu chỉ đường không bị ngắt vì Cast"),
                h("teardown", "dừng Cast", "CỤM về đồng hồ, nav vẫn phát bình thường"),
            ),
        ),
    )

    fun scenario(id: String): CarScenario? = all.firstOrNull { it.id == id }

    /** Kịch bản chỉ chạy được khi mọi step nó dùng đều đã có candidate OK trong ledger. */
    fun readiness(scenario: CarScenario, ledger: VerdictLedger): ScenarioReadiness {
        val ready = ArrayList<String>()
        val blocked = ArrayList<String>()
        scenario.stepIds.forEach { stepId ->
            if (ledger.okCandidateFor(stepId) != null) ready += stepId else blocked += stepId
        }
        return ScenarioReadiness(scenario, ready, blocked)
    }

    /** Ca canonical nào chưa có kịch bản nào phủ. Dùng cho test "đủ hay chưa". */
    fun uncoveredCases(total: Int = 32): List<Int> {
        val covered = all.flatMap { it.coveredCases }.toSet()
        return (1..total).filterNot { it in covered }
    }
}
