package app.strata.launcher.core.model

/** Identity of a launchable activity in one profile (ARCHITECTURE §3, §4.1). */
data class AppKey(
    /** `ComponentName.flattenToString()`. */
    val component: String,
    /** `UserManager.getSerialNumberForUser(user)`. */
    val userSerial: Long,
)

enum class ProfileKind { MAIN, WORK, PRIVATE, OTHER }
