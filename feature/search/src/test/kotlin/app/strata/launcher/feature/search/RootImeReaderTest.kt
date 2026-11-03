package app.strata.launcher.feature.search

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The per-pre-draw IME read reuses its answer only while the platform insets are the same instance (C-5). */
class RootImeReaderTest {
    private class Probe {
        var insets: Any? = Any()
        var visible: Boolean? = true
        var reads = 0

        fun reader(sdk: Int) =
            RootImeReader(sdk, { insets }, {
                reads++
                visible
            })
    }

    @Test
    fun api30SameInsetsInstanceReusesTheAnswer() {
        val p = Probe()
        val r = p.reader(sdk = 30)
        assertThat(r.read()).isTrue()
        assertThat(r.read()).isTrue()
        assertThat(r.read()).isTrue()
        assertThat(p.reads).isEqualTo(1)
    }

    @Test
    fun api30NewInsetsInstanceReadsAgain() {
        val p = Probe()
        val r = p.reader(sdk = 36)
        assertThat(r.read()).isTrue()
        p.insets = Any()
        p.visible = false
        assertThat(r.read()).isFalse()
        assertThat(p.reads).isEqualTo(2)
    }

    @Test
    fun detachedViewGivesNoReadingAndForgetsTheCache() {
        val p = Probe()
        val r = p.reader(sdk = 34)
        val first = p.insets
        assertThat(r.read()).isTrue()
        p.insets = null
        assertThat(r.read()).isNull()
        p.insets = first
        p.visible = false
        assertThat(r.read()).isFalse()
        assertThat(p.reads).isEqualTo(2)
    }

    @Test
    fun nullReadingIsNotCached() {
        val p = Probe()
        p.visible = null
        val r = p.reader(sdk = 30)
        assertThat(r.read()).isNull()
        p.visible = true
        assertThat(r.read()).isTrue()
        assertThat(p.reads).isEqualTo(2)
    }

    @Test
    fun belowApi30EveryCallReads() {
        val p = Probe()
        val r = p.reader(sdk = 29)
        r.read()
        r.read()
        r.read()
        assertThat(p.reads).isEqualTo(3)
    }
}
