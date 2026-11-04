package app.strata.launcher.core.theme

import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.theme.importing.BundleCodec
import app.strata.launcher.core.theme.importing.BundleProblem
import app.strata.launcher.core.theme.importing.ContentKind
import app.strata.launcher.core.theme.importing.ContentSniffer
import app.strata.launcher.core.theme.importing.FontCheck
import app.strata.launcher.core.theme.importing.FontFormat
import app.strata.launcher.core.theme.importing.ImageCheck
import app.strata.launcher.core.theme.importing.ImageHeader
import app.strata.launcher.core.theme.importing.ImageOutcome
import app.strata.launcher.core.theme.importing.ImportCandidate
import app.strata.launcher.core.theme.importing.ImportError
import app.strata.launcher.core.theme.importing.ImportMessage
import app.strata.launcher.core.theme.importing.ThemeFont
import app.strata.launcher.core.theme.importing.ThemeImage
import app.strata.launcher.core.theme.importing.ThemeImporter
import app.strata.launcher.core.theme.importing.ThemeMedia
import app.strata.launcher.core.theme.importing.WebpStrip
import app.strata.launcher.core.theme.model.FontRef
import app.strata.launcher.core.theme.model.ImageMediaType
import app.strata.launcher.testing.fixtures.BundleFixtures
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** T-THEME-29 (U half: hostile bundles), bundle codec round trip (FR-THEME-22) and content sniffing (§3). */
class BundleImportTest {
    private val importer = ThemeImporter(parser, ThemeMedia.TRUSTING, fixedIds())

    private fun prepare(bytes: ByteArray): Outcome<ImportCandidate, ImportError> = importer.prepare(bytes)

    private fun error(bytes: ByteArray): ImportError =
        when (val r = prepare(bytes)) {
            is Outcome.Ok -> error("expected a rejection")
            is Outcome.Err -> r.error
        }

    private fun ok(bytes: ByteArray): ImportCandidate =
        when (val r = prepare(bytes)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> error("expected success, got ${r.error}")
        }

    // ---- §3 sniffing ------------------------------------------------------------------------------

