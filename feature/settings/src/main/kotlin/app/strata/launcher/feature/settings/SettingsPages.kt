package app.strata.launcher.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.strata.launcher.core.model.IconCoverageEntry
import app.strata.launcher.core.model.IconSource
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.platformapi.notifications.ListenerAccess
import app.strata.launcher.core.platformapi.system.PermissionState
import app.strata.launcher.core.ui.Strata
import app.strata.launcher.core.ui.iconStyleOf

/** Test tags of the M8 pages. */
object SettingsPageTags {
    const val THEMES = "settings_themes"
    const val ICONS_ROW = "settings_icons"
    const val ICONS_PAGE = "settings_icons_page"
    const val ICONS_LOADING = "settings_icons_loading"
    const val ICONS_NOTE = "settings_icons_note"
    const val ICONS_EXPORT = "settings_icons_export"
    const val ICONS_EXPORT_ALL = "settings_icons_export_all"
    const val PRIVACY_ROW = "settings_privacy"
    const val PRIVACY_PAGE = "settings_privacy_page"
    const val PRIVACY_NO_INTERNET = "settings_privacy_no_internet"
    const val PRIVACY_NOTIFICATIONS = "settings_privacy_notifications"
    const val PRIVACY_CONTACTS = "settings_privacy_contacts"
    const val PRIVACY_LOCK = "settings_privacy_lock"
    const val ABOUT_ROW = "settings_about"
    const val ABOUT_PAGE = "settings_about_page"
    const val ABOUT_VERSION = "settings_about_version"
    const val ABOUT_PRIVACY = "settings_about_privacy"
    const val LICENCES_ROW = "settings_licences"
    const val LICENCES_PAGE = "settings_licences_page"
    const val LICENCE_TEXT = "settings_licence_text"

    fun count(source: IconSource) = "settings_icons_count_${source.name.lowercase()}"

    fun coverageRow(entry: IconCoverageEntry) = "settings_icons_row_${entry.key.component}_${entry.key.userSerial}"

    fun highlighted(entry: IconCoverageEntry) =
        "settings_icons_highlight_${entry.key.component}_${entry.key.userSerial}"

    fun licence(entry: LicenceEntry) = "settings_licence_${entry.name}"
}

internal fun sourceLabel(source: IconSource): Int =
    when (source) {
        IconSource.OVERRIDE -> R.string.icons_source_override
        IconSource.PACK -> R.string.icons_source_pack
        IconSource.GLYPH -> R.string.icons_source_glyph
        IconSource.PLATFORM_MONOCHROME -> R.string.icons_source_monochrome
        IconSource.DESATURATED -> R.string.icons_source_desaturated
        IconSource.PLAIN -> R.string.icons_source_plain
    }

/**
 * Settings -> Icons -> Icon coverage (FR-SET-07, D-014): counts per source, every app with its source, desaturated
 * apps highlighted (with a text marker, never colour alone) while the theme tints, and "Export list".
 */
