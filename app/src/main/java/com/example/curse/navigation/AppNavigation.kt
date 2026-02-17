package com.example.curse.navigation

import android.content.IntentSender
import android.net.Uri
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.curse.ui.gallery.GalleryScreen
import com.example.curse.ui.photo.PhotoScreen
import com.example.curse.ui.video.VideoScreen

@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController(),
    hasCameraPermission: Boolean = false,
    hasAudioPermission: Boolean = false,
    hasMediaReadPermission: Boolean = false,
    onRequestPhotoPermission: () -> Unit = {},
    onRequestVideoPermission: () -> Unit = {},
    onRequestGalleryPermission: () -> Unit = {},
    galleryRefreshTrigger: Int = 0,
    onRequestDeletePermission: (IntentSender, Uri) -> Unit = { _, _ -> },
    onGalleryVisible: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val navBackStackEntry = navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry.value?.destination

    LaunchedEffect(currentDestination?.route) {
        if (currentDestination?.route == NavRoutes.GALLERY) {
            onGalleryVisible()
        }
    }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
                listOf(
                    Triple(NavRoutes.PHOTO, Icons.Default.PhotoCamera, "Фото"),
                    Triple(NavRoutes.VIDEO, Icons.Default.Videocam, "Видео"),
                    Triple(NavRoutes.GALLERY, Icons.Default.PhotoLibrary, "Галерея")
                ).forEach { (route, icon, label) ->
                    NavigationBarItem(
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) },
                        selected = currentDestination?.hierarchy?.any { it.route == route } == true,
                        onClick = {
                            navController.navigate(route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = NavRoutes.PHOTO,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(NavRoutes.PHOTO) {
                PhotoScreen(
                    hasCameraPermission = hasCameraPermission,
                    onRequestPermission = onRequestPhotoPermission
                )
            }
            composable(NavRoutes.VIDEO) {
                VideoScreen(
                    hasCameraPermission = hasCameraPermission,
                    hasAudioPermission = hasAudioPermission,
                    onRequestPermission = onRequestVideoPermission
                )
            }
            composable(NavRoutes.GALLERY) {
                GalleryScreen(
                    hasMediaPermission = hasMediaReadPermission,
                    onRequestPermission = onRequestGalleryPermission,
                    galleryRefreshTrigger = galleryRefreshTrigger,
                    onRequestDeletePermission = onRequestDeletePermission
                )
            }
        }
    }
}
