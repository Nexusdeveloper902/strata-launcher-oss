package app.strata.launcher.core.theme

import app.strata.launcher.core.json.JsonObject
import app.strata.launcher.core.theme.model.ThemeDocument
import app.strata.launcher.core.theme.resolve.PlatformInputs
import app.strata.launcher.core.theme.resolve.ResolvedTheme
import app.strata.launcher.core.theme.resolve.ThemeGlobals
import app.strata.launcher.core.theme.resolve.ThemeResolver
import app.strata.launcher.core.theme.write.ThemeEncoder

/**
 * Strata Default (THEME_SYSTEM §2): the built-in preset whose values equal every schema default.
 * A Kotlin constant, never parsed from a file, so it is available even when storage is unreadable.
 */
object StrataDefault {
    /** Fixed identity (chosen here; THEME_SYSTEM does not fix it). Never reuse for another theme. */
    const val ID = "a413ab93-de91-4614-8c5c-cfbe6d79231b"
    const val NAME = "Strata Default"

    val document: ThemeDocument = ThemeDocument(id = ID, name = NAME)

    /** The document as a (sparse) JSON tree, e.g. for export or as the last-known-good copy. */
    val tree: JsonObject by lazy { ThemeEncoder.encode(document) }

    private val light by lazy { ThemeResolver.resolve(document, PlatformInputs(apiLevel = 26, systemDark = false)).theme }
    private val dark by lazy { ThemeResolver.resolve(document, PlatformInputs(apiLevel = 26, systemDark = true)).theme }

    /**
     * Resolved Strata Default for the first frame (§12 step 1). Its palettes are fixed and AA on any
     * wallpaper, so only the dark flag matters for colours; other platform inputs (animator scale,
     * haptics) are applied by [ThemeResolver.resolve] when the caller has them.
     */
    fun resolved(systemDark: Boolean): ResolvedTheme = if (systemDark) dark else light

    fun resolve(
        platform: PlatformInputs,
        globals: ThemeGlobals = ThemeGlobals(),
    ): ResolvedTheme = ThemeResolver.resolve(document, platform, globals).theme
}
