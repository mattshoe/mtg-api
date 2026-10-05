#!/usr/bin/env python3
"""Cut the lotus and its five pips out of the launcher icon.

The top bar needs a mark, not a tile. Both the launcher foreground and
the website's PWA icon are drawn on a `#0E1115` plate — correct for a
home screen and a favicon, wrong for a bar, where it reads as a black
square sitting behind the art.

The plate is removed by flooding in from the edges rather than by
keying the colour, because one of the five mana pips is black and a
colour key would delete it. Flooding only reaches background that
touches the frame; anything dark and enclosed by art is art.

Edge pixels are blends of art and plate, so they get a partial alpha
and are un-premultiplied back off the plate colour — without that,
every outline keeps a dark fringe that shows up against any other
background.

Then it crops to what is left. An adaptive icon's foreground keeps its
art inside a safe zone the launcher masks away, which is the other
half of why the lotus looked tiny.

    python3 scripts/make-brand-mark.py
"""
import struct, zlib, sys
from collections import deque

SRC = "apps/androidApp/src/main/res/drawable-xxxhdpi/ic_launcher_foreground.png"
OUT = "apps/androidApp/src/main/res/drawable-nodpi/brand_mark.png"
# How far from the plate colour a pixel may be and still be plate.
TOL = 26
# How far from the plate a pixel must be to count as solid art.
SOLID = 70


def decode(path):
    data = open(path, "rb").read()
    pos, idat = 8, b""
    w = h = None
    while pos < len(data):
        ln = struct.unpack(">I", data[pos:pos + 4])[0]
        typ = data[pos + 4:pos + 8]
        ch = data[pos + 8:pos + 8 + ln]
        if typ == b"IHDR":
            w, h, bd, ct = struct.unpack(">IIBB", ch[:10])
            if (bd, ct) != (8, 6):
                sys.exit(f"{path}: want 8-bit RGBA, got bitdepth {bd} colour type {ct}")
        elif typ == b"IDAT":
            idat += ch
        pos += 12 + ln
    raw = zlib.decompress(idat)
    stride = w * 4
    out = bytearray()
    prev = bytearray(stride)
    i = 0
    for _ in range(h):
        f = raw[i]; i += 1
        line = bytearray(raw[i:i + stride]); i += stride
        if f == 1:
            for x in range(4, stride):
                line[x] = (line[x] + line[x - 4]) & 255
        elif f == 2:
            for x in range(stride):
                line[x] = (line[x] + prev[x]) & 255
        elif f == 3:
            for x in range(stride):
                a = line[x - 4] if x >= 4 else 0
                line[x] = (line[x] + ((a + prev[x]) >> 1)) & 255
        elif f == 4:
            for x in range(stride):
                a = line[x - 4] if x >= 4 else 0
                c = prev[x - 4] if x >= 4 else 0
                b = prev[x]
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[x] = (line[x] + pr) & 255
        out += line
        prev = line
    return w, h, out


def encode(w, h, px):
    raw = bytearray()
    stride = w * 4
    for y in range(h):
        raw.append(0)
        raw += px[y * stride:(y + 1) * stride]
    def chunk(typ, body):
        return (struct.pack(">I", len(body)) + typ + body
                + struct.pack(">I", zlib.crc32(typ + body) & 0xFFFFFFFF))
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
            + chunk(b"IEND", b""))


w, h, px = decode(SRC)
stride = w * 4
bg = tuple(px[0:3])

def far(x, y):
    o = y * stride + x * 4
    return max(abs(px[o] - bg[0]), abs(px[o + 1] - bg[1]), abs(px[o + 2] - bg[2]))

# Flood the plate in from every edge pixel that still looks like plate.
plate = bytearray(w * h)
q = deque()
for x in range(w):
    for y in (0, h - 1):
        if far(x, y) <= TOL and not plate[y * w + x]:
            plate[y * w + x] = 1; q.append((x, y))
for y in range(h):
    for x in (0, w - 1):
        if far(x, y) <= TOL and not plate[y * w + x]:
            plate[y * w + x] = 1; q.append((x, y))
while q:
    x, y = q.popleft()
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        nx, ny = x + dx, y + dy
        if 0 <= nx < w and 0 <= ny < h and not plate[ny * w + nx] and far(nx, ny) <= TOL:
            plate[ny * w + nx] = 1
            q.append((nx, ny))

# Alpha, and the colour the art would have been off the plate.
out = bytearray(w * h * 4)
for y in range(h):
    for x in range(w):
        o = y * stride + x * 4
        if plate[y * w + x]:
            continue  # already 0,0,0,0
        d = far(x, y)
        a = 255 if d >= SOLID else max(0, min(255, round(255 * d / SOLID)))
        if a == 0:
            continue
        for c in range(3):
            # p = (a/255)*fg + (1 - a/255)*bg  ->  fg
            fg = (px[o + c] - (1 - a / 255) * bg[c]) / (a / 255)
            out[o + c] = max(0, min(255, round(fg)))
        out[o + 3] = a

# Crop to the art: the safe zone goes with the plate.
minx, miny, maxx, maxy = w, h, -1, -1
for y in range(h):
    for x in range(w):
        if out[y * stride + x * 4 + 3] > 8:
            minx = min(minx, x); maxx = max(maxx, x)
            miny = min(miny, y); maxy = max(maxy, y)
if maxx < 0:
    sys.exit("nothing survived the cut")
cw, ch = maxx - minx + 1, maxy - miny + 1
# Square, so the bar never stretches it.
side = max(cw, ch)
ox, oy = (side - cw) // 2, (side - ch) // 2
sq = bytearray(side * side * 4)
for y in range(ch):
    src = (miny + y) * stride + minx * 4
    dst = ((oy + y) * side + ox) * 4
    sq[dst:dst + cw * 4] = out[src:src + cw * 4]

open(OUT, "wb").write(encode(side, side, sq))
painted = sum(1 for i in range(side * side) if sq[i * 4 + 3] > 8)
print(f"{SRC} {w}x{h} plate={bg}")
print(f"-> {OUT} {side}x{side}, {100 * painted / (side * side):.0f}% ink, corners transparent")
