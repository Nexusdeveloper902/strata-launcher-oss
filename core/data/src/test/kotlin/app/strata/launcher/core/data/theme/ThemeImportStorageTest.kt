package app.strata.launcher.core.data.theme

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.theme.importing.BundleCodec
import app.strata.launcher.core.theme.importing.ConflictChoice
import app.strata.launcher.core.theme.importing.FontFormat
import app.strata.launcher.core.theme.importing.ImportMessage
import app.strata.launcher.core.theme.importing.ThemeMedia
import app.strata.launcher.core.theme.resolve.PlatformInputs
import app.strata.launcher.core.theme.store.ThemeLibrary
import app.strata.launcher.testing.fakes.FakeClock
import app.strata.launcher.testing.fakes.FakeSettingsRepository
import app.strata.launcher.testing.fakes.RecordingLogger
import app.strata.launcher.testing.fixtures.BundleFixtures
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Imports over the real stores (AtomicFile theme files, Proto DataStore index) and the Android media checks
 * (Robolectric native graphics): the R half of T-THEME-29 (hostile bundles leave no file in the stores or the import
 * cache) and of T-THEME-28 (a JPEG wallpaper is stored as stripped WebP; export + clean install + import keeps the
 * bytes and hash).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeImportStorageTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val clock = FakeClock(1_800_000_000_000)

    /** One install: its own files, index and cache. */
    private inner class Install(
        name: String,
    ) : AutoCloseable {
        val root = File(tmp.root, name)
        val themesDir = File(root, "files/themes")
        val cacheDir = File(root, "cache")
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val files = AtomicFileThemeFileStore({ themesDir })
        val androidMedia =
            AndroidThemeMedia({ File(cacheDir, AndroidThemeMedia.IMPORT_DIR) }, { 2048 }, RecordingLogger())

        /** Fonts: the fixture font is structural only, so the platform loader is not asked here (device test does). */
        val media =
            object : ThemeMedia by androidMedia {
                override fun fontLoads(
                    bytes: ByteArray,
                    format: FontFormat,
                ): Boolean = true
            }
        val repo =
            DefaultThemeRepository(
                createLibrary = {
                    ThemeLibrary(
                        files,
                        DataStoreThemeIndexStore.create(dataStoreScope) { File(root, "datastore/theme_index.pb") },
                        AssetBuiltInThemeSource(context.assets),
                        clock,
                    )
                },
                files = { files },
                bootAttempts = BootAttempts({ File(root, "no_backup/boot.state") }, clock),
                platformInputs = MutableStateFlow(PlatformInputs(apiLevel = 34)),
                settings = FakeSettingsRepository().settings,
                themeDispatcher = Dispatchers.IO.limitedParallelism(1),
                resolveDispatcher = Dispatchers.Default,
                scope = scope,
                logger = RecordingLogger(),
                media = media,
            )

        fun import(bytes: ByteArray): Outcome<String, ImportMessage> =
            runBlocking {
                when (val p = repo.prepareImport(bytes)) {
                    is Outcome.Err -> {
                        Outcome.Err(p.error.message)
                    }

                    is Outcome.Ok -> {
                        when (val c = repo.commitImport(p.value.token, ConflictChoice.KEEP_BOTH, apply = false)) {
                            is Outcome.Ok -> Outcome.Ok(c.value.id)
                            is Outcome.Err -> Outcome.Err(c.error.message)
                        }
                    }
                }
            }

        fun listing(): List<String> =
            listOf(themesDir, cacheDir).flatMap { d ->
                d
                    .walkTopDown()
                    .filter { it.isFile }
                    .map { it.relativeTo(root).path }
                    .toList()
            }

        override fun close() {
            runBlocking {
                scope.coroutineContext.job.cancelAndJoin()
                dataStoreScope.coroutineContext.job.cancelAndJoin()
            }
        }
    }

    @Test
    fun tTheme29_hostileBundlesLeaveNoFileInStoresOrCache() =
        Install("hostile").use { install ->
            val corpus =
                mapOf(
                    "zip bomb" to BundleFixtures.zipBomb(),
                    "zip slip" to BundleFixtures.zipSlip(),
                    "unlisted entry" to BundleFixtures.unlistedEntry(),
                    "hash mismatch" to BundleFixtures.hashMismatch(),
                    "encrypted" to BundleFixtures.encryptedEntry(),
                    "zip64" to BundleFixtures.zip64(),
                    "25 MiB" to BundleFixtures.oversizeArchive(),
                    "woff2" to BundleFixtures.woff2Font(),
                    "bad tables" to BundleFixtures.badTablesFont(),
                    "9000 px" to BundleFixtures.image9000px(),
                    "50 MP" to BundleFixtures.image50mp(),
                    // Header says WebP, the bytes do not decode: rejected by the platform decoder.
                    "undecodable image" to
                        BundleFixtures.bundle(
                            font = null,
                            image = BundleFixtures.webpHeader(64, 64),
                        ),
                )
            runBlocking { install.repo.entries() } // boot: the index may be written
            val baseline = install.listing()
            for ((what, bytes) in corpus) {
                assertWithMessage(what).that(install.import(bytes)).isInstanceOf(Outcome.Err::class.java)
                assertWithMessage(what).that(install.listing()).isEqualTo(baseline)
            }
        }

    private fun jpeg(): ByteArray {
        val bmp = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        for (x in 0 until 64) for (y in 0 until 48) bmp.setPixel(x, y, Color.rgb(x * 4, y * 5, 128))
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        return out.toByteArray()
    }

    @Test
    fun tTheme28_jpegIsStoredAsWebpAndRoundTripsByteForByte() {
        val photo = jpeg()
        val font = BundleFixtures.font()
        val photoSha = BundleFixtures.sha256(photo)
        val fontSha = BundleFixtures.sha256(font)
        val theme =
            BundleFixtures
                .theme(fontSha, photoSha)
                .toString(Charsets.UTF_8)
                .replace("image/webp", "image/jpeg")
                .toByteArray()
        val files =
            listOf(
                BundleFixtures.Entry("fonts/$fontSha.ttf", font, "font"),
                BundleFixtures.Entry("images/$photoSha.jpg", photo, "wallpaper"),
            )
        val bundle =
            BundleFixtures.zip(
                listOf(
                    BundleFixtures.Entry("manifest.json", BundleFixtures.manifest(theme, files)),
                    BundleFixtures.Entry("theme.json", theme),
                ) + files,
            )

        val (exported, storedImage, storedFont) =
            Install("first").use { a ->
                val id = (a.import(bundle) as Outcome.Ok).value
                val image = File(a.themesDir, "images").listFiles()!!.single()
                assertThat(image.name).endsWith(".webp")
                val bytes = image.readBytes()
                assertThat(String(bytes, 0, 4, Charsets.ISO_8859_1)).isEqualTo("RIFF")
                assertThat(image.name.removeSuffix(".webp")).isEqualTo(BundleCodec.sha256(bytes))
                val fontFile = File(a.themesDir, "fonts").listFiles()!!.single()
                assertThat(fontFile.readBytes()).isEqualTo(font)
                val zip = runBlocking { a.repo.exportBundle(id) } as Outcome.Ok
                Triple(zip.value.bytes, bytes, fontFile.readBytes())
            }

        Install("clean").use { b ->
            val id = (b.import(exported) as Outcome.Ok).value
            val image = File(b.themesDir, "images").listFiles()!!.single()
            assertThat(image.readBytes()).isEqualTo(storedImage) // stored byte for byte (already stripped WebP)
            assertThat(File(b.themesDir, "fonts").listFiles()!!.single().readBytes()).isEqualTo(storedFont)
            val doc = runBlocking { b.repo.exportJson(id) }!!.bytes.toString(Charsets.UTF_8)
            assertThat(doc).contains(BundleCodec.sha256(storedImage))
            assertThat(File(b.cacheDir, AndroidThemeMedia.IMPORT_DIR).listFiles().orEmpty()).isEmpty()
        }
    }
}
