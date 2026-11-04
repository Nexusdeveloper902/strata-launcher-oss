package app.strata.launcher.theme

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.StrictMode
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.strata.launcher.DeviceStateRule
import app.strata.launcher.appGraph
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.theme.contrast.Contrast
import app.strata.launcher.core.theme.importing.ConflictChoice
import app.strata.launcher.core.theme.model.SourceKind
import app.strata.launcher.core.theme.model.WallpaperSpec
import app.strata.launcher.core.theme.resolve.PlatformInputs
import app.strata.launcher.core.theme.resolve.ThemeResolver
import app.strata.launcher.core.theme.store.ThemeState
import app.strata.launcher.feature.theme.EditorAction
import app.strata.launcher.feature.theme.EditorRequest
import app.strata.launcher.feature.theme.EditorUiState
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Level I editor flows in the real app over real storage (AtomicFile, DataStore):
 * - T-THEME-37: edits reach `filesDir/themes/drafts/<id>.json` within the 2 s autosave; leaving the editor without
 *   Save or Discard (as process death does) keeps the draft, and reopening the theme offers it. The real kill
 *   (`am kill` while the editor is in the background) is part of the manual pass on the A56.
 * - T-THEME-38: an image handed to the photo-picker result seam ([ThemeEditorActivity.onImagePicked]) gives a `seed`
 *   theme whose palette passes P1–P8; the merged manifest requests no storage or media permission. The picker UI
 *   cannot be driven reliably on GMD and is a manual check.
 * - T-THEME-51 (editor half): the editor flows run with StrictMode `detectNetwork` + penalty death on the main and
 *   test threads, and cleartext penalty death for the VM.
 */
@RunWith(AndroidJUnit4::class)
class ThemeEditorDeviceTest {
    @get:Rule
    val device = DeviceStateRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    /** The theme that was active before the test; restored afterwards so no state leaks into later tests. */
    private var activeBefore: String? = null

