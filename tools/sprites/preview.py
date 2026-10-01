#!/usr/bin/env python3
"""
Generates preview images DIRECTLY from the packed atlas (assets/atlas/game.atlas).
Uses the same anchors as the Kotlin code -> shows whether everything sits correctly on the grid.

  python3 tools/preview.py   ->  preview/overview.png, preview/scene.gif, preview/scene.png
"""
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
ATLAS = ROOT / "assets" / "atlas" / "game.atlas"
OUT = ROOT / "preview"
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"


def load_atlas(path):
    """Minimal parser for the libGDX atlas format -> {name: {index: Image}}."""
    regions, page, cur = {}, None, None
    lines = path.read_text().splitlines()
    i = 0
    while i < len(lines):
        ln = lines[i]
        if not ln.strip():
            page = None
        elif page is None:
            page = Image.open(path.parent / ln.strip()).convert("RGBA")
        elif ln.startswith(" "):
            k, v = [s.strip() for s in ln.split(":", 1)]
            cur[k] = v
        elif ":" in ln:
            pass                                   # page property (size, filter, ...)
        else:
            cur = {"name": ln.strip(), "page": page}
            regions.setdefault(cur["name"], []).append(cur)
        i += 1
    out = {}
    for name, lst in regions.items():
        for r in lst:
            if "xy" not in r:
                continue
            x, y = map(int, r["xy"].split(","))
            w, h = map(int, r["size"].split(","))
            out.setdefault(name, {})[int(r.get("index", -1))] = r["page"].crop((x, y, x + w, y + h))
    return out


A = load_atlas(ATLAS)
C = json.loads((ROOT / "sprites.json").read_text())
BY = {e["name"]: e for k in ("buildings", "tiles", "decos", "icons", "fx") for e in C[k]}
UNITS = {u["name"]: u for u in C["units"]}


def frame_at(frames, dur, loop, t):
    n = len(frames)
    i = int(t / dur)
    return frames[i % n] if loop else frames[min(i, n - 1)]


# ---------------------------------------------------------------- Overview
def overview():
    font = ImageFont.truetype(FONT, 13)
    head = ImageFont.truetype(FONT, 18)
    W = 1400
    sheet = Image.new("RGBA", (W, 2400), (52, 56, 62, 255))
    dr = ImageDraw.Draw(sheet)
    y = 10

    def section(title):
        nonlocal y
        dr.text((12, y), title, font=head, fill=(235, 235, 235))
        y += 30

    def row(items, gap=14):
        """items: [(label, image)] -> arrange in rows."""
        nonlocal y
        x, rowh = 12, 0
        for label, im in items:
            if x + im.width > W - 12:
                x, y = 12, y + rowh + 26
                rowh = 0
            sheet.alpha_composite(im, (x, y))
            dr.text((x, y + im.height + 3), label, font=font, fill=(200, 200, 200))
            x += int(max(im.width, dr.textlength(label, font=font))) + gap
            rowh = max(rowh, im.height)
        y += rowh + 40

    section("Gebaeude  (building/*)")
    row([(f"{b['name']} ({b['frames']}f)", A[b["region"]][0]) for b in C["buildings"]])
    section("Einheiten  (unit/<name>/<aktion>_<richtung>) - Richtung se, jeweils 1. Frame")
    items = []
    for u in C["units"]:
        for a in u["actions"]:
            for d in ("se", "ne"):
                items.append((f"{u['name']} {a['action']} {d}", A[f"unit/{u['name']}/{a['action']}_{d}"][0]))
    row([(lab, im.resize((im.width * 2, im.height * 2), Image.NEAREST)) for lab, im in items], gap=8)
    section("Tiles  (tile/*)")
    row([(f"{t['name']}[{i}]", A[t["region"]][i]) for t in C["tiles"] for i in range(t["frames"])])
    section("Deko, Icons, Effekte")
    row([(d["name"], A[d["region"]][0]) for d in C["decos"]] +
        [(i["name"], A[i["region"]][0]) for i in C["icons"]] +
        [(f"explosion[{k}]", A["fx/explosion"][k]) for k in range(len(A["fx/explosion"]))])
    sheet = sheet.crop((0, 0, W, y))
    OUT.mkdir(exist_ok=True)
    sheet.convert("RGB").save(OUT / "overview.png", optimize=True)


