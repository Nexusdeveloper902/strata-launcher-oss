package app.strata.launcher.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.strata.launcher.core.model.IndexLetter
import app.strata.launcher.core.model.VisibleApp
import app.strata.launcher.core.platformapi.apps.FavoritesRepository
import app.strata.launcher.core.platformapi.apps.LabelRules
import app.strata.launcher.core.ui.AppIcon
import app.strata.launcher.core.ui.IconRequestKey
import app.strata.launcher.core.ui.SheetItem
import app.strata.launcher.core.ui.Strata
import app.strata.launcher.core.ui.StrataSheet
import app.strata.launcher.core.ui.rememberAppIconState

object SheetTestTags {
    const val SHEET = "app_sheet"
    const val NAME = "app_sheet_name"
    const val RENAME_FIELD = "app_sheet_rename_field"
    const val RENAME_SAVE = "app_sheet_rename_save"
    const val RESET_NAME = "app_sheet_reset_name"
    const val FAVORITE = "app_sheet_favorite"
    const val EDIT_FAVORITES = "app_sheet_edit_favorites"
    const val RENAME = "app_sheet_rename"
    const val CHANGE_ICON = "app_sheet_change_icon"
    const val HIDE = "app_sheet_hide"
    const val APP_INFO = "app_sheet_app_info"
    const val UNINSTALL = "app_sheet_uninstall"
    const val MENU = "home_menu"
    const val MENU_WIDGETS = "home_menu_widgets"
    const val MENU_THEMES = "home_menu_themes"
    const val MENU_WALLPAPER = "home_menu_wallpaper"
    const val MENU_SETTINGS = "home_menu_settings"
    const val MENU_EDIT = "home_menu_edit_favorites"
    const val FULL = "favorites_full"
    const val FULL_REPLACE = "favorites_full_replace"
    const val FULL_CANCEL = "favorites_full_cancel"
    const val REPLACE_PICKER = "favorites_replace_picker"
    const val LETTER_GRID = "letter_grid"

    fun replaceWith(app: VisibleApp) = "favorites_replace_${app.key.component}_${app.key.userSerial}"

    fun letter(label: String) = "letter_grid_$label"
}

