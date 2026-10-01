package bayern.kickner.ruinborn.client.render

import bayern.kickner.ruinborn.client.Assets
import bayern.kickner.ruinborn.client.PlotLayout
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
import bayern.kickner.ruinborn.client.ui.Palette
import bayern.kickner.ruinborn.shared.balance.Balance
import bayern.kickner.ruinborn.shared.dto.PlayerState
import bayern.kickner.ruinborn.shared.model.Plot
import bayern.kickner.ruinborn.shared.model.TimerKind
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import kotlin.math.floor

/**
 * Draws the base isometrically: 20 building slots (4 × 5) from `base_layout.json`, buildings with their level,
 * construction sites with a progress bar, empty slots with a construction icon and locked slots with a padlock
 * (concept section 12).
 */
class BaseRenderer(private val assets: Assets) {
    private val sprites = assets.sprites
    private val layout = assets.layout
    private val theme = assets.theme
    private val glyph = GlyphLayout()
    private val plotsByTile: Map<Pair<Int, Int>, PlotLayout> = buildMap {
        layout.plots.forEach { p -> for (dx in 0 until p.size) for (dy in 0 until p.size) put((p.x + dx) to (p.y + dy), p) }
    }

    val width: Int get() = layout.width
    val height: Int get() = layout.height

    fun plotAt(wx: Float, wy: Float): Plot? = plotsByTile[floor(wx).toInt() to floor(wy).toInt()]?.plot

    fun centerOf(plot: Plot): Pair<Float, Float> {
        val p = layout.plots.first { it.plot == plot }
        return (p.x + p.size / 2f) to (p.y + p.size / 2f)
    }

    private class Drawable(val depth: Float, val draw: () -> Unit)

