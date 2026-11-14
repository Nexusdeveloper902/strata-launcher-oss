package app.strata.launcher.platform.system

import android.content.Context
import android.content.res.XmlResourceParser
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/**
 * T-PERF-14 (NFR-PERF-05, battery): the double-tap lock service is enabled system-wide, so it must ask for nothing it
 * does not use. No event types (the system then sends it no accessibility event at all, from any app), no window
 * content, no flags beyond the default, and a long notification timeout should event types ever be added.
 */
@RunWith(AndroidJUnit4::class)
class LockServiceConfigTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun attributes(): Map<String, String?> {
        val parser: XmlResourceParser = context.resources.getXml(R.xml.lock_accessibility_service)
        parser.use {
            while (it.next() != XmlPullParser.END_DOCUMENT) {
                if (it.eventType == XmlPullParser.START_TAG && it.name == "accessibility-service") {
                    return (0 until it.attributeCount).associate { i ->
                        it.getAttributeName(i) to it.getAttributeValue(i)
                    }
                }
            }
        }
        error("no <accessibility-service> element")
    }

    @Test
    fun tPerf14_lockServiceAsksForNothingItDoesNotUse() {
        val attrs = attributes()
        // Absent = 0: no AccessibilityEvent of any type is delivered, so no per-event work can exist.
        assertThat(attrs).doesNotContainKey("accessibilityEventTypes")
        assertThat(attrs).doesNotContainKey("packageNames")
        assertThat(attrs["canRetrieveWindowContent"]).isEqualTo("false")
        assertThat(attrs).doesNotContainKey("canRequestFilterKeyEvents")
        assertThat(attrs).doesNotContainKey("canRequestTouchExplorationMode")
        assertThat(attrs).doesNotContainKey("canPerformGestures")
        // flagDefault only (1): no key-event filtering, no view ids, no interactive windows, no fingerprint gestures.
        assertThat(attrs["accessibilityFlags"]?.let(::intOf)).isEqualTo(FLAG_DEFAULT)
        assertThat(attrs["notificationTimeout"]?.let(::intOf) ?: 0).isAtLeast(MIN_TIMEOUT_MS)
    }

    private fun intOf(value: String): Int =
        when {
            value == "flagDefault" -> FLAG_DEFAULT
            value.startsWith("0x") -> value.removePrefix("0x").toInt(HEX)
            else -> value.toInt()
        }

    private companion object {
        const val FLAG_DEFAULT = 1
        const val MIN_TIMEOUT_MS = 1_000
        const val HEX = 16
    }
}
