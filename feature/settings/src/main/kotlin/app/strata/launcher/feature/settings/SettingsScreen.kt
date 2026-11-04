package app.strata.launcher.feature.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import app.strata.launcher.core.model.VisibleApp
import app.strata.launcher.core.ui.Strata
import kotlinx.coroutines.flow.Flow

object SettingsTestTags {
    const val ROOT = "settings_root"
    const val HIDDEN_APPS_PAGE = "settings_hidden_apps"
    const val HIDDEN_APPS_ROW = "settings_row_hidden_apps"
    const val CLEAR_HISTORY = "settings_clear_history"
    const val PAUSE_HISTORY = "settings_pause_history"
    const val LOCK = "settings_lock_gesture"
    const val LOCK_DISCLOSURE = "settings_lock_disclosure"
    const val LOCK_CONTINUE = "settings_lock_continue"
    const val HIDDEN_EMPTY = "settings_hidden_empty"
    const val DEFAULT_HOME = "settings_default_home"
    const val UNINSTALL_STRATA = "settings_uninstall_strata"
    const val UNINSTALL_GUIDANCE = "settings_uninstall_guidance"
    const val UNINSTALL_GUIDANCE_CONTINUE = "settings_uninstall_guidance_continue"

    fun toggle(t: Toggle) = "settings_toggle_${t.name.lowercase()}"

    fun unhide(app: VisibleApp) = "settings_unhide_${app.key.component}_${app.key.userSerial}"
}

/** Which settings page is shown. */
enum class SettingsPage { ROOT, HIDDEN_APPS }

/**
 * Settings pages (M3 part): the root with the home, list, search, gesture, apps and privacy options, and Hidden apps.
 * [onBack] leaves the page stack (finish the activity).
 */
@Composable
fun SettingsRoute(
    state: SettingsUiState,
    effects: Flow<SettingsEffect>,
    onAction: (SettingsAction) -> Unit,
    page: SettingsPage,
    onPage: (SettingsPage) -> Unit,
    startPage: SettingsPage,
    onBack: () -> Unit,
    /** Effects that need the activity: [SettingsEffect.SetDefault], [SettingsEffect.OpenHomeSettings]. */
    onNavigate: (SettingsEffect) -> Unit = {},
) {
    val context = LocalContext.current
    val currentOnNavigate by rememberUpdatedState(onNavigate)
    LaunchedEffect(effects) {
        effects.collect { effect ->
            val text =
                when (effect) {
                    SettingsEffect.HistoryCleared -> R.string.settings_history_cleared
                    SettingsEffect.NoHandler -> R.string.settings_no_handler
                    SettingsEffect.WriteFailed -> R.string.settings_write_failed
                    SettingsEffect.SetDefault, is SettingsEffect.OpenHomeSettings -> null
                }
            if (text != null) {
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            } else {
                currentOnNavigate(effect)
            }
        }
    }
    val currentOnBack by rememberUpdatedState(onBack)
    BackHandler(enabled = page != startPage) { onPage(startPage) }
    BackHandler(enabled = page == startPage) { currentOnBack() }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                // Test tags double as resource ids for UiAutomator (T-SET-03 device test).
                .semantics { testTagsAsResourceId = true },
    ) {
        when (page) {
            SettingsPage.ROOT -> SettingsRoot(state, onAction) { onPage(SettingsPage.HIDDEN_APPS) }
            SettingsPage.HIDDEN_APPS -> HiddenAppsPage(state, onAction)
        }
        if (state.showLockDisclosure) LockDisclosure(onAction)
        if (state.showUninstallGuidance) UninstallGuidance(onAction)
    }
}

