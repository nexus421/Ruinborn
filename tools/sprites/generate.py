#!/usr/bin/env python3
"""
Generates all placeholder sprites as single frames (assets-raw/sprites/), metadata (tools/sprites/sprites.json)
and the Kotlin catalog (core/.../render/gfx/SpriteCatalog.kt).

  python3 tools/sprites/generate.py    # regenerate everything
Then pack with the libGDX TexturePacker (./gradlew :tools:packTextures).

Conventions
  * File name  <name>_<index>.png -> atlas region "<folder>/<name>", index = frame
  * All frames of ONE sprite have identical size and the same anchor
    (no whitespace stripping), so animations cannot "wobble".
  * Anchor (anchorX/anchorY) = ground center of the object in pixels,
    measured from the BOTTOM left (libGDX coordinates).
"""
import json
import shutil
import sys
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import iso3d
from iso3d import alpha_bbox, render
from models import (BUILDINGS, DECOS, FX, ICONS, TILES, UNITS, deco_scene, deco_shadow, explosion_frames,
                    icon_scene, tile_image, unit_scene, unit_shadow)
from PIL import Image

# Project root: tools/sprites/ -> ../..
ROOT = Path(__file__).resolve().parent.parent.parent
RAW = ROOT / "assets-raw" / "sprites"
HERE = Path(__file__).resolve().parent
KOTLIN_PACKAGE = "bayern.kickner.ruinborn.client.render.gfx"
KOTLIN_OUT = ROOT / "core" / "src" / "main" / "kotlin" / "bayern" / "kickner" / "ruinborn" / "client" / "render" / "gfx" / "SpriteCatalog.kt"

# Unit facings: (key, yaw angle in degrees, world direction dx, dy).
# For 8 directions simply add s/w/n/e with 45/135/225/315 degrees.
DIRS = [("se", 0, (1, 0)), ("sw", 90, (0, 1)), ("nw", 180, (-1, 0)), ("ne", 270, (0, -1))]


def save(img, rel):
    p = RAW / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    img.save(p, optimize=True)


# ---------------------------------------------------------------- Workers (parallel)
def _render_building(args):
    name, N, frames = args
    fn = next(b for b in BUILDINGS if b[0] == name)[4]
    W, Hf = N * 128, N * 64 + 320
    return [render(fn(f, frames), W, Hf, W / 2, Hf - N * 64) for f in range(frames)]


def _render_unit(args):
    name, kind, scale, action, n, yaw = args
    return [render(unit_scene(kind, scale, action, f, n, yaw), 160, 160, 80, 110,
                   under=unit_shadow(kind, scale, yaw)) for f in range(n)]