# ---------------------------------------------------------------- Scene
def scene():
    MAP = 10
    ox, oy = MAP * 64, 70          # image position of world (0,0) (y down)

    def to_img(wx, wy):
        return ox + (wx - wy) * 64, oy + (wx + wy) * 32

    ground = {}
    for tx in range(MAP):
        for ty in range(MAP):
            ground[(tx, ty)] = ("grass", (tx * 7 + ty * 13) % 3) if tx == 0 or ty == 0 else ("concrete", 0)
    for ty in range(1, MAP):
        ground[(0, ty)] = ("road_y", 0)

    buildings = [("farm", 1, 1), ("oil_pump", 4, 1), ("steel_mill", 7, 1), ("sawmill", 1, 4), ("hq", 4, 4),
                 ("barracks", 7, 4), ("warehouse", 1, 7), ("hospital", 4, 7), ("construction_2x2", 7, 7)]
    decos = [("pine", 0.5, 0.5), ("tree", 2.5, 0.5), ("rock", 5.5, 0.4), ("bush", 6.6, 0.5), ("dead_tree", 9.5, 0.5),
             ("wreck", 8.3, 0.5)]
    FR, DT = 24, 0.1

    frames = []
    for f in range(FR):
        t = f * DT
        im = Image.new("RGBA", (MAP * 128, MAP * 64 + oy + 10), (60, 84, 52, 255))
        # Ground
        for (tx, ty), (name, var) in ground.items():
            e = BY[name]
            sx, sy = to_img(tx + .5, ty + .5)
            im.alpha_composite(A[e["region"]][var], (int(sx - e["ax"]), int(sy - (e["h"] - e["ay"]))))
        # Selection overlay on a tile
        sx, sy = to_img(2.5, 3.5)
        e = BY["select"]
        im.alpha_composite(A[e["region"]][0], (int(sx - e["ax"]), int(sy - (e["h"] - e["ay"]))))

        draw = []   # (depth, image, sx, sy, ax, ay)
        for name, tx, ty in buildings:
            e = BY[name]
            wx, wy = tx + e["footprint"] / 2, ty + e["footprint"] / 2
            fr = frame_at(A[e["region"]], e["duration"], e["loop"], t)
            draw.append((wx + wy, fr, *to_img(wx, wy), e["ax"], e["ay"]))
        for name, wx, wy in decos:
            e = BY[name]
            draw.append((wx + wy, A[e["region"]][0], *to_img(wx, wy), e["ax"], e["ay"]))

        def unit(name, action, d, wx, wy, tt):
            u = UNITS[name]
            spec = next(a for a in u["actions"] if a["action"] == action)
            frs = A[f"unit/{name}/{action}_{d}"]
            fr = frame_at([frs[i] for i in range(len(frs))], spec["duration"], spec["loop"], tt)
            draw.append((wx + wy, fr, *to_img(wx, wy), u["ax"], u["ay"]))

        p = f / FR                                    # 0..1 loop
        unit("soldier", "walk", "se", 1.3 + p * 7.4, 3.5, t)
        unit("soldier", "walk", "se", 0.9 + p * 7.4, 3.9, t + 0.2)
        unit("jeep", "drive", "ne", 0.5, 9.2 - p * 7.0, t)
        unit("worker", "work", "sw", 9.5, 7.8, t)
        unit("worker", "walk", "nw", 3.6 - p * 1.5, 6.5, t)
        unit("zombie", "walk", "sw", 3.5, 0.2 + p * 1.2, t)
        unit("zombie", "attack", "nw", 7.1, 0.35, t)
        unit("brute", "idle", "sw", 8.4, -0.3, t)
        unit("soldier", "attack", "se", 6.3, 0.35, t)
        unit("zombie", "die", "nw", 9.3, 1.5, t * 0.35)
        draw.sort(key=lambda d: d[0])
        for _, fr, sx, sy, ax, ay in draw:
            im.alpha_composite(fr, (int(round(sx - ax)), int(round(sy - (fr.height - ay)))))
        frames.append(im)

    frames[0].convert("RGB").save(OUT / "scene.png", optimize=True)
    small = [fr.convert("RGB").resize((fr.width * 3 // 4, fr.height * 3 // 4), Image.LANCZOS) for fr in frames]
    pal = small[0].quantize(colors=255, method=Image.Quantize.MEDIANCUT)
    q = [s.quantize(palette=pal, dither=Image.Dither.NONE) for s in small]
    q[0].save(OUT / "scene.gif", save_all=True, append_images=q[1:], duration=int(DT * 1000), loop=0, optimize=True)


if __name__ == "__main__":
    overview()
    scene()
    print("Vorschau in", OUT)
