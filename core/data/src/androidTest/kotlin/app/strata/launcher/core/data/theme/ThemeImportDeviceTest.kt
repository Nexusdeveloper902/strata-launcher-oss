package app.strata.launcher.core.data.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.StrictMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.theme.importing.BundleCodec
import app.strata.launcher.core.theme.importing.ConflictChoice
import app.strata.launcher.core.theme.importing.FontCheck
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
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Level I theme file flows over the real stores and the platform media checks (`Typeface.Builder`, `BitmapFactory`,
 * the WebP encoder): T-THEME-28 (bundle with a real system font and a JPEG wallpaper: export, clean install, import),
 * the I half of T-THEME-29 (hostile corpus leaves no file) and of T-THEME-51 (every library flow under StrictMode
 * `detectNetwork` + `penaltyDeath`, cleartext detection as well).
 */
@RunWith(AndroidJUnit4::class)
class ThemeImportDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val clock = FakeClock(1_800_000_000_000)
    private val base = File(context.cacheDir, "theme-import-device-test")
    private val installs = mutableListOf<Install>()

    @Before
    fun clean() {
        base.deleteRecursively()
    }

    @After
    fun close() {
        installs.forEach { it.close() }
        base.deleteRecursively()
    }

    /** One simulated install. [unconfined] runs every repository call on the caller's thread (StrictMode test). */
    private inner class Install(
        name: String,
        unconfined: Boolean = false,
    ) {
        val root = File(base, name)
        val themesDir = File(root, "themes")
        val importDir = File(root, "cache/import")
        private val scope =
            CoroutineScope(SupervisorJob() + if (unconfined) Dispatchers.Unconfined else Dispatchers.Default)
        private val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val files = AtomicFileThemeFileStore({ themesDir })
        val repo =
            DefaultThemeRepository(
                createLibrary = {
                    ThemeLibrary(
                        files,
                        DataStoreThemeIndexStore.create(dataStoreScope) { File(root, "theme_index.pb") },
                        AssetBuiltInThemeSource(context.assets),
                        clock,
                    )
                },
                files = { files },
                bootAttempts = BootAttempts({ File(root, "boot.state") }, clock),
                platformInputs = MutableStateFlow(PlatformInputs(apiLevel = android.os.Build.VERSION.SDK_INT)),
                settings = FakeSettingsRepository().settings,
                themeDispatcher = if (unconfined) Dispatchers.Unconfined else Dispatchers.IO.limitedParallelism(1),
                resolveDispatcher = if (unconfined) Dispatchers.Unconfined else Dispatchers.Default,
                scope = scope,
                logger = RecordingLogger(),
                media = AndroidThemeMedia({ importDir }, { 2048 }, RecordingLogger()),
            ).also { installs += this }

        fun import(bytes: ByteArray): String? =
            runBlocking {
                val p = (repo.prepareImport(bytes) as? Outcome.Ok)?.value ?: return@runBlocking null
                (repo.commitImport(p.token, ConflictChoice.KEEP_BOTH, apply = false) as? Outcome.Ok)?.value?.id
            }

        fun listing(): List<String> =
            listOf(themesDir, importDir).flatMap { d ->
                d
                    .walkTopDown()
                    .filter { it.isFile }
                    .map { it.relativeTo(root).path }
                    .toList()
            }

        fun close() {
            runBlocking {
                scope.coroutineContext.job.cancelAndJoin()
                dataStoreScope.coroutineContext.job.cancelAndJoin()
            }
        }
    }

    /** A real TrueType font of the device (any `.ttf` in `/system/fonts` that passes the structural check). */
    private fun systemFont(): ByteArray? =
        File("/system/fonts")
            .listFiles()
            .orEmpty()
            .filter { it.name.endsWith(".ttf") && it.length() in 1..FontCheck.MAX_BYTES.toLong() }
            .sortedBy { it.length() }
            .firstNotNullOfOrNull { f -> f.readBytes().takeIf { FontCheck.check(it) != null } }

    private fun jpeg(): ByteArray {
        val bmp = Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888)
        for (x in 0 until 320) for (y in 0 until 200) bmp.setPixel(x, y, Color.rgb(x * 255 / 320, y, 160))
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        return out.toByteArray()
    }

    private fun bundleWith(
        font: ByteArray,
        photo: ByteArray,
    ): ByteArray {
        val fontSha = BundleFixtures.sha256(font)
        val photoSha = BundleFixtures.sha256(photo)
        val theme =
            BundleFixtures
                .theme(fontSha, photoSha)
                .toString(Charsets.UTF_8)
                .replace("image/webp", "image/jpeg")
                .toByteArray()
        val entries =
            listOf(
                BundleFixtures.Entry("fonts/$fontSha.ttf", font, "font"),
                BundleFixtures.Entry("images/$photoSha.jpg", photo, "wallpaper"),
            )
        return BundleFixtures.zip(
            listOf(
                BundleFixtures.Entry("manifest.json", BundleFixtures.manifest(theme, entries)),
                BundleFixtures.Entry("theme.json", theme),
            ) + entries,
        )
    }

    @Test
    fun tTheme28_bundleRoundTripOnACleanInstall() {
        val font = systemFont()
        assumeTrue("no TrueType font under /system/fonts", font != null)
        val a = Install("a")
        val id = a.import(bundleWith(font!!, jpeg()))
        assertWithMessage("import with a real font and a JPEG").that(id).isNotNull()
        val storedImage = File(a.themesDir, "images").listFiles()!!.single()
        assertThat(storedImage.name).endsWith(".webp")
        val imageBytes = storedImage.readBytes()
        assertThat(storedImage.name.removeSuffix(".webp")).isEqualTo(BundleCodec.sha256(imageBytes))
        val zip = (runBlocking { a.repo.exportBundle(id!!) } as Outcome.Ok).value.bytes

        val b = Install("b")
        val id2 = b.import(zip)
        assertThat(id2).isEqualTo(id)
        assertThat(File(b.themesDir, "fonts").listFiles()!!.single().readBytes()).isEqualTo(font)
        assertThat(File(b.themesDir, "images").listFiles()!!.single().readBytes()).isEqualTo(imageBytes)
        assertThat(b.importDir.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun tTheme29_hostileBundlesLeaveNoFile() {
        val install = Install("hostile")
        runBlocking { install.repo.entries() }
        val baseline = install.listing()
        val corpus =
            listOf(
                BundleFixtures.zipBomb(),
                BundleFixtures.zipSlip(),
                BundleFixtures.unlistedEntry(),
                BundleFixtures.hashMismatch(),
                BundleFixtures.encryptedEntry(),
                BundleFixtures.zip64(),
                BundleFixtures.oversizeArchive(),
                BundleFixtures.woff2Font(),
                BundleFixtures.badTablesFont(),
                // Structurally fine but not loadable by the platform.
                BundleFixtures.bundle(image = null),
                BundleFixtures.image9000px(),
                BundleFixtures.image50mp(),
            )
        for ((i, bytes) in corpus.withIndex()) {
            assertWithMessage("corpus #$i").that(install.import(bytes)).isNull()
            assertWithMessage("corpus #$i").that(install.listing()).isEqualTo(baseline)
        }
    }

    @Test
    fun tTheme51_themeFlowsMakeNoNetworkAccess() {
        val oldThread = StrictMode.getThreadPolicy()
        val oldVm = StrictMode.getVmPolicy()
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy
                .Builder()
                .detectNetwork()
                .penaltyDeath()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy
                .Builder()
                .detectCleartextNetwork()
                .penaltyDeathOnCleartextNetwork()
                .build(),
        )
        try {
            val install = Install("network", unconfined = true)
            val font = systemFont()
            val id = install.import(ThemeTestBytes.json)!!
            runBlocking {
                install.repo.entries()
                install.repo.exportJson(id)
                val copy = install.repo.duplicate(id)!!
                install.repo.setHidden("c5017d5b-b2b0-48ff-83ed-33f77fd14754", true)
                install.repo.apply(copy)
                val ticket = install.repo.requestDelete(copy)!!
                install.repo.undoDelete(ticket)
                install.repo.commitDelete(ticket)
            }
            if (font != null) {
                val bundled = install.import(bundleWith(font, jpeg()))!!
                runBlocking {
                    install.repo.exportBundle(bundled)
                    install.repo.wallpaperImage(bundled)
                }
            }
        } finally {
            StrictMode.setThreadPolicy(oldThread)
            StrictMode.setVmPolicy(oldVm)
        }
    }

    private object ThemeTestBytes {
        val json =
            """{"schemaVersion":1,"id":"aaaaaaaa-0000-4000-8000-000000000051","name":"Offline"}""".toByteArray()
    }
}
