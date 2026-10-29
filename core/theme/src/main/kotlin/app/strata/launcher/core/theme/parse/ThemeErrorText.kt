package app.strata.launcher.core.theme.parse

/**
 * English reference texts of THEME_SYSTEM §8.2. The app shows localised strings resources keyed by the
 * same cases; these exist for logs, tests and the JSON editor's inline issue list. No text contains file
 * content (only line/column numbers), so nothing beyond 64 characters of a file can be echoed.
 */
object ThemeErrorText {
    fun english(e: ThemeError): String =
        when (e) {
            is ThemeError.TooLarge -> {
                "The file is too large."
            }

            is ThemeError.NotATheme -> {
                "This file isn't a Strata theme."
            }

            is ThemeError.NewerVersion -> {
                "This theme was made with a newer version of Strata Launcher. Update the app to use it."
            }

            is ThemeError.Damaged -> {
                val l = e.issue.line
                val c = e.issue.column
                if (l != null && c != null) "The file is damaged (line $l, column $c)." else "The file is damaged."
            }
        }
}
