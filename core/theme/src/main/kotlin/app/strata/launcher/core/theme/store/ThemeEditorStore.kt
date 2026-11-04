package app.strata.launcher.core.theme.store

import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.json.JsonObject
import app.strata.launcher.core.theme.editor.EditorDraft
import app.strata.launcher.core.theme.model.WallpaperImage
import app.strata.launcher.core.theme.resolve.PlatformInputs
import app.strata.launcher.core.theme.resolve.ThemeGlobals

/** What the editor opens (THEME_SYSTEM §13). */
data class EditTarget(
    /** The id the session saves to: the stored theme's own id, or a new id for a copy or a new theme. */
    val sessionId: String,
    /** The theme the session started from (itself, the preset of a copy), or null for a new theme. */
    val sourceId: String?,
    /** The tree to start editing from (unknown members preserved). */
    val tree: JsonObject,
    /** The stored version, or null while nothing with [sessionId] is stored yet (copy, new theme). */
    val saved: JsonObject?,
)

/** The platform inputs and global settings the preview resolves against (the same ones the home screen uses). */
data class PreviewEnvironment(
    val inputs: PlatformInputs,
    val globals: ThemeGlobals,
)

enum class EditorSaveError {
    /** The file could not be written ("Not enough storage", §11.3). */
    NOT_ENOUGH_STORAGE,

    /** The tree did not validate (never for the editor's own edits) or the id belongs to a built-in. */
    INVALID,
}

enum class ImageSeedError {
    /** Not a PNG, JPEG or WebP, or beyond the §10.3.3 image limits. */
    UNSUPPORTED,

    /** The image did not decode. */
    UNREADABLE,
}

/** A seed colour from an image (create-from-image, FR-THEME-30) and, when asked for, the stored wallpaper image. */
data class ImageSeedResult(
    val seed: Int,
    val image: WallpaperImage?,
)

/**
 * The editor's storage (THEME_SYSTEM §13, ARCHITECTURE §4 "Editor draft"): opening themes for editing, saving, the
 * draft files `themes/drafts/<id>.json`, and create-from-image. Implemented by the theme repository (same serial
 * dispatcher and library); every method is main-safe and never throws for bad data.
 */
interface ThemeEditorStore {
    /**
     * A stored theme opens as itself; a built-in (or Strata Default) opens as an unsaved copy "Name (copy)" with
     * `basedOn` set, written only on Save (§11.3). Null when [id] does not load.
     */
    suspend fun openForEdit(id: String): EditTarget?

    /** A new, unsaved theme starting from Strata Default's values, named [name] (made unique in the library). */
    suspend fun newTheme(name: String): EditTarget

    /** The tree of any theme by id (the `basedOn` preset for "Reset to preset"), or null. */
    suspend fun themeTree(id: String): JsonObject?

    /** Writes the theme (§11.3); returns the stored tree (with timestamps). An active theme re-renders at once. */
    suspend fun saveEdited(tree: JsonObject): Outcome<JsonObject, EditorSaveError>

    suspend fun writeDraft(draft: EditorDraft): Boolean

    suspend fun readDraft(sessionId: String): EditorDraft?

    suspend fun deleteDraft(sessionId: String)

    /** Every readable draft, newest first (unreadable ones are deleted). */
    suspend fun drafts(): List<EditorDraft>

    /**
     * Decodes [bytes] (bounds first, sampled), extracts a seed colour, and with [keepAsWallpaper] stores the image
     * (re-encoded, stripped WebP, §10.3.3) in the image store, where it is kept while a draft or theme uses it.
     */
    suspend fun imageSeed(
        bytes: ByteArray,
        keepAsWallpaper: Boolean,
    ): Outcome<ImageSeedResult, ImageSeedError>

    /** The latest platform inputs and globals, or defaults before the first read. */
    fun previewEnvironment(): PreviewEnvironment
}
