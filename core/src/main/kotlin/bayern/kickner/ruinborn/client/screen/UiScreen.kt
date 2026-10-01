package bayern.kickner.ruinborn.client.screen

import bayern.kickner.ruinborn.client.RuinbornGame
import bayern.kickner.ruinborn.client.ui.Palette
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.InputProcessor
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.utils.viewport.ExtendViewport
import ktx.app.KtxScreen

/** Virtual resolution in portrait mode (concept section 12). */
const val VIRTUAL_W = 720f
const val VIRTUAL_H = 1280f

/** Screen with a scene2d stage for UI and dialogs. */
abstract class UiScreen(val game: RuinbornGame) : KtxScreen {
    val stage = Stage(ExtendViewport(VIRTUAL_W, VIRTUAL_H))
    protected val input = InputMultiplexer(object : com.badlogic.gdx.InputAdapter() {
        // Back key (Android) or Esc: close the topmost dialog, otherwise [onBack].
        override fun keyDown(keycode: Int): Boolean {
            if (keycode != com.badlogic.gdx.Input.Keys.BACK && keycode != com.badlogic.gdx.Input.Keys.ESCAPE) return false
            val top = game.openDialogs.lastOrNull { it.stage == stage }
            if (top != null) top.close() else onBack()
            return true
        }
    }, stage)

    /** Back without an open dialog. Default: do nothing (the app is not closed). */
    open fun onBack() {}

    fun toast(text: String, color: Color = Palette.text) = game.ui.toast(stage, text, color)

    protected fun addInput(p: InputProcessor) = input.addProcessor(p)

    override fun show() {
        Gdx.input.inputProcessor = input
        Gdx.input.setCatchKey(com.badlogic.gdx.Input.Keys.BACK, true)
    }

    override fun hide() {
        game.openDialogs.toList().forEach { it.close() }
        // Otherwise the on-screen keyboard (Android) would stay open over the next screen.
        stage.keyboardFocus = null
        Gdx.input.setOnscreenKeyboardVisible(false)
    }

    override fun resize(width: Int, height: Int) {
        stage.viewport.update(width, height, true)
        game.openDialogs.toList().filter { it.stage == stage }.forEach { it.layoutIn(stage) }
    }

    protected fun drawStage(delta: Float) {
        stage.act(delta)
        stage.viewport.apply()
        stage.draw()
    }

    override fun dispose() {
        stage.dispose()
    }
}
