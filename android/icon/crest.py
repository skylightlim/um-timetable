"""Draws the launcher icon: the cow (牛) and the horse (马) of the app's duel holding up an empty shield
in UM's shape, the way the two tigers hold up the arms of Malaysia: standing up on either side, taller
than the shield, a front hoof on each of its top corners and a hind leg kicking its side. It's all in
the gold of UM's shield rim, on its blue (the adaptive icon's background, @color/ic_bg).

    uv run --with skia-pathops python crest.py ../app/src/main/res/drawable [key=value ...]

Writes ic_fg.xml, ic_mono.xml and the status-bar icon ic_notify.xml (the shield alone) there, and
preview-circle.svg / preview-squircle.svg here (with no directory, only the previews).

The animals are DuelView's shapes (its 120 × 60 box) as solid gold silhouettes, like the crest's yellow
tigers: a blue gap round each piece where it covers another, and blue showing through for the pupils,
brows, spots, six-packs and mouths. Their legs on the shield aren't DuelView's: each reaches from the
shoulder or hip to its point on the shield, behind the head or the body. Path booleans resolve the
drawing into one gold shape, so the blue is a hole rather than paint, and the themed icon (ic_mono) is
that same shape.
"""
import math
import re
import sys

from pathops import LineCap, LineJoin, Path, PathOp, decompose_quadratic_segment, op

