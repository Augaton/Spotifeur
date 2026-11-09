package fr.spotifeur.presentation.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import fr.spotifeur.R
import fr.spotifeur.data.model.Song
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** What swiping a track row does. The key is what the settings store. */
enum class TrackSwipeAction(val key: String) {
    NONE("none"), ADD_TO_QUEUE("queue"), PLAY_NEXT("next"), LIKE("like"), REMOVE("remove");

    companion object {
        fun of(key: String): TrackSwipeAction = entries.firstOrNull { it.key == key } ?: NONE
    }
}

/** The swipe settings and how to run an action on a track; removal is handled by the list that allows it. */
@Immutable
class TrackSwipeConfig(
    val right: TrackSwipeAction,
    val left: TrackSwipeAction,
    val perform: (TrackSwipeAction, Song) -> Unit,
)

val LocalTrackSwipe = staticCompositionLocalOf<TrackSwipeConfig?> { null }

/**
 * Lets a track row be swiped sideways. Past the threshold, releasing runs the action chosen in
 * the settings for that direction and the row springs back. A direction with no action, or with
 * removal outside an editable playlist, is left to the parent (a pager, for example).
 */
@Composable
fun TrackSwipeBox(
    song: Song,
    shape: Shape,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onRemove: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val config = LocalTrackSwipe.current
    fun usable(action: TrackSwipeAction?) = when (action) {
        null, TrackSwipeAction.NONE -> null
        TrackSwipeAction.REMOVE -> action.takeIf { onRemove != null }
        else -> action
    }
    val right = usable(config?.right).takeIf { enabled }
    val left = usable(config?.left).takeIf { enabled }
    if (config == null || (right == null && left == null)) {
        Box(modifier) { content() }
        return
    }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    val currentSong by rememberUpdatedState(song)
    val currentRemove by rememberUpdatedState(onRemove)
    val shown = if (offset.value > 0) right else if (offset.value < 0) left else null
    val reached = abs(offset.value) >= threshold
    val iconScale by animateFloatAsState(if (reached) 1.15f else 0.85f, spring(Spring.DampingRatioMediumBouncy), label = "swipeIcon")
    val colors = MaterialTheme.colorScheme
    val background = when {
        shown == null -> Color.Transparent
        shown == TrackSwipeAction.REMOVE -> if (reached) colors.errorContainer else colors.errorContainer.copy(alpha = 0.6f)
        else -> if (reached) colors.tertiaryContainer else colors.tertiaryContainer.copy(alpha = 0.6f)
    }
    val iconTint = if (shown == TrackSwipeAction.REMOVE) colors.onErrorContainer else colors.onTertiaryContainer

    Box(modifier) {
        if (shown != null) {
            Box(Modifier.matchParentSize().clip(shape).background(background).padding(horizontal = 24.dp),
                contentAlignment = if (offset.value > 0) Alignment.CenterStart else Alignment.CenterEnd) {
                Icon(shown.icon(), shown.label(), Modifier.graphicsLayer { scaleX = iconScale; scaleY = iconScale }, tint = iconTint)
            }
        }
        Box(Modifier
            .offset { IntOffset(offset.value.roundToInt(), 0) }
            .pointerInput(right, left) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var start = 0f
                    val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                        if ((over > 0 && right != null) || (over < 0 && left != null)) { change.consume(); start = over }
                    } ?: return@awaitEachGesture
                    fun bounded(value: Float) = value.coerceIn(if (left != null) -size.width.toFloat() else 0f, if (right != null) size.width.toFloat() else 0f)
                    var position = bounded(start)
                    var armed = abs(position) >= threshold
                    scope.launch { offset.snapTo(position) }
                    horizontalDrag(drag.id) { change ->
                        position = bounded(position + change.positionChange().x)
                        change.consume()
                        val past = abs(position) >= threshold
                        if (past != armed) { armed = past; if (past) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
                        scope.launch { offset.snapTo(position) }
                    }
                    val action = when {
                        abs(position) < threshold -> null
                        position > 0 -> right
                        else -> left
                    }
                    scope.launch { offset.animateTo(0f, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow)) }
                    when (action) {
                        null -> Unit
                        TrackSwipeAction.REMOVE -> currentRemove?.invoke()
                        else -> config.perform(action, currentSong)
                    }
                }
            }) { content() }
    }
}

private fun TrackSwipeAction.icon() = when (this) {
    TrackSwipeAction.ADD_TO_QUEUE -> Icons.AutoMirrored.Rounded.QueueMusic
    TrackSwipeAction.PLAY_NEXT -> Icons.AutoMirrored.Rounded.PlaylistPlay
    TrackSwipeAction.LIKE -> Icons.Rounded.Favorite
    TrackSwipeAction.REMOVE, TrackSwipeAction.NONE -> Icons.Rounded.Delete
}

@Composable
private fun TrackSwipeAction.label() = stringResource(when (this) {
    TrackSwipeAction.ADD_TO_QUEUE -> R.string.swipe_action_queue
    TrackSwipeAction.PLAY_NEXT -> R.string.swipe_action_next
    TrackSwipeAction.LIKE -> R.string.swipe_action_like
    TrackSwipeAction.REMOVE -> R.string.playlist_remove_track
    TrackSwipeAction.NONE -> R.string.swipe_action_none
})