/** The one modal of INTERACTION_SPEC §1.1: app sheet, home menu, the favourites cap dialog or its picker. */
@Composable
fun HomeModals(
    state: HomeUiState,
    onAction: (HomeAction) -> Unit,
) {
    val close = { onAction(HomeAction.CloseModal) }
    when (val modal = state.modal) {
        HomeModal.None -> {
            Unit
        }

        is HomeModal.Sheet -> {
            val app = state.sheetApp ?: return
            StrataSheet(onDismiss = close, modifier = Modifier.testTag(SheetTestTags.SHEET)) {
                AppSheet(app, app.key in state.favoriteKeys, modal.renaming, onAction)
            }
        }

        HomeModal.Menu -> {
            StrataSheet(onDismiss = close, modifier = Modifier.testTag(SheetTestTags.MENU)) {
                HomeMenu(onAction)
            }
        }

        is HomeModal.FavoritesFull -> {
            StrataSheet(onDismiss = close, modifier = Modifier.testTag(SheetTestTags.FULL)) {
                Text(
                    text = pluralStringResource(R.plurals.fav_cap, FavoritesRepository.MAX, FavoritesRepository.MAX),
                    style = MaterialTheme.typography.titleMedium,
                    color = Strata.colors.textPrimary,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = stringResource(R.string.fav_full_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Strata.colors.textSecondary,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = close, modifier = Modifier.testTag(SheetTestTags.FULL_CANCEL)) {
                        Text(text = stringResource(R.string.cancel))
                    }
                    TextButton(
                        onClick = { onAction(HomeAction.ChooseReplacement) },
                        modifier = Modifier.testTag(SheetTestTags.FULL_REPLACE),
                    ) { Text(text = stringResource(R.string.fav_replace)) }
                }
            }
        }

        is HomeModal.ReplaceFavorite -> {
            StrataSheet(onDismiss = close, modifier = Modifier.testTag(SheetTestTags.REPLACE_PICKER)) {
                Text(
                    text = stringResource(R.string.fav_replace_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = Strata.colors.textPrimary,
                    modifier = Modifier.semantics { heading() },
                )
                state.favorites.forEach { old ->
                    SheetItem(
                        text = old.displayLabel,
                        onClick = { onAction(HomeAction.ReplaceFavorite(old.key, modal.candidate)) },
                        tag = SheetTestTags.replaceWith(old),
                    )
                }
            }
        }
    }
}

/** S-SHEET (FR-APPS-03): header (icon, editable name), Favourite, Edit favourites, Rename, Change icon, Hide, App info, Uninstall. */
@Composable
private fun AppSheet(
    app: VisibleApp,
    favorite: Boolean,
    renaming: Boolean,
    onAction: (HomeAction) -> Unit,
) {
    val colors = Strata.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AppIcon(
            label = app.displayLabel,
            state = rememberAppIconState(IconRequestKey(app.key, app.lastUpdateTime), SHEET_ICON),
            size = SHEET_ICON,
            shape = androidx.compose.foundation.shape.CircleShape,
        )
        if (renaming) {
            RenameField(app, onAction, Modifier.weight(1f))
        } else {
            val renameLabel = stringResource(R.string.sheet_rename)
            Text(
                text = app.displayLabel,
                style = MaterialTheme.typography.titleLarge,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button, onClickLabel = renameLabel) {
                            onAction(HomeAction.StartRename(app.key))
                        }.padding(vertical = 8.dp)
                        .testTag(SheetTestTags.NAME),
            )
        }
    }
    // FR-APPS-04: hidden apps are not favourites; they can only be removed (kept slot), never added.
    if (favorite || !app.hidden) {
        SheetItem(
            text = stringResource(if (favorite) R.string.sheet_remove_favorite else R.string.sheet_add_favorite),
            onClick = { onAction(HomeAction.ToggleFavorite(app.key)) },
            tag = SheetTestTags.FAVORITE,
        )
    }
    if (favorite) {
        SheetItem(
            text = stringResource(R.string.menu_edit_favorites),
            onClick = { onAction(HomeAction.EditFavorites) },
            tag = SheetTestTags.EDIT_FAVORITES,
        )
    }
    SheetItem(
        text = stringResource(R.string.sheet_rename),
        onClick = { onAction(HomeAction.StartRename(app.key)) },
        tag = SheetTestTags.RENAME,
    )
    if (app.renamed) {
        SheetItem(
            text = stringResource(R.string.sheet_reset_name),
            onClick = { onAction(HomeAction.ResetName(app.key)) },
            tag = SheetTestTags.RESET_NAME,
        )
    }
    SheetItem(
        text = stringResource(R.string.sheet_change_icon),
        onClick = { onAction(HomeAction.ChangeIcon(app.key)) },
        tag = SheetTestTags.CHANGE_ICON,
    )
    SheetItem(
        text = stringResource(if (app.hidden) R.string.sheet_unhide else R.string.sheet_hide),
        onClick = { onAction(HomeAction.SetHidden(app.key, !app.hidden)) },
        tag = SheetTestTags.HIDE,
    )
    SheetItem(
        text = stringResource(R.string.sheet_app_info),
        onClick = { onAction(HomeAction.OpenAppInfo(app.key, null)) },
        tag = SheetTestTags.APP_INFO,
    )
    when (uninstallKind(app)) {
        UninstallKind.UNINSTALL -> {
            SheetItem(
                text = stringResource(R.string.sheet_uninstall),
                onClick = { onAction(HomeAction.Uninstall(app.key)) },
                tag = SheetTestTags.UNINSTALL,
            )
        }

        UninstallKind.UNINSTALL_UPDATES -> {
            SheetItem(
                text = stringResource(R.string.sheet_uninstall_updates),
                onClick = { onAction(HomeAction.Uninstall(app.key)) },
                tag = SheetTestTags.UNINSTALL,
            )
        }

        UninstallKind.NONE -> {
            Unit
        }
    }
}

