package app.strata.launcher.core.data.backup

import androidx.room.withTransaction
import app.strata.launcher.core.data.LazyResource
import app.strata.launcher.core.data.db.AppOverrideEntity
import app.strata.launcher.core.data.db.FavoriteEntity
import app.strata.launcher.core.data.db.StrataDatabase
import app.strata.launcher.core.data.list.ICON_KIND_PACK
import app.strata.launcher.core.data.list.iconOverrideOf
import app.strata.launcher.core.data.widgets.toEntity
import app.strata.launcher.core.data.widgets.toModel
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.AppOverride
import app.strata.launcher.core.platformapi.apps.LabelRules
import app.strata.launcher.core.platformapi.backup.AppData
import app.strata.launcher.core.platformapi.backup.BackupAppStore

/**
 * [BackupAppStore] over `strata.db` (ARCHITECTURE §12.3 step 3b/3e): the three tables are read in one transaction and
 * replaced in one transaction, so a crash leaves either the old or the new rows (NFR-REL-02).
 */
class RoomBackupAppStore(
    private val database: LazyResource<StrataDatabase>,
) : BackupAppStore {
    override suspend fun read(): AppData {
        val db = database.get()
        return db.withTransaction {
            AppData(
                overrides =
                    db.overrideDao().all().map {
                        AppOverride(
                            key = AppKey(it.component, it.userSerial),
                            hidden = it.hidden,
                            customLabel = it.customLabel,
                            icon = iconOverrideOf(it.iconKind, it.iconPack, it.iconDrawable),
                            missingSince = it.missingSince,
                        )
                    },
                favorites = db.favoriteDao().all().map { AppKey(it.component, it.userSerial) },
                widgets = db.widgetPlacementDao().all().map { it.toModel() },
            )
        }
    }

    override suspend fun replace(data: AppData) {
        val db = database.get()
        val overrides =
            data.overrides.distinctBy { it.key }.map {
                AppOverrideEntity(
                    component = it.key.component,
                    userSerial = it.key.userSerial,
                    hidden = it.hidden,
                    customLabel = it.customLabel?.let(LabelRules::normalize),
                    iconKind = it.icon?.let { ICON_KIND_PACK },
                    iconPack = it.icon?.packPackage,
                    iconDrawable = it.icon?.drawable,
                    missingSince = it.missingSince,
                )
            }
        val favorites =
            data.favorites.distinct().mapIndexed { i, k -> FavoriteEntity(i, k.component, k.userSerial) }
        val widgets = data.widgets.distinctBy { it.appWidgetId }.map { it.toEntity() }
        db.withTransaction {
            db.overrideDao().deleteAll()
            if (overrides.isNotEmpty()) db.overrideDao().insertAll(overrides)
            db.favoriteDao().deleteAll()
            if (favorites.isNotEmpty()) db.favoriteDao().insertAll(favorites)
            db.widgetPlacementDao().deleteAll()
            if (widgets.isNotEmpty()) db.widgetPlacementDao().insertAll(widgets)
        }
    }
}
