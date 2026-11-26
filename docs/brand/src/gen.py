#!/usr/bin/env python3
"""Laura icon concepts: one source for the SVG sheets and the Android vector drawables.

Every concept is a list of layers; a layer is a list of shapes (path data in a 108 x 108 adaptive-icon
viewport, fill colour or gradient, optional opacity). render.sh turns the HTML sheets into PNGs.
"""
import os
import sys

OUT = os.path.dirname(os.path.abspath(__file__))

# palette (brief section 5, plus the wine tail and the lantern amber)
EYE = "#1A1230"
SKIN = "#F3E9F2"
SKIN_WARM = "#FFE4CC"
HAIR_LIT = "#5A4386"
HAIR_MID = "#46336E"
HAIR_SH = "#2A1E44"
GLOSS = "#7D67B4"
WINE = "#8C3567"
WINE_SH = "#5E2147"
WINE_LIT = "#B0548A"
AMBER = "#FFC27A"
BLUSH = "#FF8FA8"
MOUTH = "#6B2B4E"
NIGHT = "#1E1830"


def lin(x1, y1, x2, y2, *stops):
    return ("linear", (x1, y1, x2, y2), stops)


def rad(cx, cy, r, *stops):
    return ("radial", (cx, cy, r), stops)


def S(d, fill, op=1.0):
    return {"d": d, "fill": fill, "op": op}


def rrect(x, y, w, h, r):
    r = min(r, w / 2, h / 2)
    return (f"M{x + r:g},{y:g}H{x + w - r:g}A{r:g},{r:g} 0 0 1 {x + w:g},{y + r:g}V{y + h - r:g}"
            f"A{r:g},{r:g} 0 0 1 {x + w - r:g},{y + h:g}H{x + r:g}A{r:g},{r:g} 0 0 1 {x:g},{y + h - r:g}"
            f"V{y + r:g}A{r:g},{r:g} 0 0 1 {x + r:g},{y:g}Z")


def ell(cx, cy, rx, ry):
    return f"M{cx - rx:g},{cy:g}A{rx:g},{ry:g} 0 1 0 {cx + rx:g},{cy:g}A{rx:g},{ry:g} 0 1 0 {cx - rx:g},{cy:g}Z"


def poly(pts):
    return "M" + "L".join(f"{x:g},{y:g}" for x, y in pts) + "Z"


def full():
    return "M0,0H108V108H0Z"


