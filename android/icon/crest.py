"""Draws the launcher icon: the cow (牛) and the horse (马) of the app's duel standing on either side of
an empty shield in UM's shape, the way the two tigers hold up the arms of Malaysia. It's all in the
gold of UM's shield rim, on its blue (the adaptive icon's background, @color/ic_bg).

    uv run --with skia-pathops python crest.py ../app/src/main/res/drawable [key=value ...]

Writes ic_fg.xml and ic_mono.xml there, and preview-circle.svg / preview-squircle.svg here (with no
directory, only the previews). The status-bar icon is still seal.py's.

The animals are DuelView's shapes (its 120 × 60 box) as solid gold silhouettes, like the crest's yellow
tigers: a blue gap round each piece where it covers another, and blue showing through for the eyes,
spots and smiles. Path booleans resolve the drawing into one gold shape, so the blue is a hole rather
than paint, and the themed icon (ic_mono) is that same shape.
"""
import math
import re
import sys

from pathops import LineCap, LineJoin, Path, PathOp, decompose_quadratic_segment, op

P = dict(
    blue="#223E99", gold="#BB9632",  # seal.py's UM crest colours
    line=1.0,       # the gaps between pieces, in icon units (dp in the 108-unit icon)
    scale=0.55,     # the animals: icon units per DuelView unit
    rim=2.4,        # the shield's gold rim
    shield_w=27.0, shield_h=33.0, shield_top=37.0,
    cow_x=33.5, horse_x=74.5, feet_y=71.0,  # where each one's feet are, in the icon
    lean=8.0,       # degrees each leans in, towards the shield
    arm=-28.0,      # the cow's arm from the shoulder, degrees (the horse's is its mirror image)
    arm_len=9.0,
)
res = sys.argv[1] if len(sys.argv) > 1 and "=" not in sys.argv[1] else None
for kv in sys.argv[1 if res is None else 2:]:
    k, v = kv.split("=", 1)
    P[k] = v if isinstance(P[k], str) else float(v)
W = P["line"] / P["scale"]  # the outline width in DuelView units


# --- shapes ----------------------------------------------------------------------------------------

def svg(d):
    """A path from SVG path data: absolute M, L, Q, C and Z only, each segment with its letter."""
    p, t, i = Path(), re.findall(r"[MLQCZ]|-?[0-9.]+", d), 0
    while i < len(t):
        c, i = t[i], i + 1
        n = {"M": 2, "L": 2, "Q": 4, "C": 6, "Z": 0}[c]
        a, i = [float(x) for x in t[i:i + n]], i + n
        {"M": p.moveTo, "L": p.lineTo, "Q": p.quadTo, "C": p.cubicTo, "Z": p.close}[c](*a)
    return p


def oval(l, t, r, b):
    k, cx, cy, rx, ry = 0.5523, (l + r) / 2, (t + b) / 2, (r - l) / 2, (b - t) / 2
    p = Path()
    p.moveTo(r, cy)
    p.cubicTo(r, cy + k * ry, cx + k * rx, b, cx, b)
    p.cubicTo(cx - k * rx, b, l, cy + k * ry, l, cy)
    p.cubicTo(l, cy - k * ry, cx - k * rx, t, cx, t)
    p.cubicTo(cx + k * rx, t, r, cy - k * ry, r, cy)
    p.close()
    return p


def rrect(l, t, r, b, rad):
    k = rad * (1 - 0.5523)
    p = Path()
    p.moveTo(l + rad, t)
    p.lineTo(r - rad, t); p.cubicTo(r - k, t, r, t + k, r, t + rad)
    p.lineTo(r, b - rad); p.cubicTo(r, b - k, r - k, b, r - rad, b)
    p.lineTo(l + rad, b); p.cubicTo(l + k, b, l, b - k, l, b - rad)
    p.lineTo(l, t + rad); p.cubicTo(l, t + k, l + k, t, l + rad, t)
    p.close()
    return p


def join(*shapes):
    out = Path()
    for s in shapes:
        out = op(out, s, PathOp.UNION)
    return out


def stroked(shape, width):
    p = Path(shape)
    p.stroke(width, LineCap.ROUND_CAP, LineJoin.ROUND_JOIN, 4)
    p.convertConicsToQuads()
    return op(p, Path(), PathOp.UNION)  # resolve the stroke's overlaps


def shield(cx, top, w, h):
    """UM's shield: a flat top, straight sides for the top third, then curving in to a point."""
    l, r, side = cx - w / 2, cx + w / 2, top + 0.36 * h
    return svg(f"M{l},{top} L{r},{top} L{r},{side} C{r},{top + 0.74 * h} {cx + 0.24 * w},{top + 0.9 * h} {cx},{top + h} "
               f"C{cx - 0.24 * w},{top + 0.9 * h} {l},{top + 0.74 * h} {l},{side} Z")


