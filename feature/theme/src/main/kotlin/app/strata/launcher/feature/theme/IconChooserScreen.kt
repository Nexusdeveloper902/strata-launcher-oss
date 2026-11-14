package app.strata.launcher.feature.theme

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.strata.launcher.core.model.IconResult
import app.strata.launcher.core.ui.AppIcon
import app.strata.launcher.core.ui.FilterChip
import app.strata.launcher.core.ui.IconRequestKey
import app.strata.launcher.core.ui.focusRing
import app.strata.launcher.core.ui.rememberAppIconState

/** Test tags of S-ICON. */
object IconChooserTags {
    const val SCREEN = "icon_chooser"
    const val THEME_ICON = "icon_chooser_theme_icon"
    const val NO_PACKS = "icon_chooser_no_packs"
    const val SEARCH = "icon_chooser_search"
    const val WRITE_FAILED = "icon_chooser_write_failed"

    fun pack(packageName: String) = "icon_chooser_pack_$packageName"

    fun cell(drawable: String) = "icon_chooser_cell_$drawable"
}

/** Loads one pack drawable for a grid cell. */
fun interface PackIconSource {
    suspend fun load(
        pack: String,
        drawable: String,
        sizePx: Int,
        densityDpi: Int,
    ): IconResult
}

/**
 * S-ICON (FR-APPS-06): "Theme icon" (reset), the installed packs as chips, a search field and the selected pack's
 * icons, the pack's own mapping for the app first. Every cell is a button with the drawable's name as its label.
 */
@Composable
fun IconChooserScreen(
    state: IconChooserState,
    onAction: (IconChooserAction) -> Unit,
    icons: PackIconSource,
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize().testTag(IconChooserTags.SCREEN),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val app = state.app
            Text(
                text =
                    if (app != null) {
                        stringResource(R.string.icon_chooser_title, app.displayLabel)
                    } else {
                        stringResource(R.string.icon_chooser_title_plain)
                    },
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 16.dp).semantics { heading() },
            )
            if (state.writeFailed) {
                Text(
                    text = stringResource(R.string.icon_chooser_write_failed),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(IconChooserTags.WRITE_FAILED),
                )
            }
            if (app != null) {
                val themeIcon = rememberAppIconState(IconRequestKey(app.key, app.lastUpdateTime, null), CELL_ICON)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .testTag(IconChooserTags.THEME_ICON)
                            .semantics(mergeDescendants = true) {
                                selected = app.iconOverride == null
                                role = Role.Button
                            }.focusRing()
                            .clickable { onAction(IconChooserAction.UseThemeIcon) },
                ) {
                    AppIcon(app.displayLabel, themeIcon, CELL_ICON, RoundedCornerShape(12.dp))
                    Text(
                        text = stringResource(R.string.icon_chooser_theme_icon),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            if (state.packsDiscovered && state.packs.isEmpty()) {
                Text(
                    text = stringResource(R.string.icon_chooser_no_packs),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(IconChooserTags.NO_PACKS),
                )
                return@Column
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.packs.forEach { pack ->
                    FilterChip(
                        selected = pack.packageName == state.selectedPack,
                        enabled = pack.usable,
                        onClick = { onAction(IconChooserAction.SelectPack(pack.packageName)) },
                        label = {
                            Text(
                                if (pack.usable) {
                                    pack.label
                                } else {
                                    stringResource(
                                        R.string.icon_chooser_pack_unusable,
                                        pack.label,
                                    )
                                },
                            )
                        },
                        modifier = Modifier.testTag(IconChooserTags.pack(pack.packageName)),
                    )
                }
            }
            if (state.selectedPack != null) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = { onAction(IconChooserAction.Query(it)) },
                    label = { Text(stringResource(R.string.icon_chooser_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(IconChooserTags.SEARCH),
                )
            }
            val pack = state.selectedPack
            if (pack != null && !state.loadingDrawables && state.visibleDrawables.isEmpty()) {
                Text(text = stringResource(R.string.icon_chooser_empty), style = MaterialTheme.typography.bodyMedium)
            }
            if (pack != null) {
                val current = app?.iconOverride
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(CELL),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.visibleDrawables, key = { it }) { name ->
                        PackIconCell(
                            pack = pack,
                            drawable = name,
                            selected = current?.packPackage == pack && current.drawable == name,
                            icons = icons,
                            onClick = { onAction(IconChooserAction.Choose(name)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PackIconCell(
    pack: String,
    drawable: String,
    selected: Boolean,
    icons: PackIconSource,
    onClick: () -> Unit,
) {
    val density = LocalDensity.current
    val px = with(density) { CELL_ICON.roundToPx() }
    val dpi = (density.density * DENSITY_BASELINE).toInt()
    val bitmap by produceState<ImageBitmap?>(null, pack, drawable, px) {
        val result = icons.load(pack, drawable, px, dpi)
        value = ((result as? IconResult.Image)?.image?.native as? Bitmap)?.asImageBitmap()
    }
    val label = drawable.replace('_', ' ')
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(CELL)
                .padding(4.dp)
                .then(
                    if (selected) {
                        Modifier.border(
                            2.dp,
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(12.dp),
                        )
                    } else {
                        Modifier
                    },
                ).testTag(IconChooserTags.cell(drawable))
                .semantics(mergeDescendants = true) {
                    contentDescription = label
                    this.selected = selected
                    role = Role.Button
                }.focusRing()
                .clickable(onClick = onClick),
    ) {
        val b = bitmap
        if (b != null) Image(bitmap = b, contentDescription = null, modifier = Modifier.size(CELL_ICON))
    }
}

private val CELL = 64.dp
private val CELL_ICON = 48.dp
private const val DENSITY_BASELINE = 160f
