package bayern.kickner.ruinborn.tools

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.headless.HeadlessApplication
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.g2d.PixmapPacker
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator
import com.badlogic.gdx.tools.bmfont.BitmapFontWriter

/** Characters: libGDX default (including umlauts, ß, €) plus typography and symbols for the UI. */
private const val EXTRA_CHARS = "–—„“”‚‘’…·×÷→←↑↓✓✗★•°±"

/** Generated fonts: name, file, target size in px (generated at 1.5x size, concept section 12). */
private val FONTS = listOf(
    Triple("noto-16", "NotoSans-Regular.ttf", 16),
    Triple("noto-20", "NotoSans-Regular.ttf", 20),
    Triple("noto-28", "NotoSans-Regular.ttf", 28),
    Triple("noto-bold-20", "NotoSans-Bold.ttf", 20),
    Triple("noto-bold-28", "NotoSans-Bold.ttf", 28),
)

/**
 * Generates the bitmap fonts into `assets/fonts/` once (FreeType only here, not in the app).
 * Runs in a headless application because libGDX must be initialized for pixmaps and files. No
 * textures are created, the PixmapPacker belongs to the tool.
 */
fun main() {
    HeadlessApplication(object : ApplicationAdapter() {
        override fun create() {
            FONTS.forEach { (name, file, size) -> generate(name, file, (size * 1.5f).toInt()) }
            Gdx.app.exit()
        }
    }, HeadlessApplicationConfiguration().apply { updatesPerSecond = -1 })
}

private fun generate(name: String, file: String, px: Int) {
    val generator = FreeTypeFontGenerator(Gdx.files.local("assets-raw/fonts/$file"))
    val packer = PixmapPacker(512, 512, Pixmap.Format.RGBA8888, 2, false)
    val param = FreeTypeFontGenerator.FreeTypeFontParameter().apply {
        size = px
        characters = FreeTypeFontGenerator.DEFAULT_CHARS + EXTRA_CHARS
        this.packer = packer
        kerning = true
        hinting = FreeTypeFontGenerator.Hinting.AutoMedium
    }
    val data = generator.generateData(param)
    val out = Gdx.files.local("assets/fonts")
    out.mkdirs()
    val pages = BitmapFontWriter.writePixmaps(packer.pages, out, name)
    val info = BitmapFontWriter.FontInfo().apply {
        face = name
        size = px
        padding = BitmapFontWriter.Padding(1, 1, 1, 1)
    }
    BitmapFontWriter.writeFont(data, pages, out.child("$name.fnt"), info, 512, 512)
    generator.dispose()
    packer.dispose()
    println("Schrift $name ($px px, ${pages.size} Seite(n)) geschrieben")
}
