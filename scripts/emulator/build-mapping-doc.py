#!/usr/bin/env python3
"""GMaps -> ClusterNav arrow mapping REVIEW doc, VISUAL.
Each row shows THREE real images side by side: GMaps notification arrow (emulator capture) |
what the STRIP draws (real cluster photo of CAN=TurnIdMapToCAN[toAmapIcon]) | what the HUD/centre
draws (real cluster photo of CAN=toHudIcon). Plus §B: full CAN 1..49 with the real cluster photo.
Cluster photos = owner's on-car icon sweep (../icon maps). Doc-only."""
import base64, os, sys
ARROWS = sys.argv[1] if len(sys.argv) > 1 else "/tmp/corpus_vis"     # GMaps arrow silhouettes (png)
GLYPH  = sys.argv[2] if len(sys.argv) > 2 else "/tmp/canglyph"       # real cluster photos CAN<n>.jpg
OUT    = sys.argv[3] if len(sys.argv) > 3 else "docs/diagnostics/gmaps-arrow-mapping-review-2026-08-17.html"

def _img(path, cls):
    if not path or not os.path.exists(path): return ""
    mime = "png" if path.endswith(".png") else "jpeg"
    b = base64.b64encode(open(path, "rb").read()).decode()
    return f'<img class="{cls}" src="data:image/{mime};base64,{b}">'
def arrow(name): return _img(os.path.join(ARROWS, name) if name else "", "arw")
def canimg(can): return _img(os.path.join(GLYPH, f"CAN{can}.jpg") if isinstance(can, int) else "", "cph")

# firmware AMAP NEW_ICON -> CAN (AmapService.TurnIdMapToCAN[0..28]); strip shows this CAN's glyph
TMAP = [0,0,1,2,3,5,7,8,9,11,45,13,24,46,47,48,49,14,23,10,12,15,18,20,22,16,17,19,21]
def strip_can(amap): return TMAP[amap] if isinstance(amap,int) and 0<=amap<len(TMAP) else None

AMAP = {2:"rẽ trái",3:"rẽ phải",4:"chếch trái",5:"chếch phải",6:"ngoặt trái",7:"ngoặt phải",8:"quay đầu",9:"đi thẳng",10:"waypoint",11:"vòng xuyến",12:"ra vòng xuyến",13:"trạm nghỉ",14:"thu phí",15:"đích",16:"hầm",19:"quay đầu phải",20:"tiếp tục"}
CANM= {1:"rẽ trái",2:"rẽ phải",3:"chếch trái",5:"chếch phải",7:"ngoặt trái",8:"ngoặt phải",9:"quay đầu",10:"quay đầu phải",11:"đi thẳng",12:"tiếp tục",13:"vào vòng xuyến",15:"vòng xuyến ra TRÁI",18:"vòng xuyến ra PHẢI",20:"vòng xuyến thẳng",22:"vòng xuyến U",24:"ra khỏi vòng xuyến",45:"waypoint",46:"trạm nghỉ",47:"thu phí",48:"điểm đến",49:"hầm"}

