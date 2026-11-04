"""Draws the launcher icon: a cow (牛) and a horse (马) rearing up on either side of an empty shield in
UM's shape, holding it up the way the two tigers hold up the arms of Malaysia, in the same proportions:
upright and slim, their heads well above the shield, a front hoof on each of its top corners, standing on
their hind legs; the shield about two fifths of the width. Realistic silhouettes, all in the gold of UM's
shield rim on its blue (the adaptive icon's background, @color/ic_bg).

    uv run --with skia-pathops python crest.py ../app/src/main/res/drawable [key=value ...]

Writes ic_fg.xml, ic_mono.xml and the status-bar icon ic_notify.xml (the shield alone) there, and
preview-circle.svg / preview-squircle.svg here (with no directory, only the previews).

Each animal is one outline (COW, HORSE: points round it, smoothed into curves, a corner where marked),
drawn rearing upright in a 100 x 170 box facing right, hind hooves on its bottom; the horse is turned to
face the cow. Placed by its hind hooves, then slid sideways until its near front hoof rests on the shield's
corner. The cow is told from a goat by a cow's head: big and boxy, a tall square muzzle and a deep jaw, a
short horn reaching forward, an ear out sideways; and a dewlap, an udder, a tufted tail. Where an animal covers the shield, a blue gap of one line width parts them. Path
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
    scale=0.235,    # the animals: icon units per unit of their 100 x 170 box
    rim=2.6,        # the shield's gold rim
    # As on Malaysia's arms: the shield about two fifths of the width, about four fifths of a supporter's height,
    # its top where their front hooves rest and their heads well above it.
    shield_w=28.0, shield_h=30.3,
    cow_x=33.0, horse_x=75.0, feet_y=70.0,  # where each one's hind hooves stand, before sliding to the shield
    lean=0.0,       # degrees each is turned about its hind hooves (they're drawn upright)
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
# In a 100 x 170 box, rearing upright, facing right, hind hooves on y = 166. A point (x, y, "c") is a
# corner (a hoof, a horn's tip); the rest are smoothed through. Their front legs reach forward, the near
# one's hoof the frontmost point, the one that rests on the shield.

HORSE = [
    # ears and the long head, turned toward the shield, nose a little down; the jaw's round cheek
    (58, 4, "c"), (62, 12), (68, 14), (77, 18), (85, 25), (90, 31), (91, 36, "c"), (86, 39), (79, 38), (74, 38), (69, 37), (66, 40),
    # the throat, down the neck's front to a deep chest
    (65, 47), (65, 55), (67, 63),
    # near foreleg, reaching forward: forearm, knee, cannon, hoof
    (73, 63), (80, 60), (86, 58, "c"), (91, 63), (95, 66, "c"), (92, 71, "c"), (88, 69), (84, 66), (77, 70), (70, 74),
    # far foreleg, lower
    (76, 78), (82, 78, "c"), (86, 84), (89, 88, "c"), (85, 91, "c"), (81, 87), (76, 85), (67, 86),
    # belly, down to the flank
    (63, 96), (60, 106), (58, 115),
    # near hind leg, standing: stifle, the hock behind, cannon, hoof
    (59, 124), (57, 134), (53, 143), (54, 152), (57, 160), (61, 166, "c"), (51, 167, "c"), (47, 160), (45, 152), (44, 146), (40, 140),
    # far hind leg
    (38, 147), (38, 155), (40, 160), (43, 166, "c"), (33, 167, "c"), (30, 160), (29, 151), (27, 142),
    # buttock and the tail, falling in waves
    (24, 132), (22, 121), (22, 110), (17, 111), (11, 119), (8, 131), (9, 145), (13, 157, "c"), (15, 146), (16, 134), (19, 123), (23, 115),
    # the back up to the withers, the mane in tufts up the arched neck, the poll
    (26, 99), (31, 84), (37, 71), (42, 60), (45, 53),
    (42, 48), (47, 44), (46, 38), (51, 34), (50, 27), (55, 23), (54, 16), (57, 11),
]

COW = [
    # the poll; a short thick horn reaching forward from it, curling up at the tip
    (56, 21), (60, 17), (66, 14), (71, 10), (74, 4, "c"), (73, 12), (69, 18), (66, 22),
    # the broad forehead, the long straight face, steep, down to a tall square muzzle
    (69, 24), (75, 30), (80, 37), (84, 44), (87, 49), (89, 53), (89.5, 59, "c"), (84, 61.5), (78, 61.5),
    # the deep flat jaw, back to a round cheek; the throat and a dewlap down the neck, the chest
    (71, 61), (65, 59.5), (61, 61), (60, 67), (61, 72), (64, 76), (67, 78),
    # near foreleg, reaching forward
    (74, 74), (81, 68), (86, 64, "c"), (90, 68), (93, 71, "c"), (89, 75, "c"), (86, 73), (82, 72), (75, 78), (69, 83),
    # far foreleg
    (75, 87), (81, 85, "c"), (85, 90), (88, 93, "c"), (84, 96, "c"), (80, 93), (75, 93), (67, 95),
    # a deeper belly, the udder
    (63, 106), (61, 114), (60, 120), (61, 124), (59, 128, "c"), (55, 127),
    # near hind leg, standing: thicker than the horse's, the hock behind
    (56, 134), (55, 142), (52, 149), (53, 157), (56, 166, "c"), (46, 167, "c"), (44, 159), (42, 151), (40, 144), (38, 140),
    # far hind leg
    (37, 148), (37, 157), (40, 166, "c"), (30, 167, "c"), (28, 158), (27, 148), (25, 140),
    # buttock, the tail hanging close, ending in a tuft
    (23, 130), (21, 118), (21, 108), (17, 113), (15, 125), (14, 138), (12, 150), (10, 157), (13, 163, "c"), (19, 162), (18, 155, "c"),
    (18, 145), (19, 132), (20, 120),
    # tailhead, the straight back, withers and the thick neck up to the poll
    (22, 102, "c"), (27, 92), (33, 80), (39, 67), (45, 54), (49, 43), (51, 35),
    # the ear: a broad leaf, straight out behind the horn
    (49, 32), (43, 31), (37, 30, "c"), (42, 26), (48, 24), (52, 23),
]

FEET = (45, 167)  # between the hind hooves, in the box


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
cow, cow_hoof = slid(placed(COW, P["cow_x"], False), 54 - w / 2 + 1.4, max)
horse, horse_hoof = slid(placed(HORSE, P["horse_x"], True), 54 + w / 2 - 1.4, min)
top = (cow_hoof + horse_hoof) / 2 - 1.0  # the hooves just over the rim
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
