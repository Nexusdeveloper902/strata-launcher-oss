package app.strata.launcher.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.model.IconCoverageEntry
import app.strata.launcher.core.model.IconSource
import app.strata.launcher.core.model.IconStyle
import app.strata.launcher.core.model.IconTintMode
import app.strata.launcher.core.platformapi.apps.AppListSource
import app.strata.launcher.core.platformapi.icons.IconProvider
import app.strata.launcher.core.platformapi.system.SystemIntents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Settings -> Icons (FR-SET-07): every launchable app with its icon source under the current theme. */
data class IconCoverageState(
    val loading: Boolean = true,
    /** THEME_SYSTEM §4.6: the theme tints icons (`monochrome`/`accent`, or `pack.fallback: monochrome`). */
    val tinted: Boolean = false,
    val entries: List<IconCoverageEntry> = emptyList(),
    /** "Export list" covers every app instead of only the desaturated ones. */
    val exportAll: Boolean = false,
) {
    /** Count per source, in precedence order, sources with no app left out. */
    val counts: List<Pair<IconSource, Int>>
        get() =
            IconSource.entries.mapNotNull { source ->
                entries.count { it.source == source }.takeIf { it > 0 }?.let { source to it }
            }

    /** Highlighted rows: apps on the desaturated fallback while the theme tints (no highlights with tint `none`). */
    fun highlighted(entry: IconCoverageEntry): Boolean = tinted && entry.source == IconSource.DESATURATED

    /** The plain-text export: one "Label (package)" line per app; by default only the desaturated ones. */
    val exportLines: List<String>
        get() = entries.filter { exportAll || highlighted(it) }.map { "${it.label} (${it.packageName})" }
}

sealed interface IconCoverageAction {
    /** The page is shown with the resolved theme's [style]; a new style (theme switch) recomputes the list. */
    data class Show(val style: IconStyle) : IconCoverageAction

    data class SetExportAll(val all: Boolean) : IconCoverageAction

    /** "Export list": the share sheet titled [title] with [IconCoverageState.exportLines]. */
    data class Export(val title: String) : IconCoverageAction
}

/**
 * FR-SET-07 / D-014. The sources come from [IconProvider.coverage], which shares the renderer's precedence, so the
 * page cannot disagree with home. Read-only: no setting, no file, no network; the export goes through the share sheet.
 */
class IconCoverageViewModel(
    private val appList: AppListSource,
    private val icons: IconProvider,
    private val systemIntents: SystemIntents,
    private val logger: Logger,
) : ViewModel() {
    private val style = MutableStateFlow<IconStyle?>(null)
    private val local = MutableStateFlow(IconCoverageState())
    private val effectChannel = Channel<SettingsEffect>(Channel.BUFFERED)

    val state: StateFlow<IconCoverageState> = local.asStateFlow()

    /** [SettingsEffect.NoHandler] when no app takes the text. */
    val effects: Flow<SettingsEffect> = effectChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            combine(style.filterNotNull(), appList.appList) { s, list -> s to list.allApps }
                .collectLatest { (s, apps) ->
                    local.update { it.copy(loading = true, tinted = isTinted(s)) }
                    val entries =
                        try {
                            icons.coverage(apps, s)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.w(TAG, "coverage failed", e)
                            emptyList()
                        }
                    local.update { it.copy(loading = false, tinted = isTinted(s), entries = entries) }
                }
        }
    }

    fun onAction(action: IconCoverageAction) {
        when (action) {
            is IconCoverageAction.Show -> {
                style.value = action.style
            }

            is IconCoverageAction.SetExportAll -> {
                local.update { it.copy(exportAll = action.all) }
            }

            is IconCoverageAction.Export -> {
                val lines = local.value.exportLines
                if (lines.isEmpty()) return
                if (systemIntents.shareText(lines.joinToString("\n"), action.title) !is Outcome.Ok) {
                    effectChannel.trySend(SettingsEffect.NoHandler)
                }
            }
        }
    }

    private fun isTinted(s: IconStyle): Boolean = s.tint != IconTintMode.NONE || s.packFallbackMonochrome

    private companion object {
        const val TAG = "IconCoverage"
    }
}
