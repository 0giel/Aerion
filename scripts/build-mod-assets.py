#!/usr/bin/env python3
"""Regenerate the Aerion mod's images (commit the results; only needed when they change):
smooth text atlas from the Inter typeface and the menu's icons from Lucide paths (ISC License).
Needs Pillow, GdkPixbuf with its SVG loader and Inter installed:
  python3 scripts/build-mod-assets.py [path/to/Inter-Medium.ttf]
"""
import io, os, re, sys, tempfile
import gi
gi.require_version("GdkPixbuf", "2.0")
from gi.repository import GdkPixbuf
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "mod/common/src/main/resources/assets/aerion")
FONT = sys.argv[1] if len(sys.argv) > 1 else "/usr/share/fonts/truetype/inter-zorin-os/Inter-Medium.ttf"
EM = 32
CHARS = (
    list(range(0x20, 0x7F)) + list(range(0xA0, 0x180)) + list(range(0x391, 0x3CF)) + list(range(0x400, 0x460))
    + [0x2013, 0x2014, 0x2018, 0x2019, 0x201A, 0x201C, 0x201D, 0x201E, 0x2022, 0x2026, 0x20AC, 0x2190, 0x2191, 0x2192, 0x2193, 0x221E]
)


def font_atlas():
    font = ImageFont.truetype(FONT, EM)
    size = (1024, 1024)
    mask = Image.new("L", size, 0)
    draw = ImageDraw.Draw(mask)
    x, y, row = 2, 2, 0
    lines = []
    for cp in CHARS:
        ch = chr(cp)
        l, t, r, b = font.getbbox(ch, anchor="ls")
        adv = round(font.getlength(ch))
        w, h = r - l, b - t
        if w <= 0 or h <= 0:
            lines.append(f"{cp} 0 0 0 0 0 0 {adv}")
            continue
        if x + w + 2 > size[0]:
            x, y, row = 2, y + row + 2, 0
        assert y + h + 2 <= size[1], "atlas too small"
        draw.text((x - l, y - t), ch, fill=255, font=font, anchor="ls")
        lines.append(f"{cp} {x} {y} {w} {h} {l} {t} {adv}")
        x += w + 2
        row = max(row, h)
    cap = -font.getbbox("H", anchor="ls")[1]
    used = y + row + 2
    height = 1 << (used - 1).bit_length()
    mask = mask.crop((0, 0, size[0], height))
    white = Image.new("RGBA", mask.size, (255, 255, 255, 0))
    white.putalpha(mask)
    white.save(os.path.join(OUT, "textures/gui/font.png"), optimize=True)
    with open(os.path.join(OUT, "font.txt"), "w") as f:
        f.write(f"{EM} {cap} {mask.size[0]} {mask.size[1]}\n" + "\n".join(lines) + "\n")


