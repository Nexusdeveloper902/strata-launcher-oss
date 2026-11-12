package app.strata.launcher.core.data.widgets

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.data.LazyResource
import app.strata.launcher.core.data.db.StrataDatabase
import app.strata.launcher.core.model.WidgetPlacement
import app.strata.launcher.core.model.WidgetPlacementState
import app.strata.launcher.core.model.WidgetProviderRef
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** `widget_placement` over a real Room file (level R): T-WIDGET-06 (persists), T-WIDGET-07 (row deleted), T-WIDGET-08 (remap). */
@RunWith(AndroidJUnit4::class)
class RoomWidgetRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var db = open()

    private fun open(): StrataDatabase =
        Room
            .databaseBuilder(context, StrataDatabase::class.java, FILE)
            .allowMainThreadQueries()
            .build()

    private fun repo() = RoomWidgetRepository(LazyResource(Dispatchers.IO) { db })

    private fun reopen() {
        db.close()
        db = open()
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(FILE)
    }

    private val ref = WidgetProviderRef("fixture.widgets/.Clock", 0L)

    @Test
    fun tWidget06_heightStepPersistsAcrossRestart_andIsClamped() =
        runBlocking<Unit> {
            repo().place(WidgetPlacement(7, ref, heightStep = 1, replaceClock = true))
            repo().setHeightStep(7, 3)
            reopen()
            val p = repo().placement.first()!!
            assertThat(p.heightStep).isEqualTo(3)
            assertThat(p.replaceClock).isTrue()
            assertThat(p.provider).isEqualTo(ref)
            repo().setHeightStep(7, 9)
            assertThat(repo().current()!!.heightStep).isEqualTo(4)
            repo().setHeightStep(7, 0)
            assertThat(repo().current()!!.heightStep).isEqualTo(1)
        }

    @Test
    fun placeReplacesTheOnlyRow_andReturnsOldIds() =
        runBlocking<Unit> {
            assertThat(repo().place(WidgetPlacement(7, ref))).isEmpty()
            assertThat(repo().place(WidgetPlacement(8, ref))).containsExactly(7)
            assertThat(db.widgetPlacementDao().all().map { it.appWidgetId }).containsExactly(8)
        }

    @Test
    fun tWidget07_removeDeletesTheRow() =
        runBlocking<Unit> {
            repo().place(WidgetPlacement(7, ref))
            repo().remove(7)
            assertThat(repo().placement.first()).isNull()
        }

    @Test
    fun tWidget08_remapMovesToTheNewId_missingNewIdIsUnavailable() =
        runBlocking<Unit> {
            repo().place(WidgetPlacement(5, ref, heightStep = 2))
            repo().remap(mapOf(5 to 9))
            val moved = repo().current()!!
            assertThat(moved.appWidgetId).isEqualTo(9)
            assertThat(moved.state).isEqualTo(WidgetPlacementState.BOUND)
            assertThat(moved.heightStep).isEqualTo(2)
            repo().remap(mapOf(9 to null))
            assertThat(repo().current()!!.state).isEqualTo(WidgetPlacementState.UNAVAILABLE)
            // An unrelated mapping changes nothing.
            repo().remap(mapOf(1 to 2))
            assertThat(repo().current()!!.appWidgetId).isEqualTo(9)
        }

    private companion object {
        const val FILE = "widgets-test.db"
    }
}