def main():
    if RAW.exists():
        shutil.rmtree(RAW)
    RAW.mkdir(parents=True)
    catalog = dict(tile=dict(w=128, h=64), dirs=[[k, dx, dy] for k, _, (dx, dy) in DIRS],
                   buildings=[], units=[], tiles=[], decos=[], icons=[], fx=[])
    pool = ProcessPoolExecutor()

    # ---- Buildings: width = footprint*128, anchor = center of the footprint
    jobs = {b[0]: pool.submit(_render_building, (b[0], b[1], b[2])) for b in BUILDINGS}
    for name, N, frames, dur, _ in BUILDINGS:
        imgs = jobs[name].result()
        W, Hf = imgs[0].size
        box = alpha_bbox(imgs)
        assert box[0] >= 0 and box[2] <= W, f"{name}: Gebaeude ragt seitlich aus dem Bild"
        assert box[1] > 0, f"{name}: Canvas zu niedrig"
        top = max(0, box[1] - 2)
        H = Hf - top
        for i, im in enumerate(imgs):
            save(im.crop((0, top, W, Hf)), f"building/{name}_{i}.png")
        catalog["buildings"].append(dict(name=name, region=f"building/{name}", footprint=N, frames=frames,
                                         duration=dur, loop=frames > 1, w=W, h=H, ax=W // 2, ay=N * 32))
        print(f"building/{name:18s} {frames}f {W}x{H}")

    # ---- Units: all actions + directions share one frame size
    for name, kind, scale, actions in UNITS:
        futs = {(a, d): pool.submit(_render_unit, (name, kind, scale, a, n, yaw))
                for a, n, _, _ in actions for d, yaw, _ in DIRS}
        rendered = {k: f.result() for k, f in futs.items()}
        allimgs = [im for v in rendered.values() for im in v]
        x0, y0, x1, y1 = alpha_bbox(allimgs)
        assert x0 > 0 and y0 > 0 and x1 < 160 and y1 < 160, f"{name}: Canvas zu klein {x0, y0, x1, y1}"
        half = max(80 - x0, x1 - 80) + 1
        cx0, cx1, cy0, cy1 = 80 - half, 80 + half, y0 - 1, y1 + 1
        W, H = cx1 - cx0, cy1 - cy0
        for (a, d), imgs in rendered.items():
            for i, im in enumerate(imgs):
                save(im.crop((cx0, cy0, cx1, cy1)), f"unit/{name}/{a}_{d}_{i}.png")
        catalog["units"].append(dict(name=name, w=W, h=H, ax=W // 2, ay=cy1 - 110,
                                     actions=[dict(action=a, frames=n, duration=dur, loop=loop)
                                              for a, n, dur, loop in actions]))
        print(f"unit/{name:22s} {len(allimgs)}f {W}x{H} anchor=({W // 2},{cy1 - 110})")

    # ---- Tiles: 128x64, anchor = diamond center
    for name, variants in TILES:
        for v in range(variants):
            save(tile_image(name, v), f"tile/{name}_{v}.png")
        catalog["tiles"].append(dict(name=name, region=f"tile/{name}", frames=variants, duration=1.0, loop=False,
                                     w=128, h=64, ax=64, ay=32))
    print(f"tile/*  {len(TILES)} Sprites")

    # ---- Decoration: anchor = center on the ground
    for name in DECOS:
        im = render(deco_scene(name), 128, 128, 64, 96, under=deco_shadow(name))
        x0, y0, x1, y1 = alpha_bbox([im])
        half = max(64 - x0, x1 - 64) + 1
        cy0, cy1 = y0 - 1, y1 + 1
        W, H = 2 * half, cy1 - cy0
        save(im.crop((64 - half, cy0, 64 + half, cy1)), f"deco/{name}_0.png")
        catalog["decos"].append(dict(name=name, region=f"deco/{name}", frames=1, duration=1.0, loop=False,
                                     w=W, h=H, ax=half, ay=cy1 - 96))
    print(f"deco/*  {len(DECOS)} Sprites")

    # ---- Icons: 64x64, centered
    for name in ICONS:
        probe = alpha_bbox([render(icon_scene(name), 128, 128, 64, 90)])
        scale = 54.0 / max(probe[2] - probe[0], probe[3] - probe[1])      # icon fills ~54 of 64 px
        im = render(icon_scene(name, scale), 128, 128, 64, 90)
        x0, y0, x1, y1 = alpha_bbox([im])
        assert x0 > 0 and y0 > 0 and x1 < 128 and y1 < 128, f"icon {name}: Canvas zu klein"
        assert x1 - x0 <= 62 and y1 - y0 <= 62, f"icon {name} zu gross"
        out = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
        out.alpha_composite(im.crop((x0, y0, x1, y1)), ((64 - (x1 - x0)) // 2, (64 - (y1 - y0)) // 2))
        save(out, f"icon/{name}_0.png")
        catalog["icons"].append(dict(name=name, region=f"icon/{name}", frames=1, duration=1.0, loop=False,
                                     w=64, h=64, ax=32, ay=32))
    print(f"icon/*  {len(ICONS)} Sprites")

    # ---- Effects
    for name, dur, loop in FX:
        frames, W, H, ax, ay = explosion_frames()
        for i, im in enumerate(frames):
            save(im, f"fx/{name}_{i}.png")
        catalog["fx"].append(dict(name=name, region=f"fx/{name}", frames=len(frames), duration=dur, loop=loop,
                                  w=W, h=H, ax=ax, ay=ay))
    print(f"fx/*    {len(FX)} Sprites")
    pool.shutdown()

    # ---- TexturePacker settings
    pack = dict(pot=True, paddingX=4, paddingY=4, edgePadding=True, duplicatePadding=False, rotation=False,
                minWidth=16, minHeight=16, maxWidth=2048, maxHeight=2048, square=False,
                stripWhitespaceX=False, stripWhitespaceY=False, alphaThreshold=0,
                filterMin="MipMapLinearNearest", filterMag="Linear", wrapX="ClampToEdge", wrapY="ClampToEdge",
                format="RGBA8888", alias=True, outputFormat="png", ignoreBlankImages=False, fast=False,
                combineSubdirectories=True, flattenPaths=False, premultiplyAlpha=False, useIndexes=True,
                bleed=True, bleedIterations=2, limitMemory=True)
    (RAW / "pack.json").write_text(json.dumps(pack, indent=2))
    (HERE / "sprites.json").write_text(json.dumps(catalog, indent=2))
    write_kotlin(catalog)
    n = len(list(RAW.rglob("*.png")))
    print(f"fertig: {n} PNG-Frames in {RAW}")


# ---------------------------------------------------------------- Kotlin catalog
def _const(name):
    return name.upper()


def _f(v):
    return f"{v}f"


def _def(e):
    return (f'SpriteDef("{e["region"]}", {e["frames"]}, {_f(e["duration"])}, {str(e["loop"]).lower()}, '
            f'{e["w"]}, {e["h"]}, {e["ax"]}, {e["ay"]})')


def write_kotlin(c):
    L = []
    a = L.append
    a("// GENERATED by tools/sprites/generate.py. Do not edit by hand, regenerate instead.")
    a(f"package {KOTLIN_PACKAGE}")
    a("")
    a("import kotlin.math.sqrt")
    a("")
    a("/**")
    a(" * A sprite in the atlas: region name, number of frames (animation or variants), frame duration,")
    a(" * pixel size and anchor. The anchor is the ground center of the object, measured from the BOTTOM left.")
    a(" */")
    a("data class SpriteDef(")
    a("    val region: String,")
    a("    val frames: Int,")
    a("    val frameDuration: Float,")
    a("    val loop: Boolean,")
    a("    val width: Int,")
    a("    val height: Int,")
    a("    val anchorX: Int,")
    a("    val anchorY: Int,")
    a(")")
    a("")
    a("data class AnimSpec(val frames: Int, val frameDuration: Float, val loop: Boolean)")
    a("")
    a("/** Facing of a unit. dx/dy = direction in world (tile) coordinates. */")
    a("enum class Dir(val key: String, val dx: Float, val dy: Float) {")
    for k, dx, dy in c["dirs"]:
        ln = (dx * dx + dy * dy) ** 0.5
        a(f'    {k.upper()}("{k}", {_f(round(dx / ln, 6))}, {_f(round(dy / ln, 6))}),')
    a("    ;")
    a("")
    a("    companion object {")
    a("        /** Closest direction for a movement (dx, dy) in world coordinates. */")
    a("        fun fromDelta(dx: Float, dy: Float): Dir {")
    a("            val len = sqrt(dx * dx + dy * dy)")
    a("            if (len < 1e-6f) return entries.first()")
    a("            return entries.maxBy { it.dx * dx / len + it.dy * dy / len }")
    a("        }")
    a("    }")
    a("}")
    a("")
    actions = sorted({x["action"] for u in c["units"] for x in u["actions"]},
                     key=["idle", "walk", "attack", "work", "die", "drive", "wreck"].index)
    a("enum class UnitAction(val key: String) {")
    for x in actions:
        a(f'    {x.upper()}("{x}"),')
    a("}")
    a("")
    a("enum class UnitSprite(")
    a("    val key: String,")
    a("    val width: Int,")
    a("    val height: Int,")
    a("    val anchorX: Int,")
    a("    val anchorY: Int,")
    a("    val actions: Map<UnitAction, AnimSpec>,")
    a(") {")
    for u in c["units"]:
        acts = ", ".join(f"UnitAction.{x['action'].upper()} to AnimSpec({x['frames']}, {_f(x['duration'])}, "
                         f"{str(x['loop']).lower()})" for x in u["actions"])
        a(f'    {_const(u["name"])}("{u["name"]}", {u["w"]}, {u["h"]}, {u["ax"]}, {u["ay"]}, mapOf({acts})),')
    a("    ;")
    a("")
    a('    fun region(action: UnitAction, dir: Dir): String = "unit/$key/${action.key}_${dir.key}"')
    a("}")
    a("")
    a("/** Building. footprint = edge length of the footprint in tiles (1, 2 or 3). */")
    a("enum class BuildingSprite(val footprint: Int, val def: SpriteDef) {")
    for b in c["buildings"]:
        a(f'    {_const(b["name"])}({b["footprint"]}, {_def(b)}),')
    a("}")
    a("")
    a("/** Ground tiles and overlays. For tiles the frames are variants (e.g. GRASS 0..2), not an animation. */")
    a("enum class TileSprite(val def: SpriteDef) {")
    for e in c["tiles"]:
        a(f"    {_const(e['name'])}({_def(e)}),")
    a("}")
    a("")
    a("enum class DecoSprite(val def: SpriteDef) {")
    for e in c["decos"]:
        a(f"    {_const(e['name'])}({_def(e)}),")
    a("}")
    a("")
    a("enum class IconSprite(val def: SpriteDef) {")
    for e in c["icons"]:
        a(f"    {_const(e['name'])}({_def(e)}),")
    a("}")
    a("")
    a("enum class FxSprite(val def: SpriteDef) {")
    for e in c["fx"]:
        a(f"    {_const(e['name'])}({_def(e)}),")
    a("}")
    a("")
    out = KOTLIN_OUT
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(L))


if __name__ == "__main__":
    sys.exit(main())
