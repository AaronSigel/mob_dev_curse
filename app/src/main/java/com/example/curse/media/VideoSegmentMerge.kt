package com.example.curse.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.media3.common.MediaItem
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
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

/**
 * Завершение сессии записи: один сегмент импортируем напрямую, несколько сегментов нормализуем в один итоговый
 * MP4 через Media3 Transformer. Для фронтальных сегментов применяем поворот 180°, чтобы геометрия совпадала
 * с сегментами основной камеры до финальной склейки.
 *
 * @return список `(uri, videoPlaybackRotationDegrees)` для [insertGalleryItem]; обычно один элемент.
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
            Log.e(
                TAG,
                "$LOG_P finalize: segmentFiles.size=${segmentFiles.size} != lens.size=${lensFacingPerSegment.size}"
            )
            return@withContext emptyList()
        }
        val paired = segmentFiles.zip(lensFacingPerSegment)
        val existingPaired = paired.filter { (f, _) -> f.exists() && f.length() > 0L }
        if (existingPaired.isEmpty()) {
            Log.e(TAG, "$LOG_P finalize: нет валидных файлов после фильтра")
            return@withContext emptyList()
        }

        suspend fun importAsSeparateClips(pairs: List<Pair<File, Int>>): List<Pair<Uri, Int>> {
            val out = mutableListOf<Pair<Uri, Int>>()
            for ((f, lens) in pairs) {
                val uri = importVideoFileToGallery(context, f) ?: continue
                val rot = if (lens == CameraSelector.LENS_FACING_FRONT) 180 else 0
                out.add(uri to rot)
                runCatching { f.delete() }
            }
            return out
        }

        if (existingPaired.size == 1) {
            Log.i(TAG, "$LOG_P finalize: один сегмент → прямой импорт в MediaStore")
            return@withContext importAsSeparateClips(existingPaired)
        }

        val filesOnly = existingPaired.map { it.first }
        val rotsMeta = filesOnly.map { videoFileRotationDegrees(it) }
        Log.i(
            TAG,
            "$LOG_P finalize: склейка ${existingPaired.size} файлов rots=$rotsMeta " +
                "lens=${existingPaired.map { it.second }}"
        )
        val workDir = File(context.cacheDir, "video_merge_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val normalized = normalizeSegmentsForMerge(context, existingPaired, workDir) ?: run {
                Log.e(TAG, "$LOG_P finalize: normalizeSegmentsForMerge=false → отдельные клипы")
                return@withContext importAsSeparateClips(existingPaired)
            }
            val outFile = File(workDir, "merged.mp4")
            if (!mergeMp4Files(normalized, outFile)) {
                Log.e(TAG, "$LOG_P finalize: mergeMp4Files=false → отдельные клипы")
                return@withContext importAsSeparateClips(existingPaired)
            }
            Log.d(
                TAG,
                "$LOG_P finalize: merge OK mergedLen=${outFile.length()} path=${outFile.absolutePath}"
            )
            val outUri = importVideoFileToGallery(context, outFile) ?: run {
                Log.e(TAG, "$LOG_P finalize: import merged вернул null → отдельные клипы")
                return@withContext importAsSeparateClips(existingPaired)
            }
            filesOnly.forEach { runCatching { it.delete() } }
            Log.i(TAG, "$LOG_P finalize: готово итоговый uri=$outUri")
            listOf(outUri to 0)
        } finally {
            workDir.deleteRecursively()
        }
    }

private fun segmentToEditedMediaItem(file: File, lensFacing: Int): EditedMediaItem {
    val builder = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(file)))
    if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
        val rotateFrontSegment = ScaleAndRotateTransformation.Builder()
            .setRotationDegrees(180f)
            .build()
        builder.setEffects(Effects(emptyList(), listOf(rotateFrontSegment)))
    }
    return builder.build()
}

private suspend fun normalizeSegmentsForMerge(
    context: Context,
    sources: List<Pair<File, Int>>,
    tempDir: File
): List<File>? {
    val sourceFiles = sources.map { it.first }
    val sourceRotations = sourceFiles.map { videoFileRotationDegrees(it) }
    val targetRotation = sourceRotations.firstOrNull() ?: 0
    Log.i(
        TAG,
        "$LOG_P normalize: sourceRotations=$sourceRotations targetRotation=$targetRotation"
    )

    val out = mutableListOf<File>()
    for ((index, pair) in sources.withIndex()) {
        val (src, lensFacing) = pair
        val srcRotation = videoFileRotationDegrees(src)
        if (srcRotation == targetRotation) {
            out.add(src)
            continue
        }
        var candidate = src
        if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            val rotated = File(tempDir, "rotated_$index.mp4")
            if (!exportSingleSegmentWithTransformer(context, src, rotated, rotationDegrees = 180f)) {
                Log.e(
                    TAG,
                    "$LOG_P normalize: transformer rotate failed for ${src.name} lens=$lensFacing srcRotation=$srcRotation"
                )
                return null
            }
            candidate = rotated
            Log.i(
                TAG,
                "$LOG_P normalize: rotated front ${src.name} -> ${rotated.name} rot=${videoFileRotationDegrees(rotated)}"
            )
        }
        val candidateRotation = videoFileRotationDegrees(candidate)
        if (candidateRotation == targetRotation) {
            out.add(candidate)
            continue
        }
        val normalized = File(tempDir, "normalized_$index.mp4")
        if (!remuxSingleFileWithOrientationHint(candidate, normalized, targetRotation)) {
            Log.e(
                TAG,
                "$LOG_P normalize: remux failed for ${candidate.name} lens=$lensFacing srcRotation=$candidateRotation target=$targetRotation"
            )
            return null
        }
        val normalizedRotation = videoFileRotationDegrees(normalized)
        Log.i(
            TAG,
            "$LOG_P normalize: ${src.name} lens=$lensFacing rot=$srcRotation -> ${normalized.name} rot=$normalizedRotation"
        )
        out.add(normalized)
    }
    return out
}

private suspend fun exportSingleSegmentWithTransformer(
    context: Context,
    source: File,
    outFile: File,
    rotationDegrees: Float
): Boolean {
    val rotateEffect = ScaleAndRotateTransformation.Builder()
        .setRotationDegrees(rotationDegrees)
        .build()
    val editedItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source)))
        .setEffects(Effects(emptyList(), listOf(rotateEffect)))
        .build()
    val composition = Composition.Builder(EditedMediaItemSequence.Builder(editedItem).build()).build()
    return withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Boolean> { continuation ->
            val transformer = Transformer.Builder(context)
                .addListener(
                    object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (continuation.isActive) continuation.resume(true)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            Log.e(TAG, "$LOG_P Transformer single export error", exportException)
                            if (continuation.isActive) continuation.resume(false)
                        }
                    }
                )
                .build()
            continuation.invokeOnCancellation { transformer.cancel() }
            runCatching {
                if (outFile.exists()) outFile.delete()
                transformer.start(composition, outFile.absolutePath)
            }.onFailure { error ->
                Log.e(TAG, "$LOG_P Transformer single start failed", error)
                if (continuation.isActive) continuation.resume(false)
            }
        }
    }
}

/**
 * Склейка MP4 после нормализации ориентации: копирование сэмплов без перекодирования.
 */
