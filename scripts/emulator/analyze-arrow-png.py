#!/usr/bin/env python3
"""Decode a 72x72 RGBA arrow PNG (pure stdlib), render ASCII, and replay ClusterNav's
ArrowClassifier COM heuristic to explain its verdict. Ground-truth tooling for the
maneuver-classifier corpus (no PIL needed)."""
import sys, zlib, struct

def load_rgba(path):
    d = open(path, "rb").read()
    assert d[:8] == b"\x89PNG\r\n\x1a\n", "not a png"
    off = 8; w = h = bd = ct = None; idat = b""
    while off < len(d):
        ln = struct.unpack(">I", d[off:off+4])[0]; typ = d[off+4:off+8]
        chunk = d[off+8:off+8+ln]; off += 12 + ln
        if typ == b"IHDR":
            w, h, bd, ct = struct.unpack(">IIBB", chunk[:10])
        elif typ == b"IDAT":
            idat += chunk
        elif typ == b"IEND":
            break
    assert bd == 8 and ct == 6, f"expect 8-bit RGBA, got bd={bd} ct={ct}"
    raw = zlib.decompress(idat); stride = w*4; out = bytearray(); prev = bytearray(stride); p = 0
    def paeth(a,b,c):
        pp=a+b-c; pa=abs(pp-a); pb=abs(pp-b); pc=abs(pp-c)
        return a if (pa<=pb and pa<=pc) else (b if pb<=pc else c)
    for _ in range(h):
        f = raw[p]; p += 1; line = bytearray(raw[p:p+stride]); p += stride
        for i in range(stride):
            a = line[i-4] if i>=4 else 0; b = prev[i]; c = prev[i-4] if i>=4 else 0
            if f==1: line[i]=(line[i]+a)&255
            elif f==2: line[i]=(line[i]+b)&255
            elif f==3: line[i]=(line[i]+((a+b)>>1))&255
            elif f==4: line[i]=(line[i]+paeth(a,b,c))&255
        out += line; prev = line
    return w, h, bytes(out)

INK_ALPHA=80; INK_LUM=140; THRESH_TURN=0.14; THRESH_SLIGHT=0.05
def is_ink(r,g,b,a):
    if a < INK_ALPHA: return False
    return (r*299+g*587+b*114)//1000 > INK_LUM

def analyze(path):
    w,h,px = load_rgba(path)
    def at(x,y):
        o=(y*w+x)*4; return px[o],px[o+1],px[o+2],px[o+3]
    # ASCII
    print(f"  {path.split('/')[-1]}  ({w}x{h})")
    step=max(1,w//36)
    for y in range(0,h,step):
        row=""
        for x in range(0,w,step):
            r,g,b,a=at(x,y); row += ("#" if is_ink(r,g,b,a) else ".")
        print("    "+row)
    # replay ArrowClassifier
    first=-1
    for y in range(h):
        if any(is_ink(*at(x,y)) for x in range(w)): first=y; break
    if first<0: print("    (no ink)"); return
    headcut=first+(h-first)*35//100
    sx=n=hsx=hn=0
    for y in range(h):
        for x in range(w):
            if not is_ink(*at(x,y)): continue
            sx+=x; n+=1
            if y<=headcut: hsx+=x; hn+=1
    cx=(w-1)/2.0
    com=(sx/n-cx)/w
    head=((hsx/hn-cx)/w) if hn>0 else com
    off=head*0.65+com*0.35
    if off<=-THRESH_TURN: icon=2
    elif off>=THRESH_TURN: icon=3
    elif off<=-THRESH_SLIGHT: icon=4
    elif off>=THRESH_SLIGHT: icon=5
    else: icon=9
    name={2:"LEFT",3:"RIGHT",4:"slight-left",5:"slight-right",9:"STRAIGHT"}[icon]
    print(f"    ink={n} firstRow={first} headCut={headcut} comOff={com:+.3f} headOff={head:+.3f} off={off:+.3f} -> ArrowClassifier={icon}({name})")

if __name__=="__main__":
    for p in sys.argv[1:]: analyze(p); print()
