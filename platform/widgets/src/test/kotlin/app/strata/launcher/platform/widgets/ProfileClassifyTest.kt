package app.strata.launcher.platform.widgets

import android.os.UserManager
import app.strata.launcher.core.model.ProfileKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Widget providers of other profiles (P-5): private space never offered or bound; unknown types fail closed. */
class ProfileClassifyTest {
    @Test
    fun belowApi35_otherProfilesAreWork() {
        assertThat(AndroidWidgetHost.classify(34, null)).isEqualTo(ProfileKind.WORK)
    }

    @Test
    fun api35_typeDecides_unknownIsSkipped() {
        assertThat(AndroidWidgetHost.classify(35, UserManager.USER_TYPE_PROFILE_MANAGED)).isEqualTo(ProfileKind.WORK)
        assertThat(AndroidWidgetHost.classify(35, UserManager.USER_TYPE_PROFILE_PRIVATE)).isEqualTo(ProfileKind.PRIVATE)
        assertThat(AndroidWidgetHost.classify(36, "android.os.usertype.profile.CLONE")).isEqualTo(ProfileKind.OTHER)
        // A type that could not be read: null = skip the profile (fail closed), never OTHER.
        assertThat(AndroidWidgetHost.classify(35, null)).isNull()
    }
}