private fun mergeMp4Files(sources: List<File>, outFile: File): Boolean {
    if (sources.isEmpty()) return false
    if (outFile.exists()) outFile.delete()
    var muxer: MediaMuxer? = null
    try {
        muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val probe = MediaExtractor()
        probe.setDataSource(sources.first().absolutePath)
        val vIn0 = findTrackIndex(probe, "video/")
        val aIn0 = findTrackIndex(probe, "audio/")
        if (vIn0 < 0) {
            probe.release()
            return false
        }
        val muxerVideo = muxer.addTrack(probe.getTrackFormat(vIn0))
        val muxerAudio = if (aIn0 >= 0) muxer.addTrack(probe.getTrackFormat(aIn0)) else -1
        val orientationHint = videoFileRotationDegrees(sources.first())
        probe.release()
        muxer.setOrientationHint(orientationHint)
        muxer.start()

        var videoBaseUs = 0L
        var audioBaseUs = 0L
        for (src in sources) {
            val (nextVideoBase, nextAudioBase) = appendFileInterleaved(
                src,
                muxer,
                muxerVideo,
                muxerAudio,
                videoBaseUs,
                audioBaseUs
            )
            videoBaseUs = nextVideoBase
            audioBaseUs = nextAudioBase
        }
        muxer.stop()
        return true
    } catch (e: Exception) {
        Log.e(TAG, "$LOG_P mergeMp4Files: failed", e)
        return false
    } finally {
        runCatching { muxer?.release() }
    }
}