# --- drawing: a painter that keeps one resolved shape per colour ------------------------------------

class Painter:
    def __init__(self):
        self.shapes = {"gold": Path()}
        self.m = (1, 0, 0, 1, 0, 0)

    def put(self, shape, colour):
        """[shape] (in the current frame) painted [colour]: "gold", or "blue" to uncover the background."""
        shape = shape.transform(*self.m)
        for k in self.shapes:
            self.shapes[k] = op(self.shapes[k], shape, PathOp.UNION if k == colour else PathOp.DIFFERENCE)

    def piece(self, *shapes):
        """One piece: [shapes] as one solid gold shape, a blue gap of one line width round it."""
        u = join(*shapes)
        self.put(stroked(u, 2 * W), "blue")
        self.put(u, "gold")

    def line(self, shape, width=None, colour="gold"):
        self.put(stroked(shape, width or W), colour)

    def inside(self, shape, within, colour="gold"):
        self.put(op(shape, within, PathOp.INTERSECTION), colour)

    def frame(self, x, y, deg, s, ax, ay):
        """Draw from here on with the point ([ax], [ay]) at ([x], [y]), turned [deg] and scaled [s]."""
        a = math.radians(deg)
        c, sn = math.cos(a) * s, math.sin(a) * s
        self.m = (c, sn, -sn, c, x - c * ax + sn * ay, y - sn * ax - c * ay)


# --- the cow and the horse: DuelView's shapes, as gold silhouettes ---------------------------------
# Like the crest's tigers (solid yellow, dark stripes): each piece is solid gold with a blue gap round
# it where it covers another, and the details (eyes, spots, mane, smile) are blue showing through.

def cow(g):
    g.line(svg("M18,46 C11,48 7.5,45 8.5,39"))
    g.put(svg("M8.5,40.5 C6,39 6,35.5 8.6,33.5 C11,35.5 11,39 8.5,40.5 Z"), "gold")
    body = oval(17, 33, 39.5, 55)
    g.piece(rrect(19.5, 47, 25, 57.4, 2.7), rrect(30.5, 47, 36, 57.4, 2.7), body)
    g.inside(join(oval(13, 37, 23, 47), oval(29, 46.5, 36, 52)), body, "blue")
    g.put(svg("M20,17.5 C14,14.5 8.5,16.5 7.5,20.5 C11.5,24 17,23.5 21,21.5 Z "
              "M41.5,13.5 C45,9.5 50.5,9.5 53,11.5 C51,16.5 46.5,18 42,17.5 Z"), "gold")
    head = oval(18, 9.5, 45, 33)
    g.piece(svg("M22,12.5 C20.6,10.4 20.4,8.2 21.4,6.4 C23.4,7.4 25,9.4 25.6,11.6 Z "
                "M36.6,11.2 C37.4,9 39,7.3 41,6.5 C41.6,8.6 41,10.7 39.8,12.5 Z"), head)
    g.inside(svg("M17,8 C25,8 28.5,13 27.5,19 C26.5,25 22,28.5 16,27 Z"), head, "blue")
    g.put(join(oval(30.4, 16.4, 34.6, 20.6), oval(38.9, 15.9, 43.1, 20.1)), "blue")  # eyes
    g.piece(oval(30, 22, 46.5, 32.5))  # muzzle
    g.put(join(oval(34.8, 25.2, 37.6, 27.8), oval(40.4, 25.2, 43.2, 27.8)), "blue")  # nostrils
    g.line(svg("M36.5,30 Q39,31.4 41.5,30"), W * 0.8, "blue")


