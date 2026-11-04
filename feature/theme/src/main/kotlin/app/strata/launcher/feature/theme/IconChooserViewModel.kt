package app.strata.launcher.feature.theme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.IconOverride
import app.strata.launcher.core.model.IconPackInfo
import app.strata.launcher.core.model.IconResult
import app.strata.launcher.core.model.PackDrawables
import app.strata.launcher.core.model.VisibleApp
import app.strata.launcher.core.platformapi.apps.AppListSource
import app.strata.launcher.core.platformapi.apps.OverridesRepository
import app.strata.launcher.core.platformapi.icons.IconProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** S-ICON state (FR-APPS-06). */
data class IconChooserState(
    /** Null while the list loads, or when the app is gone ([appMissing]). */
    val app: VisibleApp? = null,
    val appMissing: Boolean = false,
    /** Every discovered pack; unusable ones are shown but cannot be opened (NFR-COMPAT-04). */
    val packs: List<IconPackInfo> = emptyList(),
    val packsDiscovered: Boolean = false,
    val selectedPack: String? = null,
    val drawables: PackDrawables? = null,
    val loadingDrawables: Boolean = false,
    val query: String = "",
    /** The choice was saved: the screen closes. */
    val done: Boolean = false,
    val writeFailed: Boolean = false,
) {
    /** Drawables of the selected pack matching [query], the pack's own mapping for the app first. */
    val visibleDrawables: List<String>
        get() {
            val d = drawables ?: return emptyList()
            val q = query.trim().lowercase().replace(' ', '_')
            val all = listOfNotNull(d.suggested) + d.drawables.filter { it != d.suggested }
            val filtered = if (q.isEmpty()) all else all.filter { it.lowercase().contains(q) }
            return filtered.take(MAX_SHOWN)
        }

    companion object {
        /** Grid cap; a search narrows larger packs. */
        const val MAX_SHOWN = 2_000
    }
}

sealed interface IconChooserAction {
    data class SelectPack(val packageName: String) : IconChooserAction

    data class Query(val text: String) : IconChooserAction

    /** Use [drawable] of the selected pack for the app. */
    data class Choose(val drawable: String) : IconChooserAction

    /** "Theme icon": removes the override (FR-APPS-06 reset). */
    data object UseThemeIcon : IconChooserAction
}

/**
 * S-ICON (INTERACTION_SPEC §1, FR-APPS-06): pick an icon from any installed, usable pack, or reset to the theme's
 * icon. The override is global (survives theme switches, FR-THEME-31) and stored in `app_override`.
 */
class IconChooserViewModel(
    private val appKey: AppKey,
    appList: AppListSource,
    private val overrides: OverridesRepository,
    private val icons: IconProvider,
    private val logger: Logger,
) : ViewModel() {
    private val local = MutableStateFlow(IconChooserState())
    private var drawablesJob: Job? = null

    val state: StateFlow<IconChooserState> =
        combine(
            appList.appList.map { list -> list.allApps.firstOrNull { it.key == appKey } to !list.fromSnapshot },
            icons.iconPacks,
            local,
        ) { (app, settled), packs, l ->
            l.copy(
                app = app,
                appMissing = app == null && settled,
                packs = packs.packs,
                packsDiscovered = packs.discovered,
            )
        }.catch { e ->
            logger.w(TAG, "icon chooser state failed", e)
            emit(local.value.copy(appMissing = true))
        }.stateIn(viewModelScope, SharingStarted.Eagerly, IconChooserState())

    init {
        icons.refreshPacks()
        // Open the pack the app's override comes from, else the first usable pack. Decided only once both the app
        // (with its override) and the discovered packs are known, so a late app list cannot open the wrong pack.
        viewModelScope.launch {
            state.collect { s ->
                if (local.value.selectedPack != null || !s.packsDiscovered || s.app == null) return@collect
                val current = s.app.iconOverride?.packPackage
                val first =
                    s.packs.firstOrNull { it.packageName == current && it.usable }
                        ?: s.packs.firstOrNull { it.usable }
                first?.let { select(it.packageName) }
            }
        }
    }

    fun onAction(action: IconChooserAction) {
        when (action) {
            is IconChooserAction.SelectPack -> select(action.packageName)
            is IconChooserAction.Query -> local.update { it.copy(query = action.text.take(MAX_QUERY)) }
            is IconChooserAction.Choose -> save(local.value.selectedPack?.let { IconOverride(it, action.drawable) })
            IconChooserAction.UseThemeIcon -> save(null)
        }
    }

    /** One pack icon for the grid (drawn as supplied). */
    suspend fun packIcon(
        pack: String,
        drawable: String,
        sizePx: Int,
        densityDpi: Int,
    ): IconResult = icons.packIcon(pack, drawable, sizePx, densityDpi)

    private fun select(pack: String) {
        if (icons.iconPacks.value
                .find(pack)
                ?.usable != true
        ) {
            return
        }
        drawablesJob?.cancel()
        local.update { it.copy(selectedPack = pack, drawables = null, loadingDrawables = true, query = "") }
        drawablesJob =
            viewModelScope.launch {
                val list =
                    try {
                        icons.packDrawables(pack, appKey.component)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.w(TAG, "pack drawables failed", e)
                        null
                    }
                local.update {
                    if (it.selectedPack != pack) {
                        it
                    } else {
                        it.copy(drawables = list ?: PackDrawables(pack, emptyList()), loadingDrawables = false)
                    }
                }
            }
    }

    private fun save(icon: IconOverride?) {
        val app = state.value.app ?: return
        viewModelScope.launch {
            try {
                overrides.setIconOverride(app.key, app.profileKind, icon)
                local.update { it.copy(done = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Any failure (disk full, corrupt database, a bad value) is a message, never a crash.
                logger.w(TAG, "icon override not saved", e)
                local.update { it.copy(writeFailed = true) }
            }
        }
    }

    private companion object {
        const val TAG = "IconChooser"
        const val MAX_QUERY = 64
    }
}
