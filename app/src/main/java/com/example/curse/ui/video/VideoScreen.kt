@file:Suppress("DEPRECATION")

package com.example.curse.ui.video

import android.content.ComponentCallbacks
import android.content.Context
import android.hardware.Camera
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import androidx.camera.core.CameraSelector
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.curse.media.MediaType
import com.example.curse.media.ensureVideoOutputDirExists
import com.example.curse.media.finalizeSessionRecordingsToGallery
import com.example.curse.media.importVideoFileToGallery
import com.example.curse.media.insertGalleryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.abs

private const val TAG = "VideoScreen"
private const val MIN_SEGMENT_DURATION_MS = 1_200L

/** Префикс для grep в logcat: `adb logcat | grep CurseVideo` */
private const val LOG_P = "[CurseVideo]"

private fun logFileBrief(f: File): String =
    "${f.name} len=${f.length()} exists=${f.exists()}"

private enum class PendingRecorderAction {
    NONE,
    SWITCH_CAMERA,
    STOP_SESSION
}

/** [Surface.ROTATION_*] текущего дисплея для превью и orientation hint у `MediaRecorder`. */
private fun Context.displayRotationCompat(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        display?.rotation ?: Surface.ROTATION_0
    } else {
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    }

private fun surfaceRotationToDegrees(rotation: Int): Int =
    when (rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

private fun findCameraIdForLensFacing(lensFacing: Int): Int? {
    val expectedFacing = when (lensFacing) {
        CameraSelector.LENS_FACING_FRONT -> Camera.CameraInfo.CAMERA_FACING_FRONT
        else -> Camera.CameraInfo.CAMERA_FACING_BACK
    }
    repeat(Camera.getNumberOfCameras()) { cameraId ->
        val info = Camera.CameraInfo()
        Camera.getCameraInfo(cameraId, info)
        if (info.facing == expectedFacing) return cameraId
    }
    return null
}

private fun previewDisplayOrientation(cameraId: Int, displayRotation: Int): Int {
    val info = Camera.CameraInfo()
    Camera.getCameraInfo(cameraId, info)
    val degrees = surfaceRotationToDegrees(displayRotation)
    return if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
        (360 - (info.orientation + degrees) % 360) % 360
    } else {
        (info.orientation - degrees + 360) % 360
    }
}

private fun mediaRecorderOrientationHint(cameraId: Int, displayRotation: Int): Int {
    val info = Camera.CameraInfo()
    Camera.getCameraInfo(cameraId, info)
    val degrees = surfaceRotationToDegrees(displayRotation)
    return if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
        (info.orientation + degrees) % 360
    } else {
        (info.orientation - degrees + 360) % 360
    }
}

private val preferredCamcorderQualities = listOf(
    CamcorderProfile.QUALITY_720P,
    CamcorderProfile.QUALITY_480P,
    CamcorderProfile.QUALITY_HIGH,
    CamcorderProfile.QUALITY_LOW
)

private fun chooseSessionCamcorderQuality(): Int? {
    val backId = findCameraIdForLensFacing(CameraSelector.LENS_FACING_BACK)
    val frontId = findCameraIdForLensFacing(CameraSelector.LENS_FACING_FRONT)
    return preferredCamcorderQualities.firstOrNull { quality ->
        val backOk = backId == null || CamcorderProfile.hasProfile(backId, quality)
        val frontOk = frontId == null || CamcorderProfile.hasProfile(frontId, quality)
        backOk && frontOk
    } ?: preferredCamcorderQualities.firstOrNull { quality ->
        listOfNotNull(backId, frontId).any { cameraId ->
            CamcorderProfile.hasProfile(cameraId, quality)
        }
    }
}

