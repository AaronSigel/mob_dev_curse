package com.example.curse.media

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

private const val TAG = "VideoSegmentMerge"

/** Префикс для grep: `adb logcat | grep CurseMerge` */
private const val LOG_P = "[CurseMerge]"

private fun logFileBrief(f: File): String =
    "${f.name} len=${f.length()} path=${f.absolutePath}"

/**
 * Копирует готовый MP4 с диска в коллекцию видео MediaStore (Movies/Curse), снимает IS_PENDING.
 * Сегменты записи держим во [Context.getCacheDir], чтобы не попадали в выборку галереи до финала сессии.
 */
suspend fun importVideoFileToGallery(context: Context, sourceFile: File): Uri? =
    withContext(Dispatchers.IO) {
        if (!sourceFile.exists() || sourceFile.length() == 0L) {
            Log.w(TAG, "$LOG_P importVideo: пропуск (нет файла или 0 байт): ${logFileBrief(sourceFile)}")
            return@withContext null
        }
        Log.d(TAG, "$LOG_P importVideo: из ${logFileBrief(sourceFile)}")
        val name = newVideoFileName()
        val cv = createVideoContentValues(name)
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val outUri = context.contentResolver.insert(collection, cv) ?: return@withContext null
        val written = context.contentResolver.openOutputStream(outUri)?.use { output ->
            sourceFile.inputStream().use { input -> input.copyTo(output) }
        } != null
        if (!written) {
            Log.e(TAG, "$LOG_P importVideo: не удалось записать поток в MediaStore uri=$outUri")
            context.contentResolver.delete(outUri, null, null)
            return@withContext null
        }
        setVideoPendingFalse(context, outUri)
        Log.i(TAG, "$LOG_P importVideo: OK displayName=$name uri=$outUri")
        outUri
    }

/** Результат `MediaRecorder`/`Transformer` готов к воспроизведению как есть. */
private const val PLAYBACK_ROTATION_DEGREES = 0

private fun segmentToEditedMediaItem(file: File): EditedMediaItem =
    EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(file))).build()

private suspend fun mergeSegmentsWithTransformer(
    context: Context,
    segmentFiles: List<File>,
    outputFile: File
): Boolean {
    val sequence = EditedMediaItemSequence.Builder(
        segmentFiles.map(::segmentToEditedMediaItem)
    ).build()
    val composition = Composition.Builder(sequence).build()
    return withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val transformer = Transformer.Builder(context)
                .addListener(
                    object : Transformer.Listener {
                        override fun onCompleted(
                            composition: Composition,
                            exportResult: ExportResult
                        ) {
                            Log.i(
                                TAG,
                                "$LOG_P merge: Transformer completed durationMs=${exportResult.durationMs} " +
                                    "file=${logFileBrief(outputFile)}"
                            )
                            if (continuation.isActive) continuation.resume(true)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            Log.e(TAG, "$LOG_P merge: Transformer error", exportException)
                            if (continuation.isActive) continuation.resume(false)
                        }
                    }
                )
                .build()
            continuation.invokeOnCancellation { transformer.cancel() }
            runCatching {
                if (outputFile.exists()) outputFile.delete()
                transformer.start(composition, outputFile.absolutePath)
            }.onFailure { error ->
                Log.e(TAG, "$LOG_P merge: Transformer start failed", error)
                if (continuation.isActive) continuation.resume(false)
            }
        }
    }
}

/**
 * Завершение сессии записи на базе `MediaRecorder`.
 *
 * Каждый сегмент `MediaRecorder` сначала сохраняется как самостоятельный MP4. Если сегментов
 * несколько, собираем их в один итоговый файл через `Media3 Transformer`, чтобы пользователь
 * получил один ролик даже после нескольких переключений камеры. Если склейка не удалась,
 * публикуем сегменты по одному как безопасный fallback.
 *
 * @return список `(uri, videoPlaybackRotationDegrees)` для [insertGalleryItem].
 */
suspend fun finalizeSessionRecordingsToGallery(
    context: Context,
    segmentFiles: List<File>,
    lensFacingPerSegment: List<Int>
): List<Pair<Uri, Int>> =
    withContext(Dispatchers.IO) {
        Log.d(
            TAG,
            "$LOG_P finalize: вход segmentFiles=${segmentFiles.size} " +
                segmentFiles.joinToString { logFileBrief(it) }
        )
        if (segmentFiles.size != lensFacingPerSegment.size) {
            Log.w(
                TAG,
                "$LOG_P finalize: segmentFiles.size=${segmentFiles.size} != lens.size=${lensFacingPerSegment.size}; " +
                    "продолжаем по доступным файлам"
            )
        }

        val existingFiles = segmentFiles.filter { it.exists() && it.length() > 0L }
        if (existingFiles.isEmpty()) {
            Log.e(TAG, "$LOG_P finalize: нет валидных файлов после фильтра")
            return@withContext emptyList()
        }

        suspend fun importFilesIndividually(files: List<File>): List<Pair<Uri, Int>> {
            val published = mutableListOf<Pair<Uri, Int>>()
            for ((index, file) in files.withIndex()) {
                Log.i(
                    TAG,
                    "$LOG_P finalize: импорт сегмента ${index + 1}/${files.size} ${logFileBrief(file)}"
                )
                val uri = importVideoFileToGallery(context, file) ?: continue
                published += uri to PLAYBACK_ROTATION_DEGREES
                runCatching { file.delete() }
            }
            return published
        }

        if (existingFiles.size == 1) {
            Log.i(TAG, "$LOG_P finalize: один сегмент → прямой импорт")
            return@withContext importFilesIndividually(existingFiles)
        }

        val mergeDir = File(context.cacheDir, "video_merge_${System.currentTimeMillis()}").apply {
            mkdirs()
        }
        try {
            val mergedFile = File(mergeDir, "merged.mp4")
            Log.i(
                TAG,
                "$LOG_P finalize: склейка сегментов=${existingFiles.size} " +
                    "lens=${lensFacingPerSegment.joinToString()}"
            )
            if (!mergeSegmentsWithTransformer(context, existingFiles, mergedFile)) {
                Log.e(TAG, "$LOG_P finalize: mergeSegmentsWithTransformer=false → fallback по сегментам")
                return@withContext importFilesIndividually(existingFiles)
            }
            val uri = importVideoFileToGallery(context, mergedFile) ?: run {
                Log.e(TAG, "$LOG_P finalize: merged import failed → fallback по сегментам")
                return@withContext importFilesIndividually(existingFiles)
            }
            existingFiles.forEach { runCatching { it.delete() } }
            Log.i(TAG, "$LOG_P finalize: итоговый клип опубликован uri=$uri")
            listOf(uri to PLAYBACK_ROTATION_DEGREES)
        } finally {
            runCatching { mergeDir.deleteRecursively() }
        }
    }
