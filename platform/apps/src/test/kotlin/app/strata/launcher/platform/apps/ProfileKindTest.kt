package app.strata.launcher.platform.apps

import android.os.UserManager
import app.strata.launcher.core.model.ProfileKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** FR-APPS-10, P-5: a profile whose type cannot be read on API 35+ is skipped (fail closed), never taken for WORK. */
class ProfileKindTest {
    @Test
    fun unknownTypeOnApi35IsSkipped() {
        assertThat(ProfileResolver.classify(35, null)).isNull()
        assertThat(ProfileResolver.classify(36, null)).isNull()
    }

    @Test
    fun knownTypes() {
        assertThat(ProfileResolver.classify(35, UserManager.USER_TYPE_PROFILE_MANAGED)).isEqualTo(ProfileKind.WORK)
        assertThat(ProfileResolver.classify(35, UserManager.USER_TYPE_PROFILE_PRIVATE)).isEqualTo(ProfileKind.PRIVATE)
        assertThat(ProfileResolver.classify(36, "android.os.usertype.profile.CLONE")).isEqualTo(ProfileKind.OTHER)
    }

    /** Below API 35 there is no private space and no type API: other profiles are work profiles. */
    @Test
    fun beforeApi35OtherProfilesAreWork() {
        assertThat(ProfileResolver.classify(34, null)).isEqualTo(ProfileKind.WORK)
        assertThat(ProfileResolver.classify(26, null)).isEqualTo(ProfileKind.WORK)
    }
}
