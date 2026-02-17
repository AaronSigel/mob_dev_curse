package com.example.curse.ui.video

import android.content.Context
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.mutableLongStateOf
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
import com.example.curse.media.createVideoContentValues
import com.example.curse.media.ensureVideoOutputDirExists
import com.example.curse.media.insertGalleryItem
import com.example.curse.media.newVideoFileName
import com.example.curse.media.setVideoPendingFalse
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "VideoScreen"

@Composable
fun VideoScreen(
    hasCameraPermission: Boolean,
    hasAudioPermission: Boolean,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
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
    var isRecording by remember { mutableStateOf(false) }
    var recordSeconds by remember { mutableLongStateOf(0L) }
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }

    DisposableEffect(Unit) {
        onDispose {
            recording?.stop()
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

    LaunchedEffect(hasPermission, lensFacing) {
        val pv = previewViewRef ?: return@LaunchedEffect
        if (recording != null) return@LaunchedEffect
        val provider = ProcessCameraProvider.getInstance(context).get()
        provider.unbindAll()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(pv.surfaceProvider)
        }
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD))
            .build()
        val vc = VideoCapture.withOutput(recorder)
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        try {
            provider.bindToLifecycle(lifecycleOwner, selector, preview, vc)
            videoCapture = vc
        } catch (e: Exception) {
            Log.e(TAG, "Bind failed", e)
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
            Text(
                text = "%02d:%02d".format(recordSeconds / 60, recordSeconds % 60),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(24.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        IconButton(
            onClick = {
                if (isRecording) {
                    recording?.stop()
                    recording = null
                    isRecording = false
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
                    recording?.stop()
                    recording = null
                    isRecording = false
                } else {
                    val vc = videoCapture ?: return@FloatingActionButton
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !ensureVideoOutputDirExists()) {
                        Log.e(TAG, "Не удалось создать каталог для видео (нет разрешения на запись)")
                        return@FloatingActionButton
                    }
                    val name = newVideoFileName()
                    val contentValues = createVideoContentValues(name)
                    val options = MediaStoreOutputOptions.Builder(
                        context.contentResolver,
                        android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    ).setContentValues(contentValues).build()
                    recording = vc.output
                        .prepareRecording(context, options)
                        .start(mainExecutor) { event ->
                            when (event) {
                                is VideoRecordEvent.Finalize -> {
                                    if (!event.hasError()) {
                                        event.outputResults.outputUri?.let { uri ->
                                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                                setVideoPendingFalse(context, uri)
                                                insertGalleryItem(context, uri, MediaType.VIDEO, System.currentTimeMillis() / 1000)
                                            }
                                        }
                                    }
                                }
                                else -> {}
                            }
                        }
                    isRecording = true
                    recordSeconds = 0
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