# §A rows: (group, desc, gmaps_arrow_file, amap_newicon, hud_can, verdict, note)
OK="MATCH"; GEN="MATCH*"; NA="chưa bắt sống"
sit=[
 ("Rẽ cơ bản","Rẽ trái 90°","normal_left.png",2,1,OK,"đã bắt sống"),
 ("Rẽ cơ bản","Rẽ phải 90°","normal_right.png",3,2,OK,"đã bắt sống"),
 ("Rẽ cơ bản","Chếch/giữ trái","slight_left.png",4,3,OK,"đã bắt sống"),
 ("Rẽ cơ bản","Chếch/giữ phải","slight_right.png",5,5,OK,"đã bắt sống"),
 ("Rẽ cơ bản","Đi thẳng","straight.png",9,11,OK,"đã bắt sống"),
 ("Rẽ cơ bản","Xuất phát (depart)","depart.png",9,11,OK,"đã bắt sống → đi thẳng"),
 ("Rẽ gắt","Ngoặt trái (sharp)","sharp_left.png",6,7,OK,"đã bắt sống"),
 ("Rẽ gắt","Ngoặt phải (sharp)","sharp_right.png",7,8,OK,"đã bắt sống"),
 ("Quay đầu","Quay đầu (trái, RHT)","u_turn_left.png",8,9,OK,"đã bắt sống"),
 ("Cao tốc","Nhập làn (merge)","merge.png",9,11,GEN,"đã bắt sống; 0..28 không có glyph merge → ĐI THẲNG"),
 ("Cao tốc","Fork / ramp / keep","fork_right.png",5,5,GEN,"đã bắt sống (fork phải); ramp/fork/keep → chếch"),
 ("Vòng xuyến","Đi thẳng qua vòng xuyến","roundabout_ccw_straight.png",11,20,OK,"đã bắt sống; strip=vào-vòng-xuyến, HUD=vòng-xuyến-thẳng"),
 ("Vòng xuyến","Vòng xuyến RA TRÁI","roundabout_slight_left.png",11,15,GEN,"đã bắt sống; strip GENERIC (vào vòng xuyến), HUD CÓ HƯỚNG (ra trái) — TASK 1"),
 ("Vòng xuyến","Ra khỏi vòng xuyến","roundabout_exit.png",12,24,OK,"đã bắt sống"),
 ("Vòng xuyến","Vòng xuyến RA PHẢI","",11,18,NA,"chưa bắt sống; strip generic, HUD ra phải"),
 ("Vòng xuyến","Vòng xuyến RA LỐI thứ N","",11,"25–34",NA,"code + icon CAN 25–34 SẴN SÀNG (ưu tiên hơn glyph hướng). NHƯNG noti GMaps KHÔNG cấp số lối ra (đã xác minh: text=tên đường, extras không field exit, contentView=null) → app không lấy được N → DORMANT. Source-limit GMaps, không phải app thiếu."),
 ("Điểm mốc","Tới điểm đến","",15,48,NA,"chưa bắt sống"),
 ("Điểm mốc","Tới waypoint","",10,45,NA,"chưa bắt sống"),
 ("Thông tin","Vào hầm","",16,49,NA,"chưa bắt; cần kiểm GMaps có phát không"),
 ("Thông tin","Trạm thu phí","",14,47,NA,"chưa bắt; cần kiểm GMaps"),
 ("Thông tin","Trạm dừng nghỉ","",13,46,NA,"chưa bắt; cần kiểm GMaps"),
 ("Tiếp tục","Tiếp tục theo đường","",20,12,NA,"chưa bắt sống"),
]
def vcl(v): return {"MATCH":"ok","MATCH*":"gen","chưa bắt sống":"na"}[v]
def cap(can): return (f'CAN {can} · {CANM.get(can,"?")}' if isinstance(can,int) else f'CAN {can}')
sit_rows=[]; cur=None; n_live=0
for g,desc,af,amap,hud,verdict,note in sit:
    if g!=cur: sit_rows.append(f'<tr class="grp"><td colspan="5">{g}</td></tr>'); cur=g
    sc = strip_can(amap)
    ga = arrow(af) if af else '<span class="txt">chưa bắt</span>'
    if af: n_live+=1
    simg = (canimg(sc) or '<span class="txt">—</span>')
    himg = (canimg(hud) if isinstance(hud,int) else '') or '<span class="txt">—</span>'
    sit_rows.append(
      f'<tr><td class="man">{desc}<div class="v {vcl(verdict)}">{verdict}</div><div class="nt">{note}</div></td>'
      f'<td class="cell arwcell">{ga}<div class="cap">GMaps</div></td>'
      f'<td class="cell">{simg}<div class="cap">strip · NEW_ICON {amap}{" → "+str(sc) if sc else ""}</div></td>'
      f'<td class="cell">{himg}<div class="cap">HUD/Giữa · {cap(hud)}</div></td>'
      f'<td class="man2">{AMAP.get(amap,"?")}</td></tr>')

