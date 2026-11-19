"""Draws the launcher icon: a cow (牛) and a horse (马) rearing up on either side of an empty shield in
UM's shape, holding it up the way the two tigers hold up the arms of Malaysia: their heads well above
it, one front hoof on its top and the other against its side, one hind leg raised. The shield is larger
than the jata's, nearly half the width and as tall as a supporter (the user found it too small at two
fifths). Realistic
silhouettes in gold, light at the top and deep at the bottom like the metal, on UM's blue (the adaptive
icon's background, @color/ic_bg).

    uv run --with skia-pathops python crest.py ../app/src/main/res/drawable [key=value ...]

Writes ic_fg.xml, ic_mono.xml and the status-bar icon ic_notify.xml (the shield alone) there, and
preview-circle.svg / preview-squircle.svg / preview-study.svg here (with no directory, only the previews).

Each animal is drawn standing, the way it's easiest to judge against a photo (preview-study.svg shows
both standing and reared), and then posed: the trunk turned up about the hip, the neck and the head
turned back down, each leg bone and the tail given a direction. The cow is told from the horse by a
deep barrel on short legs, a straight back, a blunt square muzzle, a pair of short horns with an ear
behind them, an udder and a thin tail ending in a tuft; the horse by long legs, an arched neck, pricked
ears and a full tail. The cow is sized so both stand on the same ground with their hooves on the same
shield. Nothing overlaps the shield: each hoof stops a line's width short of it, and where an animal
would cover it, a gap of that width parts them. Path booleans resolve it all into one shape, which is
also the themed icon (ic_mono). They're not DuelView's pair (the duel has its own line drawings).
"""
import math
import re
import sys

from pathops import LineCap, LineJoin, Path, PathOp, decompose_quadratic_segment, op

