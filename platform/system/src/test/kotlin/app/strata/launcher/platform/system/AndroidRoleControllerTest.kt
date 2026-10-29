package app.strata.launcher.platform.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.platformapi.system.SystemIntent
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** RoleController fallback path on API 26–28 (supports T-ONBOARD-04; device test stays I-level). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class AndroidRoleControllerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun registerHome(component: ComponentName) {
        val pm = shadowOf(context.packageManager)
        pm.addActivityIfNotPresent(component)
        pm.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            },
        )
    }

    @Test
    fun api28_requestsHomeSettings() {
        val controller = AndroidRoleController(context)
        assertThat(controller.requestIntent()).isEqualTo(SystemIntent.HomeSettings)
        assertThat(controller.fallbackIntents())
            .containsExactly(SystemIntent.HomeSettings, SystemIntent.DefaultAppsSettings, SystemIntent.Settings)
            .inOrder()
        assertThat(SystemIntentFactory.create(context, SystemIntent.HomeSettings)?.action)
            .isEqualTo(Settings.ACTION_HOME_SETTINGS)
        assertThat(SystemIntentFactory.create(context, SystemIntent.RequestHomeRole)).isNull()
    }

    @Test
    fun api28_defaultWhenOurHomeResolves() {
        registerHome(ComponentName(context.packageName, "app.strata.launcher.home.HomeActivity"))
        assertThat(AndroidRoleController(context).isDefaultHome()).isTrue()
    }

    @Test
    fun api28_notDefaultWhenAnotherHomeResolves() {
        registerHome(ComponentName("com.example.otherhome", "com.example.otherhome.Home"))
        assertThat(AndroidRoleController(context).isDefaultHome()).isFalse()
    }
}
