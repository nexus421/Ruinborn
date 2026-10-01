package bayern.kickner.ruinborn.client.screen

import bayern.kickner.ruinborn.client.BuildInfo
import bayern.kickner.ruinborn.client.RuinbornGame
import bayern.kickner.ruinborn.client.dialog.UpdateDialog
import bayern.kickner.ruinborn.client.net.ApiResult
import bayern.kickner.ruinborn.client.render.gfx.BuildingSprite
import bayern.kickner.ruinborn.client.render.gfx.Dir
import bayern.kickner.ruinborn.client.render.gfx.Iso
import bayern.kickner.ruinborn.client.render.gfx.TileSprite
import bayern.kickner.ruinborn.client.render.gfx.UnitAction
import bayern.kickner.ruinborn.client.render.gfx.UnitSprite
import bayern.kickner.ruinborn.client.render.gfx.drawBuilding
import bayern.kickner.ruinborn.client.render.gfx.drawTile
import bayern.kickner.ruinborn.client.render.gfx.drawUnit
import bayern.kickner.ruinborn.client.ui.Palette
import bayern.kickner.ruinborn.client.ui.tr
import bayern.kickner.ruinborn.shared.rules.Validation
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.scenes.scene2d.ui.ButtonGroup
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.viewport.ExtendViewport
import kotlinx.coroutines.launch
import ktx.actors.onChange
import ktx.actors.onClick
import ktx.app.clearScreen
import ktx.async.onRenderingThread

/** Log in or register. Version check on startup (concept section 12). */
class LoginScreen(game: RuinbornGame) : UiScreen(game) {
    private val batch = SpriteBatch()
    private val camera = OrthographicCamera()
    private val viewport = ExtendViewport(VIRTUAL_W, VIRTUAL_H, camera)
    private var time = 0f
    private var registerMode = false
    private val status = game.ui.label("", "small", wrap = true, align = Align.center)
    private val name = TextField("", game.ui.skin).apply { messageText = tr("login.username"); maxLength = Validation.USERNAME_MAX }
    private val password = TextField("", game.ui.skin).apply {
        messageText = tr("login.password"); isPasswordMode = true; setPasswordCharacter('•'); maxLength = Validation.PASSWORD_MAX
    }
    private val invite = TextField("", game.ui.skin).apply { messageText = tr("login.invite") }
    private val form = Table()
    private val submit = TextButton(tr("login.submit"), game.ui.skin)
    private var busy = false

    init {
        val ui = game.ui
        // Form at the top so the on-screen keyboard (Android) does not cover it. The scene sits below.
        val root = Table().apply { setFillParent(true); top().padTop(110f) }
        root.add(ui.label("RUINBORN", "title", align = Align.center).apply { setFontScale(1.8f) }).row()
        root.add(ui.label(tr("login.subtitle"), "muted", align = Align.center)).padBottom(60f).row()
        val panel = ui.panel(26f)
        val tabs = Table()
        val loginTab = TextButton(tr("login.tab.login"), ui.skin, "tab")
        val registerTab = TextButton(tr("login.tab.register"), ui.skin, "tab")
        ButtonGroup(loginTab, registerTab).apply { setMaxCheckCount(1); setMinCheckCount(1) }
        loginTab.isChecked = true
        loginTab.onChange { if (isChecked) setMode(false) }
        registerTab.onChange { if (isChecked) setMode(true) }
        tabs.add(loginTab).growX().height(64f).padRight(8f)
        tabs.add(registerTab).growX().height(64f)
        panel.add(tabs).growX().padBottom(20f).row()
        panel.add(form).growX().row()
        panel.add(submit).growX().height(80f).padTop(16f).row()
        panel.add(status).growX().padTop(12f).row()
        root.add(panel).width(620f).row()
        root.add(ui.label(tr("login.version", BuildInfo.VERSION_NAME), "muted")).padTop(30f).row()
        stage.addActor(root)
        submit.onClick { submit() }
        password.setTextFieldListener { _, c -> if (c == '\r' || c == '\n') submit() }
        setMode(false)
        game.client.store.listen { s ->
            if (s.outdated && stage.actors.none { it is UpdateDialog }) UpdateDialog(game).show(stage)
        }
    }

    private fun setMode(register: Boolean) {
        registerMode = register
        form.clearChildren()
        form.defaults().growX().height(72f).padBottom(12f)
        form.add(name).row()
        form.add(password).row()
        if (register) form.add(invite).row()
        submit.setText(if (register) tr("login.register") else tr("login.submit"))
        status.setText("")
    }

    private fun submit() {
        if (busy) return
        val n = name.text.trim()
        val pw = password.text
        (Validation.username(n) ?: Validation.password(pw))?.let { status.setText(it); status.color = Palette.bad; return }
        busy = true
        status.color = Palette.muted
        status.setText(tr("login.working"))
        game.io.launch {
            val r = if (registerMode) game.client.register(n, pw, invite.text.trim()) else game.client.login(n, pw)
            onRenderingThread {
                busy = false
                when (r) {
                    is ApiResult.Ok -> {
                        password.text = ""
                        status.setText("")
                        game.showBase()
                    }
                    is ApiResult.Fail -> {
                        status.color = Palette.bad
                        status.setText(r.message)
                    }
                }
            }
        }
    }

    override fun show() {
        super.show()
        stage.keyboardFocus = name
        val auto = game.options.autoLogin
        if (auto != null && game.client.state.loggedIn.not()) {
            name.text = auto.first
            password.text = auto.second
            game.options.autoRegisterInvite?.let {
                invite.text = it
                registerMode = true
                game.io.launch {
                    val r = game.client.register(auto.first, auto.second, it)
                    if (r is ApiResult.Fail) game.client.login(auto.first, auto.second)
                    onRenderingThread { if (game.client.state.loggedIn) game.showBase() }
                }
            } ?: submit()
        }
    }

    override fun render(delta: Float) {
        time += delta
        clearScreen(0.10f, 0.12f, 0.10f)
        viewport.apply()
        // Scene at the bottom (fixed distance to the bottom edge), below the form.
        camera.position.set(Iso.screenX(6f, 6f), Iso.screenY(6f, 6f) + viewport.worldHeight / 2f - 180f, 0f)
        camera.update()
        batch.projectionMatrix = camera.combined
        batch.begin()
        val sprites = game.assets.sprites
        for (ty in 0 until 12) for (tx in 0 until 12) {
            batch.drawTile(sprites, if ((tx + ty) % 7 == 0) TileSprite.DIRT else TileSprite.GRASS, tx, ty, Iso.variant(tx, ty, 3))
        }
        batch.drawBuilding(sprites, BuildingSprite.CONSTRUCTION_2X2, 2, 3, time)
        batch.drawBuilding(sprites, BuildingSprite.HQ, 5, 5, time)
        batch.drawBuilding(sprites, BuildingSprite.WALL, 8, 3, time)
        val p = (time * 0.15f) % 1f
        batch.drawUnit(sprites, UnitSprite.ZOMBIE, UnitAction.WALK, Dir.NW, 11f - p * 5f, 9.5f, time)
        batch.drawUnit(sprites, UnitSprite.BRUTE, UnitAction.WALK, Dir.NW, 11.6f - p * 5f, 10.4f, time)
        batch.drawUnit(sprites, UnitSprite.SOLDIER, UnitAction.ATTACK, Dir.SE, 4.5f, 9.8f, time)
        batch.drawUnit(sprites, UnitSprite.SHOOTER, UnitAction.ATTACK, Dir.SE, 3.8f, 10.6f, time)
        batch.end()
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
