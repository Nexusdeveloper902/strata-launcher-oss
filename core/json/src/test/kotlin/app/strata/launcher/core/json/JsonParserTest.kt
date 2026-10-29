package app.strata.launcher.core.json

import app.strata.launcher.core.common.Outcome
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

class JsonParserTest {
    private fun ok(text: String): JsonValue = (JsonParser.parse(text.toByteArray()) as Outcome.Ok).value

    private fun err(bytes: ByteArray, limits: JsonLimits = JsonLimits.THEME): JsonError =
        (JsonParser.parse(bytes, limits) as Outcome.Err).error

    private fun err(text: String): JsonError = err(text.toByteArray())

    @Test
    fun parsesAllValueKindsInOrder() {
        val v = ok("""{"b":1,"a":[true,false,null,"x\u00e9\n",-0.5e+3],"c":{}}""") as JsonObject
        assertThat(v.keys).containsExactly("b", "a", "c").inOrder()
        val a = v["a"] as JsonArray
        assertThat(a[3]).isEqualTo(JsonString("xé\n"))
        assertThat((a[4] as JsonNumber).toDouble()).isEqualTo(-500.0)
    }

    @Test
    fun numbersCompareByValue() {
        assertThat(ok("1.0")).isEqualTo(ok("1"))
        assertThat(ok("1e0")).isEqualTo(ok("1.00"))
        assertThat(ok("1e0").hashCode()).isEqualTo(ok("1.00").hashCode())
        assertThat(ok("0.0")).isEqualTo(ok("-0"))
        assertThat((ok("32.0") as JsonNumber).isIntegral).isTrue()
        assertThat((ok("32.5") as JsonNumber).isIntegral).isFalse()
    }

    @Test
    fun objectEqualityIgnoresOrder() {
        assertThat(ok("""{"a":1,"b":2}""")).isEqualTo(ok("""{"b":2,"a":1}"""))
    }

    @Test
    fun rejectsSyntaxThatStrictRfc8259Forbids() {
        val bad =
            listOf(
                "{\"a\":1,}", "[1,]", "// c\n{}", "/* c */{}", "{\"a\":NaN}", "{\"a\":Infinity}",
                "{'a':1}", "{\"a\":01}", "{\"a\":+1}", "{\"a\":1.}", "{\"a\":.5}", "{\"a\":\"\t\"}",
                "{\"a\":\"\\x\"}", "{\"a\":\"\\ud800\"}", "{} {}", "", "   ", "{\"a\" 1}", "tru", "[1 2]",
            )
        for (b in bad) {
            val e = err(b)
            assertThat(e).isInstanceOf(JsonError.Syntax::class.java)
        }
    }

    @Test
    fun reportsLineAndColumn() {
        val e = err("{\n  \"a\": 1,\n  \"b\": x\n}") as JsonError.Syntax
        assertThat(e.line).isEqualTo(3)
        assertThat(e.column).isEqualTo(8)
    }

    @Test
    fun rejectsDuplicateKeys() {
        val e = err("{\"a\":1,\n\"a\":2}")
        assertThat(e).isEqualTo(JsonError.DuplicateKey(2, 1))
    }

