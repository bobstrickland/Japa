package org.strickland.japa.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase


@Database(
    entities = [Record::class, PrayerSet::class, SetMember::class],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordDao(): RecordDao
    abstract fun prayerSetDao(): PrayerSetDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        /**
         * Adds prayer sets. Purely additive — the DDL is copied from the exported v2 schema so
         * Room's validation of the migrated database matches exactly, and existing prayers are
         * left untouched.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `prayer_sets` " +
                        "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `set_members` " +
                        "(`setId` INTEGER NOT NULL, `recordId` INTEGER NOT NULL, " +
                        "`position` INTEGER NOT NULL, PRIMARY KEY(`setId`, `recordId`), " +
                        "FOREIGN KEY(`setId`) REFERENCES `prayer_sets`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE , " +
                        "FOREIGN KEY(`recordId`) REFERENCES `records`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_set_members_recordId` " +
                        "ON `set_members` (`recordId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_set_members_setId_position` " +
                        "ON `set_members` (`setId`, `position`)"
                )
            }
        }

        /**
         * Makes prayer names unique.
         *
         * Purely additive: user prayers ship for the first time in this version, so no existing
         * library can hold a duplicate for the index to trip over.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_records_name` ON `records` (`name`)"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "app-db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build().also { INSTANCE = it }
            }
    }
}