private fun appendFileInterleaved(
    file: File,
    muxer: MediaMuxer,
    muxerVideo: Int,
    muxerAudio: Int,
    videoBaseUs: Long,
    audioBaseUs: Long
): Pair<Long, Long> {
    val vEx = MediaExtractor()
    val aEx = MediaExtractor()
    vEx.setDataSource(file.absolutePath)
    aEx.setDataSource(file.absolutePath)
    val vIn = findTrackIndex(vEx, "video/")
    val aIn = findTrackIndex(aEx, "audio/")
    if (vIn < 0) {
        vEx.release()
        aEx.release()
        return videoBaseUs to audioBaseUs
    }
    vEx.selectTrack(vIn)
    val hasAudio = muxerAudio >= 0 && aIn >= 0
    if (hasAudio) aEx.selectTrack(aIn)

    val vBuf = ByteBuffer.allocateDirect(trackMaxBufferSize(vEx, vIn))
    val aBuf = if (hasAudio) ByteBuffer.allocateDirect(trackMaxBufferSize(aEx, aIn)) else null
    val vInfo = MediaCodec.BufferInfo()
    val aInfo = MediaCodec.BufferInfo()

    var vFirstRaw = -1L
    var aFirstRaw = -1L
    fun normVideoPts(raw: Long): Long {
        if (vFirstRaw < 0) vFirstRaw = raw
        return raw - vFirstRaw + videoBaseUs
    }
    fun normAudioPts(raw: Long): Long {
        if (aFirstRaw < 0) aFirstRaw = raw
        return raw - aFirstRaw + audioBaseUs
    }
    fun readNextVideo(): Triple<Long, Int, Int>? {
        vBuf.clear()
        val sz = vEx.readSampleData(vBuf, 0)
        if (sz < 0) return null
        val t = vEx.sampleTime
        val flags = vEx.sampleFlags
        vEx.advance()
        return Triple(t, sz, flags)
    }
    fun readNextAudio(): Triple<Long, Int, Int>? {
        if (!hasAudio || aBuf == null) return null
        aBuf.clear()
        val sz = aEx.readSampleData(aBuf, 0)
        if (sz < 0) return null
        val t = aEx.sampleTime
        val flags = aEx.sampleFlags
        aEx.advance()
        return Triple(t, sz, flags)
    }

    var nextV = readNextVideo()
    var nextA = readNextAudio()
    var lastVideoOutPts = videoBaseUs
    var lastAudioOutPts = audioBaseUs
    while (nextV != null || nextA != null) {
        val vPts = nextV?.let { (raw, _, _) -> normVideoPts(raw) } ?: Long.MAX_VALUE
        val aPts = nextA?.let { (raw, _, _) -> normAudioPts(raw) } ?: Long.MAX_VALUE
        if (nextV != null && vPts <= aPts) {
            val (_, sz, flags) = nextV
            vBuf.position(0)
            vBuf.limit(sz)
            vInfo.offset = 0
            vInfo.size = sz
            vInfo.presentationTimeUs = vPts
            vInfo.flags = flags
            muxer.writeSampleData(muxerVideo, vBuf, vInfo)
            lastVideoOutPts = vPts
            nextV = readNextVideo()
        } else if (nextA != null && aBuf != null) {
            val (_, sz, flags) = nextA
            aBuf.position(0)
            aBuf.limit(sz)
            aInfo.offset = 0
            aInfo.size = sz
            aInfo.presentationTimeUs = aPts
            aInfo.flags = flags
            muxer.writeSampleData(muxerAudio, aBuf, aInfo)
            lastAudioOutPts = aPts
            nextA = readNextAudio()
        } else {
            break
        }
    }

    vEx.release()
    aEx.release()
    val nextVideoBase = lastVideoOutPts + 33_333L
    val nextAudioBase = if (hasAudio) lastAudioOutPts + 1L else audioBaseUs
    return nextVideoBase to nextAudioBase
}

