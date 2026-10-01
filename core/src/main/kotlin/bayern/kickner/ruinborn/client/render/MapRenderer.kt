package bayern.kickner.ruinborn.client.render

import bayern.kickner.ruinborn.client.Assets
import bayern.kickner.ruinborn.client.render.gfx.BuildingSprite
import bayern.kickner.ruinborn.client.render.gfx.DecoSprite
import bayern.kickner.ruinborn.client.render.gfx.Dir
import bayern.kickner.ruinborn.client.render.gfx.IconSprite
import bayern.kickner.ruinborn.client.render.gfx.Iso
import bayern.kickner.ruinborn.client.render.gfx.TileSprite
import bayern.kickner.ruinborn.client.render.gfx.UnitAction
import bayern.kickner.ruinborn.client.render.gfx.UnitSprite
import bayern.kickner.ruinborn.client.render.gfx.drawAnchored
import bayern.kickner.ruinborn.client.render.gfx.drawSprite
import bayern.kickner.ruinborn.client.render.gfx.drawTile
import bayern.kickner.ruinborn.client.render.gfx.drawUnit
import bayern.kickner.ruinborn.client.state.GameState
import bayern.kickner.ruinborn.client.ui.Fmt
import bayern.kickner.ruinborn.client.ui.Palette
import bayern.kickner.ruinborn.shared.dto.MapObjectDto
import bayern.kickner.ruinborn.shared.dto.MarchDto
import bayern.kickner.ruinborn.shared.model.MapObjectKind
import bayern.kickner.ruinborn.shared.model.MarchKind
import bayern.kickner.ruinborn.shared.model.MarchState
import bayern.kickner.ruinborn.shared.rules.Rules
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.math.MathUtils
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.sqrt

/** Relation of a map object or march to the viewer (concept section 12: green, blue, red). */
enum class Relation { OWN, ALLY, ENEMY, NEUTRAL }

fun relationOf(playerId: Long?, allianceId: Long?, me: Long, myAlliance: Long?): Relation = when {
    playerId == null -> Relation.NEUTRAL
    playerId == me -> Relation.OWN
    myAlliance != null && allianceId == myAlliance -> Relation.ALLY
    else -> Relation.ENEMY
}

fun Relation.color(): Color = when (this) {
    Relation.OWN -> Palette.own
    Relation.ALLY -> Palette.ally
    Relation.ENEMY -> Palette.enemy
    Relation.NEUTRAL -> Palette.muted
}

/**
 * Draws the world map (100 × 100 tiles, isometric). Only visible tiles plus a one-tile border are drawn.
 * Each zone has its own ground tint. Marches are lines with a unit icon whose position is interpolated linearly
 * from start and arrival time (concept section 12).
 */
class MapRenderer(private val assets: Assets) {
    private val sprites = assets.sprites
    private val theme = assets.theme
    private val glyph = GlyphLayout()

    private class Drawable(val depth: Float, val draw: () -> Unit)

    fun tileAt(sx: Float, sy: Float): Pair<Int, Int> = floor(Iso.worldX(sx, sy)).toInt() to floor(Iso.worldY(sx, sy)).toInt()