    @Test
    fun toleratesLeadingBomOnly() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertThat(JsonParser.parse(bom + "{}".toByteArray())).isEqualTo(Outcome.Ok(JsonObject.EMPTY))
        assertThat(err(bom + bom + "{}".toByteArray())).isInstanceOf(JsonError.Syntax::class.java)
    }

    @Test
    fun rejectsInvalidUtf8() {
        val bytes = "{\"a\":\"".toByteArray() + byteArrayOf(0xC3.toByte(), 0x28) + "\"}".toByteArray()
        assertThat(err(bytes)).isEqualTo(JsonError.InvalidEncoding(6))
        // overlong encoding of '/'
        assertThat(err(byteArrayOf(0x22, 0xC0.toByte(), 0xAF.toByte(), 0x22))).isInstanceOf(JsonError.InvalidEncoding::class.java)
    }

    @Test
    fun enforcesEveryLimitAtTheBoundary() {
        val l = JsonLimits.THEME
        // size
        val big = ByteArray(l.maxBytes + 1) { ' '.code.toByte() }.also { it[0] = '0'.code.toByte() }
        assertThat(err(big)).isEqualTo(JsonError.TooLarge(l.maxBytes))
        val exact = ByteArray(l.maxBytes) { ' '.code.toByte() }.also { it[0] = '0'.code.toByte() }
        assertThat(JsonParser.parse(exact)).isInstanceOf(Outcome.Ok::class.java)
        // depth
        assertThat(JsonParser.parse(("[".repeat(16) + "]".repeat(16)).toByteArray())).isInstanceOf(Outcome.Ok::class.java)
        assertThat((err("[".repeat(17) + "]".repeat(17)) as JsonError.LimitExceeded).limit).isEqualTo(JsonError.Limit.DEPTH)
        // keys
        fun obj(n: Int) = (0 until n).joinToString(",", "{", "}") { "\"k$it\":0" }
        assertThat(JsonParser.parse(obj(128).toByteArray())).isInstanceOf(Outcome.Ok::class.java)
        assertThat((err(obj(129)) as JsonError.LimitExceeded).limit).isEqualTo(JsonError.Limit.KEYS_PER_OBJECT)
        // array items
        fun arr(n: Int) = (0 until n).joinToString(",", "[", "]") { "0" }
        assertThat(JsonParser.parse(arr(256).toByteArray())).isInstanceOf(Outcome.Ok::class.java)
        assertThat((err(arr(257)) as JsonError.LimitExceeded).limit).isEqualTo(JsonError.Limit.ARRAY_ITEMS)
        // total values: 1 root object + 8 arrays + 8 * 254 numbers = 2041; add keys to reach the limit
        fun values(n: Int): String {
            // root object with arrays of up to 255 numbers; count = 1 + arrays + numbers
            val sb = StringBuilder("{")
            var remaining = n - 1
            var k = 0
            while (remaining > 0) {
                val items = minOf(255, remaining - 1)
                if (k > 0) sb.append(',')
                sb.append("\"a$k\":").append((0 until items).joinToString(",", "[", "]") { "0" })
                remaining -= items + 1
                k++
            }
            return sb.append('}').toString()
        }
        assertThat(JsonParser.parse(values(2048).toByteArray())).isInstanceOf(Outcome.Ok::class.java)
        assertThat((err(values(2049)) as JsonError.LimitExceeded).limit).isEqualTo(JsonError.Limit.VALUES)
        // string length (UTF-16 units, after unescaping)
        assertThat(JsonParser.parse(("\"" + "a".repeat(4096) + "\"").toByteArray())).isInstanceOf(Outcome.Ok::class.java)
        assertThat((err("\"" + "a".repeat(4097) + "\"") as JsonError.LimitExceeded).limit).isEqualTo(JsonError.Limit.STRING_LENGTH)
        assertThat((err("{\"" + "k".repeat(4097) + "\":1}") as JsonError.LimitExceeded).limit).isEqualTo(JsonError.Limit.STRING_LENGTH)
        // number literal length
        assertThat(JsonParser.parse("1".repeat(32).toByteArray())).isInstanceOf(Outcome.Ok::class.java)
        assertThat((err("1".repeat(33)) as JsonError.LimitExceeded).limit).isEqualTo(JsonError.Limit.NUMBER_LENGTH)
    }

    @Test
    fun readCappedStopsAfterLimitPlusOne() {
        var served = 0
        val endless =
            object : java.io.InputStream() {
                override fun read(): Int = 0.also { served++ }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    served += len
                    return len
                }
            }
        assertThat(JsonParser.readCapped(endless, 1000)).isEqualTo(Outcome.Err(JsonError.TooLarge(1000)))
        assertThat(served).isEqualTo(1001)
    }

    @Test
    fun writerRoundTripsAndFormats() {
        val src = """{"b":[1,2.50,{"x":"q\"\\\u0001"}],"a":{},"c":[],"d":-1e-7}"""
        val v = ok(src)
        val pretty = JsonWriter.pretty(v)
        assertThat(pretty).isEqualTo(
            """
            |{
            |  "b": [
            |    1,
            |    2.50,
            |    {
            |      "x": "q\"\\\u0001"
            |    }
            |  ],
            |  "a": {},
            |  "c": [],
            |  "d": -1e-7
            |}
            |
            """.trimMargin(),
        )
        assertThat(ok(pretty)).isEqualTo(v)
        assertThat(ok(JsonWriter.compact(v))).isEqualTo(v)
    }

    @Test
    fun numberFactory() {
        assertThat(JsonNumber.of(2.0).raw).isEqualTo("2")
        assertThat(JsonNumber.of(0.95).raw).isEqualTo("0.95")
        assertThat(JsonNumber.parse(JsonNumber.of(1e-7).raw).toDouble()).isEqualTo(1e-7)
        assertThat(JsonNumber.parse(JsonNumber.of(1e300).raw).toDouble()).isEqualTo(1e300)
    }

    /** Random byte and text mutations never throw (the fuzz corpus of T-THEME-19 relies on this). */
    @Test
    fun neverThrowsOnGarbage() {
        val rnd = Random(42)
        val alphabet = "{}[]:,\"\\ntfrue0123456789.-+eE \t\r\nabu"
        repeat(20_000) {
            val len = rnd.nextInt(0, 64)
            val s = String(CharArray(len) { alphabet[rnd.nextInt(alphabet.length)] })
            JsonParser.parse(s.toByteArray())
            JsonParser.parse(rnd.nextBytes(len))
        }
    }
}
