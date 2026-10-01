package bayern.kickner.ruinborn.client.screen

import bayern.kickner.ruinborn.client.RuinbornGame
import bayern.kickner.ruinborn.client.dialog.TileInfoDialog
import bayern.kickner.ruinborn.client.dialog.openStartDialog
import bayern.kickner.ruinborn.client.render.MapRenderer
import bayern.kickner.ruinborn.client.render.gfx.IconSprite
import bayern.kickner.ruinborn.client.render.gfx.Iso
import bayern.kickner.ruinborn.client.ui.tr
import com.badlogic.gdx.scenes.scene2d.ui.Table
import kotlinx.coroutines.launch

/** World map: swiping pans, two fingers or the mouse wheel zoom. A button centers the own base. */
class MapScreen(game: RuinbornGame) : WorldScreen(game, onMap = true) {
    private val renderer = MapRenderer(game.assets)
    private var selected: Pair<Int, Int>? = null
    private var centered = false
    private var startDialogShown = false
    override val minZoom = 0.5f
    override val maxZoom = 3.0f

    init {
        val overlay = Table().apply { setFillParent(true); bottom().right().padBottom(150f).padRight(16f) }
        overlay.add(game.ui.iconButton(IconSprite.BASE, tr("map.center"), size = 48f) { centerOnBase() })
        stage.addActor(overlay)
    }

    override fun show() {
        super.show()
        game.io.launch { game.client.reloadMap() }
        if (centered.not()) {
            centerOnBase()
            centered = game.client.state.player != null
        }
        if (startDialogShown.not() && game.options.startDialog != null) {
            startDialogShown = true
            openStartDialog(game, stage, game.options.startDialog)
        }
    }

    override fun onBack() = game.showBase()

    fun centerOnBase() {
        val b = game.client.state.player?.base ?: return
        focus(b.x, b.y)
    }

    fun focus(x: Int, y: Int) {
        camera.position.set(Iso.screenX(x + 0.5f, y + 0.5f), Iso.screenY(x + 0.5f, y + 0.5f), 0f)
        clampCamera()
    }

    override fun renderWorld() {
        val s = game.client.state
        val rules = s.rules ?: return
        renderer.render(batch, camera, s, rules, game.client.api.serverTime.now(), time, selected)
    }

    override fun onTap(sx: Float, sy: Float) {
        val (x, y) = renderer.tileAt(sx, sy)
        val map = game.client.state.map
        if (x !in 0 until map.width || y !in 0 until map.height) return
        selected = x to y
        TileInfoDialog(game, x, y).apply { onClosed = { selected = null } }.show(stage)
    }

    override fun clampCamera() {
        val m = game.client.state.map
        val minX = Iso.screenX(0f, m.height.toFloat())
        val maxX = Iso.screenX(m.width.toFloat(), 0f)
        val minY = Iso.screenY(m.width.toFloat(), m.height.toFloat())
        val maxY = Iso.screenY(0f, 0f)
        camera.position.x = camera.position.x.coerceIn(minX, maxX)
        camera.position.y = camera.position.y.coerceIn(minY, maxY)
    }
}
