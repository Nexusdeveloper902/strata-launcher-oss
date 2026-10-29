package app.strata.launcher.testing.fakes

import app.strata.launcher.core.platformapi.system.Collation
import app.strata.launcher.core.platformapi.system.HapticKind
import app.strata.launcher.core.platformapi.system.Haptics
import app.strata.launcher.core.platformapi.system.SectionModel
import java.text.Normalizer
import java.util.Locale

/** Counts haptic calls per kind (T-LIST-04). */
class FakeHaptics : Haptics {
    val calls = mutableListOf<HapticKind>()

    fun count(kind: HapticKind): Int = calls.count { it == kind }

    override fun tick(kind: HapticKind) {
        calls += kind
    }
}

/**
 * JVM stand-in for the ICU adapter: `java.text.Collator` order, sections A–Z (accents folded), "…" for
 * other scripts and "#" last for digits and symbols (same order as the ICU adapter). Locale-specific buckets (Å, Ä, Ö in Swedish) need the real adapter (T-LIST-01).
 */
class FakeCollation : Collation {
    override fun comparator(locales: List<String>): Comparator<String> {
        val collator = java.text.Collator.getInstance(Locale.forLanguageTag(locales.firstOrNull() ?: "en"))
        return Comparator { a, b -> synchronized(collator) { collator.compare(a, b) } }
    }

    override fun sections(locales: List<String>): SectionModel =
        SectionModel(LABELS) { label ->
            val first =
                Normalizer
                    .normalize(label.trim().take(2), Normalizer.Form.NFD)
                    .firstOrNull()
                    ?.uppercaseChar()
            when {
                first == null -> LABELS.size - 1
                first in 'A'..'Z' -> first - 'A'
                first.isLetter() -> LABELS.size - 2
                else -> LABELS.size - 1
            }
        }

    companion object {
        val LABELS: List<String> = ('A'..'Z').map { it.toString() } + "…" + Collation.DIGIT_BUCKET
    }
}
