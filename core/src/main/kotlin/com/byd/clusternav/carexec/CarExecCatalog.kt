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

    /**
     * Kính lái (HUD thật) — công tắc `SET_HUD_SWITCH_SET`, không phải làn cụm.
     *
     * RE 2026-07-29 (carsettings-apk = com.byd.vehiclesettings đã decompile) chứng minh được TOÀN BỘ
     * chuỗi gọi từ UI xuống tới ranh giới stub: HudSwitch UI -> HudSwitchModel -> HalSetter.set(class,id,val)
     * -> BYDAutoSettingDevice.getInstance(ctx).set(int[]{id}, EventValue) — CÙNG hình dạng lệnh gọi mà
     * BydHal.kt đã dùng cho INSTRUMENT/SETTING, và CÙNG class BYDAutoSettingDevice. Feature-id thật
     * (0x4C10E023 SET_HUD_SWITCH_SET, 0x38B00015 SET_HUD_CONFIG, 0x38B0001C ...FEEDBACK) đọc được từ
     * firmware thật (`firmware/fw-2602-diff/jadx-l3-new`), không phải từ file stub (mọi field trong
     * `BYDAutoFeatureIds.java` decompile đều = 0).
     *
     * Xe test CÓ kính lái vật lý — chủ xe xác nhận trực tiếp 2026-07-29 (không phải suy đoán, không cần
     * đo lại việc có/không). Vẫn CHƯA đo được: SET_HUD_CONFIG trả 1 (W-mode) hay 2 (AR-mode), và
     * ClusterNav's process có được HAL cấp quyền set() hay không (permission gate nằm trong chính stub,
     * không thấy được qua decompile). Bước hud-probe dưới đây vẫn nên chạy trước mọi setraw ghi — không
     * phải để hỏi "có hay không" nữa, mà để biết W-mode/AR-mode và xác nhận quyền set() có thật.
     */
    HUD_SWITCH,
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
            // "ĐỖ" bắt buộc từ 2026-08-01: candidate leo thang `place.movestack` mang nhãn MAY_HANG_SYSTEM
            // (sập system_server 3/3 lần, đo thật). Hai candidate đầu vẫn an toàn khi đang chạy, nhưng
            // precondition được canh ở mức STEP nên phải nói theo candidate nguy hiểm nhất.
            precondition = "xe ĐỖ (bước leo thang có thể treo hệ thống); biết display cụm; app đã cài",
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
                    purpose = "Đường leo thang khi hai cách trên không bám — ĐANG BỊ CẤM, xem fieldNote",
                    // LỖI THAM SỐ, cố ý GIỮ NGUYÊN chuỗi: tham số thứ nhất của `am display move-stack`
                    // là STACK id chứ không phải task id (ActivityManagerShellCommand.runDisplayMoveStack).
                    // Không thêm placeholder {stackId} mới cho một lệnh đang bị CẤM DÙNG — sẽ là nối dây
                    // cho thứ không ai được phép chạy. Ai gỡ lệnh cấm sau này phải sửa cả hai lỗi cùng lúc.
                    commands = listOf("am display move-stack {taskId} {display}"),
                    evidence = "task chuyển sang display cụm mà không tạo orphan",
                    verdictSource = VerdictSource.MEASURED,
                    // ĐO 2026-08-01 trên DiLink3, 3/3 lần: lệnh này làm system_server ném NPE giữa chừng
                    // (TaskSnapshotController.createTaskSnapshot ← AppWindowToken.initializeChangeTransition
                    // ← DisplayContent.moveStackToDisplay), task BIẾN MẤT khỏi hệ thống, phiên CarPlay
                    // rớt hẳn phải cắm lại cáp. Nhãn READ_ONLY cũ là SAI NGHIÊM TRỌNG — nó nói với người
                    // vận hành rằng lệnh này không đổi gì.
                    //
                    // Gốc: `DisplayContent.java:2401-2402` (AOSP android-10) gỡ stack khỏi display cũ
                    // (⇒ TaskStack.mDisplayContent = null) rồi để sóng đổi cấu hình nổ TRONG LÚC gắn vào
                    // display mới, trước khi mDisplayContent mới kịp được gán. Kích hoạt khi vượt ranh
                    // giới FREEFORM — mà display 0 là fullscreen còn display cụm là freeform, nên lần
                    // nào cũng vượt. Android 10 không có bản vá ở bất kỳ nhánh release nào.
                    // Chi tiết: docs/diagnostics/carplay-aa-cluster-placement-research-2026-08-01.md
                    risk = CandidateRisk.MAY_HANG_SYSTEM,
                    fieldNote = "CẤM DÙNG cho tới khi có phép đo khác: sập system_server 3/3 lần (2026-08-01). " +
                        "Hai lỗi phải sửa cùng lúc nếu gỡ cấm: (1) tham số 1 phải là STACK id, chuỗi hiện " +
                        "tại truyền {taskId} là sai; (2) chọn đường khác. Đường thay thế cần đo là " +
                        "`am stack move-task` — chuyển TASK vào stack đã nằm sẵn trên display đích, không " +
                        "chuyển cả stack qua display, nên không rơi vào cửa sổ mDisplayContent=null.",
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
            purpose = "Đổi kiểu cụm (cong giữ km/h ↔ phẳng) — HOẶC kích thước vật lý cụm, xem fieldNote xung đột",
            precondition = "đời máy hỗ trợ đổi kiểu (styleOps khác null)",
            candidates = listOf(
                StepCandidate(
                    id = "style.curved-30",
                    purpose = "Kiểu cong, giữ đồng hồ km/h",
                    commands = listOf("service call {svc} 2 i32 1000 i32 30 s16 \"\""),
                    evidence = "cụm hiện kiểu cong và vẫn thấy km/h",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.MAY_DISRUPT_DRIVER,
                    fieldNote = "Owner chấp nhận kiểu cong sau Stop; opcode 30 nằm trong castSeq DL3. XUNG ĐỘT chưa giải quyết " +
                        "(RE 2026-07-29, dashcast-src/data/prefs/ClusterPrefs.java + ui/settings/SettingsActivity.java + " +
                        "CHANGELOG.md:12): DashCast tự field-test và label 29/30/31 là KÍCH THƯỚC VẬT LÝ cụm theo TỪNG ĐỜI XE " +
                        "(29=8.8\" Atto3/Dolphin, 30=12.3\" Seal EU mặc định, 31=10.25\" Seal U DMI) — KHÔNG PHẢI cong/phẳng. " +
                        "Có thể cả hai đều đúng (một field vừa quyết kích thước vừa đổi hình dạng do khung khác nhau), hoặc " +
                        "quan sát cũ 'owner xác nhận cong' đã bị đọc nhầm. CHƯA đối chiếu lại trên chính xe test — xem thêm " +
                        "candidate style.probe-screen-size-29.",
                ),
                StepCandidate(
                    id = "style.flat-31",
                    purpose = "Kiểu phẳng, khung rộng hơn (hoặc: kích thước 10.25\" theo DashCast — xem xung đột ở style.curved-30)",
                    commands = listOf("service call {svc} 2 i32 1000 i32 31 s16 \"\""),
                    evidence = "cụm đổi sang kiểu phẳng",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.MAY_DISRUPT_DRIVER,
                    fieldNote = "styleOps DL3 = 30 to 31. Xem fieldNote của style.curved-30 về xung đột cong/phẳng vs kích thước.",
                ),
                StepCandidate(
                    id = "style.probe-screen-size-29",
                    purpose = "Thử opcode 29 (chưa từng gửi trên xe này) để chốt xung đột cong/phẳng vs kích thước",
                    commands = listOf("service call {svc} 2 i32 1000 i32 29 s16 \"\""),
                    evidence = "NHÌN kỹ: nếu hình dạng cong/phẳng đổi -> ủng hộ giả thuyết cũ (style). Nếu độ phân giải/kích thước " +
                        "vẽ đổi mà hình dạng giữ nguyên -> ủng hộ giả thuyết DashCast (screen size). Chụp ảnh cả hai lần so sánh.",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.MAY_DISRUPT_DRIVER,
                    fieldNote = "RE 2026-07-29: opcode 29 = 8.8\" theo DashCast (Atto 3/Dolphin) — trên Seal DL3 (12.3\" mặc định " +
                        "theo cùng bảng) có thể không có hiệu ứng nhìn thấy được nếu đúng là size-per-model, vì DL3 vốn không " +
                        "phải máy 8.8\". Vẫn đáng thử để loại trừ.",
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
        // ── Cổng render nav zin (semon) ──────────────────────────────────────────────────────────────
        //
        // KHÁM PHÁ CŨ, CHƯA CHẠY XONG: phiên 2026-06-22 (docs/plans/cluster-nav-render-gate.html +
        // docs/diagnostics/verify-on-car.sh, TRƯỚC refactor V2 này rất lâu) đã LIVE-CONFIRM trên chính
        // Seal DL3 test rằng broadcast AUTONAVI_STANDARD_BROADCAST_SEND (TYPE=1, IS_BYD_MAP=false) khiến
        // AmapService thật sự GHI dữ liệu vào cụm (mIsGAODENaving=true, CAN + AutoContainerManager.sendInfo2)
        // — nhưng RENDER bị chặn bởi "semon", một kernel security monitor bật qua property
        // sys.init.navi_protect (tắt = mở cổng, theo chính init.rc rút từ system.img thật của xe này).
        // 7 cách mở cổng (M1-M8) đã viết sẵn trong verify-on-car.sh nhưng lần chạy 2026-06-22 để lại thư
        // mục verify-runs/20260622-221938 RỖNG — script không hoàn tất được lần đó (không rõ lý do, có thể
        // mất kết nối). Trạng thái đúng: "chưa biết cổng có mở được không", KHÔNG PHẢI "đã thử và thất bại".
        //
        // ĐÃ ĐO 2026-07-29 trên chính xe test (Seal DL3, navi_protect=1 KHÔNG đổi, whitelist=0 và
        // change_navi_auth=1 đã sẵn có từ trước — KHÔNG do phiên này set): gate.broadcast-full-render (bên
        // dưới) làm CỤM hiện icon + khoảng cách + tên đường THẬT, KHÔNG cần setprop bất kỳ property nào.
        // gate.setprop-navi-protect (M1) và gate.navopen-open (M8) CHƯA từng được thử trong phiên này vì
        // không cần thiết nữa cho phần cụm — vẫn giữ lại trong catalog cho trường hợp property đã bị đổi về
        // mặc định ở phiên khác, hoặc cho phần HUD (vẫn chưa hiện gì dù cụm đã render — xem hud-probe).
        CarStep(
            id = "nav-render-gate",
            feature = CarFeature.NAVIGATION,
            purpose = "Mở cổng render nav zin của cụm (semon/navi_protect) — TRƯỚC KHI đổ công sức vào bất kỳ đường nav nào khác",
            precondition = "cụm đang hiện đồng hồ hoặc app khác; chưa biết navi_protect/whitelist đang bật hay tắt",
            candidates = listOf(
                StepCandidate(
                    id = "gate.probe",
                    purpose = "Đọc 4 property quyết định cổng, trước khi đổi gì",
                    commands = listOf(
                        "getprop ro.build.system.fission_single_os",
                        "getprop sys.init.navi_protect",
                        "getprop sys.init.whitelist",
                        "getprop sys.change_navi_auth",
                    ),
                    evidence = "đọc được 4 giá trị hiện tại làm mốc so sánh trước/sau",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.READ_ONLY,
                    fieldNote = "Mốc 2026-06-22 trên chính xe này: fission_single_os=0, 1for2=true (nghĩa là CẢ hai đường " +
                        "FlatBuffer/AutoContainerManager.sendInfo2 VÀ HAL/CAN đều chạy song song cho mỗi update).",
                ),
                StepCandidate(
                    id = "gate.baseline-broadcast-only",
                    purpose = "Bơm 1 frame KHÔNG mở cổng trước — dựng lại đúng baseline 'data vào cụm nhưng không hiện' để so sánh với các bước mở cổng bên dưới",
                    commands = listOf(
                        "am broadcast -a AUTONAVI_STANDARD_BROADCAST_SEND --ei KEY_TYPE 10001 --ei TYPE 1 --ei EXTRA_STATE 1 --ei EXTRA_IS_FOREGROUND 0 --ez IS_BYD_MAP false --ez IS_BYD_BAIDU_MAP false --ei NEW_ICON 2 --ei SEG_REMAIN_DIS 250 --es NEXT_ROAD_NAME 'Nguyen Hue' --ei ROUTE_REMAIN_DIS 5000 --ei ROUTE_REMAIN_TIME 480",
                    ),
                    evidence = "logcat AmapService log mIsGAODENaving=true (grep 'amap|navi|cluster|1for2|fission|guide|instrument'); NHÌN cụm — mong đợi KHÔNG đổi gì nếu cổng vẫn đóng",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.REVERSIBLE,
                    fieldNote = "SAI, LIVE-CONFIRMED 2026-07-29 trên chính xe test: giả thuyết 'không đổi gì' KHÔNG đúng — " +
                        "cụm HIỆN icon + tên đường ngay từ candidate này (chỉ riêng khoảng cách kẹt ở -1, xem " +
                        "gate.broadcast-full-render để có số thật). Cơ chế TYPE=1+IS_BYD_MAP=false đã chứng minh qua source " +
                        "2026-07-25 và giờ tự tay đo lại khớp — nhưng tiền đề 'cổng đóng nên không hiện' của phiên 2026-06-22 " +
                        "không còn đúng ở trạng thái property hiện tại của xe này (xem gate.probe).",
                ),
                StepCandidate(
                    id = "gate.broadcast-full-render",
                    purpose = "Y hệt baseline nhưng thêm 4 field chuỗi _AUTO — bản ĐÃ CHỨNG MINH render đủ icon+khoảng cách+tên đường trên cụm, không cần setprop gì",
                    commands = listOf(
                        "am broadcast -a AUTONAVI_STANDARD_BROADCAST_SEND --ei KEY_TYPE 10001 --ei TYPE 1 --ei EXTRA_STATE 1 --ei EXTRA_IS_FOREGROUND 0 --ez IS_BYD_MAP false --ez IS_BYD_BAIDU_MAP false --ei NEW_ICON 3 --ei SEG_REMAIN_DIS 444 --es NEXT_ROAD_NAME 'Ba Test Le Loi' --ei ROUTE_REMAIN_DIS 6000 --ei ROUTE_REMAIN_TIME 300 --es SEG_REMAIN_DIS_AUTO '444 m' --es ROUTE_REMAIN_DIS_AUTO '6.0 km' --es ROUTE_REMAIN_TIME_AUTO '5 min' --es ROUTE_REMAIN_TIME_STRING '5 min'",
                    ),
                    evidence = "CỤM hiện đúng icon rẽ + '444 m' (không phải -1) + 'Ba Test Le Loi' cùng lúc",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.REVERSIBLE,
                    fieldNote = "LIVE-CONFIRMED 2026-07-29 trên chính xe test (navi_protect=1 suốt, không setprop gì): thiếu " +
                        "SEG_REMAIN_DIS_AUTO/ROUTE_REMAIN_DIS_AUTO/ROUTE_REMAIN_TIME_AUTO/ROUTE_REMAIN_TIME_STRING thì khoảng " +
                        "cách kẹt ở -1 dù icon+tên đường vẫn lên đúng (xem gate.baseline-broadcast-only) — thêm 4 field chuỗi " +
                        "này là đủ, không cần bất kỳ M1/M2/M3/M8 nào. HUD kính lái vẫn KHÔNG hiện gì từ đường này (xem hud-probe " +
                        "hud.nav-content-toggle-on) — cụm và HUD rõ ràng là hai đường render riêng.",
                ),
                StepCandidate(
                    id = "gate.setprop-navi-protect",
                    purpose = "M1 — tắt semon trực tiếp qua navi_protect, rồi bơm lại frame để xem cổng có mở không",
                    commands = listOf(
                        "setprop sys.init.navi_protect 0",
                        "am broadcast -a AUTONAVI_STANDARD_BROADCAST_SEND --ei KEY_TYPE 10001 --ei TYPE 1 --ei EXTRA_STATE 1 --ei EXTRA_IS_FOREGROUND 0 --ez IS_BYD_MAP false --ez IS_BYD_BAIDU_MAP false --ei NEW_ICON 2 --ei SEG_REMAIN_DIS 250 --es NEXT_ROAD_NAME 'Nguyen Hue' --ei ROUTE_REMAIN_DIS 5000 --ei ROUTE_REMAIN_TIME 480",
                    ),
                    evidence = "NHÌN cụm: làn nav (mũi tên + tên đường + khoảng cách) hiện RA, và đồng hồ/ADAS/D-R-P vẫn còn đủ cùng lúc",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.REVERSIBLE,
                    fieldNote = "Rẻ nhất trong 3 cách setprop. Ẩn số: shell uid 2000 có được SELinux cho phép setprop key này " +
                        "không — tự lộ ngay khi đọc lại bằng gate.probe. Không đảo thứ tự: đo cổng trước, injec sau.",
                ),
                StepCandidate(
                    id = "gate.setprop-whitelist",
                    purpose = "M2 — tắt enforcement theo whitelist (ngả khác cùng tắt semon)",
                    commands = listOf(
                        "setprop sys.init.whitelist 0",
                        "am broadcast -a AUTONAVI_STANDARD_BROADCAST_SEND --ei KEY_TYPE 10001 --ei TYPE 1 --ei EXTRA_STATE 1 --ei EXTRA_IS_FOREGROUND 0 --ez IS_BYD_MAP false --ez IS_BYD_BAIDU_MAP false --ei NEW_ICON 2 --ei SEG_REMAIN_DIS 250 --es NEXT_ROAD_NAME 'Nguyen Hue' --ei ROUTE_REMAIN_DIS 5000 --ei ROUTE_REMAIN_TIME 480",
                    ),
                    evidence = "như gate.setprop-navi-protect",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.REVERSIBLE,
                    fieldNote = "Chỉ thử nếu M1 trượt hoặc để xác nhận cả hai property đều điều khiển cùng một semon switch.",
                ),
                StepCandidate(
                    id = "gate.setprop-change-auth",
                    purpose = "M3 — cấp cờ 'đổi nav được phép' cấp BYD (có thể là cờ đúng thay vì tắt cả monitor)",
                    commands = listOf(
                        "setprop sys.change_navi_auth 1",
                        "am broadcast -a AUTONAVI_STANDARD_BROADCAST_SEND --ei KEY_TYPE 10001 --ei TYPE 1 --ei EXTRA_STATE 1 --ei EXTRA_IS_FOREGROUND 0 --ez IS_BYD_MAP false --ez IS_BYD_BAIDU_MAP false --ei NEW_ICON 2 --ei SEG_REMAIN_DIS 250 --es NEXT_ROAD_NAME 'Nguyen Hue' --ei ROUTE_REMAIN_DIS 5000 --ei ROUTE_REMAIN_TIME 480",
                    ),
                    evidence = "như gate.setprop-navi-protect",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.REVERSIBLE,
                    fieldNote = "Không có tài liệu công khai nào về key này — tên thật rút từ chính init.rc của xe. Giá trị mặc định là 0.",
                ),
                StepCandidate(
                    id = "gate.navopen-probe",
                    purpose = "M8 bước 1 — probe thuần bằng reflection (navopen-v2.jar), KHÔNG ghi gì, chỉ xem HAL có cấp device không",
                    commands = listOf(
                        "CLASSPATH=/data/local/tmp/navopen.jar app_process /system/bin com.byd.navopen.NavOpen probe",
                    ),
                    evidence = "output liệt kê InstrumentDevice/SettingDevice khác null, và các feature-id INSTRUMENT_SEND_NAVI_STATUS_SET v.v. có giá trị thật (khác 'không có field')",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.READ_ONLY,
                    fieldNote = "TIỀN ĐỀ: đã `adb push apks/navopen-v2.jar /data/local/tmp/navopen.jar` trước (v2 = bản 2026-07-29, " +
                        "thêm getraw/adas so với navopen.jar gốc, xem NavOpen/src/com/byd/navopen/NavOpen.java). " +
                        "app_process chạy từ adb shell đã sẵn uid 2000 (như proxy OpenBYD dùng), không cần root, không cần daemon.",
                ),
                StepCandidate(
                    id = "gate.navopen-open",
                    purpose = "M8 bước 2 — replica chính xác cách AmapService tự mở cổng qua HAL (SET_NAVI_SCREEN_STATUS=3) rồi bơm 1 frame",
                    commands = listOf(
                        "CLASSPATH=/data/local/tmp/navopen.jar app_process /system/bin com.byd.navopen.NavOpen full 'Nguyen Hue'",
                    ),
                    evidence = "NHÌN cụm: làn nav hiện RA (đường mạnh nhất nếu M1-M3 bị SELinux chặn, vì đây không phải setprop mà là đúng lệnh HAL map zin tự gọi)",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.MAY_DISRUPT_DRIVER,
                    fieldNote = "RE 2026-07-29: đây LÀ cơ chế map hệ thống dùng thật (AmapService gọi đúng setNaviScreenStatus(SET_NAVI_SCREEN_STATUS_SET,3)) " +
                        "— không phải suy đoán. Đóng lại bằng gate.navopen-close ngay sau khi quan sát xong.",
                ),
                StepCandidate(
                    id = "gate.navopen-close",
                    purpose = "M8 bước 3 — đóng lại nav đã mở ở gate.navopen-open, trả cụm về trạng thái trước đó",
                    commands = listOf(
                        "CLASSPATH=/data/local/tmp/navopen.jar app_process /system/bin com.byd.navopen.NavOpen close",
                    ),
                    evidence = "cụm hết hiện làn nav vừa bơm (không đảm bảo trả nguyên trạng cờ navi_protect/whitelist nếu đã setprop ở candidate khác — tự setprop lại giá trị đọc được ở gate.probe)",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.REVERSIBLE,
                    fieldNote = "Chỉ đóng phần INSTRUMENT_SEND_NAVI_STATUS/GUIDE_INFO — KHÔNG trả navi_protect/whitelist về giá trị cũ; " +
                        "nếu đã chạy gate.setprop-* thì phải tự setprop lại giá trị đã ghi ở gate.probe.",
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
            // "ĐỖ" bắt buộc từ 2026-08-01: cùng lý do như step `place` — candidate leo thang
            // `return.movestack-main` dùng `am display move-stack`, đã đo là sập system_server.
            // Candidate đầu (`am start --display 0`) vẫn an toàn khi đang chạy.
            precondition = "xe ĐỖ (bước leo thang có thể treo hệ thống); app đang trên cụm",
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
                    purpose = "Đường leo thang nếu mở lại không đủ — ĐANG BỊ CẤM, xem fieldNote",
                    // Cùng lỗi tham số như `place.movestack` (phải là STACK id) — giữ nguyên chuỗi vì
                    // lệnh đang bị cấm; xem ghi chú ở đó.
                    commands = listOf("am display move-stack {taskId} 0"),
                    evidence = "task về display 0 mà không tạo orphan (đây là lệnh từng gây NPE ở V1)",
                    verdictSource = VerdictSource.MEASURED,
                    // Cùng lỗi framework với `place.movestack`. Đáng chú ý: fieldNote V1 dưới đây ghi đúng
                    // điều kiện kích hoạt mà mãi 2026-08-01 mới đọc ra được từ source AOSP — "task
                    // FREEFORM đang HIỆN" chính là hai guard trong `AppWindowToken.initializeChangeTransition`.
                    // Một xác nhận độc lập từ hiện trường cũ, và là lý do nhãn READ_ONLY cũ càng khó chấp nhận.
                    risk = CandidateRisk.MAY_HANG_SYSTEM,
                    fieldNote = "V1: move-stack một task freeform đang hiện từng gây half-reparent. " +
                        "Xác nhận lại 2026-08-01: sập system_server 3/3 lần, task biến mất khỏi hệ thống.",
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
                StepCandidate(
                    id = "probe.autocontainer-whitelist",
                    purpose = "Đọc whitelist client được phép gọi thẳng AutoContainer (bỏ qua Manager)",
                    commands = listOf("service list | grep -i autocontainer", "cat /system/etc/container_comm_cfg.json"),
                    evidence = "thấy service AutoContainer sống + nội dung whitelist (RE: chỉ com.xdja.clusterdemo được liệt kê)",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.READ_ONLY,
                    fieldNote = "RE 2026-07-29 (dashcast-src/ClusterManager.java + firmware jadx-l3-new/AutoContainerManager.java): " +
                        "getSystemService(\"AutoContainer\") qua Java API bị chặn theo whitelist gói đọc từ file này; " +
                        "DashCast né bằng gọi Binder trực tiếp (bypass Manager). Chưa xác nhận ClusterNav (uid app thường, " +
                        "không phải uid 2000 của adb shell) có nằm trong danh sách này không.",
                ),
                StepCandidate(
                    id = "probe.magicwindow-service",
                    purpose = "Dò service 'magicwindow' (IMagicWindowManager) — nghi ngờ là API windowing đa-cụm trên DL5",
                    commands = listOf("service check magicwindow", "dumpsys magicwindow"),
                    evidence = "service check trả về khác 'not found'; dumpsys in ra nội dung (dù không hiểu được) xác nhận service sống",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.READ_ONLY,
                    fieldNote = "RE 2026-07-29 (dashcast-src/CHANGELOG.md, dò DL5 build 183/184): service có thật, ServiceManager " +
                        "slot id=140 lúc dò, nhưng DashCast tự thừa nhận CHƯA BAO GIỜ gọi xa hơn bước sống-hay-chết này — " +
                        "sản phẩm DL5 sau đó quay về `am start --display N --windowingMode 5`. Đây là READ_ONLY, chỉ để biết " +
                        "service còn tồn tại trên DL3 hay không, KHÔNG suy ra nó làm gì.",
                ),
                StepCandidate(
                    id = "probe.trafficmonitor-service",
                    purpose = "Xác nhận com.byd.trafficmonitor (dịch vụ mute/allow TSR theo từng app) có cài và đang chạy",
                    commands = listOf(
                        "pm list packages | grep -i trafficmonitor",
                        "dumpsys activity services com.byd.trafficmonitor",
                        "service list | grep -i traffic",
                    ),
                    evidence = "thấy package com.byd.trafficmonitor + service đang chạy (pid, uid=system)",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.READ_ONLY,
                    fieldNote = "RE 2026-07-29 (carsettings-apk TSRCellular.java + IAppTrafficInterface.java) + đối chiếu dump thật " +
                        "docs/diagnostics/runs/20260621-141543: package NÀY đã xác nhận SỐNG trên xe thật (log tag " +
                        "TrafficMonitorService, ReceiverList uid=1000). AIDL: action " +
                        "com.byd.trafficmonitor.aidl.apptrafficremote, interface IAppTrafficInterface{getTrafficState(pkg), " +
                        "setRestrictByUser(pkg,restrict)} — cho phép BẬT/TẮT một app cụ thể làm nguồn TSR theo package name. " +
                        "Package cứng trong code RE ra (com.telenav.app.isa) KHÔNG có trên xe test — ai là provider thật trên " +
                        "xe này còn chưa biết; chỉ bind Service được (app-bound, không qua ServiceManager) nên không gọi được " +
                        "bằng `service call` thô — cần code app_process/app mới gọi bindService thật.",
                ),
                StepCandidate(
                    id = "probe.naviserviceapi-service",
                    purpose = "Dò xem AIDL com.byd.naviserviceapi.INaviService (BYD định nghĩa cho app nav bên thứ 3) có ai host không",
                    commands = listOf(
                        "dumpsys activity services | grep -i naviservice",
                        "pm list packages | grep -i tmap",
                        "dumpsys package com.tmap.auto.byd | grep -A5 -i service",
                    ),
                    evidence = "thấy NaviService của com.tmap.auto.byd (nếu TMap có cài) hoặc bất kỳ package nào khác host cùng interface",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.READ_ONLY,
                    fieldNote = "RE 2026-07-29 (jadx-tmap com/tmap/auto/byd/NaviService.java + com/byd/naviserviceapi/*): TMap TỰ " +
                        "làm server (onBind trả Stub), tức BYD side bind VÀO TMap, không phải ngược lại — ClusterNav không thể " +
                        "dùng contract này để đẩy dữ liệu hộ VietMap trừ khi tự host y hệt interface này VÀ có gì đó phía BYD " +
                        "chịu bind tới (chưa biết có hardcode package/class hay không). Chỉ dùng candidate này để XÁC NHẬN sự " +
                        "tồn tại, không phải để khai thác ngay.",
                ),
                StepCandidate(
                    id = "probe.vehiclesettings-installed",
                    purpose = "Xác nhận com.byd.vehiclesettings (chủ công tắc HUD thật) có cài trên xe test",
                    commands = listOf(
                        "pm list packages | grep -i vehiclesettings",
                        "dumpsys package com.byd.vehiclesettings | grep -E 'versionName|versionCode'",
                    ),
                    evidence = "thấy package + versionName/versionCode",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.READ_ONLY,
                    fieldNote = "Tiền đề bắt buộc trước khi thử bất kỳ candidate nào của CarFeature.HUD_SWITCH (step hud-probe).",
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
                    id = "sign-inject.sla-state-probe",
                    purpose = "ĐỌC (không ghi) trạng thái nhận diện biển báo hiện tại qua BYDAutoADASDevice — bước bắt buộc TRƯỚC mọi ghi",
                    commands = listOf(
                        "CLASSPATH=/data/local/tmp/navopen.jar app_process /system/bin com.byd.navopen.NavOpen getraw adas 31600025",
                    ),
                    evidence = "trả về một trong 0(tắt)/1(fusion)/2(vision)/3(nv-only)/4(defect), không phải lỗi/exception",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.READ_ONLY,
                    fieldNote = "RE 2026-07-29 (carsettings-apk TrafficSign.java + jadx-tmap BYDAutoADASDevice.java): feature-id " +
                        "ADAS_SLA_STATE=0x31600025=828375077, đối chiếu chéo giữa BYDAutoFeatureIds thật (firmware) và call site " +
                        "TrafficSign.java khớp nhau. Đây là CÔNG TẮC BẬT/TẮT CẢ TÍNH NĂNG TSR (5 giá trị enum), KHÔNG PHẢI nơi " +
                        "ghi một con số km/h tuỳ ý — đừng kỳ vọng set giá trị này = hiện được số giới hạn tốc độ tuỳ chọn.",
                ),
                StepCandidate(
                    id = "sign-inject.sla-state-toggle",
                    purpose = "Bật/tắt toàn bộ tính năng TSR qua ADAS_SLA_STATE — xem có ảnh hưởng gì tới cụm/HUD không",
                    commands = listOf(
                        "CLASSPATH=/data/local/tmp/navopen.jar app_process /system/bin com.byd.navopen.NavOpen setraw adas 31600025 1",
                    ),
                    evidence = "NHÌN cụm/HUD: biểu tượng TSR (nếu có) bật/tắt theo; đọc lại bằng sign-inject.sla-state-probe để xác nhận giá trị đã ghi",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.MAY_DISRUPT_DRIVER,
                    fieldNote = "Toggle một tính năng an toàn thật (dù chỉ on/off) trong lúc xe đang chạy — chỉ thử khi đỗ ở lần đầu, " +
                        "và trả lại giá trị đọc được từ sign-inject.sla-state-probe ngay sau khi quan sát xong.",
                ),
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
        // Câu hỏi chặn đường app: khi nào được phát 30,16,35?
        //
        // CẬP NHẬT 2026-07-29: điều kiện "durable envelope pristine epoch 0" đã bị bỏ khỏi
        // CastColdBootstrapPreflight.inspect — epoch 0 trước giờ chỉ TRÙNG với "chưa ghi gì", không phải
        // sự thật cần kiểm. Nay V2 phát chuỗi này mỗi khi (a) envelope không còn ghi gì
        // (stableSession/transaction/stopRequested/pendingIntent/adjustmentDraft/pendingUiRollback đều
        // rỗng) VÀ (b) dumpsys display KHÔNG tìm thấy display cụm nào. Nếu display cụm đã có mà đang
        // trống thì adopt (không phát lại); nếu nó đang có task thì preflight chặn hẳn. Nghĩa là ca nguy
        // hiểm mà V1 cảnh báo (phát lại lúc cụm ĐANG có app) vẫn không thể xảy ra qua đường app.
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

        // ── Kính lái thật (HUD) ──────────────────────────────────────────────────────────────────────
        //
        // RE 2026-07-29: chuỗi gọi thật từ com.byd.vehiclesettings đã lần ra tới tận ranh giới stub —
        // xem KDoc của CarFeature.HUD_SWITCH. TẤT CẢ candidate dưới đây là lệnh app_process/navopen-v2.jar
        // (reflection thuần, không cần sửa gì trong ClusterNav) — KHÔNG PHẢI `service call` thô, vì
        // BYDAutoSettingDevice.getInstance() là singleton trong-tiến-trình, không phải Binder service tên
        // riêng gọi được qua ServiceManager.
        CarStep(
            id = "hud-probe",
            feature = CarFeature.HUD_SWITCH,
            purpose = "Đọc xem xe test có kính lái vật lý không, rồi mới thử bật/tắt/đổi nội dung",
            precondition = "đã xác nhận com.byd.vehiclesettings có cài (probe.vehiclesettings-installed); đã push navopen-v2.jar",
            candidates = listOf(
                StepCandidate(
                    id = "hud.config-read",
                    purpose = "ĐỌC (không ghi) SET_HUD_CONFIG — 0/không có field=không có HUD, 1=W-mode (kính lái thường), 2=AR-mode",
                    commands = listOf(
                        "CLASSPATH=/data/local/tmp/navopen.jar app_process /system/bin com.byd.navopen.NavOpen getraw setting 38B00015",
                    ),
                    evidence = "trả về 0, 1 hoặc 2 mà không lỗi — 0 nghĩa là BƯỚC DỪNG LẠI Ở ĐÂY, xe không có HUD để thử tiếp",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.READ_ONLY,
                    fieldNote = "RE 2026-07-29 (carsettings-apk HudFuncVisibleUtils.java + firmware Setting.java): feature-id " +
                        "SET_HUD_CONFIG=0x38B00015, đọc-only theo đúng thiết kế của com.byd.vehiclesettings — app đó cũng chỉ " +
                        "đọc field này để QUYẾT ĐỊNH có hiện màn cài đặt HUD hay không, không bao giờ ghi vào nó.",
                ),
                StepCandidate(
                    id = "hud.switch-feedback-read",
                    purpose = "ĐỌC trạng thái công tắc HUD hiện tại (1=đang bật/2=đang tắt) trước khi đổi",
                    commands = listOf(
                        "CLASSPATH=/data/local/tmp/navopen.jar app_process /system/bin com.byd.navopen.NavOpen getraw setting 38B0001C",
                    ),
                    evidence = "trả về 1 hoặc 2",
                    verdictSource = VerdictSource.MEASURED,
                    risk = CandidateRisk.READ_ONLY,
                    fieldNote = "feature-id SET_HUD_SWITCH_STATUS_FEEDBACK=0x38B0001C (RE 2026-07-29, cùng nguồn với hud.config-read). " +
                        "Đọc trước để biết giá trị trả về sau khi ghi hud.switch-on/off có đúng là đã đổi hay không.",
                ),
                StepCandidate(
                    id = "hud.switch-on",
                    purpose = "Bật công tắc HUD (nếu hud.config-read xác nhận có kính lái)",
                    commands = listOf(
                        "CLASSPATH=/data/local/tmp/navopen.jar app_process /system/bin com.byd.navopen.NavOpen setraw setting 4C10E023 1",
                    ),
                    evidence = "NHÌN kính lái vật lý: có hiện gì không; đọc lại bằng hud.switch-feedback-read xem có đổi thành 1 không",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.REVERSIBLE,
                    fieldNote = "RE 2026-07-29 (HudSwitchModel.java: true->mcuState=1). Chuỗi gọi: UI toggle -> HudSwitchModel -> " +
                        "HalSetter.set(BYDAutoSettingDevice.class, SET_HUD_SWITCH_SET, 1) -> BYDAutoSettingDevice.getInstance(ctx)" +
                        ".set(int[]{0x4C10E023}, EventValue{intValue=1}) — CÙNG hình dạng lệnh BydHal.kt đã dùng cho INSTRUMENT, " +
                        "khác class (SETTING). CHƯA xác nhận quyền set() có được cấp cho tiến trình gọi (app_process qua adb " +
                        "shell = uid 2000; ClusterNav app thật chạy uid khác, có thể bị chặn khác nhau).",
                ),
                StepCandidate(
                    id = "hud.switch-off",
                    purpose = "Tắt lại công tắc HUD — dùng để hoàn tác hud.switch-on hoặc tự nó là một phép thử độc lập",
                    commands = listOf(
                        "CLASSPATH=/data/local/tmp/navopen.jar app_process /system/bin com.byd.navopen.NavOpen setraw setting 4C10E023 2",
                    ),
                    evidence = "kính lái tắt hẳn (nếu đang bật); đọc lại bằng hud.switch-feedback-read xem có đổi thành 2 không",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.REVERSIBLE,
                    fieldNote = "mcuState=2 (RE 2026-07-29, HudSwitchModel.java: false->mcuState=2 — LƯU Ý không phải 0).",
                ),
                StepCandidate(
                    id = "hud.nav-content-toggle-on",
                    purpose = "Bật cờ hiển thị NỘI DUNG dẫn đường trên HUD (khác với công tắc HUD tổng)",
                    commands = listOf(
                        "CLASSPATH=/data/local/tmp/navopen.jar app_process /system/bin com.byd.navopen.NavOpen setraw setting 4C10E03A 1",
                    ),
                    evidence = "sau khi bật cờ này VÀ HUD đã bật (hud.switch-on) VÀ đang có nav thật (NavOpen full/open), xem HUD có hiện hướng rẽ không",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.REVERSIBLE,
                    fieldNote = "RE 2026-07-29 (HudOptionDisplayModel.java): SET_DYNAMIC_NAVI_FUNCTION_STATUS_SET=0x4C10E03A. " +
                        "CẢNH BÁO chưa kiểm chứng: đây chỉ là cờ 'CÓ hiện nav trên HUD hay không', KHÔNG có bằng chứng nó đổi " +
                        "NGUỒN dữ liệu — rất có thể chỉ gate cho app nav OEM riêng, không liên quan gì tới ghi INSTRUMENT_GUIDE_INFO_SIMPLE_SET " +
                        "mà BydHal.kt/navopen.jau đang dùng. Đừng mặc định 'bật cờ này thì làn cụm tự nhảy sang HUD'.",
                ),
                StepCandidate(
                    id = "hud.adas-content-toggle-on",
                    purpose = "Bật cờ hiển thị cảnh báo ADAS trên HUD",
                    commands = listOf(
                        "CLASSPATH=/data/local/tmp/navopen.jar app_process /system/bin com.byd.navopen.NavOpen setraw setting 4C10E030 1",
                    ),
                    evidence = "NHÌN HUD sau khi bật — icon ADAS (nếu có cảnh báo đang active) có hiện không",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.REVERSIBLE,
                    fieldNote = "RE 2026-07-29 (HudOptionDisplayModel.java): SET_SAFE_DRIVING_ASSIST_STATUS_SET=0x4C10E030. " +
                        "Nhóm icon ADAS chung (FCW/LDW...), không riêng cho biển báo tốc độ.",
                ),
            ),
        ),

        // ── Toggle phụ trên cụm (opcode AutoContainer chưa có candidate) ────────────────────────────
        //
        // RE 2026-07-29 (dashcast-src/ui/diag/DiagActivity.java, đối chiếu app com.byd.clusterdebug của
        // chính BYD, xác nhận đa đời máy DL3/Di4/DL5/DL6): các opcode 2/3/12/13 khác opcode chiếu/teardown
        // đã biết (16/18/30/31/35). 47/48 là build cũ hơn, CHƯA xác nhận còn tồn tại trên ROM hiện tại.
        CarStep(
            id = "cluster-overlay-toggles",
            feature = CarFeature.CLUSTER_CAST,
            purpose = "Bật/tắt lớp phủ ADAS và đèn cảnh báo trên cụm — độc lập với đường chiếu app",
            precondition = "cụm đang hiện đồng hồ (không cần đang chiếu app nào)",
            candidates = listOf(
                StepCandidate(
                    id = "overlay.adas-window-show",
                    purpose = "Hiện cửa sổ ADAS 2D/3D của Qt cluster",
                    commands = listOf("service call {svc} 2 i32 1000 i32 12 s16 \"\""),
                    evidence = "cụm hiện thêm lớp phủ ADAS (radar/làn đường...)",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.REVERSIBLE,
                    fieldNote = "RE 2026-07-29 từ com.byd.clusterdebug (app chẩn đoán CHÍNH CHỦ của BYD), xác nhận qua nhiều đời máy.",
                ),
                StepCandidate(
                    id = "overlay.adas-window-hide",
                    purpose = "Ẩn lại cửa sổ ADAS vừa hiện",
                    commands = listOf("service call {svc} 2 i32 1000 i32 13 s16 \"\""),
                    evidence = "lớp phủ ADAS biến mất",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.REVERSIBLE,
                    fieldNote = "Cặp đôi trực tiếp với overlay.adas-window-show.",
                ),
                StepCandidate(
                    id = "overlay.warning-lamps-on",
                    purpose = "Bật SÁNG toàn bộ đèn cảnh báo trên cụm cùng lúc",
                    commands = listOf("service call {svc} 2 i32 1000 i32 2 s16 \"\""),
                    evidence = "mọi đèn cảnh báo (ắc quy, phanh tay, ABS...) sáng đồng loạt",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.MAY_DISRUPT_DRIVER,
                    fieldNote = "RE 2026-07-29, field-tested trên DL5 (owner tài liệu gốc): opcode 2 rồi đợi 3s rồi opcode 3. " +
                        "Chỉ thử khi ĐỖ — đèn cảnh báo giả có thể khiến người khác trong xe hoảng. LIVE-CONFIRMED 2026-07-29 " +
                        "trên Seal DL3: opcode 2 sáng thật vài chục cảnh báo cùng lúc trên cụm — nhưng xem SỰ CỐ THẬT ở " +
                        "overlay.warning-lamps-off, opcode 3 KHÔNG đảm bảo dọn sạch được những gì opcode 2 bật lên.",
                ),
                StepCandidate(
                    id = "overlay.warning-lamps-off",
                    purpose = "Tắt lại toàn bộ đèn cảnh báo vừa bật",
                    commands = listOf("service call {svc} 2 i32 1000 i32 3 s16 \"\""),
                    evidence = "mọi đèn cảnh báo tắt, cụm về trạng thái bình thường",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.REVERSIBLE,
                    fieldNote = "SỰ CỐ THẬT 2026-07-29 trên Seal DL3: gửi opcode 3 HAI LẦN + opcode 0 (video refresh, " +
                        "teardown.0-only) MỘT LẦN — vẫn còn sót icon ADAS + biển đường cấm trên cụm, và km/h giới hạn " +
                        "nhảy sai thành 60 (HUD vẫn đúng 30, hai màn lệch nhau). CHỈ tắt máy xe rồi khởi động lại (power " +
                        "cycle thật) mới dọn sạch hoàn toàn. KẾT LUẬN: opcode 3 KHÔNG đảm bảo là nghịch đảo sạch của " +
                        "opcode 2 như tên gọi — đừng coi cặp on/off này là hoàn tác được thuần bằng phần mềm. Trước khi " +
                        "thử lại overlay.warning-lamps-on, PHẢI sẵn sàng tắt/mở máy xe làm phương án dọn cuối cùng, và " +
                        "không nên đoán thêm opcode khác khi 3/0 đã không ăn — đúng bài học CLAUDE.md §5 (đường trả về " +
                        "phải có sẵn TRƯỚC khi đổi trạng thái, không phải đi tìm sau khi đã kẹt).",
                ),
                StepCandidate(
                    id = "overlay.adas-debug-legacy-on",
                    purpose = "Thử 'chế độ debug ADAS bí mật' từ build DashCast v0.3.2-alpha cũ — chưa rõ còn hoạt động trên ROM hiện tại không",
                    commands = listOf("service call {svc} 2 i32 1000 i32 47 s16 \"\""),
                    evidence = "quan sát khác biệt so với overlay.adas-window-show (nếu có, mới đáng ghi nhận là cơ chế riêng)",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.MAY_DISRUPT_DRIVER,
                    fieldNote = "RE 2026-07-29: chỉ thấy trong CHANGELOG.md lịch sử của DashCast (versionCode 68), KHÔNG còn " +
                        "trong source hiện tại của dashcast-src — có thể đã bị bỏ vì trùng/kém hơn opcode 12/13 mới hơn. Thử để " +
                        "loại trừ, không kỳ vọng khác biệt.",
                ),
                StepCandidate(
                    id = "overlay.adas-debug-legacy-off",
                    purpose = "Tắt lại nếu overlay.adas-debug-legacy-on có hiệu ứng nhìn thấy được",
                    commands = listOf("service call {svc} 2 i32 1000 i32 48 s16 \"\""),
                    evidence = "trở lại như trước overlay.adas-debug-legacy-on",
                    verdictSource = VerdictSource.HUMAN,
                    risk = CandidateRisk.REVERSIBLE,
                    fieldNote = "Chạy ngay sau overlay.adas-debug-legacy-on bất kể có thấy hiệu ứng hay không.",
                ),
            ),
        ),
    )

    fun step(id: String): CarStep? = steps.firstOrNull { it.id == id }

    fun candidate(id: String): Pair<CarStep, StepCandidate>? = steps.firstNotNullOfOrNull { step ->
        step.candidates.firstOrNull { it.id == id }?.let { step to it }
    }
}
