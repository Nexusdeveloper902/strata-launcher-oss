package app.strata.launcher.core.json

import java.math.BigDecimal

/**
 * Ordered JSON tree (ARCHITECTURE §2: "strict bounded RFC 8259 parser/writer with ordered tree").
 *
 * Equality is *JSON equality*: object member order is ignored and numbers compare by value
 * (`1`, `1.0` and `1e0` are equal). Use [JsonObject.keys] when order matters.
 */
sealed interface JsonValue

object JsonNull : JsonValue {
    override fun toString(): String = "null"
}

data class JsonBool(val value: Boolean) : JsonValue {
    override fun toString(): String = value.toString()

    companion object {
        val TRUE = JsonBool(true)
        val FALSE = JsonBool(false)

        fun of(value: Boolean): JsonBool = if (value) TRUE else FALSE
    }
}

data class JsonString(val value: String) : JsonValue {
    override fun toString(): String = JsonWriter.quote(value)
}

/**
 * A number kept as its source lexeme, so an untouched value is written back byte-for-byte.
 * [raw] always matches the RFC 8259 number grammar.
 */
class JsonNumber private constructor(val raw: String) : JsonValue {
    private val decimal: BigDecimal by lazy { BigDecimal(raw) }

    fun toDouble(): Double = raw.toDouble()

    /** The value as a [BigDecimal] (exact). */
    fun toBigDecimal(): BigDecimal = decimal

    /** True when the value has no fractional part (`32` and `32.0` both count, as in JSON Schema). */
    val isIntegral: Boolean
        get() = decimal.signum() == 0 || decimal.stripTrailingZeros().scale() <= 0

    override fun equals(other: Any?): Boolean = other is JsonNumber && decimal.compareTo(other.decimal) == 0

    override fun hashCode(): Int =
        if (decimal.signum() == 0) 0 else decimal.stripTrailingZeros().hashCode()

    override fun toString(): String = raw

    companion object {
        private val GRAMMAR = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

        /** Wraps a lexeme that is already known to match the grammar (parser use). */
        internal fun ofLexeme(raw: String): JsonNumber = JsonNumber(raw)

        fun parse(raw: String): JsonNumber {
            require(GRAMMAR.matches(raw)) { "Not a JSON number" }
            return JsonNumber(raw)
        }

        fun of(value: Long): JsonNumber = JsonNumber(value.toString())

        fun of(value: Int): JsonNumber = JsonNumber(value.toString())

        /** Finite doubles only. Integral values are written without a fraction (`2.0` → `2`). */
        fun of(value: Double): JsonNumber {
            require(value.isFinite()) { "JSON has no NaN or Infinity" }
            if (value == Math.rint(value) && kotlin.math.abs(value) < 1e15) {
                return JsonNumber(value.toLong().toString())
            }
            return JsonNumber(BigDecimal.valueOf(value).stripTrailingZeros().toString().replace("E+", "E"))
        }
    }
}

class JsonArray(items: List<JsonValue>) : JsonValue, List<JsonValue> by items.toList() {
    override fun equals(other: Any?): Boolean =
        other is JsonArray && size == other.size && indices.all { this[it] == other[it] }

    override fun hashCode(): Int = fold(1) { acc, v -> 31 * acc + v.hashCode() }

    override fun toString(): String = JsonWriter.compact(this)

    companion object {
        val EMPTY = JsonArray(emptyList())
    }
}

/** Object with members in insertion (source) order. Keys are unique (the parser rejects duplicates). */
class JsonObject(members: Map<String, JsonValue>) : JsonValue {
    private val map: Map<String, JsonValue> = LinkedHashMap(members)

    constructor(vararg members: Pair<String, JsonValue>) : this(linkedMapOf(*members))

    val keys: List<String> get() = map.keys.toList()
    val entries: Set<Map.Entry<String, JsonValue>> get() = map.entries
    val size: Int get() = map.size

    operator fun get(key: String): JsonValue? = map[key]

    operator fun contains(key: String): Boolean = key in map

    fun toMap(): Map<String, JsonValue> = LinkedHashMap(map)

    /** Copy with [key] set to [value]; an existing key keeps its position, a new key is appended. */
    fun with(key: String, value: JsonValue): JsonObject = JsonObject(LinkedHashMap(map).apply { put(key, value) })

    fun without(key: String): JsonObject =
        if (key !in map) this else JsonObject(LinkedHashMap(map).apply { remove(key) })

    override fun equals(other: Any?): Boolean = other is JsonObject && map == other.map

    override fun hashCode(): Int = map.hashCode()

    override fun toString(): String = JsonWriter.compact(this)

    companion object {
        val EMPTY = JsonObject(emptyMap())
    }
}
