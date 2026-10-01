package bayern.kickner.ruinborn.client.render.gfx

import com.badlogic.gdx.graphics.g2d.Animation
import com.badlogic.gdx.graphics.g2d.Animation.PlayMode
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.graphics.g2d.TextureRegion

/**
 * Isometric math matching the sprites (tile diamond 128x64 pixels).
 *
 * World  = tile coordinates as float. Tile (tx, ty) covers [tx, tx+1] x [ty, ty+1].
 *          +x runs to the bottom right on screen, +y to the bottom left.
 * Screen = libGDX world coordinates of the camera (y up). World (0, 0) is at screen (0, 0).
 */
object Iso {
    const val TILE_W = 128f
    const val TILE_H = 64f

    fun screenX(wx: Float, wy: Float): Float = (wx - wy) * (TILE_W / 2f)
    fun screenY(wx: Float, wy: Float): Float = -(wx + wy) * (TILE_H / 2f)

    /** Inverse, e.g. for touch -> tile: floor(worldX(...)) / floor(worldY(...)). */
    fun worldX(sx: Float, sy: Float): Float = sx / TILE_W - sy / TILE_H
    fun worldY(sx: Float, sy: Float): Float = -sx / TILE_W - sy / TILE_H

    /** Draw order: smaller value = further back = drawn first. */
    fun depth(wx: Float, wy: Float): Float = wx + wy

    /** Stable "random" tile variant so grass does not look patterned. */
    fun variant(tx: Int, ty: Int, variants: Int): Int = Math.floorMod(tx * 73856093 xor ty * 19349663, variants)
}

/**
 * Access to all sprites from `atlas/game.atlas`.
 * Animations are built and cached on first access. They are stateless and can be shared by
 * any number of units (each unit only keeps its own stateTime).
 */
class GameSprites(private val atlas: TextureAtlas) {
    private val cache = HashMap<String, Animation<TextureRegion>>()

    fun anim(def: SpriteDef): Animation<TextureRegion> = load(def.region, def.frames, def.frameDuration, def.loop)

    fun anim(unit: UnitSprite, action: UnitAction, dir: Dir): Animation<TextureRegion> {
        val spec = requireNotNull(unit.actions[action]) { "${unit.name} hat keine Aktion ${action.name}" }
        return load(unit.region(action, dir), spec.frames, spec.frameDuration, spec.loop)
    }

    /** Single frame, e.g. tile variant or icon (Scene2D: `TextureRegionDrawable(sprites.frame(IconSprite.FOOD.def))`). */
    fun frame(def: SpriteDef, index: Int = 0): TextureRegion = anim(def).keyFrames[Math.floorMod(index, def.frames)]

    /**
     * Loads everything once and checks frame count and size against the catalog.
     * Throws an exception listing all mismatches. Useful at startup (debug) or in a test.
     */
    fun validateAll() {
        val problems = mutableListOf<String>()
        fun verify(region: String, w: Int, h: Int, block: () -> Animation<TextureRegion>) {
            val anim = try {
                block()
            } catch (e: IllegalStateException) {
                problems += e.message ?: region
                return
            }
            anim.keyFrames.forEachIndexed { i, f ->
                if (f.regionWidth != w || f.regionHeight != h) {
                    problems += "$region[$i]: ${f.regionWidth}x${f.regionHeight}, erwartet ${w}x$h"
                }
            }
        }
        val defs = BuildingSprite.entries.map { it.def } + TileSprite.entries.map { it.def } +
            DecoSprite.entries.map { it.def } + IconSprite.entries.map { it.def } + FxSprite.entries.map { it.def }
        defs.forEach { d -> verify(d.region, d.width, d.height) { anim(d) } }
        UnitSprite.entries.forEach { u ->
            u.actions.keys.forEach { a ->
                Dir.entries.forEach { d -> verify(u.region(a, d), u.width, u.height) { anim(u, a, d) } }
            }
        }
        check(problems.isEmpty()) { "Sprite-Atlas passt nicht zum Katalog:\n" + problems.joinToString("\n") }
    }

    private fun load(region: String, frames: Int, duration: Float, loop: Boolean): Animation<TextureRegion> =
        cache.getOrPut(region) {
            val regions = atlas.findRegions(region) // libGDX returns them sorted by index
            check(regions.size == frames) { "Atlas-Region '$region': erwartet $frames Frame(s), gefunden ${regions.size}" }
            Animation<TextureRegion>(duration, regions, if (loop) PlayMode.LOOP else PlayMode.NORMAL)
        }
}

/** Draws [region] so that its anchor sits on the screen point (sx, sy). */
fun Batch.drawAnchored(region: TextureRegion, sx: Float, sy: Float, anchorX: Int, anchorY: Int, scale: Float = 1f) {
    draw(region, sx - anchorX * scale, sy - anchorY * scale, region.regionWidth * scale, region.regionHeight * scale)
}

/** Draws a sprite at the world point (wx, wy) = ground center of the object. */
fun Batch.drawSprite(sprites: GameSprites, def: SpriteDef, wx: Float, wy: Float, stateTime: Float = 0f) {
    drawAnchored(sprites.anim(def).getKeyFrame(stateTime), Iso.screenX(wx, wy), Iso.screenY(wx, wy), def.anchorX, def.anchorY)
}

/** Building whose footprint starts at tile (tx, ty) (smallest x/y corner). */
fun Batch.drawBuilding(sprites: GameSprites, b: BuildingSprite, tx: Int, ty: Int, stateTime: Float = 0f) {
    val half = b.footprint / 2f
    drawSprite(sprites, b.def, tx + half, ty + half, stateTime)
}

/** Ground tile or overlay on tile (tx, ty). */
fun Batch.drawTile(sprites: GameSprites, tile: TileSprite, tx: Int, ty: Int, variant: Int = 0) {
    val cx = tx + 0.5f
    val cy = ty + 0.5f
    drawAnchored(sprites.frame(tile.def, variant), Iso.screenX(cx, cy), Iso.screenY(cx, cy), tile.def.anchorX, tile.def.anchorY)
}

/** Unit at the world point (wx, wy), e.g. (tx + 0.5, ty + 0.5) for the tile center. */
fun Batch.drawUnit(
    sprites: GameSprites,
    unit: UnitSprite,
    action: UnitAction,
    dir: Dir,
    wx: Float,
    wy: Float,
    stateTime: Float,
) {
    val frame = sprites.anim(unit, action, dir).getKeyFrame(stateTime)
    drawAnchored(frame, Iso.screenX(wx, wy), Iso.screenY(wx, wy), unit.anchorX, unit.anchorY)
}
