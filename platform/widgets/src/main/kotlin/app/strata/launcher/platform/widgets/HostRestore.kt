package app.strata.launcher.platform.widgets

/**
 * Reads an `APPWIDGET_HOST_RESTORED` broadcast (ANDROID_PLATFORM §7, ARCHITECTURE §13): only with `EXTRA_HOST_ID` =
 * [expectedHostId];
 * the extras must be int arrays of the same length, at most [MAX_IDS] each. Returns old ID → new ID (null when the
 * new ID is not valid), or null when the broadcast is not usable. Pure, so the bounds are unit-tested.
 */
object HostRestore {
    const val MAX_IDS = 64

    fun parse(
        expectedHostId: Int,
        hostId: Int?,
        oldIds: IntArray?,
        newIds: IntArray?,
    ): Map<Int, Int?>? {
        // The platform always names the host; a broadcast without our host ID is not ours to apply.
        if (hostId == null || hostId != expectedHostId) return null
        if (oldIds == null || newIds == null) return null
        if (oldIds.size != newIds.size || oldIds.isEmpty() || oldIds.size > MAX_IDS) return null
        val out = LinkedHashMap<Int, Int?>()
        for (i in oldIds.indices) {
            val old = oldIds[i]
            if (old <= 0) continue
            out[old] = newIds[i].takeIf { it > 0 }
        }
        return out.takeIf { it.isNotEmpty() }
    }
}
