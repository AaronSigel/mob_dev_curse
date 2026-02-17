package com.example.curse.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Запись медиа в локальной БД Room (uri, тип, дата). */
@Entity(tableName = "gallery_item")
data class GalleryItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val uriString: String,
    val type: String,
    val dateAdded: Long
)
