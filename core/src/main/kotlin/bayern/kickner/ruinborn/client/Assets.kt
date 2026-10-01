package bayern.kickner.ruinborn.client

import bayern.kickner.ruinborn.client.render.gfx.GameSprites
import bayern.kickner.ruinborn.client.render.gfx.IconSprite
import bayern.kickner.ruinborn.client.ui.Texts
import bayern.kickner.ruinborn.client.ui.Theme
import bayern.kickner.ruinborn.shared.model.Plot
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.utils.Disposable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PlotLayout(val plot: Plot, val x: Int, val y: Int, val size: Int)

@Serializable
data class BaseLayout(val comment: String = "", val width: Int, val height: Int, val plots: List<PlotLayout>)

private val layoutJson = Json { ignoreUnknownKeys = true }

/** All graphics, fonts, texts and the base layout. */
class Assets : Disposable {
    val atlas = TextureAtlas(Gdx.files.internal("atlas/game.atlas"))
    val sprites = GameSprites(atlas)
    val fonts: Map<String, BitmapFont> = listOf("noto-16", "noto-20", "noto-28", "noto-bold-20", "noto-bold-28").associateWith { name ->
        BitmapFont(Gdx.files.internal("fonts/$name.fnt")).apply {
            regions.forEach { it.texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear) }
            setUseIntegerPositions(false)
            data.markupEnabled = true
        }
    }
    val theme = Theme(fonts)
    val layout: BaseLayout = layoutJson.decodeFromString(BaseLayout.serializer(), Gdx.files.internal("base_layout.json").readString("UTF-8"))

    init {
        Texts.load()
    }

    fun icon(i: IconSprite): TextureRegion = sprites.frame(i.def)
    fun iconDrawable(i: IconSprite) = TextureRegionDrawable(icon(i))

    override fun dispose() {
        atlas.dispose()
        fonts.values.forEach { it.dispose() }
        theme.dispose()
    }
}