    fun render(batch: Batch, cam: OrthographicCamera, s: GameState, rules: Rules, now: Long, time: Float, selected: Pair<Int, Int>?) {
        val me = s.player?.playerId ?: -1
        val myAlliance = s.player?.alliance?.id
        val w = s.map.width
        val h = s.map.height
        // Visible area in world tiles
        val hw = cam.viewportWidth * cam.zoom / 2
        val hh = cam.viewportHeight * cam.zoom / 2
        val corners = listOf(
            cam.position.x - hw to cam.position.y - hh, cam.position.x + hw to cam.position.y - hh,
            cam.position.x - hw to cam.position.y + hh, cam.position.x + hw to cam.position.y + hh,
        ).map { (x, y) -> Iso.worldX(x, y) to Iso.worldY(x, y) }
        val minX = (corners.minOf { it.first }.toInt() - 1).coerceAtLeast(0)
        val maxX = (corners.maxOf { it.first }.toInt() + 2).coerceAtMost(w - 1)
        val minY = (corners.minOf { it.second }.toInt() - 1).coerceAtLeast(0)
        val maxY = (corners.maxOf { it.second }.toInt() + 2).coerceAtMost(h - 1)
        val byTile = s.map.byTile

        // 1) Ground per zone: grass outside, dirt in the middle, concrete in the core (ruins)
        for (ty in minY..maxY) for (tx in minX..maxX) {
            when (rules.zoneOf(tx, ty)) {
                1 -> batch.drawTile(sprites, TileSprite.GRASS, tx, ty, Iso.variant(tx, ty, 3))
                2 -> {
                    batch.setColor(0.92f, 0.86f, 0.78f, 1f)
                    batch.drawTile(sprites, TileSprite.DIRT, tx, ty)
                }
                else -> {
                    batch.setColor(0.70f, 0.68f, 0.66f, 1f)
                    batch.drawTile(sprites, if (Iso.variant(tx, ty, 9) == 0) TileSprite.ROAD_X else TileSprite.CONCRETE, tx, ty)
                }
            }
            batch.color = Color.WHITE
        }
        selected?.let { (x, y) -> batch.drawTile(sprites, TileSprite.SELECT, x, y) }

        // 2) Objects and decoration by depth
        val list = ArrayList<Drawable>()
        for (ty in minY..maxY) for (tx in minX..maxX) {
            val o = byTile[tx to ty]
            val cx = tx + 0.5f
            val cy = ty + 0.5f
            if (o == null) {
                val v = Iso.variant(tx, ty, 13)
                if (v == 0) {
                    val deco = when (rules.zoneOf(tx, ty)) {
                        1 -> if (Iso.variant(ty, tx, 3) == 0) DecoSprite.PINE else DecoSprite.TREE
                        2 -> if (Iso.variant(ty, tx, 2) == 0) DecoSprite.DEAD_TREE else DecoSprite.ROCK
                        else -> DecoSprite.WRECK
                    }
                    list += Drawable(Iso.depth(cx, cy)) { batch.drawSprite(sprites, deco.def, cx, cy) }
                }
                continue
            }
            list += Drawable(Iso.depth(cx, cy)) { drawObject(batch, o, cx, cy, time, me, myAlliance) }
        }
        // Marches: lines below the units
        val marches = s.allMarches.filter { it.state == MarchState.OUTBOUND || it.state == MarchState.RETURNING }
        marches.forEach { m -> drawLine(batch, m, relationOf(m.playerId, m.allianceId, me, myAlliance).color()) }
        marches.forEach { m ->
            val (px, py) = positionOf(m, now)
            if (px < minX - 2 || px > maxX + 2 || py < minY - 2 || py > maxY + 2) return@forEach
            val dir = Dir.fromDelta((m.toX - m.fromX).toFloat(), (m.toY - m.fromY).toFloat())
            val (unit, action) = when {
                m.kind == MarchKind.SCOUT -> UnitSprite.JEEP to UnitAction.DRIVE
                m.kind == MarchKind.GATHER -> UnitSprite.WORKER to UnitAction.WALK
                else -> UnitSprite.SOLDIER to UnitAction.WALK
            }
            list += Drawable(Iso.depth(px, py)) {
                batch.drawUnit(sprites, unit, action, dir, px, py, time + m.id)
                if (m.troopCount > 60 && unit == UnitSprite.SOLDIER) batch.drawUnit(sprites, unit, action, dir, px - 0.3f, py + 0.25f, time + m.id + 0.3f)
            }
        }
        list.sortBy { it.depth }
        list.forEach { it.draw() }

        // 3) Labels at a constant screen size
        val labels = cam.zoom <= 2.4f
        val font = theme.bold
        val scale = cam.zoom.coerceIn(0.6f, 2.4f)
        font.data.setScale(scale)
        for (ty in minY..maxY) for (tx in minX..maxX) {
            val o = byTile[tx to ty] ?: continue
            val sx = Iso.screenX(tx + 0.5f, ty + 0.5f)
            val sy = Iso.screenY(tx + 0.5f, ty + 0.5f)
            val rel = relationOf(o.playerId, o.allianceId, me, myAlliance)
            when (o.kind) {
                MapObjectKind.BASE -> if (labels || rel == Relation.OWN) {
                    val name = (o.allianceTag?.let { "[$it] " } ?: "") + (o.playerName ?: "?")
                    label(batch, font, sx, sy + 150f * 0.45f + 18f * scale, name, rel.color(), scale)
                    if (o.shielded) batch.drawAnchored(assets.icon(IconSprite.SHIELD), sx + 60f, sy + 40f, 32, 32, 0.7f)
                }
                MapObjectKind.ZOMBIE -> if (labels) label(batch, font, sx, sy + 70f, o.level.toString(), Palette.bad, scale)
                MapObjectKind.NEST -> if (labels) label(batch, font, sx, sy + 70f, "Nest ${o.level}", Palette.bad, scale)
                MapObjectKind.FIELD -> if (labels) label(batch, font, sx, sy + 56f, "${o.level} · ${Fmt.short(o.amount ?: 0)}", Palette.accent, scale)
            }
        }
        font.data.setScale(1f)
    }

