package app.strata.launcher.backup

import app.strata.launcher.core.json.JsonLimits

/** Constants of the `.strata-backup.json` format (ARCHITECTURE §12, `spec/backup.schema.json`). */
object BackupFormat {
    const val FORMAT = "strata-backup"
    const val VERSION = 1
    const val MINOR = 0
    const val MIME = "application/json"
    const val SUFFIX = ".strata-backup.json"

    const val MAX_BYTES = 8 * 1024 * 1024
    const val MAX_EMBEDDED_THEMES = 200
    const val MAX_COMPONENT_LENGTH = 512

    /** ARCHITECTURE §12 limits. Keys per object are not limited there; 512 leaves room for embedded themes (128). */
    val LIMITS =
        JsonLimits(
            maxBytes = MAX_BYTES,
            maxDepth = 24,
            maxKeysPerObject = 512,
            maxArrayItems = 4096,
            maxValues = 200_000,
            maxStringLength = 4096,
            maxNumberLength = 32,
        )

    const val PROFILE_MAIN = "main"
    const val PROFILE_WORK = "work"

    /** ARCHITECTURE §12.1 `component` (also widget `provider`). */
    val COMPONENT = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*/[A-Za-z0-9_.$]+$")

    /** Android package names (icon pack of an override). */
    val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    /** Resource entry names (icon pack drawable). */
    val DRAWABLE = Regex("^[A-Za-z0-9_]{1,128}$")

    /** Theme ids (THEME_SYSTEM §4: a UUID). */
    val THEME_ID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    val DAY = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")

    fun isComponent(s: String): Boolean = s.length <= MAX_COMPONENT_LENGTH && COMPONENT.matches(s)

    fun fileName(date: java.time.LocalDate): String = "strata-$date$SUFFIX"
}
