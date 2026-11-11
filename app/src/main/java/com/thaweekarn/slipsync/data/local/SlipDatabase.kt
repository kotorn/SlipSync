package com.thaweekarn.slipsync.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SlipEntity::class],
    version = 1,
    exportSchema = false
)
abstract class SlipDatabase : RoomDatabase() {

    abstract fun slipDao(): SlipDao

    companion object {
        @Volatile
        private var INSTANCE: SlipDatabase? = null

        fun getInstance(context: Context): SlipDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SlipDatabase::class.java,
                    "slip_sync.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
