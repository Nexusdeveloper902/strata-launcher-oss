package app.strata.launcher.core.data.backup

import app.strata.launcher.core.data.theme.AtomicFileThemeFileStore
import app.strata.launcher.core.platformapi.backup.InstallMarker
import app.strata.launcher.core.platformapi.backup.RestoreFileStore
import java.io.File

/**
 * [RestoreFileStore] over `filesDir/restore/` with `AtomicFile` (fsync + rename): a reader sees the old or the new
 * bytes, never a mix. The directory is outside every Auto Backup include and excluded explicitly (ANDROID_PLATFORM §17).
 */
class AtomicRestoreFileStore(
    private val root: () -> File,
) : RestoreFileStore {
    private val store = AtomicFileThemeFileStore(root)

    override fun exists(name: String): Boolean {
        require(name.isNotEmpty() && '/' !in name && name != "..") { "bad restore file name" }
        // A legacy `.bak` copy is what AtomicFile reads after an interrupted old-style write; a `.new` is an unfinished
        // write that AtomicFile discards, so it does not count.
        return listOf(name, "$name.bak").any { File(root(), it).isFile }
    }

    override fun read(
        name: String,
        maxBytes: Int,
    ): ByteArray? = store.readMedia(name, maxBytes + 1)?.takeIf { it.size <= maxBytes }

    override fun write(
        name: String,
        bytes: ByteArray,
    ) = store.write(name, bytes)

    override fun delete(name: String) {
        store.delete(name)
    }
}

/**
 * `noBackupFilesDir/install_marker` (ARCHITECTURE §12.3 step 6): `no_backup` is never part of Auto Backup or device
 * transfer, so after a restore the marker is missing (or differs) while `settings.install_id` came back.
 */
class FileInstallMarker(
    private val file: () -> File,
) : InstallMarker {
    override fun read(): String? {
        val f = file()
        if (!f.isFile || f.length() > MAX_BYTES) return null
        return f.readText(Charsets.US_ASCII).trim().ifEmpty { null }
    }

    override fun write(value: String) {
        val f = file()
        AtomicRestoreFileStore { f.parentFile }.write(f.name, value.toByteArray(Charsets.US_ASCII))
    }

    companion object {
        const val FILE_NAME = "install_marker"
        private const val MAX_BYTES = 128
    }
}