# ---------------------------------------------------------------- B: bob and tail (flat vector, voxel-stepped fringe)
def concept_b(variant="final"):
    cx = 51  # head centre, a little left so the tail balances it
    face = f"M{cx - 16},45H{cx + 16}V66C{cx + 16},74.5 {cx + 8.5},80 {cx},80C{cx - 8.5},80 {cx - 16},74.5 {cx - 16},66Z"
    # dome + fringe, one shape; stepped fringe edge (lower on the viewer's left, rising to the right)
    l, r = cx - 22, cx + 22
    hair = (f"M{l},53C{l},35.5 {l + 9},26.5 {cx},26.5C{r - 9},26.5 {r},35.5 {r},53"
            f"H{cx + 16}V49H{cx + 12}V47H{cx + 5}V49.5H{cx - 3}V51.5H{cx - 10}V54H{cx - 16}V53Z")
    # side locks frame the cheeks down to the jaw line, never under the chin
    lockL = f"M{l},50V77.5Q{l},80 {l + 2.5},80H{cx - 13.5}V73H{cx - 16}V50Z"
    lockR = f"M{r},50V77.5Q{r},80 {r - 2.5},80H{cx + 13.5}V73H{cx + 16}V50Z"
    tail = (f"M{r - 3},34C{r + 7},30.5 {r + 15},38 {r + 14.5},50C{r + 14},60 {r + 10},68 {r + 6},76"
            f"C{r + 4.6},71 {r + 4.8},64 {r + 6},58C{r + 7.5},50 {r + 6},42 {r - 1},39.5Z")
    tail_hi = f"M{r + 3},36C{r + 9},37 {r + 12},43 {r + 11.6},50C{r + 11.3},54 {r + 10.3},58 {r + 9.2},61C{r + 9.6},55 {r + 9},45 {r + 3},36Z"
    tie = rrect(r - 4.2, 32.2, 5.2, 5.2, 1.3)
    gloss = (f"M{cx - 15.5},40C{cx - 13},32.5 {cx - 5},29.2 {cx + 3},29.6C{cx - 4},31 {cx - 10},34.5 {cx - 13},41Z")
    ex, ey, ew, eh = 7.2, 58.5, 6.4, 10
    eyes = rrect(cx - ex - ew / 2, ey - eh / 2, ew, eh, ew / 2) + rrect(cx + ex - ew / 2, ey - eh / 2, ew, eh, ew / 2)
    catch = rrect(cx - ex - 2.2, ey - 3.6, 2.6, 2.6, 0.7) + rrect(cx + ex - 2.2, ey - 3.6, 2.6, 2.6, 0.7)
    blush = ell(cx - 11.5, 66.2, 3.2, 1.9) + ell(cx + 11.5, 66.2, 3.2, 1.9)
    mouth = f"M{cx - 2.6},68.6Q{cx},71.2 {cx + 2.6},68.6Q{cx},73.4 {cx - 2.6},68.6Z"

    bg = [S(full(), rad(46, 42, 66, (0, "#FFF4E4"), (0.55, "#FFDDB0"), (1, "#F5AE6A")))]
    if variant == "night":
        bg = [S(full(), rad(50, 50, 62, (0, "#4A3358"), (0.6, "#2A2040"), (1, NIGHT)))]
    fg = [
        S(lockL + lockR, lin(0, 50, 0, 80, (0, HAIR_MID), (1, HAIR_SH))),
        S(tail, lin(0, 32, 0, 76, (0, WINE), (1, WINE_SH))),
        S(tail_hi, WINE_LIT, 0.55),
        S(face, lin(0, 46, 0, 60, (0, "#FFD2A6"), (0.45, SKIN_WARM), (1, SKIN))),
        S(hair, lin(0, 26, 0, 53, (0, HAIR_LIT), (1, HAIR_MID))),
        S(gloss, GLOSS, 0.7),
        S(tie, AMBER),
        S(blush, BLUSH, 0.85),
        S(eyes, EYE),
        S(catch, "#FFFFFF"),
        S(mouth, MOUTH),
    ]
    # monochrome: hair, tail and tie as one ink; the face is a window with the eyes and mouth inked in it
    mono = [
        S(hair + lockL + lockR + tail + tie, "#000000"),
        S(eyes + mouth, "#000000"),
    ]
    return {"bg": bg, "fg": fg, "mono": mono, "mono_hole": face}


# ---------------------------------------------------------------- A: voxel portrait (pixel grid, lantern-dark background)
A_ART = [
    "......wHHHHHHHw.......",
    "....wHHHgggggHHHw.....",
    "...HHHgHHHHHHHgHHt....",
    "..HHHHHHHHHHHHHHHtTT..",
    "..HHHHHHHHHHHHHHHHTTT.",
    ".hHHHHHHHHHHHHHHHh.TT.",
    ".hHHHHHHHHHHHHHHHh.TTT",
    ".hHHHkkHHHHHHkHHHh..TT",
    ".hHHkkkkHHkkkkkkHh..TT",
    ".hHkkSSSSSSSSSSSkh..TT",
    ".hHSScESSSSScESSSh.TT.",
    ".hHSSEESSSSSEESSSh.TT.",
    ".hHSSEESSSSSEESSSh.T..",
    ".hHSBSSSSSSSSSBSSh.T..",
    ".hhSSSSSSMMMSSSSSh....",
    ".hh.SSSSSSSSSSSSShh...",
    ".hh..SSSSSSSSSSS.hh...",
    "..h...SSSSSSSSS...h...",
]
A_COL = {"H": HAIR_LIT, "h": HAIR_SH, "w": HAIR_MID, "g": GLOSS, "S": SKIN, "k": "#F9DCCB", "E": EYE, "c": "#FFFFFF",
         "B": BLUSH, "M": MOUTH, "T": WINE, "t": AMBER}


