package app.strata.launcher.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.model.IconCoverageEntry
import app.strata.launcher.core.model.IconSource
import app.strata.launcher.core.model.IconStyle
import app.strata.launcher.core.model.IconTintMode
import app.strata.launcher.core.model.VisibleApp
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

    /**
     * The plain-text export: one "Label (package)" line per app, by default only the desaturated ones. The label is
     * the system label, not a custom name: the glyph author needs the real app name with the package.
     */
    val exportLines: List<String>
        get() = entries.filter { exportAll || highlighted(it) }.map { "${it.systemLabel} (${it.packageName})" }
}

sealed interface IconCoverageAction {
    /** The page is shown with the resolved theme's [style]; a new style (theme switch) recomputes the list. */
    data class Show(val style: IconStyle) : IconCoverageAction

    /** The page left: stop recomputing (any load in progress is cancelled). */
    data object Hide : IconCoverageAction

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
            // Inputs that can change a source: the theme's style, the apps' identity/labels/overrides/updates (not
            // badges), and the icon revision (a pack parsed, installed, updated or removed). Null style = page gone.
            combine(
                style,
                appList.appList.map { list -> list.allApps.map(::CoverageInput) }.distinctUntilChanged(),
                icons.revision,
            ) { s, inputs, _ -> s to inputs }
                .collectLatest { (s, inputs) ->
                    if (s == null) return@collectLatest
                    // Keep the rows (and Export) while recomputing; "loading" only before the first result.
                    local.update { it.copy(loading = it.entries.isEmpty(), tinted = isTinted(s)) }
                    val entries =
                        try {
                            icons.coverage(inputs.map { it.app }, s)
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

    /** The part of an app the coverage depends on; equality ignores badges and other display-only fields. */
    private class CoverageInput(
        val app: VisibleApp,
    ) {
        private val id =
            listOf(
                app.key,
                app.packageName,
                app.displayLabel,
                app.systemLabel,
                app.profileKind,
                app.iconOverride,
                app.lastUpdateTime,
            )

        override fun equals(other: Any?): Boolean = other is CoverageInput && other.id == id

        override fun hashCode(): Int = id.hashCode()
    }

    fun onAction(action: IconCoverageAction) {
        when (action) {
            is IconCoverageAction.Show -> {
                style.value = action.style
            }

            IconCoverageAction.Hide -> {
                style.value = null
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
