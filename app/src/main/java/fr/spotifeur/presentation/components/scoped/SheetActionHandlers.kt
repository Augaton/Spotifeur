package fr.spotifeur.presentation.components.scoped

import fr.spotifeur.presentation.navigation.navigateSafely

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import fr.spotifeur.data.model.Song
import fr.spotifeur.data.model.toMusic
import fr.spotifeur.presentation.navigation.openMusic
import fr.spotifeur.presentation.navigation.Screen
import fr.spotifeur.presentation.navigation.navigateSafelyReplacing
import fr.spotifeur.presentation.viewmodel.PlayerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal data class SheetActionHandlers(
    val openQueueSheet: () -> Unit,
    val animateQueueSheet: (Boolean) -> Unit,
    val beginQueueDrag: () -> Unit,
    val dragQueueBy: (Float) -> Unit,
    val endQueueDrag: (Float, Float) -> Unit,
    val onSelectedSongForInfoChange: (Song?) -> Unit,
    val onNavigateToAlbum: (Song) -> Unit,
    val onNavigateToArtist: (Song) -> Unit,
    val onStartRadio: (Song) -> Unit
)

@OptIn(UnstableApi::class)
@Composable
internal fun rememberSheetActionHandlers(
    scope: CoroutineScope,
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    sheetMotionController: SheetMotionController,
    queueSheetController: QueueSheetController,
    sheetModalOverlayController: SheetModalOverlayController,
    sheetCollapsedTargetY: Float
): SheetActionHandlers {
    val queueSheetControllerState = rememberUpdatedState(queueSheetController)
    val sheetModalOverlayControllerState = rememberUpdatedState(sheetModalOverlayController)
    val sheetMotionControllerState = rememberUpdatedState(sheetMotionController)
    val sheetCollapsedTargetYState = rememberUpdatedState(sheetCollapsedTargetY)
    val playerViewModelState = rememberUpdatedState(playerViewModel)

    val openQueueSheet = remember {
        { queueSheetControllerState.value.animate(true) }
    }
    val animateQueueSheet = remember {
        { expanded: Boolean -> queueSheetControllerState.value.animate(expanded) }
    }
    val beginQueueDrag = remember {
        { queueSheetControllerState.value.beginDrag() }
    }
    val dragQueueBy = remember {
        { dragAmount: Float -> queueSheetControllerState.value.dragBy(dragAmount) }
    }
    val endQueueDrag = remember {
        { totalDrag: Float, velocity: Float ->
            queueSheetControllerState.value.endDrag(totalDrag, velocity)
        }
    }
    val onSelectedSongForInfoChange = remember {
        { song: Song? -> sheetModalOverlayControllerState.value.updateSelectedSongForInfo(song) }
    }
    val onNavigateToAlbum = remember(scope, navController) {
        { song: Song ->
            scope.launch {
                sheetMotionControllerState.value.snapCollapsed(sheetCollapsedTargetYState.value)
            }
            playerViewModelState.value.collapsePlayerSheet()
            queueSheetControllerState.value.animate(false)
            sheetModalOverlayControllerState.value.updateSelectedSongForInfo(null)
            playerViewModelState.value.openAlbumOf(song)
        }
    }
    val onNavigateToArtist = remember(scope, navController) {
        { song: Song ->
            scope.launch {
                sheetMotionControllerState.value.snapCollapsed(sheetCollapsedTargetYState.value)
            }
            playerViewModelState.value.collapsePlayerSheet()
            queueSheetControllerState.value.animate(false)
            sheetModalOverlayControllerState.value.updateSelectedSongForInfo(null)
            playerViewModelState.value.openArtistOf(song)
        }
    }

    val onStartRadio = remember(scope, navController) {
        { song: Song ->
            scope.launch {
                sheetMotionControllerState.value.snapCollapsed(sheetCollapsedTargetYState.value)
            }
            val ui = playerViewModelState.value
            ui.collapsePlayerSheet()
            queueSheetControllerState.value.animate(false)
            sheetModalOverlayControllerState.value.updateSelectedSongForInfo(null)
            ui.startRadio(song.toMusic()) { navController.openMusic(ui, it) }
        }
    }

    return SheetActionHandlers(
        openQueueSheet = openQueueSheet,
        animateQueueSheet = animateQueueSheet,
        beginQueueDrag = beginQueueDrag,
        dragQueueBy = dragQueueBy,
        endQueueDrag = endQueueDrag,
        onSelectedSongForInfoChange = onSelectedSongForInfoChange,
        onNavigateToAlbum = onNavigateToAlbum,
        onNavigateToArtist = onNavigateToArtist,
        onStartRadio = onStartRadio
    )
}
