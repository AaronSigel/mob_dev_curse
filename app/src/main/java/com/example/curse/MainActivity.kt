package com.example.curse

import android.app.Activity
import android.content.IntentSender
import android.content.pm.PackageManager
import android.Manifest
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.curse.media.DeleteResult
import com.example.curse.media.deleteGalleryItemByUri
import com.example.curse.media.tryDeleteMedia
import com.example.curse.navigation.AppNavigation
import com.example.curse.permission.allPermissionsToCheck
import com.example.curse.permission.galleryPermissions
import com.example.curse.permission.hasGalleryPermission
import com.example.curse.permission.hasPhotoCapturePermission
import com.example.curse.permission.hasVideoCapturePermission
import com.example.curse.permission.photoScreenPermissions
import com.example.curse.permission.videoScreenPermissions
import com.example.curse.ui.theme.CurseTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Регистрация лаунчеров до setContent: registerForActivityResult допустим только до STARTED.
        val permissionsState = mutableStateOf(buildPermissionMap())
        val pendingDeleteUriState = mutableStateOf<Uri?>(null)
        val galleryRefreshTriggerState = mutableIntStateOf(0)

        val requestPhoto = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionsState.value = buildPermissionMap()
        }
        val requestVideo = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionsState.value = buildPermissionMap()
        }
        val requestGallery = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionsState.value = buildPermissionMap()
        }
        val deletePermissionLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val pendingUri = pendingDeleteUriState.value
            if (result.resultCode == Activity.RESULT_OK) {
                pendingUri?.let { uri ->
                    when (val deleteResult = tryDeleteMedia(contentResolver, uri)) {
                        is DeleteResult.Success -> {
                            lifecycleScope.launch {
                                deleteGalleryItemByUri(this@MainActivity, uri)
                            }
                            galleryRefreshTriggerState.value++
                        }
                        is DeleteResult.NeedPermission -> {
                            Log.w(
                                TAG,
                                "Delete still requires permission after confirmation uri=$uri"
                            )
                        }
                    }
                }
            }
            pendingDeleteUriState.value = null
        }

        setContent {
            var permissions by permissionsState
            var pendingDeleteUri by pendingDeleteUriState
            var galleryRefreshTrigger by galleryRefreshTriggerState

            fun onRequestPhotoPermission() {
                requestPhoto.launch(photoScreenPermissions())
            }
            fun onRequestVideoPermission() {
                requestVideo.launch(videoScreenPermissions())
            }
            fun onRequestGalleryPermission() {
                requestGallery.launch(galleryPermissions())
            }
            fun onRequestDeletePermission(sender: IntentSender, uri: Uri) {
                pendingDeleteUri = uri
                deletePermissionLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }

            val hasPhotoCapture = hasPhotoCapturePermission(permissions)
            val hasVideoCapture = hasVideoCapturePermission(permissions)
            val hasMediaRead = hasGalleryPermission(permissions)

            CurseTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavigation(
                        hasCameraPermission = hasPhotoCapture,
                        hasAudioPermission = hasVideoCapture,
                        hasMediaReadPermission = hasMediaRead,
                        onRequestPhotoPermission = ::onRequestPhotoPermission,
                        onRequestVideoPermission = ::onRequestVideoPermission,
                        onRequestGalleryPermission = ::onRequestGalleryPermission,
                        galleryRefreshTrigger = galleryRefreshTrigger,
                        onRequestDeletePermission = ::onRequestDeletePermission,
                        onGalleryVisible = { galleryRefreshTrigger++ }
                    )
                }
            }
        }
    }

    private fun buildPermissionMap(): Map<String, Boolean> {
        return allPermissionsToCheck().associateWith {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
