#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# on-car-navprobe.sh — TRACK 2: DÒ NGUỒN tín hiệu AUTONAVI trên xe (cho SẠCH)
#
# CÂU HỎI: app nào PHÁT broadcast `AUTONAVI_STANDARD_BROADCAST_SEND` (mang turn-by-turn:
#   NEXT_ROAD_NAME · SEG_REMAIN_DIS · ROUTE_REMAIN_DIS/TIME · icon rẽ) để đẩy lên cụm?
#
# PROTOCOL (xác nhận từ firmware decompiled `com/example/amapservice/AmapService.java`):
#   [App dẫn đường]──sendBroadcast(AUTONAVI_STANDARD_BROADCAST_SEND)──▶
#     [com.example.amapservice · AmapBroadReceiver = BÊN NHẬN]──sendNaviToCluster()──▶[cụm HAL]
#   ⇒ SENDER là APP DẪN ĐƯỜNG THẬT, KHÔNG phải amapservice (nó chỉ là cầu nối/receiver).
#   ⇒ Ứng viên sender: vn.vietmap.live (nghi chính), com.tmap.auto.byd (BYD map, nhánh mIsBydMap).
#   ⇒ KHÔNG phải: com.example.amapservice (receiver), Waze / CP / AA (chỉ video-surface).
#
# VÌ SAO CẦN SCRIPT (không chỉ nút CÔ LẬP trong app):
#   • Android GIẤU sender khỏi receiver ⇒ NavProbe chỉ ghi ⟨fg=…⟩ (proxy yếu). Script bổ sung:
#       (a) truy vết `dumpsys activity broadcasts` (đôi khi còn callerPackage) — bằng chứng TRỰC TIẾP;
#       (b) MA TRẬN CÔ LẬP có kiểm soát: tắt từng nhóm → xem AUTONAVI còn phát không — bằng chứng HÀNH VI;
#       (c) đếm bản ghi theo GIỜ CỦA XE (device time) ⇒ cắt lát theo pha, KHÔNG trộn (bài học 23/07).
#
# DÙNG:
#   ./on-car-navprobe.sh [adb-serial|ip:port]
#   vd:  ./on-car-navprobe.sh 10.x.x.x:5555          (IP xe — KHÔNG hardcode, repo public)
#        PHASE_SEC=120 ./on-car-navprobe.sh 10.x.x.x:5555   (kéo dài mỗi pha)
#        ADB=/path/to/adb ./on-car-navprobe.sh       (dùng thiết bị adb mặc định)
#
# YÊU CẦU: đã cài bản DEBUG (com.byd.clusternav.debug) + bật USB debug + adb tcp 5555; cùng mạng.
#          Máy dò đã ĐANG GHI (mở app → BẮT ĐẦU DÒ, hoặc để auto-arm bật sẵn) + đã cấp 2 quyền.
#
# ⚠ AN TOÀN: các pha cô lập sẽ `am force-stop` app dẫn đường → MẤT dẫn đường tạm thời (mở lại là chạy
#   tiếp, KHÔNG mất dữ liệu). Nên để NGƯỜI NGỒI CẠNH thao tác, hoặc làm lúc dừng đèn đỏ/đỗ xe.
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail

ADB="${ADB:-adb}"
DEV="${1:-${ADB_TARGET:-}}"
PKG="com.byd.clusternav.debug"                                   # ★ bản DEBUG (khớp applicationIdSuffix .debug)
NAVDIR="/sdcard/Android/data/$PKG/files/navprobe"                # ★ getExternalFilesDir ở bản debug (RT2.4)
OUT="${OUT_DIR:-./on-car-navprobe-$(date +%Y%m%d-%H%M%S)}"
PHASELOG="$OUT/phases.log"
PHASE_SEC="${PHASE_SEC:-90}"
mkdir -p "$OUT"

