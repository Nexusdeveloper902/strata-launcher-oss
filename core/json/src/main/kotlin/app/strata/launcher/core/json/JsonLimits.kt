package app.strata.launcher.core.json

/**
 * Limits enforced *while* parsing (THEME_SYSTEM §8.1 stage 3). Exceeding any of them rejects the input.
 * The defaults are the theme limits; other formats (backup, bundle manifest) may pass their own.
 */
data class JsonLimits(
    /** Maximum input size in bytes (stage 1). */
    val maxBytes: Int = 256 * 1024,
    /** Nesting depth; the root container is depth 1. */
    val maxDepth: Int = 16,
    val maxKeysPerObject: Int = 128,
    val maxArrayItems: Int = 256,
    /** All values, containers included. */
    val maxValues: Int = 2048,
    /** Decoded length of any string (keys included), in UTF-16 units. */
    val maxStringLength: Int = 4096,
    /** Length of a number literal in characters. */
    val maxNumberLength: Int = 32,
) {
    companion object {
        val THEME = JsonLimits()

        /** Bundle manifest: same parsing limits, 16 KiB (THEME_SYSTEM §10.3.2). */
        val BUNDLE_MANIFEST = JsonLimits(maxBytes = 16 * 1024)
    }
}