    private fun drawObject(batch: Batch, o: MapObjectDto, cx: Float, cy: Float, time: Float, me: Long, myAlliance: Long?) {
        when (o.kind) {
            MapObjectKind.BASE -> {
                val rel = relationOf(o.playerId, o.allianceId, me, myAlliance)
                val c = rel.color()
                batch.setColor(c.r, c.g, c.b, 0.9f)
                batch.drawTile(sprites, TileSprite.SELECT, floor(cx).toInt(), floor(cy).toInt())
                batch.color = SpriteMap.skinTint(o.skin)
                val def = BuildingSprite.HQ.def
                batch.drawAnchored(sprites.anim(def).getKeyFrame(time), Iso.screenX(cx, cy), Iso.screenY(cx, cy), def.anchorX, def.anchorY, 0.42f)
                batch.color = Color.WHITE
                if (o.shielded) {
                    batch.setColor(0.4f, 0.7f, 1f, 0.22f)
                    batch.drawTile(sprites, TileSprite.VALID, floor(cx).toInt(), floor(cy).toInt())
                    batch.color = Color.WHITE
                }
            }
            MapObjectKind.ZOMBIE -> {
                val brute = o.level >= 11
                val unit = if (brute) UnitSprite.BRUTE else UnitSprite.ZOMBIE
                batch.drawUnit(sprites, UnitSprite.ZOMBIE, UnitAction.IDLE, Dir.SW, cx - 0.22f, cy - 0.1f, time + o.id)
                batch.drawUnit(sprites, unit, UnitAction.IDLE, Dir.SE, cx + 0.15f, cy + 0.12f, time + o.id * 0.7f)
                if (o.level >= 6) batch.drawUnit(sprites, UnitSprite.ZOMBIE, UnitAction.IDLE, Dir.NE, cx + 0.3f, cy - 0.3f, time + o.id * 0.3f)
            }
            MapObjectKind.NEST -> batch.drawSprite(sprites, BuildingSprite.NEST.def, cx, cy, time)
            MapObjectKind.FIELD -> {
                batch.drawSprite(sprites, SpriteMap.field(o.resType!!).def, cx, cy, time)
                if (o.occupiedByMarchId != null) batch.drawUnit(sprites, UnitSprite.WORKER, UnitAction.WORK, Dir.SW, cx + 0.2f, cy + 0.25f, time + o.id)
            }
        }
    }

    /** Current position of a march in world tiles (tile center). */
    fun positionOf(m: MarchDto, now: Long): Pair<Float, Float> {
        val total = (m.arriveAt - m.departAt).coerceAtLeast(1)
        val f = ((now - m.departAt).toFloat() / total).coerceIn(0f, 1f)
        return (m.fromX + (m.toX - m.fromX) * f + 0.5f) to (m.fromY + (m.toY - m.fromY) * f + 0.5f)
    }

    private fun drawLine(batch: Batch, m: MarchDto, color: Color) {
        val x0 = Iso.screenX(m.fromX + 0.5f, m.fromY + 0.5f)
        val y0 = Iso.screenY(m.fromX + 0.5f, m.fromY + 0.5f)
        val x1 = Iso.screenX(m.toX + 0.5f, m.toY + 0.5f)
        val y1 = Iso.screenY(m.toX + 0.5f, m.toY + 0.5f)
        val len = sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0))
        val angle = atan2(y1 - y0, x1 - x0) * MathUtils.radiansToDegrees
        batch.setColor(color.r, color.g, color.b, 0.75f)
        batch.draw(theme.white, x0, y0 - 2f, 0f, 2f, len, 4f, 1f, 1f, angle)
        batch.color = Color.WHITE
    }

    private fun label(batch: Batch, font: BitmapFont, x: Float, y: Float, text: String, color: Color, scale: Float) {
        glyph.setText(font, text)
        val pad = 8f * scale
        batch.setColor(0f, 0f, 0f, 0.6f)
        batch.draw(theme.white, x - glyph.width / 2 - pad, y - glyph.height - pad * 0.8f, glyph.width + pad * 2, glyph.height + pad * 1.6f)
        batch.color = Color.WHITE
        font.color = color
        font.draw(batch, text, x - glyph.width / 2, y)
        font.color = Color.WHITE
    }
}
