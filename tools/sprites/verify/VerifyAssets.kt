/*
 * Checks the asset package against the REAL libGDX code (without a GPU):
 *  1. game.atlas is parsed with libGDX' TextureAtlasData (pages, filters, regions).
 *  2. Each region is compared pixel by pixel with its source PNG from raw/.
 *  3. A real TextureAtlas is built with a dummy OpenGL and GameSprites.validateAll()
 *     as well as animation playback (frame order, loop/normal) are tested.
 *  4. Catalog <-> raw/: no missing and no orphaned files.
 *
 * Usage: java -cp verify.jar:gdx.jar VerifyAssetsKt <atlas/game.atlas> <raw directory>
 */
import bayern.kickner.ruinborn.client.render.gfx.BuildingSprite
import bayern.kickner.ruinborn.client.render.gfx.DecoSprite
import bayern.kickner.ruinborn.client.render.gfx.Dir
import bayern.kickner.ruinborn.client.render.gfx.FxSprite
import bayern.kickner.ruinborn.client.render.gfx.GameSprites
import bayern.kickner.ruinborn.client.render.gfx.IconSprite
import bayern.kickner.ruinborn.client.render.gfx.Iso
import bayern.kickner.ruinborn.client.render.gfx.TileSprite
import bayern.kickner.ruinborn.client.render.gfx.UnitSprite
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Graphics
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.TextureData
import com.badlogic.gdx.graphics.g2d.Animation
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.graphics.g2d.TextureRegion
import java.io.File
import java.lang.reflect.Proxy
import javax.imageio.ImageIO
import kotlin.system.exitProcess

private val errors = mutableListOf<String>()
private fun fail(msg: String) { errors += msg }

private fun <T> stub(type: Class<T>): T = type.cast(
    Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, m, _ ->
        when (m.returnType) {
            java.lang.Boolean.TYPE -> false
            Integer.TYPE -> 0
            java.lang.Float.TYPE -> 0f
            java.lang.Long.TYPE -> 0L
            java.lang.Double.TYPE -> 0.0
            else -> null
        }
    },
)

/** Texture data without pixels. Enough for libGDX to compute regions/UVs. */
private class SizeOnlyData(private val w: Int, private val h: Int) : TextureData {
    override fun getType() = TextureData.TextureDataType.Custom
    override fun isPrepared() = true
    override fun prepare() {}
    override fun consumePixmap(): Pixmap = throw UnsupportedOperationException()
    override fun disposePixmap() = false
    override fun consumeCustomData(target: Int) {}
    override fun getWidth() = w
    override fun getHeight() = h
    override fun getFormat() = Pixmap.Format.RGBA8888
    override fun useMipMaps() = false
    override fun isManaged() = false
}

