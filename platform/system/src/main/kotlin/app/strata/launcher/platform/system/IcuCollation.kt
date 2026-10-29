package app.strata.launcher.platform.system

import android.icu.text.AlphabeticIndex
import android.icu.text.Collator
import android.icu.util.ULocale
import app.strata.launcher.core.platformapi.system.Collation
import app.strata.launcher.core.platformapi.system.SectionModel
import java.util.concurrent.ConcurrentHashMap

/**
 * [Collation] over `android.icu` (API 24+, so always available at minSdk 26). FR-LIST-01, NFR-I18N-03:
 * labels are ordered by the primary locale's `Collator`; sections are `AlphabeticIndex` buckets of the
 * primary locale plus the labels of the other locales. The underflow bucket (digits and symbols) is
 * shown as "#" and placed last (owner decision, docs/impl-notes/M1.md); other buckets keep ICU's label and
 * order (the overflow bucket is "…").
 * Results are cached per locale list; collators are frozen, so both are thread-safe.
 */
class IcuCollation : Collation {
    private val comparators = ConcurrentHashMap<List<String>, Comparator<String>>()
    private val sectionModels = ConcurrentHashMap<List<String>, SectionModel>()

    override fun comparator(locales: List<String>): Comparator<String> =
        comparators.getOrPut(normalize(locales)) {
            val collator = Collator.getInstance(ULocale.forLanguageTag(normalize(locales).first())).freeze()
            Comparator { a, b -> collator.compare(a, b) }
        }

    override fun sections(locales: List<String>): SectionModel =
        sectionModels.getOrPut(normalize(locales)) { build(normalize(locales)) }

    private fun build(locales: List<String>): SectionModel {
        val index = AlphabeticIndex<String>(ULocale.forLanguageTag(locales.first()))
        locales.drop(1).forEach { index.addLabels(ULocale.forLanguageTag(it)) }
        val immutable = index.buildImmutableIndex()
        val icuLabels =
            (0 until immutable.bucketCount).map { i ->
                val bucket = immutable.getBucket(i)
                if (bucket.labelType == AlphabeticIndex.Bucket.LabelType.UNDERFLOW) null else bucket.label
            }
        val underflow = icuLabels.indexOf(null)
        if (underflow <
            0
        ) {
            return SectionModel(icuLabels.map { it.orEmpty() }) { name -> immutable.getBucketIndex(name) }
        }
        // Move the "#" bucket from ICU's first position to the end; every other bucket shifts by one.
        val labels = icuLabels.filterNotNull() + Collation.DIGIT_BUCKET
        val last = labels.size - 1
        return SectionModel(labels) { name ->
            val b = immutable.getBucketIndex(name)
            when {
                b == underflow -> last
                b > underflow -> b - 1
                else -> b
            }
        }
    }

    private fun normalize(locales: List<String>): List<String> =
        locales.filter { it.isNotBlank() }.ifEmpty {
            listOf(
                "en",
            )
        }
}
