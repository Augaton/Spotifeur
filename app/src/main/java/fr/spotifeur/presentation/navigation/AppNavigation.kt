package fr.spotifeur.presentation.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import fr.spotifeur.SpotifeurModel
import fr.spotifeur.data.preferences.AppSettings
import fr.spotifeur.presentation.screens.AlbumDetailScreen
import fr.spotifeur.presentation.screens.ArtistDetailScreen
import fr.spotifeur.presentation.screens.HomeScreen
import fr.spotifeur.presentation.screens.LibraryScreen
import fr.spotifeur.presentation.screens.PlaylistDetailScreen
import fr.spotifeur.presentation.screens.SearchScreen
import fr.spotifeur.presentation.screens.EqualizerScreen
import fr.spotifeur.presentation.screens.SettingsCategoryScreen
import fr.spotifeur.presentation.screens.SettingsScreen
import java.net.URLDecoder

@Composable
fun AppNavigation(
    model: SpotifeurModel,
    navController: NavHostController,
    paddingValues: PaddingValues,
    onSearchBarActiveChange: (Boolean) -> Unit,
) {
    // The tab chosen in the settings applies at the next start, not while navigating.
    val defaultTab = androidx.compose.runtime.remember { model.ui.settings.value.defaultTab }
    NavHost(
        navController = navController,
        startDestination = when (defaultTab) {
            AppSettings.TAB_SEARCH -> Screen.Search.route
            AppSettings.TAB_LIBRARY -> Screen.Library.route
            else -> Screen.Home.route
        },
        enterTransition = { aospSharedAxisEnter() },
        exitTransition = { aospSharedAxisExit() },
        popEnterTransition = { aospSharedAxisPopEnter() },
        popExitTransition = { aospSharedAxisPopExit() },
    ) {
        composable(
            Screen.Home.route,
            enterTransition = { mainRootEnterTransition(initialState.destination.route, targetState.destination.route, aospSharedAxisEnter()) },
            exitTransition = { mainRootExitTransition(initialState.destination.route, targetState.destination.route, aospSharedAxisExit()) },
            popEnterTransition = { mainRootEnterTransition(initialState.destination.route, targetState.destination.route, aospSharedAxisPopEnter()) },
            popExitTransition = { mainRootExitTransition(initialState.destination.route, targetState.destination.route, aospSharedAxisPopExit()) },
        ) { HomeScreen(model, navController, paddingValues) }
        composable(
            Screen.Search.route,
            enterTransition = { mainRootEnterTransition(initialState.destination.route, targetState.destination.route, aospSharedAxisEnter()) },
            exitTransition = { mainRootExitTransition(initialState.destination.route, targetState.destination.route, aospSharedAxisExit()) },
            popEnterTransition = { mainRootEnterTransition(initialState.destination.route, targetState.destination.route, aospSharedAxisPopEnter()) },
            popExitTransition = { mainRootExitTransition(initialState.destination.route, targetState.destination.route, aospSharedAxisPopExit()) },
        ) { SearchScreen(model, navController, paddingValues, onSearchBarActiveChange) }
        composable(
            Screen.Library.route,
            enterTransition = { mainRootEnterTransition(initialState.destination.route, targetState.destination.route, aospSharedAxisEnter()) },
            exitTransition = { mainRootExitTransition(initialState.destination.route, targetState.destination.route, aospSharedAxisExit()) },
            popEnterTransition = { mainRootEnterTransition(initialState.destination.route, targetState.destination.route, aospSharedAxisPopEnter()) },
            popExitTransition = { mainRootExitTransition(initialState.destination.route, targetState.destination.route, aospSharedAxisPopExit()) },
        ) { LibraryScreen(model, navController, paddingValues) }
        composable(Screen.Settings.route) { SettingsScreen(model, navController) }
        composable(Screen.Equalizer.route) { EqualizerScreen(navController = navController, playerViewModel = model.ui) }
        composable(
            Screen.SettingsCategory.route,
            arguments = listOf(navArgument("categoryId") { type = NavType.StringType }),
        ) { entry -> SettingsCategoryScreen(model, navController, entry.arguments?.getString("categoryId").orEmpty()) }
        composable(
            Screen.PlaylistDetail.route,
            arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
        ) { entry ->
            val id = URLDecoder.decode(entry.arguments?.getString("playlistId").orEmpty(), "UTF-8")
            PlaylistDetailScreen(model, navController, id)
        }
        composable(
            Screen.AlbumDetail.route,
            arguments = listOf(navArgument("albumId") { type = NavType.LongType }),
        ) { entry -> AlbumDetailScreen(model, navController, entry.arguments?.getLong("albumId") ?: -1L) }
        composable(
            Screen.ArtistDetail.route,
            arguments = listOf(navArgument("artistId") { type = NavType.LongType }),
        ) { entry -> ArtistDetailScreen(model, navController, entry.arguments?.getLong("artistId") ?: -1L) }
    }
}

