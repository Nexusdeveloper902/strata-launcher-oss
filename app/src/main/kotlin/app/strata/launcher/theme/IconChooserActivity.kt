package app.strata.launcher.theme

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.strata.launcher.appGraph
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.ui.LocalIconLoader
import app.strata.launcher.feature.theme.IconChooserScreen
import app.strata.launcher.feature.theme.IconChooserViewModel
import app.strata.launcher.feature.theme.PackIconSource
import app.strata.launcher.home.ProviderIconLoader

/**
 * S-ICON (FR-APPS-06), started from the options sheet's "Change icon" with the app's key in the extras. Not exported;
 * finishes once the choice is saved. A missing or malformed key finishes at once.
 */
class IconChooserActivity : ComponentActivity() {
    private val appKey: AppKey? by lazy {
        val component = intent.getStringExtra(EXTRA_COMPONENT)
        val serial = intent.getLongExtra(EXTRA_USER_SERIAL, -1L)
        if (component.isNullOrEmpty() || serial < 0) null else AppKey(component, serial)
    }

    private val viewModel: IconChooserViewModel by viewModels {
        viewModelFactory {
            initializer {
                val graph = appGraph
                IconChooserViewModel(
                    appKey = checkNotNull(appKey),
                    appList = graph.appList,
                    overrides = graph.overrides,
                    icons = graph.iconProvider,
                    logger = graph.logger,
                )
            }
        }
    }

    private val themeHost by lazy { ThemeHost(appGraph.themeRepository) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (appKey == null) {
            finish()
            return
        }
        appGraph.startTheme()
        appGraph.startCatalog()
        themeHost.holdFirstFrame(window.decorView)
        val iconLoader = ProviderIconLoader(appGraph.iconProvider)
        val packIcons = PackIconSource { pack, drawable, px, dpi -> viewModel.packIcon(pack, drawable, px, dpi) }
        setContent {
            themeHost.Content(window) {
                CompositionLocalProvider(LocalIconLoader provides iconLoader) {
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    LaunchedEffect(state.done, state.appMissing) {
                        if (state.done || state.appMissing) finish()
                    }
                    IconChooserScreen(state = state, onAction = viewModel::onAction, icons = packIcons)
                }
            }
        }
    }

    override fun onStop() {
        themeHost.onStop()
        super.onStop()
    }

    companion object {
        const val EXTRA_COMPONENT = "app.strata.launcher.extra.COMPONENT"
        const val EXTRA_USER_SERIAL = "app.strata.launcher.extra.USER_SERIAL"

        fun intent(
            context: Context,
            key: AppKey,
        ): Intent =
            Intent(context, IconChooserActivity::class.java)
                .putExtra(EXTRA_COMPONENT, key.component)
                .putExtra(EXTRA_USER_SERIAL, key.userSerial)
    }
}
