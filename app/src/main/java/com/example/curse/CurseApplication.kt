package com.example.curse

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.curse.db.AppDatabase

class CurseApplication : Application() {
    val database: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "curse.db")
            .addMigrations(
                object : Migration(1, 2) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "ALTER TABLE gallery_item ADD COLUMN videoPlaybackRotationDegrees INTEGER NOT NULL DEFAULT 0"
                        )
                    }
                }
            )
            .build()
    }

    companion object {
        fun getDatabase(context: Context): AppDatabase =
            (context.applicationContext as CurseApplication).database
    }
}