def grid_paths(art, cell, ox, oy, colmap):
    out = {}
    for j, row in enumerate(art):
        i = 0
        while i < len(row):
            ch = row[i]
            if ch == ".":
                i += 1
                continue
            k = i
            while k < len(row) and row[k] == ch:
                k += 1
            out.setdefault(ch, []).append(f"M{ox + i * cell:g},{oy + j * cell:g}h{(k - i) * cell:g}v{cell:g}h{-(k - i) * cell:g}Z")
            i = k
    return [(ch, colmap[ch], "".join(ds)) for ch, ds in out.items()]


def concept_a():
    cell = 3.1
    w, h = len(A_ART[0]), len(A_ART)
    ox, oy = 54 - w * cell / 2 + 1.5, 54 - h * cell / 2 + 1
    parts = grid_paths(A_ART, cell, ox, oy, A_COL)
    order = "hwHgTtkSBEcM"
    parts.sort(key=lambda p: order.index(p[0]))
    fg = [S(d, c) for ch, c, d in parts]
    bg = [S(full(), "#171225"), S(full(), rad(50, 46, 40, (0, "#7A4A3A"), (0.5, "#3A2530"), (1, "#171225")))]
    ink = "".join(d for ch, c, d in parts if ch in "hwHgTt")
    feat = "".join(d for ch, c, d in parts if ch in "EM")
    hole = "".join(d for ch, c, d in parts if ch in "kSBc")
    return {"bg": bg, "fg": fg, "mono": [S(ink, "#000"), S(feat, "#000")], "mono_hole": hole}


