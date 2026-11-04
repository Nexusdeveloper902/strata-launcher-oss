package app.strata.launcher.core.data.theme

import android.content.res.AssetManager
import androidx.core.util.AtomicFile
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import app.strata.launcher.core.json.JsonLimits
import app.strata.launcher.core.theme.contrast.ContrastStatus
import app.strata.launcher.core.theme.store.BuiltInThemeSource
import app.strata.launcher.core.theme.store.ThemeFileStore
import app.strata.launcher.core.theme.store.ThemeIndex
import app.strata.launcher.core.theme.store.ThemeIndexEntry
import app.strata.launcher.core.theme.store.ThemeIndexStore
import app.strata.launcher.core.theme.store.ThemeOrigin
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * [ThemeFileStore] over `filesDir/themes/` (THEME_SYSTEM §11.1) with `androidx.core.util.AtomicFile`:
 * write temp → `fsync` → rename (§11.3). Blocking; callers run it on the theme dispatcher, never on main.
 * Reads are capped at the parser's limit + 1 byte, so a huge file is rejected by stage 1 without being loaded.
 */
class AtomicFileThemeFileStore(
    private val root: () -> File,
    private val maxReadBytes: Int = JsonLimits.THEME.maxBytes + 1,
) : ThemeFileStore {
    private fun file(name: String): File {
        require(name.isNotEmpty() && !name.startsWith("/") && name.split('/').none { it == ".." || it.isEmpty() }) {
            "bad theme store name"
        }
        return File(root(), name)
    }

    override fun read(name: String): ByteArray? {
        val f = file(name)
        val atomic = AtomicFile(f)
        return try {
            atomic.openRead().use { it.readCapped(maxReadBytes) }
        } catch (e: FileNotFoundException) {
            null
        }
    }

    override fun write(
        name: String,
        bytes: ByteArray,
    ) {
        val f = file(name)
        val dir = f.parentFile
        if (dir != null && !dir.isDirectory && !dir.mkdirs() &&
            !dir.isDirectory
        ) {
            throw IOException("cannot create $dir")
        }
        val atomic = AtomicFile(f)
        val out = atomic.startWrite()
        try {
            out.write(bytes)
            atomic.finishWrite(out) // fsync + rename
        } catch (e: IOException) {
            atomic.failWrite(out)
            throw e
        }
    }

    override fun delete(name: String): Boolean {
        val f = file(name)
        val existed = f.exists()
        AtomicFile(f).delete()
        return existed
    }

    override fun list(dir: String): List<String> {
        val names = file(dir).list() ?: return emptyList()
        // AtomicFile's own temp (`.new`) and legacy backup (`<name>.bak` next to a theme) are not entries.
        return names
            .filterNot { it.endsWith(NEW_SUFFIX) || it.endsWith(THEME_SUFFIX + LEGACY_BAK) }
            .filter { File(file(dir), it).isFile }
            .sorted()
    }

    override fun move(
        from: String,
        to: String,
    ) {
        val src = file(from)
        val dst = file(to)
        dst.parentFile?.let {
            if (!it.isDirectory && !it.mkdirs() &&
                !it.isDirectory
            ) {
                throw IOException("cannot create $it")
            }
        }
        if (!src.exists()) throw IOException("missing $from")
        if (!src.renameTo(dst)) throw IOException("rename failed: $from")
    }

    private companion object {
        const val NEW_SUFFIX = ".new"
        const val LEGACY_BAK = ".bak"
        const val THEME_SUFFIX = ".strata-theme.json"
    }
}

/** Reads at most [cap] bytes (callers pass the limit + 1 so "too large" stays detectable). */
internal fun InputStream.readCapped(cap: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(BUFFER)
    var total = 0
    while (total < cap) {
        val n = read(buf, 0, minOf(buf.size, cap - total))
        if (n < 0) break
        out.write(buf, 0, n)
        total += n
    }
    return out.toByteArray()
}

private const val BUFFER = 8 * 1024

/** Built-in presets from the APK's `assets/themes/` (THEME_SYSTEM §11.1, §14). Assets are read-only. */
class AssetBuiltInThemeSource(
    private val assets: AssetManager,
    private val dir: String = DIR,
) : BuiltInThemeSource {
    override fun names(): List<String> =
        assets
            .list(dir)
            .orEmpty()
            .filter { it.endsWith(SUFFIX) }
            .sorted()

    override fun read(name: String): ByteArray? {
        require('/' !in name) { "bad asset name" }
        return try {
            assets.open("$dir/$name").use { it.readCapped(JsonLimits.THEME.maxBytes + 1) }
        } catch (e: FileNotFoundException) {
            null
        }
    }

    companion object {
        const val DIR = "themes"
        const val SUFFIX = ".strata-theme.json"
    }
}

/**
 * [ThemeIndexStore] over Proto DataStore `datastore/theme_index.pb` (THEME_SYSTEM §11.2). The library is
 * synchronous and runs on the theme dispatcher, so this blocks that thread while DataStore does the I/O on
 * its own [scope] (which must not be the theme dispatcher). A missing or corrupt file reads as `null`, and
 * the library rebuilds the index from the files.
 */
