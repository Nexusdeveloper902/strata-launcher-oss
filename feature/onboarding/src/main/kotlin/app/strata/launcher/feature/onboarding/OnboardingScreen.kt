package app.strata.launcher.feature.onboarding

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.VisibleApp
import app.strata.launcher.core.platformapi.apps.FavoritesRepository
import app.strata.launcher.core.ui.Button
import app.strata.launcher.core.ui.Checkbox
import app.strata.launcher.core.ui.OutlinedButton
import app.strata.launcher.core.ui.TextButton
import app.strata.launcher.core.ui.focusRing
import kotlinx.coroutines.flow.Flow

object OnboardingTestTags {
    const val ROOT = "onboarding_root"

    /** The primary forward button of every step (Welcome, Pick favourites, the held set-default step, Done). */
    const val CONTINUE = "onboarding_continue"
    const val STEP = "onboarding_step"
    const val WELCOME = "onboarding_welcome"
    const val FAVORITES = "onboarding_favorites"
    const val FAVORITES_SEARCH = "onboarding_favorites_search"
    const val FAVORITES_COUNT = "onboarding_favorites_count"
    const val FAVORITES_LIMIT = "onboarding_favorites_limit"
    const val SKIP = "onboarding_skip"
    const val SET_DEFAULT_STEP = "onboarding_set_default_step"
    const val HELD = "onboarding_held"
    const val SET_DEFAULT = "onboarding_set_default"
    const val NOT_NOW = "onboarding_not_now"
    const val DENIED_CARD = "set_default_denied"
    const val TRY_AGAIN = "set_default_try_again"
    const val OPEN_SETTINGS = "set_default_open_settings"
    const val OEM_HELP = "set_default_oem_help"
    const val OEM_BLOCKING_NOTE = "set_default_oem_blocking_note"
    const val RETRY = "set_default_retry"
    const val CARD_NOT_NOW = "set_default_card_not_now"
    const val DONE = "onboarding_done"

    fun pick(key: AppKey) = "onboarding_pick_${key.component}_${key.userSerial}"
}

/**
 * The first-run flow (FR-ONBOARD-01…03, S-ONB). [onLeave] is called for Back on Welcome: the entry activity finishes;
 * home passes null, because there Back leaves the flow only after it ends (INTERACTION_SPEC §3 ONBOARDING).
 */
@Composable
fun OnboardingRoute(
    state: OnboardingUiState,
    effects: Flow<OnboardingEffect>,
    onAction: (OnboardingAction) -> Unit,
    onFinished: () -> Unit,
    onLeave: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val currentOnFinished by rememberUpdatedState(onFinished)
    LaunchedEffect(effects) {
        effects.collect { effect ->
            when (effect) {
                OnboardingEffect.Finished -> {
                    currentOnFinished()
                }

                OnboardingEffect.WriteFailed -> {
                    Toast
                        .makeText(
                            context,
                            R.string.onboarding_write_failed,
                            Toast.LENGTH_SHORT,
                        ).show()
                }
            }
        }
    }
    BackHandler(enabled = state.step != OnboardingStep.WELCOME) { onAction(OnboardingAction.Back) }
    BackHandler(enabled = state.step == OnboardingStep.WELCOME) { onLeave?.invoke() }
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier =
            modifier
                .fillMaxSize()
                .semantics { testTagsAsResourceId = true }
                .testTag(OnboardingTestTags.ROOT),
    ) {
        // The stored step is still being read: draw only the background, never a wrong step (no Welcome flash).
        if (!state.loaded) return@Surface
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp)) {
            Text(
                text = stringResource(R.string.onboarding_step, state.stepNumber, OnboardingStep.entries.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(OnboardingTestTags.STEP),
            )
            when (state.step) {
                OnboardingStep.WELCOME -> WelcomeStep(onAction)
                OnboardingStep.FAVORITES -> FavoritesStep(state, onAction)
                OnboardingStep.SET_DEFAULT -> SetDefaultStep(state, onAction)
                OnboardingStep.DONE -> DoneStep(state, onAction)
            }
        }
    }
}