# ---------------------------------------------------------------- C: lantern cube (isometric porcelain voxel)
def concept_c():
    k = 0.78
    cx, cy = 52, 56

    def P(x, y):
        return (cx + x * k, cy + y * k)

    top = [P(0, -38), P(32, -20), P(0, -2), P(-32, -20)]
    left = [P(-32, -20), P(0, -2), P(0, 36), P(-32, 18)]
    right = [P(0, -2), P(32, -20), P(32, 18), P(0, 36)]

    # skew helper for the left (front) face: u along the top edge, v down
    def L(u, v):
        x0, y0 = P(-32, -20)
        return (x0 + u * 32 * k, y0 + u * 18 * k + v * 38 * k)

    def R(u, v):
        x0, y0 = P(0, -2)
        return (x0 + u * 32 * k, y0 - u * 18 * k + v * 38 * k)

    fringe = poly([L(0, 0), L(1, 0), L(1, 0.30), L(0.78, 0.30), L(0.78, 0.22), L(0.5, 0.22), L(0.5, 0.33),
                   L(0.22, 0.33), L(0.22, 0.42), L(0, 0.42)])
    sidehair = poly([R(0, 0), R(1, 0), R(1, 0.86), R(0.8, 0.86), R(0.8, 0.5), R(0.25, 0.42), R(0.08, 0.95), R(0, 0.95)])
    glow_edge = poly([L(0, 0.42), L(0.22, 0.42), L(0.22, 0.33), L(0.5, 0.33), L(0.5, 0.22), L(0.78, 0.22), L(0.78, 0.30),
                      L(1, 0.30), L(1, 0.37), L(0.78, 0.37), L(0.5, 0.40), L(0.22, 0.48), L(0, 0.5)])

    def eye(u0):
        return poly([L(u0, 0.5), L(u0 + 0.13, 0.5), L(u0 + 0.13, 0.75), L(u0, 0.75)])

    def catch(u0):
        return poly([L(u0 + 0.02, 0.53), L(u0 + 0.07, 0.53), L(u0 + 0.07, 0.6), L(u0 + 0.02, 0.6)])
    eyes = eye(0.22) + eye(0.63)
    catches = catch(0.22) + catch(0.63)
    blush = poly([L(0.12, 0.8), L(0.24, 0.8), L(0.24, 0.85), L(0.12, 0.85)]) + poly([L(0.74, 0.8), L(0.86, 0.8), L(0.86, 0.85), L(0.74, 0.85)])
    mouth = poly([L(0.45, 0.82), L(0.58, 0.82), L(0.555, 0.87), L(0.475, 0.87)])
    tx, ty = R(0.62, 0.0)
    tail = (f"M{tx - 2:g},{ty - 1:g}C{tx + 10:g},{ty - 6:g} {tx + 14:g},{ty + 8:g} {tx + 12:g},{ty + 20:g}"
            f"C{tx + 10:g},{ty + 30:g} {tx + 6:g},{ty + 36:g} {tx + 3:g},{ty + 40:g}C{tx + 3:g},{ty + 30:g} {tx + 7:g},{ty + 20:g} {tx + 4:g},{ty + 8:g}Z")
    tie = rrect(tx - 3, ty - 4, 6, 6, 1.5)
    bg = [S(full(), lin(0, 0, 0, 108, (0, "#2B2148"), (1, "#151022")))]
    fg = [
        S(poly(top), lin(0, 14, 0, 55, (0, GLOSS), (1, HAIR_LIT))),
        S(poly(left), lin(0, 40, 0, 84, (0, SKIN_WARM), (1, SKIN))),
        S(poly(right), "#C9BCD8"),
        S(sidehair, HAIR_SH),
        S(glow_edge, AMBER, 0.9),
        S(fringe, HAIR_MID),
        S(tail, lin(0, ty, 0, ty + 40, (0, WINE), (1, WINE_SH))),
        S(tie, AMBER),
        S(blush, BLUSH, 0.85),
        S(eyes, EYE),
        S(catches, "#FFFFFF"),
        S(mouth, MOUTH),
    ]
    ink = poly(top) + sidehair + fringe + tail + tie
    return {"bg": bg, "fg": fg, "mono": [S(ink, "#000"), S(eyes + mouth, "#000")], "mono_hole": poly(left) + poly(right)}


CONCEPTS = {
    "A": ("Voxel portrait", concept_a),
    "B": ("Bob and tail", concept_b),
    "C": ("Lantern cube", concept_c),
}

# ---------------------------------------------------------------- SVG output
_gid = [0]


def svg_shapes(shapes, tint=None):
    defs, body = [], []
    for s in shapes:
        f = s["fill"]
        if tint:
            fill = tint
        elif isinstance(f, tuple):
            _gid[0] += 1
            gid = f"g{_gid[0]}"
            stops = "".join(f'<stop offset="{o}" stop-color="{c}"/>' for o, c in f[2])
            if f[0] == "linear":
                x1, y1, x2, y2 = f[1]
                defs.append(f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}">{stops}</linearGradient>')
            else:
                gx, gy, gr = f[1]
                defs.append(f'<radialGradient id="{gid}" gradientUnits="userSpaceOnUse" cx="{gx}" cy="{gy}" r="{gr}">{stops}</radialGradient>')
            fill = f"url(#{gid})"
        else:
            fill = f
        op = f' fill-opacity="{s["op"]}"' if s["op"] != 1 and not tint else ""
        body.append(f'<path d="{s["d"]}" fill="{fill}"{op}/>')
    return "".join(defs), "".join(body)


