package com.thaweekarn.slipsync.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SlipEntity::class],
    version = 2,
    exportSchema = false
)
abstract class SlipDatabase : RoomDatabase() {

    abstract fun slipDao(): SlipDao

    companion object {
        @Volatile
        private var INSTANCE: SlipDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE slips ADD COLUMN localFilePath TEXT")
                db.execSQL("ALTER TABLE slips ADD COLUMN contentUriString TEXT")
                db.execSQL("ALTER TABLE slips ADD COLUMN normalizedTransactionId TEXT")
                db.execSQL("ALTER TABLE slips ADD COLUMN uploadLeaseUntil INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE slips ADD COLUMN workerRunId TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_slips_normalizedTransactionId ON slips(normalizedTransactionId)")
            }
        }

        fun getInstance(context: Context): SlipDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SlipDatabase::class.java,
                    "slip_sync.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
