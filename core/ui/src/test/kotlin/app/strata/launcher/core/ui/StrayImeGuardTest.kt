package app.strata.launcher.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.model.AppKey
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-HOME-14 (C, field level): [StrayImeGuard] with the screen state saying "no field" ([allowed] false) never hides a
 * keyboard while an [imeField] has focus, for each field type home uses (a plain `BasicTextField` like search, an
 * `OutlinedTextField` like rename, and the shared [AppPicker]). Once the field loses focus or leaves the composition,
 * a visible keyboard is hidden again.
 */
@RunWith(AndroidJUnit4::class)
class StrayImeGuardTest {
    @get:Rule
    val compose = createComposeRule()

    private var hides = 0
    private var imeVisible by mutableStateOf(false)
    private var shown by mutableStateOf(true)

    private fun setUp(field: @androidx.compose.runtime.Composable () -> Unit) {
        val fields = ImeFields()
        compose.setContent {
            CompositionLocalProvider(LocalImeFields provides fields) {
                StrayImeGuard(allowed = false, fields = fields, imeVisible = imeVisible) { hides++ }
                if (shown) field()
            }
        }
    }

    private fun focusThenShowKeyboard(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        imeVisible = true
        compose.waitForIdle()
        compose.onNodeWithTag(tag).assertIsFocused()
        assertThat(hides).isEqualTo(0)
    }

    private fun removeFieldHidesKeyboard() {
        shown = false
        compose.waitForIdle()
        assertThat(hides).isEqualTo(1)
    }

    @Test
    fun basicTextField() {
        setUp { BasicTextField("q", {}, modifier = Modifier.imeField().testTag(TAG)) }
        focusThenShowKeyboard(TAG)
        removeFieldHidesKeyboard()
    }

    @Test
    fun outlinedTextField() {
        setUp { OutlinedTextField("Maps", {}, modifier = Modifier.imeField().testTag(TAG)) }
        focusThenShowKeyboard(TAG)
        removeFieldHidesKeyboard()
    }

    @Test
    fun appPicker() {
        setUp {
            Column {
                AppPicker(
                    apps = emptyList(),
                    query = "",
                    picked = emptyList<AppKey>(),
                    limitReached = false,
                    texts = AppPickerTexts("0 of 12", "Limit", "Search apps"),
                    tags = AppPickerTags(TAG, "list", "count", "limit") { "row" },
                    onQuery = {},
                    onToggle = {},
                )
            }
        }
        focusThenShowKeyboard(TAG)
        removeFieldHidesKeyboard()
    }

    @Test
    fun noFieldFocusedHides() {
        setUp { BasicTextField("q", {}, modifier = Modifier.imeField().testTag(TAG)) }
        imeVisible = true
        compose.waitForIdle()
        assertThat(hides).isEqualTo(1)
    }

    private companion object {
        const val TAG = "field"
    }
}
