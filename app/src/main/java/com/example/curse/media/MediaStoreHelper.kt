package com.example.curse.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.curse.CurseApplication
import com.example.curse.db.GalleryItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Имя подпапки в стандартных каталогах для медиа приложения. */
private const val APP_MEDIA_FOLDER = "Curse"

/** Элемент галереи: uri, тип, дата добавления. */
data class GalleryItem(
    val id: Long,
    val uri: Uri,
    val type: MediaType,
    val dateAdded: Long
)

enum class MediaType { PHOTO, VIDEO }

/**
 * Создаёт ContentValues для вставки фото в MediaStore (Scoped Storage).
 * RELATIVE_PATH, DISPLAY_NAME, IS_PENDING по ТЗ.
 */
fun createImageContentValues(displayName: String): ContentValues = ContentValues().apply {
    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$APP_MEDIA_FOLDER")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    } else {
        @Suppress("DEPRECATION")
        val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            .absolutePath + "/$APP_MEDIA_FOLDER/$displayName"
        put(MediaStore.Images.Media.DATA, path)
    }
}

/**
 * Создаёт ContentValues для вставки видео в MediaStore.
 * На API &lt; 29 задаётся DATA (путь к файлу) для записи в общее хранилище.
 */
fun createVideoContentValues(displayName: String): ContentValues = ContentValues().apply {
    put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$APP_MEDIA_FOLDER")
        put(MediaStore.Video.Media.IS_PENDING, 1)
    } else {
        @Suppress("DEPRECATION")
        val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            .absolutePath + "/$APP_MEDIA_FOLDER/$displayName"
        put(MediaStore.Video.Media.DATA, path)
    }
}

/**
 * Генерирует уникальное имя файла с датой.
 */
fun newImageFileName(): String {
    val format = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    return "IMG_${format.format(Date())}.jpg"
}

/** URI коллекции изображений MediaStore для вставки (передавать в ImageCapture, не URI строки). */
fun imageCollectionUri(): Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
} else {
    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
}

/**
 * Подготавливает каталог для сохранения фото на API &lt; 29. Вернуть false при ошибке (нет разрешения).
 */
fun ensureImageOutputDirExists(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true
    @Suppress("DEPRECATION")
    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), APP_MEDIA_FOLDER)
    return dir.exists() || dir.mkdirs()
}

/**
 * Подготавливает каталог для сохранения видео на API &lt; 29. Вернуть false при ошибке (нет разрешения).
 */
fun ensureVideoOutputDirExists(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true
    @Suppress("DEPRECATION")
    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), APP_MEDIA_FOLDER)
    return dir.exists() || dir.mkdirs()
}

fun newVideoFileName(): String {
    val format = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    return "VID_${format.format(Date())}.mp4"
}

/**
 * Помечает запись MediaStore как завершённую (IS_PENDING = 0).
 * Вызывать после успешной записи файла. На API &lt; 29 колонки IS_PENDING нет — не выполняем update.
 */
suspend fun setImagePendingFalse(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.contentResolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null,
            null
        )
    }
}

suspend fun setVideoPendingFalse(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.contentResolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
            null,
            null
        )
    }
}

/**
 * Загружает список медиа и синхронизирует кэш Room с актуальным состоянием MediaStore.
 * При ошибке чтения MediaStore возвращает локальный кэш из Room.
 */
suspend fun loadGalleryItems(context: Context): List<GalleryItem> = withContext(Dispatchers.IO) {
    val dao = CurseApplication.getDatabase(context).galleryItemDao()
    val cached = dao.getAll().map { entity ->
        GalleryItem(
            id = entity.id,
            uri = Uri.parse(entity.uriString),
            type = if (entity.type == MediaType.PHOTO.name) MediaType.PHOTO else MediaType.VIDEO,
            dateAdded = entity.dateAdded
        )
    }

    val mediaStoreItems = runCatching { loadGalleryItemsFromMediaStoreUnsafe(context) }
        .getOrElse { return@withContext cached }

    dao.clearAll()
    mediaStoreItems.forEach { item ->
        dao.insert(
            GalleryItemEntity(
                uriString = item.uri.toString(),
                type = item.type.name,
                dateAdded = item.dateAdded
            )
        )
    }
    mediaStoreItems
}

/** Вставка записи в Room после сохранения фото/видео в MediaStore. */
suspend fun insertGalleryItem(context: Context, uri: Uri, type: MediaType, dateAdded: Long) =
    withContext(Dispatchers.IO) {
        CurseApplication.getDatabase(context).galleryItemDao().insert(
            GalleryItemEntity(uriString = uri.toString(), type = type.name, dateAdded = dateAdded)
        )
    }

/** Удаление записи из Room после удаления файла через MediaStore. */
suspend fun deleteGalleryItemByUri(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
    CurseApplication.getDatabase(context).galleryItemDao().deleteByUri(uri.toString())
}

/**
 * Загрузка списка медиа из MediaStore (для первичной синхронизации в Room).
 * При отсутствии разрешения на чтение медиа возвращает пустой список (без падения).
 */
private suspend fun loadGalleryItemsFromMediaStore(context: Context): List<GalleryItem> =
    withContext(Dispatchers.IO) {
        try {
            loadGalleryItemsFromMediaStoreUnsafe(context)
        } catch (e: SecurityException) {
            emptyList()
        }
    }

private fun loadGalleryItemsFromMediaStoreUnsafe(context: Context): List<GalleryItem> {
    val list = mutableListOf<GalleryItem>()
    val pathFilter = "%$APP_MEDIA_FOLDER%"
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DATE_ADDED
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
                arrayOf(pathFilter),
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    list.add(
                        GalleryItem(
                            id = id,
                            uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()),
                            type = MediaType.PHOTO,
                            dateAdded = cursor.getLong(dateIdx)
                        )
                    )
                }
            }
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DATE_ADDED),
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
                arrayOf(pathFilter),
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    list.add(
                        GalleryItem(
                            id = id,
                            uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString()),
                            type = MediaType.VIDEO,
                            dateAdded = cursor.getLong(dateIdx)
                        )
                    )
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val dataColumn = MediaStore.Images.Media.DATA
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                "$dataColumn LIKE ?",
                arrayOf(pathFilter),
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    list.add(
                        GalleryItem(
                            id = id,
                            uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()),
                            type = MediaType.PHOTO,
                            dateAdded = cursor.getLong(dateIdx)
                        )
                    )
                }
            }
            @Suppress("DEPRECATION")
            val videoDataColumn = MediaStore.Video.Media.DATA
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DATE_ADDED),
                "$videoDataColumn LIKE ?",
                arrayOf(pathFilter),
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    list.add(
                        GalleryItem(
                            id = id,
                            uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString()),
                            type = MediaType.VIDEO,
                            dateAdded = cursor.getLong(dateIdx)
                        )
                    )
                }
            }
        }
        list.sortByDescending { it.dateAdded }
        return list
    }
