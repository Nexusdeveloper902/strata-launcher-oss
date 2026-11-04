package app.strata.launcher.theme

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.strata.launcher.appGraph
import app.strata.launcher.feature.theme.EditorAction
import app.strata.launcher.feature.theme.EditorRequest
import app.strata.launcher.feature.theme.ThemeEditorRoute
import app.strata.launcher.feature.theme.ThemeEditorViewModel
import kotlinx.coroutines.flow.map
import app.strata.launcher.feature.theme.R as ThemeR

/**
 * The theme editor (THEME_SYSTEM §13, FR-THEME-29/30), started from the theme library. Not exported. Create-from-image
 * uses the system photo picker (`PickVisualMedia`, images only), which needs no storage permission; on devices without
 * it the contract falls back to the document picker. The picked URI reaches [onImagePicked], the seam the device test
 * drives (the picker UI itself is a manual check).
 */
class ThemeEditorActivity : ComponentActivity() {
    private val request: EditorRequest by lazy { requestOf(intent) }

    internal val viewModel: ThemeEditorViewModel by viewModels {
        viewModelFactory {
            initializer {
                val graph = appGraph
                ThemeEditorViewModel(
                    store = graph.themeEditor,
                    themes = graph.themeRepository,
                    documents = graph.documents,
                    handle = createSavedStateHandle(),
                    request = request,
                    appLabels =
                        graph.appList.appList.map { list ->
                            list.sections
                                .asSequence()
                                .flatMap { it.apps.asSequence() }
                                .map { it.displayLabel }
                                .take(
                                    PREVIEW_APPS,
                                ).toList()
                        },
                    newThemeName = getString(ThemeR.string.editor_new_theme_name),
                    imageThemeName = getString(ThemeR.string.editor_image_theme_name),
                )
            }
        }
    }

    private val themeHost by lazy { ThemeHost(appGraph.themeRepository) }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> onImagePicked(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        appGraph.startTheme()
        appGraph.startCatalog()
        themeHost.holdFirstFrame(window.decorView)
        setContent {
            themeHost.Content(window) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                ThemeEditorRoute(
                    state = state,
                    effects = viewModel.effects,
                    onAction = viewModel::onAction,
                    onPickImage = ::launchPicker,
                    onDone = ::finish,
                )
            }
        }
    }

    private fun launchPicker() {
        try {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (e: android.content.ActivityNotFoundException) {
            appGraph.logger.w(TAG, "no image picker", e)
            onImagePicked(null)
        }
    }

    /** The photo picker's result (null when cancelled); the create-from-image seam (T-THEME-38). */
    fun onImagePicked(uri: Uri?) {
        viewModel.onAction(EditorAction.ImagePicked(uri?.toString()))
    }

    override fun onStop() {
        viewModel.onAction(EditorAction.Pause)
        themeHost.onStop()
        super.onStop()
    }

    companion object {
        private const val TAG = "ThemeEditor"
        private const val PREVIEW_APPS = 12
        const val EXTRA_MODE = "app.strata.launcher.extra.EDITOR_MODE"
        const val EXTRA_ID = "app.strata.launcher.extra.EDITOR_ID"
        private const val MODE_EDIT = "edit"
        private const val MODE_NEW = "new"
        private const val MODE_IMAGE = "image"
        private const val MODE_DRAFT = "draft"

        fun intent(
            context: Context,
            request: EditorRequest,
        ): Intent {
            val i = Intent(context, ThemeEditorActivity::class.java)
            return when (request) {
                is EditorRequest.Edit -> i.putExtra(EXTRA_MODE, MODE_EDIT).putExtra(EXTRA_ID, request.id)
                EditorRequest.New -> i.putExtra(EXTRA_MODE, MODE_NEW)
                EditorRequest.FromImage -> i.putExtra(EXTRA_MODE, MODE_IMAGE)
                is EditorRequest.Draft -> i.putExtra(EXTRA_MODE, MODE_DRAFT).putExtra(EXTRA_ID, request.sessionId)
            }
        }

        /** Unknown or incomplete extras open a new theme (nothing is ever edited by surprise). */
        fun requestOf(intent: Intent?): EditorRequest {
            val id = intent?.getStringExtra(EXTRA_ID)
            return when (intent?.getStringExtra(EXTRA_MODE)) {
                MODE_EDIT -> id?.let { EditorRequest.Edit(it) } ?: EditorRequest.New
                MODE_IMAGE -> EditorRequest.FromImage
                MODE_DRAFT -> id?.let { EditorRequest.Draft(it) } ?: EditorRequest.New
                else -> EditorRequest.New
            }
        }
    }
}
