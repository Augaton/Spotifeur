package fr.spotifeur

import android.content.Context
import android.os.Bundle
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.spotifeur.presentation.SpotifeurMain
import fr.spotifeur.data.model.Song
import fr.spotifeur.data.preferences.AppSettings
import fr.spotifeur.presentation.components.subcomps.LocalTrackQuality
import fr.spotifeur.ui.theme.LocalShowScrollbar
import fr.spotifeur.ui.theme.SpotifeurTheme
import fr.spotifeur.utils.AppLocales

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLocales.wrap(newBase))

    override fun onStart() {
        super.onStart()
        fr.spotifeur.update.AppUpdater.get(this).check()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val model: SpotifeurModel = viewModel()
            val settings by model.ui.settings.collectAsStateWithLifecycle()
            val downloads by model.ui.downloadCollections.collectAsStateWithLifecycle()
            val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
            // Asked once, at first launch, so local files are in the library right away.
            val media = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) model.refreshLocalTracks()
            }
            LaunchedEffect(Unit) {
                val preferences = getSharedPreferences("first_launch", MODE_PRIVATE)
                if (!preferences.getBoolean("media_requested", false)) {
                    preferences.edit().putBoolean("media_requested", true).apply()
                    if (ContextCompat.checkSelfPermission(this@MainActivity, fr.spotifeur.data.LocalLibrary.PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                        media.launch(fr.spotifeur.data.LocalLibrary.PERMISSION)
                    }
                }
            }
            val downloading = downloads.values.any { it.isActive }
            LaunchedEffect(downloading) {
                if (downloading && Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    val preferences = getSharedPreferences("download_notifications", MODE_PRIVATE)
                    if (!preferences.getBoolean("requested", false)) {
                        preferences.edit().putBoolean("requested", true).apply()
                        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
            val dark = when (settings.themeMode) {
                AppSettings.THEME_LIGHT -> false
                AppSettings.THEME_DARK -> true
                else -> isSystemInDarkTheme()
            }
            SpotifeurTheme(darkTheme = dark) {
                val qualityRevision by model.ui.qualityRevision.collectAsStateWithLifecycle()
                val quality = remember(settings.showQualityInLists, qualityRevision) {
                    { song: Song -> if (settings.showQualityInLists) model.ui.qualityOf(song) else null }
                }
                val swipe = remember(settings.swipeRight, settings.swipeLeft) {
                    fr.spotifeur.presentation.components.TrackSwipeConfig(
                        fr.spotifeur.presentation.components.TrackSwipeAction.of(settings.swipeRight),
                        fr.spotifeur.presentation.components.TrackSwipeAction.of(settings.swipeLeft),
                    ) { action, song ->
                        when (action) {
                            fr.spotifeur.presentation.components.TrackSwipeAction.ADD_TO_QUEUE -> model.ui.addSongToQueue(song)
                            fr.spotifeur.presentation.components.TrackSwipeAction.PLAY_NEXT -> model.ui.addSongNextToQueue(song)
                            fr.spotifeur.presentation.components.TrackSwipeAction.LIKE -> model.ui.toggleFavoriteSpecificSong(song)
                            else -> Unit
                        }
                    }
                }
                CompositionLocalProvider(
                    LocalShowScrollbar provides settings.showScrollbar, LocalTrackQuality provides quality,
                    fr.spotifeur.presentation.components.LocalCompactLists provides settings.compactLists,
                    fr.spotifeur.presentation.components.LocalSourceBadges provides settings.showSourceBadges,
                    fr.spotifeur.presentation.components.LocalTrackSwipe provides swipe,
                ) {
                    SpotifeurMain(model)
                    fr.spotifeur.update.UpdatePrompt()
                }
            }
        }
    }
}
