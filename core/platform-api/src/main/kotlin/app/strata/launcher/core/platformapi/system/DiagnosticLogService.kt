package app.strata.launcher.core.platformapi.system

/**
 * About -> "Save diagnostic log" (ARCHITECTURE §11): the local log, redacted for handing out (no labels, queries,
 * contact data or private-space components; package names as 6-hex hashes, T-PRIV-06). Main-safe.
 */
interface DiagnosticLogService {
    /** The full text to preview and save, or null when it could not be built. */
    suspend fun build(): String?

    /** Writes [text] to the `content:` document [uri]; false on any failure. */
    suspend fun save(
        uri: String,
        text: String,
    ): Boolean

    /** File name proposed to the system "save as" screen. */
    fun suggestedFileName(): String
}