# ── Ứng viên SENDER của AUTONAVI (đúng TARGETS máy dò + BYD map) ──
VIETMAP="vn.vietmap.live"        # Vietmap Live — nghi chính
WAZE="com.waze"                  # Waze — video-surface, không nói AUTONAVI (đối chứng âm)
BYDMAP="com.tmap.auto.byd"       # BYD map (BydAutoTMap) — nhánh mIsBydMap
CP="com.byd.carplay.ui"          # CarPlay wrapper — video-surface
AA="com.byd.androidauto"         # Android Auto wrapper — video-surface
BRIDGE="com.example.amapservice" # ★ RECEIVER/cầu nối — TUYỆT ĐỐI KHÔNG force-stop coi như "nguồn"
# nhóm được phép tắt khi cô lập (KHÔNG bao giờ gồm $BRIDGE)
OTHERS_OF_VIETMAP=("$WAZE" "$BYDMAP" "$CP" "$AA")

adbx() { if [ -n "$DEV" ]; then "$ADB" -s "$DEV" "$@"; else "$ADB" "$@"; fi; }
c_pass=0; c_fail=0; c_warn=0
ok()   { echo "  ✅ $*"; c_pass=$((c_pass+1)); }
bad()  { echo "  ❌ $*"; c_fail=$((c_fail+1)); }
warn() { echo "  ⚠  $*"; c_warn=$((c_warn+1)); }
info() { echo "  ℹ  $*"; }
hr()   { echo "──────────────────────────────────────────────────────────"; }
pause(){ echo; read -r -p "⏸  $* → xong bấm Enter…" _; }
int()  { local v="${1//[^0-9]/}"; echo "${v:-0}"; }

# giờ CỦA XE (khớp mốc thời gian trong file navprobe — tránh lệch đồng hồ Mac↔xe)
dev_time() { adbx shell "date +%H:%M:%S" 2>/dev/null | tr -d '\r'; }

READ="shell"   # cách đọc file navprobe: shell (cat/grep trực tiếp) | pull (kéo về rồi grep)

# đường dẫn file navprobe MỚI NHẤT trên xe (head -n 1: toybox trên xe không chắc hỗ trợ 'head -1')
latest_file() { adbx shell "ls -t $NAVDIR/*.txt 2>/dev/null | head -n 1" 2>/dev/null | tr -d '\r'; }

# đếm số bản ghi AUTONAVI_STANDARD_BROADCAST_SEND trong file mới nhất (chỉ RECORD, không tính dòng liệt kê action)
count_send() {
  local f n
  if [ "$READ" = "shell" ]; then
    f="$(latest_file)"; [ -z "$f" ] && { echo 0; return; }
    n="$(adbx shell "grep -Fc 'BROADCAST] AUTONAVI_STANDARD_BROADCAST_SEND' '$f'" 2>/dev/null | tr -d '\r')"
  else
    rm -rf "$OUT/_poll"; adbx pull "$NAVDIR" "$OUT/_poll" >/dev/null 2>&1
    f="$(ls -t "$OUT/_poll"/*.txt 2>/dev/null | head -1)"; [ -z "$f" ] && { echo 0; return; }
    n="$(grep -Fc 'BROADCAST] AUTONAVI_STANDARD_BROADCAST_SEND' "$f" 2>/dev/null)"
  fi
  int "${n:-0}"
}

# tiến trình nav đang chạy (đối chiếu: khi AUTONAVI phát thì app nào còn sống)
running_navs() {
  adbx shell "ps -A 2>/dev/null || ps" 2>/dev/null | tr -d '\r' \
    | grep -iE "vietmap|waze|tmap|amap|carplay|androidauto" | awk '{print $NF}' | sort -u | tr '\n' ' '
}

# ghi 1 mốc pha vào phases.log (giờ XE | nhãn | tổng send | app nav còn sống) — trả về COUNT (stdout) để tính delta.
# Dòng người-đọc đẩy ra STDERR (tee >&2) để `$(phase_mark …)` CHỈ bắt được con số, không lẫn dòng text.
phase_mark() {
  local label="$1" dt navs cnt
  dt="$(dev_time)"; navs="$(running_navs)"; cnt="$(count_send)"
  printf '%s | %-26s | send_total=%-5s | running: %s\n' "$dt" "$label" "$cnt" "${navs:-none}" | tee -a "$PHASELOG" >&2
  echo "$cnt"
}