# §B — CAN 1..49 (glyph text from RE; emit from Maneuver.kt 1.30; real cluster photo when RHT)
CAN=[
 (1,"rẽ trái","TURN_LEFT","turn_normal_left","live"),(2,"rẽ phải","TURN_RIGHT","turn_normal_right","live"),
 (3,"chếch/giữ trái","SLIGHT_LEFT·RAMP·FORK·KEEP_LEFT","slight/fork/ramp/keep left","live"),
 (4,"GAP firmware","(không phát)","—","gap"),
 (5,"chếch/giữ phải","SLIGHT_RIGHT·RAMP·FORK·KEEP_RIGHT","slight/fork/ramp/keep right","live"),
 (6,"GAP firmware","(không phát)","—","gap"),
 (7,"ngoặt trái","SHARP_LEFT","turn_sharp_left","live"),(8,"ngoặt phải","SHARP_RIGHT","turn_sharp_right","live"),
 (9,"quay đầu (trái)","UTURN","u_turn_left","live"),(10,"quay đầu phải","UTURN_RIGHT","u_turn_right (LHT — bỏ)","cw"),
 (11,"đi thẳng","STRAIGHT·MERGE","depart/straight/merge","live"),(12,"tiếp tục","CONTINUE","maneuver_continue","code"),
 (13,"vào vòng xuyến","(strip của mọi vòng xuyến)","= strip khi NEW_ICON 11","live"),
 (14,"vào vòng xuyến (CW)","(bỏ — LHT)","—","cw"),
 (15,"vòng xuyến ra TRÁI","ROUNDABOUT_LEFT","roundabout ccw + left","live"),
 (16,"vòng xuyến ra trái (CW)","ROUNDABOUT_LEFT_CW","(bỏ — LHT)","cw"),
 (17,"vòng xuyến ra phải (CW)","ROUNDABOUT_RIGHT_CW","(bỏ — LHT)","cw"),
 (18,"vòng xuyến ra PHẢI","ROUNDABOUT_RIGHT","roundabout ccw + right","code"),
 (19,"vòng xuyến thẳng (CW)","ROUNDABOUT_STRAIGHT_CW","(bỏ — LHT)","cw"),
 (20,"vòng xuyến thẳng","ROUNDABOUT_STRAIGHT·ROUNDABOUT","roundabout ccw straight","live"),
 (21,"vòng xuyến U (CW)","ROUNDABOUT_UTURN_CW","(bỏ — LHT)","cw"),
 (22,"vòng xuyến U","ROUNDABOUT_UTURN","roundabout ccw u_turn","code"),
 (23,"ra vòng xuyến (CW)","(bỏ — LHT)","—","cw"),
 (24,"ra khỏi vòng xuyến","ROUNDABOUT_EXIT","roundabout_exit_ccw","live"),
 ("25–34","vòng xuyến LỐI RA N (CCW)","hudIcon=24+N","text 'lối ra thứ N'","code"),
 ("35–44","vòng xuyến lối ra N (CW)","(bỏ — LHT)","—","cw"),
 (45,"tới waypoint","WAYPOINT","*waypoint*","code"),(46,"tới trạm nghỉ","SERVICE_AREA","*service*","code"),
 (47,"tới thu phí","TOLL","*toll*","code"),(48,"tới điểm đến","DESTINATION","destination/arrive","code"),
 (49,"vào hầm","TUNNEL","*tunnel*","code"),
]
SL={"live":"● LIVE","code":"○ code","gap":"▲ GAP","cw":"◇ bỏ (LHT/CW)"}
can_rows=[]
for can,glyph,emit,src,st in CAN:
    cimg = canimg(can) if isinstance(can,int) else ""
    ccell = cimg if cimg else '<span class="txt">—</span>'
    can_rows.append(f'<tr class="st-{st}"><td class="mono">CAN {can}</td><td>{glyph}</td>'
      f'<td class="cell">{ccell}</td><td class="mono sm">{emit}</td><td class="sm">{src}</td>'
      f'<td class="stt {st}">{SL[st]}</td></tr>')

