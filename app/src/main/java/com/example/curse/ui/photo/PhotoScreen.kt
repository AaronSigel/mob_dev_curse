package com.example.curse.ui.photo

import android.content.Context
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.curse.media.MediaType
import com.example.curse.media.createImageContentValues
import com.example.curse.media.ensureImageOutputDirExists
import com.example.curse.media.imageCollectionUri
import com.example.curse.media.insertGalleryItem
import com.example.curse.media.newImageFileName
import com.example.curse.media.setImagePendingFalse
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

private const val TAG = "PhotoScreen"

@Composable
fun PhotoScreen(
    hasCameraPermission: Boolean,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    if (!hasCameraPermission) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp)
            ) {
                Text(
                    text = "Для съёмки нужны доступ к камере и сохранение фото",
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
    var camera by remember { mutableStateOf<Camera?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var zoom by remember { mutableFloatStateOf(0f) }
    val flashAlpha = remember { Animatable(0f) }
    val executor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    LaunchedEffect(hasCameraPermission, lensFacing) {
        val pv = previewViewRef ?: return@LaunchedEffect
        val provider = ProcessCameraProvider.getInstance(context).get()
        provider.unbindAll()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(pv.surfaceProvider)
        }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        try {
            val cam = provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
            camera = cam
            imageCapture = capture
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
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoomChange, _ ->
                        val newZoom = (zoom + (zoomChange - 1f) * 0.5f).coerceIn(0f, 1f)
                        zoom = newZoom
                        camera?.cameraControl?.setLinearZoom(newZoom)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val pv = previewViewRef ?: return@detectTapGestures
                        val point = pv.meteringPointFactory.createPoint(offset.x, offset.y)
                        camera?.cameraControl?.startFocusAndMetering(
                            FocusMeteringAction.Builder(point).build()
                        )
                    }
                }
        )

        // Анимация вспышки при съёмке
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Color.White.copy(alpha = flashAlpha.value))
        )

        // Кнопка переключения камеры
        IconButton(
            onClick = {
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                    CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Default.Cameraswitch, contentDescription = "Переключить камеру")
        }

        // Кнопка спуска
        FloatingActionButton(
            onClick = {
                val cap = imageCapture ?: return@FloatingActionButton
                scope.launch {
                    flashAlpha.snapTo(0.4f)
                    flashAlpha.animateTo(0f, tween(150))
                }
                takePicture(context, cap, executor, scope) {
                    scope.launch {
                        flashAlpha.snapTo(0.6f)
                        flashAlpha.animateTo(0f, tween(100))
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
                .size(72.dp)
                .clip(CircleShape),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) {
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

private fun takePicture(
    context: Context,
    imageCapture: ImageCapture,
    executor: java.util.concurrent.Executor,
    scope: kotlinx.coroutines.CoroutineScope,
    onSaved: () -> Unit
) {
    if (!ensureImageOutputDirExists()) {
        Log.e(TAG, "Photo capture failed: cannot create output dir (check storage permission)")
        return
    }
    val name = newImageFileName()
    val contentValues = createImageContentValues(name)
    val outputOptions = ImageCapture.OutputFileOptions.Builder(
        context.contentResolver,
        imageCollectionUri(),
        contentValues
    ).build()

    imageCapture.takePicture(
        outputOptions,
        executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                val uri = result.savedUri ?: return
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    setImagePendingFalse(context, uri)
                    insertGalleryItem(context, uri, MediaType.PHOTO, System.currentTimeMillis() / 1000)
                }
                onSaved()
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "Photo capture failed", exception)
            }
        }
    )
}
