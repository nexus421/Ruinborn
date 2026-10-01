package bayern.kickner.ruinborn.client.screen

import bayern.kickner.ruinborn.client.RuinbornGame
import bayern.kickner.ruinborn.client.ui.Hud
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.input.GestureDetector
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.viewport.ExtendViewport
import ktx.app.clearScreen

/** Shared base of base view and map: world camera, gestures, HUD. */
abstract class WorldScreen(game: RuinbornGame, onMap: Boolean) : UiScreen(game) {
    protected val batch = SpriteBatch()
    val camera = OrthographicCamera()
    protected val viewport = ExtendViewport(VIRTUAL_W, VIRTUAL_H, camera)
    val hud = Hud(game, stage, onMap)
    protected var time = 0f
    open val minZoom = 1f
    open val maxZoom = 1f
    private val tmp = Vector3()

    init {
        addInput(GestureDetector(object : GestureDetector.GestureAdapter() {
            override fun pan(x: Float, y: Float, deltaX: Float, deltaY: Float): Boolean {
                val f = viewport.worldWidth / Gdx.graphics.width * camera.zoom
                camera.translate(-deltaX * f, deltaY * f)
                clampCamera()
                return true
            }

            override fun tap(x: Float, y: Float, count: Int, button: Int): Boolean {
                tmp.set(x, y, 0f)
                viewport.unproject(tmp)
                onTap(tmp.x, tmp.y)
                return true
            }

            private var startZoom = 1f

            override fun touchDown(x: Float, y: Float, pointer: Int, button: Int): Boolean {
                startZoom = camera.zoom
                return false
            }

            override fun zoom(initialDistance: Float, distance: Float): Boolean {
                if (maxZoom <= minZoom) return false
                camera.zoom = (startZoom * initialDistance / distance).coerceIn(minZoom, maxZoom)
                clampCamera()
                return true
            }
        }))
        addInput(object : InputAdapter() {
            override fun scrolled(amountX: Float, amountY: Float): Boolean {
                if (maxZoom <= minZoom) return false
                camera.zoom = (camera.zoom * (1f + amountY * 0.12f)).coerceIn(minZoom, maxZoom)
                clampCamera()
                return true
            }
        })
    }

    /** Tap on the world (world/screen coordinates of the camera). */
    abstract fun onTap(sx: Float, sy: Float)

    abstract fun renderWorld()

    open fun clampCamera() {}

    override fun show() {
        super.show()
        hud.update(game.client.state)
    }

    override fun render(delta: Float) {
        time += delta
        clearScreen(0.12f, 0.14f, 0.12f)
        viewport.apply()
        camera.update()
        batch.projectionMatrix = camera.combined
        batch.begin()
        renderWorld()
        batch.end()
        hud.act()
        drawStage(delta)
    }

    override fun resize(width: Int, height: Int) {
        super.resize(width, height)
        viewport.update(width, height)
    }

    override fun dispose() {
        super.dispose()
        batch.dispose()
    }
}