cleanup() { rm -rf "$OUT/_poll" 2>/dev/null || true; }
trap cleanup EXIT

# ═══════════════════════════════════════════════════════════════════════════
echo "ClusterNav — DÒ NGUỒN AUTONAVI (Track 2, bản debug)"; hr
[ -n "$DEV" ] && adbx connect "$DEV" >/dev/null 2>&1 || true

# ── 0. PREFLIGHT ──
echo "[0] Preflight"
if ! adbx shell "echo ok" >/dev/null 2>&1; then bad "KHÔNG nối được xe (adb). Kiểm IP/mạng/USB-debug."; echo "Tổng: PASS=$c_pass FAIL=$c_fail"; exit 1; fi
ok "adb nối được (giờ xe: $(dev_time))"

VER="$(adbx shell "dumpsys package $PKG | grep versionName" | head -1 | tr -d '\r ')"
if [ -z "$VER" ]; then
  bad "CHƯA cài bản debug $PKG — cài: adb install -r apks/ClusterNav-debug.apk"
  echo "Tổng: PASS=$c_pass FAIL=$c_fail"; exit 1
fi
echo "$VER" | grep -qi "debug" && ok "bản DEBUG đã cài ($VER)" \
  || warn "$PKG có mặt nhưng versionName KHÔNG chứa 'debug' ($VER) — kiểm đúng build .debug chưa"

# cách đọc navprobe: thử shell trước (Android 10 cho shell đọc /sdcard/Android/data), hụt thì dùng pull.
# Redirect phía HOST (bao cả lệnh adbx) để chắc chắn không rò output dù device shell xử lý ra sao.
if adbx shell "test -r $NAVDIR && ls $NAVDIR/*.txt" >/dev/null 2>&1; then
  READ="shell"; ok "đọc navprobe qua shell (grep trực tiếp trên xe)"
else
  READ="pull"; warn "shell không đọc được $NAVDIR → chuyển sang 'adb pull' (chậm hơn, vẫn chạy)"
fi

# quyền 2 kênh chính (thiếu là cả chuyến công cốc)
ACC="$(adbx shell "settings get secure enabled_accessibility_services" 2>/dev/null | tr -d '\r')"
echo "$ACC" | grep -qi "clusternav.debug/.*NavProbe" && ok "quyền ĐỌC MÀN HÌNH (accessibility) đã bật — kênh 3 sống" \
  || warn "accessibility máy dò CHƯA bật (kênh 3 câm) → mở app DEBUG bấm 'Cấp quyền ĐỌC MÀN HÌNH', hoặc app tự cấp qua dadb"
NL="$(adbx shell "settings get secure enabled_notification_listeners" 2>/dev/null | tr -d '\r')"
echo "$NL" | grep -qi "clusternav.debug/" && ok "quyền ĐỌC THÔNG BÁO đã bật — kênh 1 sống" \
  || warn "notification listener của máy dò CHƯA bật (kênh 1 câm)"

# liệt kê ứng viên + bridge có mặt không
echo "  — app liên quan trên xe —"
for p in "$VIETMAP" "$WAZE" "$BYDMAP" "$CP" "$AA" "$BRIDGE"; do
  if adbx shell "pm path $p" 2>/dev/null | grep -q "package:"; then
    tag=""; [ "$p" = "$BRIDGE" ] && tag="  ← RECEIVER/cầu nối (KHÔNG tắt)"
    info "cài: $p$tag"
  fi
done
adbx shell "pm path $BRIDGE" 2>/dev/null | grep -q "package:" \
  && ok "bridge $BRIDGE có mặt (nơi nhận AUTONAVI → đẩy cụm)" \
  || warn "KHÔNG thấy $BRIDGE — cụm có thể nhận nav qua đường khác; vẫn đo được nguồn phát"