private fun findTrackIndex(extractor: MediaExtractor, mimePrefix: String): Int {
    for (i in 0 until extractor.trackCount) {
        val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
        if (mime.startsWith(mimePrefix)) return i
    }
    return -1
}

/** Нормализует угол поворота до 0 / 90 / 180 / 270. */
private fun normalizeMuxerOrientationDegrees(degrees: Int): Int {
    val d = ((degrees % 360) + 360) % 360
    return when (d) {
        90, 180, 270 -> d
        else -> 0
    }
}

/**
 * Поворот первого сегмента для итогового MP4: из [MediaFormat] дорожки или через [MediaMetadataRetriever].
 */
private fun mergedOutputOrientationDegrees(firstSegment: File, videoTrackFormat: MediaFormat): Int {
    runCatching {
        if (videoTrackFormat.containsKey(MediaFormat.KEY_ROTATION)) {
            val deg = videoTrackFormat.getInteger(MediaFormat.KEY_ROTATION)
            return normalizeMuxerOrientationDegrees(deg)
        }
    }
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(firstSegment.absolutePath)
        val meta = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        normalizeMuxerOrientationDegrees(meta)
    } catch (_: Exception) {
        0
    } finally {
        runCatching { r.release() }
    }
}

private fun videoFileRotationDegrees(file: File): Int {
    val ex = MediaExtractor()
    return try {
        ex.setDataSource(file.absolutePath)
        val vi = findTrackIndex(ex, "video/")
        if (vi < 0) {
            0
        } else {
            mergedOutputOrientationDegrees(file, ex.getTrackFormat(vi))
        }
    } catch (_: Exception) {
        0
    } finally {
        runCatching { ex.release() }
    }
}

private fun trackMaxBufferSize(extractor: MediaExtractor, trackIndex: Int): Int {
    val fmt = extractor.getTrackFormat(trackIndex)
    return try {
        fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
    } catch (_: Exception) {
        1024 * 1024
    }.coerceIn(64 * 1024, 16 * 1024 * 1024)
}

/**
 * Перепаковывает один MP4, сохраняя сэмплы как есть, но с принудительным orientation hint.
 * Это быстрее перекодирования и достаточно, когда проблема в метаданных поворота.
 */
private fun remuxSingleFileWithOrientationHint(input: File, output: File, orientationHint: Int): Boolean {
    if (output.exists()) output.delete()
    val ex = MediaExtractor()
    var muxer: MediaMuxer? = null
    try {
        ex.setDataSource(input.absolutePath)
        val vIn = findTrackIndex(ex, "video/")
        val aIn = findTrackIndex(ex, "audio/")
        if (vIn < 0) return false

        muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val muxerVideo = muxer.addTrack(ex.getTrackFormat(vIn))
        val muxerAudio = if (aIn >= 0) muxer.addTrack(ex.getTrackFormat(aIn)) else -1
        muxer.setOrientationHint(orientationHint)
        muxer.start()

        fun copyTrack(inTrack: Int, outTrack: Int) {
            if (inTrack < 0 || outTrack < 0) return
            ex.unselectTrack(vIn)
            if (aIn >= 0) ex.unselectTrack(aIn)
            ex.selectTrack(inTrack)
            val buf = ByteBuffer.allocateDirect(trackMaxBufferSize(ex, inTrack))
            val info = MediaCodec.BufferInfo()
            while (true) {
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = ex.sampleTime
                info.flags = ex.sampleFlags
                buf.position(0)
                buf.limit(size)
                muxer?.writeSampleData(outTrack, buf, info)
                ex.advance()
            }
        }

        copyTrack(vIn, muxerVideo)
        copyTrack(aIn, muxerAudio)
        muxer.stop()
        return true
    } catch (e: Exception) {
        Log.e(TAG, "$LOG_P remuxSingleFileWithOrientationHint: failed", e)
        return false
    } finally {
        runCatching { ex.release() }
        runCatching { muxer?.release() }
    }
}
