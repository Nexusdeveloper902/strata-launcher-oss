package app.strata.launcher.backup

import app.strata.launcher.core.json.JsonObject
import app.strata.launcher.core.model.IconOverride
import app.strata.launcher.core.model.Settings

/** `main` or `work` (ARCHITECTURE §12.1). There is no value for private space: such entries cannot exist. */
enum class BackupProfile(
    val wire: String,
) {
    MAIN(BackupFormat.PROFILE_MAIN),
    WORK(BackupFormat.PROFILE_WORK),
    ;

    companion object {
        fun of(wire: String): BackupProfile? = entries.firstOrNull { it.wire == wire }
    }
}

data class BackupApp(
    val component: String,
    val profile: BackupProfile,
    val hidden: Boolean = false,
    /** 1–60 characters, or null for the system label. */
    val label: String? = null,
    /** A pack drawable, or null for the theme's icon (`"kind": "system"` or absent). */
    val icon: IconOverride? = null,
) {
    val packageName: String get() = component.substringBefore('/')
}

data class BackupRef(
    val component: String,
    val profile: BackupProfile,
) {
    val packageName: String get() = component.substringBefore('/')
}

data class BackupWidget(
    val provider: String,
    val profile: BackupProfile,
    val heightStep: Int,
    val replaceClock: Boolean,
)

data class BackupLaunchDay(
    val component: String,
    val profile: BackupProfile,
    val day: String,
    val count: Int,
)

/** An embedded theme after the THEME_SYSTEM §8 pipeline. */
sealed interface EmbeddedTheme {
    val name: String

    /** Valid: [tree] is the validated tree (G/T fixes applied, unknown keys kept). */
    data class Valid(
        val id: String,
        override val name: String,
        val tree: JsonObject,
    ) : EmbeddedTheme

    /** A newer `schemaVersion`: restored untouched, marked "Needs app update" (THEME_SYSTEM §10.5). */
    data class Newer(
        val id: String,
        override val name: String,
        val tree: JsonObject,
    ) : EmbeddedTheme

    /** Rejected by the pipeline: listed as excluded, never written (T-BACKUP-09). */
    data class Invalid(
        override val name: String,
    ) : EmbeddedTheme
}

data class BackupThemes(
    val active: String? = null,
    val builtinRefs: List<String> = emptyList(),
    val hiddenBuiltins: Set<String> = emptySet(),
    val embedded: List<EmbeddedTheme> = emptyList(),
)

/** A decoded `.strata-backup.json` (formatVersion 1). Only valid entries are kept; see [BackupStats] for the rest. */
data class BackupDocument(
    val formatMinor: Int = BackupFormat.MINOR,
    val createdAt: String? = null,
    val appVersion: String? = null,
    val workProfile: Boolean = false,
    val launchLogIncluded: Boolean = false,
    /** Null when the file has no `settings` object (then the catalogue defaults are restored). */
    val settings: Settings? = null,
    val settingsCount: Int = 0,
    val apps: List<BackupApp> = emptyList(),
    val favorites: List<BackupRef> = emptyList(),
    /** Null when the file has no `themes` object (the library is then left as it is). */
    val themes: BackupThemes? = null,
    val widgets: List<BackupWidget> = emptyList(),
    val launchLog: List<BackupLaunchDay> = emptyList(),
)

/** What decoding skipped (ARCHITECTURE §12.1/§12.3: unknown keys of a newer minor, invalid entries). */
data class BackupStats(
    val unknownKeys: Int = 0,
    val dropped: Int = 0,
)
