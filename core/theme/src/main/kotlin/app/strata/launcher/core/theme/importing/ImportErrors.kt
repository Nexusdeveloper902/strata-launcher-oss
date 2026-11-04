package app.strata.launcher.core.theme.importing

import app.strata.launcher.core.theme.parse.ThemeError
import app.strata.launcher.core.theme.parse.ThemeErrorText

/** Why a bundle (THEME_SYSTEM §10.3) was rejected. Mapped to the user messages of §8.2 by [ImportError.message]. */
enum class BundleProblem {
    /** Not a readable zip: no end record, bad offsets, a CRC or size that does not match the bytes. */
    DAMAGED,

    /** Archive larger than 24 MiB, or an entry above its type limit, or more than 24 MiB decompressed. */
    TOO_LARGE,

    /** Decompressed/compressed ratio above 200 for an entry (counted on the actual bytes). */
    ZIP_BOMB,
    TOO_MANY_ENTRIES,
    ENCRYPTED,
    ZIP64,

    /** A compression method other than stored (0) or deflate (8). */
    UNSUPPORTED_METHOD,

    /** An entry name outside the allow-list regex of §10.3.1, or a name used twice. */
    UNEXPECTED_ENTRY,

    /** An entry that the manifest does not list. */
    UNLISTED_ENTRY,

    /** A manifest file (or `theme.json`) missing from the archive. */
    MISSING_ENTRY,

    /** A SHA-256 that does not match the manifest, or a file name whose hash is not its content hash. */
    HASH_MISMATCH,

    /** A byte count that does not match the manifest. */
    SIZE_MISMATCH,
    BAD_MANIFEST,

    /** `bundleVersion > 1`. */
    NEWER_BUNDLE,

    /** `theme.json` references a user font or wallpaper image the bundle does not list. */
    UNLISTED_REFERENCE,
    FONT_INVALID,
    IMAGE_INVALID,
}

/** The user-facing message classes of THEME_SYSTEM §8.2 (the UI maps them to localised strings). */
enum class ImportMessage {
    NOT_A_THEME,
    NEWER_VERSION,
    DAMAGED,
    TOO_LARGE,
    UNEXPECTED_FILES,
    FONT_UNREADABLE,

    /** Not in §8.2 (Proposed, docs/impl-notes/M5.md): "The wallpaper in this bundle couldn't be read." */
    IMAGE_UNREADABLE,

    /** The source could not be opened (provider revoked, file deleted): ANDROID_PLATFORM §16 failure row. */
    UNREADABLE,

    /** §11.3: writes fail cleanly with "Not enough storage". */
    NOT_ENOUGH_STORAGE,

    /** Only `content:` URIs are read (ARCHITECTURE §13, T-SEC-01). */
    UNSUPPORTED_SOURCE,
}

/** A rejected import. [detail] is a short developer text that never echoes file content. */
sealed interface ImportError {
    val message: ImportMessage
    val detail: String

    data class Theme(val error: ThemeError) : ImportError {
        override val message: ImportMessage
            get() =
                when (error) {
                    is ThemeError.TooLarge -> ImportMessage.TOO_LARGE
                    is ThemeError.Damaged -> ImportMessage.DAMAGED
                    is ThemeError.NotATheme -> ImportMessage.NOT_A_THEME
                    is ThemeError.NewerVersion -> ImportMessage.NEWER_VERSION
                }
        override val detail: String get() = ThemeErrorText.english(error)
    }

    data class Bundle(val problem: BundleProblem, override val detail: String) : ImportError {
        override val message: ImportMessage
            get() =
                when (problem) {
                    BundleProblem.TOO_LARGE, BundleProblem.ZIP_BOMB, BundleProblem.TOO_MANY_ENTRIES -> {
                        ImportMessage.TOO_LARGE
                    }

                    BundleProblem.UNEXPECTED_ENTRY, BundleProblem.UNLISTED_ENTRY, BundleProblem.UNLISTED_REFERENCE -> {
                        ImportMessage.UNEXPECTED_FILES
                    }

                    BundleProblem.DAMAGED, BundleProblem.HASH_MISMATCH, BundleProblem.SIZE_MISMATCH,
                    BundleProblem.MISSING_ENTRY,
                    -> {
                        ImportMessage.DAMAGED
                    }

                    BundleProblem.ENCRYPTED, BundleProblem.ZIP64, BundleProblem.UNSUPPORTED_METHOD,
                    BundleProblem.BAD_MANIFEST,
                    -> {
                        ImportMessage.NOT_A_THEME
                    }

                    BundleProblem.NEWER_BUNDLE -> {
                        ImportMessage.NEWER_VERSION
                    }

                    BundleProblem.FONT_INVALID -> {
                        ImportMessage.FONT_UNREADABLE
                    }

                    BundleProblem.IMAGE_INVALID -> {
                        ImportMessage.IMAGE_UNREADABLE
                    }
                }
    }

    data class Other(override val message: ImportMessage, override val detail: String) : ImportError

    companion object {
        val NOT_A_THEME: ImportError = Other(ImportMessage.NOT_A_THEME, "Neither JSON nor a zip bundle")
        val TOO_LARGE: ImportError = Other(ImportMessage.TOO_LARGE, "Source larger than the import limit")
        val UNREADABLE: ImportError = Other(ImportMessage.UNREADABLE, "Source could not be read")
        val UNSUPPORTED_SOURCE: ImportError = Other(ImportMessage.UNSUPPORTED_SOURCE, "Only content: URIs are read")
        val NOT_ENOUGH_STORAGE: ImportError = Other(ImportMessage.NOT_ENOUGH_STORAGE, "Write failed")
    }
}

/** English reference texts (logs, tests); the app shows string resources keyed by [ImportMessage]. */
object ImportMessageText {
    fun english(m: ImportMessage): String =
        when (m) {
            ImportMessage.NOT_A_THEME -> {
                "This file isn't a Strata theme."
            }

            ImportMessage.NEWER_VERSION -> {
                "This theme was made with a newer version of Strata Launcher. Update the app to use it."
            }

            ImportMessage.DAMAGED -> {
                "The file is damaged."
            }

            ImportMessage.TOO_LARGE -> {
                "The file is too large."
            }

            ImportMessage.UNEXPECTED_FILES -> {
                "The theme bundle contains unexpected files."
            }

            ImportMessage.FONT_UNREADABLE -> {
                "A font in this bundle couldn't be read."
            }

            ImportMessage.IMAGE_UNREADABLE -> {
                "The wallpaper in this bundle couldn't be read."
            }

            ImportMessage.UNREADABLE -> {
                "The file couldn't be opened."
            }

            ImportMessage.NOT_ENOUGH_STORAGE -> {
                "Not enough storage."
            }

            ImportMessage.UNSUPPORTED_SOURCE -> {
                "Strata can only import files shared by another app."
            }
        }
}
