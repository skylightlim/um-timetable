"""Draws the status-bar icon: a seal's (印章) rim with 马 inside (马大 is UM), in AR PL UKai CN (文鼎 PL
楷书, Arphic Public License). Until 15 October 2026 the launcher icon was this seal too, with all four
characters 马大牛逼; crest.py draws the launcher icon now, and git history has the seal.

    curl -LO https://stable-mirror.omarchy.org/extra/os/x86_64/ttf-arphic-ukai-0.2.20080216.2-3-any.pkg.tar.zst
    tar --zstd -xf ttf-arphic-ukai-*.pkg.tar.zst usr/share/fonts/TTF/ukai.ttc
    uv run --with fonttools python seal.py usr/share/fonts/TTF/ukai.ttc ../app/src/main/res/drawable

Writes ic_notify.xml there.
"""
import sys

from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTCollection

font_path, res = sys.argv[1], sys.argv[2]


def num(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s == "-0" else s


def rrect(x, w, r):
    """A rounded square from (x, x), width w, corner radius r."""
    e = x + w
    return (f"M{num(x + r)} {num(x)}H{num(e - r)}A{num(r)} {num(r)} 0 0 1 {num(e)} {num(x + r)}"
            f"V{num(e - r)}A{num(r)} {num(r)} 0 0 1 {num(e - r)} {num(e)}"
            f"H{num(x + r)}A{num(r)} {num(r)} 0 0 1 {num(x)} {num(e - r)}"
            f"V{num(x + r)}A{num(r)} {num(r)} 0 0 1 {num(x + r)} {num(x)}Z")


font = TTCollection(font_path).fonts[0]  # AR PL UKai CN: simplified forms
glyphs, cmap, em = font.getGlyphSet(), font.getBestCmap(), font["head"].unitsPerEm

# 24 units: the seal's rim and 马 alone. A notification icon is one colour, so the seal is a ring with
# the character inside. size: the glyph's em box relative to its cell (the brush strokes don't fill it);
# bold: stroke added to the character, so it holds up at status-bar size.
N = dict(side=20.0, radius=3.4, rim=1.7, pad=1.4, size=1.08, bold=0.45)
nx = 12 - N["side"] / 2
ring = rrect(nx, N["side"], N["radius"]) + rrect(nx + N["rim"], N["side"] - 2 * N["rim"], N["radius"] - N["rim"])
g = glyphs[cmap[ord("马")]]
b = BoundsPen(glyphs)
g.draw(b)
bx, by = (b.bounds[0] + b.bounds[2]) / 2, (b.bounds[1] + b.bounds[3]) / 2
nscale = (N["side"] - 2 * (N["rim"] + N["pad"])) * N["size"] / em
pen = SVGPathPen(glyphs, ntos=num)
g.draw(TransformPen(pen, (nscale, 0, 0, -nscale, 12 - bx * nscale, 12 + by * nscale)))
open(f"{res}/ic_notify.xml", "w").write(
    '<?xml version="1.0" encoding="utf-8"?>\n'
    "<!-- Status-bar icon: the seal's rim and 马 (马大, UM), in one colour. Drawn by android/icon/seal.py; edit that, not this. -->\n"
    '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
    '    android:width="24dp" android:height="24dp"\n'
    '    android:viewportWidth="24" android:viewportHeight="24">\n'
    f'    <path android:fillColor="#FFFFFFFF" android:fillType="evenOdd" android:pathData="{ring}" />\n'
    f'    <path android:fillColor="#FFFFFFFF" android:strokeColor="#FFFFFFFF" android:strokeWidth="{num(N["bold"])}" '
    f'android:strokeLineJoin="round" android:pathData="{pen.getCommands()}" />\n'
    "</vector>\n")
