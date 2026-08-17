#!/usr/bin/env python3
"""Render captured white-on-transparent GMaps arrows as CRISP opaque white-on-dark silhouettes
(thresholded alpha, upscaled) so they show clearly in the review doc (fixes the 'black block' look).
Pure stdlib PNG decode+encode (no PIL)."""
import glob, os, sys, zlib, struct
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from importlib import import_module
_m = import_module("analyze-arrow-png")

SRC = sys.argv[1] if len(sys.argv) > 1 else "/tmp/corpus"
DST = sys.argv[2] if len(sys.argv) > 2 else "/tmp/corpus_vis"
SCALE = 3
BG = (26, 26, 28); INK = (255, 255, 255)
os.makedirs(DST, exist_ok=True)

def write_png_rgb(path, w, h, rows):  # rows: bytes per row = w*3
    raw = bytearray()
    for y in range(h):
        raw.append(0)                 # filter: none
        raw += rows[y]
    def chunk(typ, data):
        c = typ + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xffffffff)
    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    open(path, "wb").write(png)

n = 0
for f in sorted(glob.glob(os.path.join(SRC, "*.png"))):
    w, h, px = _m.load_rgba(f)
    W, H = w * SCALE, h * SCALE
    rows = []
    for Y in range(H):
        y = Y // SCALE; row = bytearray()
        for X in range(W):
            x = X // SCALE; a = px[(y * w + x) * 4 + 3]
            r, g, b = INK if a >= 80 else BG
            row += bytes((r, g, b))
        rows.append(row)
    out = os.path.join(DST, os.path.basename(f))
    write_png_rgb(out, W, H, rows); n += 1
print(f"wrote {n} silhouettes to {DST} ({W}x{H} each)")
