package com.byd.clusternav.carexec

/** Tính năng của xe mà ta đang chứng minh khả thi. */
enum class CarFeature { CLUSTER_CAST, NAVIGATION }

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

    val placeholders = setOf(
        PLACEHOLDER_PACKAGE, PLACEHOLDER_COMPONENT, PLACEHOLDER_DISPLAY,
        PLACEHOLDER_TASK, PLACEHOLDER_SERVICE,
        PLACEHOLDER_LEFT, PLACEHOLDER_TOP, PLACEHOLDER_RIGHT, PLACEHOLDER_BOTTOM, PLACEHOLDER_DPI,
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
                    fieldNote = "vn.vietmap.live: lands 440x720 rồi resize đầy khung (2026-07-26)",
                ),
                StepCandidate(
                    id = "place.freeform-only",
                    purpose = "App khai unresizeable: chỉ cần freeform khi force_resizable_activities=1",
                    commands = listOf("am start --display {display} --windowingMode 5 -n {comp}"),
                    evidence = "task lên đúng 1920x720 ngay, không cần resize",
                    verdictSource = VerdictSource.MEASURED,
                    fieldNote = "com.byd.auto_photo landed 1920x720; am task resize bị từ chối nhưng không cần (2026-07-26)",
                ),
                StepCandidate(
                    id = "place.movestack",
                    purpose = "Đường leo thang khi hai cách trên không bám",
                    commands = listOf("am display move-stack {taskId} {display}"),
                    evidence = "task chuyển sang display cụm mà không tạo orphan",
                    verdictSource = VerdictSource.MEASURED,
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
                    fieldNote = "Gõ tay 2026-07-27 09:58: cả ba trả Parcel(0,0), owner xác nhận 'lên rồi'",
                ),
                StepCandidate(
                    id = "open.seal-16-only",
                    purpose = "Hình dạng DiLink5: chỉ opcode chiếu, không có opcode kiểu",
                    commands = listOf("service call {svc} 2 i32 1000 i32 16 s16 \"\""),
                    evidence = "cụm hiện app trên đời máy không hỗ trợ đổi kiểu",
                    verdictSource = VerdictSource.HUMAN,
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
                    fieldNote = "Gõ tay 2026-07-26 tối: owner xác nhận 'về rồi' (kiểu cong)",
                ),
                StepCandidate(
                    id = "teardown.0-only",
                    purpose = "Chỉ refresh video, xem có đủ để trả đồng hồ không",
                    commands = listOf("service call {svc} 2 i32 1000 i32 0 s16 \"\""),
                    evidence = "cụm về đồng hồ mà không cần opcode 18",
                    verdictSource = VerdictSource.HUMAN,
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
                    fieldNote = "Số đo bounds là MEASURED, nhưng 'render có ổn không' thì phải nhìn",
                ),
                StepCandidate(
                    id = "geometry.overscan",
                    purpose = "Đường dự phòng của V1 khi freeform không sống",
                    commands = listOf("wm overscan {left},{top},{right},{bottom} -d {display}"),
                    evidence = "khung co lại đúng và app vẫn vẽ đủ",
                    verdictSource = VerdictSource.HUMAN,
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
                    fieldNote = "Mốc đo trên xe: density cụm mặc định 320",
                ),
                StepCandidate(
                    id = "dpi.reset",
                    purpose = "Trả density về mặc định",
                    commands = listOf("wm density reset -d {display}"),
                    evidence = "density trở lại 320 theo dumpsys display",
                    verdictSource = VerdictSource.MEASURED,
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
                    fieldNote = "Owner chấp nhận kiểu cong sau Stop; opcode 30 nằm trong castSeq DL3",
                ),
                StepCandidate(
                    id = "style.flat-31",
                    purpose = "Kiểu phẳng, khung rộng hơn",
                    commands = listOf("service call {svc} 2 i32 1000 i32 31 s16 \"\""),
                    evidence = "cụm đổi sang kiểu phẳng",
                    verdictSource = VerdictSource.HUMAN,
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
                    fieldNote = "NavConnect tự-heal dùng đúng đường này",
                ),
                StepCandidate(
                    id = "nav.listener-read",
                    purpose = "Đọc danh sách listener hiện tại",
                    commands = listOf("settings get secure enabled_notification_listeners"),
                    evidence = "đọc được danh sách, xác nhận có hoặc không có {comp}",
                    verdictSource = VerdictSource.MEASURED,
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
                ),
                StepCandidate(
                    id = "return.movestack-main",
                    purpose = "Đường leo thang nếu mở lại không đủ",
                    commands = listOf("am display move-stack {taskId} 0"),
                    evidence = "task về display 0 mà không tạo orphan (đây là lệnh từng gây NPE ở V1)",
                    verdictSource = VerdictSource.MEASURED,
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
                ),
                StepCandidate(
                    id = "pip.restore",
                    purpose = "Trả app-op về mốc",
                    commands = listOf("appops set {pkg} PICTURE_IN_PICTURE allow"),
                    evidence = "appops đọc lại đúng mốc đã journal",
                    verdictSource = VerdictSource.MEASURED,
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
                    fieldNote = "DL3 có AutoContainer/AutoContainerNative/FissionGeneraySvc/FissionHostSvc",
                ),
            ),
        ),
    )

    fun step(id: String): CarStep? = steps.firstOrNull { it.id == id }

    fun candidate(id: String): Pair<CarStep, StepCandidate>? = steps.firstNotNullOfOrNull { step ->
        step.candidates.firstOrNull { it.id == id }?.let { step to it }
    }
}