SQUIRCLE = "M54,18C26,18 18,26 18,54C18,82 26,90 54,90C82,90 90,82 90,54C90,26 82,18 54,18Z"
CIRCLE = "M18,54A36,36 0 1 0 90,54A36,36 0 1 0 18,54Z"
MASKS = {"circle": CIRCLE, "squircle": SQUIRCLE}


def icon_svg(c, size, mask="circle", mode="color", theme=None):
    """mode: color | mono (themed icon: theme = (bg, fg)) | fg (no mask, transparent) | watch."""
    _gid[0] += 1
    cid = f"m{_gid[0]}"
    if mode == "mono":
        tb, tf = theme
        hole_id = f"h{_gid[0]}"
        d1, b1 = svg_shapes(c["mono"][:1], tint=tf)
        d2, b2 = svg_shapes(c["mono"][1:], tint=tf)
        # the themed icon is the monochrome layer's alpha; the face window is left out of the ink
        body = (f'<path d="{MASKS[mask]}" fill="{tb}"/>'
                f'<mask id="{hole_id}" maskUnits="userSpaceOnUse" x="0" y="0" width="108" height="108"><rect width="108" height="108" fill="#fff"/>'
                f'<path d="{c["mono_hole"]}" fill="#000"/></mask><g mask="url(#{hole_id})">{b1}</g>{b2}')
        defs = d1 + d2
    else:
        dbg, bbg = svg_shapes(c["bg"])
        dfg, bfg = svg_shapes(c["fg"])
        defs, body = dbg + dfg, bbg + bfg
    vb = "18 18 72 72"
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="{vb}">'
            f'<defs>{defs}<clipPath id="{cid}"><path d="{MASKS[mask]}"/></clipPath></defs>'
            f'<g clip-path="url(#{cid})">{body}</g></svg>')


def notif_svg(size, color="#fff"):
    d1, b1 = svg_shapes(notif_shapes()[:1], tint=color)
    d2, b2 = svg_shapes(notif_shapes()[1:], tint=color)
    _gid[0] += 1
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="0 0 24 24">'
            f'{b1}{b2}</svg>')


def notif_shapes():
    """24 dp status-bar glyph: the bob-and-tail head with the face cut out (evenodd), the eyes inked in."""
    # scale the 108 art into 24 dp: head spans x 29..87 in art; map so it fills ~22 dp
    return [S(NOTIF_INK, "#fff"), S(NOTIF_EYES, "#fff")]


def _notif_geom():
    # 24 dp version of concept B: the hair frame (dome, stepped fringe, side locks) with the face left open
    # down to the chin, the eyes as two islands, and the side tail. Nothing sits under the chin.
    cx, l, r = 10.4, 1.4, 19.4
    hair = (f"M{l},11.6C{l},5.4 {l + 3.8},2 {cx},2C{r - 3.8},2 {r},5.4 {r},11.6V20.4Q{r},21.4 {r - 1},21.4H{cx + 5.6}"
            f"V9.6H{cx - 0.6}V11.6H{cx - 5.6}V21.4H{l + 1}Q{l},21.4 {l},20.4Z")
    tail = (f"M{r - 1.4},4.6C{r + 1.4},4 {r + 3.4},7 {r + 3.2},11C{r + 3},14.5 {r + 2},17 {r + 0.8},19.4"
            f"C{r + 0.6},17 {r + 0.8},15 {r + 1},13C{r + 1.3},10 {r + 0.6},7.8 {r - 1.4},7Z")
    eyes = rrect(cx - 3.9, 13.2, 2.4, 4.2, 1.2) + rrect(cx + 1.5, 13.2, 2.4, 4.2, 1.2)
    return hair + tail, eyes


NOTIF_INK, NOTIF_EYES = _notif_geom()


# ---------------------------------------------------------------- Android vector drawable output
def _hex_android(c, op=1.0):
    c = c.lstrip("#")
    if len(c) == 3:
        c = "".join(ch * 2 for ch in c)
    a = int(round(op * 255))
    return f"#{a:02X}{c.upper()}"


