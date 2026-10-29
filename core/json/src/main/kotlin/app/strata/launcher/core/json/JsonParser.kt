package app.strata.launcher.core.json

import app.strata.launcher.core.common.Outcome
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Strict, bounded RFC 8259 parser (THEME_SYSTEM §8.1 stages 1–3, §9 "one parser").
 *
 * - UTF-8 only, malformed bytes rejected; one leading BOM tolerated and dropped.
 * - No comments, trailing commas, NaN/Infinity, single quotes, leading `+`/zeros or unpaired surrogates.
 * - Duplicate keys rejected. All [JsonLimits] enforced while parsing, so no input can make it allocate
 *   more than the limits allow.
 * - Never throws for any input: every failure is an [Outcome.Err].
 */
object JsonParser {
    fun parse(
        bytes: ByteArray,
        limits: JsonLimits = JsonLimits.THEME,
    ): Outcome<JsonValue, JsonError> {
        if (bytes.size > limits.maxBytes) return Outcome.Err(JsonError.TooLarge(limits.maxBytes))
        val text =
            when (val decoded = decodeUtf8(bytes)) {
                is Outcome.Ok -> decoded.value
                is Outcome.Err -> return decoded
            }
        return parseText(text, limits)
    }

    /** Reads at most `limits.maxBytes + 1` bytes from [input] (stage 1), then parses. Does not close [input]. */
    fun parse(
        input: InputStream,
        limits: JsonLimits = JsonLimits.THEME,
    ): Outcome<JsonValue, JsonError> =
        when (val read = readCapped(input, limits.maxBytes)) {
            is Outcome.Ok -> parse(read.value, limits)
            is Outcome.Err -> read
        }

    /** Parses already-decoded text (no BOM handling, no byte cap). */
    fun parseText(
        text: String,
        limits: JsonLimits = JsonLimits.THEME,
    ): Outcome<JsonValue, JsonError> =
        try {
            Outcome.Ok(Reader(text, limits).parseDocument())
        } catch (e: Failure) {
            Outcome.Err(e.error)
        } catch (e: StackOverflowError) {
            // Unreachable with the depth limit; kept so the parser can never crash its caller.
            Outcome.Err(JsonError.LimitExceeded(1, 1, JsonError.Limit.DEPTH))
        }

    /** Reads up to [maxBytes] + 1 bytes; more than [maxBytes] → [JsonError.TooLarge]. */
    fun readCapped(
        input: InputStream,
        maxBytes: Int,
    ): Outcome<ByteArray, JsonError> {
        val out = java.io.ByteArrayOutputStream(minOf(maxBytes + 1, 64 * 1024))
        val buf = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val want = minOf(buf.size.toLong(), maxBytes + 1L - total).toInt()
            if (want <= 0) break
            val n = input.read(buf, 0, want)
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return if (total > maxBytes) Outcome.Err(JsonError.TooLarge(maxBytes)) else Outcome.Ok(out.toByteArray())
    }

    private fun decodeUtf8(bytes: ByteArray): Outcome<String, JsonError> {
        val start =
            if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        val decoder =
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = ByteBuffer.wrap(bytes, start, bytes.size - start)
        val output = CharBuffer.allocate(bytes.size - start + 1)
        val result = decoder.decode(input, output, true)
        if (result.isError) return Outcome.Err(JsonError.InvalidEncoding(input.position().toLong()))
        val flush = decoder.flush(output)
        if (flush.isError) return Outcome.Err(JsonError.InvalidEncoding(input.position().toLong()))
        output.flip()
        return Outcome.Ok(output.toString())
    }

    private class Failure(val error: JsonError) : RuntimeException(null, null, false, false)

    private class Reader(private val s: String, private val limits: JsonLimits) {
        private var i = 0
        private var values = 0

        fun parseDocument(): JsonValue {
            skipWs()
            if (i >= s.length) throw syntax(JsonError.SyntaxKind.EMPTY_INPUT)
            val v = value(1)
            skipWs()
            if (i < s.length) throw syntax(JsonError.SyntaxKind.TRAILING_CONTENT)
            return v
        }

        private fun value(depth: Int): JsonValue {
            if (++values > limits.maxValues) throw limit(JsonError.Limit.VALUES)
            if (i >= s.length) throw syntax(JsonError.SyntaxKind.UNEXPECTED_END)
            return when (s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> JsonString(string())
                't' -> literal("true", JsonBool.TRUE)
                'f' -> literal("false", JsonBool.FALSE)
                'n' -> literal("null", JsonNull)
                '-', in '0'..'9' -> number()
                else -> throw syntax(JsonError.SyntaxKind.UNEXPECTED_CHARACTER)
            }
        }