fun main(args: Array<String>) {
    val atlasFile = File(args[0])
    val rawDir = File(args[1])
    val data = TextureAtlas.TextureAtlasData(FileHandle(atlasFile), FileHandle(atlasFile.parentFile), false)

    // ---- 1. Pages
    for (p in data.pages) {
        val img = ImageIO.read(p.textureFile.file())
        if (img == null) fail("Seite ${p.textureFile.name()} nicht lesbar")
        else if (img.width != p.width.toInt() || img.height != p.height.toInt()) fail("Seite ${p.textureFile.name()}: Groesse passt nicht")
        val pot = { v: Int -> v > 0 && (v and (v - 1)) == 0 }
        if (!pot(p.width.toInt()) || !pot(p.height.toInt())) fail("Seite nicht Zweierpotenz: ${p.width}x${p.height}")
        if (p.width > 2048 || p.height > 2048) fail("Seite groesser als 2048: ${p.width}x${p.height}")
        println("Seite ${p.textureFile.name()}: ${p.width.toInt()}x${p.height.toInt()} min=${p.minFilter} mag=${p.magFilter} mipmaps=${p.useMipMaps}")
    }

    // ---- 2. Pixel comparison of each region with its source
    val pageImages = data.pages.associateWith { ImageIO.read(it.textureFile.file()) }
    var pixels = 0L
    for (r in data.regions) {
        val src = File(rawDir, "${r.name}_${r.index}.png")
        if (r.rotate) fail("${r.name}[${r.index}] ist rotiert")
        if (r.offsetX != 0f || r.offsetY != 0f || r.originalWidth != r.width || r.originalHeight != r.height) {
            fail("${r.name}[${r.index}]: Whitespace wurde entfernt (Offsets) - Anker wuerde springen")
        }
        if (!src.exists()) { fail("Quelle fehlt: $src"); continue }
        val a = ImageIO.read(src)
        val page = pageImages.getValue(r.page)
        if (a.width != r.width || a.height != r.height) { fail("${r.name}[${r.index}]: Groesse ${r.width}x${r.height} != Quelle ${a.width}x${a.height}"); continue }
        var diff = 0
        for (y in 0 until r.height) for (x in 0 until r.width) {
            val s = a.getRGB(x, y)
            val d = page.getRGB(r.left + x, r.top + y)
            val sa = s ushr 24
            // Transparent pixels: TexturePacker 'bleed' changes RGB (intended, against dark edges) -> only check alpha
            if (sa != (d ushr 24) || (sa != 0 && (s and 0xFFFFFF) != (d and 0xFFFFFF))) diff++
            pixels++
        }
        if (diff > 0) fail("${r.name}[${r.index}]: $diff Pixel weichen ab")
    }
    println("Regionen: ${data.regions.size}, verglichene Pixel: $pixels")

    // ---- 3. Real TextureAtlas + GameSprites with dummy OpenGL
    Gdx.gl = stub(GL20::class.java)
    Gdx.gl20 = Gdx.gl
    Gdx.graphics = stub(Graphics::class.java)
    for (p in data.pages) p.texture = Texture(SizeOnlyData(p.width.toInt(), p.height.toInt()))
    val atlas = TextureAtlas(data)
    val sprites = GameSprites(atlas)
    try {
        sprites.validateAll()
        println("GameSprites.validateAll(): OK")
    } catch (e: IllegalStateException) {
        fail(e.message ?: "validateAll fehlgeschlagen")
    }

    fun checkPlayback(name: String, anim: Animation<TextureRegion>, loop: Boolean) {
        val n = anim.keyFrames.size
        val dur = anim.frameDuration
        for (i in 0 until n) {
            val f = anim.getKeyFrame(i * dur + dur * 0.5f) as TextureAtlas.AtlasRegion
            if (f.index != i) fail("$name: bei Frame $i wird Index ${f.index} gezeigt")
        }
        val after = anim.getKeyFrame(n * dur + dur * 0.5f) as TextureAtlas.AtlasRegion
        val expected = if (loop) 0 else n - 1
        if (after.index != expected) fail("$name: nach dem Ende Index ${after.index}, erwartet $expected")
    }
    var anims = 0
    val defs = BuildingSprite.entries.map { it.def } + FxSprite.entries.map { it.def }
    defs.forEach { d -> checkPlayback(d.region, sprites.anim(d), d.loop); anims++ }
    UnitSprite.entries.forEach { u ->
        u.actions.forEach { (a, spec) ->
            Dir.entries.forEach { d -> checkPlayback(u.region(a, d), sprites.anim(u, a, d), spec.loop); anims++ }
        }
    }
    println("Animations-Wiedergabe geprueft: $anims Animationen")

    // ---- 4. Catalog <-> raw/
    val expected = mutableSetOf<String>()
    (BuildingSprite.entries.map { it.def } + TileSprite.entries.map { it.def } + DecoSprite.entries.map { it.def } +
        IconSprite.entries.map { it.def } + FxSprite.entries.map { it.def }).forEach { d ->
        repeat(d.frames) { expected += "${d.region}_$it.png" }
    }
    UnitSprite.entries.forEach { u ->
        u.actions.forEach { (a, spec) -> Dir.entries.forEach { d -> repeat(spec.frames) { expected += "${u.region(a, d)}_$it.png" } } }
    }
    val actual = rawDir.walk().filter { it.isFile && it.extension == "png" }.map { it.relativeTo(rawDir).invariantSeparatorsPath }.toSet()
    (expected - actual).forEach { fail("Im Katalog, aber keine Datei: $it") }
    (actual - expected).forEach { fail("Datei nicht im Katalog: $it") }
    println("Katalog: ${expected.size} Frames, raw/: ${actual.size} Dateien")

    // ---- 5. Iso math
    for ((wx, wy) in listOf(0f to 0f, 3.5f to 1.25f, -2f to 7f, 10.75f to -4.5f)) {
        val sx = Iso.screenX(wx, wy)
        val sy = Iso.screenY(wx, wy)
        if (kotlin.math.abs(Iso.worldX(sx, sy) - wx) > 1e-4f || kotlin.math.abs(Iso.worldY(sx, sy) - wy) > 1e-4f) fail("Iso-Umkehrung falsch fuer ($wx,$wy)")
    }
    if (Iso.screenX(1f, 0f) != 64f || Iso.screenY(1f, 0f) != -32f) fail("Iso: Tile-Kante != 64/32 Pixel")
    if (Dir.fromDelta(1f, 0.2f) != Dir.SE || Dir.fromDelta(-0.1f, -1f) != Dir.NE) fail("Dir.fromDelta falsch")

    if (errors.isEmpty()) {
        println("ALLES OK")
    } else {
        println("FEHLER (${errors.size}):")
        errors.take(50).forEach { println("  - $it") }
        exitProcess(1)
    }
}