@Composable
internal fun IconCoveragePage(
    state: IconCoverageState,
    onAction: (IconCoverageAction) -> Unit,
) {
    val theme = Strata.theme
    val style = remember(theme.icons, theme.colors) { iconStyleOf(theme) }
    // The page reads the resolved theme's icon style; a theme switch while it is open recomputes the list.
    LaunchedEffect(style) { onAction(IconCoverageAction.Show(style)) }
    val exportTitle = stringResource(R.string.icons_export_title)
    LazyColumn(modifier = Modifier.fillMaxSize().safeDrawingPadding().testTag(SettingsPageTags.ICONS_PAGE)) {
        item { Title(stringResource(R.string.icons_coverage)) }
        if (state.loading) {
            item { NoteRow(stringResource(R.string.icons_loading), SettingsPageTags.ICONS_LOADING) }
            return@LazyColumn
        }
        item(key = "note") {
            NoteRow(
                stringResource(if (state.tinted) R.string.icons_tinted_note else R.string.icons_untinted_note),
                SettingsPageTags.ICONS_NOTE,
            )
        }
        items(state.counts, key = { "count_${it.first}" }) { (source, n) ->
            Text(
                text = stringResource(R.string.icons_count, stringResource(sourceLabel(source)), n),
                style = MaterialTheme.typography.bodyLarge,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 4.dp)
                        .testTag(SettingsPageTags.count(source)),
            )
        }
        item(key = "export_all") {
            SwitchRow(
                title = stringResource(R.string.icons_export_all),
                summary = stringResource(R.string.icons_export_all_summary),
                checked = state.exportAll,
                onChange = { onAction(IconCoverageAction.SetExportAll(it)) },
                tag = SettingsPageTags.ICONS_EXPORT_ALL,
            )
        }
        item(key = "export") {
            Button(
                onClick = { onAction(IconCoverageAction.Export(exportTitle)) },
                enabled = state.exportLines.isNotEmpty(),
                modifier =
                    Modifier
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                        .heightIn(min = 48.dp)
                        .testTag(SettingsPageTags.ICONS_EXPORT),
            ) { Text(stringResource(R.string.icons_export)) }
        }
        if (state.entries.isEmpty()) {
            item { NoteRow(stringResource(R.string.icons_empty), "settings_icons_empty") }
        }
        items(state.entries, key = { it.key.component + ":" + it.key.userSerial }) { entry ->
            CoverageRow(entry, state.highlighted(entry))
        }
    }
}

@Composable
private fun CoverageRow(
    entry: IconCoverageEntry,
    highlighted: Boolean,
) {
    val label =
        if (entry.profileKind ==
            ProfileKind.WORK
        ) {
            stringResource(R.string.icons_work_suffix, entry.label)
        } else {
            entry.label
        }
    val source = stringResource(sourceLabel(entry.source))
    val marker = stringResource(R.string.icons_needs_glyph)
    val spoken =
        stringResource(R.string.icons_row_description, label, if (highlighted) "$source, $marker" else source)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(if (highlighted) Modifier.background(Strata.colors.highlight) else Modifier)
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .testTag(if (highlighted) SettingsPageTags.highlighted(entry) else SettingsPageTags.coverageRow(entry))
                .clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = entry.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = Strata.colors.textSecondary,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(text = source, style = MaterialTheme.typography.bodyMedium)
            if (highlighted) {
                Text(text = marker, style = MaterialTheme.typography.labelMedium, color = Strata.colors.accent)
            }
        }
    }
}

/**
 * Privacy & permissions (FR-SET-03, S-SET): every optional access this build declares with its state, purpose and
 * an action, and "Strata has no internet access". Also opened by the system's permission-usage link.
 */
@Composable
internal fun PrivacyPage(
    state: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize().safeDrawingPadding().testTag(SettingsPageTags.PRIVACY_PAGE)) {
        item { Title(stringResource(R.string.privacy_page)) }
        item { NoteRow(stringResource(R.string.privacy_no_internet), SettingsPageTags.PRIVACY_NO_INTERNET) }
        item { NoteRow(stringResource(R.string.privacy_on_device), "settings_privacy_on_device") }
        state.optional.badgeAccess?.let { access ->
            item(key = "notifications") {
                val stateText =
                    stringResource(
                        when {
                            access == ListenerAccess.UNSUPPORTED -> R.string.privacy_state_unsupported
                            access.granted -> R.string.privacy_state_allowed
                            else -> R.string.privacy_state_not_allowed
                        },
                    )
                AccessRow(
                    title = stringResource(R.string.privacy_notifications),
                    state = stateText,
                    purpose = stringResource(R.string.privacy_notifications_purpose),
                    action =
                        if (access ==
                            ListenerAccess.UNSUPPORTED
                        ) {
                            null
                        } else {
                            stringResource(R.string.privacy_manage_in_system)
                        },
                    tag = SettingsPageTags.PRIVACY_NOTIFICATIONS,
                ) { onAction(SettingsAction.ManageBadgeAccess) }
            }
        }
        state.optional.contactsPermission?.let { permission ->
            item(key = "contacts") {
                AccessRow(
                    title = stringResource(R.string.privacy_contacts),
                    state =
                        stringResource(
                            if (permission == PermissionState.GRANTED) {
                                R.string.privacy_state_allowed
                            } else {
                                R.string.privacy_state_not_allowed
                            },
                        ),
                    purpose = stringResource(R.string.privacy_contacts_purpose),
                    action = stringResource(R.string.privacy_manage_app_info),
                    tag = SettingsPageTags.PRIVACY_CONTACTS,
                ) { onAction(SettingsAction.ManageContactsAccess) }
            }
        }
        if (state.lockSupported) {
            item(key = "lock") {
                AccessRow(
                    title = stringResource(R.string.privacy_lock),
                    state =
                        stringResource(
                            if (state.lockServiceEnabled) R.string.privacy_state_on else R.string.privacy_state_off,
                        ),
                    purpose = stringResource(R.string.privacy_lock_purpose),
                    action = stringResource(R.string.privacy_manage_in_system),
                    tag = SettingsPageTags.PRIVACY_LOCK,
                ) { onAction(SettingsAction.ManageLockAccess) }
            }
        }
    }
}