@Composable
private fun SettingsRoot(
    state: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
    onHiddenApps: () -> Unit,
) {
    val s = state.settings ?: return
    LazyColumn(modifier = Modifier.fillMaxSize().safeDrawingPadding().testTag(SettingsTestTags.ROOT)) {
        item { Title(stringResource(R.string.settings_title)) }
        group(R.string.settings_group_default_home) {
            item {
                ActionRow(
                    title = stringResource(R.string.settings_default_home),
                    summary =
                        when (state.defaultHome) {
                            true -> stringResource(R.string.settings_default_home_held)
                            false -> stringResource(R.string.settings_default_home_not_held)
                            null -> null
                        },
                    tag = SettingsTestTags.DEFAULT_HOME,
                ) { onAction(SettingsAction.DefaultHomeRow) }
            }
            item {
                ActionRow(
                    title = stringResource(R.string.settings_uninstall_strata),
                    summary = stringResource(R.string.settings_uninstall_strata_summary),
                    tag = SettingsTestTags.UNINSTALL_STRATA,
                ) { onAction(SettingsAction.UninstallStrata) }
            }
        }
        group(R.string.settings_group_home) {
            toggle(Toggle.HOME_PRESS_OPENS_SEARCH, R.string.settings_home_press_search, s, onAction)
            toggle(Toggle.HIDE_STATUS_BAR, R.string.settings_hide_status_bar, s, onAction)
            toggle(
                Toggle.ALLOW_ROTATION,
                R.string.settings_allow_rotation,
                s,
                onAction,
                R.string.settings_allow_rotation_summary,
            )
        }
        group(R.string.settings_group_list) {
            toggle(Toggle.LIST_TWO_COLUMN, R.string.settings_two_column, s, onAction)
        }
        group(R.string.settings_group_search) {
            toggle(Toggle.SHOW_HIDDEN_IN_SEARCH, R.string.settings_show_hidden, s, onAction)
            toggle(Toggle.RECENTS, R.string.settings_recents, s, onAction)
        }
        group(R.string.settings_group_gestures) {
            toggle(Toggle.HAPTICS, R.string.settings_haptics, s, onAction)
            toggle(
                Toggle.KEEP_BACK_GESTURE,
                R.string.settings_keep_back,
                s,
                onAction,
                R.string.settings_keep_back_summary,
            )
            if (state.lockSupported) {
                item {
                    SwitchRow(
                        title = stringResource(R.string.settings_lock),
                        summary =
                            stringResource(
                                if (!s.lockGestureEnabled || state.lockServiceEnabled) {
                                    R.string.settings_lock_summary
                                } else {
                                    R.string.settings_lock_service_off
                                },
                            ),
                        checked = s.lockGestureEnabled,
                        onChange = { onAction(SettingsAction.LockGesture(it)) },
                        tag = SettingsTestTags.LOCK,
                    )
                }
                if (s.lockGestureEnabled && !state.lockServiceEnabled) {
                    item {
                        ActionRow(stringResource(R.string.settings_lock_open_accessibility), null) {
                            onAction(SettingsAction.OpenAccessibilitySettings)
                        }
                    }
                }
            }
        }
        group(R.string.settings_group_apps) {
            item {
                ActionRow(
                    title = stringResource(R.string.settings_hidden_apps),
                    summary =
                        pluralStringResource(
                            R.plurals.settings_hidden_count,
                            state.hiddenApps.size,
                            state.hiddenApps.size,
                        ),
                    tag = SettingsTestTags.HIDDEN_APPS_ROW,
                    onClick = onHiddenApps,
                )
            }
            if (state.privateSpaceSupported) {
                toggle(
                    Toggle.SHOW_PRIVATE_SPACE,
                    R.string.settings_show_private_space,
                    s,
                    onAction,
                    R.string.settings_show_private_space_summary,
                )
            }
        }
        group(R.string.settings_group_privacy) {
            item {
                SwitchRow(
                    title = stringResource(R.string.settings_pause_history),
                    summary = stringResource(R.string.settings_pause_history_summary),
                    checked = s.launchLogPaused,
                    onChange = { onAction(SettingsAction.PauseHistory(it)) },
                    tag = SettingsTestTags.PAUSE_HISTORY,
                )
            }
            item {
                ActionRow(
                    title = stringResource(R.string.settings_clear_history),
                    summary = stringResource(R.string.settings_clear_history_summary),
                    tag = SettingsTestTags.CLEAR_HISTORY,
                ) { onAction(SettingsAction.ClearHistory) }
            }
        }
    }
}