ICONS = {
    "fps": '<path d="m12 14 4-4"/><path d="M3.34 19a10 10 0 1 1 17.32 0"/>',
    "coords": '<path d="M20 10c0 4.993-5.539 10.193-7.399 11.799a1 1 0 0 1-1.202 0C9.539 20.193 4 14.993 4 10a8 8 0 0 1 16 0"/><circle cx="12" cy="10" r="3"/>',
    "direction": '<path d="m16.24 7.76-1.804 5.411a2 2 0 0 1-1.265 1.265L7.76 16.24l1.804-5.411a2 2 0 0 1 1.265-1.265z"/><circle cx="12" cy="12" r="10"/>',
    "cps": '<rect x="5" y="2" width="14" height="20" rx="7"/><path d="M12 6v4"/>',
    "keystrokes": '<path d="M10 8h.01"/><path d="M12 12h.01"/><path d="M14 8h.01"/><path d="M16 12h.01"/><path d="M18 8h.01"/><path d="M6 8h.01"/><path d="M7 16h10"/><path d="M8 12h.01"/><rect width="20" height="16" x="2" y="4" rx="2"/>',
    "potions": '<path d="M14 2v6a2 2 0 0 0 .245.96l5.51 10.08A2 2 0 0 1 18 22H6a2 2 0 0 1-1.755-2.96l5.51-10.08A2 2 0 0 0 10 8V2"/><path d="M6.453 15h11.094"/><path d="M8.5 2h7"/>',
    "ping": '<path d="M12 20h.01"/><path d="M2 8.82a15 15 0 0 1 20 0"/><path d="M5 12.859a10 10 0 0 1 14 0"/><path d="M8.5 16.429a5 5 0 0 1 7 0"/>',
    "minimap": '<path d="M14.106 5.553a2 2 0 0 0 1.788 0l3.659-1.83A1 1 0 0 1 21 4.619v12.764a1 1 0 0 1-.553.894l-4.553 2.277a2 2 0 0 1-1.788 0l-4.212-2.106a2 2 0 0 0-1.788 0l-3.659 1.83A1 1 0 0 1 3 19.381V6.618a1 1 0 0 1 .553-.894l4.553-2.277a2 2 0 0 1 1.788 0z"/><path d="M15 5.764v15"/><path d="M9 3.236v15"/>',
    "toggles": '<circle cx="15" cy="12" r="3"/><rect width="20" height="14" x="2" y="5" rx="7"/>',
    "toggleSprint": '<path d="M4 14a1 1 0 0 1-.78-1.63l9.9-10.2a.5.5 0 0 1 .86.46l-1.92 6.02A1 1 0 0 0 13 10h7a1 1 0 0 1 .78 1.63l-9.9 10.2a.5.5 0 0 1-.86-.46l1.92-6.02A1 1 0 0 0 11 14z"/>',
    "help": '<circle cx="12" cy="12" r="10"/><path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3"/><path d="M12 17h.01"/>',
    "hitboxes": '<path d="M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z"/><path d="m3.3 7 8.7 5 8.7-5"/><path d="M12 22V12"/>',
    "overlays": '<path d="m12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58-3.9a1 1 0 0 0 0-1.83Z"/><path d="m22 17.65-9.17 4.16a2 2 0 0 1-1.66 0L2 17.65"/><path d="m22 12.65-9.17 4.16a2 2 0 0 1-1.66 0L2 12.65"/>',
    "sounds": '<path d="M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z"/><path d="M16 9a5 5 0 0 1 0 6"/><path d="M19.364 18.364a9 9 0 0 0 0-12.728"/>',
    "layout": '<rect width="7" height="9" x="3" y="3" rx="1"/><rect width="7" height="5" x="14" y="3" rx="1"/><rect width="7" height="9" x="14" y="12" rx="1"/><rect width="7" height="5" x="3" y="16" rx="1"/>',
    "toggleSneak": '<path d="m7 6 5 5 5-5"/><path d="m7 13 5 5 5-5"/>',
}
# Shared with the launcher's own icon set.
FROM_LAUNCHER = {"armor": "shield", "clock": "clock", "memory": "cpu", "zoom": "search", "freelook": "eye", "fullbright": "sun", "back": "arrowLeft", "close": "x", "look": "palette", "undo": "undo", "keys": "key", "reset": "refresh", "settings": "sliders", "hide": "eyeOff", "play": "play", "search": "search"}


def launcher_icons():
    src = open(os.path.join(ROOT, "src/lib/icons.ts")).read()
    out = {}
    for name, key in FROM_LAUNCHER.items():
        m = re.search(r"\b" + key + r":\s*'([^']*)'", src)
        assert m, key
        out[name] = m.group(1)
    return out


def icon_atlas():
    cell, cols = 64, 6
    items = {**ICONS, **launcher_icons()}
    rows = (len(items) + cols - 1) // cols
    atlas = Image.new("RGBA", (cols * cell, rows * cell), (255, 255, 255, 0))
    names = []
    for i, (name, body) in enumerate(items.items()):
        svg = f'<svg xmlns="http://www.w3.org/2000/svg" width="{cell}" height="{cell}" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">{body}</svg>'
        with tempfile.NamedTemporaryFile(suffix=".svg", mode="w") as f:
            f.write(svg)
            f.flush()
            pix = GdkPixbuf.Pixbuf.new_from_file_at_size(f.name, cell, cell)
        png = Image.open(io.BytesIO(pix.save_to_bufferv("png", [], [])[1])).convert("RGBA")
        alpha = png.getchannel("A")
        tile = Image.new("RGBA", (cell, cell), (255, 255, 255, 0))
        tile.putalpha(alpha)
        atlas.paste(tile, ((i % cols) * cell, (i // cols) * cell))
        names.append(name)
    atlas.save(os.path.join(OUT, "textures/gui/icons.png"), optimize=True)
    with open(os.path.join(OUT, "icons.txt"), "w") as f:
        f.write(f"{cell} {cols} {atlas.size[0]} {atlas.size[1]}\n" + "\n".join(names) + "\n")


font_atlas()
icon_atlas()
print("done")
