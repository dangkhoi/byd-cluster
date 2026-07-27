package com.byd.clusternav.carexec

/** Tính năng của xe mà ta đang chứng minh khả thi. */
enum class CarFeature {
    CLUSTER_CAST,
    NAVIGATION,

    /**
     * Biển báo giới hạn tốc độ.
     *
     * Xe đọc biển bằng camera rồi đẩy lên cụm và HUD. Biển ở Việt Nam rối (biển phụ, biển hết hạn chế,
     * biển của làn khác, biển trong ngoặc theo giờ), camera đọc sai nên cảnh báo sai. Hướng đang đánh giá:
     * lấy giới hạn tốc độ từ VietMap rồi đưa vào đúng đường mà cụm/HUD đang nghe, và có thể tắt đường đọc
     * camera để hai nguồn không tranh nhau.
     *
     * Chưa biết gì về ba mắt xích: ai sinh giá trị, nó đi qua đường nào, ai vẽ nó. Vì thế các step của
     * tính năng này phần lớn là KHÁM PHÁ, không phải thao tác.
     */
    SPEED_SIGN,
}

/**
 * Mức rủi ro khi chạy một candidate. Có mặt vì không phải lệnh nào cũng chỉ đọc, và một số lệnh có thể
 * làm treo head unit trong lúc xe đang chạy — người bấm phải được cảnh báo TRƯỚC khi gửi, không phải sau.
 */
enum class CandidateRisk {
    /** Chỉ đọc. Không đổi gì. */
    READ_ONLY,

    /** Đổi trạng thái nhưng phục hồi được bằng một lệnh đã biết. */
    REVERSIBLE,

    /** Có thể làm gián đoạn thứ tài xế đang dùng: cụm, HUD, ADAS. Chỉ chạy khi đã dặn trước. */
    MAY_DISRUPT_DRIVER,

    /** Có bằng chứng hoặc lý do vững rằng có thể treo/khởi động lại hệ thống. Chỉ chạy khi đỗ. */
    MAY_HANG_SYSTEM,
}

/**
 * Ai kết luận một candidate là đạt.
 *
 * [MEASURED] — máy tự kết luận từ output đọc được, nên chạy lại được vô số lần và không cần người.
 * [HUMAN] — phải có người nhìn cụm rồi nói. Đây không phải chỗ để né việc: hiện chưa có observable nào
 * phân biệt "cụm đang hiện app" với "cụm đang hiện đồng hồ" (Q1 trong spec), nên mở/đóng chiếu buộc phải
 * do mắt người kết luận. Ledger ghi rõ nguồn verdict để sau này không ai nhầm mắt người thành số đo.
 */
enum class VerdictSource { MEASURED, HUMAN }

/**
 * Một cách làm cụ thể cho một step. Nhiều candidate cho cùng một step là chuyện bình thường: đời máy khác
 * nhau, app resizeable hay không, chiếu đang mở hay đóng — mỗi hoàn cảnh có thể cần cách khác.
 */
data class StepCandidate(
    val id: String,
    val purpose: String,
    /** Lệnh gửi vào shell của head unit, theo đúng thứ tự. Placeholder dạng {pkg}, {comp}, {display}, {taskId}, {svc}. */
    val commands: List<String>,
    /** Điều gì chứng minh candidate này đạt. Với HUMAN thì đây là câu hỏi đặt cho người nhìn. */
    val evidence: String,
    val verdictSource: VerdictSource,
    /**
     * Bắt buộc khai, KHÔNG có giá trị mặc định.
     *
     * Bản đầu tôi cho mặc định READ_ONLY và lập tức dán nhãn sai cho toàn bộ candidate cũ, kể cả những
     * candidate có `am start` và `settings put`. Mặc định ở đây là một lời nói dối im lặng: nó không làm
     * test đổ, chỉ làm người vận hành tin sai rồi gửi lệnh đổi trạng thái trong lúc đang lái.
     */
    val risk: CandidateRisk,
    /** Ghi chú field: đã chứng minh ở đâu, khi nào, trên đời máy nào. */
    val fieldNote: String? = null,
)

/** Một bước trong tính năng. Step là đơn vị được đánh cờ OK/FAIL. */
data class CarStep(
    val id: String,
    val feature: CarFeature,
    val purpose: String,
    val precondition: String,
    val candidates: List<StepCandidate>,
) {
    init {
        require(candidates.isNotEmpty()) { "step $id phải có ít nhất một candidate" }
    }
}

/**
 * Catalog các step và candidate để đánh giá trên xe.
 *
 * Đây là **khai báo**, không phải thực thi: cùng một danh sách được runner dùng để chạy trên xe và được
 * test dùng để kiểm tính nhất quán off-car. Trước đây các recipe này nằm trong `thư mục scripts/vehicle`,
 * tức một bản hiện thực thứ hai song song với Kotlin — ngày 2026-07-27 đã cho thấy tác hại: hai nơi giữ
 * cùng một logic và không ai biết bản nào đúng.
 */
object CarExecCatalog {

    const val PLACEHOLDER_PACKAGE = "{pkg}"
    const val PLACEHOLDER_COMPONENT = "{comp}"
    const val PLACEHOLDER_DISPLAY = "{display}"
    const val PLACEHOLDER_TASK = "{taskId}"
    const val PLACEHOLDER_SERVICE = "{svc}"

    const val PLACEHOLDER_LEFT = "{left}"
    const val PLACEHOLDER_TOP = "{top}"
    const val PLACEHOLDER_RIGHT = "{right}"
    const val PLACEHOLDER_BOTTOM = "{bottom}"
    const val PLACEHOLDER_DPI = "{dpi}"

    /** Giá trị cần ghi, ví dụ giới hạn tốc độ km/h. */
    const val PLACEHOLDER_VALUE = "{value}"

    /** Khoá/hằng do khám phá tìm ra: tên setting, action broadcast, mã transaction. */
    const val PLACEHOLDER_KEY = "{key}"