P = dict(
    blue="#223E99", gold="#BB9632",  # UM crest colours: the shield and its rim
    line=1.0,       # the gaps between pieces, in icon units (dp in the 108-unit icon)
    scale=0.5,      # the animals: icon units per DuelView unit
    rim=2.4,        # the shield's gold rim
    # The shield fits between them, its top at their eyes: they stand taller than it, as the tigers do.
    shield_w=20.0, shield_h=24.5, shield_top=45.0,
    cow_x=30.0, horse_x=78.0, feet_y=67.0,  # where each one's standing hoof is, in the icon
    lean=3.0,       # degrees each leans in, towards the shield
    kick_at=0.72,   # how far down the shield's side the hind hooves kick it (0 top, 1 the point)
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


def turned(shape, deg, cx, cy):
    """[shape] turned [deg] about ([cx], [cy])."""
    a = math.radians(deg)
    c, sn = math.cos(a), math.sin(a)
    return shape.transform(c, sn, -sn, c, cx - c * cx + sn * cy, cy - sn * cx - c * cy)


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
# it where it covers another, and the details (eyes, brows, spots, six-packs, mouths) are blue showing
# through. They rear up like the tigers on Malaysia's arms: a front hoof on the shield's top corner,
# one hind leg standing, the other kicking the shield.

def eyes(g, eyes, pupils, brows):
    g.piece(*eyes)
    for x, y in pupils:
        g.put(oval(x - 2.4, y - 2.4, x + 2.4, y + 2.4), "blue")
        g.put(oval(x + 0.05, y - 1.75, x + 1.55, y - 0.25), "gold")  # the glint
    g.line(svg(brows), 2.3, "blue")


def six_pack(g, pecs, pack, lines):
    """The chest's arcs and the six-pack's lines, cut in the gold belly (an outlined block was too heavy here)."""
    g.line(svg(pecs), W * 0.8, "blue")
    g.line(op(pack, Path(), PathOp.UNION), W * 0.7, "blue")
    g.line(svg(lines), W * 0.7, "blue")


def local(g, x, y):
    """The icon point ([x], [y]) in the current frame's own units."""
    a, b, c, d, e, f = g.m
    det = a * d - b * c
    x, y = x - e, y - f
    return (d * x - c * y) / det, (-b * x + a * y) / det


def limb(g, base, target, width, bicep=False):
    """A leg from [base] (a shoulder or hip, in the animal's units) out to the icon point [target], its hoof there."""
    tx, ty = local(g, *target)
    bx, by = base
    n = math.hypot(tx - bx, ty - by)
    a = math.atan2(ty - by, tx - bx)
    m = (math.cos(a), math.sin(a), -math.sin(a), math.cos(a), bx, by)
    h = width / 2
    shapes = [rrect(-h, -h, n + h, h, h).transform(*m)]
    if bicep:
        shapes.append(oval(2.4, -5.2, 9.4, 1.2).transform(*m))
    g.piece(*shapes, oval(tx - h - W / 2, ty - h - W / 2, tx + h + W / 2, ty + h + W / 2))


def cow(g):
    g.line(svg("M14,46 C8,48 4.6,44 5.8,38.6"))
    g.put(svg("M5.8,40.2 C3.4,38.8 3.4,35.2 6,33.2 C8.4,35.2 8.4,38.8 5.8,40.2 Z"), "gold")
    body = oval(11, 14.5, 43, 55)
    muzzle = oval(29.6, 25.6, 44.2, 34.6)
    g.piece(rrect(17.5, 47, 24, 57.4, 3), body, muzzle)
    g.inside(join(oval(7.5, 39.5, 16.5, 49), svg("M9.5,22 C11.5,16.6 17.6,14.2 21.6,16.4 C20.8,21.2 16,24.8 10,26.4 Z")), body, "blue")
    six_pack(g, "M19.4,35.6 C21.4,38.2 25.2,38.8 27.6,36.8 M28.4,36.8 C31,38.8 34.6,38.2 36.6,35.6",
             rrect(22.6, 39, 33.4, 51.4, 3.4), "M28,39.6 L28,50.8 M23,43.2 Q28,44.4 33,43.2 M23,47.2 Q28,48.4 33,47.2")
    g.put(svg("M13.6,22.4 C8.6,20.2 4.4,21.6 3.6,25 C7.4,27.8 11.8,27.2 14.6,25.4 Z "
              "M40.6,19.2 C44,16 48.4,15.6 50.6,17.2 C49.2,21 45.4,22.6 41.4,22.4 Z"), "gold")  # ears
    g.piece(svg("M17,18 C13.8,15.2 13.2,11.2 14.8,8.4 C17.4,10.2 19.4,13.4 20.4,16.4 Z "
                "M33.4,15.6 C34.2,12.2 36.4,9.6 39.2,8.6 C39.8,11.4 38.8,14.4 36.8,16.8 Z"))  # horns
    g.line(muzzle, W * 0.8, "blue")
    eyes(g, (oval(23.8, 16.4, 31.6, 25.8), oval(31.2, 16.4, 39, 25.8)), ((28.8, 21.8), (36.2, 21.8)),
         "M23.6,14.6 L30.6,17.6 M32.4,17.6 L39.4,14.4")
    g.put(join(oval(34.4, 27.2, 36.6, 29.2), oval(39.2, 27.2, 41.4, 29.2)), "blue")  # nostrils
    mouth = svg("M34,30.6 Q38.4,31.8 42.6,30.2 C42.4,33.4 40.4,35.2 38.2,35 C35.8,34.8 34.2,33 34,30.6 Z")
    g.put(mouth, "blue")
    g.inside(oval(35.6, 32.6, 40.8, 36.2), mouth)  # the tongue


def horse(g):
    g.put(svg("M105.6,39.6 C113,38 117.4,44.2 116.8,53.8 C113.6,49.8 110,47.8 105.4,48.2 Z"), "gold")
    g.piece(rrect(96, 47, 102.5, 57.4, 3), oval(77, 25, 107, 55), svg("M89.6,22 L87.4,38 L103.6,38 L103.4,19 Z"))
    six_pack(g, "M83.4,37 C85.4,39.4 89.2,40 91.6,38 M92.4,38 C95,40 98.6,39.4 100.6,37",
             rrect(86.6, 39.6, 97.4, 51.8, 3.4), "M92,40.2 L92,51.2 M87,43.8 Q92,45 97,43.8 M87,47.8 Q92,49 97,47.8")
    g.piece(svg("M101.6,8.6 C104.6,11.6 106,15.2 106.2,19.2 C106.8,25.4 105.8,31.4 103.4,36.8 "
                "C101.2,35.4 99.8,33.6 99,31.4 C100.6,26.2 100.6,19.4 98.4,14.2 Z"))  # mane
    crown = oval(83.3, 4.2, 103.7, 24.6)
    face = svg("M92.63,11.63 C96.38,14.55 95.99,21.3 91.78,26.69 C87.56,32.09 81.11,34.1 77.37,31.17 "
               "C73.62,28.25 74.01,21.5 78.22,16.11 C82.44,10.71 88.89,8.7 92.63,11.63 Z")
    g.piece(svg("M87.8,8.6 C86.6,6 86.8,3.6 88,2 C90.6,2.9 92.6,5 93.4,7.2 Z "
                "M97.2,6.4 C98,3.8 99.8,2.2 102,1.8 C102.4,4.2 101.6,6.6 100.2,8.4 Z"), crown, face)
    muzzle = svg("M84.76,20.57 C87.63,22.81 87.92,27.24 85.4,30.46 C82.88,33.68 78.52,34.48 75.64,32.23 C72.77,29.99 72.48,25.56 75,22.34 C77.52,19.12 81.88,18.32 84.76,20.57 Z")
    g.inside(stroked(muzzle, W * 0.8), join(crown, face), "blue")  # the muzzle's edge
    g.piece(svg("M91.4,6.4 C91.4,3.8 93,1.8 95.6,1 C95,2.2 95,3.2 95.6,4.2 C96.8,2.4 99,1.8 101.2,2.2 "
                "C99.8,3 99,4.2 98.8,5.6 C100.6,5.2 102.6,5.8 104,7.4 C101.4,7.4 99.6,8.2 98.6,9.6 Z"))  # crest
    eyes(g, (oval(82, 8.8, 89.6, 18), oval(89.2, 8.8, 96.8, 18)), ((84.8, 14), (92, 14)),
         "M83.4,7.6 L89,9.8 M90.6,9.8 L96.6,7.4")
    g.put(join(svg("M77.5,23.85 C77.8,24.38 77.47,25.14 76.75,25.55 C76.03,25.97 75.2,25.88 74.9,25.35 C74.6,24.82 74.93,24.06 75.65,23.65 C76.37,23.23 77.2,23.32 77.5,23.85 Z"), svg("M81.3,25.25 C81.6,25.78 81.27,26.54 80.55,26.95 C79.83,27.37 79,27.28 78.7,26.75 C78.4,26.22 78.73,25.46 79.45,25.05 C80.17,24.63 81,24.72 81.3,25.25 Z")), "blue")  # nostrils
    teeth = join(rrect(77, 29.4, 79.6, 33.2, 0.9), rrect(79.8, 29.7, 82.4, 33.3, 0.9))
    g.put(stroked(teeth, W * 1.6), "blue")
    g.put(teeth, "gold")
    g.line(svg("M74.6,28.2 Q79.4,31.8 85,29.2"), W * 0.8, "blue")


# --- the icon -------------------------------------------------------------------------------------

g = Painter()
s, lean = P["scale"], P["lean"]
cow_at = (P["cow_x"], P["feet_y"], lean, s, 21, 57.4)      # the cow's standing hoof
horse_at = (P["horse_x"], P["feet_y"], -lean, s, 99, 57.4)  # the horse's
mirror = lambda m: (-m[0], -m[1], m[2], m[3], m[0] * 120 + m[4], m[1] * 120 + m[5])
# The shield, and their legs on it: a front hoof on each top corner (the leg behind the head), a hind
# hoof kicking each side (the leg behind the body). Both reach from the animal to those points.
w, top, h = P["shield_w"], P["shield_top"], P["shield_h"]
sh = shield(54, top, w, h)
g.inside(stroked(sh, 2 * P["rim"]), sh)
kick_y = top + P["kick_at"] * h
kick_dx = w / 2 * (1 - 0.55 * (P["kick_at"] - 0.36) / 0.64)  # where the side has curved in to, roughly
for at, flip, corner, side, hip in ((cow_at, False, (54 - w / 2, top), (54 - kick_dx, kick_y), (33.2, 49)),
                                    (horse_at, True, (54 + w / 2, top), (54 + kick_dx, kick_y), (33.2, 49))):
    g.frame(*at)
    if flip: g.m = mirror(g.m)
    limb(g, (35, 37), corner, 6.8, bicep=True)
    limb(g, hip, side, 6.5)
g.frame(*cow_at); cow(g)
g.frame(*horse_at); horse(g)


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


# The status-bar icon, 24 units: the shield alone, its rim in one colour (the animals can't be read at
# that size).
N = dict(w=16.6, h=20.0, rim=2.0)
small = shield(12, 12 - N["h"] / 2, N["w"], N["h"])
notify = data(op(stroked(small, 2 * N["rim"]), small, PathOp.INTERSECTION))


def vector(comment, color, path, box=108):
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            f"<!-- {comment} Drawn by android/icon/crest.py; edit that, not this. -->\n"
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:width="{box}dp" android:height="{box}dp"\n'
            f'    android:viewportWidth="{box}" android:viewportHeight="{box}">\n'
            f'    <path android:fillColor="{color}" android:pathData="{path}" />\n'
            "</vector>\n")


if res:
    open(f"{res}/ic_fg.xml", "w").write(vector(
        "The cow and the horse holding up UM's shield, in its gold, on the blue of @color/ic_bg.",
        "#FF" + P["gold"][1:].upper(), gold))
    open(f"{res}/ic_mono.xml", "w").write(vector("Themed icon: the same drawing in one colour.", "#FFFFFFFF", gold))
    open(f"{res}/ic_notify.xml", "w").write(vector("Status-bar icon: UM's shield, in one colour.", "#FFFFFFFF", notify, 24))

body = f'<rect width="108" height="108" fill="{P["blue"]}"/><path fill="{P["gold"]}" d="{gold}"/>'
open("preview-notify.svg", "w").write(
    f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path fill="#FFFFFF" d="{notify}"/></svg>')
for name, mask in (("circle", '<circle cx="54" cy="54" r="36"/>'),
                   ("squircle", '<rect x="18" y="18" width="72" height="72" rx="22"/>')):
    open(f"preview-{name}.svg", "w").write(
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72"><defs><clipPath id="m">{mask}</clipPath>'
        f'</defs><g clip-path="url(#m)">{body}</g></svg>')
