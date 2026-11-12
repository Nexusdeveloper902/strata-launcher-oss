package app.strata.launcher.widget

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.VisibleForTesting
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.strata.launcher.appGraph
import app.strata.launcher.core.model.WidgetProviderRef
import app.strata.launcher.feature.widgets.WidgetPickerEffect
import app.strata.launcher.feature.widgets.WidgetPickerScreen
import app.strata.launcher.feature.widgets.WidgetPickerViewModel
import app.strata.launcher.feature.widgets.WidgetPreviewSource
import app.strata.launcher.theme.ThemeHost
import kotlinx.coroutines.launch
import app.strata.launcher.feature.widgets.R as WidgetsR

/**
 * S-WPICK and the bind flow of J-10 (FR-WIDGET-02), started from the home menu, the widget menu's Replace, or the
 * placeholder's Re-add (with the provider in the extras). Not exported. The consent dialog (`ACTION_APPWIDGET_BIND`)
 * runs through an activity-result launcher, the configure activity through
 * `AppWidgetHost.startAppWidgetConfigureActivityForResult` (required for providers in another profile), whose answer
 * arrives in [onActivityResult]. Both answers reach the view model, whose step in flight lives in its
 * `SavedStateHandle`, so they survive recreation and process death. Finishing with a step in flight frees its ID.
 */
class WidgetPickerActivity : ComponentActivity() {
    private val readd: WidgetProviderRef? by lazy {
        val component = intent.getStringExtra(EXTRA_COMPONENT)
        val serial = intent.getLongExtra(EXTRA_USER_SERIAL, -1L)
        if (component.isNullOrEmpty() || serial < 0) null else WidgetProviderRef(component, serial)
    }

    private val viewModel: WidgetPickerViewModel by viewModels {
        viewModelFactory {
            initializer {
                val graph = appGraph
                WidgetPickerViewModel(
                    host = graph.widgetHost,
                    repository = graph.widgets,
                    handle = createSavedStateHandle(),
                    logger = graph.logger,
                    cleanup = graph.appScope,
                    readd = readd,
                )
            }
        }
    }

    private val consent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            viewModel.onConsentResult(result.resultCode == Activity.RESULT_OK)
        }

    private val themeHost by lazy { ThemeHost(appGraph.themeRepository) }
    private val snackbar = SnackbarHostState()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        appGraph.startTheme()
        themeHost.holdFirstFrame(window.decorView)
        val graph = appGraph
        val previews =
            object : WidgetPreviewSource {
                override suspend fun preview(
                    ref: WidgetProviderRef,
                    maxWidthPx: Int,
                    maxHeightPx: Int,
                ) = graph.widgetHost.preview(ref, maxWidthPx, maxHeightPx)

                override suspend fun icon(
                    ref: WidgetProviderRef,
                    sizePx: Int,
                ) = graph.widgetHost.icon(ref, sizePx)
            }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effects.collect(::handle)
            }
        }
        setContent {
            themeHost.Content(window) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                LaunchedEffect(state.done) { if (state.done) finish() }
                WidgetPickerScreen(
                    state = state,
                    onAction = viewModel::onAction,
                    previews = previews,
                    onBack = ::finish,
                    snackbar = snackbar,
                )
            }
        }
    }

    private suspend fun handle(effect: WidgetPickerEffect) {
        when (effect) {
            is WidgetPickerEffect.LaunchConsent -> {
                val request = appGraph.widgetHost.consentRequest(effect.id, effect.ref) as? Intent
                val started =
                    request != null &&
                        try {
                            consent.launch(request)
                            true
                        } catch (e: ActivityNotFoundException) {
                            appGraph.logger.w(TAG, "no consent activity", e)
                            false
                        }
                if (!started) viewModel.onConsentResult(false)
            }

            is WidgetPickerEffect.LaunchConfigure -> {
                if (!appGraph.widgetHost.startConfigure(this, effect.id, REQUEST_CONFIGURE)) {
                    viewModel.onConfigureResult(false)
                }
            }

            WidgetPickerEffect.NotAdded -> {
                lifecycleScope.launch { snackbar.showSnackbar(getString(WidgetsR.string.widget_not_added)) }
            }
        }
    }

    @Deprecated("The configure activity is started by AppWidgetHost with a request code")
    @Suppress("DEPRECATION")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        if (requestCode == REQUEST_CONFIGURE) {
            deliverConfigureResult(resultCode)
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    /** ANDROID_PLATFORM §7: only OK counts; cancel, RESULT_FIRST_USER and a crashed activity are a cancel. */
    @VisibleForTesting
    internal fun deliverConfigureResult(resultCode: Int) {
        viewModel.onConfigureResult(resultCode == Activity.RESULT_OK)
    }

    override fun onStop() {
        themeHost.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) viewModel.abandon()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WidgetPicker"
        private const val REQUEST_CONFIGURE = 0x5701
        private const val EXTRA_COMPONENT = "app.strata.launcher.extra.WIDGET_PROVIDER"
        private const val EXTRA_USER_SERIAL = "app.strata.launcher.extra.WIDGET_USER_SERIAL"

        /** The picker; with [readd] the bind flow for that provider starts at once (FR-WIDGET-04 "Re-add"). */
        fun intent(
            context: Context,
            readd: WidgetProviderRef? = null,
        ): Intent =
            Intent(context, WidgetPickerActivity::class.java).apply {
                if (readd != null) {
                    putExtra(EXTRA_COMPONENT, readd.component)
                    putExtra(EXTRA_USER_SERIAL, readd.userSerial)
                }
            }
    }
}