    val placeholders = setOf(
        PLACEHOLDER_PACKAGE, PLACEHOLDER_COMPONENT, PLACEHOLDER_DISPLAY,
        PLACEHOLDER_TASK, PLACEHOLDER_SERVICE,
        PLACEHOLDER_LEFT, PLACEHOLDER_TOP, PLACEHOLDER_RIGHT, PLACEHOLDER_BOTTOM, PLACEHOLDER_DPI,
        PLACEHOLDER_VALUE, PLACEHOLDER_KEY,
    )

    val steps: List<CarStep> = listOf(
        CarStep(
            id = "observe",
            feature = CarFeature.CLUSTER_CAST,
            purpose = "Đọc trạng thái cụm: display nào là cụm, ai đang chiếm, geometry, globals",
            precondition = "không có",
            candidates = listOf(
                StepCandidate(
                    id = "observe.dumpsys",
                    purpose = "Đọc qua am stack list + dumpsys display + settings",
                    commands = listOf("am stack list", "dumpsys display", "dumpsys SurfaceFlinger --list"),
                    evidence = "parse ra đúng display cụm 1920x720 và danh sách occupant",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.READ_ONLY,
                    fieldNote = "Đã chạy được qua runner 2026-07-27, 2.315 ms trên thiết bị thật",
                ),
            ),
        ),
        CarStep(
            id = "place",
            feature = CarFeature.CLUSTER_CAST,
            purpose = "Đưa task của app lên display của cụm",
            precondition = "biết display cụm; app đã cài",
            candidates = listOf(
                StepCandidate(
                    id = "place.freeform-then-resize",
                    purpose = "App resizeable: mở freeform rồi kéo full khung cụm",
                    commands = listOf(
                        "am start --display {display} --windowingMode 5 -n {comp}",
                        "am task resize {taskId} 0 0 1920 720",
                    ),
                    evidence = "xuất hiện Stack ... displayId={display} với taskId của {pkg}, bounds [0,0][1920,720]",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.REVERSIBLE,
                    fieldNote = "vn.vietmap.live: lands 440x720 rồi resize đầy khung (2026-07-26)",
                ),
                StepCandidate(
                    id = "place.freeform-only",
                    purpose = "App khai unresizeable: chỉ cần freeform khi force_resizable_activities=1",
                    commands = listOf("am start --display {display} --windowingMode 5 -n {comp}"),
                    evidence = "task lên đúng 1920x720 ngay, không cần resize",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.REVERSIBLE,
                    fieldNote = "com.byd.auto_photo landed 1920x720; am task resize bị từ chối nhưng không cần (2026-07-26)",
                ),
                StepCandidate(
                    id = "place.movestack",
                    purpose = "Đường leo thang khi hai cách trên không bám",
                    commands = listOf("am display move-stack {taskId} {display}"),
                    evidence = "task chuyển sang display cụm mà không tạo orphan",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.READ_ONLY,
                    fieldNote = "Chưa cần dùng lần nào trong bốn ca đã chứng minh; giữ làm escalation",
                ),
            ),
        ),
        CarStep(
            id = "open-projection",
            feature = CarFeature.CLUSTER_CAST,
            purpose = "Bảo OEM route bề mặt cụm ra màn hình vật lý",
            precondition = "task đã ở trên display cụm",
            candidates = listOf(
                StepCandidate(
                    id = "open.seal-30-16-35",
                    purpose = "Seal DL3: giữ kiểu cong, chiếu, DI40",
                    commands = listOf(
                        "service call {svc} 2 i32 1000 i32 30 s16 \"\"",
                        "service call {svc} 2 i32 1000 i32 16 s16 \"\"",
                        "service call {svc} 2 i32 1000 i32 35 s16 \"\"",
                    ),
                    evidence = "CỤM VẬT LÝ hiện app — cần người nhìn, chưa có cách đo",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.MAY_DISRUPT_DRIVER,
                    fieldNote = "Gõ tay 2026-07-27 09:58: cả ba trả Parcel(0,0), owner xác nhận 'lên rồi'",
                ),
                StepCandidate(
                    id = "open.seal-16-only",
                    purpose = "Hình dạng DiLink5: chỉ opcode chiếu, không có opcode kiểu",
                    commands = listOf("service call {svc} 2 i32 1000 i32 16 s16 \"\""),
                    evidence = "cụm hiện app trên đời máy không hỗ trợ đổi kiểu",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.MAY_DISRUPT_DRIVER,
                    fieldNote = "Chưa thử; suy từ ClusterProfile DiLink5 castSeq=[16]",
                ),
            ),
        ),
        CarStep(
            id = "teardown",
            feature = CarFeature.CLUSTER_CAST,
            purpose = "Đóng đường chiếu, trả cụm về đồng hồ gốc",
            precondition = "đường chiếu đang mở",
            candidates = listOf(
                StepCandidate(
                    id = "teardown.18-then-0",
                    purpose = "Đóng chiếu rồi refresh video",
                    commands = listOf(
                        "service call {svc} 2 i32 1000 i32 18 s16 \"\"",
                        "service call {svc} 2 i32 1000 i32 0 s16 \"\"",
                    ),
                    evidence = "cụm hiện lại đồng hồ gốc, không cần reboot, không force-stop app",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.MAY_DISRUPT_DRIVER,
                    fieldNote = "Gõ tay 2026-07-26 tối: owner xác nhận 'về rồi' (kiểu cong)",
                ),
                StepCandidate(
                    id = "teardown.0-only",
                    purpose = "Chỉ refresh video, xem có đủ để trả đồng hồ không",
                    commands = listOf("service call {svc} 2 i32 1000 i32 0 s16 \"\""),
                    evidence = "cụm về đồng hồ mà không cần opcode 18",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.MAY_DISRUPT_DRIVER,
                    fieldNote = "Chưa thử — để biết opcode nào thật sự cần",
                ),
            ),
        ),
        CarStep(
            id = "restore",
            feature = CarFeature.CLUSTER_CAST,
            purpose = "Trả app về màn giữa và đưa globals về mốc đã ghi",
            precondition = "đã journal mốc globals trước khi đổi",
            candidates = listOf(
                StepCandidate(
                    id = "restore.main-standard",
                    purpose = "Đưa task về display 0 ở chế độ chuẩn",
                    commands = listOf("am start --display 0 --windowingMode 1 -n {comp}"),
                    evidence = "0 stack trên display cụm; app còn sống trên màn giữa",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.REVERSIBLE,
                    fieldNote = "Đã dùng 2026-07-27: VietMap về màn giữa, display 1 rỗng",
                ),
                StepCandidate(
                    id = "restore.globals",
                    purpose = "Đưa bốn global về mốc",
                    commands = listOf(
                        "settings put global force_resizable_activities 1",
                        "settings put global transition_animation_scale 0.5",
                        "settings put global window_animation_scale 0.5",
                        "settings put global animator_duration_scale 1.0",
                    ),
                    evidence = "bốn global khớp mốc đã journal",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.REVERSIBLE,
                    fieldNote = "Mốc đo trên xe: 1 / 0.5 / 0.5 / 1.0",
                ),
            ),
        ),
        CarStep(
            id = "switch",
            feature = CarFeature.CLUSTER_CAST,
            purpose = "Đổi app đang chiếu sang app khác mà không trả cụm về đồng hồ",
            precondition = "đang có một app trên cụm; đường chiếu đang mở",
            candidates = listOf(
                StepCandidate(
                    id = "switch.reparent-warm",
                    purpose = "App đích đã có task fullscreen ở màn giữa: một lệnh là đủ",
                    commands = listOf("am start --display {display} --windowingMode 5 -n {comp}"),
                    evidence = "occupant của cụm đổi sang {pkg}, app cũ còn sống ở màn giữa, chiếu không tắt",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.REVERSIBLE,
                    fieldNote = "Đã chứng minh 2026-07-26: warm reparent bằng một lệnh",
                ),
                StepCandidate(
                    id = "switch.place-then-fit",
                    purpose = "App đích chưa chạy: mở rồi kéo khung",
                    commands = listOf(
                        "am start --display {display} --windowingMode 5 -n {comp}",
                        "am task resize {taskId} 0 0 1920 720",
                    ),
                    evidence = "occupant đổi và bounds đúng khung cụm",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.REVERSIBLE,
                ),
            ),
        ),
        CarStep(
            id = "adjust-geometry",
            feature = CarFeature.CLUSTER_CAST,
            purpose = "Chỉnh từng cạnh khung hiển thị trên cụm",
            precondition = "app đang trên cụm",
            candidates = listOf(
                StepCandidate(
                    id = "geometry.task-resize",
                    purpose = "Đặt bốn cạnh trực tiếp lên task",
                    commands = listOf("am task resize {taskId} {left} {top} {right} {bottom}"),
                    evidence = "bounds đọc lại đúng bằng bốn giá trị vừa đặt; nội dung vẫn render, không méo",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.REVERSIBLE,
                    fieldNote = "Số đo bounds là MEASURED, nhưng 'render có ổn không' thì phải nhìn",
                ),
                StepCandidate(
                    id = "geometry.overscan",
                    purpose = "Đường dự phòng của V1 khi freeform không sống",
                    commands = listOf("wm overscan {left},{top},{right},{bottom} -d {display}"),
                    evidence = "khung co lại đúng và app vẫn vẽ đủ",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.READ_ONLY,
                    fieldNote = "V1 dùng overscan 0,90,0,90 làm fallback",
                ),
            ),
        ),
        CarStep(
            id = "adjust-dpi",
            feature = CarFeature.CLUSTER_CAST,
            purpose = "Đổi density của cụm cho app đang chiếu",
            precondition = "app đang trên cụm",
            candidates = listOf(
                StepCandidate(
                    id = "dpi.wm-density",
                    purpose = "Đặt density riêng cho display cụm",
                    commands = listOf("wm density {dpi} -d {display}"),
                    evidence = "chữ và icon đổi kích thước, layout không bị cắt",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.READ_ONLY,
                    fieldNote = "Mốc đo trên xe: density cụm mặc định 320",
                ),
                StepCandidate(
                    id = "dpi.reset",
                    purpose = "Trả density về mặc định",
                    commands = listOf("wm density reset -d {display}"),
                    evidence = "density trở lại 320 theo dumpsys display",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.READ_ONLY,
                ),
            ),
        ),
        CarStep(
            id = "set-style",
            feature = CarFeature.CLUSTER_CAST,
            purpose = "Đổi kiểu cụm (cong giữ km/h ↔ phẳng)",
            precondition = "đời máy hỗ trợ đổi kiểu (styleOps khác null)",
            candidates = listOf(
                StepCandidate(
                    id = "style.curved-30",
                    purpose = "Kiểu cong, giữ đồng hồ km/h",
                    commands = listOf("service call {svc} 2 i32 1000 i32 30 s16 \"\""),
                    evidence = "cụm hiện kiểu cong và vẫn thấy km/h",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.MAY_DISRUPT_DRIVER,
                    fieldNote = "Owner chấp nhận kiểu cong sau Stop; opcode 30 nằm trong castSeq DL3",
                ),
                StepCandidate(
                    id = "style.flat-31",
                    purpose = "Kiểu phẳng, khung rộng hơn",
                    commands = listOf("service call {svc} 2 i32 1000 i32 31 s16 \"\""),
                    evidence = "cụm đổi sang kiểu phẳng",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.MAY_DISRUPT_DRIVER,
                    fieldNote = "styleOps DL3 = 30 to 31",
                ),
            ),
        ),
        CarStep(
            id = "nav-listener",
            feature = CarFeature.NAVIGATION,
            purpose = "Quyền đọc notification của app dẫn đường",
            precondition = "không có",
            candidates = listOf(
                StepCandidate(
                    id = "nav.listener-allow",
                    purpose = "Cấp quyền notification listener bằng cmd",
                    commands = listOf("cmd notification allow_listener {comp}"),
                    evidence = "settings secure enabled_notification_listeners chứa {comp}",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.READ_ONLY,
                    fieldNote = "NavConnect tự-heal dùng đúng đường này",
                ),
                StepCandidate(
                    id = "nav.listener-read",
                    purpose = "Đọc danh sách listener hiện tại",
                    commands = listOf("settings get secure enabled_notification_listeners"),
                    evidence = "đọc được danh sách, xác nhận có hoặc không có {comp}",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.READ_ONLY,
                ),
            ),
        ),
        CarStep(
            id = "nav-source",
            feature = CarFeature.NAVIGATION,
            purpose = "App dẫn đường có đang phát dữ liệu chỉ đường không",
            precondition = "listener đã được cấp quyền; đang có tuyến",
            candidates = listOf(
                StepCandidate(
                    id = "nav.notification-dump",
                    purpose = "Đọc notification đang hiện của app dẫn đường",
                    commands = listOf("dumpsys notification --noredact"),
                    evidence = "thấy notification của {pkg} kèm nội dung chỉ đường",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.READ_ONLY,
                    fieldNote = "CHƯA kiểm trên xe qua runner — cần xác nhận cờ --noredact có được phép",
                ),
            ),
        ),
        CarStep(
            id = "nav-cluster-lane",
            feature = CarFeature.NAVIGATION,
            purpose = "Làn cụm có hiện hướng rẽ và khoảng cách không",
            precondition = "nav-source đang phát",
            candidates = listOf(
                StepCandidate(
                    id = "nav.cluster-lane-visual",
                    purpose = "Xác nhận bằng mắt trên cụm",
                    commands = listOf("dumpsys display"),
                    evidence = "CỤM hiện mũi tên rẽ và khoảng cách đúng như app dẫn đường",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.READ_ONLY,
                    fieldNote = "Chưa có cách đo output của làn cụm; giống Q1 của Cast",
                ),
            ),
        ),
        CarStep(
            id = "bootstrap-cold",
            feature = CarFeature.CLUSTER_CAST,
            purpose = "Lần chiếu đầu từ đồng hồ: tạo/đánh thức display cụm trước khi đặt app",
            precondition = "cụm đang hiện đồng hồ; chưa có phiên nào",
            candidates = listOf(
                StepCandidate(
                    id = "bootstrap.seal-cold",
                    purpose = "Phát chuỗi seal lạnh rồi đọc lại display",
                    commands = listOf(
                        "service call {svc} 2 i32 1000 i32 30 s16 \"\"",
                        "service call {svc} 2 i32 1000 i32 16 s16 \"\"",
                        "dumpsys display",
                    ),
                    evidence = "display cụm 1920x720 tồn tại và ở trạng thái ON",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.MAY_DISRUPT_DRIVER,
                ),
            ),
        ),
        CarStep(
            id = "probe-target",
            feature = CarFeature.CLUSTER_CAST,
            purpose = "App đích có cài, có launcher, có resizeable hay không",
            precondition = "biết tên package",
            candidates = listOf(
                StepCandidate(
                    id = "target.package-info",
                    purpose = "Đọc thông tin gói và cờ resize",
                    commands = listOf(
                        "pm path {pkg}",
                        "dumpsys package {pkg}",
                        "cmd package resolve-activity --brief {pkg}",
                    ),
                    evidence = "biết chắc: đã cài hay chưa, có activity khởi chạy hay không, có cờ unresizeable hay không",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.READ_ONLY,
                    fieldNote = "Cờ PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_UNRESIZEABLE quyết định chọn candidate place nào",
                ),
            ),
        ),
        CarStep(
            id = "resume-protected",
            feature = CarFeature.CLUSTER_CAST,
            purpose = "Đưa phiên CarPlay/Android Auto lên cụm mà KHÔNG tắt app của người ta",
            precondition = "sink đang kết nối",
            candidates = listOf(
                StepCandidate(
                    id = "protected.resume-no-kill",
                    purpose = "Chỉ reparent task đang sống, không force-stop, không clear task",
                    commands = listOf("am start --display {display} --windowingMode 5 -n {comp}"),
                    evidence = "phiên điện thoại KHÔNG bị ngắt; pid của app đích không đổi",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.MAY_DISRUPT_DRIVER,
                    fieldNote = "V1 tuyệt đối không force-stop CP/AA; đây là ràng buộc sản phẩm",
                ),
            ),
        ),
        CarStep(
            id = "return-protected",
            feature = CarFeature.CLUSTER_CAST,
            purpose = "Trả app về màn giữa một cách nhẹ, kể cả khi nó cưỡng lại",
            precondition = "app đang trên cụm",
            candidates = listOf(
                StepCandidate(
                    id = "return.gentle-main",
                    purpose = "Mở lại ở display 0 chế độ chuẩn",
                    commands = listOf("am start --display 0 --windowingMode 1 -n {comp}"),
                    evidence = "app về màn giữa, pid không đổi, cụm rỗng",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.REVERSIBLE,
                ),
                StepCandidate(
                    id = "return.movestack-main",
                    purpose = "Đường leo thang nếu mở lại không đủ",
                    commands = listOf("am display move-stack {taskId} 0"),
                    evidence = "task về display 0 mà không tạo orphan (đây là lệnh từng gây NPE ở V1)",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.READ_ONLY,
                    fieldNote = "V1: move-stack một task freeform đang hiện từng gây half-reparent",
                ),
            ),
        ),
        CarStep(
            id = "pip-guard",
            feature = CarFeature.CLUSTER_CAST,
            purpose = "Chặn và trả lại chế độ PIP của app đích",
            precondition = "đã journal app-op PIP trước khi đổi",
            candidates = listOf(
                StepCandidate(
                    id = "pip.block",
                    purpose = "Chặn PIP trong lúc chiếu",
                    commands = listOf("appops set {pkg} PICTURE_IN_PICTURE ignore"),
                    evidence = "app không nhảy PIP khi bị reparent",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.REVERSIBLE,
                ),
                StepCandidate(
                    id = "pip.restore",
                    purpose = "Trả app-op về mốc",
                    commands = listOf("appops set {pkg} PICTURE_IN_PICTURE allow"),
                    evidence = "appops đọc lại đúng mốc đã journal",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.REVERSIBLE,
                ),
            ),
        ),
        CarStep(
            id = "animation-quiesce",
            feature = CarFeature.CLUSTER_CAST,
            purpose = "Tắt/khôi phục animation để tránh reparent giữa lúc transition",
            precondition = "đã journal ba global animation",
            candidates = listOf(
                StepCandidate(
                    id = "animation.disable",
                    purpose = "Đưa ba scale về 0 trong lúc thao tác",
                    commands = listOf(
                        "settings put global transition_animation_scale 0",
                        "settings put global window_animation_scale 0",
                        "settings put global animator_duration_scale 0",
                    ),
                    evidence = "ba global bằng 0; reparent không rơi vào lúc đang transition",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.REVERSIBLE,
                    fieldNote = "NPE A của V1 gắn với VD bị huỷ/tái tạo khi token còn transition",
                ),
                StepCandidate(
                    id = "animation.restore",
                    purpose = "Trả ba scale về mốc",
                    commands = listOf(
                        "settings put global transition_animation_scale 0.5",
                        "settings put global window_animation_scale 0.5",
                        "settings put global animator_duration_scale 1.0",
                    ),
                    evidence = "ba global khớp mốc đo trên xe: 0.5 / 0.5 / 1.0",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.REVERSIBLE,
                ),
            ),
        ),
        CarStep(
            id = "orphan-inspect",
            feature = CarFeature.CLUSTER_CAST,
            purpose = "Phát hiện task/stack mồ côi trên cụm",
            precondition = "không có",
            candidates = listOf(
                StepCandidate(
                    id = "orphan.list",
                    purpose = "Đối chiếu stack, layer và tiến trình",
                    commands = listOf(
                        "am stack list",
                        "dumpsys SurfaceFlinger --list",
                        "pidof {pkg}",
                    ),
                    evidence = "biết chắc có stack nào trỏ tới tiến trình đã chết hay không",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.READ_ONLY,
                    fieldNote = "Layer bydAdd-<pkg> tồn tại KHÔNG chứng minh đang chiếu (đo 2026-07-27)",
                ),
            ),
        ),
        CarStep(
            id = "target-process",
            feature = CarFeature.CLUSTER_CAST,
            purpose = "Giết hoặc khởi động lại app đích để thử ca app chết giữa lúc chiếu",
            precondition = "app đang trên cụm; app KHÔNG phải CarPlay/Android Auto",
            candidates = listOf(
                StepCandidate(
                    id = "process.force-stop",
                    purpose = "Giết app đích có chủ ý để xem hệ thống xử lý ra sao",
                    commands = listOf("am force-stop {pkg}", "pidof {pkg}"),
                    evidence = "app chết; cụm và journal phản ứng đúng thay vì treo",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.MAY_DISRUPT_DRIVER,
                    fieldNote = "KHÔNG dùng candidate này cho CP/AA — ràng buộc sản phẩm",
                ),
            ),
        ),
        CarStep(
            id = "power-state",
            feature = CarFeature.CLUSTER_CAST,
            purpose = "Ngủ/thức màn hình để thử ca head unit sleep-wake",
            precondition = "xe đỗ hoặc an toàn để màn tắt",
            candidates = listOf(
                StepCandidate(
                    id = "power.sleep-wake",
                    purpose = "Tắt rồi bật màn",
                    commands = listOf("input keyevent 223", "input keyevent 224", "dumpsys display"),
                    evidence = "sau khi thức, display cụm và phiên chiếu ở trạng thái xác định, không nửa vời",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.MAY_DISRUPT_DRIVER,
                    fieldNote = "CHƯA thử: keyevent 223/224 (sleep/wakeup) có tác dụng trên head unit hay không",
                ),
            ),
        ),
        CarStep(
            id = "capture-state",
            feature = CarFeature.CLUSTER_CAST,
            purpose = "Chụp toàn bộ trạng thái hiển thị để so sánh giữa hai thời điểm",
            precondition = "không có",
            candidates = listOf(
                StepCandidate(
                    id = "capture.full-surface-flinger",
                    purpose = "Dump đầy đủ SurfaceFlinger + display, để so mốc chiếu-mở với chiếu-đóng",
                    commands = listOf(
                        "dumpsys SurfaceFlinger",
                        "dumpsys display",
                        "appops get {pkg}",
                    ),
                    evidence = "có đủ khối DisplayDevice của cụm kèm numLayers để đối chiếu hai trạng thái",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.READ_ONLY,
                    fieldNote = "Mốc chiếu-ĐÓNG đã có: fixtures/sf-FULL-projection-CLOSED.txt, numLayers=0. " +
                        "Thiếu mốc chiếu-MỞ — đây là dữ liệu duy nhất còn cần để trả lời Q1",
                ),
            ),
        ),
        CarStep(
            id = "probe-profile",
            feature = CarFeature.CLUSTER_CAST,
            purpose = "Nhận diện đời máy: tên service OEM, kích thước và density cụm",
            precondition = "không có",
            candidates = listOf(
                StepCandidate(
                    id = "probe.services-and-display",
                    purpose = "Liệt kê service OEM và đọc thông số display cụm",
                    commands = listOf("service list", "dumpsys display"),
                    evidence = "tìm được đúng một service container và display cụm; suy ra svcName của profile",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.READ_ONLY,
                    fieldNote = "DL3 có AutoContainer/AutoContainerNative/FissionGeneraySvc/FissionHostSvc",
                ),
            ),
        ),
        // ── Biển báo giới hạn tốc độ ─────────────────────────────────────────────────────────────────
        //
        // Chuỗi khám phá, theo đúng thứ tự phải biết: (1) ai sinh giá trị, (2) nó đi đường nào, (3) ai vẽ
        // nó, (4) lấy được giá trị đúng từ đâu, rồi mới (5) tắt nguồn sai và (6) ghi nguồn đúng vào.
        //
        // Không đảo thứ tự: ghi vào một đường chưa biết ai nghe thì không đọc được kết quả, mà vẫn mang đủ
        // rủi ro. Bốn step đầu CHỈ ĐỌC nên chạy được trong lúc lái.
        CarStep(
            id = "sign-inventory",
            feature = CarFeature.SPEED_SIGN,
            purpose = "Liệt kê ứng viên sinh/vẽ biển báo: package, service, tiến trình",
            precondition = "không có",
            candidates = listOf(
                StepCandidate(
                    id = "sign-inventory.packages",
                    purpose = "Tìm app liên quan nhận diện biển, ADAS, cụm, HUD",
                    commands = listOf("pm list packages | grep -iE 'adas|tsr|sign|dvr|cluster|hud|hmi|meter|camera'"),
                    evidence = "ra danh sách package ứng viên, ghi lại để các step sau nhắm đúng",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.READ_ONLY,
                ),
                StepCandidate(
                    id = "sign-inventory.services",
                    purpose = "Tìm service hệ thống liên quan xe, cụm, HUD",
                    commands = listOf("service list | grep -iE 'vehicle|car|adas|tsr|hud|meter|cluster|byd|xdja'"),
                    evidence = "ra tên service ứng viên kèm descriptor",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.READ_ONLY,
                ),
                StepCandidate(
                    id = "sign-inventory.hal",
                    purpose = "Có Vehicle HAL kiểu Android Automotive không, hay OEM tự làm",
                    commands = listOf("lshal 2>/dev/null | grep -iE 'vehicle|automotive'", "dumpsys car_service 2>&1 | head -40"),
                    evidence = "biết được giá trị đi qua HAL chuẩn hay qua service riêng của OEM",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.READ_ONLY,
                    fieldNote = "DiLink3 là Android 10 thường (QKQ1.210910.001), rất có thể KHÔNG có car_service",
                ),
                StepCandidate(
                    id = "sign-inventory.processes",
                    purpose = "Tiến trình nào đang chạy liên tục — nhận diện biển phải chạy thường trú",
                    commands = listOf("ps -A -o PID,USER,NAME | grep -iE 'adas|tsr|sign|dvr|meter|cluster|hud'"),
                    evidence = "ra pid để soi dumpsys và logcat theo pid",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.READ_ONLY,
                ),
            ),
        ),
        CarStep(
            id = "sign-watch-live",
            feature = CarFeature.SPEED_SIGN,
            purpose = "Bắt giá trị giới hạn tốc độ lúc nó ĐỔI, khi xe đi qua biển thật",
            precondition = "xe đang chạy, đi qua ít nhất hai biển khác số",
            candidates = listOf(
                StepCandidate(
                    id = "sign-watch.logcat-keywords",
                    purpose = "Xoá log, chạy qua biển, rồi lọc từ khoá",
                    commands = listOf(
                        "logcat -c",
                        "logcat -d -v time | grep -iE 'speed.?limit|speedlimit|tsr|traffic.?sign|isa|slif|limit=|maxspeed'",
                    ),
                    evidence = "có dòng log chứa số trùng với biển vừa đi qua",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.READ_ONLY,
                    fieldNote = "Người phải nhớ biển vừa qua là bao nhiêu để đối chiếu; đây là mắt xích quyết định",
                ),
                StepCandidate(
                    id = "sign-watch.logcat-raw-window",
                    purpose = "Không lọc gì, lấy nguyên cửa sổ log để soi off-car",
                    commands = listOf("logcat -c", "logcat -d -v threadtime"),
                    evidence = "file log đủ lớn để tìm mẫu số đổi theo thời điểm qua biển",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.READ_ONLY,
                    fieldNote = "Dùng khi lọc từ khoá ra rỗng — OEM có thể đặt tên khác hẳn",
                ),
                StepCandidate(
                    id = "sign-watch.props-diff",
                    purpose = "Giá trị có nằm trong system property không",
                    commands = listOf("getprop | grep -iE 'speed|limit|tsr|sign|adas'"),
                    evidence = "chụp hai lần ở hai vùng biển khác nhau và thấy property đổi",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.READ_ONLY,
                ),
                StepCandidate(
                    id = "sign-watch.settings-diff",
                    purpose = "Giá trị có nằm trong settings không",
                    commands = listOf("settings list global | grep -iE 'speed|limit|tsr|sign|adas'"),
                    evidence = "chụp hai lần ở hai vùng biển khác nhau và thấy key đổi",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.READ_ONLY,
                ),
            ),
        ),
        CarStep(
            id = "sign-consumer",
            feature = CarFeature.SPEED_SIGN,
            purpose = "Ai VẼ biển lên cụm và HUD — đó là đích cần gửi vào",
            precondition = "đã có danh sách package/service ứng viên",
            candidates = listOf(
                StepCandidate(
                    id = "sign-consumer.receivers",
                    purpose = "Package ứng viên khai báo receiver nào, action nào",
                    commands = listOf("dumpsys package {pkg} | grep -A3 -iE 'receiver|action'"),
                    evidence = "ra action broadcast mà bên vẽ đang nghe",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.READ_ONLY,
                ),
                StepCandidate(
                    id = "sign-consumer.service-dump",
                    purpose = "Service ứng viên tự dump trạng thái không",
                    commands = listOf("dumpsys activity service {pkg}", "dumpsys {svc}"),
                    evidence = "dump có trường giới hạn tốc độ hiện hành",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.READ_ONLY,
                    fieldNote = "Bài học từ Cluster Cast: dumpsys của service OEM có thể trả rỗng hoàn toàn",
                ),
                StepCandidate(
                    id = "sign-consumer.descriptor",
                    purpose = "Lấy descriptor AIDL của service để biết đường ghi",
                    commands = listOf("service call {svc} 1598968902"),
                    evidence = "đọc được tên interface, suy ra khả năng có setter",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.READ_ONLY,
                    fieldNote = "Cách này đã dùng được với AutoContainer, ra android.os.IAutoContainer…",
                ),
            ),
        ),
        CarStep(
            id = "sign-source-vietmap",
            feature = CarFeature.SPEED_SIGN,
            purpose = "Lấy giới hạn tốc độ ra khỏi VietMap mà không phải đọc ảnh",
            precondition = "VietMap đang dẫn đường trên đoạn có giới hạn",
            candidates = listOf(
                StepCandidate(
                    id = "sign-source.notification",
                    purpose = "Giới hạn có nằm trong notification của VietMap không",
                    commands = listOf("dumpsys notification --noredact | grep -A40 vietmap"),
                    evidence = "extras của notification chứa số giới hạn",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.READ_ONLY,
                    fieldNote = "Rẻ nhất và dùng lại được đường NavNotificationListener đã có sẵn trong app",
                ),
                StepCandidate(
                    id = "sign-source.exported-surface",
                    purpose = "VietMap có phát ra broadcast hay mở provider nào không",
                    commands = listOf("dumpsys package vn.vietmap.live | grep -iE 'exported=true|provider|receiver' "),
                    evidence = "có bề mặt đọc được mà không cần quyền đặc biệt",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.READ_ONLY,
                ),
                StepCandidate(
                    id = "sign-source.logcat",
                    purpose = "VietMap tự ghi log giới hạn không",
                    commands = listOf("logcat -c", "logcat -d | grep -iE 'vietmap.*(speed|limit)|limit.*vietmap'"),
                    evidence = "log chứa số đúng bằng biển đang đi qua",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.READ_ONLY,
                ),
                StepCandidate(
                    id = "sign-source.screen-crop",
                    purpose = "Cách cuối: chụp vùng badge giới hạn rồi đọc số off-car",
                    commands = listOf("screencap -d {display} -p /sdcard/vietmap-limit.png"),
                    evidence = "ảnh có badge rõ, đọc được số bằng mắt hoặc OCR ngoài xe",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.READ_ONLY,
                    fieldNote = "Chỉ dùng nếu ba cách trên rỗng: đọc ảnh thì trễ và dễ sai, đúng thứ đang muốn bỏ",
                ),
            ),
        ),
        CarStep(
            id = "sign-mute-camera",
            feature = CarFeature.SPEED_SIGN,
            purpose = "Tắt đường đọc biển bằng camera để hai nguồn không tranh nhau",
            precondition = "đã biết ai sinh giá trị; XE ĐỖ",
            candidates = listOf(
                StepCandidate(
                    id = "sign-mute.settings-key",
                    purpose = "Tắt bằng đúng khoá setting nếu OEM có khai",
                    commands = listOf("settings get global {key}", "settings put global {key} 0"),
                    evidence = "cụm ngừng hiện biển do camera đọc, và đặt lại giá trị cũ thì hiện lại",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.REVERSIBLE,
                ),
                StepCandidate(
                    id = "sign-mute.appops-camera",
                    purpose = "Chặn quyền camera của riêng app nhận diện",
                    commands = listOf("appops get {pkg} CAMERA", "appops set {pkg} CAMERA ignore"),
                    evidence = "app mất nguồn ảnh nên ngừng sinh giá trị; trả quyền thì chạy lại",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.MAY_DISRUPT_DRIVER,
                    fieldNote = "Phải kiểm cùng app đó có lo việc khác không, ví dụ camera lùi hay cảnh báo làn",
                ),
                StepCandidate(
                    id = "sign-mute.pm-disable",
                    purpose = "Vô hiệu hoá cả app nhận diện",
                    commands = listOf("pm disable-user --user 0 {pkg}", "pm enable {pkg}"),
                    evidence = "biển camera tắt hẳn; bật lại được về nguyên trạng",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.MAY_HANG_SYSTEM,
                    fieldNote = "Rủi ro cao nhất trong nhóm: app đó có thể là một phần của HMI cụm. Chỉ khi đỗ",
                ),
            ),
        ),
        CarStep(
            id = "sign-inject",
            feature = CarFeature.SPEED_SIGN,
            purpose = "Ghi giá trị của mình vào đúng đường mà cụm/HUD đang nghe",
            precondition = "đã biết đường và đích; XE ĐỖ ở lần thử đầu",
            candidates = listOf(
                StepCandidate(
                    id = "sign-inject.broadcast",
                    purpose = "Gửi broadcast đúng action đã tìm ra",
                    commands = listOf("am broadcast -a {key} --ei value {value}"),
                    evidence = "cụm hoặc HUD hiện đúng số vừa gửi",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.MAY_DISRUPT_DRIVER,
                ),
                StepCandidate(
                    id = "sign-inject.service-call",
                    purpose = "Gọi thẳng setter của service nếu descriptor có",
                    commands = listOf("service call {svc} {key} i32 {value}"),
                    evidence = "cụm hoặc HUD hiện đúng số vừa gửi",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.MAY_HANG_SYSTEM,
                    fieldNote = "Gọi transaction lạ trên service hệ thống là chỗ dễ làm treo nhất; chỉ khi đỗ",
                ),
                StepCandidate(
                    id = "sign-inject.settings-key",
                    purpose = "Ghi vào chính khoá mà bước watch thấy đổi theo biển",
                    commands = listOf("settings put global {key} {value}"),
                    evidence = "cụm hoặc HUD hiện đúng số vừa ghi",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.REVERSIBLE,
                ),
            ),
        ),
        CarStep(
            id = "sign-stale-guard",
            feature = CarFeature.SPEED_SIGN,
            purpose = "Ngừng gửi thì giá trị cũ có dính lại mãi không",
            precondition = "đã ghi được một giá trị vào cụm/HUD",
            candidates = listOf(
                StepCandidate(
                    id = "sign-stale.stop-sending",
                    purpose = "Ngừng gửi rồi chờ, xem số cũ có tự mất",
                    commands = listOf("sleep 60"),
                    evidence = "số cũ tự mất, hoặc dính lại — cả hai đều là kết quả cần biết",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.READ_ONLY,
                    fieldNote = "Số dính lại là nguy hiểm nhất: tài xế tin một giới hạn đã hết hiệu lực từ lâu",
                ),
                StepCandidate(
                    id = "sign-stale.clear-value",
                    purpose = "Có cách xoá hiển thị không, hay chỉ ghi được số",
                    commands = listOf("am broadcast -a {key} --ei value 0", "settings put global {key} 0"),
                    evidence = "hiển thị trở về trạng thái không có giới hạn",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.REVERSIBLE,
                ),
            ),
        ),

        // ── Chính sách phát lại chuỗi mở chiếu ───────────────────────────────────────────────────────
        //
        // Câu hỏi chặn đường app: khi nào được phát 30,16,35? V2 hiện chỉ phát ở lần chạy đầu tiên sau khi
        // cài (điều kiện "durable envelope pristine epoch 0"), nên sau khi Dừng hoặc tắt máy thì lần chiếu
        // sau chỉ đặt task và cụm nằm im ở đồng hồ.
        //
        // V1 dùng luật khác: cụm chưa có app thì phát, cụm đang có app thì hot-swap, KHÔNG phát lại. V1 ghi
        // tại chỗ rằng phát lại lúc cụm đang có app gây WM NPE và treo head unit, có kiểm 2026-07-23.
        //
        // KHÔNG lấy đó làm chân lý. V1 cũng sai nhiều chỗ, và lời ghi trong mã không phải bằng chứng. Vì
        // thế đây là step riêng để tự kiểm, và tách từng opcode để biết CHÍNH XÁC cái nào nguy hiểm thay vì
        // cấm cả chuỗi vì một câu chú thích.
        CarStep(
            id = "reissue-policy",
            feature = CarFeature.CLUSTER_CAST,
            purpose = "Xác định khi nào được phát lại chuỗi mở chiếu, và opcode nào nguy hiểm",
            precondition = "XE ĐỖ, máy nổ; có người nhìn cụm; sẵn sàng khởi động lại head unit nếu treo",
            candidates = listOf(
                StepCandidate(
                    id = "reissue.full-while-warm",
                    purpose = "Phát cả 30,16,35 khi cụm ĐANG có app — đúng ca V1 nói sẽ treo",
                    commands = listOf(
                        "service call {svc} 2 i32 1000 i32 30 s16 \"\"",
                        "service call {svc} 2 i32 1000 i32 16 s16 \"\"",
                        "service call {svc} 2 i32 1000 i32 35 s16 \"\"",
                    ),
                    evidence = "cụm vẫn hiện app và máy không treo, HOẶC treo — cả hai đều là kết quả cần biết",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.MAY_HANG_SYSTEM,
                    fieldNote = "Nếu treo: đây là bằng chứng cho luật của V1. Nếu không: V2 được phép phát lại vô điều kiện, và đường app đơn giản hẳn",
                ),
                StepCandidate(
                    id = "reissue.16-only-while-warm",
                    purpose = "Chỉ phát 16 khi cụm đang có app — tách riêng opcode bị nghi tái tạo display",
                    commands = listOf("service call {svc} 2 i32 1000 i32 16 s16 \"\""),
                    evidence = "cụm còn app và máy không treo",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.MAY_HANG_SYSTEM,
                    fieldNote = "V1 bỏ cmd16 khỏi đường warm ở v0.61 vì cho rằng nó tái tạo virtual display",
                ),
                StepCandidate(
                    id = "reissue.35-only-while-warm",
                    purpose = "Chỉ phát 35 khi cụm đang có app — opcode ít bị nghi nhất",
                    commands = listOf("service call {svc} 2 i32 1000 i32 35 s16 \"\""),
                    evidence = "cụm còn app và máy không treo",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.MAY_DISRUPT_DRIVER,
                ),
                StepCandidate(
                    id = "reissue.return-then-recast",
                    purpose = "Đường an toàn giả định: trả task về màn giữa cho cụm rỗng, rồi chiếu lại từ đầu",
                    commands = listOf(
                        "am start --display 0 -n {comp}",
                        "am start --display 1 --windowingMode 5 -n {comp}",
                        "am task resize {taskId} {left} {top} {right} {bottom}",
                        "service call {svc} 2 i32 1000 i32 30 s16 \"\"",
                        "service call {svc} 2 i32 1000 i32 16 s16 \"\"",
                        "service call {svc} 2 i32 1000 i32 35 s16 \"\"",
                    ),
                    evidence = "cụm hiện app trở lại, không treo",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.MAY_DISRUPT_DRIVER,
                    fieldNote = "Đây là đường phục hồi khi người dùng nói 'không thấy map'; cần chứng minh trước khi đưa vào app",
                ),
                StepCandidate(
                    id = "reissue.task-placed-projection-closed",
                    purpose = "Dựng lại đúng trạng thái lệch: task trên cụm nhưng chiếu đã đóng",
                    commands = listOf(
                        "service call {svc} 2 i32 1000 i32 18 s16 \"\"",
                        "service call {svc} 2 i32 1000 i32 0 s16 \"\"",
                        "am stack list",
                    ),
                    evidence = "task vẫn trên display cụm với visible=true trong khi cụm hiện đồng hồ",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.MAY_DISRUPT_DRIVER,
                    fieldNote = "Đã dựng được 2026-07-27 chiều; đây là trạng thái mà app KHÔNG đo được, nên phải hỏi người dùng",
                ),
            ),
        ),

    )

    fun step(id: String): CarStep? = steps.firstOrNull { it.id == id }

    fun candidate(id: String): Pair<CarStep, StepCandidate>? = steps.firstNotNullOfOrNull { step ->
        step.candidates.firstOrNull { it.id == id }?.let { step to it }
    }
}
