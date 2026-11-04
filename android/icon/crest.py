"""Draws the launcher icon: a cow (牛) and a horse (马) rearing up on either side of an empty shield in
UM's shape, holding it up the way the two tigers hold up the arms of Malaysia, a front hoof on each of
its top corners, standing on their hind legs. Realistic silhouettes, all in the gold of UM's shield rim
on its blue (the adaptive icon's background, @color/ic_bg).

    uv run --with skia-pathops python crest.py ../app/src/main/res/drawable [key=value ...]

Writes ic_fg.xml, ic_mono.xml and the status-bar icon ic_notify.xml (the shield alone) there, and
preview-circle.svg / preview-squircle.svg here (with no directory, only the previews).

Each animal is one outline (COW, HORSE: points round it, smoothed into curves, a corner where marked),
drawn rearing in a 120 x 160 box facing right, hind hooves on its bottom; the horse is turned to face
the cow. Placed by its hind hooves, leaned upright, then slid sideways until its near front hoof rests on
the shield's corner. Where an animal covers the shield, a blue gap of one line width parts them. Path
booleans resolve it into one gold shape, so the blue is a hole rather than paint, and the themed icon
(ic_mono) is that same shape. They're not DuelView's pair (the duel has its own line drawings).
"""
import math
import re
import sys

from pathops import LineCap, LineJoin, Path, PathOp, decompose_quadratic_segment, op

P = dict(
    blue="#223E99", gold="#BB9632",  # UM crest colours: the shield and its rim
    line=1.0,       # the gap between an animal and the shield, in icon units (dp in the 108-unit icon)
    scale=0.26,     # the animals: icon units per unit of their 120 x 160 box
    rim=2.4,        # the shield's gold rim
    shield_w=21.0, shield_h=25.0,  # its top is where the hooves rest
    cow_x=35.0, horse_x=73.0, feet_y=70.0,  # where each one's hind hooves stand, before sliding to the shield
    lean=-18.0,     # degrees each is turned upright about its hind hooves (from the drawing's rear)
)
res = sys.argv[1] if len(sys.argv) > 1 and "=" not in sys.argv[1] else None
for kv in sys.argv[1 if res is None else 2:]:
    k, v = kv.split("=", 1)
    P[k] = v if isinstance(P[k], str) else float(v)


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

    def put(self, shape, colour):
        """[shape] painted [colour]: "gold", or "blue" to uncover the background."""
        for k in self.shapes:
            self.shapes[k] = op(self.shapes[k], shape, PathOp.UNION if k == colour else PathOp.DIFFERENCE)

    def inside(self, shape, within, colour="gold"):
        self.put(op(shape, within, PathOp.INTERSECTION), colour)


# --- the cow and the horse: rearing silhouettes ----------------------------------------------------
# In a 120 x 160 box, facing right, hind hooves on y = 156. A point (x, y, "c") is a corner (a hoof, a
# horn's tip); the rest are smoothed through. Their front legs are bent, a hoof forward; the near one's
# hoof is the frontmost point, the one that rests on the shield.

HORSE = [
    (75, 3, "c"), (79, 13), (86, 17), (95, 23), (103, 31), (108, 37), (108.5, 42, "c"), (103, 45), (96, 44),
    (90, 44), (86, 41), (83, 43), (80, 50), (79, 59), (80, 67), (83, 73),
    # near foreleg, raised and bent at the knee
    (91, 72), (97, 73), (100, 77, "c"), (101, 86), (103, 92), (106, 97, "c"), (100, 100, "c"), (98, 94), (95, 86), (93, 81), (86, 82), (78, 86),
    # far foreleg, lower
    (84, 90), (89, 92), (90.5, 95, "c"), (91, 103), (93, 109, "c"), (87, 110, "c"), (86, 104), (85, 98), (78, 98), (71, 97),
    # belly to the flank
    (64, 104), (57, 112), (51, 119),
    # near hind leg: stifle, hock, cannon, hoof
    (53, 127), (51, 135), (51, 142), (53, 148), (55, 152), (60, 156, "c"), (51, 157, "c"), (49, 152), (46, 146), (42, 140), (39, 133), (37, 129),
    # far hind leg
    (36, 135), (37, 143), (39, 149), (41, 152), (45, 156, "c"), (35, 157, "c"), (33, 151), (29, 144), (25, 138),
    # buttock and the tail, flowing
    (21, 128), (17, 117), (16, 107), (11, 106), (6, 113), (4, 124), (5, 136), (9, 146, "c"), (11, 136), (13, 126), (16, 117), (20, 108),
    # topline: croup, back, withers, the neck's crest with its mane in tufts, the poll
    (22, 97), (26, 88), (33, 78), (40, 68), (46, 59), (50, 52),
    (47, 48), (52, 45), (53, 40), (58, 37), (59, 31), (64, 28), (65, 22), (69, 18), (72, 12),
]

