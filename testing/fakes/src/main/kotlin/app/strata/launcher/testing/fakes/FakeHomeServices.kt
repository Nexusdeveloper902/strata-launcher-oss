package app.strata.launcher.testing.fakes

import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.AppOverride
import app.strata.launcher.core.model.IconCoverageEntry
import app.strata.launcher.core.model.IconImage
import app.strata.launcher.core.model.IconOverride
import app.strata.launcher.core.model.IconPackInfo
import app.strata.launcher.core.model.IconPacks
import app.strata.launcher.core.model.IconRequest
import app.strata.launcher.core.model.IconResult
import app.strata.launcher.core.model.IconSource
import app.strata.launcher.core.model.IconStyle
import app.strata.launcher.core.model.IconTintMode
import app.strata.launcher.core.model.PackDrawables
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.model.VisibleApp
import app.strata.launcher.core.platformapi.apps.AddFavoriteResult
import app.strata.launcher.core.platformapi.apps.FavoritesRepository
import app.strata.launcher.core.platformapi.apps.LabelRules
import app.strata.launcher.core.platformapi.apps.OverridesRepository
import app.strata.launcher.core.platformapi.icons.IconProvider
import app.strata.launcher.core.platformapi.system.LockService
import app.strata.launcher.core.platformapi.system.TimeTicks
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update

/** In-memory [OverridesRepository] with the same PRIVATE guard and label rules as the Room one. */
class FakeOverridesRepository(initial: Map<AppKey, AppOverride> = emptyMap()) : OverridesRepository {
    val state = MutableStateFlow(initial)

    override val overrides: StateFlow<Map<AppKey, AppOverride>> = state

    override suspend fun setHidden(
        key: AppKey,
        profileKind: ProfileKind,
        hidden: Boolean,
    ) {
        if (profileKind == ProfileKind.PRIVATE) return
        edit(key) { it.copy(hidden = hidden) }
    }

    override suspend fun setCustomLabel(
        key: AppKey,
        profileKind: ProfileKind,
        label: String?,
    ) {
        if (profileKind == ProfileKind.PRIVATE) return
        edit(key) { it.copy(customLabel = label?.let(LabelRules::normalize)) }
    }

    override suspend fun setIconOverride(
        key: AppKey,
        profileKind: ProfileKind,
        icon: IconOverride?,
    ) {
        if (profileKind == ProfileKind.PRIVATE) return
        edit(key) { it.copy(icon = icon) }
    }

    private fun edit(
        key: AppKey,
        transform: (AppOverride) -> AppOverride,
    ) = state.update { map ->
        val next = transform(map[key] ?: AppOverride(key))
        if (!next.hidden && next.customLabel == null && next.icon == null) map - key else map + (key to next)
    }
}

/** In-memory [FavoritesRepository] with the repository's cap rule. */
class FakeFavoritesRepository(initial: List<AppKey> = emptyList()) : FavoritesRepository {
    val state = MutableStateFlow(initial)

    override val favorites: StateFlow<List<AppKey>> = state

    override suspend fun add(
        key: AppKey,
        isShown: (AppKey) -> Boolean,
    ): AddFavoriteResult {
        val current = state.value
        return when {
            key in current -> AddFavoriteResult.ALREADY_FAVORITE
            !isShown(key) -> AddFavoriteResult.NOT_SHOWN
            current.count(isShown) >= FavoritesRepository.MAX -> AddFavoriteResult.FULL
            else -> AddFavoriteResult.ADDED.also { state.value = current + key }
        }
    }

    override suspend fun remove(key: AppKey) = state.update { it - key }

    override suspend fun replace(
        old: AppKey,
        new: AppKey,
        isShown: (AppKey) -> Boolean,
    ) = state.update { current ->
        val without = current.filterNot { it == new }
        val i = without.indexOf(old)
        when {
            old == new -> current
            i >= 0 -> without.toMutableList().also { it[i] = new }
            new in current -> current
            !isShown(new) || current.count(isShown) >= FavoritesRepository.MAX -> current
            else -> current + new
        }
    }

