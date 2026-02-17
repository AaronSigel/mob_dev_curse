@file:OptIn(ExperimentalMaterial3Api::class)
package com.example.curse.ui.gallery

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.content.IntentSender
import android.util.Size
import android.view.ContextThemeWrapper
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.example.curse.R
import com.example.curse.media.DeleteResult
import com.example.curse.media.GalleryItem
import com.example.curse.media.MediaType
import com.example.curse.media.deleteGalleryItemByUri
import com.example.curse.media.loadGalleryItems
import com.example.curse.media.tryDeleteMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MEDIA_MAX_WIDTH_FRACTION = 0.9f

@Composable
fun GalleryScreen(
    hasMediaPermission: Boolean,
    onRequestPermission: () -> Unit,
    galleryRefreshTrigger: Int,
    onRequestDeletePermission: (IntentSender, Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    var selectedItem by remember { mutableStateOf<GalleryItem?>(null) }

    if (!hasMediaPermission) {
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
                    text = "Для просмотра галереи нужен доступ к фото и видео",
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

    LaunchedEffect(galleryRefreshTrigger) {
        items = loadGalleryItems(context)
    }

    val selectedIndex = remember(selectedItem, items) {
        selectedItem?.let { items.indexOfFirst { i -> i.id == it.id && i.uri == it.uri }.takeIf { idx -> idx >= 0 } }
            ?: 0
    }

    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Галерея") }
                )
            },
            modifier = Modifier.fillMaxSize()
        ) { padding ->
            if (items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Нет медиафайлов",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    items(items) { item ->
                        GalleryGridItem(
                            item = item,
                            onClick = { selectedItem = item }
                        )
                    }
                }
            }
        }

        // Полноэкранный просмотр поверх сетки, чтобы перехватывал клики и был виден
        if (selectedItem != null && items.isNotEmpty() && selectedIndex in items.indices) {
            FullscreenViewer(
                items = items,
                initialIndex = selectedIndex,
                onDismiss = { selectedItem = null },
                onDelete = {
                    when (val result = tryDeleteMedia(context.contentResolver, it.uri)) {
                        is DeleteResult.Success -> {
                            scope.launch { deleteGalleryItemByUri(context, it.uri) }
                            selectedItem = null
                            items = items.filterNot { i -> i.uri == it.uri }
                        }
                        is DeleteResult.NeedPermission -> onRequestDeletePermission(result.intentSender, it.uri)
                    }
                }
            )
        }
    }
}

/** Загрузка превью видео: системный кэш (Android 8) или loadThumbnail (API 29+), иначе кадр через MMR. */
private fun loadVideoThumbnail(context: Context, uri: Uri): Bitmap? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        runCatching {
            context.contentResolver.loadThumbnail(uri, Size(512, 512), null)
        }.getOrNull()?.let { return it }
    }
    if (uri.scheme == "content") {
        runCatching {
            val id = ContentUris.parseId(uri)
            @Suppress("DEPRECATION")
            MediaStore.Video.Thumbnails.getThumbnail(
                context.contentResolver,
                id,
                MediaStore.Video.Thumbnails.MINI_KIND,
                null
            )
        }.getOrNull()?.let { return it }
    }
    return runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            val usedFd = context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                retriever.setDataSource(pfd.fileDescriptor)
                true
            } ?: false
            if (!usedFd) retriever.setDataSource(context, uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } else {
                retriever.getFrameAtTime(1000) ?: retriever.getFrameAtTime(0)
            }
        } finally {
            retriever.release()
        }
    }.getOrNull()
}

@Composable
private fun VideoThumbnail(
    context: Context,
    uri: Uri,
    modifier: Modifier = Modifier
) {
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) {
            loadVideoThumbnail(context, uri)
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
    }
}

