package bayern.kickner.ruinborn.client.ui

import bayern.kickner.ruinborn.client.Assets
import bayern.kickner.ruinborn.client.render.gfx.IconSprite
import bayern.kickner.ruinborn.shared.balance.Cost
import bayern.kickner.ruinborn.shared.model.Resource
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Container
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Stack
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.Widget
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Scaling
import ktx.actors.onClick

/** Small building blocks for all screens and dialogs. */
class Ui(val assets: Assets) {
    val theme = assets.theme
    val skin = theme.skin

    fun label(text: String, style: String = "default", wrap: Boolean = false, align: Int = Align.left): Label =
        (if (wrap) WrapLabel(text, skin, style) else Label(text, skin, style)).apply {
            this.wrap = wrap
            setAlignment(align)
        }

    fun button(text: String, style: String = "default", enabled: Boolean = true, onClick: () -> Unit): TextButton =
        TextButton(text, skin, style).apply {
            isDisabled = enabled.not()
            touchable = if (enabled) Touchable.enabled else Touchable.disabled
            pad(8f, 18f, 8f, 18f)
            onClick { if (isDisabled.not()) onClick() }
        }

    fun icon(i: IconSprite, size: Float = 36f): Image = Image(assets.icon(i)).apply {
        setScaling(Scaling.fit)
        setSize(size, size)
    }

    fun resourceIcon(r: Resource, size: Float = 30f) = icon(
        when (r) {
            Resource.FOOD -> IconSprite.FOOD
            Resource.WOOD -> IconSprite.WOOD
            Resource.STEEL -> IconSprite.STEEL
        }, size,
    )

    /** Icon button with a label below and an optional counter (bottom bar). */
    fun iconButton(i: IconSprite, text: String, badge: Int = 0, size: Float = 52f, onClick: () -> Unit): Button {
        val b = Button(skin, "flat")
        val stack = Stack()
        val inner = Table()
        inner.add(icon(i, size)).size(size).row()
        inner.add(label(text, "small", align = Align.center))
        stack.add(inner)
        if (badge > 0) {
            val badgeTable = Table()
            badgeTable.top().right()
            badgeTable.add(Container(label(if (badge > 99) "99+" else badge.toString(), "small", align = Align.center)).apply {
                background = theme.badge
                pad(0f, 8f, 0f, 8f)
            })
            stack.add(badgeTable)
        }
        b.add(stack).pad(2f)
        b.onClick { onClick() }
        return b
    }

    /** Costs in one line. Unaffordable amounts in red. */
    fun cost(c: Cost, available: Map<Resource, Long>? = null): Table = Table().apply {
        Resource.entries.forEach { r ->
            val amount = c[r]
            if (amount <= 0) return@forEach
            add(resourceIcon(r, 26f)).size(26f).padRight(4f)
            val ok = available == null || (available[r] ?: 0) >= amount
            add(label(Fmt.short(amount), if (ok) "small" else "bad")).padRight(14f)
        }
    }

    /** Progress bar without its own texture. */
    fun bar(fraction: () -> Float, color: Color = Palette.good, height: Float = 10f): Widget = object : Widget() {
        override fun getPrefHeight() = height
        override fun getPrefWidth() = 120f
        override fun draw(batch: Batch, parentAlpha: Float) {
            val c = batch.color.cpy()
            batch.setColor(0f, 0f, 0f, 0.6f * parentAlpha)
            batch.draw(theme.white, x, y, width, height)
            batch.setColor(color.r, color.g, color.b, color.a * parentAlpha)
            batch.draw(theme.white, x, y, width * fraction().coerceIn(0f, 1f), height)
            batch.color = c
        }
    }

    fun panel(pad: Float = 14f): Table = Table().apply {
        background = theme.panel
        pad(pad)
    }

    fun row(pad: Float = 10f): Table = Table().apply {
        background = theme.panelLight
        pad(pad)
    }

    /** Short toast at the bottom of the screen. */
    fun toast(stage: Stage, text: String, color: Color = Palette.text) {
        val t = Container(label(text, "default", wrap = true, align = Align.center).apply { this.color = color }).apply {
            background = theme.panel
            pad(14f, 22f, 14f, 22f)
            width(stage.width - 80f)
        }
        t.pack()
        t.setPosition((stage.width - t.width) / 2, stage.height * 0.22f)
        t.touchable = Touchable.disabled
        stage.addActor(t)
        t.addAction(Actions.sequence(Actions.delay(2.6f), Actions.fadeOut(0.4f), Actions.removeActor()))
    }
}

/**
 * Wrapping label without its own minimum width: takes the width the table gives it instead of widening the row
 * to the full text length.
 */
class WrapLabel(text: CharSequence, skin: com.badlogic.gdx.scenes.scene2d.ui.Skin, style: String) : Label(text, skin, style) {
    override fun getMinWidth(): Float = 0f
    override fun getPrefWidth(): Float = 0f
}

/** This actor does nothing except take up space. */
fun spacer(w: Float = 0f, h: Float = 0f): Actor = object : Actor() {}.apply { setSize(w, h) }