COW = [
    # two horns from the poll, curving up and forward
    (73, 37), (74, 30), (78, 22, "c"), (78.5, 30), (77, 36), (79, 33), (84, 26, "c"), (83, 34), (81, 38),
    # forehead, the face and the broad muzzle, the deep jaw
    (87, 41), (93, 47), (98, 54), (101, 60), (101.5, 66, "c"), (97, 69), (90, 69), (84, 66),
    # throat and the dewlap hanging under the neck, to the chest
    (80, 68), (80, 75), (82, 82), (86, 86),
    # near foreleg, raised: short and thick
    (94, 85), (99, 88, "c"), (100, 96), (102, 102, "c"), (95, 104, "c"), (93, 98), (91, 94), (85, 95), (79, 99),
    # far foreleg
    (84, 102), (89, 105, "c"), (90, 112), (92, 118, "c"), (85, 119, "c"), (83, 113), (81, 109), (73, 110),
    # the big round belly and the udder
    (66, 119), (60, 126), (56, 131), (53, 136, "c"), (49, 134),
    # near hind leg, planted: thick thigh, hock, hoof
    (49, 141), (49, 148), (52, 156, "c"), (43, 157, "c"), (42, 150), (40, 144), (37, 138),
    # far hind leg
    (35, 143), (36, 150), (38, 156, "c"), (29, 157, "c"), (28, 150), (24, 142), (20, 133),
    # thigh, buttock, the tail hanging close from its head, ending in a tuft
    (16, 120), (14, 106), (14, 95), (11, 102), (10, 114), (10, 126), (10, 136), (8, 143), (10, 150, "c"), (16, 149), (16, 142, "c"),
    (14, 134), (15, 122), (15, 110), (16, 100),
    # tailhead, the straight back, withers, the short thick neck to the poll
    (17, 89, "c"), (24, 85), (32, 79), (42, 72), (52, 64), (60, 56), (65, 50),
    # the ear, out sideways under the horns
    (63, 47), (55, 46, "c"), (61, 41), (68, 40),
]

FEET = (44, 156)  # between the hind hooves, in the box


def outline(pts):
    """A closed shape through [pts], smoothed (Catmull-Rom, as cubics), sharp at the corners."""
    p, n = Path(), len(pts)
    xy = [q[:2] for q in pts]
    corner = [len(q) > 2 for q in pts]
    p.moveTo(*xy[0])
    for i in range(n):
        p0, p1, p2, p3 = xy[i - 1], xy[i], xy[(i + 1) % n], xy[(i + 2) % n]
        c1 = p1 if corner[i] else (p1[0] + (p2[0] - p0[0]) / 6, p1[1] + (p2[1] - p0[1]) / 6)
        c2 = p2 if corner[(i + 1) % n] else (p2[0] - (p3[0] - p1[0]) / 6, p2[1] - (p3[1] - p1[1]) / 6)
        p.cubicTo(*c1, *c2, *p2)
    p.close()
    return op(p, Path(), PathOp.UNION)


def placed(pts, x0, face_left):
    """[pts] with the hind hooves at ([x0], feet_y), turned upright by lean, facing left if [face_left]."""
    a, s = math.radians(P["lean"]), P["scale"]
    out = []
    for q in pts:
        x, y = (q[0] - FEET[0]) * s, (q[1] - FEET[1]) * s
        x, y = x * math.cos(a) - y * math.sin(a), x * math.sin(a) + y * math.cos(a)
        out.append((x0 + (-x if face_left else x), P["feet_y"] + y) + tuple(q[2:]))
    return out


def slid(pts, to_x, front):
    """[pts] moved sideways so the frontmost of them is at [to_x]; that point's height."""
    f = front(pts, key=lambda q: q[0])
    return [(q[0] + to_x - f[0], q[1]) + tuple(q[2:]) for q in pts], f[1]


# --- the icon -------------------------------------------------------------------------------------

g = Painter()
w, h = P["shield_w"], P["shield_h"]
cow, cow_hoof = slid(placed(COW, P["cow_x"], False), 54 - w / 2 + 1.2, max)
horse, horse_hoof = slid(placed(HORSE, P["horse_x"], True), 54 + w / 2 - 1.2, min)
top = (cow_hoof + horse_hoof) / 2 - 1.2  # the hooves just over the rim
sh = shield(54, top, w, h)
g.inside(stroked(sh, 2 * P["rim"]), sh)
for animal in (cow, horse):
    shape = outline(animal)
    g.put(stroked(shape, 2 * P["line"]), "blue")
    g.put(shape, "gold")


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