@Composable
private fun GalleryGridItem(
    item: GalleryItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val dateStr = remember(item.dateAdded) {
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
            .format(Date(item.dateAdded * 1000))
    }
    Card(
        modifier = modifier
            .aspectRatio(1f)
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (item.type) {
                    MediaType.PHOTO -> AsyncImage(
                        model = item.uri,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    MediaType.VIDEO -> VideoThumbnail(
                        context = context,
                        uri = item.uri,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Icon(
                    imageVector = if (item.type == MediaType.PHOTO) Icons.Default.Photo else Icons.Default.Videocam,
                    contentDescription = item.type.name,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = dateStr,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(4.dp),
                maxLines = 1
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullscreenViewer(
    items: List<GalleryItem>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    onDelete: (GalleryItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
        pageCount = { items.size }
    )
    val currentItem = items.getOrNull(pagerState.currentPage) ?: items.getOrNull(initialIndex)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            when (currentItem?.type) {
                                MediaType.PHOTO -> "Фото"
                                MediaType.VIDEO -> "Видео"
                                null -> "Медиа"
                            }
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    },
                    actions = {
                        currentItem?.let { item ->
                            IconButton(onClick = { onDelete(item) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Удалить")
                            }
                        }
                    }
                )
            }
        ) { padding ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                userScrollEnabled = true
            ) { page ->
                items.getOrNull(page)?.let { galleryItem ->
                    SingleMediaView(
                        item = galleryItem,
                        isActive = page == pagerState.currentPage,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

private data class VideoDisplayInfo(
    val aspectRatio: Float?
)

private suspend fun getVideoDisplayInfo(context: Context, uri: Uri): VideoDisplayInfo = withContext(Dispatchers.IO) {
    runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            val usedFd = context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                retriever.setDataSource(pfd.fileDescriptor)
                true
            } ?: false
            if (!usedFd) retriever.setDataSource(context, uri)
            val metaRotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val normalizedRotation = ((metaRotation ?: 0) % 360 + 360) % 360
            val displayW = if (normalizedRotation == 90 || normalizedRotation == 270) h else w
            val displayH = if (normalizedRotation == 90 || normalizedRotation == 270) w else h
            val aspectRatio = if (displayW > 0 && displayH > 0) {
                displayW.toFloat() / displayH.toFloat()
            } else {
                null
            }
            VideoDisplayInfo(aspectRatio = aspectRatio)
        } finally {
            retriever.release()
        }
    }.getOrElse {
        VideoDisplayInfo(aspectRatio = null)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun SingleMediaView(
    item: GalleryItem,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (item.type) {
            MediaType.PHOTO -> {
                AsyncImage(
                    model = item.uri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(MEDIA_MAX_WIDTH_FRACTION),
                    contentScale = ContentScale.Fit
                )
            }
            MediaType.VIDEO -> {
                val context = LocalContext.current
                var displayInfo by remember(item.uri) { mutableStateOf<VideoDisplayInfo?>(null) }
                LaunchedEffect(item.uri) {
                    displayInfo = getVideoDisplayInfo(context, item.uri)
                }
                val videoModifier = Modifier
                    .fillMaxWidth(MEDIA_MAX_WIDTH_FRACTION)
                    .aspectRatio(displayInfo?.aspectRatio ?: (16f / 9f))
                VideoPlayer(
                    uri = item.uri,
                    isActive = isActive,
                    modifier = videoModifier
                )
            }
        }
    }
}

@UnstableApi
@Composable
private fun VideoPlayer(
    uri: Uri,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
        }
    }

    LaunchedEffect(exoPlayer, isActive) {
        exoPlayer.playWhenReady = isActive
        if (isActive) exoPlayer.play() else exoPlayer.pause()
    }

    val currentIsActive by rememberUpdatedState(isActive)

    DisposableEffect(exoPlayer, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (currentIsActive) exoPlayer.play()
                Lifecycle.Event.ON_STOP -> exoPlayer.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    AndroidView(
        factory = { viewContext ->
            val playerView = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) {
                val textureSurfaceContext = ContextThemeWrapper(
                    viewContext,
                    R.style.Theme_Curse_PlayerView_TextureSurface
                )
                PlayerView(textureSurfaceContext)
            } else {
                PlayerView(viewContext)
            }
            playerView.apply {
                useController = true
                controllerAutoShow = true
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                player = exoPlayer
            }
        },
        update = { playerView ->
            playerView.player = exoPlayer
        },
        modifier = modifier
    )
}
