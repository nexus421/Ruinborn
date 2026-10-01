package bayern.kickner.ruinborn.client

import bayern.kickner.ruinborn.client.render.SpriteMap
import bayern.kickner.ruinborn.client.render.gfx.BuildingSprite
import bayern.kickner.ruinborn.client.render.gfx.DecoSprite
import bayern.kickner.ruinborn.client.render.gfx.Dir
import bayern.kickner.ruinborn.client.render.gfx.FxSprite
import bayern.kickner.ruinborn.client.render.gfx.IconSprite
import bayern.kickner.ruinborn.client.render.gfx.SpriteDef
import bayern.kickner.ruinborn.client.render.gfx.TileSprite
import bayern.kickner.ruinborn.client.render.gfx.UnitSprite
import bayern.kickner.ruinborn.shared.model.BuildingType
import bayern.kickner.ruinborn.shared.model.Plot
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.utils.I18NBundle
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Checks the assets without OpenGL: atlas ↔ sprite catalog, fonts, texts, base layout. */
class AssetsTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "assets/atlas/game.atlas").isFile }
    private fun asset(path: String) = FileHandle(File(root, "assets/$path"))
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun atlasContainsEveryCatalogSpriteWithCorrectFramesAndSize() {
        val data = TextureAtlas.TextureAtlasData(asset("atlas/game.atlas"), asset("atlas"), false)
        val regions = data.regions.groupBy { it.name }
        val problems = mutableListOf<String>()
        fun check(region: String, frames: Int, w: Int, h: Int) {
            val list = regions[region]
            if (list == null) { problems += "$region fehlt"; return }
            if (list.size != frames) problems += "$region: ${list.size} statt $frames Frames"
            list.forEach { r -> if (r.width != w || r.height != h) problems += "$region[${r.index}]: ${r.width}x${r.height} statt ${w}x$h" }
        }
        fun check(d: SpriteDef) = check(d.region, d.frames, d.width, d.height)
        BuildingSprite.entries.forEach { check(it.def) }
        TileSprite.entries.forEach { check(it.def) }
        DecoSprite.entries.forEach { check(it.def) }
        IconSprite.entries.forEach { check(it.def) }
        FxSprite.entries.forEach { check(it.def) }
        UnitSprite.entries.forEach { u -> u.actions.forEach { (a, spec) -> Dir.entries.forEach { d -> check(u.region(a, d), spec.frames, u.width, u.height) } } }
        assertEquals(emptyList(), problems)
        assertTrue(data.pages.all { it.width <= 2048 && it.height <= 2048 }, "Seiten höchstens 2048 × 2048 (Android)")
    }

    @Test
    fun everyBuildingTypeHasASprite() {
        BuildingType.entries.forEach { assertNotNull(SpriteMap.building(it)) }
    }

    @Test
    fun fontsContainGermanCharacters() {
        listOf("noto-16", "noto-20", "noto-28", "noto-bold-20", "noto-bold-28").forEach { name ->
            val data = BitmapFont.BitmapFontData(asset("fonts/$name.fnt"), false)
            "ÄÖÜäöüß€–„“…·×•".forEach { c -> assertNotNull(data.getGlyph(c), "$name: Zeichen '$c' fehlt") }
        }
    }

    @Test
    fun baseLayoutCoversAllPlotsWithoutOverlap() {
        val layout = json.decodeFromString(BaseLayout.serializer(), asset("base_layout.json").readString("UTF-8"))
        assertEquals(Plot.entries.toSet(), layout.plots.map { it.plot }.toSet())
        val tiles = layout.plots.flatMap { p -> (0 until p.size).flatMap { dx -> (0 until p.size).map { dy -> (p.x + dx) to (p.y + dy) } } }
        assertEquals(tiles.size, tiles.toSet().size, "Plätze überlappen")
        assertTrue(tiles.all { (x, y) -> x in 1 until layout.width - 1 && y in 1 until layout.height - 1 })
    }

    @Test
    fun allTextKeysUsedInCodeExist() {
        val bundle = I18NBundle.createBundle(asset("i18n/strings"), Locale.GERMAN, "UTF-8")
        val keys = bundle.keys().toSet()
        val used = File(root, "core/src/main/kotlin").walkTopDown().filter { it.extension == "kt" }
            .flatMap { f -> Regex("""tr\("([A-Za-z0-9_.]+)"""").findAll(f.readText()).map { it.groupValues[1] } }
            .filterNot { it.endsWith(".") }.toSet()
        assertEquals(emptySet(), used - keys)
        // Dynamic keys (enum names, achievements, daily tasks)
        val dynamic = listOf(BuildingType.entries.map { "BuildingType.$it" }, Plot.entries.map { "Plot.$it" },
            bayern.kickner.ruinborn.shared.model.AchievementId.entries.map { "achievement.$it" },
            bayern.kickner.ruinborn.shared.model.DailyTask.entries.flatMap { listOf("daily.$it", "daily.desc.$it") },
            bayern.kickner.ruinborn.shared.model.ItemId.entries.map { "ItemId.$it" },
            bayern.kickner.ruinborn.shared.model.Tech.entries.map { "Tech.$it" },
            bayern.kickner.ruinborn.shared.model.BonusKind.entries.map { "BonusKind.$it" },
            bayern.kickner.ruinborn.shared.model.Cosmetic.entries.map { "Cosmetic.$it" },
            BuildingType.entries.map { "desc.$it" }).flatten()
        assertEquals(emptyList(), dynamic.filterNot { it in keys })
    }
}
