package com.example.curse

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.example.curse.db.AppDatabase

class CurseApplication : Application() {
    val database: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "curse.db")
            .build()
    }

    companion object {
        fun getDatabase(context: Context): AppDatabase =
            (context.applicationContext as CurseApplication).database
    }
}
