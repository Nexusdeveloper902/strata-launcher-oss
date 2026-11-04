package app.strata.launcher.theme

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.strata.launcher.R
import app.strata.launcher.appGraph
import app.strata.launcher.feature.theme.LibraryAction
import app.strata.launcher.feature.theme.ThemeLibraryRoute
import app.strata.launcher.feature.theme.ThemeLibraryViewModel

/**
 * The theme library (FR-THEME-27), opened from the home menu's "Themes". In-app import uses the Storage Access
 * Framework with the four types of THEME_SYSTEM §3.1 (`text/plain` only here) and hands the picked document to
 * [ThemeImportActivity]; export uses `ACTION_CREATE_DOCUMENT`. Not exported.
 */
class ThemesActivity : ComponentActivity() {
    private val viewModel: ThemeLibraryViewModel by viewModels {
        viewModelFactory {
            initializer {
                val graph = appGraph
                ThemeLibraryViewModel(
                    manager = graph.themeManager,
                    themes = graph.themeRepository,
                    documents = graph.documents,
                    sharedFiles = graph.sharedFiles,
                    systemIntents = graph.systemIntents,
                    appScope = graph.appScope,
                    shareTitle = getString(R.string.theme_share_chooser),
                )
            }
        }
    }

    private val themeHost by lazy { ThemeHost(appGraph.themeRepository) }

    private val pickImport =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::openImport) }

    private val createJson =
        registerForActivityResult(ActivityResultContracts.CreateDocument(MIME_JSON)) { uri ->
            viewModel.onAction(LibraryAction.ExportTo(uri?.toString()))
        }

    private val createZip =
        registerForActivityResult(ActivityResultContracts.CreateDocument(MIME_ZIP)) { uri ->
            viewModel.onAction(LibraryAction.ExportTo(uri?.toString()))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        appGraph.startTheme()
        themeHost.holdFirstFrame(window.decorView)
        setContent {
            themeHost.Content(window) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                ThemeLibraryRoute(
                    state = state,
                    effects = viewModel.effects,
                    onAction = viewModel::onAction,
                    onPickImport = { launchSafely { pickImport.launch(IMPORT_TYPES) } },
                    onCreateDocument = { name, mime ->
                        val started =
                            launchSafely { if (mime == MIME_ZIP) createZip.launch(name) else createJson.launch(name) }
                        if (!started) viewModel.onAction(LibraryAction.ExportUnavailable)
                    },
                    onBack = ::finish,
                )
            }
        }
    }

    override fun onStop() {
        themeHost.onStop()
        super.onStop()
    }

    /** Starts a SAF intent; false when no document provider handles it (rare, stripped devices). */
    private fun launchSafely(block: () -> Unit): Boolean =
        try {
            block()
            true
        } catch (e: android.content.ActivityNotFoundException) {
            appGraph.logger.w(TAG, "no SAF handler", e)
            false
        }

    private fun openImport(uri: Uri) {
        startActivity(
            Intent(Intent.ACTION_VIEW, uri)
                .setClass(this, ThemeImportActivity::class.java)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    companion object {
        private const val TAG = "Themes"
        const val MIME_JSON = "application/json"
        const val MIME_ZIP = "application/zip"

        /** THEME_SYSTEM §3.1, in-app import (the only entry point that accepts `text/plain`). */
        val IMPORT_TYPES = arrayOf(MIME_JSON, MIME_ZIP, "application/octet-stream", "text/plain")
    }
}