private enum class MainRootDirection {
    FORWARD,
    BACKWARD
}

// Base duration for bottom-nav switches at 1x — at 0.5x system scale = ~190 ms.
private const val BOTTOM_NAV_TRANSITION_DURATION = 380

// MD3 Expressive easing for bottom-nav switches
private val BottomNavEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

private val MAIN_ROOT_TRANSITION_SPEC =
    tween<IntOffset>(durationMillis = BOTTOM_NAV_TRANSITION_DURATION, easing = BottomNavEasing)

private val MAIN_ROOT_FADE_SPEC =
    tween<Float>(durationMillis = BOTTOM_NAV_TRANSITION_DURATION / 2, easing = BottomNavEasing)

private fun mainRootDirection(
    fromRoute: String?,
    toRoute: String?
): MainRootDirection? {
    val fromIndex = mainRootRouteIndex(fromRoute) ?: return null
    val toIndex = mainRootRouteIndex(toRoute) ?: return null
    if (fromIndex == toIndex) return null
    return if (toIndex > fromIndex) MainRootDirection.FORWARD else MainRootDirection.BACKWARD
}

private fun mainRootEnterTransition(
    fromRoute: String?,
    toRoute: String?,
    fallback: EnterTransition
): EnterTransition = when (mainRootDirection(fromRoute, toRoute)) {
    MainRootDirection.FORWARD -> {
        slideInHorizontally(
            animationSpec = MAIN_ROOT_TRANSITION_SPEC,
            initialOffsetX = { (it * 0.5f).toInt() }
        ) + fadeIn(animationSpec = MAIN_ROOT_FADE_SPEC)
    }
    MainRootDirection.BACKWARD -> {
        slideInHorizontally(
            animationSpec = MAIN_ROOT_TRANSITION_SPEC,
            initialOffsetX = { -(it * 0.5f).toInt() }
        ) + fadeIn(animationSpec = MAIN_ROOT_FADE_SPEC)
    }
    null -> fallback
}

private fun mainRootExitTransition(
    fromRoute: String?,
    toRoute: String?,
    fallback: ExitTransition
): ExitTransition = when (mainRootDirection(fromRoute, toRoute)) {
    MainRootDirection.FORWARD -> {
        slideOutHorizontally(
            animationSpec = MAIN_ROOT_TRANSITION_SPEC,
            targetOffsetX = { -(it * 0.5f).toInt() }
        ) + fadeOut(animationSpec = MAIN_ROOT_FADE_SPEC)
    }
    MainRootDirection.BACKWARD -> {
        slideOutHorizontally(
            animationSpec = MAIN_ROOT_TRANSITION_SPEC,
            targetOffsetX = { (it * 0.5f).toInt() }
        ) + fadeOut(animationSpec = MAIN_ROOT_FADE_SPEC)
    }
    null -> fallback
}