def vd_paths(shapes, tint=None, evenodd=False):
    out = []
    for s in shapes:
        f = s["fill"]
        fr = '\n        android:fillType="evenOdd"' if evenodd else ""
        if tint:
            out.append(f'    <path\n        android:fillColor="{tint}"{fr}\n        android:pathData="{s["d"]}" />')
        elif isinstance(f, tuple):
            stops = "\n".join(
                f'                <item android:color="{_hex_android(c, s["op"])}" android:offset="{o}" />' for o, c in f[2])
            if f[0] == "linear":
                x1, y1, x2, y2 = f[1]
                g = (f'android:type="linear" android:startX="{x1}" android:startY="{y1}" '
                     f'android:endX="{x2}" android:endY="{y2}"')
            else:
                gx, gy, gr = f[1]
                g = f'android:type="radial" android:centerX="{gx}" android:centerY="{gy}" android:gradientRadius="{gr}"'
            out.append(f'    <path\n        android:pathData="{s["d"]}">\n        <aapt:attr name="android:fillColor">\n'
                       f'            <gradient {g}>\n{stops}\n            </gradient>\n        </aapt:attr>\n    </path>')
        else:
            out.append(f'    <path\n        android:fillColor="{_hex_android(f, s["op"])}"\n        android:pathData="{s["d"]}" />')
    return "\n".join(out)


def vd(body, size=108, vp=108, aapt=False):
    ns = ' xmlns:aapt="http://schemas.android.com/aapt"' if aapt else ""
    return (f'<vector xmlns:android="http://schemas.android.com/apk/res/android"{ns}\n'
            f'    android:width="{size}dp"\n    android:height="{size}dp"\n'
            f'    android:viewportWidth="{vp}"\n    android:viewportHeight="{vp}">\n{body}\n</vector>\n')


def write_android(root):
    c = concept_b()
    app = os.path.join(root, "clients/android/app/src/main/res/drawable")
    core = os.path.join(root, "clients/android/core/src/main/res/drawable")
    wear = os.path.join(root, "clients/wear/src/main/res/drawable")
    files = {}
    files[f"{app}/ic_launcher_background.xml"] = vd(vd_paths(c["bg"]), aapt=True)
    files[f"{app}/ic_launcher_foreground.xml"] = vd(vd_paths(c["fg"]), aapt=True)
    # monochrome: alpha only; the face window is cut out of the hair ink with evenodd, the eyes and mouth go back in
    files[f"{app}/ic_launcher_monochrome.xml"] = vd(
        vd_paths(c["mono"], tint="#FF000000"))
    adaptive = ('<?xml version="1.0" encoding="utf-8"?>\n'
                '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <background android:drawable="@drawable/ic_launcher_background" />\n'
                '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
                '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n'
                '</adaptive-icon>\n')
    files[f"{app}/ic_launcher.xml"] = adaptive
    files[f"{app}/ic_launcher_round.xml"] = adaptive
    # notification: white silhouette, 24 dp
    files[f"{core}/ic_laura.xml"] = vd(
        f'    <path\n        android:fillColor="#FFFFFFFF"\n        android:pathData="{NOTIF_INK}" />\n'
        f'    <path\n        android:fillColor="#FFFFFFFF"\n        android:pathData="{NOTIF_EYES}" />', size=24, vp=24)
    # watch: a round, self-contained icon (the system crops it to a circle; keep everything inside r 36 of 108)
    files[f"{wear}/ic_launcher.xml"] = vd(
        "    <group android:translateX=\"-18\" android:translateY=\"-18\">\n"
        "    <clip-path android:pathData=\"" + CIRCLE + "\" />\n" +
        vd_paths(c["bg"] + c["fg"]) + "\n    </group>", size=48, vp=72, aapt=True)
    for p, t in files.items():
        with open(p, "w") as fh:
            fh.write(t)
        print("wrote", os.path.relpath(p, root))




