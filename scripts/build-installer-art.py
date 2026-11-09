#!/usr/bin/env python3
"""Regenerate the Windows installer's pictures (commit the results; only needed when they change).
Needs Pillow and Inter: python3 scripts/build-installer-art.py [path/to/Inter-Bold.ttf]"""
import math, os, random, sys
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "src-tauri/nsis")
FONT = sys.argv[1] if len(sys.argv) > 1 else "/usr/share/fonts/truetype/inter-zorin-os/Inter-Bold.ttf"
LOGO = Image.open(os.path.join(ROOT, "src-tauri/icons/128x128@2x.png")).convert("RGBA")
BG, ACCENT = (22, 23, 27), (251, 191, 36)

# The panel beside the first and last pages: the launcher's dark look, 164x314 as NSIS expects.
W, H = 164, 314
side = Image.new("RGB", (W, H), BG)
px = side.load()
for y in range(H):
    for x in range(W):
        d = min(1.0, math.hypot(x - W / 2, y - 92) / 170)
        t = (1 - d) ** 2 * 0.34
        px[x, y] = tuple(round(b + (a - b) * t) for a, b in zip(ACCENT, BG))
draw = ImageDraw.Draw(side, "RGBA")
rng = random.Random(7)
for _ in range(34):
    x, y = rng.randrange(W), rng.randrange(H)
    draw.point((x, y), fill=(236, 235, 243, rng.randrange(40, 150)))
logo = LOGO.resize((88, 88), Image.LANCZOS)
side.paste(logo, ((W - 88) // 2, 48), logo)
title = ImageFont.truetype(FONT, 27)
tw = draw.textlength("Aerion", font=title)
draw.text(((W - tw) / 2, 150), "Aerion", font=title, fill=(236, 235, 243))
side.save(os.path.join(OUT, "sidebar.bmp"), "BMP")

# The strip at the top of the other pages: white like the page, the logo on the right.
head = Image.new("RGB", (150, 57), (255, 255, 255))
small = LOGO.resize((44, 44), Image.LANCZOS)
head.paste(small, (150 - 44 - 8, 6), small)
head.save(os.path.join(OUT, "header.bmp"), "BMP")