        private fun obj(depth: Int): JsonObject {
            if (depth > limits.maxDepth) throw limit(JsonError.Limit.DEPTH)
            i++ // {
            val members = LinkedHashMap<String, JsonValue>()
            skipWs()
            if (peek() == '}') {
                i++
                return JsonObject(members)
            }
            while (true) {
                skipWs()
                if (peek() != '"') throw syntaxOrEnd()
                val keyStart = i
                val key = string()
                if (key in members) throw at(keyStart) { l, c -> JsonError.DuplicateKey(l, c) }
                if (members.size + 1 > limits.maxKeysPerObject) {
                    throw at(keyStart) { l, c -> JsonError.LimitExceeded(l, c, JsonError.Limit.KEYS_PER_OBJECT) }
                }
                skipWs()
                if (peek() != ':') throw syntaxOrEnd()
                i++
                skipWs()
                members[key] = value(depth + 1)
                skipWs()
                when (peek()) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return JsonObject(members)
                    }
                    else -> throw syntaxOrEnd()
                }
            }
        }

        private fun arr(depth: Int): JsonArray {
            if (depth > limits.maxDepth) throw limit(JsonError.Limit.DEPTH)
            i++ // [
            val items = ArrayList<JsonValue>()
            skipWs()
            if (peek() == ']') {
                i++
                return JsonArray(items)
            }
            while (true) {
                skipWs()
                if (items.size + 1 > limits.maxArrayItems) throw limit(JsonError.Limit.ARRAY_ITEMS)
                items += value(depth + 1)
                skipWs()
                when (peek()) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return JsonArray(items)
                    }
                    else -> throw syntaxOrEnd()
                }
            }
        }

        private fun string(): String = readString().also { checkSurrogates(it) }

        private fun readString(): String {
            val start = i
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw syntax(JsonError.SyntaxKind.UNEXPECTED_END)
                val ch = s[i]
                when {
                    ch == '"' -> {
                        i++
                        return sb.toString()
                    }
                    ch == '\\' -> {
                        if (i + 1 >= s.length) throw syntax(JsonError.SyntaxKind.UNEXPECTED_END)
                        val e = s[i + 1]
                        when (e) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                val cp = hex4(i + 2)
                                sb.append(cp.toChar())
                                i += 4
                            }
                            else -> throw syntax(JsonError.SyntaxKind.INVALID_ESCAPE)
                        }
                        i += 2
                    }
                    ch < ' ' -> throw syntax(JsonError.SyntaxKind.CONTROL_CHARACTER_IN_STRING)
                    else -> {
                        sb.append(ch)
                        i++
                    }
                }
                if (sb.length > limits.maxStringLength) {
                    throw at(start) { l, c -> JsonError.LimitExceeded(l, c, JsonError.Limit.STRING_LENGTH) }
                }
            }
        }

        private fun checkSurrogates(v: String) {
            var k = 0
            while (k < v.length) {
                val c = v[k]
                if (Character.isHighSurrogate(c)) {
                    if (k + 1 >= v.length || !Character.isLowSurrogate(v[k + 1])) {
                        throw syntax(JsonError.SyntaxKind.UNPAIRED_SURROGATE)
                    }
                    k += 2
                    continue
                }
                if (Character.isLowSurrogate(c)) throw syntax(JsonError.SyntaxKind.UNPAIRED_SURROGATE)
                k++
            }
        }

        private fun hex4(from: Int): Int {
            if (from + 4 > s.length) throw syntax(JsonError.SyntaxKind.UNEXPECTED_END)
            var v = 0
            for (k in from until from + 4) {
                val d = Character.digit(s[k], 16)
                if (d < 0 || s[k].code > 0x7F) throw syntax(JsonError.SyntaxKind.INVALID_ESCAPE)
                v = v * 16 + d
            }
            return v
        }

        private fun number(): JsonNumber {
            val start = i
            if (peek() == '-') i++
            when {
                peek() == '0' -> i++
                peek() in '1'..'9' -> while (peek() in '0'..'9') i++
                else -> throw syntaxOrEnd(JsonError.SyntaxKind.INVALID_NUMBER)
            }
            if (peek() == '.') {
                i++
                if (peek() !in '0'..'9') throw syntaxOrEnd(JsonError.SyntaxKind.INVALID_NUMBER)
                while (peek() in '0'..'9') i++
            }
            if (peek() == 'e' || peek() == 'E') {
                i++
                if (peek() == '+' || peek() == '-') i++
                if (peek() !in '0'..'9') throw syntaxOrEnd(JsonError.SyntaxKind.INVALID_NUMBER)
                while (peek() in '0'..'9') i++
            }
            if (i - start > limits.maxNumberLength) {
                throw at(start) { l, c -> JsonError.LimitExceeded(l, c, JsonError.Limit.NUMBER_LENGTH) }
            }
            return JsonNumber.ofLexeme(s.substring(start, i))
        }

        private fun literal(
            word: String,
            v: JsonValue,
        ): JsonValue {
            if (!s.startsWith(word, i)) throw syntaxOrEnd()
            i += word.length
            return v
        }

        private fun peek(): Char = if (i < s.length) s[i] else '\u0000'

        private fun skipWs() {
            while (i < s.length) {
                when (s[i]) {
                    ' ', '\t', '\n', '\r' -> i++
                    else -> return
                }
            }
        }

        private fun syntaxOrEnd(kind: JsonError.SyntaxKind = JsonError.SyntaxKind.UNEXPECTED_CHARACTER): Failure =
            if (i >= s.length) syntax(JsonError.SyntaxKind.UNEXPECTED_END) else syntax(kind)

        private fun syntax(kind: JsonError.SyntaxKind): Failure = at(minOf(i, s.length)) { l, c -> JsonError.Syntax(l, c, kind) }

        private fun limit(which: JsonError.Limit): Failure = at(i) { l, c -> JsonError.LimitExceeded(l, c, which) }

        private inline fun at(
            index: Int,
            make: (Int, Int) -> JsonError,
        ): Failure {
            var line = 1
            var lineStart = 0
            var k = 0
            while (k < index && k < s.length) {
                val c = s[k]
                if (c == '\n' || (c == '\r' && (k + 1 >= s.length || s[k + 1] != '\n'))) {
                    line++
                    lineStart = k + 1
                }
                k++
            }
            return Failure(make(line, index - lineStart + 1))
        }
    }
}