    @Before
    fun rememberActiveTheme() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { context.appGraph.startTheme() }
        activeBefore = awaitTheme("loaded") { it.loaded }.activeId
    }

    /**
     * Leaves the device as found: the previously active theme is applied again (T-THEME-51 applies its theme), and the
     * themes and drafts this class made are deleted. Without this `ThemeFilesDeviceTest.tTheme27…` saw the editor's
     * theme still active (CI run 41893533129).
     */
    @After
    fun restoreThemes() {
        val repo = context.appGraph.themeRepository
        val before = activeBefore ?: return
        if (!repo.state.value.activeId
                .equals(before, ignoreCase = true)
        ) {
            repo.apply(before)
            awaitTheme("previous theme active again") { it.activeId.equals(before, ignoreCase = true) }
        }
        runBlocking {
            val manager = context.appGraph.themeManager
            for (id in listOf(ID_37, ID_51)) {
                manager.requestDelete(id)?.let { manager.commitDelete(it) }
            }
            val editor = context.appGraph.themeEditor
            for (d in editor.drafts()) editor.deleteDraft(d.sessionId)
            // Themes saved by create-from-image sessions (new ids) are named "Theme from image…": remove them too.
            for (t in manager.entries()) {
                if (!t.builtIn && t.name.startsWith(IMAGE_THEME_PREFIX)) {
                    manager.requestDelete(t.id)?.let { manager.commitDelete(it) }
                }
            }
        }
    }

    private fun awaitTheme(
        what: String,
        check: (ThemeState) -> Boolean,
    ): ThemeState {
        val repo = context.appGraph.themeRepository
        val end = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (SystemClock.uptimeMillis() < end) {
            val s = repo.state.value
            if (check(s)) return s
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("timed out waiting for $what: active=${repo.state.value.activeId}")
    }

    private fun storeTheme(
        id: String,
        name: String,
        extra: String = "",
    ): String =
        runBlocking {
            val m = context.appGraph.themeManager
            val bytes = """{"schemaVersion":1,"id":"$id","name":"$name"$extra}""".toByteArray()
            val p = (m.prepareImport(bytes) as Outcome.Ok).value
            (m.commitImport(p.token, ConflictChoice.REPLACE, apply = false) as Outcome.Ok).value.id
        }

    private fun launch(request: EditorRequest): ActivityScenario<ThemeEditorActivity> =
        ActivityScenario.launch(ThemeEditorActivity.intent(context, request))

    private fun ActivityScenario<ThemeEditorActivity>.state(): EditorUiState {
        var s: EditorUiState? = null
        onActivity { s = it.viewModel.state.value }
        return s!!
    }

    private fun ActivityScenario<ThemeEditorActivity>.action(a: EditorAction) = onActivity { it.viewModel.onAction(a) }

    private fun ActivityScenario<ThemeEditorActivity>.await(
        what: String,
        timeoutMs: Long = TIMEOUT_MS,
        check: (EditorUiState) -> Boolean,
    ): EditorUiState {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            val s = state()
            if (check(s)) return s
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("timed out waiting for $what: ${state()}")
    }

    private fun draftFile(id: String) = File(context.filesDir, "themes/drafts/${id.lowercase()}.json")

    @Test
    fun tTheme37_draftIsWrittenAndOfferedOnReturn() {
        val id = storeTheme(ID_37, "Draft device")
        draftFile(id).delete()
        launch(EditorRequest.Edit(id)).use { s ->
            s.await("opened") { it.document != null }
            repeat(120) { i -> s.action(EditorAction.Set("icons.sizeDp", if (i % 2 == 0) 40 else 41)) }
            for (v in 0..50) s.action(EditorAction.Set("typography.scale", 0.8 + v * 0.01, dragging = true))
            s.action(EditorAction.EndDrag)
            val end = SystemClock.uptimeMillis() + DRAFT_WAIT_MS
            while (!draftFile(id).exists() && SystemClock.uptimeMillis() < end) SystemClock.sleep(POLL_MS)
            assertWithMessage("draft within the 2 s autosave").that(draftFile(id).exists()).isTrue()
            var steps = 0
            s.onActivity {
                while (it.viewModel.state.value.canUndo && steps < 200) {
                    it.viewModel.onAction(EditorAction.Undo)
                    steps++
                }
                repeat(steps) { _ -> it.viewModel.onAction(EditorAction.Redo) }
            }
            assertThat(steps).isAtLeast(100)
        }
        // The activity went away without Save/Discard, as in process death: the draft stays and is offered.
        assertThat(draftFile(id).exists()).isTrue()
        launch(EditorRequest.Edit(id)).use { s ->
            s.await("draft offer") { it.draftOffer != null }
            s.action(EditorAction.RestoreDraft)
            val st = s.await("restored") { it.document?.icons?.sizeDp == 41 }
            assertThat(st.dirty).isTrue()
            s.action(EditorAction.LeaveDiscard)
        }
    }

    private fun fixtureImageUri(): Uri {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(32, 112, 208))
        c.drawRect(0f, 40f, 64f, 64f, Paint().apply { color = Color.rgb(230, 230, 230) })
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        return Uri.parse(runBlocking { context.appGraph.sharedFiles.publish("fixture.jpg", out.toByteArray()) }!!)
    }

    @Test
    fun tTheme38_createFromImageGivesAPassingSeedTheme() {
        val uri = fixtureImageUri()
        launch(EditorRequest.FromImage).use { s ->
            s.await("opened") { it.document != null }
            s.onActivity { it.onImagePicked(uri) }
            val st = s.await("seed theme") { it.document?.colors?.sourceKind == SourceKind.SEED && !it.busy }
            val doc = st.document!!
            assertThat(doc.colors.seedColor).isNotNull()
            assertThat(Contrast.staticCheck(doc).all { it.passes }).isTrue()
            for (dark in listOf(false, true)) {
                val r =
                    ThemeResolver.resolve(
                        doc,
                        PlatformInputs(apiLevel = android.os.Build.VERSION.SDK_INT, systemDark = dark),
                    )
                assertThat(Contrast.passes(r.theme.colors, WallpaperSpec())).isTrue()
            }
            s.action(EditorAction.LeaveDiscard)
        }
        val requested =
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                .orEmpty()
                .toSet()
        val storage =
            setOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                "android.permission.READ_MEDIA_IMAGES",
                "android.permission.READ_MEDIA_VIDEO",
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
                "android.permission.MANAGE_EXTERNAL_STORAGE",
            )
        assertThat(requested.intersect(storage)).isEmpty()
    }

    @Test
    fun tTheme51_editorFlowsMakeNoNetworkAccess() {
        val id = storeTheme(ID_51, "No network", ""","colors":{"mode":"light","light":{"textSecondary":"#BBBBBB"}}""")
        val instr = InstrumentationRegistry.getInstrumentation()
        // Prepared before StrictMode is armed (the copy is test set-up, not an editor flow).
        val uri = fixtureImageUri()
        val policy =
            StrictMode.ThreadPolicy
                .Builder()
                .detectNetwork()
                .penaltyDeath()
                .build()
        val oldThread = StrictMode.getThreadPolicy()
        val oldVm = StrictMode.getVmPolicy()
        var oldMain: StrictMode.ThreadPolicy? = null
        instr.runOnMainSync {
            oldMain = StrictMode.getThreadPolicy()
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy
                    .Builder(oldMain!!)
                    // The app's main thread: no network and no disk access at all during the editor flows.
                    .detectNetwork()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .penaltyDeath()
                    .build(),
            )
        }
        StrictMode.setThreadPolicy(policy)
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy
                .Builder()
                .detectCleartextNetwork()
                .penaltyDeathOnCleartextNetwork()
                .build(),
        )
        try {
            launch(EditorRequest.Edit(id)).use { s ->
                s.await("banner") { it.contrast?.failing?.isNotEmpty() == true }
                s.action(EditorAction.Set("colors.light.accent", "#224488"))
                s.action(EditorAction.Fix)
                s.await("fixed") { it.contrast?.allPass == true }
                s.action(EditorAction.OpenJson)
                s.action(EditorAction.CloseJson)
                s.action(EditorAction.KeepImageAsWallpaper(true))
                s.onActivity { it.onImagePicked(uri) }
                s.await("seed") { it.document?.colors?.sourceKind == SourceKind.SEED && !it.busy }
                s.action(EditorAction.Apply)
                s.await("saved") { !it.dirty }
            }
        } finally {
            instr.runOnMainSync { oldMain?.let(StrictMode::setThreadPolicy) }
            StrictMode.setThreadPolicy(oldThread)
            StrictMode.setVmPolicy(oldVm)
        }
    }

    private companion object {
        const val ID_37 = "aaaaaaaa-0000-4000-8000-000000000037"
        const val ID_51 = "aaaaaaaa-0000-4000-8000-000000000051"
        const val IMAGE_THEME_PREFIX = "Theme from image"
        const val TIMEOUT_MS = 15_000L
        const val DRAFT_WAIT_MS = 6_000L
        const val POLL_MS = 50L
    }
}
