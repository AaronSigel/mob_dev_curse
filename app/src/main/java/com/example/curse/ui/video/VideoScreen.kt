package com.example.curse.ui.video

import android.content.ComponentCallbacks
import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import android.view.Surface
import android.view.ViewGroup
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.curse.media.MediaType
import android.os.Build
import com.example.curse.media.ensureVideoOutputDirExists
import com.example.curse.media.finalizeSessionRecordingsToGallery
import com.example.curse.media.importVideoFileToGallery
import com.example.curse.media.insertGalleryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private const val TAG = "VideoScreen"

/** Префикс для grep в logcat: `adb logcat | grep CurseVideo` */
private const val LOG_P = "[CurseVideo]"

private fun logFileBrief(f: File): String =
    "${f.name} len=${f.length()} exists=${f.exists()}"

/** Причина остановки записи: смена камеры (сегмент в сессии) или завершение пользователем (склейка/один файл). */
private enum class RecordingFinalizeKind {
    NONE,
    CAMERA_SWITCH,
    USER_STOP
}

/** Uri результата FileOutputOptions (CameraX) → локальный файл сегмента. */
private fun outputUriToSegmentFile(uri: Uri): File? {
    if (uri.scheme != "file") {
        Log.e(TAG, "Ожидался file:// для сегмента записи, получено: $uri")
        return null
    }
    val path = uri.path ?: return null
    return File(path)
}

/** [Surface.ROTATION_*] для привязки Preview/VideoCapture к текущему дисплею (метаданные поворота в MP4). */
private fun Context.displayRotationCompat(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        display?.rotation ?: Surface.ROTATION_0
    } else {
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    }