def horse(g):
    g.put(svg("M100,40 C108,38.5 113,44.5 112.5,54 C109.5,50 106,48 100.5,48.5 Z"), "gold")
    g.piece(rrect(95, 47, 100.5, 57.4, 2.7), rrect(84, 47, 89.5, 57.4, 2.7), oval(80.5, 33, 103, 55),
            svg("M89,27 C88.5,32 87.5,36 86.5,40 L100,40 C101,34 101.5,28 101,23 Z"))
    g.piece(svg("M88,12 C87.6,9.4 88.4,7 90,5.4 C92,7.2 93.2,9.2 93.6,11.4 Z "
                "M96.5,10 C97.6,7.8 99.2,6.2 101.4,5.4 C101.8,7.8 101.4,10.2 100,12.4 Z"),
            svg("M97,8.5 C90,8.5 84,13 79.5,19 C75.5,24.5 75,31 80.5,32.6 C85.5,34 90.5,31 94,29 "
                "C100,27 104,22 103.5,16 C103,11 100.5,8.5 97,8.5 Z"))
    g.line(svg("M80.5,21 C84,22.5 87.5,26 87.5,29.5"), W * 0.8, "blue")  # the muzzle's edge
    g.put(oval(88.9, 14.4, 93.1, 18.6), "blue")  # eye
    g.put(join(oval(77, 24.6, 79.6, 27.2), oval(81, 26, 83.6, 28.6)), "blue")  # nostrils
    g.line(svg("M79.6,30.4 Q82.4,31.8 85,29.8"), W * 0.8, "blue")
    g.piece(svg("M101.5,11 C98,7.2 92,7.4 87.5,11.8 C90.5,11.4 92.6,12.6 93.8,14.8 C95,12.8 97.4,12.4 99.4,13.6 "
                "C100.6,19 100.2,26 98.4,31.5 C99.8,34.5 101.8,37 104.2,38.5 C107,32 107,18 101.5,11 Z"))  # mane


def arm(g):
    """The cow's front leg from its shoulder (DuelView's sword arm, no sword), the hoof at its end."""
    a, n = math.radians(P["arm"]), P["arm_len"]
    hx, hy = 35 + math.cos(a) * n, 41 + math.sin(a) * n
    cap = rrect(-2.6, -2.6, n + 2.6, 2.6, 2.6).transform(math.cos(a), math.sin(a), -math.sin(a), math.cos(a), 35, 41)
    g.piece(cap, oval(hx - 2.6 - W / 2, hy - 2.6 - W / 2, hx + 2.6 + W / 2, hy + 2.6 + W / 2))


# --- the icon -------------------------------------------------------------------------------------

g = Painter()
s, lean = P["scale"], P["lean"]
cow_at = (P["cow_x"], P["feet_y"], lean, s, 28, 57.4)
horse_at = (P["horse_x"], P["feet_y"], -lean, s, 92, 57.4)
# The shield behind them, their hooves on it.
sh = shield(54, P["shield_top"], P["shield_w"], P["shield_h"])
g.inside(stroked(sh, 2 * P["rim"]), sh)
g.frame(*cow_at); cow(g)
g.frame(*horse_at); horse(g)
g.frame(*cow_at); arm(g)
g.frame(*horse_at); g.m = (-g.m[0], -g.m[1], g.m[2], g.m[3], g.m[0] * 120 + g.m[4], g.m[1] * 120 + g.m[5]); arm(g)


def num(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s == "-0" else s


def data(p):
    """SVG path data. A qCurveTo segment can hold several quadratics, with implied points between."""
    def xy(*pts):
        return " ".join(f"{num(x)} {num(y)}" for x, y in pts)
    out = []
    for verb, pts in p.segments:
        if verb == "qCurveTo":
            out += ["Q" + xy(c, e) for c, e in decompose_quadratic_segment(pts)]
        else:
            out.append({"moveTo": "M", "lineTo": "L", "curveTo": "C", "closePath": "Z", "endPath": ""}[verb] + xy(*pts))
    return "".join(out)


gold = data(g.shapes["gold"])
far = max(math.hypot(x - 54, y - 54) for _, pts in g.shapes["gold"].segments for x, y in pts)
print(f"farthest point from the centre: {far:.1f} (the safe zone is 33, a round mask shows 36)", file=sys.stderr)


def vector(comment, color):
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            f"<!-- {comment} Drawn by android/icon/crest.py; edit that, not this. -->\n"
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="108dp" android:height="108dp"\n'
            '    android:viewportWidth="108" android:viewportHeight="108">\n'
            f'    <path android:fillColor="{color}" android:pathData="{gold}" />\n'
            "</vector>\n")


if res:
    open(f"{res}/ic_fg.xml", "w").write(vector(
        "The cow and the horse holding up UM's shield, in its gold, on the blue of @color/ic_bg.",
        "#FF" + P["gold"][1:].upper()))
    open(f"{res}/ic_mono.xml", "w").write(vector("Themed icon: the same drawing in one colour.", "#FFFFFFFF"))

body = f'<rect width="108" height="108" fill="{P["blue"]}"/><path fill="{P["gold"]}" d="{gold}"/>'
for name, mask in (("circle", '<circle cx="54" cy="54" r="36"/>'),
                   ("squircle", '<rect x="18" y="18" width="72" height="72" rx="22"/>')):
    open(f"preview-{name}.svg", "w").write(
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72"><defs><clipPath id="m">{mask}</clipPath>'
        f'</defs><g clip-path="url(#m)">{body}</g></svg>')