# ── 1. CHỐNG TRỘN LOG CŨ/MỚI (bài học 23/07 — gốc làm kết luận cũ vô nghĩa) ──
echo; hr; echo "[1] Chống TRỘN phiên (session-key = versionName@boot_count → file MỚI mỗi lần nổ máy)"
BOOT="$(adbx shell "settings get global boot_count" 2>/dev/null | tr -d '\r')"
LF="$(latest_file)"
if [ -z "$LF" ]; then
  warn "chưa có file navprobe nào — mở app DEBUG → BẮT ĐẦU DÒ để tạo phiên mới, rồi chạy lại script"
else
  HDR="$(adbx shell "head -n 20 '$LF'" 2>/dev/null | tr -d '\r')"
  SESS="$(echo "$HDR" | grep -m1 'phiên' | sed -E 's/^[^:]*: *//' | awk '{print $1}')"
  BON="$(echo "$HDR" | grep -m1 'bật lúc' | sed -E 's/^[^:]*: *//')"
  BK="${SESS##*@}"   # phần bootKey sau '@' (vd "b7" hoặc "t169…")
  info "file mới nhất : $(basename "$LF")"
  info "phiên (file)  : ${SESS:-?}   · bật lúc: ${BON:-?}"
  info "boot_count XE : ${BOOT:-?}   → phiên khớp phải là …@b${BOOT:-?}"
  if [ -n "$BOOT" ] && [ "$BK" = "b${BOOT}" ]; then
    ok "phiên KHỚP lần-boot hiện tại → file KHÔNG trộn phiên cũ, an toàn để đo"
  elif [ -z "$BOOT" ]; then
    warn "xe không trả boot_count (ROM lạ) — máy dò fallback theo giờ-khởi-động; kiểm 'bật lúc' phải là HÔM NAY"
  else
    warn "phiên trong file (bootKey=$BK) KHÁC boot hiện tại (b$BOOT) → FILE CŨ."
    echo "        → Toggle Máy dò (TẮT rồi BẬT lại) trong app để mở FILE MỚI cho phiên này, rồi chạy lại [1]."
  fi
fi

# ── 2. TRUY VẾT SENDER (bằng chứng TRỰC TIẾP nếu AM còn giữ callerPackage) ──
echo; hr; echo "[2] Truy vết sender qua 'dumpsys activity broadcasts' (Android giấu sender, nhưng AM history đôi khi còn caller)"
adbx shell "dumpsys activity broadcasts" > "$OUT/broadcasts-history.txt" 2>&1 || true
if grep -q "AUTONAVI_STANDARD_BROADCAST" "$OUT/broadcasts-history.txt"; then
  ok "có bản ghi AUTONAVI trong AM broadcast history → lưu $OUT/broadcasts-history.txt"
  echo "  — dòng có ích (caller/record) —"
  grep -nE "AUTONAVI_STANDARD_BROADCAST_SEND|callerPackage=|caller=" "$OUT/broadcasts-history.txt" \
    | grep -iE "AUTONAVI|caller" | head -12 | sed 's/^/    /'
  CALLER="$(grep -A6 "AUTONAVI_STANDARD_BROADCAST_SEND" "$OUT/broadcasts-history.txt" \
            | grep -m1 -oE "callerPackage=[^ ]+" | sed 's/callerPackage=//')"
  [ -n "$CALLER" ] && ok "★ callerPackage lộ ra: $CALLER  (đối chiếu với kết quả cô lập bên dưới)"
else
  info "chưa thấy AUTONAVI trong history (chưa dẫn đường / history đã cuộn). Chạy lại mục [2] lúc ĐANG dẫn đường."
fi

# ── 3. MA TRẬN CÔ LẬP — chốt nguồn bằng HÀNH VI ──
echo; hr; echo "[3] MA TRẬN CÔ LẬP (bằng chứng hành vi — mạnh hơn ⟨fg⟩)"
echo "    Mỗi pha ~${PHASE_SEC}s: LÁI CÓ DẪN ĐƯỜNG để sinh tín hiệu; script đếm số AUTONAVI SEND tăng thêm."
echo "    ⚠ force-stop = mất dẫn đường tạm thời, KHÔNG mất dữ liệu. Nên để người ngồi cạnh thao tác / lúc dừng."