@Composable
fun VideoScreen(
    hasCameraPermission: Boolean,
    hasAudioPermission: Boolean,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appContext = remember { context.applicationContext }
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val hasPermission = hasCameraPermission && hasAudioPermission
    if (!hasPermission) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.foundation.layout.Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp)
            ) {
                Text(
                    text = "Для записи видео нужны доступ к камере и микрофону",
                    style = MaterialTheme.typography.bodyLarge
                )
                androidx.compose.material3.Button(
                    onClick = onRequestPermission,
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Text("Разрешить")
                }
            }
        }
        return
    }

    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var latestRecording by remember { mutableStateOf<Recording?>(null) }
    SideEffect { latestRecording = recording }

    var pendingFinalizeKind by remember { mutableStateOf(RecordingFinalizeKind.NONE) }
    /** Сегменты сессии только на диске (cache), не в MediaStore — иначе галерея подхватывает их через сканирование папки Curse. */
    val sessionSegmentFiles = remember { mutableStateListOf<File>() }
    /** Порядок соответствует сегментам: какая камера была активна при старте каждого `startRecordingSegment`. */
    val sessionSegmentLensFacings = remember { mutableStateListOf<Int>() }
    var sessionCacheDir by remember { mutableStateOf<File?>(null) }
    var sessionPartIndex by remember { mutableIntStateOf(0) }

    /** После смены камеры во время записи — автоматически начать следующий сегмент, когда Preview/VideoCapture снова привязаны. */
    var pendingCameraSwitchResume by remember { mutableStateOf(false) }

    var isRecording by remember { mutableStateOf(false) }
    var recordSeconds by remember { mutableLongStateOf(0L) }
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }

    var displayRotation by remember { mutableIntStateOf(Surface.ROTATION_0) }
    DisposableEffect(context) {
        fun refreshRotation() {
            displayRotation = context.displayRotationCompat()
        }
        refreshRotation()
        val app = context.applicationContext
        val callbacks = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
                refreshRotation()
            }

            override fun onLowMemory() {}
        }
        app.registerComponentCallbacks(callbacks)
        onDispose { app.unregisterComponentCallbacks(callbacks) }
    }

    /**
     * Запускает запись в MediaStore. [resetTimer] — только при старте с FAB; при продолжении после смены камеры таймер не сбрасывается.
     * @return false, если запустить не удалось (состояние «запись» сбрасывается вызывающим кодом при необходимости).
     */
    fun startRecordingSegment(resetTimer: Boolean): Boolean {
        val vc = videoCapture ?: run {
            Log.e(TAG, "startRecordingSegment: videoCapture == null")
            return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !ensureVideoOutputDirExists()) {
            Log.e(TAG, "Не удалось создать каталог для видео (нет разрешения на запись)")
            return false
        }
        val dir = sessionCacheDir ?: run {
            Log.e(TAG, "sessionCacheDir == null")
            return false
        }
        dir.mkdirs()
        val segmentFile = File(dir, "part_${sessionPartIndex++}.mp4")
        if (segmentFile.exists()) segmentFile.delete()
        Log.d(
            TAG,
            "$LOG_P startSegment resetTimer=$resetTimer dir=${dir.absolutePath} out=${segmentFile.absolutePath}"
        )
        val fileOptions = FileOutputOptions.Builder(segmentFile).build()
        recording = vc.output
            .prepareRecording(context, fileOptions)
            .start(mainExecutor) { event ->
                when (event) {
                    is VideoRecordEvent.Finalize -> {
                        if (event.hasError()) {
                            Log.e(TAG, "$LOG_P Finalize: ошибка CameraX, event=$event")
                            pendingFinalizeKind = RecordingFinalizeKind.NONE
                            pendingCameraSwitchResume = false
                            sessionSegmentFiles.clear()
                            sessionSegmentLensFacings.clear()
                            isRecording = false
                            return@start
                        }
                        val uri = event.outputResults.outputUri ?: run {
                            Log.e(TAG, "$LOG_P Finalize: outputUri == null")
                            pendingCameraSwitchResume = false
                            sessionSegmentFiles.clear()
                            sessionSegmentLensFacings.clear()
                            isRecording = false
                            return@start
                        }
                        val segmentFile = outputUriToSegmentFile(uri) ?: run {
                            Log.e(TAG, "$LOG_P Finalize: не file:// uri=$uri")
                            pendingCameraSwitchResume = false
                            sessionSegmentFiles.clear()
                            sessionSegmentLensFacings.clear()
                            isRecording = false
                            return@start
                        }
                        val kindRaw = pendingFinalizeKind.also {
                            pendingFinalizeKind = RecordingFinalizeKind.NONE
                        }
                        val kind = if (kindRaw == RecordingFinalizeKind.NONE) {
                            Log.w(
                                TAG,
                                "$LOG_P Finalize: pendingKind был NONE → трактуем как USER_STOP (uri=$uri)"
                            )
                            RecordingFinalizeKind.USER_STOP
                        } else {
                            kindRaw
                        }
                        Log.d(
                            TAG,
                            "$LOG_P Finalize: kindRaw=$kindRaw kind=$kind file=${logFileBrief(segmentFile)} " +
                                "накопленоДоДобавления=${sessionSegmentFiles.size}"
                        )
                        when (kind) {
                            RecordingFinalizeKind.CAMERA_SWITCH -> {
                                sessionSegmentFiles.add(segmentFile)
                                Log.i(
                                    TAG,
                                    "$LOG_P сегмент после смены камеры: всего в сессии=${sessionSegmentFiles.size}"
                                )
                            }
                            RecordingFinalizeKind.USER_STOP -> {
                                sessionSegmentFiles.add(segmentFile)
                                val segments = sessionSegmentFiles.toList()
                                val lensSnapshot = sessionSegmentLensFacings.toList()
                                sessionSegmentFiles.clear()
                                sessionSegmentLensFacings.clear()
                                if (lensSnapshot.size != segments.size) {
                                    Log.w(
                                        TAG,
                                        "$LOG_P USER_STOP: сегментов=${segments.size} ≠ lens=${lensSnapshot.size}"
                                    )
                                }
                                val dirToDelete = sessionCacheDir
                                Log.i(
                                    TAG,
                                    "$LOG_P USER_STOP: к публикации сегментов=${segments.size} " +
                                        segments.joinToString { logFileBrief(it) }
                                )
                                scope.launch(Dispatchers.IO) {
                                    val ts = System.currentTimeMillis() / 1000
                                    val published = finalizeSessionRecordingsToGallery(
                                        context,
                                        segments,
                                        lensSnapshot
                                    )
                                    if (published.isNotEmpty()) {
                                        Log.i(TAG, "$LOG_P опубликовано клипов=${published.size}")
                                        for ((uri, rot) in published) {
                                            insertGalleryItem(
                                                context,
                                                uri,
                                                MediaType.VIDEO,
                                                ts,
                                                videoPlaybackRotationDegrees = rot
                                            )
                                        }
                                    } else {
                                        Log.e(
                                            TAG,
                                            "$LOG_P публикация сессии не удалась → fallback по одному файлу"
                                        )
                                        for (i in segments.indices) {
                                            val f = segments[i]
                                            val u = importVideoFileToGallery(context, f)
                                            Log.w(TAG, "$LOG_P fallback импорт: ${logFileBrief(f)} → uri=$u")
                                            if (u != null) {
                                                val rot =
                                                    if (lensSnapshot.getOrNull(i) == CameraSelector.LENS_FACING_FRONT) {
                                                        180
                                                    } else {
                                                        0
                                                    }
                                                insertGalleryItem(
                                                    context,
                                                    u,
                                                    MediaType.VIDEO,
                                                    ts,
                                                    videoPlaybackRotationDegrees = rot
                                                )
                                            }
                                        }
                                    }
                                    runCatching { dirToDelete?.deleteRecursively() }
                                }
                            }
                            RecordingFinalizeKind.NONE -> { }
                        }
                    }
                    else -> {}
                }
            }
        sessionSegmentLensFacings.add(lensFacing)
        isRecording = true
        if (resetTimer) recordSeconds = 0L
        return true
    }

    DisposableEffect(Unit) {
        onDispose {
            val hadRecording = latestRecording != null
            latestRecording?.let { active ->
                Log.w(TAG, "$LOG_P onDispose: активная запись → USER_STOP + stop()")
                pendingFinalizeKind = RecordingFinalizeKind.USER_STOP
                active.stop()
            }
            // Сегменты после смены камеры без активной записи: иначе клип останется только в MediaStore без Room.
            if (!hadRecording) {
                val orphan = sessionSegmentFiles.toList()
                if (orphan.isNotEmpty()) {
                    Log.w(
                        TAG,
                        "$LOG_P onDispose: сиротские сегменты n=${orphan.size} " +
                            orphan.joinToString { logFileBrief(it) }
                    )
                    runBlocking(Dispatchers.IO) {
                        val ts = System.currentTimeMillis() / 1000
                        val lensSnap = sessionSegmentLensFacings.toList()
                        if (lensSnap.size != orphan.size) {
                            Log.w(
                                TAG,
                                "$LOG_P onDispose: orphan=${orphan.size} ≠ lens=${lensSnap.size}"
                            )
                        }
                        val published = finalizeSessionRecordingsToGallery(appContext, orphan, lensSnap)
                        if (published.isNotEmpty()) {
                            Log.i(TAG, "$LOG_P onDispose: опубликовано клипов=${published.size}")
                            for ((uri, rot) in published) {
                                insertGalleryItem(
                                    appContext,
                                    uri,
                                    MediaType.VIDEO,
                                    ts,
                                    videoPlaybackRotationDegrees = rot
                                )
                            }
                        } else {
                            for (i in orphan.indices) {
                                val f = orphan[i]
                                val u = importVideoFileToGallery(appContext, f)
                                Log.w(TAG, "$LOG_P onDispose fallback: ${logFileBrief(f)} → $u")
                                if (u != null) {
                                    val rot =
                                        if (lensSnap.getOrNull(i) == CameraSelector.LENS_FACING_FRONT) 180 else 0
                                    insertGalleryItem(
                                        appContext,
                                        u,
                                        MediaType.VIDEO,
                                        ts,
                                        videoPlaybackRotationDegrees = rot
                                    )
                                }
                            }
                        }
                    }
                    sessionSegmentFiles.clear()
                    sessionSegmentLensFacings.clear()
                    runCatching { sessionCacheDir?.deleteRecursively() }
                }
            }
        }
    }

    // Таймер: при старте записи (isRecording=true) каждую секунду увеличиваем recordSeconds
    LaunchedEffect(isRecording) {
        if (!isRecording) return@LaunchedEffect
        while (true) {
            delay(1000)
            recordSeconds++
        }
    }

    // isRecording в ключах: после стопа перепривязать камеру, если поворот менялся во время записи
    // (пока recording != null, эффект выходит раньше и не трогает активную сессию).
    LaunchedEffect(hasPermission, lensFacing, displayRotation, isRecording) {
        val pv = previewViewRef ?: return@LaunchedEffect
        if (recording != null) return@LaunchedEffect
        val provider = ProcessCameraProvider.getInstance(context).get()
        provider.unbindAll()
        val preview = Preview.Builder()
            .setTargetRotation(displayRotation)
            .build()
            .also {
                it.setSurfaceProvider(pv.surfaceProvider)
            }
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD))
            .build()
        val vc = VideoCapture.withOutput(recorder).apply {
            setTargetRotation(displayRotation)
        }
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        try {
            provider.bindToLifecycle(lifecycleOwner, selector, preview, vc)
            videoCapture = vc
            // Продолжение записи после смены камеры: новый сегмент сразу после привязки.
            if (pendingCameraSwitchResume && isRecording) {
                Log.d(TAG, "$LOG_P rebind: автопродолжение записи после смены камеры")
                pendingCameraSwitchResume = false
                if (!startRecordingSegment(resetTimer = false)) {
                    Log.e(TAG, "$LOG_P rebind: не удалось startRecordingSegment")
                    isRecording = false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bind failed", e)
            pendingCameraSwitchResume = false
            isRecording = false
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    previewViewRef = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (isRecording) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(24.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                        RoundedCornerShape(999.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "%02d:%02d".format(recordSeconds / 60, recordSeconds % 60),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        IconButton(
            onClick = {
                if (isRecording) {
                    Log.i(TAG, "$LOG_P UI: смена камеры во время записи")
                    pendingFinalizeKind = RecordingFinalizeKind.CAMERA_SWITCH
                    pendingCameraSwitchResume = true
                    recording?.stop()
                    recording = null
                    // isRecording остаётся true — запись продолжится на другой камере после rebind.
                }
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                    CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Icon(
                Icons.Default.Cameraswitch,
                contentDescription = "Переключить камеру"
            )
        }

        FloatingActionButton(
            onClick = {
                if (isRecording) {
                    Log.i(TAG, "$LOG_P UI: стоп записи пользователем")
                    pendingCameraSwitchResume = false
                    pendingFinalizeKind = RecordingFinalizeKind.USER_STOP
                    recording?.stop()
                    recording = null
                    isRecording = false
                } else {
                    runCatching { sessionCacheDir?.deleteRecursively() }
                    sessionCacheDir = File(
                        context.cacheDir,
                        "video_session_${System.currentTimeMillis()}"
                    ).apply { mkdirs() }
                    sessionPartIndex = 0
                    sessionSegmentFiles.clear()
                    sessionSegmentLensFacings.clear()
                    Log.i(TAG, "$LOG_P UI: старт новой сессии dir=${sessionCacheDir?.absolutePath}")
                    if (!startRecordingSegment(resetTimer = true)) {
                        isRecording = false
                        sessionCacheDir = null
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
                .size(72.dp)
                .clip(CircleShape),
            containerColor = if (isRecording) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) {
            if (isRecording) {
                Icon(Icons.Default.Stop, contentDescription = "Стоп", modifier = Modifier.size(32.dp))
            } else {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onPrimary)
                )
            }
        }
    }
}