    fun render(batch: Batch, s: PlayerState, balance: Balance, now: Long, time: Float, selected: Plot?) {
        val tint = SpriteMap.skinTint(s.cosmetics.skin)
        val buildings = s.buildings.associateBy { it.plot }
        val timers = s.timers.filter { it.kind == TimerKind.BUILD }.associateBy { it.plot }
        val hq = buildings[Plot.HQ]?.level ?: 1

        // 1) Ground: grass at the edge, concrete for slots, road in between
        for (ty in 0 until height) for (tx in 0 until width) {
            val border = tx == 0 || ty == 0 || tx == width - 1 || ty == height - 1
            val inPlot = plotsByTile.containsKey(tx to ty)
            when {
                border -> batch.drawTile(sprites, TileSprite.GRASS, tx, ty, Iso.variant(tx, ty, 3))
                inPlot -> {
                    batch.color = tint
                    batch.drawTile(sprites, TileSprite.CONCRETE, tx, ty)
                    batch.color = Color.WHITE
                }
                (tx - 1) % 4 == 0 -> batch.drawTile(sprites, TileSprite.ROAD_Y, tx, ty)
                (ty - 1) % 4 == 0 -> batch.drawTile(sprites, TileSprite.ROAD_X, tx, ty)
                else -> batch.drawTile(sprites, TileSprite.DIRT, tx, ty)
            }
        }
        selected?.let { sel ->
            val p = layout.plots.first { it.plot == sel }
            for (dx in 0 until p.size) for (dy in 0 until p.size) batch.drawTile(sprites, TileSprite.SELECT, p.x + dx, p.y + dy)
        }

        // 2) Objects sorted by depth
        val list = ArrayList<Drawable>()
        layout.plots.forEach { p ->
            val cx = p.x + p.size / 2f
            val cy = p.y + p.size / 2f
            val b = buildings[p.plot]
            val t = timers[p.plot]
            list += Drawable(Iso.depth(cx, cy)) {
                when {
                    b != null -> {
                        val sprite = SpriteMap.building(b.type)
                        if (p.plot == Plot.HQ) batch.color = tint
                        batch.drawSprite(sprites, sprite.def, cx, cy, time)
                        batch.color = Color.WHITE
                        if (t != null) batch.drawSprite(sprites, BuildingSprite.CONSTRUCTION_1X1.def, cx + 0.7f, cy + 0.7f, time)
                    }
                    t != null -> batch.drawSprite(sprites, BuildingSprite.CONSTRUCTION_2X2.def, cx, cy, time)
                    else -> {}
                }
            }
            if (t != null) {
                list += Drawable(Iso.depth(cx + 1.3f, cy + 1.3f)) {
                    batch.drawUnit(sprites, UnitSprite.WORKER, UnitAction.WORK, Dir.NW, cx + 1.25f, cy + 1.3f, time + p.x)
                }
            }
        }
        // Decoration at the edge
        for (i in 0 until width step 3) {
            list += Drawable(Iso.depth(i + 0.5f, 0.4f)) { batch.drawSprite(sprites, (if (i % 2 == 0) DecoSprite.PINE else DecoSprite.TREE).def, i + 0.5f, 0.4f) }
            list += Drawable(Iso.depth(0.4f, i + 0.5f)) { batch.drawSprite(sprites, (if (i % 2 == 0) DecoSprite.TREE else DecoSprite.BUSH).def, 0.4f, i + 0.5f) }
        }
        list.sortBy { it.depth }
        list.forEach { it.draw() }

        // 3) Labels: level, construction progress, empty and locked slots
        layout.plots.forEach { p ->
            val cx = p.x + p.size / 2f
            val cy = p.y + p.size / 2f
            val sx = Iso.screenX(cx, cy)
            val sy = Iso.screenY(cx, cy)
            val b = buildings[p.plot]
            val t = timers[p.plot]
            val needHq = requiredHq(p.plot, balance)
            when {
                b != null -> {
                    val def = SpriteMap.building(b.type).def
                    badge(batch, sx, sy + def.height - def.anchorY - 8f, b.level.toString())
                }
                t == null && hq < needHq -> {
                    batch.drawAnchored(assets.icon(IconSprite.LOCK), sx, sy + 10f, 32, 0, 0.9f)
                    text(batch, sx, sy + 8f, "HQ $needHq", Palette.muted)
                }
                t == null -> {
                    batch.setColor(1f, 1f, 1f, 0.55f)
                    batch.drawSprite(sprites, BuildingSprite.CONSTRUCTION_1X1.def, cx, cy)
                    batch.color = Color.WHITE
                    text(batch, sx, sy + 70f, "+", Palette.accent, big = true)
                }
            }
            if (t != null) {
                val total = (t.endsAt - t.startedAt).coerceAtLeast(1)
                val f = ((now - t.startedAt).toFloat() / total).coerceIn(0f, 1f)
                val w = 140f
                batch.setColor(0f, 0f, 0f, 0.7f)
                batch.draw(theme.white, sx - w / 2, sy - 30f, w, 14f)
                batch.color = Palette.good
                batch.draw(theme.white, sx - w / 2 + 2, sy - 28f, (w - 4) * f, 10f)
                batch.color = Color.WHITE
            }
        }
    }

    /** Required HQ level for a slot, `null` if the slot is always free. */
    fun requiredHq(plot: Plot, balance: Balance): Int =
        if (plot.isResourcePlot) balance.resourcePlots.getValue(plot) else balance.buildings.getValue(plot.fixedType!!).unlockHq

    private fun badge(batch: Batch, x: Float, y: Float, text: String) {
        val font = theme.bold
        glyph.setText(font, text)
        val w = glyph.width + 18f
        val h = 34f
        batch.color = Palette.panel
        batch.draw(theme.white, x - w / 2, y - h / 2, w, h)
        batch.color = Palette.accent
        batch.draw(theme.white, x - w / 2, y - h / 2, w, 3f)
        batch.color = Color.WHITE
        font.color = Palette.text
        font.draw(batch, text, x - glyph.width / 2, y + glyph.height / 2)
    }

    private fun text(batch: Batch, x: Float, y: Float, text: String, color: Color, big: Boolean = false) {
        val font = if (big) theme.title else theme.bold
        glyph.setText(font, text)
        font.color = color
        font.draw(batch, text, x - glyph.width / 2, y + glyph.height)
        font.color = Color.WHITE
    }
}
