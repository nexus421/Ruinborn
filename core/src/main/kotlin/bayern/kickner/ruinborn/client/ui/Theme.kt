package bayern.kickner.ruinborn.client.ui

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.NinePatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.PixmapTextureData
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.utils.Disposable
import ktx.style.button
import ktx.style.label
import ktx.style.progressBar
import ktx.style.scrollPane
import ktx.style.selectBox
import ktx.style.skin
import ktx.style.slider
import ktx.style.textButton
import ktx.style.textField
import ktx.style.window
import com.badlogic.gdx.scenes.scene2d.ui.List as UiList

/** UI colors: dark panels with amber as accent color. */
object Palette {
    val panel = Color(0.11f, 0.13f, 0.17f, 0.94f)
    val panelLight = Color(0.18f, 0.21f, 0.27f, 0.96f)
    val border = Color(0.36f, 0.40f, 0.48f, 1f)
    val accent = Color(0.95f, 0.66f, 0.23f, 1f)
    val accentDark = Color(0.78f, 0.48f, 0.12f, 1f)
    val text = Color(0.93f, 0.93f, 0.90f, 1f)
    val muted = Color(0.66f, 0.68f, 0.72f, 1f)
    val good = Color(0.45f, 0.82f, 0.40f, 1f)
    val bad = Color(0.93f, 0.33f, 0.28f, 1f)
    val info = Color(0.40f, 0.66f, 0.95f, 1f)
    val own = Color(0.35f, 0.85f, 0.35f, 1f)
    val ally = Color(0.35f, 0.60f, 1.00f, 1f)
    val enemy = Color(0.95f, 0.30f, 0.26f, 1f)
    val disabled = Color(0.32f, 0.34f, 0.38f, 1f)
}

/**
 * UI skin built entirely in code (ktx style), without skin JSON (concept section 12). Backgrounds are generated
 * as rounded nine-patches from pixmaps.
 */
class Theme(fonts: Map<String, BitmapFont>) : Disposable {
    private val textures = mutableListOf<Texture>()
    private val pixmaps = mutableListOf<Pixmap>()

    val small: BitmapFont = fonts.getValue("noto-16")
    val normal: BitmapFont = fonts.getValue("noto-20")
    val large: BitmapFont = fonts.getValue("noto-28")
    val bold: BitmapFont = fonts.getValue("noto-bold-20")
    val title: BitmapFont = fonts.getValue("noto-bold-28")

    /** White pixel for bars, lines and tints. */
    val white: TextureRegion = TextureRegion(texture(Pixmap(4, 4, Pixmap.Format.RGBA8888).apply { setColor(Color.WHITE); fill() }))

    /**
     * Managed texture: the pixmap is kept so libGDX can re-upload the texture after the GL context is lost
     * (Android, app in background). Otherwise panels and buttons would turn black afterwards.
     */
    private fun texture(p: Pixmap): Texture = Texture(PixmapTextureData(p, null, false, false, true)).also {
        it.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        textures += it
        pixmaps += p
    }

    /** Rounded rectangle with border as a nine-patch. */
    fun rounded(fill: Color, border: Color? = null, radius: Int = 12, borderWidth: Int = 2): Drawable {
        val size = radius * 2 + 4
        val p = Pixmap(size, size, Pixmap.Format.RGBA8888)
        p.blending = Pixmap.Blending.None
        fun roundRect(inset: Int, c: Color) {
            p.setColor(c)
            val r = (radius - inset).coerceAtLeast(1)
            val x0 = inset
            val y0 = inset
            val x1 = size - 1 - inset
            val y1 = size - 1 - inset
            p.fillRectangle(x0 + r, y0, x1 - x0 - 2 * r + 1, y1 - y0 + 1)
            p.fillRectangle(x0, y0 + r, x1 - x0 + 1, y1 - y0 - 2 * r + 1)
            p.fillCircle(x0 + r, y0 + r, r)
            p.fillCircle(x1 - r, y0 + r, r)
            p.fillCircle(x0 + r, y1 - r, r)
            p.fillCircle(x1 - r, y1 - r, r)
        }
        if (border != null) {
            roundRect(0, border)
            roundRect(borderWidth, fill)
        } else roundRect(0, fill)
        val t = texture(p)
        return NinePatchDrawable(NinePatch(TextureRegion(t), radius + 1, radius + 1, radius + 1, radius + 1)).apply {
            leftWidth = radius.toFloat(); rightWidth = radius.toFloat(); topHeight = (radius * 0.7f); bottomHeight = (radius * 0.7f)
        }
    }

    fun tinted(c: Color): Drawable = TextureRegionDrawable(white).tint(c)

