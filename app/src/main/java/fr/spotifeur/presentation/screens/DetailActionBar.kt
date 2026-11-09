package fr.spotifeur.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.spotifeur.R
import fr.spotifeur.data.preferences.DetailAction
import fr.spotifeur.data.preferences.DetailPage
import fr.spotifeur.presentation.viewmodel.PlayerViewModel

/** One button a page can offer: in the bar when the layout shows it, in the ⋮ menu otherwise. */
internal data class ActionSpec(
    val action: DetailAction,
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
)

/** The page's buttons, in the order and split chosen in Settings → Album and playlist pages. */
@Composable
internal fun DetailActionBar(page: DetailPage, actions: List<ActionSpec>, ui: PlayerViewModel) {
    val settings by ui.settings.collectAsStateWithLifecycle()
    val available = actions.associateBy { it.action }
    val ordered = settings.actions(page).mapNotNull { slot -> available[slot.action]?.let { it to slot.shown } }
    val bar = ordered.filter { it.second }.map { it.first }
    val menu = ordered.filterNot { it.second }.map { it.first }
    var expanded by remember { mutableStateOf(false) }
    FlowRow(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        bar.forEach { spec ->
            FilledTonalIconButton(
                onClick = spec.onClick, enabled = spec.enabled,
                colors = when {
                    spec.selected -> IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
                    spec.destructive -> IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
                    else -> IconButtonDefaults.filledTonalIconButtonColors()
                },
            ) { Icon(spec.icon, spec.label) }
        }
        if (menu.isNotEmpty()) Box {
            FilledTonalIconButton(onClick = { expanded = true }) {
                Icon(Icons.Rounded.MoreVert, stringResource(R.string.detail_more_actions))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, shape = RoundedCornerShape(20.dp)) {
                menu.forEach { spec ->
                    DropdownMenuItem(
                        text = { Text(spec.label) },
                        leadingIcon = { Icon(spec.icon, null) },
                        enabled = spec.enabled,
                        colors = if (spec.destructive) MenuDefaults.itemColors(
                            textColor = MaterialTheme.colorScheme.error, leadingIconColor = MaterialTheme.colorScheme.error) else MenuDefaults.itemColors(),
                        onClick = { expanded = false; spec.onClick() },
                    )
                }
            }
        }
    }
}

/** The download button of a page, reflecting what is already kept on the device. */
@Composable
internal fun downloadAction(ui: PlayerViewModel, item: fr.spotifeur.data.Music): ActionSpec {
    val collections by ui.downloadCollections.collectAsStateWithLifecycle()
    val preparations by ui.downloadPreparations.collectAsStateWithLifecycle()
    val progress = collections[item.id]
    val complete = progress?.isComplete == true
    val requested = progress != null || item.id in preparations
    return ActionSpec(
        DetailAction.DOWNLOAD,
        if (complete) Icons.Rounded.DownloadForOffline else Icons.Outlined.DownloadForOffline,
        stringResource(if (complete) R.string.download_remove else if (requested) R.string.download_cancel else R.string.download_action),
        onClick = { ui.toggleDownloadItem(item) }, selected = complete,
    )
}

/** Names of the buttons in Settings. */
@Composable
internal fun actionName(action: DetailAction): String = stringResource(when (action) {
    DetailAction.DOWNLOAD -> R.string.download_action
    DetailAction.ADD_TRACKS -> R.string.playlist_add_tracks
    DetailAction.SAVE -> R.string.saved_add
    DetailAction.PIN -> R.string.pin_add
    DetailAction.RADIO -> R.string.radio
    DetailAction.SHARE -> R.string.detail_share
    DetailAction.SORT -> R.string.playlist_sort
    DetailAction.REORDER -> R.string.reorder
    DetailAction.EDIT -> R.string.detail_edit
    DetailAction.EXPORT -> R.string.playlist_export_csv
    DetailAction.DELETE -> R.string.playlist_delete
})
