package app.strata.launcher.core.json

/**
 * Why input was rejected. Positions are 1-based; `column` counts UTF-16 units from the line start.
 * No variant carries file content, so messages built from it can never echo the file (THEME_SYSTEM §8.2).
 */
sealed interface JsonError {
    /** Stage 1: more than [JsonLimits.maxBytes] bytes. */
    data class TooLarge(val limitBytes: Int) : JsonError

    /** Stage 2: not strict UTF-8. [byteOffset] is the first bad byte. */
    data class InvalidEncoding(val byteOffset: Long) : JsonError

    /** Stage 3: syntax error (comments, trailing commas, NaN, bad escapes, trailing garbage, …). */
    data class Syntax(val line: Int, val column: Int, val kind: SyntaxKind) : JsonError

    /** Stage 3: the same key twice in one object (avoids parser differentials, §9). */
    data class DuplicateKey(val line: Int, val column: Int) : JsonError

    /** Stage 3: a structural limit of [JsonLimits] was exceeded. */
    data class LimitExceeded(val line: Int, val column: Int, val limit: Limit) : JsonError

    enum class Limit { DEPTH, KEYS_PER_OBJECT, ARRAY_ITEMS, VALUES, STRING_LENGTH, NUMBER_LENGTH }

    enum class SyntaxKind {
        UNEXPECTED_END,
        UNEXPECTED_CHARACTER,
        INVALID_NUMBER,
        INVALID_ESCAPE,
        UNPAIRED_SURROGATE,
        CONTROL_CHARACTER_IN_STRING,
        TRAILING_CONTENT,
        EMPTY_INPUT,
    }
}

/** Line and column, when the error has a position. */
val JsonError.position: Pair<Int, Int>?
    get() =
        when (this) {
            is JsonError.Syntax -> line to column
            is JsonError.DuplicateKey -> line to column
            is JsonError.LimitExceeded -> line to column
            is JsonError.TooLarge, is JsonError.InvalidEncoding -> null
        }
