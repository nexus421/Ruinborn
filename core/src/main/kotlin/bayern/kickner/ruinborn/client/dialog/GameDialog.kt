package bayern.kickner.ruinborn.client.dialog

import bayern.kickner.ruinborn.client.RuinbornGame
import bayern.kickner.ruinborn.client.state.GameState
import bayern.kickner.ruinborn.client.ui.Ui
import bayern.kickner.ruinborn.client.ui.tr
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.Window
import com.badlogic.gdx.utils.Align
import ktx.actors.onClick

/**
 * Base of all dialogs: modal scene2d window above the screen with title, close button and
 * scrollable content. [build] fills the content. On state changes the screen calls [onState].
 */
abstract class GameDialog(val game: RuinbornGame, title: String) : Window(title, game.ui.skin) {
    val ui: Ui get() = game.ui
    protected val content = Table().top()
    private val scroll = ScrollPane(content, ui.skin).apply {
        setFadeScrollBars(false)
        setScrollingDisabled(true, false)
        setOverscroll(false, true)
    }
    private var lastState: GameState? = null

    /** Rebuild the dialog when the state changes (default: yes). */
    open val rebuildOnState: Boolean = true

    init {
        isModal = true
        isMovable = false
        titleLabel.setAlignment(Align.left)
        titleTable.add(TextButton("×", ui.skin, "secondary").apply {
            pad(2f, 14f, 2f, 14f)
            onClick { close() }
        }).padRight(4f)
        padTop(64f)
        pad(64f, 16f, 16f, 16f)
        add(scroll).grow()
    }

    val state: GameState get() = game.client.state

    /** Builds the content from the current state. */
    abstract fun build()

    private var builtWithoutPlayer = false

    fun refresh() {
        builtWithoutPlayer = state.player == null
        val y = scroll.scrollY
        content.clearChildren()
        content.defaults().growX().padBottom(10f)
        build()
        content.invalidateHierarchy()
        stage?.let { layoutIn(it) }
        scroll.layout()
        scroll.scrollY = y
        scroll.updateVisualScroll()
    }

    open fun onState(s: GameState) {
        // If the dialog was built before the first game state arrived, do it once now.
        val firstData = builtWithoutPlayer && s.player != null
        if ((rebuildOnState || firstData) && s != lastState) {
            lastState = s
            refresh()
        }
    }

    /**
     * Shows the dialog. [stage] may be `null` (e.g. the stage of a dialog that has been closed in the meantime when
     * a response arrives late). In that case nothing happens instead of crashing the app.
     */
    fun show(stage: Stage?): GameDialog {
        if (stage == null) return this
        lastState = state
        refresh()
        stage.addActor(this)
        layoutIn(stage)
        stage.keyboardFocus = this
        game.openDialogs += this
        return this
    }

    /**
     * Fixed width, height based on content (at most up to just below the bars). The content height is only known
     * once wrapping texts know their width, so lay out with maximum height first, then measure.
     */
    fun layoutIn(stage: Stage) {
        val maxH = stage.height - 300f
        setSize(stage.width - 32f, maxH)
        validate()
        content.width = scroll.width
        content.invalidate()
        content.validate()
        val wanted = content.prefHeight + padTop + padBottom + 8f
        height = wanted.coerceIn(220f, maxH)
        setPosition((stage.width - width) / 2, (stage.height - height) / 2 + 10f)
        invalidateHierarchy()
        validate()
    }

    /** Called after closing (e.g. to clear a selection on the map). */
    var onClosed: () -> Unit = {}

    open fun close() {
        game.openDialogs -= this
        // If the input focus was in this dialog (e.g. chat), close the on-screen keyboard as well.
        val focus = stage?.keyboardFocus
        if (focus != null && focus.isDescendantOf(this)) {
            stage.keyboardFocus = null
            com.badlogic.gdx.Gdx.input.setOnscreenKeyboardVisible(false)
        }
        remove()
        onClosed()
    }

    /** Shorthand for headings inside the dialog. */
    protected fun section(text: String) {
        content.add(ui.label(text, "accent")).padTop(8f).row()
    }

    protected fun hint(text: String) {
        content.add(ui.label(text, "muted", wrap = true)).row()
    }

    protected fun empty(text: String = tr("common.empty")) = hint(text)
}