    @Test
    fun sniffsByContentNotName() {
        assertThat(ContentSniffer.sniff("  \n\t{\"a\":1}".toByteArray())).isEqualTo(ContentKind.JSON)
        assertThat(ContentSniffer.sniff(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), '{'.code.toByte())))
            .isEqualTo(ContentKind.JSON)
        assertThat(ContentSniffer.sniff(BundleFixtures.bundle())).isEqualTo(ContentKind.ZIP)
        assertThat(ContentSniffer.sniff("[1]".toByteArray())).isEqualTo(ContentKind.UNKNOWN)
        assertThat(ContentSniffer.sniff(ByteArray(0))).isEqualTo(ContentKind.UNKNOWN)
        assertThat(error("hello".toByteArray()).message).isEqualTo(ImportMessage.NOT_A_THEME)
    }

    // ---- well-formed bundles ----------------------------------------------------------------------

    @Test
    fun wellFormedBundleImportsWithFontAndImage() {
        val c = ok(BundleFixtures.bundle())
        assertThat(c.fromBundle).isTrue()
        assertThat(c.fonts).hasSize(1)
        assertThat(c.image).isNotNull()
        val family = c.parsed.document.typography.family as FontRef.User
        assertThat(family.sha256).isEqualTo(c.fonts.single().sha256)
        assertThat(
            c.parsed.document.wallpaper.image!!
                .sha256,
        ).isEqualTo(c.image!!.sha256)
    }

    @Test
    fun codecRoundTripKeepsBytesAndHashes() {
        val font = BundleFixtures.font()
        val image = BundleFixtures.webpHeader(10, 10)
        val f = ThemeFont(BundleCodec.sha256(font), FontFormat.TTF, font)
        val i = ThemeImage(BundleCodec.sha256(image), ImageMediaType.WEBP, image)
        val theme = BundleFixtures.theme(f.sha256, i.sha256)
        val zip = BundleCodec.write(theme, listOf(f), i)
        assertThat(BundleCodec.write(theme, listOf(f), i)).isEqualTo(zip) // deterministic
        val back = (BundleCodec.read(zip) as Outcome.Ok).value
        assertThat(back.theme).isEqualTo(theme)
        assertThat(back.fonts.single().bytes).isEqualTo(font)
        assertThat(back.image!!.bytes).isEqualTo(image)
    }

    @Test
    fun reencodedImageRewritesTheReference() {
        val reencoded = BundleFixtures.webpHeader(5, 5)
        val media =
            object : ThemeMedia by ThemeMedia.TRUSTING {
                override fun prepareImage(
                    bytes: ByteArray,
                    header: ImageHeader,
                ): ImageOutcome = ImageOutcome.Reencoded(reencoded)
            }
        val c = ThemeImporter(parser, media, fixedIds()).prepare(BundleFixtures.bundle()) as Outcome.Ok
        val sha = BundleCodec.sha256(reencoded)
        assertThat(c.value.image!!.sha256).isEqualTo(sha)
        assertThat(
            c.value.parsed.document.wallpaper.image!!
                .sha256,
        ).isEqualTo(sha)
        assertThat(
            c.value.parsed.document.wallpaper.image!!
                .mediaType,
        ).isEqualTo(ImageMediaType.WEBP)
    }

    @Test
    fun fontThatThePlatformRefusesRejectsTheBundle() {
        val media =
            object : ThemeMedia by ThemeMedia.TRUSTING {
                override fun fontLoads(
                    bytes: ByteArray,
                    format: FontFormat,
                ) = false
            }
        val r = ThemeImporter(parser, media, fixedIds()).prepare(BundleFixtures.bundle()) as Outcome.Err
        assertThat(r.error.message).isEqualTo(ImportMessage.FONT_UNREADABLE)
    }

    @Test
    fun themeReferencingAnUnlistedFontIsRejected() {
        val otherSha = "ab".repeat(32)
        val bytes =
            BundleFixtures.bundle(
                font = null,
                image = null,
                themeBytes = BundleFixtures.theme(fontSha = otherSha),
            )
        assertThat(error(bytes).message).isEqualTo(ImportMessage.UNEXPECTED_FILES)
    }

    @Test
    fun newerBundleVersionIsRejectedWithTheNewerMessage() {
        assertThat(error(BundleFixtures.bundle(manifestVersion = 2)).message).isEqualTo(ImportMessage.NEWER_VERSION)
    }

    // ---- T-THEME-29 hostile corpus ------------------------------------------------------------------

    @Test
    fun tTheme29_hostileBundlesAreRejectedWithTheDocumentedMessage() {
        val corpus =
            listOf(
                Triple("zip bomb (ratio > 200)", BundleFixtures.zipBomb(), ImportMessage.TOO_LARGE),
                Triple("zip-slip names", BundleFixtures.zipSlip(), ImportMessage.UNEXPECTED_FILES),
                Triple("unlisted entry", BundleFixtures.unlistedEntry(), ImportMessage.UNEXPECTED_FILES),
                Triple("hash mismatch", BundleFixtures.hashMismatch(), ImportMessage.DAMAGED),
                Triple("encrypted entry", BundleFixtures.encryptedEntry(), ImportMessage.NOT_A_THEME),
                Triple("ZIP64", BundleFixtures.zip64(), ImportMessage.NOT_A_THEME),
                Triple("25 MiB archive", BundleFixtures.oversizeArchive(), ImportMessage.TOO_LARGE),
                Triple("WOFF2 font", BundleFixtures.woff2Font(), ImportMessage.FONT_UNREADABLE),
                Triple("font with bad tables", BundleFixtures.badTablesFont(), ImportMessage.FONT_UNREADABLE),
                Triple("font missing a table", BundleFixtures.missingTableFont(), ImportMessage.FONT_UNREADABLE),
                Triple("9000 px image", BundleFixtures.image9000px(), ImportMessage.IMAGE_UNREADABLE),
                Triple("50 MP image", BundleFixtures.image50mp(), ImportMessage.IMAGE_UNREADABLE),
            )
        for ((what, bytes, expected) in corpus) {
            assertWithMessage(what).that(error(bytes).message).isEqualTo(expected)
        }
    }

    @Test
    fun specificProblemsAreReported() {
        fun problem(b: ByteArray) = (error(b) as ImportError.Bundle).problem
        assertThat(problem(BundleFixtures.zipBomb())).isEqualTo(BundleProblem.ZIP_BOMB)
        assertThat(problem(BundleFixtures.encryptedEntry())).isEqualTo(BundleProblem.ENCRYPTED)
        assertThat(problem(BundleFixtures.zip64())).isEqualTo(BundleProblem.ZIP64)
        assertThat(problem(BundleFixtures.zipSlip())).isEqualTo(BundleProblem.UNEXPECTED_ENTRY)
        assertThat(problem(BundleFixtures.unlistedEntry())).isEqualTo(BundleProblem.UNLISTED_ENTRY)
        assertThat(problem(BundleFixtures.hashMismatch())).isEqualTo(BundleProblem.HASH_MISMATCH)
    }

    @Test
    fun corruptedArchivesNeverThrow() {
        val good = BundleFixtures.bundle()
        val rnd = kotlin.random.Random(29)
        repeat(2_000) {
            val b = good.copyOf()
            repeat(1 + rnd.nextInt(8)) { b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() }
            val cut = if (rnd.nextInt(4) == 0) b.copyOf(rnd.nextInt(b.size)) else b
            prepare(cut) // Ok or Err, never an exception
        }
    }

    // ---- font and image header checks ---------------------------------------------------------------

    @Test
    fun fontCheckAcceptsSfntAndRejectsCollectionsAndWoff() {
        assertThat(FontCheck.check(BundleFixtures.font())).isEqualTo(FontFormat.TTF)
        assertThat(FontCheck.check(BundleFixtures.font(version = 0x4F54544F))).isEqualTo(FontFormat.OTF)
        assertThat(FontCheck.check("ttcf".toByteArray() + ByteArray(60))).isNull()
        assertThat(FontCheck.check("wOFF".toByteArray() + ByteArray(60))).isNull()
        assertThat(FontCheck.check(BundleFixtures.woff2())).isNull()
        assertThat(FontCheck.check(ByteArray(FontCheck.MAX_BYTES + 1))).isNull()
    }

    @Test
    fun imageHeadersGiveDimensions() {
        assertThat(ImageCheck.header(BundleFixtures.webpHeader(640, 480)))
            .isEqualTo(ImageHeader(ImageMediaType.WEBP, 640, 480, hasMetadata = false))
        assertThat(ImageCheck.header(BundleFixtures.webpHeader(10, 10, exif = true))!!.hasMetadata).isTrue()
        assertThat(ImageCheck.header(BundleFixtures.pngHeader(9000, 10)))
            .isEqualTo(ImageHeader(ImageMediaType.PNG, 9000, 10, hasMetadata = false))
        assertThat(ImageCheck.withinLimits(ImageCheck.header(BundleFixtures.pngHeader(9000, 10))!!)).isFalse()
        assertThat(ImageCheck.withinLimits(ImageCheck.header(BundleFixtures.webpHeader(8000, 6250))!!)).isFalse()
        assertThat(ImageCheck.withinLimits(ImageCheck.header(BundleFixtures.webpHeader(8192, 4000))!!)).isTrue()
    }

    @Test
    fun webpStripRemovesMetadataChunksAndFlags() {
        val withExif = BundleFixtures.webpHeader(10, 10, exif = true)
        val stripped = WebpStrip.strip(withExif)!!
        assertThat(ImageCheck.header(stripped)).isEqualTo(ImageHeader(ImageMediaType.WEBP, 10, 10, hasMetadata = false))
        assertThat(stripped).isEqualTo(BundleFixtures.webpHeader(10, 10))
        assertThat(WebpStrip.strip(stripped)).isEqualTo(stripped)
        assertThat(WebpStrip.strip("RIFF....WEBX".toByteArray())).isNull()
    }

    @Test
    fun webpWithUnknownChunksOrTrailingBytesIsNotClean() {
        val clean = BundleFixtures.webpHeader(10, 10)
        assertThat(ImageCheck.header(clean)!!.hasMetadata).isFalse()
        assertThat(ImageCheck.header(clean + byteArrayOf(1, 2, 3))!!.hasMetadata).isTrue()
        // An unknown chunk inside the RIFF payload.
        val body = clean.copyOfRange(8, clean.size) + "ZZZZ".toByteArray() + byteArrayOf(2, 0, 0, 0, 7, 7)
        val withUnknown = "RIFF".toByteArray() + le32(body.size) + body
        assertThat(ImageCheck.header(withUnknown)!!.hasMetadata).isTrue()
    }

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    @Test
    fun unreferencedBundleImageIsDropped() {
        val image = BundleFixtures.webpHeader(64, 32)
        val bytes =
            BundleFixtures.bundle(
                font = null,
                image = image,
                themeBytes = BundleFixtures.theme(fontSha = null, imageSha = null),
            )
        val c = ok(bytes)
        assertThat(c.image).isNull()
    }

    @Test
    fun directoryEntriesAreIgnoredForTheEntryCount() {
        val font = BundleFixtures.font()
        val image = BundleFixtures.webpHeader(10, 10)
        val fontSha = BundleFixtures.sha256(font)
        val imageSha = BundleFixtures.sha256(image)
        val theme = BundleFixtures.theme(fontSha, imageSha)
        val files =
            listOf(
                BundleFixtures.Entry("fonts/$fontSha.ttf", font, "font"),
                BundleFixtures.Entry("images/$imageSha.webp", image, "wallpaper"),
            )
        // Like `zip -r`: explicit directory entries, which do not count towards the 8 entries.
        val dirs = (1..6).map { BundleFixtures.Entry("dir$it/", ByteArray(0), listed = false) }
        val zip =
            BundleFixtures.zip(
                listOf(
                    BundleFixtures.Entry("manifest.json", BundleFixtures.manifest(theme, files)),
                    BundleFixtures.Entry("theme.json", theme),
                    BundleFixtures.Entry("fonts/", ByteArray(0), listed = false),
                    BundleFixtures.Entry("images/", ByteArray(0), listed = false),
                ) + dirs + files,
            )
        assertThat(ok(zip).fonts).hasSize(1)
    }
}