# ---------------------------------------------------------------- sheets
WALL_LIGHT = "linear-gradient(160deg,#EFE9F7,#D9E6F2 60%,#F3E7DA)"
WALL_DARK = "linear-gradient(160deg,#1A1C24,#12151C 60%,#231A22)"
THEME_LIGHT = ("#E9DDFF", "#3F2D6E")
THEME_DARK = ("#2A2438", "#D3C2FF")

CSS = """
body{margin:0;width:1790px;font:14px/1.35 'DejaVu Sans',sans-serif;background:#f6f4f1;color:#222}
h1{font-size:22px;margin:0 0 4px} h2{font-size:17px;margin:0 0 2px} .sub{color:#666;margin:0 0 10px;font-size:12.5px}
.wrap{padding:22px;display:flex;gap:22px;flex-wrap:nowrap}
.col{background:#fff;border-radius:14px;padding:16px;box-shadow:0 1px 3px #0002;width:560px}
.wall{border-radius:10px;padding:12px;margin-top:10px;display:flex;align-items:center;gap:16px;flex-wrap:wrap}
.wall .lab{font-size:11px;width:100%;opacity:.75}
.light{background:%WL%;color:#333} .dark{background:%WD%;color:#ddd}
.row{display:flex;align-items:center;gap:14px}
.hero{display:flex;gap:14px;align-items:center}
.cap{font-size:11px;color:#777;text-align:center}
.pick{outline:4px solid #E8A04C;outline-offset:3px}
""".replace("%WL%", WALL_LIGHT).replace("%WD%", WALL_DARK)


def wall_block(c, cls, theme):
    h = [f'<div class="wall {cls}"><div class="lab">{"light" if cls == "light" else "dark"} wallpaper: circle and squircle at 192 / 96 / 48 px, then themed (monochrome)</div>']
    for m in ("circle", "squircle"):
        h.append('<div class="row">' + "".join(icon_svg(c, s, m) for s in (192, 96, 48)) + "</div>")
    h.append('<div class="row">' + "".join(icon_svg(c, s, "circle", "mono", theme) for s in (96, 48)) +
             "".join(icon_svg(c, s, "squircle", "mono", theme) for s in (96, 48)) + "</div>")
    h.append("</div>")
    return "".join(h)


def concepts_html(blurbs, winner):
    cols = []
    for key, (name, fn) in CONCEPTS.items():
        c = fn()
        cls = "col pick" if key == winner else "col"
        cols.append(f'<div class="{cls}"><h2>{key}. {name}{"  (picked)" if key == winner else ""}</h2><p class="sub">{blurbs[key]}</p>'
                    + wall_block(c, "light", THEME_LIGHT) + wall_block(c, "dark", THEME_DARK) + "</div>")
    return (f"<!doctype html><html><head><meta charset=utf-8><style>{CSS}</style></head><body>"
            f'<div style="padding:22px 22px 0"><h1>Laura app icon: three concepts</h1>'
            f'<p class="sub">Adaptive icon (108 dp canvas, 72 dp visible) under the circle and squircle masks. Real pixel sizes. '
            f'Themed rows use the monochrome layer tinted the way Android 13+ does.</p></div>'
            f'<div class="wrap">{"".join(cols)}</div></body></html>')


