package app.strata.launcher.core.theme.store

import app.strata.launcher.core.theme.StrataDefault
import app.strata.launcher.core.theme.resolve.ResolvedTheme
import kotlinx.coroutines.flow.StateFlow

/**
 * The active theme, resolved for both system dark states, so a dark-mode flip re-renders in the same frame
 * without waiting for a resolution (THEME_SYSTEM §5.1 `system` mode, T-THEME-05). For modes that do not
 * follow the system (`light`, `dark`, `wallpaper`) both values are equal.
 */
data class ThemePair(val light: ResolvedTheme, val dark: ResolvedTheme) {
    fun forSystemDark(systemDark: Boolean): ResolvedTheme = if (systemDark) dark else light

    companion object {
        val DEFAULT = ThemePair(StrataDefault.resolved(systemDark = false), StrataDefault.resolved(systemDark = true))
    }
}

/** Why the default (or last-known-good) theme is shown instead of the active one (THEME_SYSTEM §12 step 2). */
enum class ThemeBannerKind {
    /** The active theme could not be loaded; last-known-good or Strata Default is shown. */
    LOAD_FAILED,

    /** The theme crash-loop guard (§12 step 4) stopped the active theme. */
    CRASH_GUARD,

    /** Process-level safe mode (ARCHITECTURE §10): Strata Default, widgets not inflated. */
    SAFE_MODE,
}

data class ThemeBanner(
    val kind: ThemeBannerKind,
    /** Plain-text reason for [Details]; never contains file contents. */
    val details: String,
)

/** One row of the theme library (THEME_SYSTEM §11, FR-THEME-27). */
data class ThemeSummary(
    val id: String,
    val name: String,
    val builtIn: Boolean,
    /** Stored but not applicable (`needsAppUpdate` or "Caused a problem — not applied"). */
    val applicable: Boolean,
    /** Built-ins only: hidden from the picker (§11.3). */
    val hidden: Boolean = false,
    val needsAppUpdate: Boolean = false,
    val causedProblem: Boolean = false,
    /** Stored static contrast status (§7.3), null when unknown. */
    val contrast: app.strata.launcher.core.theme.contrast.ContrastStatus? = null,
)

/**
 * What the UI renders: the resolved pair plus the boot outcome. [loaded] is false only before the first
 * boot finished (the first frame then uses [ThemePair.DEFAULT], §12 step 1).
 */
data class ThemeState(
    val themes: ThemePair = ThemePair.DEFAULT,
    val activeId: String = StrataDefault.ID,
    val loaded: Boolean = false,
    val banner: ThemeBanner? = null,
    /** Bumped on every apply; the UI reports the first frame drawn with it through [ThemeRepository.onFirstFrame]. */
    val applyGeneration: Int = 0,
    /** True while the crash-loop guard's `applyInProgress` is set and waits for [ThemeRepository.onFirstFrame]. */
    val awaitingFirstFrame: Boolean = false,
    val safeMode: Boolean = false,
    /** A substitute is rendered (boot fallback or failed resolution): never promoted to last-known-good. */
    val fallback: Boolean = false,
)

/**
 * Owner of the theme library and the active theme (ARCHITECTURE §4: `StateFlow<ResolvedTheme>`; THEME_SYSTEM
 * §11, §12). Every method is main-safe: work runs on the repository's own serial dispatcher. Implemented in
 * `:core:data`; faked in `:testing:fakes`.
 */
interface ThemeRepository {
    val state: StateFlow<ThemeState>

    /** Boots the library once per process (§12 steps 1–5); later calls do nothing. */
    fun start()

    /** Makes [id] active (arms the crash-loop guard) and re-renders without a restart. */
    fun apply(id: String)

    /**
     * The first frame with [generation] was drawn: clears the crash-loop flag (§12 step 4). The first call of the
     * process also starts the 10 s timer after which the start counts as good ([onBootSucceeded]).
     */
    fun onFirstFrame(generation: Int)

    /** The start is known good (10 s after the first frame, ARCHITECTURE §10). */
    fun onBootSucceeded()

    /** Rendered ≥ 10 s and reached `onStop`: copy the active theme to last-known-good (§12 step 3). */
    fun onStableRender()

    fun dismissBanner()

    /** Built-in and stored themes, built-ins first. */
    suspend fun library(): List<ThemeSummary>
}