glyphs_have = len([f for f in os.listdir(GLYPH) if f.endswith(".jpg")]) if os.path.isdir(GLYPH) else 0
html=f"""<!doctype html><html lang="vi"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>GMaps → ClusterNav — mapping mũi tên (ảnh thật, review)</title><style>
:root{{--bg:#f5f5f7;--pa:#fff;--pa2:#fbfbfd;--ink:#1d1d1f;--mut:#6e6e73;--ln:#d2d2d7;--bl:#0071e3;--gn:#248a3d;--gns:#e9f7ed;--am:#9a5b00;--ams:#fff4df;--rd:#c9342f;--rds:#ffebe9;--mono:ui-monospace,Menlo,Consolas,monospace;--sans:-apple-system,BlinkMacSystemFont,"SF Pro Display","Segoe UI",sans-serif}}
@media(prefers-color-scheme:dark){{:root{{--bg:#09090b;--pa:#171719;--pa2:#1d1d20;--ink:#f5f5f7;--mut:#a1a1a6;--ln:#38383c;--bl:#5ac8fa;--gn:#68d982;--gns:#14291a;--am:#ffb340;--ams:#302511;--rd:#ff6961;--rds:#351714}}}}
*{{box-sizing:border-box}}body{{margin:0;background:var(--bg);color:var(--ink);font:15px/1.55 var(--sans)}}
.wrap{{max-width:1150px;margin:auto;padding:0 20px 90px}}
h1{{font-size:clamp(24px,4vw,36px);letter-spacing:-.02em;margin:30px 0 8px}} h2{{font-size:21px;margin:32px 0 6px}}
.lead{{color:var(--mut);max-width:900px}} .meta{{font:12px var(--mono);color:var(--mut);margin:12px 0}}
.card{{background:var(--pa);border:1px solid var(--ln);border-radius:16px;padding:6px;margin:14px 0;overflow:auto}}
table{{border-collapse:collapse;width:100%;min-width:820px}} th,td{{padding:8px 10px;text-align:left;vertical-align:top;border-bottom:1px solid var(--ln);font-size:13px}}
th{{font:600 11px var(--mono);text-transform:uppercase;letter-spacing:.04em;color:var(--mut);position:sticky;top:0;background:var(--pa2)}}
tr.grp td{{background:var(--pa2);font:600 12px var(--mono);text-transform:uppercase;color:var(--bl);border-bottom:2px solid var(--bl)}}
.man{{font-weight:600;max-width:190px}} .man2{{color:var(--mut);font-size:12px}}
.cell{{text-align:center;width:132px}} .arwcell{{background:#0b0b0d;border-radius:8px}}
img.arw{{width:66px;height:66px;image-rendering:pixelated;display:block;margin:2px auto}}
img.cph{{width:118px;max-height:92px;object-fit:cover;border-radius:6px;display:block;margin:2px auto;border:1px solid var(--ln)}}
.cap{{font:10.5px var(--mono);color:var(--mut);margin-top:3px}} .txt{{font:11px var(--mono);color:var(--mut)}}
.v{{font:600 11px var(--mono);margin-top:3px}} .v.ok{{color:var(--gn)}} .v.gen{{color:var(--am)}} .v.na{{color:var(--mut)}}
.nt{{font:11px/1.35 var(--sans);color:var(--mut);margin-top:3px}}
.mono{{font-family:var(--mono);font-size:12px}} .sm{{font-size:11px}} td.sm{{color:var(--mut)}}
.stt{{font:600 11px var(--mono);white-space:nowrap}} .stt.live{{color:var(--gn)}} .stt.code{{color:var(--bl)}} .stt.gap{{color:var(--rd)}} .stt.cw{{color:var(--mut)}}
tr.st-live td{{background:color-mix(in srgb,var(--gn) 8%,transparent)}} tr.st-gap td{{background:color-mix(in srgb,var(--rd) 8%,transparent)}}
.legend{{display:flex;gap:14px;flex-wrap:wrap;margin:12px 0;font-size:12.5px}} .pill{{font:600 11px var(--mono);padding:3px 8px;border-radius:999px}}
.pill.ok{{background:var(--gns);color:var(--gn)}} .pill.gen{{background:var(--ams);color:var(--am)}} .pill.na{{background:color-mix(in srgb,var(--mut) 16%,transparent);color:var(--mut)}}
.note{{background:var(--pa);border:1px solid var(--ln);border-left:3px solid var(--bl);border-radius:10px;padding:12px 15px;margin:14px 0;font-size:13.5px}} .note.am{{border-left-color:var(--am)}}
code{{font:12px var(--mono);background:color-mix(in srgb,var(--mut) 14%,transparent);padding:1px 5px;border-radius:5px}}
</style></head><body><div class="wrap">
<h1>GMaps → ClusterNav — mapping mũi tên (ẢNH THẬT, để review)</h1>
<p class="lead">Mỗi tình huống: <b>3 ảnh thật</b> cạnh nhau — <b>arrow GMaps</b> (bắt trên emulator) · <b>strip cụm vẽ gì</b> · <b>HUD/Giữa vẽ gì</b> (2 cái sau là ảnh CỤM THẬT chụp on-car). Soi bằng mắt, không cần nhớ mã.</p>
<p class="meta">Owner: Đăng Khôi · dangkhoi · 2026-08-17 · Arrow GMaps: emulator (GMaps mới nhất, GPS mock khắp HCM) · Ảnh cụm: sweep on-car 2026-08-16 (../icon maps, {glyphs_have} glyph) · Mapping: TurnIdMapToCAN + Maneuver.kt 1.30 · Bỏ CW/LHT theo owner</p>
<div class="legend"><span class="pill ok">MATCH</span> đúng · <span class="pill gen">MATCH*</span> đúng + lưu ý · <span class="pill na">chưa bắt sống</span> (ảnh cụm vẫn có từ sweep)</div>

<h2>§A — GMaps → app vẽ gì (3 ảnh thật/hàng)</h2>
<div class="card"><table><thead><tr><th>Tình huống + verdict</th><th>Arrow GMaps</th><th>STRIP cụm vẽ</th><th>HUD/Giữa vẽ</th><th>Nghĩa</th></tr></thead>
<tbody>{''.join(sit_rows)}</tbody></table></div>

<h2>§B — Firmware CAN 1..49 + ảnh glyph cụm thật</h2>
<div class="legend"><span class="pill ok">● LIVE</span> bắt được arrow GMaps · <span class="pill" style="background:color-mix(in srgb,var(--bl) 16%,transparent);color:var(--bl)">○ code</span> app map, chưa bắt · <span class="pill" style="background:var(--rds);color:var(--rd)">▲ GAP</span> firmware · <span class="pill na">◇ bỏ</span> CW/LHT</div>
<div class="card"><table><thead><tr><th>CAN id</th><th>Glyph (RE)</th><th>Cụm vẽ (ảnh thật)</th><th>App phát qua</th><th>Từ GMaps</th><th>TT</th></tr></thead>
<tbody>{''.join(can_rows)}</tbody></table></div>

<div class="note am"><b>Điểm soi kỹ (khác biệt cố ý):</b> hàng <b>"Đi thẳng qua vòng xuyến"</b> và <b>"Vòng xuyến RA TRÁI"</b> — cột STRIP vẽ glyph <b>"vào vòng xuyến" (CAN 13) generic</b>, còn HUD/Giữa vẽ glyph <b>CÓ HƯỚNG (CAN 20 thẳng / CAN 15 ra-trái)</b>. Đây là carve-out TASK 1 (cụm-strip không có glyph vòng-xuyến-theo-hướng). So 2 ảnh cụm ở 2 cột sẽ thấy rõ khác biệt này.</div>
<div class="note am"><b>Vòng xuyến — số lối ra (CAN 25–34):</b> app ĐÃ hỗ trợ (icon + code ưu tiên số hơn hướng), nhưng <b>noti GMaps KHÔNG phát số lối ra</b> (xác minh emulator: extras chỉ có cự ly/ETA/tên-đường/bitmap mũi tên; text luôn là tên đường). Nên số lối ra hiện <b>dormant</b> — vòng xuyến rơi về glyph CÓ HƯỚNG đọc từ ảnh mũi tên. Đây là source-limit GMaps.</div>
<div class="note"><b>Nguồn ảnh:</b> arrow GMaps = silhouette từ bitmap notification thật (emulator). Ảnh "cụm vẽ" = photo cụm Seal chụp on-car (sweep bắn từng mã CAN, owner xác nhận glyph). Vài ảnh vòng-xuyến hơi nhoè (chụp đêm) nhưng đủ nhận glyph. Hàng "chưa bắt sống" = chưa bắt được arrow GMaps tương ứng (lái mù emulator chạm trần), nhưng ảnh CỤM vẫn có sẵn để bạn biết cụm sẽ vẽ gì.</div>
</div></body></html>"""
os.makedirs(os.path.dirname(OUT), exist_ok=True); open(OUT,"w").write(html)
print(f"wrote {OUT} ({len(html)//1024}KB) arrows_live={n_live} can_glyph_photos={glyphs_have}")
