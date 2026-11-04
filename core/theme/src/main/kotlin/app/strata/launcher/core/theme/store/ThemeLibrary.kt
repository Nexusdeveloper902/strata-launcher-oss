package app.strata.launcher.core.theme.store

import app.strata.launcher.core.common.Clock
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.json.JsonObject
import app.strata.launcher.core.json.JsonString
import app.strata.launcher.core.theme.StrataDefault
import app.strata.launcher.core.theme.contrast.Contrast
import app.strata.launcher.core.theme.contrast.ContrastStatus
import app.strata.launcher.core.theme.editor.EditorDraft
import app.strata.launcher.core.theme.importing.BundleCodec
import app.strata.launcher.core.theme.importing.FontCheck
import app.strata.launcher.core.theme.importing.FontFormat
import app.strata.launcher.core.theme.importing.ImageCheck
import app.strata.launcher.core.theme.importing.ImportCandidate
import app.strata.launcher.core.theme.importing.LibrarySnapshot
import app.strata.launcher.core.theme.importing.ThemeFont
import app.strata.launcher.core.theme.importing.ThemeImage
import app.strata.launcher.core.theme.importing.ThemeImporter
import app.strata.launcher.core.theme.model.ImageMediaType
import app.strata.launcher.core.theme.model.ThemeDocument
import app.strata.launcher.core.theme.parse.ParsedTheme
import app.strata.launcher.core.theme.parse.ThemeError
import app.strata.launcher.core.theme.parse.ThemeParser
import app.strata.launcher.core.theme.write.ThemeWriter
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Result of [ThemeLibrary.exportBundle]. */
sealed interface BundleExport {
    class Ok(val bytes: ByteArray) : BundleExport

    data object NotFound : BundleExport

    /** Over the §10.3.3 limits: it could not be imported back. */
    data object TooLarge : BundleExport
}

/** Where the theme of this start came from (THEME_SYSTEM §12). */
enum class BootSource {
    ACTIVE,
    LAST_KNOWN_GOOD,
    STRATA_DEFAULT,

    /** Process-level safe mode (ARCHITECTURE §10 `boot_attempts`) forced Strata Default. */
    SAFE_MODE,

    /** The theme crash-loop guard (§12 step 4) forced Strata Default. */
    CRASH_GUARD,
}

data class BootResult(
    val document: ThemeDocument,
    val source: BootSource,
    /** Show "Your theme couldn't be loaded, so the default theme is shown. [Details] [Choose theme]". */
    val showBanner: Boolean,
    /** Why the active theme was not used, for [Details]. */
    val problem: ThemeError? = null,
    /** Theme marked "Caused a problem — not applied" during this start, if any. */
    val offenderId: String? = null,
)

/**
 * Theme persistence, index reconciliation, last-known-good, crash-loop guard and migration safety copies
 * (THEME_SYSTEM §10.6, §11, §12). Pure Kotlin over [ThemeFileStore]/[ThemeIndexStore]; `ThemeRepository`
 * in `:core:data` wraps it with AtomicFile/DataStore, dispatchers and the `StateFlow<ResolvedTheme>`.
 *
 * Not thread-safe: the repository serialises calls on one dispatcher. No method throws for bad data or
 * I/O errors on reads; [save] propagates write failures ("Not enough storage") with existing data intact.
 */
