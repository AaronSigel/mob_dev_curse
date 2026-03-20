package com.example.curse.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [GalleryItemEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun galleryItemDao(): GalleryItemDao
}