class DataStoreThemeIndexStore internal constructor(
    private val dataStore: DataStore<StoredThemeIndex>,
) : ThemeIndexStore {
    override fun read(): ThemeIndex? =
        runBlocking {
            val stored =
                try {
                    dataStore.data.first()
                } catch (e: IOException) {
                    return@runBlocking null
                }
            if (stored.formatVersion == 0) null else ThemeIndexMapper.toModel(stored)
        }

    override fun write(index: ThemeIndex) {
        runBlocking { dataStore.updateData { ThemeIndexMapper.toProto(index) } }
    }

    companion object {
        const val FILE_NAME = "theme_index.pb"

        /** `filesDir/datastore/theme_index.pb`, resolved lazily on [scope]. */
        fun create(
            context: android.content.Context,
            scope: CoroutineScope,
        ): DataStoreThemeIndexStore {
            val app = context.applicationContext
            return create(scope) { app.dataStoreFile(FILE_NAME) }
        }

        fun create(
            scope: CoroutineScope,
            produceFile: () -> File,
        ): DataStoreThemeIndexStore =
            DataStoreThemeIndexStore(
                DataStoreFactory.create(
                    serializer = ThemeIndexSerializer,
                    // A corrupt index is replaced by "no index"; the library then rebuilds it (§11.2).
                    corruptionHandler = ReplaceFileCorruptionHandler { StoredThemeIndex.getDefaultInstance() },
                    scope = scope,
                    produceFile = produceFile,
                ),
            )
    }
}

internal object ThemeIndexSerializer : Serializer<StoredThemeIndex> {
    override val defaultValue: StoredThemeIndex = StoredThemeIndex.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): StoredThemeIndex =
        try {
            StoredThemeIndex.parseFrom(input)
        } catch (e: InvalidProtocolBufferException) {
            throw CorruptionException("theme_index.pb is not a valid index", e)
        }

    override suspend fun writeTo(
        t: StoredThemeIndex,
        output: OutputStream,
    ) = t.writeTo(output)
}

internal object ThemeIndexMapper {
    const val FORMAT_VERSION = 1

    fun toModel(p: StoredThemeIndex): ThemeIndex =
        ThemeIndex(
            entries = p.entriesList.map(::entry),
            activeThemeId = p.activeThemeId,
            lastKnownGoodId = p.lastKnownGoodId,
            applyInProgress = p.applyInProgress,
            applyStartedAt = p.applyStartedAt,
            applyCrashCount = p.applyCrashCount.coerceAtLeast(0),
        )

    fun toProto(i: ThemeIndex): StoredThemeIndex =
        StoredThemeIndex
            .newBuilder()
            .addAllEntries(i.entries.map(::entry))
            .setActiveThemeId(i.activeThemeId)
            .setLastKnownGoodId(i.lastKnownGoodId)
            .setApplyInProgress(i.applyInProgress)
            .setApplyStartedAt(i.applyStartedAt)
            .setApplyCrashCount(i.applyCrashCount)
            .setFormatVersion(FORMAT_VERSION)
            .build()

    private fun entry(e: StoredThemeEntry): ThemeIndexEntry =
        ThemeIndexEntry(
            id = e.id,
            name = e.name,
            origin =
                when (e.origin) {
                    StoredOrigin.STORED_ORIGIN_BUILTIN -> ThemeOrigin.BUILTIN
                    StoredOrigin.STORED_ORIGIN_IMPORTED -> ThemeOrigin.IMPORTED
                    else -> ThemeOrigin.USER
                },
            fileSha256 = e.fileSha256,
            createdAt = e.createdAt,
            modifiedAt = e.modifiedAt,
            lastAppliedAt = e.lastAppliedAt,
            contrastStatus =
                when (e.contrastStatus) {
                    StoredContrast.STORED_CONTRAST_AA_ANY_WALLPAPER -> ContrastStatus.AA_ANY_WALLPAPER
                    StoredContrast.STORED_CONTRAST_AA_CURRENT_WALLPAPER -> ContrastStatus.AA_CURRENT_WALLPAPER
                    StoredContrast.STORED_CONTRAST_LOW -> ContrastStatus.LOW_CONTRAST
                    else -> null
                },
            needsAppUpdate = e.needsAppUpdate,
            causedProblem = e.causedProblem,
        )

    private fun entry(e: ThemeIndexEntry): StoredThemeEntry =
        StoredThemeEntry
            .newBuilder()
            .setId(e.id)
            .setName(e.name)
            .setOrigin(
                when (e.origin) {
                    ThemeOrigin.BUILTIN -> StoredOrigin.STORED_ORIGIN_BUILTIN
                    ThemeOrigin.USER -> StoredOrigin.STORED_ORIGIN_USER
                    ThemeOrigin.IMPORTED -> StoredOrigin.STORED_ORIGIN_IMPORTED
                },
            ).setFileSha256(e.fileSha256)
            .setCreatedAt(e.createdAt)
            .setModifiedAt(e.modifiedAt)
            .setLastAppliedAt(e.lastAppliedAt)
            .setContrastStatus(
                when (e.contrastStatus) {
                    ContrastStatus.AA_ANY_WALLPAPER -> StoredContrast.STORED_CONTRAST_AA_ANY_WALLPAPER
                    ContrastStatus.AA_CURRENT_WALLPAPER -> StoredContrast.STORED_CONTRAST_AA_CURRENT_WALLPAPER
                    ContrastStatus.LOW_CONTRAST -> StoredContrast.STORED_CONTRAST_LOW
                    null -> StoredContrast.STORED_CONTRAST_UNKNOWN
                },
            ).setNeedsAppUpdate(e.needsAppUpdate)
            .setCausedProblem(e.causedProblem)
            .build()
}
