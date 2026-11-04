package app.strata.launcher.theme

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.strata.launcher.appGraph
import app.strata.launcher.core.theme.importing.ImportMessage
import app.strata.launcher.feature.theme.ThemeImportScreen
import app.strata.launcher.feature.theme.ThemeImportViewModel

/**
 * Theme import from other apps and from the in-app picker (THEME_SYSTEM §10.1, FR-THEME-21). `ACTION_VIEW` carries the
 * URI in the data, `ACTION_SEND` in `EXTRA_STREAM`; only `content:` URIs are read (ARCHITECTURE §13, T-SEC-01): anything
 * else is refused with a message before any byte is opened. The summary is always shown first; nothing is applied
 * until the user confirms.
 */
class ThemeImportActivity : ComponentActivity() {
    private val viewModel: ThemeImportViewModel by viewModels {
        viewModelFactory {
            initializer {
                val graph = appGraph
                ThemeImportViewModel(
                    manager = graph.themeManager,
                    themes = graph.themeRepository,
                    documents = graph.documents,
                    sharedFiles = graph.sharedFiles,
                    systemIntents = graph.systemIntents,
                )
            }
        }
    }

    private val themeHost by lazy { ThemeHost(appGraph.themeRepository) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        appGraph.startTheme()
        when (val source = ImportSource.of(intent?.action, intent?.data, streamOf(intent))) {
            is ImportSource.Accepted -> viewModel.load(source.uri)
            ImportSource.Refused -> viewModel.refuse(ImportMessage.UNSUPPORTED_SOURCE)
            ImportSource.Missing -> viewModel.refuse(ImportMessage.NOT_A_THEME)
        }
        themeHost.holdFirstFrame(window.decorView)
        setContent {
            themeHost.Content(window) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val finished by viewModel.finished.collectAsStateWithLifecycle()
                LaunchedEffect(finished) { if (finished) finish() }
                ThemeImportScreen(state, viewModel::onAction, onClose = ::finish)
            }
        }
    }

    override fun onStop() {
        themeHost.onStop()
        super.onStop()
    }

    @Suppress("DEPRECATION")
    private fun streamOf(intent: Intent?): Uri? {
        if (intent?.action != Intent.ACTION_SEND) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
    }
}

/** Which URI an import intent names, and whether it may be read (`content:` only). */
sealed interface ImportSource {
    data class Accepted(val uri: String) : ImportSource

    /** A `file:`, `http:` or other non-content URI: refused unread. */
    data object Refused : ImportSource

    data object Missing : ImportSource

    companion object {
        fun of(
            action: String?,
            data: Uri?,
            stream: Uri?,
        ): ImportSource {
            val uri =
                when (action) {
                    Intent.ACTION_VIEW -> data
                    Intent.ACTION_SEND -> stream
                    else -> null
                } ?: return Missing
            return if (uri.scheme == "content" && !uri.authority.isNullOrEmpty()) Accepted(uri.toString()) else Refused
        }
    }
}