@Composable
private fun ColumnScope.WelcomeStep(onAction: (OnboardingAction) -> Unit) {
    Column(
        modifier =
            Modifier
                .weight(
                    1f,
                ).fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .testTag(OnboardingTestTags.WELCOME),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Heading(stringResource(R.string.onboarding_welcome_title))
        Body(stringResource(R.string.onboarding_welcome_body))
        Body(stringResource(R.string.onboarding_welcome_privacy))
    }
    Buttons {
        Button(
            onClick = { onAction(OnboardingAction.Continue) },
            modifier = Modifier.testTag(OnboardingTestTags.CONTINUE),
        ) { Text(stringResource(R.string.onboarding_continue)) }
    }
}

/** FR-ONBOARD-01 step 2: alphabetical checklist with search, up to 12, nothing preselected, skippable (T-ONBOARD-02). */
@Composable
private fun ColumnScope.FavoritesStep(
    state: OnboardingUiState,
    onAction: (OnboardingAction) -> Unit,
) {
    Heading(stringResource(R.string.onboarding_favorites_title), Modifier.padding(top = 16.dp))
    Body(pluralStringResource(R.plurals.onboarding_favorites_body, FavoritesRepository.MAX, FavoritesRepository.MAX))
    Text(
        text = stringResource(R.string.onboarding_favorites_count, state.pickedCount, FavoritesRepository.MAX),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 8.dp).testTag(OnboardingTestTags.FAVORITES_COUNT),
    )
    if (state.limitReached) {
        Text(
            text = stringResource(R.string.onboarding_favorites_limit, FavoritesRepository.MAX),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier =
                Modifier
                    .testTag(OnboardingTestTags.FAVORITES_LIMIT)
                    .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    OutlinedTextField(
        value = state.query,
        onValueChange = { onAction(OnboardingAction.Query(it)) },
        label = { Text(stringResource(R.string.onboarding_favorites_search)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag(OnboardingTestTags.FAVORITES_SEARCH),
    )
    val apps = state.apps
    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().testTag(OnboardingTestTags.FAVORITES)) {
        if (apps != null) {
            items(apps, key = { it.key.component + ":" + it.key.userSerial }) { app ->
                PickRow(app, picked = app.key in state.picked) { onAction(OnboardingAction.TogglePick(app.key)) }
            }
        }
    }
    Buttons {
        TextButton(
            onClick = { onAction(OnboardingAction.SkipFavorites) },
            modifier = Modifier.testTag(OnboardingTestTags.SKIP),
        ) { Text(stringResource(R.string.onboarding_skip)) }
        Button(
            onClick = { onAction(OnboardingAction.Continue) },
            enabled = !state.busy,
            modifier = Modifier.testTag(OnboardingTestTags.CONTINUE),
        ) { Text(stringResource(R.string.onboarding_continue)) }
    }
}

@Composable
private fun PickRow(
    app: VisibleApp,
    picked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .focusRing()
                .toggleable(value = picked, role = Role.Checkbox, onValueChange = { onToggle() })
                .testTag(OnboardingTestTags.pick(app.key)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Checkbox(checked = picked, onCheckedChange = null)
        Text(text = app.displayLabel, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

/** J-01 step 3 and the step-4 result cards (FR-ONBOARD-02/03). */
@Composable
private fun ColumnScope.SetDefaultStep(
    state: OnboardingUiState,
    onAction: (OnboardingAction) -> Unit,
) {
    val sd = state.setDefault
    Column(
        modifier =
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .testTag(OnboardingTestTags.SET_DEFAULT_STEP),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        if (sd.held == true) {
            Heading(stringResource(R.string.onboarding_held_title), Modifier.testTag(OnboardingTestTags.HELD))
            Body(stringResource(R.string.onboarding_held_body))
        } else {
            Heading(stringResource(R.string.onboarding_set_default_title))
            Body(stringResource(R.string.onboarding_set_default_body))
            Body(stringResource(R.string.onboarding_set_default_undo))
            SetDefaultCardContent(
                card = sd.card,
                onTryAgain = { onAction(OnboardingAction.SetAsDefault) },
                onOpenSettings = { onAction(OnboardingAction.OpenSettings) },
                onNotNow = { onAction(OnboardingAction.NotNow) },
            )
        }
    }
    Buttons {
        when {
            sd.held == true -> {
                Button(
                    onClick = { onAction(OnboardingAction.Continue) },
                    modifier = Modifier.testTag(OnboardingTestTags.CONTINUE),
                ) { Text(stringResource(R.string.onboarding_continue)) }
            }

            sd.held == false && sd.card == SetDefaultCard.None -> {
                TextButton(
                    onClick = { onAction(OnboardingAction.NotNow) },
                    enabled = !state.busy,
                    modifier = Modifier.testTag(OnboardingTestTags.NOT_NOW),
                ) { Text(stringResource(R.string.onboarding_not_now)) }
                Button(
                    onClick = { onAction(OnboardingAction.SetAsDefault) },
                    enabled = !sd.waiting,
                    modifier = Modifier.testTag(OnboardingTestTags.SET_DEFAULT),
                ) { Text(stringResource(R.string.onboarding_set_default)) }
            }

            else -> {
                Unit
            }
        }
    }
}

@Composable
private fun ColumnScope.DoneStep(
    state: OnboardingUiState,
    onAction: (OnboardingAction) -> Unit,
) {
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().testTag(OnboardingTestTags.DONE),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Heading(stringResource(R.string.onboarding_done_title))
        Body(stringResource(R.string.onboarding_done_body))
    }
    Buttons {
        Button(
            onClick = { onAction(OnboardingAction.Finish) },
            enabled = !state.busy,
            modifier = Modifier.testTag(OnboardingTestTags.CONTINUE),
        ) { Text(stringResource(R.string.onboarding_go_home)) }
    }
}

/**
 * The J-01 step-4 cards in place: Denied ([Try again] [Open settings] [Not now]) and the OEM help card ([Retry]
 * [Not now], plus the HyperOS 2+ note when the build is known to block other home apps).
 */
@Composable
fun SetDefaultCardContent(
    card: SetDefaultCard,
    onTryAgain: () -> Unit,
    onOpenSettings: () -> Unit,
    onNotNow: () -> Unit,
) {
    when (card) {
        SetDefaultCard.None -> {
            Unit
        }

        SetDefaultCard.Denied -> {
            CardSurface(OnboardingTestTags.DENIED_CARD) {
                Text(
                    text = stringResource(R.string.set_default_denied_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(stringResource(R.string.set_default_denied_body), style = MaterialTheme.typography.bodyMedium)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = onTryAgain, modifier = Modifier.testTag(OnboardingTestTags.TRY_AGAIN)) {
                        Text(stringResource(R.string.set_default_try_again))
                    }
                    OutlinedButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.testTag(OnboardingTestTags.OPEN_SETTINGS),
                    ) {
                        Text(stringResource(R.string.set_default_open_settings))
                    }
                    TextButton(onClick = onNotNow, modifier = Modifier.testTag(OnboardingTestTags.CARD_NOT_NOW)) {
                        Text(stringResource(R.string.onboarding_not_now))
                    }
                }
            }
        }

        is SetDefaultCard.OemHelp -> {
            CardSurface(OnboardingTestTags.OEM_HELP) {
                Text(
                    text = stringResource(R.string.set_default_oem_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(stringResource(R.string.set_default_oem_body), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.set_default_oem_steps), style = MaterialTheme.typography.bodyMedium)
                if (card.knownBlocking) {
                    Text(
                        text = stringResource(R.string.set_default_oem_hyperos),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag(OnboardingTestTags.OEM_BLOCKING_NOTE),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onNotNow, modifier = Modifier.testTag(OnboardingTestTags.CARD_NOT_NOW)) {
                        Text(stringResource(R.string.onboarding_not_now))
                    }
                    Button(onClick = onTryAgain, modifier = Modifier.testTag(OnboardingTestTags.RETRY)) {
                        Text(stringResource(R.string.set_default_retry))
                    }
                }
            }
        }
    }
}

/**
 * The step-4 cards as dialogs over home or settings, for the banner's and the settings row's attempts. "Not now" only
 * closes the card there.
 */
@Composable
fun SetDefaultDialogs(
    state: SetDefaultState,
    flow: SetDefaultFlow,
) {
    if (state.card == SetDefaultCard.None) return
    AlertDialog(
        onDismissRequest = flow::dismissCard,
        confirmButton = {},
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SetDefaultCardContent(
                    card = state.card,
                    onTryAgain = flow::setAsDefault,
                    onOpenSettings = flow::openSettings,
                    onNotNow = flow::dismissCard,
                )
            }
        },
    )
}

@Composable
private fun CardSurface(
    tag: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(tag)
                .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun Heading(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        modifier = modifier.semantics { heading() },
    )
}

@Composable
private fun Body(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun Buttons(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.weight(1f))
        content()
    }
}