    override suspend fun reorder(order: List<AppKey>) =
        state.update { current ->
            val ordered = order.distinct().filter { it in current }
            ordered + current.filterNot { it in ordered }
        }

    var saveEditCalls: Int = 0
        private set

    override suspend fun saveEdit(
        order: List<AppKey>,
        edited: Set<AppKey>,
    ) {
        saveEditCalls++
        state.update { FavoritesRepository.merge(it, order, edited) }
    }
}

/** Ticks on demand. */
class FakeTimeTicks : TimeTicks {
    private val events = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    override val ticks: Flow<Unit> = events.onStart { emit(Unit) }

    fun tick() {
        events.tryEmit(Unit)
    }
}

/** Records lock requests; [enabled] is set by tests. */
class FakeLockService(
    override val supported: Boolean = true,
    enabled: Boolean = false,
    /** False = enabled in the system settings but the service has not connected yet. */
    var connected: Boolean = true,
) : LockService {
    override val enabled = MutableStateFlow(enabled)
    var locks: Int = 0
        private set

    override fun refresh() = Unit

    override fun lock(): Boolean {
        if (!enabled.value || !connected) return false
        locks++
        return true
    }
}

/**
 * Always the monogram (pure JVM: no bitmaps); counts requests. [iconPacks] and [drawables] are set by tests; the icon
 * chooser and the pack inputs of the theme read them.
 */
class FakeIconProvider(
    packs: IconPacks = IconPacks(discovered = true),
) : IconProvider {
    val requests = mutableListOf<IconRequest>()

    override val iconPacks = MutableStateFlow(packs)

    override val revision = MutableStateFlow(0L)

    /** pack → (drawables, component → suggested). */
    val drawables = mutableMapOf<String, Pair<List<String>, Map<String, String>>>()

    var refreshes: Int = 0
        private set

    override fun cached(req: IconRequest): IconImage? = null

    override suspend fun icon(req: IconRequest): IconResult {
        requests += req
        return IconResult.Monogram
    }

    override fun trim(critical: Boolean) = Unit

    override fun start() = Unit

    override fun refreshPacks() {
        refreshes++
    }

    override suspend fun packDrawables(
        pack: String,
        component: String?,
    ): PackDrawables? {
        if (iconPacks.value.find(pack)?.usable != true) return null
        val (names, mapping) = drawables[pack] ?: return PackDrawables(pack, emptyList())
        return PackDrawables(pack, names, component?.let { mapping[it] })
    }

    override suspend fun packIcon(
        pack: String,
        drawable: String,
        sizePx: Int,
        densityDpi: Int,
    ): IconResult = IconResult.Monogram

    /**
     * FR-SET-07: the source per component when the icon is tinted. Override and pack sources stay under any tint;
     * glyph, monochrome and desaturated become `PLAIN` when the style is untinted (THEME_SYSTEM §4.6). Unlisted
     * components are desaturated (tinted) or plain.
     */
    val coverageSources = mutableMapOf<String, IconSource>()

    /** Styles of every coverage call. */
    val coverageCalls = mutableListOf<IconStyle>()

    override suspend fun coverage(
        apps: List<VisibleApp>,
        style: IconStyle,
    ): List<IconCoverageEntry> {
        coverageCalls += style
        val tinted = style.tint != IconTintMode.NONE || style.packFallbackMonochrome
        return apps.filter { it.profileKind != ProfileKind.PRIVATE }.map { app ->
            val tintedSource = coverageSources[app.key.component] ?: IconSource.DESATURATED
            val source =
                when {
                    tintedSource == IconSource.OVERRIDE || tintedSource == IconSource.PACK -> tintedSource
                    tinted -> tintedSource
                    else -> IconSource.PLAIN
                }
            IconCoverageEntry(app.key, app.packageName, app.displayLabel, app.profileKind, source, app.systemLabel)
        }
    }

    /** Simulates installing or removing packs. */
    fun setPacks(vararg packs: IconPackInfo) {
        iconPacks.value = IconPacks(packs.toList(), discovered = true)
        revision.value++
    }
}
