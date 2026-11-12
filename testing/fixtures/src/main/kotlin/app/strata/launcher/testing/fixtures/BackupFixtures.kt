package app.strata.launcher.testing.fixtures

/**
 * The `BACKUPS` fixture (TEST_PLAN §0): a valid `.strata-backup.json`, a newer major, a newer minor with unknown keys,
 * a theme file, truncated JSON, a 9 MiB file, duplicate keys, invalid UTF-8, and one valid plus one malformed embedded
 * theme. Authored here; the embedded themes are the spec's sample themes with fresh ids.
 */
object BackupFixtures {
    const val THEME_A = "0b1c2d3e-4f50-4a61-8b72-93a4b5c6d7e8"
    const val THEME_B = "1c2d3e4f-5061-4b72-8c83-a4b5c6d7e8f9"

    /** A sample theme with its id replaced by [id] (and its name by [name]). */
    fun theme(
        id: String,
        name: String,
        sample: String = "paper.strata-theme.json",
    ): String {
        val text = ThemeFixtures.sample(sample).toString(Charsets.UTF_8)
        return text
            .replaceFirst(Regex("\"id\"\\s*:\\s*\"[0-9a-f-]{36}\""), "\"id\": \"$id\"")
            .replaceFirst(Regex("\"name\"\\s*:\\s*\"[^\"]*\""), "\"name\": \"$name\"")
            .replace(Regex("\"basedOn\"\\s*:\\s*\"[0-9a-f-]{36}\","), "")
    }

    fun valid(
        minor: Int = 0,
        extraTop: String = "",
        extraSettings: String = "",
        embedded: List<String> = listOf(theme(THEME_A, "Backup A")),
        work: Boolean = false,
        workEntries: String = "",
    ): String =
        """
        {
          "format": "strata-backup",
          "formatVersion": 1,
          "formatMinor": $minor,$extraTop
          "createdAt": "2026-10-29T10:00:00Z",
          "appVersion": "1.0.0",
          "contents": { "workProfile": $work, "launchLog": false },
          "settings": {
            "list_two_column": false,
            "haptics_enabled": false,
            "badge_style": "BADGE_STYLE_COUNT",
            "appearance_override": "APPEARANCE_DARK"$extraSettings
          },
          "apps": [
            { "component": "com.example.maps/.MainActivity", "profile": "main", "hidden": false, "label": "Nav",
              "icon": { "kind": "pack", "pack": "com.example.iconpack", "drawable": "maps" } },
            { "component": "com.example.mail/.Inbox", "profile": "main", "hidden": true, "icon": { "kind": "system" } },
            { "component": "com.example.missing/.Main", "profile": "main", "label": "Gone" }$workEntries
          ],
          "favorites": [
            { "component": "com.example.maps/.MainActivity", "profile": "main" },
            { "component": "com.example.missing/.Main", "profile": "main" }
          ],
          "themes": {
            "active": "$THEME_A",
            "builtinRefs": [],
            "hiddenBuiltins": [],
            "embedded": [${embedded.joinToString(",")}]
          },
          "widgets": [
            { "provider": "com.example.clock/.ClockWidget", "profile": "main", "heightStep": 2, "replaceClock": true }
          ]
        }
        """.trimIndent()

    fun validBytes(): ByteArray = valid().toByteArray(Charsets.UTF_8)

    fun newerMajor(): ByteArray = valid().replace("\"formatVersion\": 1", "\"formatVersion\": 2").toByteArray()

    /** `formatMinor: 4` with 3 unknown keys (one top-level, two in `settings`). */
    fun newerMinor(): ByteArray =
        valid(
            minor = 4,
            extraTop = "\n  \"futureSection\": { \"x\": 1 },",
            extraSettings = ",\n    \"future_toggle\": true,\n    \"future_mode\": \"FAST\"",
        ).toByteArray()

    fun themeFile(): ByteArray = ThemeFixtures.sample("paper.strata-theme.json")

    fun truncated(): ByteArray = validBytes().let { it.copyOf(it.size / 2) }

    fun duplicateKeys(): ByteArray =
        valid().replace("\"formatMinor\": 0,", "\"formatMinor\": 0,\n  \"formatMinor\": 0,").toByteArray()

    fun invalidUtf8(): ByteArray {
        val b = validBytes()
        val i = String(b, Charsets.UTF_8).indexOf("Nav")
        return b.copyOf().also { it[i] = 0xC3.toByte() } // a lead byte followed by ASCII
    }

    /** 9 MiB: a valid start padded with spaces inside a string would hit other limits first; plain padding. */
    fun oversize(): ByteArray = validBytes() + ByteArray(9 * 1024 * 1024) { ' '.code.toByte() }

    /** One valid and one malformed embedded theme (T-BACKUP-09). */
    fun mixedThemes(): ByteArray =
        valid(
            embedded =
                listOf(
                    theme(THEME_A, "Backup A"),
                    """{ "schemaVersion": "one", "id": "$THEME_B", "name": "Broken" }""",
                ),
        ).toByteArray()

    /** One theme with a newer `schemaVersion`: kept, "Needs app update". */
    fun newerTheme(): ByteArray =
        valid(
            embedded =
                listOf(
                    theme(THEME_A, "Backup A"),
                    """{ "schemaVersion": 9, "id": "$THEME_B", "name": "From the future" }""",
                ),
        ).toByteArray()
}