/** Settings → Hidden apps (FR-APPS-04): every hidden app with "Show in list". */
@Composable
private fun HiddenAppsPage(
    state: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize().safeDrawingPadding().testTag(SettingsTestTags.HIDDEN_APPS_PAGE)) {
        item { Title(stringResource(R.string.settings_hidden_apps)) }
        if (state.hiddenApps.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.settings_hidden_none),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier =
                        Modifier
                            .padding(
                                horizontal = 24.dp,
                                vertical = 12.dp,
                            ).testTag(SettingsTestTags.HIDDEN_EMPTY),
                )
            }
        }
        items(state.hiddenApps, key = { it.key.component + ":" + it.key.userSerial }) { app ->
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = app.displayLabel,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { onAction(SettingsAction.Unhide(app)) },
                    modifier = Modifier.testTag(SettingsTestTags.unhide(app)),
                ) { Text(text = stringResource(R.string.settings_unhide)) }
            }
        }
    }
}

/** ANDROID_PLATFORM §10: separate prominent disclosure with affirmative consent before the system grant. */
@Composable
private fun LockDisclosure(onAction: (SettingsAction) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAction(SettingsAction.DismissLockDisclosure) },
        title = { Text(text = stringResource(R.string.settings_lock_disclosure_title)) },
        text = { Text(text = stringResource(R.string.settings_lock_disclosure_body)) },
        confirmButton = {
            TextButton(
                onClick = { onAction(SettingsAction.AcceptLockDisclosure) },
                modifier = Modifier.testTag(SettingsTestTags.LOCK_CONTINUE),
            ) { Text(text = stringResource(R.string.settings_continue)) }
        },
        dismissButton = {
            TextButton(onClick = { onAction(SettingsAction.DismissLockDisclosure) }) {
                Text(text = stringResource(R.string.settings_cancel))
            }
        },
        modifier = Modifier.testTag(SettingsTestTags.LOCK_DISCLOSURE),
    )
}

/** FR-SET-02: the user must switch the home app before Strata can be uninstalled. */
@Composable
private fun UninstallGuidance(onAction: (SettingsAction) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAction(SettingsAction.DismissUninstallGuidance) },
        title = { Text(text = stringResource(R.string.settings_uninstall_guidance_title)) },
        text = { Text(text = stringResource(R.string.settings_uninstall_guidance_body)) },
        confirmButton = {
            TextButton(
                onClick = { onAction(SettingsAction.UninstallGuidanceContinue) },
                modifier = Modifier.testTag(SettingsTestTags.UNINSTALL_GUIDANCE_CONTINUE),
            ) { Text(text = stringResource(R.string.settings_uninstall_guidance_continue)) }
        },
        dismissButton = {
            TextButton(onClick = { onAction(SettingsAction.DismissUninstallGuidance) }) {
                Text(text = stringResource(R.string.settings_cancel))
            }
        },
        modifier = Modifier.testTag(SettingsTestTags.UNINSTALL_GUIDANCE),
    )
}

private fun LazyListScope.group(
    title: Int,
    content: LazyListScope.() -> Unit,
) {
    item(key = "group_$title") {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, top = 32.dp, bottom = 8.dp)
                    .semantics { heading() },
        )
    }
    content()
}

private fun LazyListScope.toggle(
    toggle: Toggle,
    title: Int,
    settings: app.strata.launcher.core.model.Settings,
    onAction: (SettingsAction) -> Unit,
    summary: Int? = null,
) {
    item(key = toggle.name) {
        SwitchRow(
            title = stringResource(title),
            summary = summary?.let { stringResource(it) },
            checked = toggle.read(settings),
            onChange = { onAction(SettingsAction.Set(toggle, it)) },
            tag = SettingsTestTags.toggle(toggle),
        )
    }
}

@Composable
private fun Title(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp).semantics { heading() },
    )
}

@Composable
private fun SwitchRow(
    title: String,
    summary: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    tag: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) {
                Text(text = summary, style = MaterialTheme.typography.bodyMedium, color = Strata.colors.textSecondary)
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ActionRow(
    title: String,
    summary: String?,
    tag: String? = null,
    onClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        if (summary != null) {
            Text(text = summary, style = MaterialTheme.typography.bodyMedium, color = Strata.colors.textSecondary)
        }
    }
}