/**
 * J-06 / FR-APPS-05: inline field, preselected, IME up; at most 60 characters can be entered; Save is disabled for an
 * empty or whitespace-only name (T-APPS-09). "Reset name" is a separate item.
 */
@Composable
private fun RenameField(
    app: VisibleApp,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier,
) {
    var value by remember(app.key) {
        mutableStateOf(TextFieldValue(app.displayLabel, TextRange(0, app.displayLabel.length)))
    }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    val valid = LabelRules.normalize(value.text) != null
    val save = { if (valid) onAction(HomeAction.Rename(app.key, value.text)) }
    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = { v ->
                value =
                    if (v.text.length > LabelRules.MAX_LENGTH) v.copy(text = v.text.take(LabelRules.MAX_LENGTH)) else v
            },
            singleLine = true,
            label = { Text(text = stringResource(R.string.sheet_name_label)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(SheetTestTags.RENAME_FIELD),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                onClick = { onAction(HomeAction.CancelRename(app.key)) },
            ) { Text(text = stringResource(R.string.cancel)) }
            TextButton(onClick = save, enabled = valid, modifier = Modifier.testTag(SheetTestTags.RENAME_SAVE)) {
                Text(text = stringResource(R.string.save))
            }
        }
    }
}

/** S-HMENU (FR-GEST-02): Widgets, Themes, Wallpaper, Settings, Edit favourites. */
@Composable
private fun HomeMenu(onAction: (HomeAction) -> Unit) {
    SheetItem(
        stringResource(R.string.menu_widgets),
        { onAction(HomeAction.MenuWidgets) },
        tag = SheetTestTags.MENU_WIDGETS,
    )
    SheetItem(
        stringResource(R.string.menu_themes),
        { onAction(HomeAction.MenuThemes) },
        tag = SheetTestTags.MENU_THEMES,
    )
    SheetItem(
        stringResource(R.string.menu_wallpaper),
        { onAction(HomeAction.MenuWallpaper) },
        tag = SheetTestTags.MENU_WALLPAPER,
    )
    SheetItem(stringResource(R.string.menu_settings), {
        onAction(HomeAction.OpenSettings)
    }, tag = SheetTestTags.MENU_SETTINGS)
    SheetItem(
        stringResource(R.string.menu_edit_favorites),
        { onAction(HomeAction.EditFavorites) },
        tag = SheetTestTags.MENU_EDIT,
    )
}

/** "Jump to letter…" (INTERACTION_SPEC §9, FR-A11Y-02): a grid of letter buttons; empty letters are skipped. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LetterGrid(
    letters: List<IndexLetter>,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    StrataSheet(onDismiss = onDismiss, modifier = Modifier.testTag(SheetTestTags.LETTER_GRID)) {
        Text(
            text = stringResource(R.string.list_jump_to_letter),
            style = MaterialTheme.typography.titleMedium,
            color = Strata.colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        FlowRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            letters.forEachIndexed { i, letter ->
                if (letter.isEmpty) return@forEachIndexed
                val description =
                    pluralStringResource(R.plurals.jump_letter, letter.appCount, letter.label, letter.appCount)
                Box(
                    modifier =
                        Modifier
                            .size(48.dp)
                            .clickable(role = Role.Button) { onPick(i) }
                            .semantics { contentDescription = description }
                            .testTag(SheetTestTags.letter(letter.label)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = letter.label, style = Strata.typography.label, color = Strata.colors.textPrimary)
                }
            }
        }
    }
}

private val SHEET_ICON = 56.dp