P = dict(
    blue="#223E99",  # UM's blue
    gold_hi="#F7E08A", gold="#D9AE3A", gold_lo="#B4841C",  # gold from the top of the drawing to its bottom
    line=0.9,       # the gap between a hoof and the shield, in icon units (dp in the 108-unit icon)
    scale=0.165,    # the horse: icon units per unit it's drawn in (it stands 92 high at the withers)
    rim=2.8,        # the shield's rim
    shield_w=30, shield_h=31,
    inset=1.6,      # how far in from the shield's corner the upper front hoof rests
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


def union(shapes):
    out = Path()
    for s in shapes:
        out = op(out, s, PathOp.UNION)
    return out


def circle(x, y, r):
    k = 0.5523 * r
    return svg(f"M{x + r},{y} C{x + r},{y + k} {x + k},{y + r} {x},{y + r} C{x - k},{y + r} {x - r},{y + k} {x - r},{y} "
               f"C{x - r},{y - k} {x - k},{y - r} {x},{y - r} C{x + k},{y - r} {x + r},{y - k} {x + r},{y} Z")


def limb(joints, steps=3):
    """A leg or a tail: a tube along a smooth line through [joints] (x, y, half width), tapering between
    them. Built from round beads joined by their tangents, so a wide thigh on a sharp bend can't fold over."""
    n = len(joints)
    at = lambda i: joints[min(max(i, 0), n - 1)]
    beads = []
    for i in range(n - 1):
        p0, p1, p2, p3 = at(i - 1), at(i), at(i + 1), at(i + 2)
        for k in range(steps + (i == n - 2)):
            t = k / steps
            f = [((-t + 2) * t - 1) * t / 2, ((3 * t - 5) * t * t + 2) / 2, ((-3 * t + 4) * t + 1) * t / 2, (t - 1) * t * t / 2]
            beads.append(tuple(sum(w * p[c] for w, p in zip(f, (p0, p1, p2, p3))) for c in (0, 1)) + (p1[2] + (p2[2] - p1[2]) * t,))
    parts = [circle(*j) for j in beads]
    for (x0, y0, r0), (x1, y1, r1) in zip(beads, beads[1:]):
        d = math.hypot(x1 - x0, y1 - y0)
        if d <= abs(r0 - r1):
            continue
        ux, uy = (x1 - x0) / d, (y1 - y0) / d
        sin = (r0 - r1) / d
        cos = math.sqrt(1 - sin * sin)
        side = lambda k: (ux * sin - k * uy * cos, uy * sin + k * ux * cos)  # toward where a side touches both beads
        (ax, ay), (bx, by) = side(1), side(-1)
        parts.append(svg(f"M{x0 + r0 * ax},{y0 + r0 * ay} L{x1 + r1 * ax},{y1 + r1 * ay} "
                         f"L{x1 + r1 * bx},{y1 + r1 * by} L{x0 + r0 * bx},{y0 + r0 * by} Z"))
    return smoothed(union(parts))


def smoothed(shape, gap=2.5):
    """[shape]'s outline redrawn as one flowing curve through points [gap] apart along it."""
    out, line = [], []
    for verb, pts in list(shape.segments) + [("moveTo", ())]:
        if verb == "moveTo":
            if len(line) > 2:
                lengths = [math.dist(a, b) for a, b in zip(line, line[1:] + line[:1])]
                n, walked, i, pts2 = max(8, round(sum(lengths) / gap)), 0.0, 0, []
                for k in range(n):
                    want = k * sum(lengths) / n
                    while walked + lengths[i] < want:
                        walked, i = walked + lengths[i], i + 1
                    t, (ax, ay), (bx, by) = (want - walked) / lengths[i], line[i], line[(i + 1) % len(line)]
                    pts2.append((ax + (bx - ax) * t, ay + (by - ay) * t))
                out.append(outline(pts2))
            line = list(pts)
        elif verb == "lineTo":
            line += pts
        elif verb == "curveTo":
            (x0, y0), (x1, y1), (x2, y2), (x3, y3) = line[-1], *pts
            line += [((1 - t) ** 3 * x0 + 3 * (1 - t) ** 2 * t * x1 + 3 * (1 - t) * t * t * x2 + t ** 3 * x3,
                      (1 - t) ** 3 * y0 + 3 * (1 - t) ** 2 * t * y1 + 3 * (1 - t) * t * t * y2 + t ** 3 * y3)
                     for t in (0.25, 0.5, 0.75, 1)]
        elif verb == "qCurveTo":
            for (x1, y1), (x2, y2) in decompose_quadratic_segment(pts):
                x0, y0 = line[-1]
                line += [((1 - t) ** 2 * x0 + 2 * (1 - t) * t * x1 + t * t * x2, (1 - t) ** 2 * y0 + 2 * (1 - t) * t * y1 + t * t * y2)
                         for t in (0.25, 0.5, 0.75, 1)]
    return union(out)


# --- the horse and the cow ---------------------------------------------------------------------------
# Each is drawn standing, facing right, the ground at y = 0 (so heights are negative). trunk, neck, head,
# horn and extra are outlines: points round them, smoothed into curves, a corner where marked "c". The
# neck's first point sits inside the trunk so that, turned down, the neck runs on from the back. A leg is
# where it joins the trunk, its half width there, and its bones (length, half width at the far end): hind
# legs are thigh, gaskin in two, cannon in two and pastern, front legs upper arm, forearm in two, cannon
# in two and pastern; hoof is (half width, height, toe). The legs are the standing hind one, the raised
# hind one, the upper front one and the lower front one. The tail is bones too.
# A pose turns the trunk about the hip (lean), the neck about neck_at and the head about the poll, all
# anticlockwise in degrees, and gives every bone its direction (0 = forward, 90 = down, 180 = back) and
# every hoof the direction its sole faces.

HORSE = dict(
    trunk=[(0, -84), (7, -91), (18, -93), (32, -90.5), (46, -87.5), (58, -88.5), (67, -93),
           (76, -86), (85, -73), (86.5, -65), (82, -57), (72, -53), (64, -52), (50, -51.5), (36, -53.5),
           (25, -58), (19, -58), (10, -57), (2, -63), (-3, -73), (-2.5, -80)],
    neck=[(58, -80), (66, -101), (75, -110), (84, -116), (93, -119),
          (94, -100), (92.5, -92), (90.5, -83), (88.5, -73), (86, -62), (72, -60), (58, -74)],
    head=[(89.6, -119), (93.6, -130.2, "c"), (96.4, -122.3), (99.2, -129.1, "c"), (100.8, -120.7),   # the ears
          (105.3, -116.2), (109.8, -108.9), (113.7, -101.1), (116, -95.5), (116, -91), (112.6, -88.7),  # the face, the muzzle
          (108.1, -91.5), (103.1, -94.3), (97.5, -96.6), (92.4, -101.1), (89.6, -110)],                 # the jaw, the jowl
    tail=dict(at=(2, -84), r=3, bones=[(7, 5), (10, 6.2), (12, 6), (12, 4.5), (10, 0.8)]),
    hip=(11, -73), neck_at=(74, -80), poll=(93, -114.5), dock=(0, -84),
    legs=[
        dict(at=(13, -74), r=13.5, bones=[(24, 8), (12, 6), (12, 4.3), (10.5, 2.8), (10.5, 3.2), (6, 2.7)], hoof=(3.2, 5, 3)),
        dict(at=(14, -74), r=13, bones=[(24, 8), (12, 6), (12, 4.3), (10.5, 2.8), (10.5, 3.2), (6, 2.7)], hoof=(3.2, 5, 3)),
        dict(at=(78, -70), r=8, bones=[(16.5, 6.5), (12.5, 5), (12.5, 3.9), (9.5, 2.6), (9.5, 3), (7, 2.5)], hoof=(3, 5, 3)),
        dict(at=(77, -69), r=8, bones=[(16.5, 6.5), (12.5, 5), (12.5, 3.9), (9.5, 2.6), (9.5, 3), (7, 2.5)], hoof=(3, 5, 3)),
    ],
    stand=dict(lean=0, neck=0, head=0, tail=[160, 120, 95, 85, 80], hoof=[90, 90, 90, 90],
               legs=[[65, 115, 115, 88, 88, 60], [80, 112, 112, 95, 95, 60], [115, 90, 90, 90, 90, 60], [105, 80, 80, 95, 95, 60]]),
    rear=dict(lean=60, neck=-40, head=-3, tail=[150, 120, 100, 92, 88], hoof=[90, 60, 90, 0],
              legs=[[50, 128, 128, 78, 78, 50], [15, 100, 100, 50, 50, 35], [50, -45, -45, 40, 40, 55], [75, 22, 22, 100, 100, 50]]),
)

COW = dict(
    trunk=[(0, -78, "c"), (9, -78.5), (24, -76), (42, -75), (58, -76), (70, -78),
           (80, -70), (88, -56), (86, -44), (78, -37), (68, -33), (54, -31), (40, -31), (28, -33),
           (22, -38), (12, -40), (3, -46), (-2, -58), (-3, -70)],
    neck=[(55, -68), (76, -80.5), (86, -83), (95, -85), (94, -66), (91, -58), (90, -50), (88, -42), (74, -42), (62, -62)],
    head=[(91, -81), (86, -84), (81, -91, "c"), (88, -89.5), (92, -87.5),                           # the ear, up and back
          (93, -92), (96.5, -97), (101, -98, "c"), (98, -93.5), (97.5, -89.5), (99, -86),          # the horn
          (104, -83), (110, -78.5), (115, -73.5), (119, -69.5), (121.5, -65), (121.5, -60), (119, -56.5, "c"),  # the face, a square muzzle
          (112, -56), (105, -57), (99, -59.5, "c"), (95, -66), (91, -75)],
    tail=dict(at=(1, -76), r=2.4, bones=[(5, 2.2), (11, 2), (11, 2), (7, 2.4), (7, 4), (6, 0.8)]),
    extra=[[(16, -36), (30, -35), (35, -29), (33, -23.5), (31, -22), (30.5, -18, "c"), (28.5, -22), (25, -22.5), (20, -26)]],  # the udder
    horn=[(99, -86), (99.5, -92), (103, -96.5), (107.5, -97, "c"), (104.5, -93), (104, -89), (105, -84), (101, -83)],  # the far horn
    hip=(12, -58), neck_at=(78, -64), poll=(95, -79), dock=(0, -77),
    legs=[
        dict(at=(14, -59), r=15.5, bones=[(19, 10.5), (10, 7.5), (10, 5.2), (8, 3.8), (8, 4.2), (4, 3.6)], hoof=(4, 4.5, 2.6)),
        dict(at=(15, -59), r=15, bones=[(19, 10.5), (10, 7.5), (10, 5.2), (8, 3.8), (8, 4.2), (4, 3.6)], hoof=(4, 4.5, 2.6)),
        dict(at=(77, -57), r=10, bones=[(18, 8), (9, 6.2), (9, 5), (6.5, 3.6), (6.5, 4), (4, 3.4)], hoof=(3.8, 4.5, 2.6)),
        dict(at=(76, -56), r=10, bones=[(18, 8), (9, 6.2), (9, 5), (6.5, 3.6), (6.5, 4), (4, 3.4)], hoof=(3.8, 4.5, 2.6)),
    ],
    stand=dict(lean=0, neck=0, head=0, tail=[150, 110, 95, 90, 88, 85], hoof=[90, 90, 90, 90],
               legs=[[60, 118, 118, 86, 86, 65], [78, 112, 112, 95, 95, 65], [115, 90, 90, 90, 90, 65], [105, 82, 82, 95, 95, 65]]),
    rear=dict(lean=58, neck=-20, head=-12, tail=[140, 108, 96, 92, 90, 90], hoof=[90, 60, 90, 0],
              legs=[[50, 125, 125, 80, 80, 55], [15, 100, 100, 55, 55, 40], [50, -45, -45, 40, 40, 55], [75, 29, 29, 100, 100, 50]]),
)


def bones(x, y, r, parts, angles):
    """The joints of a chain of bones from (x, y): each bone's length and far half width, and its direction."""
    joints = [(x, y, r)]
    for (length, r), deg in zip(parts, angles):
        x, y = x + length * math.cos(math.radians(deg)), y + length * math.sin(math.radians(deg))
        joints.append((x, y, r))
    return joints


def turn(p, about, deg):
    """[p] turned [deg] anticlockwise (as seen, y pointing down) about a point; keeps a corner mark."""
    a, dx, dy = math.radians(deg), p[0] - about[0], p[1] - about[1]
    return (about[0] + dx * math.cos(a) + dy * math.sin(a), about[1] - dx * math.sin(a) + dy * math.cos(a)) + tuple(p[2:])


def posed(a, pose):
    """The animal's shape in [pose], its standing hind hoof on y = 0; and where each hoof's sole is."""
    hip, t = a["hip"], pose["lean"]
    trunk = lambda p: turn(p, hip, t)
    neck = lambda p: trunk(turn(p, a["neck_at"], pose["neck"]))
    head = lambda p: neck(turn(p, a["poll"], pose["head"]))
    shapes = [outline([trunk(p) for p in a["trunk"]]), outline([neck(p) for p in a["neck"]]),
              outline([head(p) for p in a["head"]])]
    shapes += [outline([trunk(p) for p in e]) for e in a.get("extra", [])]
    shapes += [outline([head(p) for p in a[k]]) for k in ("horn",) if k in a]
    shapes.append(limb(bones(*trunk(a["tail"]["at"]), a["tail"]["r"], a["tail"]["bones"], pose["tail"])))
    soles = []
    for leg, angles, down in zip(a["legs"], pose["legs"], pose["hoof"]):
        joints = bones(*trunk(leg["at"]), leg["r"], leg["bones"], angles)
        shapes.append(limb(joints))
        x, y = joints[-1][:2]
        c, h, toe = leg["hoof"]
        ux, uy = math.cos(math.radians(down)), math.sin(math.radians(down))  # down the hoof
        fx, fy = uy, -ux                                                    # toward its toe
        at = lambda f, u: (x + f * fx + u * ux, y + f * fy + u * uy, "c")
        shapes.append(outline([at(-c, -1.5), at(c, -1.5), at(c + toe, h), at(-c - 0.6, h)]))
        soles.append((x + toe / 2 * fx + h * ux, y + toe / 2 * fy + h * uy))
    ground = soles[0][1]
    shape = union(shapes).transform(1, 0, 0, 1, 0, -ground)
    return shape, [(sx, sy - ground) for sx, sy in soles]


def placed(a, edge, top, face_left, size=1.0):
    """[a] rearing, scaled into the icon: its upper front hoof on the shield's top, a line's width above
    it and `inset` in from the side at x = [edge]. And how far its lower front hoof is from that side."""
    shape, soles = posed(a, a["rear"])
    s, (rx, ry), d = P["scale"] * size, soles[2], -1 if face_left else 1
    x, y = edge + d * P["inset"], top - P["line"]
    p = shape.transform(d * s, 0, 0, s, x - d * rx * s, y - ry * s)
    return op(p, Path(), PathOp.UNION), P["inset"] - (rx - soles[3][0]) * s


# --- the icon -------------------------------------------------------------------------------------

def icon(top):
    """The whole drawing as one shape, the shield's top at [top]."""
    w, h = P["shield_w"], P["shield_h"]
    sh = shield(54, top, w, h)
    gold = op(stroked(sh, 2 * P["rim"]), sh, PathOp.INTERSECTION)
    level = posed(HORSE, HORSE["rear"])[1][2][1] / posed(COW, COW["rear"])[1][2][1]  # the cow as tall as the horse
    for a, x, left, size in ((COW, 54 - w / 2, False, level), (HORSE, 54 + w / 2, True, 1.0)):
        shape, side = placed(a, x, top, left, size)
        if abs(-side - P["line"]) > 0.25:
            print(f"a lower front hoof is {-side:.2f} from the shield's side, not a line's width ({P['line']}): "
                  "turn its forearm", file=sys.stderr)
        gold = op(op(gold, stroked(shape, 2 * P["line"]), PathOp.DIFFERENCE), shape, PathOp.UNION)
    return gold


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


g = icon(40)
l, t, r, b = g.bounds
g = icon(40 + 54 - (t + b) / 2)  # centred top to bottom
l, t, r, b = g.bounds
gold = data(g)
far = max(math.hypot(x - 54, y - 54) for _, pts in g.segments for x, y in pts)
print(f"farthest point from the centre: {far:.1f} (the safe zone is 33, a round mask shows 36)", file=sys.stderr)
stops = ((0, P["gold_hi"]), (0.5, P["gold"]), (1, P["gold_lo"]))


# The status-bar icon, 24 units: the shield alone, its rim in one colour (the animals can't be read at
# that size).
N = dict(w=16.6, h=20.0, rim=2.0)
small = shield(12, 12 - N["h"] / 2, N["w"], N["h"])
notify = data(op(stroked(small, 2 * N["rim"]), small, PathOp.INTERSECTION))


def vector(comment, fill, path, box=108):
    """A vector drawable of one path; [fill] is a colour, or gradient stops running down the drawing."""
    if isinstance(fill, str):
        paint, aapt = f'<path android:fillColor="{fill}" android:pathData="{path}" />', ""
    else:
        aapt = ' xmlns:aapt="http://schemas.android.com/aapt"'
        items = "".join(f'\n                <item android:offset="{o}" android:color="#FF{c[1:].upper()}" />' for o, c in fill)
        paint = (f'<path android:pathData="{path}">\n        <aapt:attr name="android:fillColor">\n'
                 f'            <gradient android:type="linear" android:startX="54" android:startY="{num(t)}" '
                 f'android:endX="54" android:endY="{num(b)}">{items}\n'
                 "            </gradient>\n        </aapt:attr>\n    </path>")
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            f"<!-- {comment} Drawn by android/icon/crest.py; edit that, not this. -->\n"
            f'<vector xmlns:android="http://schemas.android.com/apk/res/android"{aapt}\n'
            f'    android:width="{box}dp" android:height="{box}dp"\n'
            f'    android:viewportWidth="{box}" android:viewportHeight="{box}">\n'
            f"    {paint}\n"
            "</vector>\n")