phase_mark 'P0 baseline' >/dev/null   # ghi mốc nền vào phases.log; con số không cần giữ (dòng người-đọc vẫn hiện qua stderr)

pause "PHA A — ĐỦ APP: mở Vietmap DẪN ĐƯỜNG (để các app khác như thường). Sẵn sàng thì Enter rồi LÁI"
PA_S="$(phase_mark 'PA all-running start')"
echo "  … đang đo ĐỦ APP trong ${PHASE_SEC}s, cứ lái có dẫn đường …"; sleep "$PHASE_SEC"
PA_E="$(phase_mark 'PA all-running end')"

pause "PHA B — CHỈ VIETMAP: script sẽ force-stop Waze/BYD-map/CP/AA (GIỮ Vietmap + bridge). Bấm Enter khi Vietmap đang dẫn"
for p in "${OTHERS_OF_VIETMAP[@]}"; do adbx shell "am force-stop $p" >/dev/null 2>&1; done
ok "đã force-stop: ${OTHERS_OF_VIETMAP[*]} — giờ chỉ còn Vietmap phát (nếu có)"
PB_S="$(phase_mark 'PB only-vietmap start')"
echo "  … đang đo CHỈ VIETMAP trong ${PHASE_SEC}s, lái tiếp có dẫn đường …"; sleep "$PHASE_SEC"
PB_E="$(phase_mark 'PB only-vietmap end')"

pause "PHA C — TẮT LUÔN VIETMAP giữa lúc đang dẫn (QUYẾT ĐỊNH). Bấm Enter để script force-stop Vietmap"
adbx shell "am force-stop $VIETMAP" >/dev/null 2>&1
ok "đã force-stop $VIETMAP — nếu AUTONAVI DỪNG hẳn ⇒ Vietmap là nguồn; nếu VẪN chạy ⇒ nguồn là hệ thống"
PC_S="$(phase_mark 'PC vietmap-killed start')"
echo "  … theo dõi ${PHASE_SEC}s xem AUTONAVI còn phát không (KHÔNG mở lại Vietmap) …"; sleep "$PHASE_SEC"
PC_E="$(phase_mark 'PC vietmap-killed end')"

# ── 4. KÉO DỮ LIỆU SẠCH + tổng hợp ⟨fg⟩ ──
echo; hr; echo "[4] Kéo navprobe SẠCH về + tổng hợp"
rm -rf "$OUT/navprobe"; adbx pull "$NAVDIR" "$OUT/navprobe" >/dev/null 2>&1 \
  && ok "đã kéo navprobe → $OUT/navprobe" || warn "pull navprobe hụt (kiểm quyền/đường dẫn)"
LF_LOCAL="$(ls -t "$OUT/navprobe"/*.txt 2>/dev/null | head -1)"
if [ -n "$LF_LOCAL" ]; then
  grep -F 'BROADCAST] AUTONAVI_STANDARD_BROADCAST_SEND' "$LF_LOCAL" 2>/dev/null \
    | sed -nE 's/.*(⟨fg=[^⟩]*⟩).*/\1/p' | sort | uniq -c | sort -rn > "$OUT/autonavi-fg-tally.txt" || true
  if [ -s "$OUT/autonavi-fg-tally.txt" ]; then
    echo "  ⟨fg=…⟩ đi kèm mỗi AUTONAVI SEND (proxy — đối chiếu, KHÔNG thay isolation):"
    sed 's/^/    /' "$OUT/autonavi-fg-tally.txt" | head
  else
    info "chưa có bản ghi AUTONAVI SEND nào trong file (đo lúc ĐANG dẫn để có dữ liệu)"
  fi
fi