def final_html():
    c = concept_b()
    hero = ('<div class="hero">' + icon_svg(c, 432, "squircle") + icon_svg(c, 432, "circle") +
            '<div>' + layer_card(c) + "</div></div>")
    walls = wall_block(c, "light", THEME_LIGHT) + wall_block(c, "dark", THEME_DARK)
    small = [f'<div class="wall dark"><div class="lab">notification glyph (white, 24 dp) at 96 / 48 / 24 px, and the watch icon at 192 / 96 / 48 px</div>'
             f'<div class="row">{notif_svg(96)}{notif_svg(48)}{notif_svg(24)}'
             f'<div style="width:24px"></div>{watch_svg(192)}{watch_svg(96)}{watch_svg(48)}</div></div>'
             f'<div class="wall light"><div class="lab">notification glyph tinted dark, as a light status bar shows it</div>'
             f'<div class="row">{notif_svg(96, "#2A2438")}{notif_svg(48, "#2A2438")}{notif_svg(24, "#2A2438")}</div></div>']
    return (f"<!doctype html><html><head><meta charset=utf-8><style>{CSS}</style></head><body><div style='padding:22px'>"
            f"<h1>Laura app icon: final (B, bob and tail)</h1><p class='sub'>Squircle and circle at 432 px, the three layers, "
            f"launcher sizes on light and dark wallpapers, themed icon, notification glyph and watch icon.</p>"
            f"{hero}<div style='display:flex;gap:22px'><div style='width:620px'>{walls}</div><div style='width:620px'>{''.join(small)}</div></div>"
            f"</div></body></html>")


def layer_card(c):
    def raw(shapes, size=150):
        d, b = svg_shapes(shapes)
        return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="0 0 108 108" '
                f'style="background:repeating-conic-gradient(#ddd 0 25%,#fff 0 50%) 0 0/12px 12px;border-radius:6px">'
                f'<defs>{d}</defs>{b}<path d="{CIRCLE}" fill="none" stroke="#E8A04C" stroke-width=".6" stroke-dasharray="2 2"/>'
                f'<circle cx="54" cy="54" r="33" fill="none" stroke="#3a8" stroke-width=".6" stroke-dasharray="1 2"/></svg>')
    mono = (f'<svg xmlns="http://www.w3.org/2000/svg" width="150" height="150" viewBox="0 0 108 108" '
            f'style="background:repeating-conic-gradient(#ddd 0 25%,#fff 0 50%) 0 0/12px 12px;border-radius:6px">'
            f'<mask id="mh"><rect width="108" height="108" fill="#fff"/><path d="{c["mono_hole"]}"/></mask>'
            f'<g mask="url(#mh)">{svg_shapes(c["mono"][:1], tint="#000")[1]}</g>{svg_shapes(c["mono"][1:], tint="#000")[1]}</svg>')
    return ('<div class="row">' + "".join(f'<div>{x}<div class="cap">{n}</div></div>' for x, n in
                                          ((raw(c["bg"]), "background"), (raw(c["fg"]), "foreground"), (mono, "monochrome"))) +
            '</div><p class="cap" style="text-align:left">dashed orange: visible 72 dp; dotted green: 66 dp safe zone</p>')


def watch_svg(size):
    c = concept_b()
    return icon_svg(c, size, "circle")


BLURBS = {
    "A": "FACE lane. Her front view rebuilt on a voxel grid (22 x 18 cells): the avatar itself, eyes and blush, tail on the right, "
         "on the dark lantern glow. Most faithful, busiest at 48 px.",
    "B": "HAIR lane. Flat vector: the bob and the wine side tail are one outline, a porcelain face window with pill eyes, "
         "a stepped (voxel) fringe and a warm lantern ground. Fewest parts, strongest silhouette and monochrome.",
    "C": "LIGHT lane. She is a porcelain voxel lantern seen from above: an isometric cube with a plum cap, the face on the front "
         "plane, amber glow at the hairline seam. Most 'voxel', but the face is skewed and small.",
}

if __name__ == "__main__":
    what = sys.argv[1] if len(sys.argv) > 1 else "sheets"
    if what == "android":
        write_android(sys.argv[2])
    else:
        os.makedirs(os.path.join(OUT, "build"), exist_ok=True)
        with open(os.path.join(OUT, "build/concepts.html"), "w") as f:
            f.write(concepts_html(BLURBS, "B"))
        with open(os.path.join(OUT, "build/final.html"), "w") as f:
            f.write(final_html())
        print("sheets written")
