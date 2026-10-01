package bayern.kickner.ruinborn.client.ui

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.utils.I18NBundle
import java.util.Locale

/** All texts are in German in `assets/i18n/strings.properties` (concept section 12). */
object Texts {
    private var bundle: I18NBundle? = null

    fun load() {
        I18NBundle.setExceptionOnMissingKey(false)
        // Simple placeholders {0}. Numbers are formatted with Fmt beforehand, apostrophes stay unchanged.
        I18NBundle.setSimpleFormatter(true)
        bundle = I18NBundle.createBundle(Gdx.files.internal("i18n/strings"), Locale.GERMAN, "UTF-8")
    }

    /** Text for [key]. Placeholders {0}, {1} ... are replaced by [args]. If the key is missing, the key itself is shown. */
    fun tr(key: String, vararg args: Any): String {
        val b = bundle ?: return key
        return if (args.isEmpty()) b.get(key) else b.format(key, *args)
    }

    /** Name of an enum value, e.g. `enum(BuildingType.HQ)` → key `BuildingType.HQ`. */
    fun enum(e: Enum<*>): String = tr(e::class.simpleName + "." + e.name)
}

fun tr(key: String, vararg args: Any) = Texts.tr(key, *args)
fun Enum<*>.label(): String = Texts.enum(this)