    val panel = rounded(Palette.panel, Palette.border, 14)
    val panelLight = rounded(Palette.panelLight, null, 10)
    val buttonUp = rounded(Palette.accent, Palette.accentDark, 12, 3)
    val buttonDown = rounded(Palette.accentDark, Palette.accentDark, 12, 3)
    val buttonDisabled = rounded(Palette.disabled, null, 12)
    val secondaryUp = rounded(Palette.panelLight, Palette.border, 12)
    val secondaryDown = rounded(Palette.border, Palette.border, 12)
    val dangerUp = rounded(Color(0.70f, 0.22f, 0.20f, 1f), Color(0.50f, 0.14f, 0.12f, 1f), 12, 3)
    val tabChecked = rounded(Palette.accent, null, 10)
    val field = rounded(Color(0.07f, 0.08f, 0.11f, 1f), Palette.border, 10)
    val bannerRed = rounded(Color(0.62f, 0.12f, 0.10f, 0.95f), Palette.bad, 10)
    val bannerYellow = rounded(Color(0.55f, 0.42f, 0.08f, 0.95f), Palette.accent, 10)
    val bannerBlue = rounded(Color(0.12f, 0.28f, 0.52f, 0.95f), Palette.info, 10)
    val badge = rounded(Palette.bad, null, 10)
    val dim = tinted(Color(0f, 0f, 0f, 0.55f))

    val skin: Skin = skin { s ->
        label { font = normal; fontColor = Palette.text }
        label("small") { font = small; fontColor = Palette.text }
        label("muted") { font = small; fontColor = Palette.muted }
        label("bold") { font = bold; fontColor = Palette.text }
        label("title") { font = title; fontColor = Palette.accent }
        label("large") { font = large; fontColor = Palette.text }
        label("good") { font = small; fontColor = Palette.good }
        label("bad") { font = small; fontColor = Palette.bad }
        label("accent") { font = bold; fontColor = Palette.accent }
        textButton {
            font = bold; fontColor = Color(0.12f, 0.10f, 0.08f, 1f); disabledFontColor = Palette.muted
            up = buttonUp; down = buttonDown; disabled = buttonDisabled
        }
        textButton("secondary") {
            font = normal; fontColor = Palette.text; disabledFontColor = Palette.muted
            up = secondaryUp; down = secondaryDown; disabled = buttonDisabled
        }
        textButton("danger") {
            font = bold; fontColor = Palette.text; disabledFontColor = Palette.muted
            up = dangerUp; down = secondaryDown; disabled = buttonDisabled
        }
        textButton("tab") {
            font = normal; fontColor = Palette.text; checkedFontColor = Color(0.12f, 0.10f, 0.08f, 1f)
            up = secondaryUp; down = secondaryDown; checked = tabChecked
        }
        textButton("link") { font = small; fontColor = Palette.info; downFontColor = Palette.accent }
        button { up = secondaryUp; down = secondaryDown }
        button("flat") { }
        window {
            titleFont = title; titleFontColor = Palette.accent; background = panel; stageBackground = dim
        }
        textField {
            font = normal; fontColor = Palette.text; messageFont = normal; messageFontColor = Palette.muted
            background = field; focusedBackground = rounded(Color(0.07f, 0.08f, 0.11f, 1f), Palette.accent, 10)
            cursor = tinted(Palette.accent).also { it.minWidth = 2f }
            selection = tinted(Color(0.95f, 0.66f, 0.23f, 0.4f))
        }
        scrollPane {
            vScrollKnob = rounded(Palette.border, null, 4).also { it.minWidth = 6f }
        }
        slider("default-horizontal") {
            background = rounded(Color(0.07f, 0.08f, 0.11f, 1f), Palette.border, 6).also { it.minHeight = 12f }
            knob = rounded(Palette.accent, Palette.accentDark, 14, 2).also { it.minWidth = 36f; it.minHeight = 36f }
            knobBefore = rounded(Palette.accentDark, null, 6).also { it.minHeight = 12f }
        }
        progressBar("default-horizontal") {
            background = rounded(Color(0.07f, 0.08f, 0.11f, 1f), null, 6).also { it.minHeight = 12f }
            knobBefore = rounded(Palette.good, null, 6).also { it.minHeight = 12f; it.minWidth = 0f }
        }
        selectBox {
            font = normal; fontColor = Palette.text; background = field
            scrollStyle = com.badlogic.gdx.scenes.scene2d.ui.ScrollPane.ScrollPaneStyle().apply { background = panelLight }
            listStyle = UiList.ListStyle(normal, Palette.accent, Palette.text, tinted(Color(0.95f, 0.66f, 0.23f, 0.3f))).apply {
                background = panelLight
            }
        }
        s.add("white", white)
    }

    override fun dispose() {
        textures.forEach { it.dispose() }
        pixmaps.forEach { it.dispose() }
        skin.dispose()
    }
}
