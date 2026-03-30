package com.reminder.multistage
import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Template::class, Stage::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun reminderDao(): ReminderDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "reminder_db")
                    .fallbackToDestructiveMigration() // 强制迁移会清空旧数据，适合开发阶段
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
