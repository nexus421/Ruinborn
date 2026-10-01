"""
Models of all placeholder sprites (buildings, units, decoration, icons) plus 2D tiles and effects.
Each function builds a scene or an image for ONE frame.
"""
import math
import random
import zlib

from PIL import Image, ImageDraw

from iso3d import (SS, TILE, Rx, Ry, Rz, S, Scene, T, fwd, mix, project, shade, ss_canvas, ss_finish)

# ---------------------------------------------------------------- Palette
P = dict(
    concrete=(176, 170, 156), concrete_dk=(142, 138, 128), concrete_lt=(196, 192, 184),
    dirt=(150, 124, 88), dirt_grass=(128, 128, 82), soil=(104, 78, 56),
    wall=(210, 196, 166), wall2=(192, 178, 150), roof_dk=(86, 90, 98), roof_red=(166, 72, 58),
    barn=(168, 64, 50), barn_door=(116, 42, 34), white=(236, 234, 226), white_wall=(228, 226, 218),
    wood=(160, 116, 74), wood_dk=(108, 76, 50), wood_lt=(206, 164, 110), log_end=(222, 184, 128),
    metal=(132, 140, 150), metal_dk=(82, 88, 98), metal_lt=(196, 200, 204), steel_wall=(122, 136, 152),
    olive=(104, 118, 64), olive_dk=(78, 90, 48), sand=(206, 186, 140), sand_dk=(178, 158, 114),
    window=(58, 88, 118), window_lit=(252, 214, 118), cyan=(110, 226, 236), door=(90, 70, 56),
    blue=(46, 118, 206), red=(200, 50, 44), yellow=(236, 188, 52), black=(40, 40, 44), orange=(236, 128, 40),
    crop=(112, 166, 58), crop_lt=(150, 196, 74), glass=(150, 196, 222), rust=(150, 88, 52),
    pine=(58, 108, 62), leaf=(86, 146, 64), bush=(74, 132, 58), rock=(132, 130, 124), bark=(96, 72, 52),
    skin=(226, 180, 138), zskin=(142, 164, 118), bskin=(170, 176, 150),
)
B = TILE


def t(v):
    """Tile units -> 3D length."""
    return v * B


def plate(sc, N, color):
    sc.box(t(0.05), t(0.05), 0, t(N - 0.05), t(N - 0.05), 3, color, ground=True, casts=False)


def flag(sc, x, y, z0, z_top, color, frame, frames, length=24.0, height=14.0, segs=5):
    sc.box(x - 1.2, y - 1.2, z0, x + 1.2, y + 1.2, z_top + 3, (96, 96, 104))
    sc.sphere(x, y, z_top + 4, 1.8, P["yellow"], n=6, rings=3)
    phase = 2 * math.pi * frame / max(frames, 1)
    top, bot = [], []
    for i in range(segs + 1):
        u = i / segs
        yy = y - 1.2 - u * length
        xx = x + math.sin(phase - u * 4.0) * 3.2 * u
        droop = u * 1.5
        top.append((xx, yy, z_top - droop))
        bot.append((xx, yy, z_top - height - droop))
    part = sc.new_part()
    for i in range(segs):
        sc.quad2([top[i], top[i + 1], bot[i + 1], bot[i]], color, part=part)


