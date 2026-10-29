package app.strata.launcher.testing.fixtures

/**
 * The malformed-file part of the `THEMES` corpus (TEST_PLAN T-THEME-43), generated so that boundary
 * sizes are exact and no 256 KiB file is committed. Each entry is a complete file.
 */
object MalformedThemeCorpus {
    private const val ID = "11111111-2222-4333-8444-555555555555"
    private const val HEAD = "{\"schemaVersion\":1,\"id\":\"$ID\",\"name\":\"Corpus\""

    enum class Expect { TOO_LARGE, LIMIT, DUPLICATE_KEY, SYNTAX, ENCODING, OK }

    data class Entry(val name: String, val bytes: ByteArray, val expect: Expect) {
        override fun toString(): String = name
    }

    private fun theme(extra: String): String = "$HEAD,$extra}"

    /** A valid theme padded with spaces to exactly [size] bytes. */
    fun paddedTo(size: Int): ByteArray {
        val base = theme("\"x-pad\":0").toByteArray()
        require(size >= base.size)
        return base.copyOf(size).also { for (i in base.size until size) it[i] = ' '.code.toByte() }
    }

    fun entries(): List<Entry> =
        listOf(
            Entry("256KiB", paddedTo(256 * 1024), Expect.OK),
            Entry("256KiB+1", paddedTo(256 * 1024 + 1), Expect.TOO_LARGE),
            Entry("depth16", theme("\"x-deep\":" + "[".repeat(15) + "]".repeat(15)).toByteArray(), Expect.OK),
            Entry("depth17", theme("\"x-deep\":" + "[".repeat(16) + "]".repeat(16)).toByteArray(), Expect.LIMIT),
            Entry("129keys", theme("\"x-k\":" + (0 until 129).joinToString(",", "{", "}") { "\"k$it\":0" }).toByteArray(), Expect.LIMIT),
            Entry("257items", theme("\"x-a\":" + (0 until 257).joinToString(",", "[", "]") { "0" }).toByteArray(), Expect.LIMIT),
            Entry("2049values", theme(valuesTo(2049)).toByteArray(), Expect.LIMIT),
            Entry("2048values", theme(valuesTo(2048)).toByteArray(), Expect.OK),
            Entry("4097string", theme("\"x-s\":\"" + "s".repeat(4097) + "\"").toByteArray(), Expect.LIMIT),
            Entry("33charNumber", theme("\"x-n\":" + "1".repeat(33)).toByteArray(), Expect.LIMIT),
            Entry("duplicateKey", "{\"schemaVersion\":1,\n\"name\":\"a\",\n\"name\":\"b\",\"id\":\"$ID\"}".toByteArray(), Expect.DUPLICATE_KEY),
            Entry("lineComment", "{\n// comment\n\"schemaVersion\":1}".toByteArray(), Expect.SYNTAX),
            Entry("blockComment", "{/* c */\"schemaVersion\":1}".toByteArray(), Expect.SYNTAX),
            Entry("trailingComma", theme("\"x-a\":[1,2,]").toByteArray(), Expect.SYNTAX),
            Entry("trailingCommaObject", "$HEAD,}".toByteArray(), Expect.SYNTAX),
            Entry("NaN", theme("\"wallpaper\":{\"dim\":NaN}").toByteArray(), Expect.SYNTAX),
            Entry("Infinity", theme("\"wallpaper\":{\"dim\":-Infinity}").toByteArray(), Expect.SYNTAX),
            Entry("invalidUtf8", HEAD.toByteArray() + ",\"author\":\"".toByteArray() + byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "\"}".toByteArray(), Expect.ENCODING),
            Entry("truncatedUtf8", HEAD.toByteArray() + ",\"author\":\"".toByteArray() + byteArrayOf(0xE2.toByte(), 0x82.toByte()) + "\"}".toByteArray(), Expect.ENCODING),
            Entry("bom", byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + theme("\"author\":\"BOM\"").toByteArray(), Expect.OK),
            Entry("garbageAfter", (theme("\"author\":\"x\"") + "xx").toByteArray(), Expect.SYNTAX),
            Entry("empty", ByteArray(0), Expect.SYNTAX),
        )

    /** `"x-v":[...]` members that bring the document to exactly [n] values (root object + 3 strings + 1 number counted). */
    private fun valuesTo(n: Int): String {
        var remaining = n - 4 // root, schemaVersion, id, name
        val parts = ArrayList<String>()
        var k = 0
        while (remaining > 0) {
            val items = minOf(255, remaining - 1)
            parts += "\"x-v$k\":" + (0 until items).joinToString(",", "[", "]") { "0" }
            remaining -= items + 1
            k++
        }
        return parts.joinToString(",")
    }
}