private fun selectCamcorderProfile(cameraId: Int, preferredQuality: Int? = null): CamcorderProfile? {
    val quality = when {
        preferredQuality != null && CamcorderProfile.hasProfile(cameraId, preferredQuality) -> preferredQuality
        else -> preferredCamcorderQualities.firstOrNull { CamcorderProfile.hasProfile(cameraId, it) }
    } ?: return null
    return CamcorderProfile.get(cameraId, quality)
}

private fun configureCameraParameters(camera: Camera, profile: CamcorderProfile) {
    runCatching {
        val parameters = camera.parameters
        val previewSize = parameters.supportedPreviewSizes
            ?.minByOrNull { size ->
                abs(size.width - profile.videoFrameWidth) + abs(size.height - profile.videoFrameHeight)
            }
        if (previewSize != null) {
            parameters.setPreviewSize(previewSize.width, previewSize.height)
        }
        if (parameters.supportedFocusModes?.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO) == true) {
            parameters.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
        }
        camera.parameters = parameters
    }.onFailure { error ->
        Log.w(TAG, "$LOG_P не удалось настроить параметры камеры", error)
    }
}

private suspend fun publishVideoSession(
    context: Context,
    segments: List<File>,
    lensSnapshot: List<Int>,
    dirToDelete: File?
) {
    if (segments.isEmpty()) {
        runCatching { dirToDelete?.deleteRecursively() }
        return
    }
    try {
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
            Log.e(TAG, "$LOG_P публикация сессии не удалась → fallback по одному файлу")
            for (i in segments.indices) {
                val file = segments[i]
                val uri = importVideoFileToGallery(context, file)
                Log.w(TAG, "$LOG_P fallback импорт: ${logFileBrief(file)} → uri=$uri")
                if (uri != null) {
                    insertGalleryItem(
                        context,
                        uri,
                        MediaType.VIDEO,
                        ts,
                        videoPlaybackRotationDegrees = 0
                    )
                }
            }
        }
    } finally {
        runCatching { dirToDelete?.deleteRecursively() }
    }
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
    var surfaceHolderRef by remember { mutableStateOf<SurfaceHolder?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var currentCameraId by remember { mutableStateOf<Int?>(null) }
    var mediaRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var activeSegmentFile by remember { mutableStateOf<File?>(null) }
    /** Сегменты сессии только на диске (cache), не в MediaStore — иначе галерея подхватывает их через сканирование папки Curse. */
    val sessionSegmentFiles = remember { mutableStateListOf<File>() }
    /** Порядок соответствует сегментам: какая камера была активна при старте каждого `startRecordingSegment`. */
    val sessionSegmentLensFacings = remember { mutableStateListOf<Int>() }
    var sessionCacheDir by remember { mutableStateOf<File?>(null) }
    var sessionPartIndex by remember { mutableIntStateOf(0) }
    var sessionQuality by remember { mutableStateOf<Int?>(null) }

    /** После смены камеры во время записи — автоматически начать следующий сегмент после открытия нового превью. */
    var pendingCameraSwitchResume by remember { mutableStateOf(false) }
    var pendingRecorderAction by remember { mutableStateOf(PendingRecorderAction.NONE) }
    var pendingLensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var currentSegmentStartedAtMs by remember { mutableLongStateOf(0L) }

    var isRecording by remember { mutableStateOf(false) }
    var recordSeconds by remember { mutableLongStateOf(0L) }

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

    fun releaseRecorder() {
        val recorder = mediaRecorder
        mediaRecorder = null
        activeSegmentFile = null
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
    }

    fun releaseCamera() {
        val openedCamera = camera
        camera = null
        currentCameraId = null
        runCatching { openedCamera?.stopPreview() }
        runCatching { openedCamera?.lock() }
        runCatching { openedCamera?.release() }
    }

    fun openPreviewCamera(): Boolean {
        val holder = surfaceHolderRef ?: return false
        val cameraId = findCameraIdForLensFacing(lensFacing) ?: run {
            Log.e(TAG, "$LOG_P не найдена камера для lensFacing=$lensFacing")
            return false
        }
        releaseRecorder()
        releaseCamera()
        return try {
            val openedCamera = Camera.open(cameraId)
            selectCamcorderProfile(cameraId, sessionQuality)?.let { profile ->
                configureCameraParameters(openedCamera, profile)
            }
            openedCamera.setDisplayOrientation(previewDisplayOrientation(cameraId, displayRotation))
            openedCamera.setPreviewDisplay(holder)
            openedCamera.startPreview()
            camera = openedCamera
            currentCameraId = cameraId
            true
        } catch (e: Exception) {
            Log.e(TAG, "$LOG_P не удалось открыть превью камеры", e)
            releaseCamera()
            false
        }
    }

    /**
     * Запускает сегмент через `MediaRecorder`. [resetTimer] — только при старте с FAB;
     * при продолжении после смены камеры таймер не сбрасывается.
     * @return false, если запустить не удалось (состояние «запись» сбрасывается вызывающим кодом при необходимости).
     */
    fun startRecordingSegment(resetTimer: Boolean): Boolean {
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
        if (camera == null && !openPreviewCamera()) {
            Log.e(TAG, "$LOG_P startSegment: не удалось открыть камеру")
            return false
        }
        val holder = surfaceHolderRef ?: run {
            Log.e(TAG, "$LOG_P startSegment: surfaceHolder == null")
            return false
        }
        val openedCamera = camera ?: run {
            Log.e(TAG, "$LOG_P startSegment: camera == null")
            return false
        }
        val cameraId = currentCameraId ?: run {
            Log.e(TAG, "$LOG_P startSegment: currentCameraId == null")
            return false
        }
        val effectiveQuality = sessionQuality ?: chooseSessionCamcorderQuality().also { sessionQuality = it }
        val profile = selectCamcorderProfile(cameraId, effectiveQuality) ?: run {
            Log.e(TAG, "$LOG_P startSegment: нет подходящего CamcorderProfile cameraId=$cameraId")
            return false
        }
        Log.d(
            TAG,
            "$LOG_P startSegment resetTimer=$resetTimer dir=${dir.absolutePath} out=${segmentFile.absolutePath}"
        )
        var preparedRecorder: MediaRecorder? = null
        return try {
            configureCameraParameters(openedCamera, profile)
            openedCamera.stopPreview()
            openedCamera.unlock()
            preparedRecorder = MediaRecorder().apply {
                setCamera(openedCamera)
                setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                setVideoSource(MediaRecorder.VideoSource.CAMERA)
                setProfile(profile)
                setOutputFile(segmentFile.absolutePath)
                setOrientationHint(mediaRecorderOrientationHint(cameraId, displayRotation))
                setPreviewDisplay(holder.surface)
                prepare()
                start()
            }
            mediaRecorder = preparedRecorder
            activeSegmentFile = segmentFile
            sessionSegmentLensFacings.add(lensFacing)
            currentSegmentStartedAtMs = SystemClock.elapsedRealtime()
            isRecording = true
            if (resetTimer) recordSeconds = 0L
            Log.i(TAG, "$LOG_P startSegment OK file=${logFileBrief(segmentFile)}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "$LOG_P startSegment: MediaRecorder start failed", e)
            runCatching { preparedRecorder?.reset() }
            runCatching { preparedRecorder?.release() }
            runCatching { openedCamera.lock() }
            releaseRecorder()
            releaseCamera()
            runCatching { segmentFile.delete() }
            false
        }
    }

    /**
     * Останавливает активный сегмент и возвращает готовый файл. Если запись не успела стартовать,
     * удаляем незавершённый выходной файл и выравниваем массив линз.
     */
    fun stopRecordingSegment(): File? {
        val recorder = mediaRecorder ?: return null
        val segmentFile = activeSegmentFile
        mediaRecorder = null
        activeSegmentFile = null
        var stopSucceeded = false
        try {
            recorder.stop()
            stopSucceeded = true
        } catch (e: RuntimeException) {
            Log.e(TAG, "$LOG_P stopSegment: MediaRecorder stop failed", e)
        } finally {
            runCatching { recorder.reset() }
            runCatching { recorder.release() }
            runCatching { camera?.lock() }
            releaseCamera()
        }
        val finishedFile =
            if (segmentFile != null && segmentFile.exists() && segmentFile.length() > 0L) {
                if (!stopSucceeded) {
                    Log.w(
                        TAG,
                        "$LOG_P stopSegment: stop() завершился с ошибкой, но файл оставлен для recovery " +
                            logFileBrief(segmentFile)
                    )
                }
                segmentFile
            } else {
                runCatching { segmentFile?.delete() }
                if (sessionSegmentLensFacings.isNotEmpty()) {
                    sessionSegmentLensFacings.removeAt(sessionSegmentLensFacings.lastIndex)
                }
                null
            }
        if (finishedFile != null) {
            Log.i(TAG, "$LOG_P stopSegment OK file=${logFileBrief(finishedFile)}")
        }
        return finishedFile
    }

    fun publishCompletedSession() {
        isRecording = false
        val segments = sessionSegmentFiles.toList()
        val lensSnapshot = sessionSegmentLensFacings.toList()
        sessionSegmentFiles.clear()
        sessionSegmentLensFacings.clear()
        val dirToDelete = sessionCacheDir
        sessionCacheDir = null
        sessionQuality = null
        if (lensSnapshot.size != segments.size) {
            Log.w(
                TAG,
                "$LOG_P USER_STOP: сегментов=${segments.size} ≠ lens=${lensSnapshot.size}"
            )
        }
        Log.i(
            TAG,
            "$LOG_P USER_STOP: к публикации сегментов=${segments.size} " +
                segments.joinToString { logFileBrief(it) }
        )
        scope.launch(Dispatchers.IO) {
            publishVideoSession(context, segments, lensSnapshot, dirToDelete)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (mediaRecorder != null) {
                Log.w(TAG, "$LOG_P onDispose: активная запись → USER_STOP")
                stopRecordingSegment()?.let { sessionSegmentFiles.add(it) }
            }
            // Сегменты после смены камеры без активной записи: иначе клип останется только в MediaStore без Room.
            val orphan = sessionSegmentFiles.toList()
            if (orphan.isNotEmpty()) {
                Log.w(
                    TAG,
                    "$LOG_P onDispose: сиротские сегменты n=${orphan.size} " +
                        orphan.joinToString { logFileBrief(it) }
                )
                val lensSnap = sessionSegmentLensFacings.toList()
                val dirToDelete = sessionCacheDir
                runBlocking(Dispatchers.IO) {
                    publishVideoSession(appContext, orphan, lensSnap, dirToDelete)
                }
            }
            sessionSegmentFiles.clear()
            sessionSegmentLensFacings.clear()
            sessionCacheDir = null
            sessionQuality = null
            pendingCameraSwitchResume = false
            isRecording = false
            releaseRecorder()
            releaseCamera()
        }
    }

    LaunchedEffect(pendingRecorderAction, pendingLensFacing) {
        if (pendingRecorderAction == PendingRecorderAction.NONE) return@LaunchedEffect
        val action = pendingRecorderAction
        val targetLensFacing = pendingLensFacing
        val elapsedSinceSegmentStart = SystemClock.elapsedRealtime() - currentSegmentStartedAtMs
        val remainingWarmupMs = (MIN_SEGMENT_DURATION_MS - elapsedSinceSegmentStart).coerceAtLeast(0L)
        if (remainingWarmupMs > 0L) {
            Log.w(
                TAG,
                "$LOG_P откладываем $action на ${remainingWarmupMs}мс: сегмент ещё слишком короткий"
            )
            delay(remainingWarmupMs)
        }
        if (!isRecording || mediaRecorder == null) {
            pendingRecorderAction = PendingRecorderAction.NONE
            return@LaunchedEffect
        }
        when (action) {
            PendingRecorderAction.SWITCH_CAMERA -> {
                Log.i(TAG, "$LOG_P выполняем отложенную смену камеры")
                stopRecordingSegment()?.let { finishedSegment ->
                    sessionSegmentFiles.add(finishedSegment)
                    Log.i(
                        TAG,
                        "$LOG_P сегмент после смены камеры: всего в сессии=${sessionSegmentFiles.size}"
                    )
                }
                pendingCameraSwitchResume = true
                lensFacing = targetLensFacing
            }
            PendingRecorderAction.STOP_SESSION -> {
                Log.i(TAG, "$LOG_P выполняем отложенный стоп записи")
                pendingCameraSwitchResume = false
                stopRecordingSegment()?.let { sessionSegmentFiles.add(it) }
                publishCompletedSession()
            }
            PendingRecorderAction.NONE -> Unit
        }
        pendingRecorderAction = PendingRecorderAction.NONE
    }

    // Таймер: при старте записи (isRecording=true) каждую секунду увеличиваем recordSeconds
    LaunchedEffect(isRecording) {
        if (!isRecording) return@LaunchedEffect
        while (true) {
            delay(1000)
            recordSeconds++
        }
    }

    // Когда рекордер не активен, держим превью открытым. После смены камеры этот же эффект
    // автоматически поднимет следующий сегмент на новой линзе.
    LaunchedEffect(hasPermission, lensFacing, displayRotation, isRecording) {
        if (!hasPermission) {
            releaseRecorder()
            releaseCamera()
            return@LaunchedEffect
        }
        if (surfaceHolderRef == null) return@LaunchedEffect
        if (mediaRecorder != null) return@LaunchedEffect
        if (!openPreviewCamera()) {
            Log.e(TAG, "$LOG_P preview: не удалось открыть камеру")
            pendingCameraSwitchResume = false
            if (isRecording) isRecording = false
            return@LaunchedEffect
        }
        if (pendingCameraSwitchResume && isRecording) {
            Log.d(TAG, "$LOG_P preview: автопродолжение записи после смены камеры")
            pendingCameraSwitchResume = false
            if (!startRecordingSegment(resetTimer = false)) {
                Log.e(TAG, "$LOG_P preview: не удалось запустить следующий сегмент")
                isRecording = false
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    holder.addCallback(
                        object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                surfaceHolderRef = holder
                            }

                            override fun surfaceChanged(
                                holder: SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int
                            ) {
                                surfaceHolderRef = holder
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                surfaceHolderRef = null
                                releaseCamera()
                            }
                        }
                    )
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
                    if (pendingRecorderAction != PendingRecorderAction.NONE) return@IconButton
                    Log.i(TAG, "$LOG_P UI: смена камеры во время записи")
                    pendingLensFacing =
                        if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                            CameraSelector.LENS_FACING_FRONT
                        } else {
                            CameraSelector.LENS_FACING_BACK
                        }
                    pendingRecorderAction = PendingRecorderAction.SWITCH_CAMERA
                    return@IconButton
                }
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                    CameraSelector.LENS_FACING_FRONT
                } else {
                    CameraSelector.LENS_FACING_BACK
                }
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
                    if (pendingRecorderAction != PendingRecorderAction.NONE) return@FloatingActionButton
                    Log.i(TAG, "$LOG_P UI: стоп записи пользователем")
                    pendingRecorderAction = PendingRecorderAction.STOP_SESSION
                } else {
                    runCatching { sessionCacheDir?.deleteRecursively() }
                    sessionCacheDir = File(
                        context.cacheDir,
                        "video_session_${System.currentTimeMillis()}"
                    ).apply { mkdirs() }
                    sessionPartIndex = 0
                    sessionQuality = chooseSessionCamcorderQuality()
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