# ── 5. PHÁN QUYẾT (bảng quyết định từ delta các pha) ──
echo; hr; echo "[5] PHÁN QUYẾT — dựa DELTA số AUTONAVI SEND mỗi pha (isolation)"
dA=$(( $(int "$PA_E") - $(int "$PA_S") ))
dB=$(( $(int "$PB_E") - $(int "$PB_S") ))
dC=$(( $(int "$PC_E") - $(int "$PC_S") ))
echo "  ΔA (đủ app)       : $dA bản ghi"
echo "  ΔB (chỉ Vietmap)  : $dB bản ghi"
echo "  ΔC (giết Vietmap) : $dC bản ghi"
echo "  (phases.log đầy đủ: $PHASELOG)"
echo
if [ "$dA" -le 0 ] && [ "$dB" -le 0 ]; then
  warn "CHƯA đủ tín hiệu (ΔA=$dA, ΔB=$dB). Có THẬT SỰ đang dẫn đường không? Kênh broadcast chỉ bắt được lúc đang dẫn."
  echo "     → Kiểm mục [1] (phiên đúng chưa) + quyền + chạy lại, đảm bảo Vietmap ĐANG DẪN suốt các pha."
elif [ "$dB" -gt 0 ] && [ "$dC" -le 0 ]; then
  echo "  ✅ KẾT LUẬN: NGUỒN = Vietmap ($VIETMAP)."
  echo "     Còn AUTONAVI khi CHỈ Vietmap (ΔB=$dB) VÀ DỪNG hẳn khi giết Vietmap (ΔC=$dC). Đường sáng — viết bộ rút dữ liệu."
elif [ "$dC" -gt 0 ]; then
  echo "  ⚠ KẾT LUẬN: NGUỒN = HỆ THỐNG, KHÔNG phải Vietmap."
  echo "     AUTONAVI VẪN phát sau khi giết Vietmap (ΔC=$dC). Nghi nav hệ thống (GAODE nền) hoặc $BRIDGE tự sinh."
  echo "     → Chạy PHA D: đóng hết, chỉ mở BYD-map ($BYDMAP) dẫn đường, đo lại; và soi callerPackage mục [2]."
elif [ "$dB" -le 0 ] && [ "$dA" -gt 0 ]; then
  echo "  ⚠ KẾT LUẬN: nguồn NẰM TRONG nhóm bị tắt ở PHA B (Waze/BYD-map/CP/AA) — nhiều khả năng BYD-map ($BYDMAP)."
  echo "     Có AUTONAVI khi đủ app (ΔA=$dA) nhưng TẮT khi chỉ còn Vietmap (ΔB=$dB) ⇒ Vietmap KHÔNG phải nguồn."
  echo "     → Chạy PHA D: chỉ mở BYD-map dẫn đường, đo lại để xác nhận."
else
  warn "kết quả không rơi vào ô rõ ràng (ΔA=$dA ΔB=$dB ΔC=$dC) — đọc $PHASELOG + $OUT/navprobe thủ công."
fi

# ── PHA D (tuỳ chọn, thủ công) ──
echo
echo "  ┄ PHA D (tuỳ chọn) — chỉ BYD-map: đóng hết nav, mở '$BYDMAP' dẫn đường, rồi chạy lại script từ [2]."
echo "     (không tự động vì cần bạn chủ động khởi tạo tuyến trên BYD-map)"

# ── TỔNG KẾT ──
echo; hr; echo "TỔNG KẾT: ✅ PASS=$c_pass · ❌ FAIL=$c_fail · ⚠ WARN=$c_warn"
echo "Kết quả + snapshot: $OUT/"
echo "  • phases.log            — mốc thời gian XE + số AUTONAVI mỗi pha (cắt lát sạch)"
echo "  • navprobe/             — file máy dò kéo về (grep AUTONAVI để đọc turn-by-turn)"
echo "  • broadcasts-history.txt— dumpsys activity broadcasts (truy vết caller)"
echo "  • autonavi-fg-tally.txt — ⟨fg=…⟩ đi kèm mỗi AUTONAVI (proxy)"
[ "$c_fail" -eq 0 ] && exit 0 || exit 1
