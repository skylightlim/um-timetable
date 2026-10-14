"""Draws the launcher icon: 马大牛逼 as a seal (印章), four characters 2×2, in UM's crest colours.

The crest is a blue shield with a gold border, yellow tigers and a red hibiscus at its heart; the icon
is a blue field (the adaptive icon's background, @color/ic_bg) with a red seal, gold rim and yellow
characters in AR PL UKai CN (文鼎 PL 楷书, Arphic Public License).

    curl -LO https://stable-mirror.omarchy.org/extra/os/x86_64/ttf-arphic-ukai-0.2.20080216.2-3-any.pkg.tar.zst
    tar --zstd -xf ttf-arphic-ukai-*.pkg.tar.zst usr/share/fonts/TTF/ukai.ttc
    uv run --with fonttools python seal.py usr/share/fonts/TTF/ukai.ttc ../app/src/main/res/drawable [key=value ...]

Writes ic_fg.xml and ic_mono.xml there, the status-bar icon ic_notify.xml (the rim and 马 alone), and
preview-circle.svg / preview-squircle.svg here.
"""
import sys

from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTCollection

P = dict(
    blue="#223E99", gold="#BB9632", red="#DA2128", yellow="#FFD900",
    side=50.0,     # seal width and height in the 108-unit icon; its corners must stay in the radius-33 safe circle
    radius=6.0,    # seal corner radius
    rim=1.8,       # gold rim width
    pad=2.6,       # red margin around the characters
    gap=1.0,       # gap between the characters
    size=1.08,     # glyph em box relative to its cell (the brush strokes don't fill the em box)
    bold=0.9,      # stroke added to the characters, so they hold up at launcher size
)
font_path, res = sys.argv[1], sys.argv[2]
for kv in sys.argv[3:]:
    k, v = kv.split("=", 1)
    P[k] = v if isinstance(P[k], str) else float(v)


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

s, rim = P["side"], P["rim"]
x0 = 54 - s / 2
gold = rrect(x0, s, P["radius"])
red = rrect(x0 + rim, s - 2 * rim, P["radius"] - rim)
inset = rim + P["pad"]
cell = (s - 2 * inset - P["gap"]) / 2
scale = cell * P["size"] / em

text = ""
for n, ch in enumerate("马大牛逼"):
    g = glyphs[cmap[ord(ch)]]
    b = BoundsPen(glyphs)
    g.draw(b)
    bx, by = (b.bounds[0] + b.bounds[2]) / 2, (b.bounds[1] + b.bounds[3]) / 2
    cx = x0 + inset + cell / 2 + (n % 2) * (cell + P["gap"])
    cy = x0 + inset + cell / 2 + (n // 2) * (cell + P["gap"])
    pen = SVGPathPen(glyphs, ntos=num)
    g.draw(TransformPen(pen, (scale, 0, 0, -scale, cx - bx * scale, cy + by * scale)))
    text += pen.getCommands()


def argb(c):
    return "#FF" + c[1:].upper()


def vector(comment, paths, box=108):
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            f"<!-- {comment} Drawn by android/icon/seal.py; edit that, not this. -->\n"
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:width="{box}dp" android:height="{box}dp"\n'
            f'    android:viewportWidth="{box}" android:viewportHeight="{box}">\n'
            + "".join(f"    <path {p} />\n" for p in paths) + "</vector>\n")


def chars(color):
    return (f'android:fillColor="{color}" android:strokeColor="{color}" android:strokeWidth="{num(P["bold"])}" '
            f'android:strokeLineJoin="round" android:pathData="{text}"')


open(f"{res}/ic_fg.xml", "w").write(vector(
    "马大牛逼 in AR PL UKai CN as a seal in UM's crest colours, on the blue of @color/ic_bg.",
    [f'android:fillColor="{argb(P["gold"])}" android:pathData="{gold}"',
     f'android:fillColor="{argb(P["red"])}" android:pathData="{red}"',
     chars(argb(P["yellow"]))]))
open(f"{res}/ic_mono.xml", "w").write(vector(
    "Themed icon: the seal's rim and characters.",
    [f'android:fillColor="#FFFFFFFF" android:fillType="evenOdd" android:pathData="{gold}{red}"',
     chars("#FFFFFFFF")]))

# The status-bar icon, 24 units: the seal's rim and 马 alone (马大 is UM). Four characters can't be read
# at that size, and a notification icon is one colour, so the seal is a ring with the character inside.
N = dict(side=20.0, radius=3.4, rim=1.7, pad=1.4, bold=0.45)
nx = 12 - N["side"] / 2
ring = rrect(nx, N["side"], N["radius"]) + rrect(nx + N["rim"], N["side"] - 2 * N["rim"], N["radius"] - N["rim"])
g = glyphs[cmap[ord("马")]]
b = BoundsPen(glyphs)
g.draw(b)
bx, by = (b.bounds[0] + b.bounds[2]) / 2, (b.bounds[1] + b.bounds[3]) / 2
nscale = (N["side"] - 2 * (N["rim"] + N["pad"])) * P["size"] / em
pen = SVGPathPen(glyphs, ntos=num)
g.draw(TransformPen(pen, (nscale, 0, 0, -nscale, 12 - bx * nscale, 12 + by * nscale)))
open(f"{res}/ic_notify.xml", "w").write(vector(
    "Status-bar icon: the seal's rim and 马 (马大, UM), in one colour.",
    [f'android:fillColor="#FFFFFFFF" android:fillType="evenOdd" android:pathData="{ring}"',
     f'android:fillColor="#FFFFFFFF" android:strokeColor="#FFFFFFFF" android:strokeWidth="{num(N["bold"])}" '
     f'android:strokeLineJoin="round" android:pathData="{pen.getCommands()}"'], box=24))

body = (f'<rect width="108" height="108" fill="{P["blue"]}"/><path fill="{P["gold"]}" d="{gold}"/>'
        f'<path fill="{P["red"]}" d="{red}"/><path fill="{P["yellow"]}" stroke="{P["yellow"]}" '
        f'stroke-width="{P["bold"]}" stroke-linejoin="round" d="{text}"/>')
for name, mask in (("circle", '<circle cx="54" cy="54" r="36"/>'),
                   ("squircle", '<rect x="18" y="18" width="72" height="72" rx="22"/>')):
    open(f"preview-{name}.svg", "w").write(
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72"><defs><clipPath id="m">{mask}</clipPath>'
        f'</defs><g clip-path="url(#m)">{body}</g></svg>')