def sandbags(sc, x0, y0, x1, y1, z0=3, rows=2, along="y"):
    """Row of sandbags as small boxes."""
    h = 5.0
    L = (y1 - y0) if along == "y" else (x1 - x0)
    n = max(1, int(L // 9))
    for r in range(rows):
        off = 4.5 if r % 2 else 0.0
        for i in range(n - (1 if r % 2 else 0)):
            a = (y0 if along == "y" else x0) + off + i * (L / n)
            b = a + L / n - 1.0
            c = P["sand"] if (i + r) % 2 == 0 else P["sand_dk"]
            if along == "y":
                sc.box(x0, a, z0 + r * h, x1, b, z0 + (r + 1) * h, c)
            else:
                sc.box(a, y0, z0 + r * h, b, y1, z0 + (r + 1) * h, c)


def barrel(sc, x, y, z0=3, r=6, h=17, color=None, band=None):
    color = color or P["black"]
    sc.prism(x, y, z0, z0 + h, r, color, n=10, top=shade(color, 1.25))
    band = band or P["orange"]
    sc.prism(x, y, z0 + h * 0.3, z0 + h * 0.42, r + 0.35, band, n=10)
    sc.prism(x, y, z0 + h * 0.62, z0 + h * 0.74, r + 0.35, band, n=10)


def crate(sc, x0, y0, z0, x1, y1, z1):
    sc.box(x0, y0, z0, x1, y1, z1, P["wood"], top=P["wood_lt"])


# ================================================================ BUILDINGS
def building_hq(frame, frames):
    sc = Scene()
    plate(sc, 3, P["concrete"])
    sc.box(t(.45), t(.45), 3, t(2.55), t(2.55), 68, P["wall"])
    sc.box(t(.40), t(.40), 68, t(2.60), t(2.60), 74, P["roof_dk"])
    sc.box(t(1.0), t(1.0), 74, t(2.0), t(2.0), 116, P["wall2"])
    sc.box(t(.95), t(.95), 116, t(2.05), t(2.05), 121, P["roof_dk"])
    for yc in (0.8, 1.25, 1.75, 2.2):
        sc.decal_x(t(2.55), t(yc) - 8, t(yc) + 8, 42, 56, P["window"])
        sc.decal_y(t(2.55), t(yc) - 8, t(yc) + 8, 42, 56, P["window"])
        sc.decal_y(t(2.55), t(yc) - 8, t(yc) + 8, 16, 30, P["window"])
    for yc in (0.8, 2.2):
        sc.decal_x(t(2.55), t(yc) - 8, t(yc) + 8, 16, 30, P["window"])
    sc.decal_x(t(2.55), t(1.30), t(1.70), 3, 32, P["door"])
    sc.decal_x(t(2.55), t(1.24), t(1.76), 35, 40, P["blue"])
    for c in (1.3, 1.7):
        sc.decal_x(t(2.0), t(c) - 7, t(c) + 7, 88, 104, P["window_lit"])
        sc.decal_y(t(2.0), t(c) - 7, t(c) + 7, 88, 104, P["window_lit"])
    ax, ay = t(1.2), t(1.2)
    sc.box(ax - 1, ay - 1, 121, ax + 1, ay + 1, 178, P["metal_dk"])
    sc.box(ax - 8, ay - 1, 164, ax + 8, ay + 1, 166, P["metal_dk"])
    sc.box(ax - 5, ay - 1, 172, ax + 5, ay + 1, 174, P["metal_dk"])
    sc.box(ax - 1.5, ay - 1.5, 178, ax + 1.5, ay + 1.5, 181, P["red"], emissive=True)
    flag(sc, t(1.9), t(1.12), 121, 176, P["blue"], frame, frames, length=30, height=17)
    sandbags(sc, t(2.66), t(0.95), t(2.78), t(1.25), rows=2, along="y")
    sandbags(sc, t(2.66), t(1.75), t(2.78), t(2.05), rows=2, along="y")
    return sc


def building_farm(frame, frames):
    sc = Scene()
    plate(sc, 2, P["dirt_grass"])
    sc.box(t(1.0), t(.15), 3, t(1.88), t(1.85), 5, P["soil"], ground=True, casts=False)
    for i in range(7):
        y = t(.22) + i * t(.235)
        sc.box(t(1.05), y, 5, t(1.83), y + t(.12), 11, P["crop"], top=P["crop_lt"])
    sc.box(t(.2), t(.25), 3, t(.9), t(1.35), 38, P["barn"])
    sc.gable(t(.14), t(.19), t(.96), t(1.41), 38, 24, P["roof_dk"], P["barn"], ridge="y")
    sc.decal_x(t(.9), t(.56), t(1.04), 3, 31, P["white"])
    sc.decal_x(t(.9), t(.6), t(1.0), 3, 28, P["barn_door"], off=1.0)
    sc.decal_y(t(1.35), t(.45), t(.65), 20, 30, P["white"])
    sc.decal_y(t(1.35), t(.47), t(.63), 21.5, 28.5, P["window"], off=1.0)
    sc.prism(t(.5), t(1.65), 3, 62, 14, P["metal_lt"], n=12)
    sc.prism(t(.5), t(1.65), 22, 25, 14.4, P["metal"], n=12)
    sc.prism(t(.5), t(1.65), 42, 45, 14.4, P["metal"], n=12)
    sc.cone(t(.5), t(1.65), 62, 74, 15, P["roof_red"], n=12)
    crate(sc, t(.95), t(1.45), 3, t(1.12), t(1.62), 14)
    return sc


def building_oil_pump(frame, frames):
    sc = Scene()
    plate(sc, 2, P["concrete_dk"])
    tx, ty = t(.55), t(.55)
    sc.prism(tx, ty, 3, 44, 26, P["metal_lt"], n=14, top=P["white"])
    sc.prism(tx, ty, 18, 24, 26.4, P["rust"], n=14)
    sc.cone(tx, ty, 44, 51, 26, P["metal_lt"], n=14)
    yc = t(1.35)
    sc.box(t(.55), yc - 2, 3, t(1.7), yc + 2, 7, P["metal_dk"])
    sc.box(t(.55) - 2, t(.55) + 22, 3, t(.55) + 2, yc - 2, 7, P["metal_dk"])
    px, pz = t(1.25), 52.0
    # Pump jack enlarged 1.3x (scaled around its base point) so it reads well
    with sc.push(T(px, yc, 3) @ S(1.3) @ T(-px, -yc, -3)):
        sc.box(t(.82), yc - 8, 3, t(1.75), yc + 8, 8, P["metal_dk"])
        sc.beam((px - 12, yc - 7, 8), (px, yc - 2, pz - 2), 3, 3, P["yellow"])
        sc.beam((px - 12, yc + 7, 8), (px, yc + 2, pz - 2), 3, 3, P["yellow"])
        sc.beam((px + 12, yc, 8), (px, yc, pz - 2), 3, 3, P["yellow"])
        ang = 11.0 * math.sin(2 * math.pi * frame / frames)
        M = T(px, yc, pz) @ Ry(ang)
        with sc.push(M):
            sc.box(-34, -3, 0, 38, 3, 6, P["black"])
            sc.box(35, -3.5, -15, 44, 3.5, 8, P["yellow"])
            sc.box(-36, -4, -3, -30, 4, 3, P["black"])
        head = (M @ [41.5, 0, -15, 1])[:3]
        rear = (M @ [-33, 0, -2, 1])[:3]
        ccx, ccz = t(.9), 18.0
        phi = 360.0 * frame / frames
        pin = (ccx + 9 * math.cos(math.radians(phi)), yc + 6, ccz - 9 * math.sin(math.radians(phi)))
        sc.box(ccx - 3, yc - 5, 3, ccx + 3, yc + 5, ccz, P["metal_dk"])
        with sc.push(T(ccx, yc + 5.5, ccz) @ Ry(-phi)):
            sc.box(-4, -1.2, -4, 13, 1.2, 4, P["red"])
        sc.beam(pin, rear, 2.5, 2.5, P["metal"])
        wx = t(1.25) + 41.5
        sc.box(wx - 4, yc - 4, 3, wx + 4, yc + 4, 11, P["metal"], top=P["metal_lt"])
        sc.beam(head, (wx, yc, 11), 1.4, 1.4, P["metal_lt"])
    barrel(sc, t(1.72), t(.38))
    barrel(sc, t(1.58), t(.25))
    barrel(sc, t(1.78), t(.6), color=P["blue"], band=P["white"])
    return sc


def building_steel_mill(frame, frames):
    sc = Scene()
    plate(sc, 2, P["concrete"])
    x0, y0, x1, y1 = t(.2), t(.2), t(1.55), t(1.45)
    sc.box(x0, y0, 3, x1, y1, 44, P["steel_wall"])
    tw = (x1 - x0) / 3
    for i in range(3):
        xa, xb = x0 + i * tw, x0 + (i + 1) * tw
        f = [[(xa, y0, 44), (xa, y1, 44), (xb, y1, 62), (xb, y0, 62)],
             [(xb, y0, 44), (xb, y1, 44), (xb, y1, 62), (xb, y0, 62)],
             [(xa, y0, 44), (xb, y0, 44), (xb, y0, 62)],
             [(xa, y1, 44), (xb, y1, 44), (xb, y1, 62)],
             [(xa, y0, 44), (xb, y0, 44), (xb, y1, 44), (xa, y1, 44)]]
        sc.solid(f, [P["roof_dk"], P["glass"], P["steel_wall"], P["steel_wall"], P["roof_dk"]])
    sc.decal_x(x1, t(.55), t(1.05), 3, 32, P["metal_dk"])
    sc.decal_x(x1, t(.62), t(.98), 3, 13, P["orange"], off=1.0, emissive=True)
    sc.decal_y(y1, t(.3), t(1.45), 24, 32, P["window"])
    cx, cy = t(.45), t(1.72)
    for i, z in enumerate(range(3, 111, 27)):
        sc.prism(cx, cy, z, z + 27, 9, P["red"] if i % 2 == 0 else P["white"], n=12)
    sc.prism(cx, cy, 111, 116, 10, P["metal_dk"], n=12)
    for k in range(3):
        z = 3 + k * 5
        sc.box(t(1.65), t(.3), z, t(1.9), t(1.2), z + 4, P["metal"], top=P["metal_lt"])

    def smoke(draw, to_px):
        sx, sy = to_px((cx, cy, 117))
        for k in range(3):
            p = (k + frame / frames) / 3.0
            r = (5 + p * 11) * SS
            px_, py_ = sx + p * 16 * SS, sy - p * 64 * SS
            a = int(200 * (1 - p) ** 1.3)
            g = int(150 + 70 * p)
            draw.ellipse([px_ - r, py_ - r, px_ + r, py_ + r], fill=(g, g, g + 6, a))
    sc.overlay(smoke)
    return sc


def building_sawmill(frame, frames):
    sc = Scene()
    plate(sc, 2, P["dirt"])
    for (x, y) in ((.3, .3), (1.2, .3), (1.2, 1.6), (.3, 1.6), (1.2, .95)):
        sc.box(t(x) - 2, t(y) - 2, 3, t(x) + 2, t(y) + 2, 34, P["wood_dk"])
    sc.gable(t(.22), t(.22), t(1.28), t(1.68), 34, 16, P["wood"], P["wood_dk"], ridge="y")
    sc.box(t(.55), t(.7), 3, t(.95), t(1.2), 15, P["wood_dk"], top=P["wood"])
    with sc.push(T(t(.75), t(.95), 15) @ Rx(90)):
        sc.prism(0, 0, -0.8, 0.8, 9, P["metal_lt"], n=12)
    with sc.push(T(0, t(.3), 0) @ Rx(-90)):
        for x, h in ((1.45, 10), (1.6, 10), (1.75, 10), (1.525, 22), (1.675, 22)):
            sc.prism(t(x), -h, 0, t(1.0), 7, P["bark"], n=8, top=P["log_end"], bottom=P["log_end"])
    sc.box(t(1.3), t(1.45), 3, t(1.85), t(1.8), 9, P["wood_lt"])
    sc.box(t(1.33), t(1.48), 9, t(1.82), t(1.77), 14, P["wood_lt"], top=shade(P["wood_lt"], 1.05))
    return sc


def building_barracks(frame, frames):
    sc = Scene()
    plate(sc, 2, P["concrete"])
    x1 = t(1.7)
    sc.halfcyl(t(.25), x1, t(1.0), 3, t(.55), P["olive"], end_color=P["olive_dk"], n=10)
    sc.decal_x(x1, t(.86), t(1.14), 3, 28, P["door"])
    sc.decal_x(x1, t(.58), t(.72), 16, 26, P["window"])
    sc.decal_x(x1, t(1.28), t(1.42), 16, 26, P["window"])
    cy, cz = t(1.0), 38.0
    star = []
    for i in range(10):
        r = 7.0 if i % 2 == 0 else 2.9
        a = math.pi / 2 + i * math.pi / 5
        star.append((x1 + 0.6, cy - r * math.cos(a), cz + r * math.sin(a)))
    sc.poly(star, P["white"], inside=(x1 - 1, cy, cz), casts=False)
    sandbags(sc, t(1.84), t(.18), t(1.95), t(.72), rows=2, along="y")
    sc.box(t(1.78), t(1.35), 3, t(1.92), t(1.6), 12, P["olive_dk"], top=P["olive"])
    flag(sc, t(1.86), t(1.8), 3, 66, P["blue"], 0, 1, length=20, height=12)
    return sc


def building_warehouse(frame, frames):
    sc = Scene()
    plate(sc, 2, P["concrete"])
    wall = (150, 128, 98)
    sc.box(t(.2), t(.3), 3, t(1.55), t(1.75), 40, wall)
    sc.gable(t(.15), t(.25), t(1.6), t(1.8), 40, 20, P["metal"], wall, ridge="x")
    sc.decal_x(t(1.55), t(.65), t(1.35), 3, 34, P["metal"])
    for k in range(5):
        sc.decal_x(t(1.55), t(.65), t(1.35), 6 + 6 * k, 7.5 + 6 * k, P["metal_dk"], off=1.0)
    for xc in (.5, .9, 1.3):
        sc.decal_y(t(1.75), t(xc) - 6, t(xc) + 6, 26, 32, P["window"])
    crate(sc, t(1.66), t(.2), 3, t(1.86), t(.42), 18)
    crate(sc, t(1.68), t(.46), 3, t(1.85), t(.62), 14)
    crate(sc, t(1.7), t(.24), 18, t(1.84), t(.38), 29)
    barrel(sc, t(1.75), t(1.6))
    barrel(sc, t(1.62), t(1.8))
    return sc


def building_hospital(frame, frames):
    sc = Scene()
    plate(sc, 2, P["concrete_lt"])
    sc.box(t(.25), t(.3), 3, t(1.55), t(1.7), 46, P["white_wall"])
    sc.box(t(.22), t(.27), 46, t(1.58), t(1.73), 50, (172, 174, 178))
    cx, cy, a, b = t(.9), t(1.0), 17.0, 6.0
    cross = [(cx - b, cy - a), (cx + b, cy - a), (cx + b, cy - b), (cx + a, cy - b), (cx + a, cy + b), (cx + b, cy + b),
             (cx + b, cy + a), (cx - b, cy + a), (cx - b, cy + b), (cx - a, cy + b), (cx - a, cy - b), (cx - b, cy - b)]
    sc.decal_z(50, cross, P["red"])
    for yc in (.5, .72, 1.28, 1.5):
        sc.decal_x(t(1.55), t(yc) - 6, t(yc) + 6, 30, 40, P["window"])
        sc.decal_x(t(1.55), t(yc) - 6, t(yc) + 6, 10, 20, P["window"])
    sc.decal_x(t(1.55), t(.88), t(1.12), 3, 24, P["glass"])
    sc.box(t(1.55), t(.8), 26, t(1.78), t(1.2), 29, P["red"], top=P["white"])
    for yy in (.84, 1.16):
        sc.box(t(1.74), t(yy) - 1, 3, t(1.76) + 1, t(yy) + 1, 26, P["metal_lt"])
    for xc in (.45, .7, 1.1, 1.35):
        sc.decal_y(t(1.7), t(xc) - 6, t(xc) + 6, 30, 40, P["window"])
        sc.decal_y(t(1.7), t(xc) - 6, t(xc) + 6, 10, 20, P["window"])
    sc.decal_y(t(1.7), t(.9) - 7, t(.9) + 7, 22, 26, P["red"])
    sc.decal_y(t(1.7), t(.9) - 2, t(.9) + 2, 17, 31, P["red"])
    return sc


def building_lab(frame, frames):
    sc = Scene()
    plate(sc, 2, P["concrete"])
    wall = (178, 190, 200)
    sc.box(t(.2), t(.3), 3, t(1.05), t(1.75), 36, wall)
    sc.box(t(.17), t(.27), 36, t(1.08), t(1.78), 39, P["roof_dk"])
    sc.decal_x(t(1.05), t(.4), t(1.65), 18, 26, P["cyan"], emissive=True)
    sc.decal_y(t(1.75), t(.5), t(.72), 3, 24, P["door"])
    sc.decal_y(t(1.75), t(.8), t(.98), 14, 24, P["cyan"], emissive=True)
    dx, dy = t(1.45), t(.85)
    sc.prism(dx, dy, 3, 22, t(.4), wall, n=16)
    sc.dome(dx, dy, 22, t(.4), P["white"], n=16, rings=5)
    sc.prism(dx, dy, 22, 25, t(.4) + 0.4, P["metal"], n=16)
    sc.beam((t(.6), t(.8), 39), (t(.6), t(.8), 50), 2.5, 2.5, P["metal_dk"])
    with sc.push(T(t(.6), t(.8), 52) @ Rz(45) @ Ry(-40)):
        sc.prism(0, 0, -1.5, 1.5, 12, P["white"], n=12, top=P["metal_lt"])
        sc.beam((0, 0, 1.5), (0, 0, 10), 1.2, 1.2, P["metal_dk"])
    sc.beam((t(.35), t(1.55), 39), (t(.35), t(1.55), 78), 2, 2, P["metal_dk"])
    sc.box(t(.35) - 1.8, t(1.55) - 1.8, 78, t(.35) + 1.8, t(1.55) + 1.8, 81, P["red"], emissive=True)
    return sc


def building_watchtower(frame, frames):
    sc = Scene()
    plate(sc, 1, P["dirt"])
    lo, hi = (.2, .8), (.3, .7)
    corners = [(lo[0], lo[0], hi[0], hi[0]), (lo[1], lo[0], hi[1], hi[0]),
               (lo[1], lo[1], hi[1], hi[1]), (lo[0], lo[1], hi[0], hi[1])]
    for x, y, xh, yh in corners:
        sc.beam((t(x), t(y), 3), (t(xh), t(yh), 72), 4, 4, P["wood_dk"])
    sc.beam((t(.78), t(.22), 8), (t(.72), t(.72), 60), 2.2, 2.2, P["wood"])
    sc.beam((t(.78), t(.78), 8), (t(.72), t(.28), 60), 2.2, 2.2, P["wood"])
    sc.beam((t(.22), t(.78), 8), (t(.72), t(.72), 60), 2.2, 2.2, P["wood"])
    sc.beam((t(.78), t(.78), 8), (t(.28), t(.72), 60), 2.2, 2.2, P["wood"])
    sc.box(t(.16), t(.16), 72, t(.84), t(.84), 77, P["wood"], top=P["wood_lt"])
    sandbags(sc, t(.18), t(.18), t(.82), t(.27), z0=77, rows=2, along="x")
    sandbags(sc, t(.18), t(.73), t(.82), t(.82), z0=77, rows=2, along="x")
    sandbags(sc, t(.18), t(.27), t(.27), t(.73), z0=77, rows=2, along="y")
    sandbags(sc, t(.73), t(.27), t(.82), t(.73), z0=77, rows=2, along="y")
    for x, y in ((.22, .22), (.78, .22), (.78, .78), (.22, .78)):
        sc.box(t(x) - 1.3, t(y) - 1.3, 77, t(x) + 1.3, t(y) + 1.3, 100, P["wood_dk"])
    sc.cone(t(.5), t(.5), 100, 116, t(.36) * math.sqrt(2) + 3, P["olive_dk"], n=4, rot=45)
    sc.box(t(.7), t(.7), 87, t(.78), t(.78), 93, P["window_lit"], emissive=True)
    return sc


def construction(N):
    def build(frame, frames):
        sc = Scene()
        plate(sc, N, P["dirt"])
        sc.box(t(.2), t(.2), 3, t(N - .2), t(N - .2), 8, P["concrete_dk"], top=P["concrete"])
        wh = 14 + 6 * N
        sc.box(t(.3), t(.3), 8, t(N - .3), t(.42), 8 + wh, P["concrete"])
        sc.box(t(.3), t(.42), 8, t(.42), t(N - .3), 8 + wh, P["concrete"])
        H = 30 + 16 * N
        steps = [.25 + i * (N - .5) / N for i in range(N + 1)]
        xe, ye = t(N - .25), t(N - .25)
        for s in steps:
            sc.box(xe - 1.2, t(s) - 1.2, 8, xe + 1.2, t(s) + 1.2, H, P["metal"])
            sc.box(t(s) - 1.2, ye - 1.2, 8, t(s) + 1.2, ye + 1.2, H, P["metal"])
        for z in (H * .5, H - 2):
            sc.box(xe - 1, t(.25), z, xe + 1, t(N - .25), z + 2, P["metal"])
            sc.box(t(.25), ye - 1, z, t(N - .25), ye + 1, z + 2, P["metal"])
        z = H * .5 + 2
        sc.box(xe - 7, t(.3), z, xe + 1, t(N - .3), z + 1.5, P["wood_lt"])
        sc.box(t(.3), ye - 7, z, t(N - .3), ye + 1, z + 1.5, P["wood_lt"])
        if N >= 2:
            mx, my = t(.6), t(.6)
            top = H + 34 + 14 * N
            sc.box(mx - 2.5, my - 2.5, 8, mx + 2.5, my + 2.5, top, P["yellow"])
            sc.box(mx - 22, my - 2, top, mx + t(N * .55), my + 2, top + 4, P["yellow"])
            sc.box(mx - 24, my - 4, top - 6, mx - 14, my + 4, top, P["concrete_dk"])
            sc.box(mx - 3, my - 3, top + 4, mx + 3, my + 3, top + 10, P["metal_dk"])
            hx = mx + t(N * .42)
            sc.beam((hx, my, top), (hx, my, top - 30), 0.8, 0.8, P["black"])
            sc.box(hx - 6, my - 6, top - 38, hx + 6, my + 6, top - 30, P["wood"], top=P["wood_lt"])
        for i in range(4):
            c = P["yellow"] if i % 2 == 0 else P["black"]
            sc.box(t(N - .12) - 2, t(.2 + i * .12), 3, t(N - .12) + 2, t(.2 + (i + 1) * .12), 10, c)
        crate(sc, t(N * .5), t(N - .15) - 10, 3, t(N * .5) + 12, t(N - .15), 12)
        sc.prism(t(N * .5) + 22, t(N - .2), 3, 10, 6, P["sand"], n=8, r_top=3)
        return sc
    return build


BUILDINGS = [
    # name, footprint, frames, frameDuration, builder
    ("hq", 3, 4, 0.15, building_hq),
    ("farm", 2, 1, 1.0, building_farm),
    ("oil_pump", 2, 6, 0.15, building_oil_pump),
    ("steel_mill", 2, 4, 0.2, building_steel_mill),
    ("sawmill", 2, 1, 1.0, building_sawmill),
    ("barracks", 2, 1, 1.0, building_barracks),
    ("warehouse", 2, 1, 1.0, building_warehouse),
    ("hospital", 2, 1, 1.0, building_hospital),
    ("lab", 2, 1, 1.0, building_lab),
    ("watchtower", 1, 1, 1.0, building_watchtower),
    ("construction_1x1", 1, 1, 1.0, construction(1)),
    ("construction_2x2", 2, 1, 1.0, construction(2)),
    ("construction_3x3", 3, 1, 1.0, construction(3)),
]


# ================================================================ UNITS
# Soldier-like figures (helmet/cap, rifle, normal eyes). Added for Ruinborn: shooters and heroes.
SOLDIER_KINDS = {"soldier", "shooter", "hero_rhea", "hero_viktor", "hero_kaya", "hero_brock", "hero_nova"}
HUMAN_KINDS = SOLDIER_KINDS | {"worker"}

HUMAN_COLORS = {
    "soldier": dict(pants=(74, 86, 48), boots=(58, 48, 38), shirt=(98, 114, 60), belt=(66, 56, 40),
                    skin=P["skin"], sleeve=(98, 114, 60), hat=(78, 92, 50), hat_top=(88, 102, 56)),
    # Shooters: dark blue-gray with a red beret
    "shooter": dict(pants=(58, 64, 78), boots=(40, 40, 44), shirt=(76, 86, 104), belt=(48, 44, 40),
                    skin=P["skin"], sleeve=(76, 86, 104), hat=(150, 44, 40), hat_top=(170, 54, 48)),
    # Heroes: one signature color each
    "hero_rhea": dict(pants=(74, 86, 48), boots=(58, 48, 38), shirt=(60, 120, 70), belt=(66, 56, 40),
                      skin=(236, 196, 160), sleeve=(60, 120, 70), hat=(176, 70, 44), hat_top=(196, 86, 54)),
    "hero_viktor": dict(pants=(50, 50, 56), boots=(36, 34, 34), shirt=(88, 60, 50), belt=(40, 36, 34),
                        skin=P["skin"], sleeve=(88, 60, 50), hat=(40, 40, 44), hat_top=(58, 58, 62)),
    "hero_kaya": dict(pants=(70, 98, 132), boots=(70, 54, 40), shirt=(60, 170, 180), belt=(70, 98, 132),
                      skin=(210, 160, 120), sleeve=(60, 170, 180), hat=(236, 188, 52), hat_top=(246, 208, 80)),
    "hero_brock": dict(pants=(92, 80, 60), boots=(58, 48, 38), shirt=(206, 120, 44), belt=(66, 56, 40),
                       skin=(196, 150, 112), sleeve=(206, 120, 44), hat=(104, 118, 64), hat_top=(118, 132, 72)),
    "hero_nova": dict(pants=(52, 58, 92), boots=(40, 40, 50), shirt=(70, 110, 200), belt=(40, 44, 70),
                      skin=(236, 200, 170), sleeve=(70, 110, 200), hat=(210, 214, 226), hat_top=(230, 232, 240)),
    "worker": dict(pants=(70, 98, 132), boots=(70, 54, 40), shirt=(232, 132, 40), belt=(70, 98, 132),
                   skin=P["skin"], sleeve=(70, 98, 132), hat=P["yellow"], hat_top=(246, 208, 80)),
    "zombie": dict(pants=(62, 72, 96), boots=(52, 46, 42), shirt=(118, 88, 74), belt=(84, 64, 54),
                   skin=P["zskin"], sleeve=P["zskin"], hat=None),
    "brute": dict(pants=(70, 60, 54), boots=(48, 42, 38), shirt=(96, 104, 92), belt=(60, 50, 44),
                  skin=P["bskin"], sleeve=P["bskin"], hat=None),
}


def rifle(sc, p0, p1):
    import numpy as np
    p0, p1 = np.array(p0, float), np.array(p1, float)
    sc.beam(p0, p1, 1.8, 2.6, (46, 46, 50))
    sc.beam(p0, p0 + (p1 - p0) * 0.3, 2.2, 3.8, P["wood_dk"])


def muzzle_flash(sc, tip, d):
    """Star-shaped, self-illuminated muzzle flash at position tip in direction d (local +x)."""
    x, y, z = tip
    for (w, L, col) in ((4.5, 9, P["orange"]), (2.4, 6, (255, 244, 170))):
        sc.quad2([(x, y, z - w * .3), (x + L * .5, y, z - w), (x + L, y, z), (x + L * .5, y, z + w)], col, emissive=True)
        sc.quad2([(x, y - w * .3, z), (x + L * .5, y - w, z), (x + L, y, z), (x + L * .5, y + w, z)], col, emissive=True)


def humanoid(sc, kind, pose):
    c = HUMAN_COLORS[kind]
    g = pose.get
    root = Rz(g("yaw", 0))
    fall = g("fall", 0)
    if fall:
        px = 3.0 if fall > 0 else -3.0
        root = root @ T(px, 0, 0) @ Ry(fall) @ T(-px, 0, 0)
    root = root @ T(0, 0, g("bob", 0))
    with sc.push(root):
        for side, ang in ((+1, g("legL", 0)), (-1, g("legR", 0))):
            with sc.push(T(0, side * 2.6, 14) @ fwd(ang)):
                sc.box(-2.3, -2.0, -11, 2.3, 2.0, 0, c["pants"])
                sc.box(-2.5, -2.2, -14, 3.3, 2.2, -11, c["boots"])
        with sc.push(T(0, 0, 14) @ Ry(g("lean", 0)) @ Rx(g("roll", 0))):
            sc.box(-2.8, -5, 0, 2.8, 5, 13, c["shirt"])
            sc.box(-2.9, -5.1, 0, 2.9, 5.1, 2, c["belt"])
            if kind == "zombie":
                sc.decal_x(2.8, -4, -1, 3, 7, (150, 60, 50))
            with sc.push(T(0, 0, 13.4) @ Rx(g("head_roll", 0)) @ Ry(g("head_pitch", 0))):
                sc.box(-3.2, -3.2, 0, 3.2, 3.2, 6.5, c["skin"])
                eye = (30, 30, 34) if kind in HUMAN_KINDS else (220, 60, 40)
                sc.decal_x(3.2, -2.3, -0.9, 3.4, 4.6, eye, off=0.3, emissive=kind not in HUMAN_KINDS)
                sc.decal_x(3.2, 0.9, 2.3, 3.4, 4.6, eye, off=0.3, emissive=kind not in HUMAN_KINDS)
                if kind in SOLDIER_KINDS:
                    sc.box(-3.9, -3.9, 4.8, 3.9, 3.9, 8.4, c["hat"], top=c["hat_top"])
                    sc.box(-4.4, -4.4, 4.8, 4.4, 4.4, 5.6, c["hat"])
                elif kind == "worker":
                    sc.box(-3.7, -3.7, 5.2, 3.7, 3.7, 8.6, c["hat"], top=c["hat_top"])
                    sc.box(-4.0, -4.3, 5.2, 5.8, 4.3, 6.0, c["hat"])
                else:
                    sc.box(-3.3, -3.3, 6.5, 2.0, 3.3, 7.4, (58, 50, 44))
            for side, ang, ab in ((+1, g("armL", 0), g("armL_side", 8)), (-1, g("armR", 0), g("armR_side", 8))):
                with sc.push(T(0, side * 6.6, 12.5) @ fwd(ang) @ Rx(side * ab)):
                    sc.box(-1.6, -1.6, -10, 1.6, 1.6, 0.5, c["sleeve"])
                    sc.box(-1.5, -1.5, -12.5, 1.5, 1.5, -10, c["skin"])
                    if side == -1 and g("tool") == "hammer":
                        sc.beam((-1, 0, -11.3), (9, 0, -11.3), 1.6, 1.6, P["wood"])
                        sc.box(8, -1.5, -14.5, 11.5, 1.5, -8.5, P["metal_dk"], top=P["metal"])
            w = g("weapon")
            if w == "carry":
                rifle(sc, (3.8, -4.5, 3.5), (5.2, 4.2, 17.5))
            elif w == "aim":
                rifle(sc, (-1, -3.2, 11.5), (22, -3.2, 12))
                if g("flash"):
                    muzzle_flash(sc, (22.5, -3.2, 12), 1)
    if g("weapon") == "dropped":
        # outside the fall rotation: the rifle lies flat on the ground next to the figure
        with sc.push(Rz(g("yaw", 0) - 25)):
            rifle(sc, (4, 5, 1.3), (26, 5, 1.3))


def pose_default(kind):
    if kind in ("zombie", "brute"):
        return dict(armL=78, armR=66, armL_side=4, armR_side=4, lean=9, head_roll=12)
    if kind in SOLDIER_KINDS:
        return dict(armL=38, armR=34, armL_side=-12, armR_side=-4, weapon="carry")
    return dict(armL=10, armR=12, tool="hammer")


def human_frames(kind, action):
    base = pose_default(kind)
    out = []

    def P_(**kw):
        d = dict(base)
        d.update(kw)
        return d

    zombie = kind in ("zombie", "brute")
    if action == "idle":
        if zombie:
            out = [P_(roll=-3, armL=74), P_(roll=3, armL=80, armR=70, bob=-0.4)]
        else:
            out = [P_(), P_(bob=-0.7)]
    elif action == "walk":
        a = 14 if zombie else 26
        for i, (l, b) in enumerate(((a, 0), (0, 1), (-a, 0), (0, 1))):
            kw = dict(legL=l, legR=-l, bob=b)
            if zombie:
                kw.update(roll=(-4, 0, 4, 0)[i], armL=78 + (5, 0, -5, 0)[i], armR=66 - (5, 0, -5, 0)[i])
            elif kind == "worker":
                kw.update(armL=-l * 0.8, armR=l * 0.8 + 10)
            else:
                kw.update(lean=4)
            out.append(P_(**kw))
    elif action == "attack":
        if zombie:
            out = [P_(armL=160, armR=150, lean=-6, legL=10, legR=-6),
                   P_(armL=95, armR=85, lean=12, legL=14, legR=-8),
                   P_(armL=30, armR=24, lean=18, legL=14, legR=-8)]
        else:
            aim = dict(weapon="aim", armL=84, armR=88, armL_side=-26, armR_side=-14, legL=12, legR=-10)
            out = [P_(**aim), P_(**aim, flash=True, lean=-3), P_(**aim, lean=-1)]
    elif action == "work":
        for arm, lean in ((160, -3), (110, 2), (36, 12), (80, 6)):
            out.append(P_(armR=arm, armR_side=2, armL=30, lean=lean, legL=12, legR=-8))
    elif action == "die":
        if zombie:
            for f, arms in ((20, 90), (50, 110), (76, 150), (90, 170)):
                out.append(P_(fall=f, armL=arms, armR=arms - 10, lean=4))
        else:
            for f, arms in ((-15, 60), (-45, 110), (-75, 150), (-90, 170)):
                kw = dict(fall=f, armL=arms, armR=arms + 6, armL_side=20, armR_side=20, legL=6, legR=-4)
                if kind in SOLDIER_KINDS:
                    kw["weapon"] = "dropped" if f < -15 else "carry"
                out.append(P_(**kw))
    return out


def jeep(sc, yaw, frame, action, frames, wreck_colors=False, deco=False):
    body = P["olive"] if not wreck_colors else (62, 58, 52)
    dark = P["olive_dk"] if not wreck_colors else (44, 42, 40)
    if deco:
        body, dark = P["rust"], (112, 72, 50)
    bob = 0.0
    if action == "idle":
        bob = (0.0, 0.5)[frame % 2]
    elif action == "drive":
        bob = (0.0, 0.8, 0.0, -0.4)[frame % 4]
    wr = 22.5 * frame if action == "drive" else 0.0
    wreck = action == "wreck"
    root = Rz(yaw)
    if wreck:
        root = root @ Rx(6) @ T(0, 0, -1.5)
    with sc.push(root):
        for wx in (-10.5, 10.5):
            for side in (1, -1):
                if wreck and wx > 0 and side < 0:
                    continue
                wy = side * 9.6
                with sc.push(T(wx, wy, 5.5) @ Rx(-90) @ Rz(wr)):
                    hub = (150, 150, 140) if not (wreck or deco) else (70, 64, 58)
                    sc.prism(0, 0, -1.8, 1.8, 5.5, (40, 40, 44), n=8, top=hub, bottom=hub)
                    sc.box(2.2, -0.8, 1.8, 4.2, 0.8, 2.2, (70, 70, 74))
                    sc.box(2.2, -0.8, -2.2, 4.2, 0.8, -1.8, (70, 70, 74))
        with sc.push(T(0, 0, bob)):
            sc.box(-16, -8, 5, 16, 8, 9, dark)
            sc.box(-15, -8.5, 9, 8, 8.5, 15, body)
            sc.box(8, -7.5, 8, 17, 7.5, 14, body, top=shade(body, 1.06))
            sc.decal_x(17, -5, 5, 8.5, 12.5, (50, 52, 50))
            sc.box(-14, -7, 15, -9, 7, 18, (54, 50, 44))
            sc.box(-4, -7, 15, 0, -1, 19, (54, 50, 44))
            sc.box(-4, 1, 15, 0, 7, 19, (54, 50, 44))
            if not wreck:
                sc.box(4, -8, 15, 5.5, 8, 24, dark)
                sc.box(5.5, -7, 16, 5.9, 7, 23, P["glass"])
                for yy in (-7.5, 7.5):
                    sc.beam((-10, yy, 15), (-10, yy, 26), 1.6, 1.6, (60, 62, 60))
                sc.beam((-10, -7.5, 26), (-10, 7.5, 26), 1.6, 1.6, (60, 62, 60))
            if not (wreck or deco):
                sc.beam((-6, 0, 15), (-6, 0, 22), 1.6, 1.6, (60, 62, 60))
                sc.box(-9, -1.4, 22, 9, 1.4, 24.6, (46, 46, 50))
                for yy in (-5.5, 5.5):
                    sc.box(17, yy - 1.5, 10.5, 17.6, yy + 1.5, 12.5, (255, 244, 190), emissive=True)
            with sc.push(T(-17.5, 0, 11) @ Ry(90)):
                sc.prism(0, 0, -1.6, 1.6, 5, (40, 40, 44), n=8, top=(70, 70, 74), bottom=(70, 70, 74))
            if wreck and not deco:
                for (x, y, z) in ((2, 3, 15), (-6, -4, 15), (10, 0, 14.2)):
                    sc.box(x - 1.5, y - 1.5, z, x + 1.5, y + 1.5, z + 1.5, P["orange"], emissive=True)


UNITS = [
    # name, kind, scale, actions: (action, frames, duration, loop)
    ("soldier", "soldier", 1.0, [("idle", 2, 0.5, True), ("walk", 4, 0.13, True), ("attack", 3, 0.09, True),
                                 ("die", 4, 0.14, False)]),
    ("worker", "worker", 1.0, [("idle", 2, 0.5, True), ("walk", 4, 0.13, True), ("work", 4, 0.12, True),
                               ("die", 4, 0.14, False)]),
    ("zombie", "zombie", 1.0, [("idle", 2, 0.45, True), ("walk", 4, 0.2, True), ("attack", 3, 0.13, True),
                               ("die", 4, 0.14, False)]),
    ("brute", "brute", 1.35, [("idle", 2, 0.5, True), ("walk", 4, 0.24, True), ("attack", 3, 0.15, True),
                              ("die", 4, 0.16, False)]),
    ("jeep", "jeep", 1.5, [("idle", 2, 0.12, True), ("drive", 4, 0.08, True), ("wreck", 1, 1.0, False)]),
]


def unit_scene(kind, scale, action, frame, frames, yaw):
    sc = Scene()
    with sc.push(S(scale)):
        if kind == "jeep":
            jeep(sc, yaw, frame, action, frames, wreck_colors=(action == "wreck"))
        else:
            pose = human_frames(kind, action)[frame]
            pose["yaw"] = yaw
            humanoid(sc, kind, pose)
    return sc


def unit_shadow(kind, scale, yaw):
    import numpy as np

    def draw(dr, to_px):
        if kind == "jeep":
            pts = [(-17, -10), (17, -10), (18, 10), (-17, 10)]
            M = Rz(yaw)
            poly = []
            for x, y in pts:
                p = M @ [x * scale, y * scale, 0, 1]
                poly.append(to_px(p[:3]))
        else:
            r = 8.5 * scale
            poly = [to_px((r * math.cos(a), r * math.sin(a), 0)) for a in np.linspace(0, 2 * math.pi, 24, endpoint=False)]
        dr.polygon(poly, fill=(0, 0, 0, 72))
    return draw


# ================================================================ DECORATION (anchor = center on the ground)
def deco_scene(name):
    sc = Scene()
    if name == "pine":
        sc.prism(0, 0, 0, 10, 2.6, P["bark"], n=6)
        sc.cone(0, 0, 7, 28, 13, P["pine"], n=8)
        sc.cone(0, 0, 19, 39, 10, shade(P["pine"], 1.08), n=8, rot=22)
        sc.cone(0, 0, 30, 49, 7, shade(P["pine"], 1.15), n=8)
    elif name == "tree":
        sc.prism(0, 0, 0, 16, 2.8, P["bark"], n=6)
        sc.sphere(0, 0, 27, 13, P["leaf"], n=9, rings=5)
        sc.sphere(5, 6, 22, 8, shade(P["leaf"], 0.92), n=8, rings=4)
    elif name == "dead_tree":
        sc.prism(0, 0, 0, 30, 3.2, (104, 92, 80), n=6, r_top=1.4)
        sc.beam((0, 0, 16), (8, 3, 28), 1.8, 1.8, (104, 92, 80))
        sc.beam((0, 0, 21), (-7, -2, 31), 1.6, 1.6, (104, 92, 80))
        sc.beam((0, 0, 11), (-3, 8, 18), 1.5, 1.5, (104, 92, 80))
    elif name == "rock":
        sc.prism(0, 0, 0, 9, 12, P["rock"], n=6, r_top=6, rot=15)
        sc.prism(9, 6, 0, 5, 6, shade(P["rock"], 0.9), n=5, r_top=3, rot=40)
    elif name == "bush":
        sc.sphere(0, 0, 6, 8, P["bush"], n=8, rings=4, z_scale=0.8)
        sc.sphere(6, 5, 5, 6, shade(P["bush"], 1.1), n=8, rings=4, z_scale=0.8)
        sc.sphere(-4, 6, 4, 5, shade(P["bush"], 0.95), n=7, rings=3, z_scale=0.8)
    elif name == "wreck":
        with sc.push(Rz(-20)):
            jeep(sc, 0, 0, "wreck", 1, deco=True)
    return sc


def deco_shadow(name):
    r = {"pine": 11, "tree": 12, "dead_tree": 6, "rock": 12, "bush": 10, "wreck": 16}[name]

    def draw(dr, to_px):
        import numpy as np
        pts = [to_px((r * math.cos(a), r * math.sin(a), 0)) for a in np.linspace(0, 2 * math.pi, 24, endpoint=False)]
        dr.polygon(pts, fill=(0, 0, 0, 60))
    return draw


DECOS = ["pine", "tree", "dead_tree", "rock", "bush", "wreck"]


# ================================================================ ICONS (3D mini objects)
def icon_scene(name, scale=1.0):
    """Icon object. generate.py chooses scale so the icon fills 64x64 well."""
    sc = Scene()
    with sc.push(S(scale)):
        _icon_body(sc, name)
    return sc


def _icon_body(sc, name):
    if name == "food":
        sc.sphere(0, 0, 15, 15, (206, 52, 44), n=12, rings=6, z_scale=0.92)
        sc.beam((0, 0, 27), (1.5, 0.5, 34), 1.8, 1.8, P["bark"])
        sc.quad2([(1.5, 0.5, 31), (9, -3, 35), (12, -4, 31), (5, -1, 29)], (92, 168, 64))
    elif name == "wood":
        with sc.push(T(0, -15, 0) @ Rx(-90)):
            for x, h in ((-8, 7.5), (8, 7.5), (0, 20.5)):
                sc.prism(x, -h, 0, 30, 7.5, P["bark"], n=8, top=P["log_end"], bottom=P["log_end"])
    elif name == "steel":
        def ingot(M):
            with sc.push(M):
                lo = [(-12, -6, 0), (12, -6, 0), (12, 6, 0), (-12, 6, 0)]
                hi = [(-9, -4, 7), (9, -4, 7), (9, 4, 7), (-9, 4, 7)]
                f = [lo[::-1], hi] + [[lo[i], lo[(i + 1) % 4], hi[(i + 1) % 4], hi[i]] for i in range(4)]
                sc.solid(f, [(150, 164, 184), (206, 216, 230)] + [(150, 164, 184)] * 4)
        ingot(T(0, -7, 0))
        ingot(T(0, 7, 0))
        ingot(T(0, 0, 7) @ Rz(90))
    elif name == "oil":
        sc.prism(0, 0, 0, 32, 12, (46, 50, 56), n=14, top=(70, 74, 80))
        for z in (7, 22):
            sc.prism(0, 0, z, z + 3, 12.4, P["orange"], n=14)
        sc.prism(5, 3, 32, 34, 2.5, (140, 140, 140), n=8)
    elif name == "speedup":
        sc.prism(0, 0, 0, 3, 12, P["wood"], n=10, top=P["wood_lt"])
        sc.prism(0, 0, 3, 11, 9, P["sand"], n=10, r_top=5.5)
        sc.prism(0, 0, 11, 17, 5.5, P["glass"], n=10, r_top=1.4)
        sc.prism(0, 0, 17, 31, 1.4, P["glass"], n=10, r_top=9)
        sc.prism(0, 0, 31, 34, 12, P["wood"], n=10, top=P["wood_lt"])
        for a in (30, 150, 270):
            x, y = 10 * math.cos(math.radians(a)), 10 * math.sin(math.radians(a))
            sc.beam((x, y, 3), (x, y, 31), 1.6, 1.6, P["wood_dk"])


ICONS = ["food", "wood", "steel", "oil", "speedup"]


# ================================================================ TILES (2D, 128x64)
TW, TH = 128, 64


def _diamond(inset=0.0):
    s = SS
    return [((TW / 2) * s, inset * s), ((TW - inset * 2) * s, (TH / 2) * s),
            ((TW / 2) * s, (TH - inset) * s), (inset * 2 * s, (TH / 2) * s)]


def _clip_to_diamond(im):
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).polygon(_diamond(), fill=255)
    a = im.getchannel("A")
    from PIL import ImageChops
    im.putalpha(ImageChops.multiply(a, mask))
    return im


def _speckle(dr, rnd, n, colors, rmin=0.6, rmax=1.6):
    for _ in range(n):
        x, y = rnd.uniform(0, TW), rnd.uniform(0, TH)
        r = rnd.uniform(rmin, rmax)
        c = rnd.choice(colors)
        dr.ellipse([(x - r) * SS, (y - r * 0.6) * SS, (x + r) * SS, (y + r * 0.6) * SS], fill=c)


def tile_image(name, variant=0):
    im, dr = ss_canvas(TW, TH)
    rnd = random.Random(zlib.crc32(f"{name}:{variant}".encode()))   # deterministic (no hash()!)
    if name in ("grass", "dirt", "concrete", "road_x", "road_y"):
        base = {"grass": [(104, 150, 72), (98, 144, 68), (110, 154, 76)][variant % 3],
                "dirt": (150, 122, 86), "concrete": (158, 156, 150), "road_x": (86, 88, 94), "road_y": (86, 88, 94)}[name]
        dr.polygon(_diamond(), fill=base + (255,))
        if name == "grass":
            _speckle(dr, rnd, 70, [shade(base, 0.86) + (255,), shade(base, 1.12) + (255,)])
            for _ in range(8 + variant * 3):
                x, y = rnd.uniform(20, 108), rnd.uniform(10, 54)
                c = shade(base, 0.72) + (255,)
                dr.line([((x - 2) * SS, (y - 2) * SS), (x * SS, y * SS), ((x + 2) * SS, (y - 2.5) * SS)], fill=c, width=SS)
            if variant == 2:
                for _ in range(4):
                    x, y = rnd.uniform(34, 94), rnd.uniform(18, 46)
                    col = rnd.choice([(240, 226, 110), (236, 236, 236), (220, 120, 150)])
                    dr.ellipse([(x - 1.2) * SS, (y - 1) * SS, (x + 1.2) * SS, (y + 1) * SS], fill=col + (255,))
        elif name == "dirt":
            _speckle(dr, rnd, 60, [shade(base, 0.85) + (255,), shade(base, 1.1) + (255,)])
            _speckle(dr, rnd, 10, [(120, 112, 104, 255), (170, 164, 150, 255)], 1.2, 2.2)
        elif name == "concrete":
            _speckle(dr, rnd, 50, [shade(base, 0.92) + (255,), shade(base, 1.05) + (255,)], 0.5, 1.2)
            seam = shade(base, 0.8) + (255,)
            dr.line([(32 * SS, 16 * SS), (96 * SS, 48 * SS)], fill=seam, width=SS)
            dr.line([(96 * SS, 16 * SS), (32 * SS, 48 * SS)], fill=seam, width=SS)
            x, y = rnd.uniform(40, 80), rnd.uniform(20, 40)
            dr.line([(x * SS, y * SS), ((x + 6) * SS, (y + 2) * SS), ((x + 9) * SS, (y + 1) * SS)], fill=seam, width=SS)
        else:
            _speckle(dr, rnd, 40, [shade(base, 0.9) + (255,), shade(base, 1.08) + (255,)], 0.5, 1.1)
            if name == "road_x":
                a, b = (32, 16), (96, 48)
            else:
                a, b = (96, 16), (32, 48)
            for k in range(3):
                t0, t1 = (k + 0.2) / 3, (k + 0.65) / 3
                p0 = (a[0] + (b[0] - a[0]) * t0, a[1] + (b[1] - a[1]) * t0)
                p1 = (a[0] + (b[0] - a[0]) * t1, a[1] + (b[1] - a[1]) * t1)
                dr.line([(p0[0] * SS, p0[1] * SS), (p1[0] * SS, p1[1] * SS)], fill=(236, 214, 120, 255), width=2 * SS)
        # slight edge highlight top left / darkening bottom right -> tiles stay readable
        dr.line([_diamond()[3], _diamond()[0]], fill=(255, 255, 255, 34), width=SS)
        dr.line([_diamond()[1], _diamond()[2]], fill=(0, 0, 0, 34), width=SS)
    else:
        fill, line, width = {
            "grid": (None, (255, 255, 255, 110), 1),
            "valid": ((80, 220, 90, 80), (80, 230, 100, 230), 2),
            "invalid": ((230, 60, 50, 90), (240, 70, 60, 230), 2),
            "select": ((255, 220, 60, 40), (255, 220, 60, 255), 2),
        }[name]
        d = _diamond(inset=1.0)
        if fill:
            dr.polygon(d, fill=fill)
        dr.line(d + [d[0]], fill=line, width=width * SS, joint="curve")
    return ss_finish(_clip_to_diamond(im))


TILES = [("grass", 3), ("dirt", 1), ("concrete", 1), ("road_x", 1), ("road_y", 1),
         ("grid", 1), ("valid", 1), ("invalid", 1), ("select", 1)]


# ================================================================ EFFECTS
def explosion_frames():
    W, H, gx, gy = 96, 96, 48, 72       # (gx, gy) = ground point
    specs = [
        dict(r=9, cy=64, fire=1.0, smoke=0.0, flash=True),
        dict(r=17, cy=58, fire=1.0, smoke=0.15),
        dict(r=23, cy=50, fire=0.8, smoke=0.45),
        dict(r=26, cy=44, fire=0.35, smoke=0.8),
        dict(r=28, cy=38, fire=0.1, smoke=0.75),
        dict(r=30, cy=32, fire=0.0, smoke=0.4),
    ]
    frames = []
    for i, s in enumerate(specs):
        im, dr = ss_canvas(W, H)
        rnd = random.Random(1234)
        if i >= 1:
            a = int(110 * min(1.0, 0.4 + i * 0.2))
            dr.ellipse([(gx - 24) * SS, (gy - 9) * SS, (gx + 24) * SS, (gy + 9) * SS], fill=(30, 26, 22, a))
        blobs = [(rnd.uniform(-1, 1), rnd.uniform(-1, 1), rnd.uniform(0.45, 0.75)) for _ in range(9)]
        if s["smoke"] > 0:
            for bx, by, br in blobs:
                r = s["r"] * br * 1.1
                x, y = gx + bx * s["r"] * 0.7, s["cy"] + by * s["r"] * 0.55 - 4
                g = int(92 + 60 * (i / 5))
                dr.ellipse([(x - r) * SS, (y - r) * SS, (x + r) * SS, (y + r) * SS],
                           fill=(g, g - 4, g - 8, int(230 * s["smoke"])))
        if s.get("flash"):
            r = s["r"] * 1.7
            dr.ellipse([(gx - r) * SS, (s["cy"] - r) * SS, (gx + r) * SS, (s["cy"] + r) * SS], fill=(255, 214, 120, 150))
        if s["fire"] > 0:
            for k, (bx, by, br) in enumerate(blobs[:6]):
                r = s["r"] * br * 0.9
                x, y = gx + bx * s["r"] * 0.5, s["cy"] + by * s["r"] * 0.4
                c = mix((236, 96, 36), (255, 208, 80), (k % 3) / 2)
                dr.ellipse([(x - r) * SS, (y - r) * SS, (x + r) * SS, (y + r) * SS], fill=c + (int(255 * s["fire"]),))
            r = s["r"] * 0.45
            dr.ellipse([(gx - r) * SS, (s["cy"] - r) * SS, (gx + r) * SS, (s["cy"] + r) * SS],
                       fill=(255, 246, 190, int(255 * s["fire"])))
        if 1 <= i <= 3:
            for _ in range(7):
                a = rnd.uniform(-math.pi, 0)
                d = s["r"] * rnd.uniform(1.0, 1.5)
                x, y = gx + math.cos(a) * d, s["cy"] + math.sin(a) * d * 0.8
                dr.ellipse([(x - 1.3) * SS, (y - 1.3) * SS, (x + 1.3) * SS, (y + 1.3) * SS], fill=(60, 50, 40, 255))
        frames.append(ss_finish(im))
    return frames, W, H, gx, H - gy


FX = [("explosion", 0.07, False)]


# ================================================================ RUINBORN ADDITIONS
# Sprites added for Ruinborn in the style of the base set: wall, vehicle factory, shooting range, rally point,
# alliance center, map objects (nest, resource fields), shooters and icons for items, UI and heroes.

def building_wall(frame, frames):
    """Wall: concrete rampart with gate, watchtower top and barbed wire."""
    sc = Scene()
    plate(sc, 2, P["dirt"])
    c, c2 = P["concrete_dk"], P["concrete"]
    # Rampart along the back edges (L shape) so the gate stays visible at the front
    sc.box(t(.15), t(.15), 3, t(1.85), t(.45), 34, c, top=c2)
    sc.box(t(.15), t(.45), 3, t(.45), t(1.85), 34, c, top=c2)
    for k in range(7):
        x = t(.2 + k * .24)
        sc.box(x, t(.12), 34, x + 8, t(.48), 40, c2)
    for k in range(6):
        y = t(.5 + k * .22)
        sc.box(t(.12), y, 34, t(.48), y + 8, 40, c2)
    # Gate
    sc.box(t(1.05), t(1.1), 3, t(1.85), t(1.35), 30, c, top=c2)
    sc.decal_x(t(1.85), t(1.12), t(1.33), 3, 24, P["metal_dk"])
    for k in range(4):
        sc.decal_x(t(1.85), t(1.12), t(1.33), 5 + k * 5, 6.5 + k * 5, P["metal"], off=0.9)
    # Corner tower
    sc.box(t(.1), t(.1), 3, t(.6), t(.6), 58, P["wall2"])
    sc.box(t(.05), t(.05), 58, t(.65), t(.65), 62, P["roof_dk"])
    sc.decal_x(t(.6), t(.25), t(.45), 44, 52, P["window_lit"], emissive=True)
    sandbags(sc, t(1.05), t(1.5), t(1.9), t(1.62), rows=2, along="x")
    # Barbed wire on top of the wall
    for k in range(9):
        x = t(.55 + k * .15)
        sc.beam((x, t(.3), 40), (x + 6, t(.3), 43), 0.6, 0.6, P["metal_lt"])
    return sc


def building_factory(frame, frames):
    """Vehicle factory: workshop hall with sawtooth roof, large gate and a jeep in front."""
    sc = Scene()
    plate(sc, 2, P["concrete"])
    wall = P["steel_wall"]
    sc.box(t(.15), t(.2), 3, t(1.45), t(1.8), 44, wall)
    for k in range(4):
        y0 = t(.2 + k * .4)
        sc.gable(t(.12), y0, t(1.48), y0 + t(.4), 44, 14, P["metal"], wall, ridge="x")
        sc.decal_y(y0 + t(.2), t(.3), t(1.3), 50, 55, P["glass"], off=1.0)
    sc.decal_x(t(1.45), t(.5), t(1.3), 3, 36, P["metal_dk"])
    for k in range(6):
        sc.decal_x(t(1.45), t(.5), t(1.3), 6 + 5 * k, 7.2 + 5 * k, P["metal"], off=1.0)
    sc.box(t(1.46), t(.45), 36, t(1.52), t(1.35), 40, P["yellow"])
    with sc.push(T(t(1.72), t(1.45), 3) @ S(0.62)):
        jeep(sc, 0, 0, "idle", 1)
    barrel(sc, t(1.75), t(.3))
    crate(sc, t(1.6), t(.55), 3, t(1.85), t(.8), 14)
    return sc


def building_range(frame, frames):
    """Shooting range: covered shooter booth, firing lane and targets."""
    sc = Scene()
    plate(sc, 2, P["dirt"])
    # Lanes
    for k in range(3):
        y = t(.35 + k * .5)
        sc.box(t(.2), y - 3, 3, t(1.8), y + 3, 4, P["sand"], ground=True, casts=False)
    # Shooter booth with roof
    sc.box(t(1.35), t(.2), 3, t(1.85), t(1.8), 14, P["wood"], top=P["wood_lt"])
    for x, y in ((1.4, .25), (1.8, .25), (1.4, 1.75), (1.8, 1.75)):
        sc.box(t(x) - 1.5, t(y) - 1.5, 14, t(x) + 1.5, t(y) + 1.5, 40, P["wood_dk"])
    sc.gable(t(1.32), t(.18), t(1.88), t(1.82), 40, 10, P["olive"], P["wood_dk"], ridge="y")
    sandbags(sc, t(1.25), t(.2), t(1.34), t(1.8), rows=1, along="y")
    # Targets
    for k in range(3):
        y = t(.35 + k * .5)
        sc.beam((t(.3), y, 3), (t(.3), y, 26), 1.5, 1.5, P["wood_dk"])
        with sc.push(T(t(.32), y, 26) @ Ry(90)):
            sc.prism(0, 0, -1, 1, 9, P["white"], n=14, top=P["white"], bottom=P["white"])
            sc.prism(0, 0, 1, 1.4, 6, P["red"], n=14)
            sc.prism(0, 0, 1.4, 1.8, 2.6, P["white"], n=10)
    return sc


def building_rally_point(frame, frames):
    """Rally point: parade ground with flagpole, tents and crates. Animated flag."""
    sc = Scene()
    plate(sc, 2, P["concrete"])
    sc.box(t(.55), t(.55), 3, t(1.45), t(1.45), 5, P["sand_dk"], ground=True, casts=False)
    for k in range(4):
        a = t(.55 + k * .22)
        sc.box(a, t(.55), 5, a + 2, t(1.45), 5.4, P["white"], ground=True, casts=False)
    # Tents
    for (x0, y0) in ((.15, .15), (.15, 1.2)):
        sc.gable(t(x0), t(y0), t(x0 + .55), t(y0 + .6), 3, 26, P["olive"], P["olive_dk"], ridge="x")
    sc.gable(t(1.2), t(.15), t(1.8), t(.7), 3, 26, P["olive"], P["olive_dk"], ridge="y")
    crate(sc, t(1.5), t(1.45), 3, t(1.75), t(1.7), 14)
    crate(sc, t(1.55), t(1.5), 14, t(1.7), t(1.65), 24)
    flag(sc, t(1.0), t(1.0), 5, 92, P["red"], frame, frames, length=30, height=18)
    return sc


def building_alliance_center(frame, frames):
    """Alliance center: radio station with dome, antenna mast and blue alliance flags."""
    sc = Scene()
    plate(sc, 2, P["concrete_lt"])
    wall = (196, 186, 160)
    sc.box(t(.25), t(.25), 3, t(1.5), t(1.75), 44, wall)
    sc.box(t(.22), t(.22), 44, t(1.53), t(1.78), 48, P["roof_dk"])
    for yc in (.55, .95, 1.35):
        sc.decal_x(t(1.5), t(yc) - 7, t(yc) + 7, 24, 36, P["window"])
    sc.decal_x(t(1.5), t(.8), t(1.1), 3, 22, P["door"])
    sc.decal_x(t(1.5), t(.7), t(1.2), 38, 42, P["blue"], emissive=True)
    sc.prism(t(.8), t(1.2), 48, 54, t(.3), wall, n=14)
    sc.dome(t(.8), t(1.2), 54, t(.3), P["metal_lt"], n=14, rings=4)
    ax, ay = t(.55), t(.5)
    sc.beam((ax, ay, 48), (ax, ay, 118), 2.2, 2.2, P["metal_dk"])
    for z in (70, 88, 104):
        sc.beam((ax - 8, ay, z), (ax + 8, ay, z), 1.2, 1.2, P["metal_dk"])
    sc.box(ax - 1.8, ay - 1.8, 118, ax + 1.8, ay + 1.8, 121, P["red"], emissive=True)
    flag(sc, t(1.75), t(.3), 3, 60, P["blue"], frame, frames, length=20, height=12)
    flag(sc, t(1.75), t(1.7), 3, 60, P["blue"], (frame + 1) % max(frames, 1), frames, length=20, height=12)
    return sc


def map_nest(frame, frames):
    """Zombie nest: organic mound with pulsing glowing spots."""
    sc = Scene()
    plate(sc, 1, (96, 84, 70))
    flesh, dark = (122, 96, 88), (86, 64, 60)
    sc.sphere(t(.5), t(.5), 8, 30, flesh, n=12, rings=6, z_scale=0.8)
    sc.sphere(t(.28), t(.62), 6, 16, dark, n=10, rings=5, z_scale=0.8)
    sc.sphere(t(.7), t(.3), 5, 14, dark, n=10, rings=5, z_scale=0.8)
    glow = mix((150, 40, 30), (255, 90, 60), 0.5 + 0.5 * math.sin(2 * math.pi * frame / max(frames, 1)))
    for (x, y, z) in ((.62, .62, 26), (.42, .66, 18), (.7, .45, 14), (.5, .38, 30)):
        sc.sphere(t(x), t(y), z, 3.2, glow, n=8, rings=3, emissive=True)
    for (x, y) in ((.12, .2), (.85, .8), (.2, .88)):
        sc.beam((t(x), t(y), 3), (t(x) + 5, t(y) + 3, 12), 1.2, 1.2, (220, 214, 200))
    return sc


def map_field(kind):
    def build(frame, frames):
        sc = Scene()
        if kind == "food":
            plate(sc, 1, P["dirt_grass"])
            for k in range(4):
                y = t(.2 + k * .18)
                sc.box(t(.15), y, 3, t(.85), y + 7, 8, P["crop"], top=P["crop_lt"])
            sc.prism(t(.75), t(.8), 3, 16, 10, P["sand"], n=10, r_top=4)
            sc.prism(t(.55), t(.85), 3, 12, 7, P["sand_dk"], n=10, r_top=3)
        elif kind == "wood":
            plate(sc, 1, P["dirt"])
            with sc.push(T(t(.25), t(.62), 0) @ Rx(-90)):
                for x, h in ((0, 6), (12, 6), (6, 16)):
                    sc.prism(x, -h, 0, 34, 6, P["bark"], n=8, top=P["log_end"], bottom=P["log_end"])
            sc.prism(t(.8), t(.3), 3, 14, 3, P["bark"], n=6)
            sc.cone(t(.8), t(.3), 10, 40, 12, P["pine"], n=8)
            sc.prism(t(.3), t(.22), 3, 8, 3.4, P["log_end"], n=8, top=P["log_end"])
        else:
            plate(sc, 1, P["concrete_dk"])
            sc.box(t(.2), t(.2), 3, t(.55), t(.5), 14, P["rust"])
            sc.box(t(.5), t(.45), 3, t(.8), t(.75), 10, P["metal"], top=P["metal_lt"])
            sc.beam((t(.2), t(.75), 5), (t(.85), t(.6), 5), 4, 4, P["metal_dk"])
            sc.beam((t(.3), t(.8), 9), (t(.75), t(.25), 14), 3, 3, P["steel_wall"])
            for (x, y) in ((.7, .2), (.25, .6)):
                barrel(sc, t(x), t(y), r=5, h=13, color=P["rust"], band=P["metal_dk"])
        return sc
    return build


BUILDINGS += [
    ("wall", 2, 1, 1.0, building_wall),
    ("factory", 2, 1, 1.0, building_factory),
    ("range", 2, 1, 1.0, building_range),
    ("rally_point", 2, 4, 0.15, building_rally_point),
    ("alliance_center", 2, 4, 0.2, building_alliance_center),
    ("nest", 1, 4, 0.25, map_nest),
    ("field_food", 1, 1, 1.0, map_field("food")),
    ("field_wood", 1, 1, 1.0, map_field("wood")),
    ("field_steel", 1, 1, 1.0, map_field("steel")),
]

UNITS += [
    ("shooter", "shooter", 1.0, [("idle", 2, 0.5, True), ("walk", 4, 0.13, True), ("attack", 3, 0.09, True),
                                 ("die", 4, 0.14, False)]),
]


_icon_body_base = _icon_body


def _hero_icon(sc, kind):
    pose = pose_default(kind)
    pose["yaw"] = 20
    humanoid(sc, kind, pose)


def _icon_body(sc, name):  # noqa: F811 (extends the base set with Ruinborn icons)
    if name.startswith("hero_"):
        _hero_icon(sc, name)
    elif name == "shield":
        pts = [(0, -12, 30), (0, 12, 30), (0, 12, 14), (0, 0, 0), (0, -12, 14)]
        with sc.push(Rz(20)):
            sc.solid([pts, [(2, p[1] * 0.8, p[2] * 0.85 + 2.5) for p in pts][::-1]], [P["blue"], P["blue"]])
            sc.box(-1, -12, 12, 3, 12, 31, P["blue"], top=(90, 150, 230))
            sc.decal_x(3, -3, 3, 8, 26, P["white"], off=0.5)
            sc.decal_x(3, -9, 9, 18, 22, P["white"], off=0.6)
    elif name == "crate":
        sc.box(-12, -12, 0, 12, 12, 20, P["wood"], top=P["wood_lt"])
        for z in (0, 17):
            sc.box(-12.5, -12.5, z, 12.5, 12.5, z + 3, P["wood_dk"])
        sc.box(-3, -12.6, 3, 3, 12.6, 17, P["wood_dk"])
    elif name == "book":
        sc.box(-10, -14, 0, 10, 14, 6, (140, 50, 44), top=(236, 226, 200))
        sc.box(-10, -14, 6, 10, 14, 8, (160, 60, 50))
        sc.decal_z(8, [(-4, -6), (4, -6), (4, 6), (-4, 6)], P["yellow"])
    elif name == "relocate":
        sc.box(-10, -10, 0, 10, 10, 16, P["wall"], top=P["roof_dk"])
        sc.gable(-11, -11, 11, 11, 16, 10, P["roof_red"], P["wall"], ridge="x")
        sc.beam((12, 0, 10), (24, 0, 10), 4, 4, P["yellow"])
        sc.quad2([(24, -7, 10), (32, 0, 10), (24, 7, 10), (24, 0, 10)], P["yellow"])
    elif name == "sword":
        with sc.push(Rx(35)):
            sc.box(-1.4, -1.4, 10, 1.4, 1.4, 40, P["metal_lt"])
            sc.box(-7, -2, 8, 7, 2, 11, P["yellow"])
            sc.box(-1.8, -1.8, 0, 1.8, 1.8, 8, P["wood_dk"])
    elif name == "clock":
        with sc.push(Ry(90)):
            sc.prism(0, 0, -3, 3, 14, P["metal"], n=16, top=P["white"], bottom=P["metal"])
        sc.beam((3.5, 0, 0), (3.5, 0, 8), 1.4, 1.4, P["black"])
        sc.beam((3.5, 0, 0), (3.5, 6, 0), 1.4, 1.4, P["black"])
    elif name == "scout":
        for y in (-6, 6):
            with sc.push(T(0, y, 6) @ Ry(90)):
                sc.prism(0, 0, -8, 8, 5, P["black"], n=12, top=P["glass"], bottom=(60, 60, 64))
        sc.box(-3, -3, 2, 3, 3, 10, (60, 60, 64))
    elif name == "chat":
        sc.box(-14, -3, 6, 14, 3, 26, P["white"], top=P["white"])
        sc.quad2([(-6, 3, 6), (0, 3, 6), (-8, 3, -2)], P["white"])
        for x in (-7, 0, 7):
            sc.decal_y(3, x - 2, x + 2, 14, 18, P["blue"], off=0.5)
    elif name == "report":
        sc.box(-14, -10, 0, 14, 10, 3, P["white"], top=P["white"])
        sc.quad2([(-14, -10, 3.2), (0, 0, 3.2), (14, -10, 3.2)], (206, 204, 196))
        sc.decal_z(3.3, [(-14, 10), (0, 0), (14, 10)], (216, 214, 206))
    elif name == "alliance":
        for y, col in ((-6, P["blue"]), (6, P["red"])):
            sc.beam((0, y, 0), (0, y, 34), 1.4, 1.4, P["metal_dk"])
            sc.quad2([(0, y, 34), (0, y + (9 if y > 0 else -9), 30), (0, y, 24)], col)
        sc.prism(0, 0, 0, 3, 12, P["concrete"], n=10)
    elif name == "gear":
        sc.prism(0, 0, 0, 6, 11, P["metal"], n=16, top=P["metal_lt"])
        for k in range(8):
            a = math.radians(k * 45)
            x, y = 12 * math.cos(a), 12 * math.sin(a)
            sc.box(x - 3, y - 3, 0, x + 3, y + 3, 6, P["metal"], top=P["metal_lt"])
        sc.prism(0, 0, 6, 6.4, 4, P["metal_dk"], n=12)
    elif name == "lock":
        sc.box(-9, -4, 0, 9, 4, 16, P["yellow"], top=(246, 208, 80))
        with sc.push(T(0, 0, 16) @ Rx(90)):
            for k in range(7):
                a0, a1 = math.radians(k * 180 / 7), math.radians((k + 1) * 180 / 7)
                sc.beam((6.5 * math.cos(a0), 6.5 * math.sin(a0), 0), (6.5 * math.cos(a1), 6.5 * math.sin(a1), 0), 2.4, 2.4, P["metal"])
        sc.decal_y(4, -1.5, 1.5, 5, 11, P["black"], off=0.4)
    elif name == "research":
        sc.prism(0, 0, 0, 14, 11, P["glass"], n=12, r_top=4)
        sc.prism(0, 0, 14, 26, 4, P["glass"], n=10)
        sc.prism(0, 0, 0, 7, 10.5, P["cyan"], n=12, r_top=7, emissive=True)
    elif name == "hospital":
        sc.box(-14, -14, 0, 14, 14, 4, P["white"], top=P["white"])
        sc.decal_z(4, [(-4, -11), (4, -11), (4, -4), (11, -4), (11, 4), (4, 4), (4, 11), (-4, 11), (-4, 4), (-11, 4), (-11, -4), (-4, -4)], P["red"])
    elif name == "gift":
        sc.box(-11, -11, 0, 11, 11, 18, P["red"], top=(220, 70, 60))
        sc.box(-2, -11.4, 0, 2, 11.4, 18.4, P["yellow"])
        sc.box(-11.4, -2, 0, 11.4, 2, 18.4, P["yellow"])
        sc.sphere(-3, 0, 21, 3.5, P["yellow"], n=8, rings=3)
        sc.sphere(3, 0, 21, 3.5, P["yellow"], n=8, rings=3)
    elif name == "star":
        pts = []
        for i in range(10):
            r = 15 if i % 2 == 0 else 6.5
            a = math.pi / 2 + i * math.pi / 5
            pts.append((0, r * math.cos(a), 15 + r * math.sin(a)))
        with sc.push(Rz(25)):
            sc.poly(pts, P["yellow"], inside=(-1, 0, 15), casts=False)
            sc.poly([(2, y, z) for _, y, z in pts][::-1], (246, 208, 80), inside=(3, 0, 15), casts=False)
    elif name == "trophy":
        sc.box(-8, -8, 0, 8, 8, 4, P["wood_dk"])
        sc.prism(0, 0, 4, 12, 2.5, P["yellow"], n=10)
        sc.prism(0, 0, 12, 28, 5, P["yellow"], n=12, r_top=10)
    elif name == "task":
        sc.box(-10, -13, 0, 10, 13, 2, P["wood"], top=P["wood_lt"])
        sc.box(-8, -11, 2, 8, 11, 2.6, P["white"], top=P["white"])
        for k in range(3):
            sc.decal_z(2.7, [(-5, -7 + k * 6), (5, -7 + k * 6), (5, -5 + k * 6), (-5, -5 + k * 6)], P["metal_dk"])
        sc.box(-4, -14, 2, 4, -10, 5, P["metal"])
    elif name == "map":
        sc.sphere(0, 0, 14, 14, P["blue"], n=14, rings=7)
        sc.sphere(4, -3, 18, 7, P["leaf"], n=8, rings=4, z_scale=0.6)
        sc.sphere(-5, 5, 10, 6, P["leaf"], n=8, rings=4, z_scale=0.6)
    elif name == "base":
        sc.box(-12, -12, 0, 12, 12, 18, P["wall"], top=P["roof_dk"])
        sc.box(-5, -5, 18, 5, 5, 28, P["wall2"], top=P["roof_dk"])
        sc.decal_x(12, -3, 3, 0, 10, P["door"])
    elif name == "troops":
        with sc.push(S(1.25)):
            _hero_icon(sc, "soldier")
    elif name == "help":
        sc.box(-6, -9, 0, 6, 9, 10, P["skin"], top=P["skin"])
        for k in range(4):
            y = -7 + k * 4.6
            sc.box(-2, y - 1.8, 10, 2, y + 1.8, 20 - abs(k - 1.5) * 2, P["skin"])
        sc.box(-5, 9, 2, 5, 14, 12, P["skin"])
    elif name == "power":
        with sc.push(Rz(45)):
            for sgn in (-1, 1):
                with sc.push(Rx(sgn * 35)):
                    sc.box(-1.4, -1.4, 6, 1.4, 1.4, 34, P["metal_lt"])
                    sc.box(-6, -1.8, 6, 6, 1.8, 9, P["yellow"])
    else:
        _icon_body_base(sc, name)


ICONS += ["shield", "crate", "book", "relocate", "sword", "clock", "scout", "chat", "report", "alliance", "gear",
          "lock", "research", "hospital", "gift", "star", "trophy", "task", "map", "base", "troops", "help", "power",
          "hero_rhea", "hero_viktor", "hero_kaya", "hero_brock", "hero_nova"]
