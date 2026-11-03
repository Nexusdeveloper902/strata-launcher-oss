package app.strata.launcher.feature.home

/**
 * Hardware keyboard on home and list (INTERACTION_SPEC §5): a printable key opens search with that
 * character (FR-SEARCH-01, FR-A11Y-04). Pure, so the Compose handler and the activity fallback agree.
 */
object HomeKeys {
    /**
     * The text a key press types into search, or null when it is not a plain printable character
     * (control, whitespace, or any Ctrl/Alt/Meta chord). Digits are typed too (INTERACTION_SPEC §5).
     */
    fun printable(
        codePoint: Int,
        ctrl: Boolean,
        alt: Boolean,
        meta: Boolean,
    ): String? {
        if (ctrl || alt || meta) return null
        if (codePoint <= 0 || !Character.isValidCodePoint(codePoint)) return null
        if (Character.isISOControl(codePoint) || Character.isWhitespace(codePoint)) return null
        if (Character.getType(codePoint) == Character.UNASSIGNED.toInt()) return null
        return String(Character.toChars(codePoint))
    }
}
