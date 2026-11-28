package com.example.photx.navigation

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.photx.core.ui.PhotxDockDestination
import com.example.photx.core.ui.PhotxNavigationDock
import com.example.photx.feature.auth.AuthRoute
import com.example.photx.feature.gallery.AlbumDetailRoute
import com.example.photx.feature.gallery.AlbumsRoute
import com.example.photx.feature.gallery.BackupRoute
import com.example.photx.feature.gallery.DeviceFoldersRoute
import com.example.photx.feature.gallery.GalleryAlbumId
import com.example.photx.feature.gallery.PhotosRoute
import com.example.photx.feature.gallery.SearchRoute
import com.example.photx.feature.viewer.MediaViewerRoute
import com.example.photx.feature.viewer.MediaViewerViewModel

private const val ROUTE_AUTH = "auth"
private const val ROUTE_PHOTOS = "photos"
private const val ROUTE_ALBUMS = "albums"
private const val ROUTE_BACKUP = "backup"
private const val ROUTE_DEVICE_FOLDERS = "device_folders"
private const val ROUTE_SEARCH = "search"
private const val ARG_ALBUM_ID = "albumId"
private const val ROUTE_ALBUM_DETAIL = "album/{$ARG_ALBUM_ID}"
private const val ROUTE_VIEWER = "viewer/{${MediaViewerViewModel.ARG_MEDIA_ID}}"

private fun albumDetailRoute(albumId: GalleryAlbumId): String = "album/${albumId.name}"
private fun viewerRoute(mediaId: String): String = "viewer/${Uri.encode(mediaId)}"

/**
 * Root navigation graph connecting Telegram auth, the 4 floating-dock destinations,
 * album detail browsing, and the full-screen media viewer.
 */
@Composable
public fun PhotxAppShell(isDarkTheme: Boolean, onToggleTheme: () -> Unit, modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val currentDockDestination = currentRoute.toDockDestinationOrNull()

    Box(modifier = modifier.fillMaxSize()) {
        PhotxNavGraph(navController = navController, isDarkTheme = isDarkTheme, onToggleTheme = onToggleTheme)
        if (currentDockDestination != null) {
            PhotxNavigationDock(
                selectedDestination = currentDockDestination,
                onSelectDestination = { destination ->
                    val targetRoute = destination.toRoute()
                    if (targetRoute != currentRoute) {
                        navController.navigate(targetRoute) {
                            popUpTo(ROUTE_PHOTOS) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

@Composable
private fun PhotxNavGraph(
    navController: androidx.navigation.NavHostController,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit
) {
    val enterPhotosClearingAuth: () -> Unit = {
        navController.navigate(ROUTE_PHOTOS) {
            popUpTo(ROUTE_AUTH) { inclusive = true }
            launchSingleTop = true
        }
    }

    NavHost(navController = navController, startDestination = ROUTE_AUTH, modifier = Modifier.fillMaxSize()) {
        composable(ROUTE_AUTH) {
            AuthRoute(onAuthenticated = enterPhotosClearingAuth, onBrowseOffline = enterPhotosClearingAuth)
        }
        composable(ROUTE_PHOTOS) {
            PhotosRoute(
                isDarkTheme = isDarkTheme,
                onToggleTheme = onToggleTheme,
                onOpenAuth = { navController.navigate(ROUTE_AUTH) },
                onOpenMedia = { mediaId -> navController.navigate(viewerRoute(mediaId)) }
            )
        }
        composable(ROUTE_ALBUMS) {
            AlbumsRoute(onOpenAlbum = { albumId -> navController.navigate(albumDetailRoute(albumId)) })
        }
        composable(
            route = ROUTE_ALBUM_DETAIL,
            arguments = listOf(navArgument(ARG_ALBUM_ID) { type = NavType.StringType })
        ) { entry ->
            val rawAlbumId = entry.arguments?.getString(ARG_ALBUM_ID)
            val albumId = GalleryAlbumId.entries.firstOrNull { it.name == rawAlbumId } ?: GalleryAlbumId.RECENTS
            AlbumDetailRoute(
                albumId = albumId,
                onBack = { navController.popBackStack() },
                onOpenMedia = { mediaId -> navController.navigate(viewerRoute(mediaId)) }
            )
        }
        composable(ROUTE_BACKUP) {
            BackupRoute(
                onOpenAuth = { navController.navigate(ROUTE_AUTH) },
                onRetryScan = { navController.navigateToPhotos() },
                onOpenDeviceFolders = { navController.navigate(ROUTE_DEVICE_FOLDERS) }
            )
        }
        composable(ROUTE_DEVICE_FOLDERS) {
            DeviceFoldersRoute(onBack = { navController.popBackStack() })
        }
        composable(ROUTE_SEARCH) {
            SearchRoute(onOpenMedia = { mediaId -> navController.navigate(viewerRoute(mediaId)) })
        }
        composable(
            route = ROUTE_VIEWER,
            arguments = listOf(navArgument(MediaViewerViewModel.ARG_MEDIA_ID) { type = NavType.StringType })
        ) {
            MediaViewerRoute(onBack = { navController.popBackStack() })
        }
    }
}

private fun androidx.navigation.NavHostController.navigateToPhotos() {
    navigate(ROUTE_PHOTOS) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun String?.toDockDestinationOrNull(): PhotxDockDestination? = when (this) {
    ROUTE_PHOTOS -> PhotxDockDestination.PHOTOS
    ROUTE_ALBUMS -> PhotxDockDestination.ALBUMS
    ROUTE_BACKUP -> PhotxDockDestination.BACKUP
    ROUTE_SEARCH -> PhotxDockDestination.SEARCH
    else -> null
}

private fun PhotxDockDestination.toRoute(): String = when (this) {
    PhotxDockDestination.PHOTOS -> ROUTE_PHOTOS
    PhotxDockDestination.ALBUMS -> ROUTE_ALBUMS
    PhotxDockDestination.BACKUP -> ROUTE_BACKUP
    PhotxDockDestination.SEARCH -> ROUTE_SEARCH
}
