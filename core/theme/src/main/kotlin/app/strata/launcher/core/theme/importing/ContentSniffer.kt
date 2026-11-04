package app.strata.launcher.core.theme.importing

/** What an import source contains, by content (THEME_SYSTEM §3): never by MIME type or file name. */
enum class ContentKind { JSON, ZIP, UNKNOWN }

/**
 * Content sniffing of THEME_SYSTEM §3: the first non-whitespace byte `{` (after an optional UTF-8 BOM) means a
 * JSON theme; the bytes `50 4B 03 04` mean a zip bundle. Everything else is not a Strata theme.
 */
object ContentSniffer {
    fun sniff(bytes: ByteArray): ContentKind {
        if (bytes.size >= ZIP_MAGIC.size && ZIP_MAGIC.indices.all { bytes[it] == ZIP_MAGIC[it] }) return ContentKind.ZIP
        var i = 0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            i = 3
        }
        while (i < bytes.size && bytes[i].toInt().toChar() in WHITESPACE) i++
        return if (i < bytes.size && bytes[i] == '{'.code.toByte()) ContentKind.JSON else ContentKind.UNKNOWN
    }

    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    /** RFC 8259 insignificant whitespace. */
    private val WHITESPACE = charArrayOf(' ', '\t', '\n', '\r')
}
