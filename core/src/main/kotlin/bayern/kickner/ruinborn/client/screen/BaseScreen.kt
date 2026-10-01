package bayern.kickner.ruinborn.client.screen

import bayern.kickner.ruinborn.client.RuinbornGame
import bayern.kickner.ruinborn.client.dialog.BuildingDialog
import bayern.kickner.ruinborn.client.dialog.openStartDialog
import bayern.kickner.ruinborn.client.render.BaseRenderer
import bayern.kickner.ruinborn.client.render.gfx.Iso
import bayern.kickner.ruinborn.shared.model.Plot

/** Base view with 20 building slots, scrollable by swiping, no zoom (concept section 12). */
class BaseScreen(game: RuinbornGame) : WorldScreen(game, onMap = false) {
    private val renderer = BaseRenderer(game.assets)
    private var selected: Plot? = null
    private var centered = false
    private var startDialogShown = false

    override fun show() {
        super.show()
        if (centered.not()) {
            val (cx, cy) = renderer.centerOf(Plot.HQ)
            camera.position.set(Iso.screenX(cx + 3f, cy + 3f), Iso.screenY(cx + 3f, cy + 3f), 0f)
            centered = true
        }
        if (startDialogShown.not() && game.options.startDialog != null) {
            startDialogShown = true
            openStartDialog(game, stage, game.options.startDialog)
        }
    }

    private var startScreenDone = false

    override fun renderWorld() {
        val s = game.client.state
        val p = s.player ?: return
        if (startScreenDone.not() && game.options.startScreen == "map" && s.map.objects.isNotEmpty()) {
            startScreenDone = true
            com.badlogic.gdx.Gdx.app.postRunnable { game.showMap() }
        }
        val b = s.balance ?: return
        renderer.render(batch, p, b, game.client.api.serverTime.now(), time, selected)
    }

    override fun onTap(sx: Float, sy: Float) {
        val plot = renderer.plotAt(Iso.worldX(sx, sy), Iso.worldY(sx, sy)) ?: return
        if (game.client.state.player == null) return
        selected = plot
        BuildingDialog(game, plot).apply { onClosed = { selected = null } }.show(stage)
    }

    override fun clampCamera() {
        val w = renderer.width.toFloat()
        val h = renderer.height.toFloat()
        val minX = Iso.screenX(0f, h)
        val maxX = Iso.screenX(w, 0f)
        val minY = Iso.screenY(w, h) - 200f
        val maxY = Iso.screenY(0f, 0f) + 200f
        camera.position.x = camera.position.x.coerceIn(minX, maxX)
        camera.position.y = camera.position.y.coerceIn(minY, maxY)
    }
}
