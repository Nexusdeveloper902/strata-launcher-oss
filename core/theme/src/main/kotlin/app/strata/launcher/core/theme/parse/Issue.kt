package app.strata.launcher.core.theme.parse

import app.strata.launcher.core.json.JsonObject
import app.strata.launcher.core.theme.model.ThemeDocument

/** THEME_SYSTEM §8.1 severities. */
enum class Severity {
    /** File rejected. */
    ERROR,

    /** A value was clamped, defaulted, regenerated, truncated or cleaned. */
    FIXED,

    /** Rendered with a fallback (missing font/image/pack, unknown key, seed missing, low contrast). */
    WARNING,
    INFO,
}

/** The **On invalid** letter of THEME_SYSTEM §4 that produced an issue ([NONE] for non-field issues). */
enum class Policy { CLAMP, DEFAULT, GENERATE, TRUNCATE, REJECT, NONE }

/**
 * Stable machine codes. The UI maps them to localised text (§8.2); [Issue.message] is an English
 * developer message that never contains more than 64 characters of file content.
 */
enum class IssueCode(val severity: Severity, val policy: Policy) {
    // Rejections (stages 1-4)
    TOO_LARGE(Severity.ERROR, Policy.REJECT),
    INVALID_ENCODING(Severity.ERROR, Policy.REJECT),
    MALFORMED_JSON(Severity.ERROR, Policy.REJECT),
    DUPLICATE_KEY(Severity.ERROR, Policy.REJECT),
    LIMIT_EXCEEDED(Severity.ERROR, Policy.REJECT),
    NOT_A_THEME(Severity.ERROR, Policy.REJECT),
    NEWER_VERSION(Severity.ERROR, Policy.REJECT),
    MIGRATION_FAILED(Severity.ERROR, Policy.REJECT),

    // Field policies (stage 5)

    /** C: number outside the allowed range. */
    CLAMPED(Severity.FIXED, Policy.CLAMP),

    /** C: non-integral number for an integer field, or a weight not a multiple of 100. */
    ROUNDED(Severity.FIXED, Policy.CLAMP),

    /** D: wrong JSON type (including a group that is not an object). */
    WRONG_TYPE(Severity.FIXED, Policy.DEFAULT),

    /** D: right type, value not allowed (bad colour, pattern mismatch, out-of-range minor, …). */
    INVALID_VALUE(Severity.FIXED, Policy.DEFAULT),

    /** D: enum string this version does not know; the original is preserved (a newer minor may define it). */
    UNKNOWN_ENUM(Severity.FIXED, Policy.DEFAULT),

    /** D: an invalid array item (e.g. in `lockedRoles`) was dropped. */
    ITEM_DROPPED(Severity.FIXED, Policy.DEFAULT),

    /** G: missing or malformed `id` / `name` replaced. */
    GENERATED(Severity.FIXED, Policy.GENERATE),

    /** T: string longer than its limit. */
    TRUNCATED(Severity.FIXED, Policy.TRUNCATE),

    /** Control characters removed from `name`/`author`/`description` (§4.1: issue `fixed`). */
    CONTROL_CHARS_REMOVED(Severity.FIXED, Policy.NONE),

    // Warnings
    UNKNOWN_KEY(Severity.WARNING, Policy.NONE),
    SEED_MISSING(Severity.WARNING, Policy.NONE),
    FONT_MISSING(Severity.WARNING, Policy.NONE),
    BUNDLED_FONT_UNKNOWN(Severity.WARNING, Policy.NONE),
    IMAGE_MISSING(Severity.WARNING, Policy.NONE),
    ICON_PACK_MISSING(Severity.WARNING, Policy.NONE),
    LOW_CONTRAST(Severity.WARNING, Policy.NONE),

    // Info

    /** `schemaMinor` above the app's: unknown keys/values are kept ("newer settings kept", §10.5). */
    NEWER_MINOR(Severity.INFO, Policy.NONE),
    MIGRATED(Severity.INFO, Policy.NONE),
}

/** `Issue(path, severity, code, message)` of THEME_SYSTEM §8.1. [path] is a JSON Pointer (`""` = root). */
data class Issue(
    val path: String,
    val code: IssueCode,
    val message: String,
    val line: Int? = null,
    val column: Int? = null,
) {
    val severity: Severity get() = code.severity
    val policy: Policy get() = code.policy
}

/** Why a file was rejected (only the **R** cases of §4 and stages 1–4 of §8.1). */
sealed interface ThemeError {
    val issue: Issue

    /** "The file is too large." */
    data class TooLarge(override val issue: Issue) : ThemeError

    /** "The file is damaged (line L, column C)." — encoding, syntax, duplicate keys, parse limits. */
    data class Damaged(override val issue: Issue) : ThemeError

    /** "This file isn't a Strata theme." */
    data class NotATheme(override val issue: Issue) : ThemeError

    /** "This theme was made with a newer version of Strata Launcher. Update the app to use it." */
    data class NewerVersion(override val issue: Issue, val schemaVersion: Long) : ThemeError
}

/**
 * Result of stages 1–5: the typed document, the tree to store/write (unknown members and **D** originals
 * preserved; G and T fixes applied), and every issue found.
 */
data class ParsedTheme(
    val document: ThemeDocument,
    val tree: JsonObject,
    val issues: List<Issue>,
    /** Source `schemaVersion` when a migration ran, else null (THEME_SYSTEM §10.6). */
    val migratedFrom: Int? = null,
) {
    /** True when strict JSON Schema validation would also pass: no issue above [Severity.INFO]. */
    val isStrictlyValid: Boolean get() = issues.none { it.severity != Severity.INFO }
}