class ThemeLibrary(
    private val files: ThemeFileStore,
    private val indexStore: ThemeIndexStore,
    private val builtIns: BuiltInThemeSource,
    private val clock: Clock,
    private val parser: ThemeParser = ThemeParser(),
) {
    private var index: ThemeIndex = ThemeIndex()

    /** Original bytes and version of themes loaded through a migration, for the `.bak` on first rewrite. */
    private val migratedOriginals = HashMap<String, Pair<Int, ByteArray>>()

    /** True when this start found the index missing or corrupt although themes exist (the active pointer is lost). */
    private var indexWasLost = false

    /** `.bak` files that existed when this process booted; deleted by [onBootSucceeded]. */
    private var baksAtBoot: List<String> = emptyList()

    val currentIndex: ThemeIndex get() = index

    /**
     * Versions replaced by an import, kept for Undo until the process ends (§10.4). Their fonts and images count as
     * referenced, so garbage collection never strips media an Undo would bring back. Only the latest replaced version
     * is kept per id; an edit or a delete of the theme drops it.
     */
    private val replacedVersions = HashMap<String, ByteArray>()

    /**
     * Media hashes that someone outside the library still needs (prepared imports waiting for confirmation). Garbage
     * collection treats them as referenced.
     */
    var pinnedMedia: () -> Set<String> = { emptySet() }

    // ---- boot -------------------------------------------------------------------------------------

    /**
     * §12 steps 1–5. [safeMode] is the process-level decision of ARCHITECTURE §10, checked before the
     * theme guard; in safe mode this only marks the offender.
     */
    fun boot(safeMode: Boolean = false): BootResult {
        index = loadAndReconcileIndex()
        baksAtBoot = safeList(USER_DIR).filter { it.endsWith(".bak") }

        var offender: String? = null
        if (index.applyInProgress) {
            val now = clock.nowMillis()
            val inWindow = now - index.applyStartedAt in 0..CRASH_WINDOW_MS
            val count = if (inWindow) index.applyCrashCount + 1 else 1
            if (safeMode || count >= 2) {
                offender = index.activeThemeId
                index =
                    markProblem(
                        index,
                        offender,
                    ).copy(applyInProgress = false, applyCrashCount = 0, activeThemeId = StrataDefault.ID)
            } else {
                index =
                    index.copy(applyCrashCount = count, applyStartedAt = if (inWindow) index.applyStartedAt else now)
            }
            persistIndex()
        }
        if (safeMode) {
            return BootResult(
                StrataDefault.document,
                BootSource.SAFE_MODE,
                showBanner = true,
                offenderId = offender,
            )
        }
        if (offender !=
            null
        ) {
            return BootResult(
                StrataDefault.document,
                BootSource.CRASH_GUARD,
                showBanner = true,
                offenderId = offender,
            )
        }

        if (indexWasLost) {
            // The active pointer was in the lost index: last-known-good is the best guess (§12 step 2).
            val lkg = readParsed(LKG)
            if (lkg is Outcome.Ok) {
                index = index.copy(activeThemeId = lkg.value.document.id, lastKnownGoodId = lkg.value.document.id)
                persistIndex()
                return BootResult(
                    lkg.value.document,
                    BootSource.LAST_KNOWN_GOOD,
                    showBanner = true,
                    problem = INDEX_LOST,
                )
            }
            return BootResult(
                StrataDefault.document,
                BootSource.STRATA_DEFAULT,
                showBanner = true,
                problem = INDEX_LOST,
            )
        }
        val activeId = index.activeThemeId
        if (activeId.isEmpty() || activeId.equals(StrataDefault.ID, ignoreCase = true)) {
            return BootResult(StrataDefault.document, BootSource.STRATA_DEFAULT, showBanner = false)
        }
        val active = loadTheme(activeId)
        if (active is Outcome.Ok) return BootResult(active.value.document, BootSource.ACTIVE, showBanner = false)
        val problem = (active as Outcome.Err).error

        val lkg = readParsed(LKG)
        if (lkg is Outcome.Ok) {
            return BootResult(
                lkg.value.document,
                BootSource.LAST_KNOWN_GOOD,
                showBanner = true,
                problem = problem,
            )
        }
        return BootResult(StrataDefault.document, BootSource.STRATA_DEFAULT, showBanner = true, problem = problem)
    }

    /** First frame drawn with the applied theme: clears the crash-loop flag (§12 step 4). */
    fun onFirstFrame() {
        if (index.applyInProgress || index.applyCrashCount != 0) {
            index = index.copy(applyInProgress = false, applyCrashCount = 0)
            persistIndex()
        }
    }

    /**
     * The start is known good (ARCHITECTURE §10: 10 s after the first frame). Deletes the `.bak` copies of
     * migrated files that were rewritten before this start (§10.6).
     */
    fun onBootSucceeded() {
        for (bak in baksAtBoot) {
            val id = bak.substringBefore(".v")
            val current = readParsed(userFile(id))
            if (current is Outcome.Ok && current.value.migratedFrom == null) safeDelete("$USER_DIR/$bak")
        }
        baksAtBoot = emptyList()
    }

    /** Applied, rendered 10 s and survived to `onStop` (§12 step 3): copy the active theme to last-known-good. */
    fun onStableRender() {
        val id = index.activeThemeId
        val bytes =
            when {
                id.isEmpty() || id.equals(StrataDefault.ID, ignoreCase = true) -> ThemeWriter.write(StrataDefault.tree)
                else -> rawBytes(id) ?: return
            }
        if (parser.parse(bytes) !is Outcome.Ok) return
        files.write(LKG, bytes)
        index = index.copy(lastKnownGoodId = id.ifEmpty { StrataDefault.ID })
        persistIndex()
    }

    // ---- library operations -----------------------------------------------------------------------

    /** Makes [id] active and arms the crash-loop guard; the caller then renders and calls [onFirstFrame]. */
    fun apply(id: String) {
        val now = clock.nowMillis()
        index = index.copy(activeThemeId = id, applyInProgress = true, applyStartedAt = now, applyCrashCount = 0)
        index.entry(id)?.let { index = index.withEntry(it.copy(lastAppliedAt = now, causedProblem = false)) }
        persistIndex()
    }

    /** Loads any stored or built-in theme by id (Strata Default included). */
    fun loadTheme(id: String): Outcome<ParsedTheme, ThemeError> {
        if (id.equals(StrataDefault.ID, ignoreCase = true)) return parser.validate(StrataDefault.tree)
        val entry = index.entry(id)
        if (entry != null && (entry.needsAppUpdate || entry.causedProblem)) return Outcome.Err(problemError(entry))
        builtInName(id)?.let { name -> return parseBytes(builtIns.read(name)) }
        val bytes = rawBytes(id)
        val result = parseBytes(bytes)
        val from = (result as? Outcome.Ok)?.value?.migratedFrom
        if (from != null && bytes != null) migratedOriginals[id.lowercase()] = from to bytes
        return result
    }

    /**
     * Writes a user theme (THEME_SYSTEM §11.3): file first, then index. Stamps `modifiedAt` (and
     * `createdAt` when absent). Before the first rewrite of a migrated file, keeps the original as
     * `<id>.v<N>.bak` (§10.6). Built-in ids are never written: callers create a copy first.
     */
    fun save(
        tree: JsonObject,
        origin: ThemeOrigin = ThemeOrigin.USER,
    ): ParsedTheme {
        val parsed = (parser.validate(tree) as? Outcome.Ok)?.value ?: error("save() needs a valid theme tree")
        val id = parsed.document.id
        require(
            builtInName(id) == null && !id.equals(StrataDefault.ID, ignoreCase = true),
        ) { "built-in themes are immutable" }
        val stamp = rfc3339(clock.nowMillis())
        var t = parsed.tree.with("modifiedAt", JsonString(stamp))
        if (t["createdAt"] == null) t = t.with("createdAt", JsonString(stamp))
        val bytes = ThemeWriter.write(t)
        // An edit (or a new import) supersedes a pending Replace undo of this id.
        replacedVersions.remove(id.lowercase())

        migratedOriginals.remove(id.lowercase())?.let { (version, original) ->
            val bak = "$USER_DIR/${id.lowercase()}.v$version.bak"
            if (files.read(bak) == null) files.write(bak, original)
        }
        files.write(userFile(id), bytes)
        val now = clock.nowMillis()
        val old = index.entry(id)
        index =
            index.withEntry(
                ThemeIndexEntry(
                    id = id,
                    name = parsed.document.name,
                    origin = old?.origin ?: origin,
                    fileSha256 = sha256(bytes),
                    createdAt = old?.createdAt ?: now,
                    modifiedAt = now,
                    lastAppliedAt = old?.lastAppliedAt ?: 0,
                    contrastStatus = contrastStatus(parsed.document),
                ),
            )
        persistIndex()
        return (parser.parse(bytes) as Outcome.Ok).value
    }

    /** Deletes a user theme; the active theme first falls back to last-known-good or Strata Default (§11.3). */
    fun delete(id: String) {
        require(builtInName(id) == null) { "built-in themes cannot be deleted" }
        if (index.activeThemeId.equals(id, ignoreCase = true)) {
            val lkg =
                index.lastKnownGoodId.takeIf { it.isNotEmpty() && !it.equals(id, ignoreCase = true) }
                    ?: StrataDefault.ID
            index = index.copy(activeThemeId = lkg, applyInProgress = false)
        }
        safeDelete(userFile(id))
        replacedVersions.remove(id.lowercase())
        index = index.copy(entries = index.entries.filterNot { it.id.equals(id, ignoreCase = true) })
        persistIndex()
        collectGarbage()
    }

    /** Moves an invalid stored file to `themes/quarantine/` (never deleted silently, §11.4). */
    fun quarantine(name: String) {
        try {
            // An earlier quarantined file of the same name is kept: the new one gets a numbered suffix.
            val taken = safeList(QUARANTINE_DIR).toSet()
            val target =
                generateSequence(0) { it + 1 }
                    .map {
                        if (it ==
                            0
                        ) {
                            name
                        } else {
                            "$name.$it"
                        }
                    }.first { it !in taken }
            files.move("$USER_DIR/$name", "$QUARANTINE_DIR/$target")
        } catch (e: java.io.IOException) {
            // Left in place; the next reconciliation tries again.
        }
    }

    // ---- M5: library management, import, export (THEME_SYSTEM §10, §11.3) ------------------------

    /** What the importer needs for conflict planning (§10.4). */
    fun snapshot(): LibrarySnapshot {
        val stored =
            index.entries
                .filter { it.origin != ThemeOrigin.BUILTIN && builtInName(it.id) == null && !it.needsAppUpdate }
                .mapNotNull { e ->
                    val tree = (readParsed(userFile(e.id)) as? Outcome.Ok)?.value?.tree ?: return@mapNotNull null
                    e.id.lowercase() to ThemeImporter.canonical(tree)
                }.toMap()
        val names = summaries().associate { it.id.lowercase() to it.name }
        return LibrarySnapshot(builtInIds = builtInIds.keys + StrataDefault.ID.lowercase(), stored, names)
    }

    /**
     * Commits an import (§10.4 step 3): media first (content-addressed, written only if absent), then the theme file
     * and the index. Returns the saved theme and, when an existing theme was replaced, its previous bytes; those stay
     * available to [restore] (and their media protected from garbage collection) until the process ends.
     */
    fun commitImport(
        candidate: ImportCandidate,
        tree: JsonObject,
    ): Pair<ParsedTheme, ByteArray?> {
        for (f in candidate.fonts) writeMediaIfAbsent("$FONTS_DIR/${f.storeName}", f.bytes)
        candidate.image?.let { writeMediaIfAbsent("$IMAGES_DIR/${it.sha256}.$IMAGE_EXT", it.bytes) }
        val id = (tree["id"] as? JsonString)?.value ?: candidate.parsed.document.id
        val previous = if (index.entry(id) != null) rawBytes(id) else null
        val saved = save(tree, ThemeOrigin.IMPORTED)
        if (previous != null) {
            // The latest replaced version is the one Undo brings back.
            replacedVersions[id.lowercase()] = previous
            collectGarbage()
        }
        return saved to previous
    }

    /** True while an Undo of a Replace is available for [id]. */
    fun canRestore(id: String): Boolean = id.lowercase() in replacedVersions

    /**
     * Undo of an import's Replace (§10.4): writes back the version [commitImport] replaced. Returns false when there is
     * nothing to restore (already restored, edited or deleted since).
     */
    fun restore(id: String): Boolean {
        val bytes = replacedVersions[id.lowercase()] ?: return false
        val parsed = (parser.parse(bytes) as? Outcome.Ok)?.value ?: return false
        files.write(userFile(id), bytes)
        replacedVersions.remove(id.lowercase())
        val old = index.entry(id)
        index =
            index.withEntry(
                (old ?: ThemeIndexEntry(id, parsed.document.name, ThemeOrigin.USER)).copy(
                    name = parsed.document.name,
                    fileSha256 = sha256(bytes),
                    contrastStatus = contrastStatus(parsed.document),
                ),
            )
        persistIndex()
        // The import's media may now be unreferenced.
        collectGarbage()
        return true
    }

    /**
     * Duplicates any theme (built-in, Strata Default or stored) as a new user theme "Name (copy)" with `basedOn` = the
     * source id (§11.3). Editing a built-in uses this on the first change. Returns the new id.
     */
    fun duplicate(
        id: String,
        newId: String,
    ): String? {
        val tree = copyTree(id, newId) ?: return null
        return save(tree, ThemeOrigin.USER).document.id
    }

    /**
     * The tree of a copy of [id] ("Name (copy)", new [newId], `basedOn` = the source), not written. The editor saves it
     * on Save only, so opening a built-in for editing leaves the library unchanged (§11.3).
     */
    fun copyTree(
        id: String,
        newId: String,
    ): JsonObject? {
        val source = (loadTheme(id) as? Outcome.Ok)?.value ?: return null
        val name = uniqueName(copyName(source.document.name))
        return source.tree
            .without("createdAt")
            .without("modifiedAt")
            .with("id", JsonString(newId))
            .with("name", JsonString(name))
            .with("basedOn", JsonString(source.document.id))
    }

    /** [name], numbered if any theme already has it (case-insensitive, built-ins included). */
    fun uniqueName(
        name: String,
    ): String = ThemeImporter.uniqueName(name, summaries().map { it.name.lowercase() }.toSet())

    // ---- M5 part 2b: editor drafts and images (THEME_SYSTEM §13) ------------------------------------

    /** Writes a draft atomically (`themes/drafts/<sessionId>.json`). */
    fun writeDraft(draft: EditorDraft) {
        files.write(EditorDraft.fileName(draft.sessionId), EditorDraft.encode(draft))
    }

    /** The draft of [sessionId], or null; an unreadable draft is deleted. */
    fun readDraft(sessionId: String): EditorDraft? {
        if (!EditorDraft.isValidId(sessionId)) return null
        val name = EditorDraft.fileName(sessionId)
        val bytes = safeReadMedia(name, EditorDraft.LIMITS.maxBytes) ?: return null
        val draft = EditorDraft.decode(bytes)?.takeIf { it.sessionId.equals(sessionId, ignoreCase = true) }
        if (draft == null || parser.validate(draft.tree) !is Outcome.Ok) {
            safeDelete(name)
            return null
        }
        return draft
    }

    fun deleteDraft(sessionId: String) {
        if (EditorDraft.isValidId(sessionId)) safeDelete(EditorDraft.fileName(sessionId))
    }

    /** Every readable draft, newest first. */
    fun drafts(): List<EditorDraft> =
        safeList(EditorDraft.DIR)
            .filter { it.endsWith(EditorDraft.SUFFIX) }
            .mapNotNull { readDraft(it.removeSuffix(EditorDraft.SUFFIX)) }
            .sortedByDescending { it.savedAtMillis }

    /** Stores a prepared (stripped WebP) wallpaper image in the content-addressed image store. */
    fun storeImage(image: ThemeImage) {
        writeMediaIfAbsent("$IMAGES_DIR/${image.sha256}.$IMAGE_EXT", image.bytes)
    }

    /** Hides or shows a built-in in the picker (§11.3). Stored themes are deleted instead. */
    fun setHidden(
        id: String,
        hidden: Boolean,
    ) {
        if (builtInName(id) == null) return
        val e = index.entry(id) ?: ThemeIndexEntry(id = id.lowercase(), name = id, origin = ThemeOrigin.BUILTIN)
        index = index.withEntry(e.copy(hidden = hidden))
        persistIndex()
    }

    /**
     * Before deleting the active theme (§11.3): makes last-known-good (if it is another, loadable theme) or Strata
     * Default active. Returns the new active id, or null when [id] was not active.
     */
    fun switchAwayFrom(id: String): String? {
        if (!index.activeThemeId.equals(id, ignoreCase = true)) return null
        val lkg = index.lastKnownGoodId
        val target =
            if (lkg.isNotEmpty() && !lkg.equals(id, ignoreCase = true) && loadTheme(lkg) is Outcome.Ok) {
                lkg
            } else {
                StrataDefault.ID
            }
        apply(target)
        return target
    }

    /** The stored document for export (§10.2): preserved unknowns, `modifiedAt` refreshed in the copy. */
    fun exportJson(id: String): ByteArray? {
        val parsed = (loadTheme(id) as? Outcome.Ok)?.value ?: return null
        return ThemeWriter.write(parsed.tree.with("modifiedAt", JsonString(rfc3339(clock.nowMillis()))))
    }

    /** True when the theme references user fonts or an image that the stores hold ("Include fonts and wallpaper"). */
    fun hasBundleMedia(id: String): Boolean {
        val parsed = (loadTheme(id) as? Outcome.Ok)?.value ?: return false
        return storedFonts(parsed).isNotEmpty() || storedImage(parsed) != null
    }

    /**
     * A `.strata-theme.zip` with the theme, its user fonts and its wallpaper image (§10.3). Fails with
     * [BundleExport.TooLarge] when the result would break the import limits of §10.3.3 (it could not be imported back).
     */
    fun exportBundle(id: String): BundleExport {
        val parsed = (loadTheme(id) as? Outcome.Ok)?.value ?: return BundleExport.NotFound
        val theme = exportJson(id) ?: return BundleExport.NotFound
        val fonts = storedFonts(parsed)
        val image = storedImage(parsed)
        val total = theme.size.toLong() + fonts.sumOf { it.bytes.size.toLong() } + (image?.bytes?.size ?: 0)
        if (fonts.size > BundleCodec.MAX_FONTS || total > BundleCodec.LIMITS.maxTotalBytes) return BundleExport.TooLarge
        val zip = BundleCodec.write(theme, fonts, image)
        if (zip.size > BundleCodec.LIMITS.maxArchiveBytes) return BundleExport.TooLarge
        return BundleExport.Ok(zip)
    }

    /** The theme's wallpaper image from the image store, for the system hand-off (FR-THEME-34). */
    fun wallpaperImage(id: String): ThemeImage? {
        val parsed = (loadTheme(id) as? Outcome.Ok)?.value ?: return null
        return storedImage(parsed)
    }

    private fun storedFonts(parsed: ParsedTheme): List<ThemeFont> {
        val present = safeList(FONTS_DIR)
        return ThemeImporter.userFonts(parsed).mapNotNull { sha ->
            val name = present.firstOrNull { it.substringBefore('.') == sha } ?: return@mapNotNull null
            val bytes = safeReadMedia("$FONTS_DIR/$name", FontCheck.MAX_BYTES) ?: return@mapNotNull null
            val format = if (name.endsWith(".otf")) FontFormat.OTF else FontFormat.TTF
            ThemeFont(sha, format, bytes).takeIf { BundleCodec.sha256(bytes) == sha }
        }
    }

    private fun storedImage(parsed: ParsedTheme): ThemeImage? {
        val sha =
            parsed.document.wallpaper.image
                ?.sha256 ?: return null
        val bytes = safeReadMedia("$IMAGES_DIR/$sha.$IMAGE_EXT", ImageCheck.MAX_BYTES) ?: return null
        if (BundleCodec.sha256(bytes) != sha) return null
        return ThemeImage(sha, ImageMediaType.WEBP, bytes)
    }

    /**
     * Deletes fonts and images that no stored theme (nor last-known-good) references (§10.3.3: the stores are shared
     * and content-addressed; orphans are garbage-collected).
     */
    fun collectGarbage() {
        val referencedFonts = HashSet<String>()
        val referencedImages = HashSet<String>()
        val trees =
            index.entries
                .filter { it.origin != ThemeOrigin.BUILTIN }
                .map { userFile(it.id) } + LKG

        fun reference(parsed: ParsedTheme) {
            referencedFonts += ThemeImporter.userFonts(parsed)
            parsed.document.wallpaper.image
                ?.sha256
                ?.let { referencedImages += it }
        }
        for (name in trees) (readParsed(name) as? Outcome.Ok)?.value?.let(::reference)
        // Drafts (§13) may reference an image stored by create-from-image before the theme is first saved.
        for (d in drafts()) (parser.validate(d.tree) as? Outcome.Ok)?.value?.let(::reference)
        // Versions an Undo may restore, and media of prepared imports, are still in use.
        for (bytes in replacedVersions.values) (parser.parse(bytes) as? Outcome.Ok)?.value?.let(::reference)
        val pinned = pinnedMedia()
        referencedFonts += pinned
        referencedImages += pinned
        // Stored files that need an app update are kept untouched with everything they might reference.
        if (index.entries.any { it.needsAppUpdate }) return
        for (f in safeList(FONTS_DIR)) if (f.substringBefore('.') !in referencedFonts) safeDelete("$FONTS_DIR/$f")
        for (f in safeList(IMAGES_DIR)) if (f.substringBefore('.') !in referencedImages) safeDelete("$IMAGES_DIR/$f")
    }

    private fun writeMediaIfAbsent(
        name: String,
        bytes: ByteArray,
    ) {
        if (name.substringAfter('/') in safeList(name.substringBefore('/'))) return
        files.write(name, bytes)
    }

    private fun safeReadMedia(
        name: String,
        cap: Int,
    ): ByteArray? =
        try {
            files.readMedia(name, cap + 1)?.takeIf { it.size <= cap }
        } catch (e: Exception) {
            null
        }

    private fun copyName(name: String): String = "$name (copy)"

    // ---- index ------------------------------------------------------------------------------------

    /**
     * Reads the index; if it is missing or corrupt, rebuilds it by scanning `themes/user/` (§11.2). Then
     * reconciles it with the directory: entries without a file are dropped, files without an entry are
     * parsed and added (newer major → `needsAppUpdate`; rejected → quarantined). Built-in entries are kept.
     */
    private fun loadAndReconcileIndex(): ThemeIndex {
        val stored =
            try {
                indexStore.read()
            } catch (e: java.io.IOException) {
                null
            }
        var idx = stored ?: ThemeIndex()
        val names = safeList(USER_DIR).filter { it.endsWith(SUFFIX) }
        indexWasLost = stored == null && (names.isNotEmpty() || safeRead(LKG) != null)
        val fileIds = names.map { it.removeSuffix(SUFFIX).lowercase() }.toSet()
        val kept = idx.entries.filter { it.origin == ThemeOrigin.BUILTIN || it.id.lowercase() in fileIds }
        var changed = stored == null || kept.size != idx.entries.size
        idx = idx.copy(entries = kept)
        for (name in names) {
            val id = name.removeSuffix(SUFFIX)
            if (idx.entry(id) != null) continue
            changed = true
            val bytes = safeRead("$USER_DIR/$name")
            when (val r = parseBytes(bytes)) {
                is Outcome.Ok -> {
                    idx =
                        idx.withEntry(
                            ThemeIndexEntry(
                                id = id,
                                name = r.value.document.name,
                                origin = ThemeOrigin.USER,
                                fileSha256 = bytes?.let(::sha256) ?: "",
                                contrastStatus = contrastStatus(r.value.document),
                            ),
                        )
                }

                is Outcome.Err -> {
                    if (r.error is ThemeError.NewerVersion) {
                        idx =
                            idx.withEntry(
                                ThemeIndexEntry(id = id, name = id, origin = ThemeOrigin.USER, needsAppUpdate = true),
                            )
                    } else {
                        quarantine(name)
                    }
                }
            }
        }
        if (changed) {
            index = idx
            persistIndex()
        }
        return idx
    }

    private fun markProblem(
        idx: ThemeIndex,
        id: String,
    ): ThemeIndex = idx.entry(id)?.let { idx.withEntry(it.copy(causedProblem = true)) } ?: idx

    private fun persistIndex() {
        try {
            indexStore.write(index)
        } catch (e: java.io.IOException) {
            // The index is a cache; the next start rebuilds it from the files.
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------

    /** Built-in preset ids → (asset name, display name), read once (assets are immutable for the process lifetime). */
    private val builtInIds: Map<String, Pair<String, String>> by lazy {
        val names =
            try {
                builtIns.names()
            } catch (e: Exception) {
                emptyList()
            }
        names
            .mapNotNull { name ->
                val r =
                    parseBytes(
                        try {
                            builtIns.read(name)
                        } catch (e: Exception) {
                            null
                        },
                    )
                (r as? Outcome.Ok)
                    ?.value
                    ?.document
                    ?.let { it.id.lowercase() to (name to it.name) }
            }.toMap()
    }

    fun isBuiltIn(id: String): Boolean = builtInName(id) != null

    /**
     * Strata Default, then the built-in presets (asset order), then stored themes by name. Stored themes that
     * need an app update or caused a problem are listed as not applicable.
     */
    fun summaries(): List<ThemeSummary> {
        val builtIn =
            builtInIds.entries
                .sortedBy { it.value.first }
                .map { (id, v) ->
                    ThemeSummary(
                        id,
                        v.second,
                        builtIn = true,
                        applicable = true,
                        hidden = index.entry(id)?.hidden == true,
                        contrast = ContrastStatus.AA_ANY_WALLPAPER,
                    )
                }
        val stored =
            index.entries
                .filter { it.origin != ThemeOrigin.BUILTIN && builtInName(it.id) == null }
                .sortedBy { it.name.lowercase() }
                .map {
                    ThemeSummary(
                        it.id,
                        it.name,
                        builtIn = false,
                        applicable =
                            !it.needsAppUpdate && !it.causedProblem,
                        needsAppUpdate = it.needsAppUpdate,
                        causedProblem = it.causedProblem,
                        contrast = it.contrastStatus,
                    )
                }
        val default =
            ThemeSummary(
                StrataDefault.ID,
                StrataDefault.NAME,
                builtIn = true,
                applicable = true,
                hidden = index.entry(StrataDefault.ID)?.hidden == true,
                contrast = ContrastStatus.AA_ANY_WALLPAPER,
            )
        return listOf(default) +
            builtIn + stored
    }

    private fun builtInName(id: String): String? = builtInIds[id.lowercase()]?.first

    private fun rawBytes(id: String): ByteArray? = builtInName(id)?.let { builtIns.read(it) } ?: safeRead(userFile(id))

    private fun readParsed(name: String): Outcome<ParsedTheme, ThemeError> = parseBytes(safeRead(name))

    private fun parseBytes(bytes: ByteArray?): Outcome<ParsedTheme, ThemeError> =
        if (bytes == null) Outcome.Err(MISSING) else parser.parse(bytes)

    private fun safeRead(name: String): ByteArray? =
        try {
            files.read(name)
        } catch (e: Exception) {
            null
        }

    private fun safeList(dir: String): List<String> =
        try {
            files.list(dir)
        } catch (e: Exception) {
            emptyList()
        }

    private fun safeDelete(name: String) {
        try {
            files.delete(name)
        } catch (e: Exception) {
            // Ignored: a leftover file is reconciled or retried later.
        }
    }

    private fun problemError(entry: ThemeIndexEntry): ThemeError =
        if (entry.needsAppUpdate) {
            ThemeError.NewerVersion(
                app.strata.launcher.core.theme.parse.Issue(
                    "",
                    app.strata.launcher.core.theme.parse.IssueCode.NEWER_VERSION,
                    "Needs app update",
                ),
                0,
            )
        } else {
            ThemeError.NotATheme(
                app.strata.launcher.core.theme.parse.Issue(
                    "",
                    app.strata.launcher.core.theme.parse.IssueCode.NOT_A_THEME,
                    "Caused a problem; not applied",
                ),
            )
        }

    private fun contrastStatus(doc: ThemeDocument): ContrastStatus =
        if (Contrast
                .staticCheck(
                    doc,
                ).all { it.passes }
        ) {
            ContrastStatus.AA_ANY_WALLPAPER
        } else {
            ContrastStatus.LOW_CONTRAST
        }

    companion object {
        const val USER_DIR = "user"
        const val FONTS_DIR = "fonts"
        const val IMAGES_DIR = "images"

        /** Stored images are always WebP (§10.3.3, §11.1). */
        const val IMAGE_EXT = "webp"
        const val QUARANTINE_DIR = "quarantine"
        const val SUFFIX = ".strata-theme.json"
        const val LKG = "lkg.strata-theme.json"
        const val CRASH_WINDOW_MS = 5 * 60 * 1000L

        fun userFile(id: String): String = "$USER_DIR/${id.lowercase()}$SUFFIX"

        private val MISSING =
            ThemeError.NotATheme(
                app.strata.launcher.core.theme.parse.Issue(
                    "",
                    app.strata.launcher.core.theme.parse.IssueCode.NOT_A_THEME,
                    "File missing or unreadable",
                ),
            )

        private val INDEX_LOST =
            ThemeError.NotATheme(
                app.strata.launcher.core.theme.parse.Issue(
                    "",
                    app.strata.launcher.core.theme.parse.IssueCode.NOT_A_THEME,
                    "Theme index was unreadable",
                ),
            )

        private val RFC3339 = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

        fun rfc3339(millis: Long): String = RFC3339.format(Instant.ofEpochMilli(millis))

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            }
    }
}
