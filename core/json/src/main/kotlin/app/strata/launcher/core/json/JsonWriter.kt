package app.strata.launcher.core.json

/**
 * Deterministic JSON writer. [pretty] produces the theme writer format of THEME_SYSTEM §3:
 * 2-space indent, LF line endings, one trailing newline, members in the tree's order.
 * Numbers are written as their stored lexeme; strings escape only what RFC 8259 requires
 * (plus DEL-free control characters), so non-ASCII text stays human-readable.
 */
object JsonWriter {
    fun pretty(value: JsonValue): String =
        StringBuilder()
            .apply {
                write(value, this, 0)
                append('\n')
            }.toString()

    fun prettyBytes(value: JsonValue): ByteArray = pretty(value).toByteArray(Charsets.UTF_8)

    fun compact(value: JsonValue): String = StringBuilder().apply { writeCompact(value, this) }.toString()

    fun quote(s: String): String = StringBuilder(s.length + 2).apply { appendQuoted(s, this) }.toString()

    private fun write(
        v: JsonValue,
        out: StringBuilder,
        level: Int,
    ) {
        when (v) {
            is JsonObject -> {
                if (v.size == 0) {
                    out.append("{}")
                    return
                }
                out.append("{\n")
                var first = true
                for ((k, child) in v.entries) {
                    if (!first) out.append(",\n")
                    first = false
                    indent(out, level + 1)
                    appendQuoted(k, out)
                    out.append(": ")
                    write(child, out, level + 1)
                }
                out.append('\n')
                indent(out, level)
                out.append('}')
            }

            is JsonArray -> {
                if (v.isEmpty()) {
                    out.append("[]")
                    return
                }
                out.append("[\n")
                v.forEachIndexed { idx, child ->
                    if (idx > 0) out.append(",\n")
                    indent(out, level + 1)
                    write(child, out, level + 1)
                }
                out.append('\n')
                indent(out, level)
                out.append(']')
            }

            else -> {
                writeCompact(v, out)
            }
        }
    }

    private fun writeCompact(
        v: JsonValue,
        out: StringBuilder,
    ) {
        when (v) {
            is JsonObject -> {
                out.append('{')
                var first = true
                for ((k, child) in v.entries) {
                    if (!first) out.append(',')
                    first = false
                    appendQuoted(k, out)
                    out.append(':')
                    writeCompact(child, out)
                }
                out.append('}')
            }

            is JsonArray -> {
                out.append('[')
                v.forEachIndexed { idx, child ->
                    if (idx > 0) out.append(',')
                    writeCompact(child, out)
                }
                out.append(']')
            }

            is JsonString -> {
                appendQuoted(v.value, out)
            }

            is JsonNumber -> {
                out.append(v.raw)
            }

            is JsonBool -> {
                out.append(if (v.value) "true" else "false")
            }

            JsonNull -> {
                out.append("null")
            }
        }
    }

    private fun indent(
        out: StringBuilder,
        level: Int,
    ) {
        repeat(level * 2) { out.append(' ') }
    }

    private fun appendQuoted(
        s: String,
        out: StringBuilder,
    ) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> {
                    out.append("\\\"")
                }

                '\\' -> {
                    out.append("\\\\")
                }

                '\n' -> {
                    out.append("\\n")
                }

                '\r' -> {
                    out.append("\\r")
                }

                '\t' -> {
                    out.append("\\t")
                }

                '\b' -> {
                    out.append("\\b")
                }

                '\u000C' -> {
                    out.append("\\f")
                }

                else -> {
                    if (c < ' ' || c == ' ' || c == ' ') {
                        out.append("\\u").append(String.format(java.util.Locale.ROOT, "%04x", c.code))
                    } else {
                        out.append(c)
                    }
                }
            }
        }
        out.append('"')
    }
}
