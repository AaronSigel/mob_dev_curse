package com.example.curse.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GalleryItemDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: GalleryItemEntity): Long

    @Query("SELECT * FROM gallery_item ORDER BY dateAdded DESC")
    suspend fun getAll(): List<GalleryItemEntity>

    @Query("SELECT * FROM gallery_item ORDER BY dateAdded DESC")
    fun getAllFlow(): Flow<List<GalleryItemEntity>>

    @Query("DELETE FROM gallery_item WHERE uriString = :uriString")
    suspend fun deleteByUri(uriString: String)

    @Query("DELETE FROM gallery_item")
    suspend fun clearAll()
}
