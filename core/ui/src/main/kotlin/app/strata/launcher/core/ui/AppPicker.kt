package app.strata.launcher.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.VisibleApp

/** Test tags of one [AppPicker] (each screen keeps its own). */
data class AppPickerTags(
    val search: String,
    val list: String,
    val count: String,
    val limit: String,
    val row: (AppKey) -> String,
)

/** The texts of one [AppPicker], resolved by the caller from its own strings. */
data class AppPickerTexts(
    val count: String,
    val limit: String,
    val search: String,
)

/**
 * The favourites checklist (FR-ONBOARD-01 step 2, FR-FAV-01/02): count, the cap note when a pick was refused, a search
 * field and the apps with a checkbox each. Shared by onboarding and Edit favourites; the caller keeps the state
 * (query, picks, cap) and decides what a confirmation does. The list takes the remaining height.
 */
@Composable
fun ColumnScope.AppPicker(
    apps: List<VisibleApp>?,
    query: String,
    picked: Collection<AppKey>,
    limitReached: Boolean,
    texts: AppPickerTexts,
    tags: AppPickerTags,
    onQuery: (String) -> Unit,
    onToggle: (AppKey) -> Unit,
) {
    Text(
        text = texts.count,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 8.dp).testTag(tags.count),
    )
    if (limitReached) {
        Text(
            text = texts.limit,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(tags.limit).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        label = { Text(texts.search) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag(tags.search),
    )
    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().testTag(tags.list)) {
        if (apps != null) {
            items(apps, key = { it.key.component + ":" + it.key.userSerial }) { app ->
                PickRow(app, picked = app.key in picked, tag = tags.row(app.key)) { onToggle(app.key) }
            }
        }
    }
}

@Composable
private fun PickRow(
    app: VisibleApp,
    picked: Boolean,
    tag: String,
    onToggle: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .focusRing()
                .toggleable(value = picked, role = Role.Checkbox, onValueChange = { onToggle() })
                .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Checkbox(checked = picked, onCheckedChange = null)
        Text(text = app.displayLabel, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

/** Shared picker logic (pure). */
object AppPickerLogic {
    /** Case-insensitive label match; the picker is a short checklist, not the search index. */
    fun filter(
        apps: List<VisibleApp>,
        query: String,
    ): List<VisibleApp> {
        val q = query.trim()
        return if (q.isEmpty()) apps else apps.filter { it.displayLabel.contains(q, ignoreCase = true) }
    }

    /** Toggles [key] in [picked] (pick order kept); null when adding would pass [max] (the caller shows the cap). */
    fun toggle(
        picked: List<AppKey>,
        key: AppKey,
        max: Int,
    ): List<AppKey>? =
        when {
            key in picked -> picked - key
            picked.size >= max -> null
            else -> picked + key
        }
}