if res:
    open(f"{res}/ic_fg.xml", "w").write(vector(
        "The cow and the horse holding up UM's shield, in gold, on the blue of @color/ic_bg.", stops, gold))
    open(f"{res}/ic_mono.xml", "w").write(vector("Themed icon: the same drawing in one colour.", "#FFFFFFFF", gold))
    open(f"{res}/ic_notify.xml", "w").write(vector("Status-bar icon: UM's shield, in one colour.", "#FFFFFFFF", notify, 24))

grad = (f'<linearGradient id="g" gradientUnits="userSpaceOnUse" x1="54" y1="{num(t)}" x2="54" y2="{num(b)}">'
        + "".join(f'<stop offset="{o}" stop-color="{c}"/>' for o, c in stops) + "</linearGradient>")
body = f'<rect width="108" height="108" fill="{P["blue"]}"/><path fill="url(#g)" d="{gold}"/>'
open("preview-notify.svg", "w").write(
    f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path fill="#FFFFFF" d="{notify}"/></svg>')
for name, mask in (("circle", '<circle cx="54" cy="54" r="36"/>'),
                   ("squircle", '<rect x="18" y="18" width="72" height="72" rx="22"/>')):
    open(f"preview-{name}.svg", "w").write(
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72"><defs>{grad}<clipPath id="m">{mask}</clipPath>'
        f'</defs><g clip-path="url(#m)">{body}</g></svg>')

# The animals alone, black on white: the cow standing and reared, then the horse.
study = "".join(f'<path transform="translate({i * 330 + j * 170 + 30} 185)" d="{data(posed(a, a[pose])[0])}"/>'
                for i, a in enumerate((COW, HORSE)) for j, pose in enumerate(("stand", "rear")))
open("preview-study.svg", "w").write(
    f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 660 200"><rect width="660" height="200" fill="#fff"/>{study}</svg>')