@Composable
private fun AccessRow(
    title: String,
    state: String,
    purpose: String,
    action: String?,
    tag: String,
    onClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp).semantics { heading() },
        )
        Text(
            text = stringResource(R.string.privacy_state_line, state, purpose),
            style = MaterialTheme.typography.bodyMedium,
            color = Strata.colors.textSecondary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        if (action != null) ActionRow(action, null, "${tag}_action", onClick)
    }
}

/** About (FR-SET-06): version, licences and the privacy statement. */
@Composable
internal fun AboutPage(
    state: SettingsUiState,
    onLicences: () -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize().safeDrawingPadding().testTag(SettingsPageTags.ABOUT_PAGE)) {
        item { Title(stringResource(R.string.about_page)) }
        item { NoteRow(stringResource(R.string.about_version, state.versionName), SettingsPageTags.ABOUT_VERSION) }
        item {
            ActionRow(
                title = stringResource(R.string.about_licences),
                summary = stringResource(R.string.about_licences_summary),
                tag = SettingsPageTags.LICENCES_ROW,
                onClick = onLicences,
            )
        }
        item {
            Text(
                text = stringResource(R.string.about_privacy_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp).semantics { heading() },
            )
        }
        item { NoteRow(stringResource(R.string.about_privacy_statement), SettingsPageTags.ABOUT_PRIVACY) }
    }
}

/** About -> Licences: each bundled font and each open-source library with its licence; a tap shows the text. */
@Composable
internal fun LicencesPage(
    state: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
    onOpen: () -> Unit,
) {
    LaunchedEffect(Unit) { onAction(SettingsAction.LoadLicences) }
    val licences = state.licences
    LazyColumn(modifier = Modifier.fillMaxSize().safeDrawingPadding().testTag(SettingsPageTags.LICENCES_PAGE)) {
        item { Title(stringResource(R.string.licences_page)) }
        when {
            licences == null -> {
                Unit
            }

            licences.failed -> {
                item { NoteRow(stringResource(R.string.licences_unreadable), "settings_licences_unreadable") }
            }

            else -> {
                if (licences.entries.none { it.font }) {
                    item { NoteRow(stringResource(R.string.licences_none_fonts), "settings_licences_no_fonts") }
                }
                items(licences.entries, key = { it.name }) { entry ->
                    ActionRow(
                        title = entry.name,
                        summary = entry.licence,
                        tag = SettingsPageTags.licence(entry),
                    ) {
                        onAction(SettingsAction.OpenLicence(entry))
                        onOpen()
                    }
                }
            }
        }
    }
}

/** The full text of one licence. */
@Composable
internal fun LicenceTextPage(state: SettingsUiState) {
    val text = state.licenceText
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .testTag(SettingsPageTags.LICENCE_TEXT),
    ) {
        Title(text?.first?.name ?: "")
        Text(
            text = text?.second ?: "",
            style = MaterialTheme.typography.bodySmall,
            color = if (text == null) Color.Unspecified else Strata.colors.textPrimary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
    }
}
