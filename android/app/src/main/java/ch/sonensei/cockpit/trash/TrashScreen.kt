package ch.sonensei.cockpit.trash

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.search.searchTypeStyle
import ch.sonensei.cockpit.ui.LocalFabClearance
import ch.sonensei.cockpit.ui.PlaceholderScreen
import dagger.hilt.internal.GeneratedComponentManagerHolder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The Trash screen. Its view model comes from Hilt, so outside a Hilt activity (the shell's navigation tests, previews)
 * it shows the placeholder instead of failing.
 */
@Composable
fun TrashScreen(modifier: Modifier = Modifier) {
    if (LocalActivity.current is GeneratedComponentManagerHolder) {
        TrashRoute(modifier)
    } else {
        PlaceholderScreen(R.string.nav_trash, Icons.Outlined.Delete, modifier)
    }
}

@Composable
fun TrashRoute(
    modifier: Modifier = Modifier,
    viewModel: TrashViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Reloaded when the screen is shown and when the app comes back to it: items may have been deleted meanwhile.
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    TrashContent(
        state = state,
        callbacks =
            TrashCallbacks(
                refresh = { viewModel.refresh(pulled = true) },
                retry = { viewModel.refresh() },
                restore = viewModel::restore,
                purge = viewModel::askPurge,
                confirmPurge = viewModel::confirmPurge,
                cancelPurge = viewModel::cancelPurge,
                dismiss = viewModel::dismiss,
            ),
        modifier = modifier,
    )
}

/** What the screen can ask for, so it can be tested without a view model. */
data class TrashCallbacks(
    val refresh: () -> Unit = {},
    val retry: () -> Unit = {},
    val restore: (TrashItem) -> Unit = {},
    /** Delete forever was tapped: asks for confirmation first. */
    val purge: (TrashItem) -> Unit = {},
    val confirmPurge: () -> Unit = {},
    val cancelPurge: () -> Unit = {},
    val dismiss: (TrashSnackbar) -> Unit = {},
)

internal object TrashTags {
    const val LIST = "trash-list"
    const val LOADING = "trash-loading"
    const val EMPTY = "trash-empty"
    const val MESSAGE = "trash-message"
    const val RETRY = "trash-retry"
    const val CONFIRM = "trash-confirm"
    const val CONFIRM_DELETE = "trash-confirm-delete"
    const val CONFIRM_CANCEL = "trash-confirm-cancel"

    fun item(item: TrashItem) = "trash-item:${item.key}"

    fun restore(item: TrashItem) = "trash-restore:${item.key}"

    fun purge(item: TrashItem) = "trash-purge:${item.key}"

    fun purgeIn(item: TrashItem) = "trash-purge-in:${item.key}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashContent(
    state: TrashUiState,
    callbacks: TrashCallbacks,
    modifier: Modifier = Modifier,
    /** The time the countdowns count from; tests fix it. */
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val snackbarHost = remember { SnackbarHostState() }
    TrashSnackbarEffect(state.snackbar, snackbarHost, callbacks.dismiss)
    val locale = LocalConfiguration.current.locales[0]
    val dates = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            state.message?.let { ListMessage(it, state.loaded, state.loading, callbacks.retry) }
            if (state.loading && !state.refreshing) {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag(TrashTags.LOADING))
            } else {
                Spacer(Modifier.padding(top = 4.dp))
            }
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = callbacks.refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                // Scrollable also when empty, so the list can be pulled down from the empty state too.
                LazyColumn(
                    Modifier.fillMaxSize().testTag(TrashTags.LIST),
                    contentPadding = PaddingValues(bottom = LocalFabClearance.current),
                ) {
                    if (state.loaded) {
                        item(key = "hint") {
                            Text(
                                stringResource(R.string.trash_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                    if (state.items.isEmpty() && state.loaded) {
                        item(key = "empty") {
                            Text(
                                stringResource(R.string.trash_empty),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(24.dp).testTag(TrashTags.EMPTY),
                            )
                        }
                    }
                    items(state.items, key = { it.key }) { item ->
                        TrashRow(
                            item = item,
                            busy = item.key in state.busy,
                            deleted = dates.format(item.deletedAt.atZone(zone)),
                            daysLeft = daysUntilPurge(item.purgeAt, now),
                            callbacks = callbacks,
                        )
                        HorizontalDivider(Modifier.padding(start = 56.dp))
                    }
                }
            }
        }
        SnackbarHost(snackbarHost, Modifier.align(Alignment.BottomCenter).padding(bottom = LocalFabClearance.current))
    }
    state.confirmingPurge?.let { PurgeDialog(it, callbacks.confirmPurge, callbacks.cancelPurge) }
}

@Composable
private fun TrashRow(
    item: TrashItem,
    busy: Boolean,
    deleted: String,
    daysLeft: Int,
    callbacks: TrashCallbacks,
) {
    val type = stringResource(trashTypeLabel(item.type))
    ListItem(
        headlineContent = { Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        overlineContent = {
            Text(
                listOfNotNull(type, item.subtitle).joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column {
                Text(stringResource(R.string.trash_deleted_at, deleted))
                Text(
                    if (daysLeft == 0) {
                        stringResource(R.string.trash_purge_today)
                    } else {
                        pluralStringResource(R.plurals.trash_purge_in_days, daysLeft, daysLeft)
                    },
                    color =
                        if (daysLeft <=
                            PURGE_SOON_DAYS
                        ) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    modifier = Modifier.testTag(TrashTags.purgeIn(item)),
                )
            }
        },
        leadingContent = { Icon(searchTypeStyle(item.type).icon, contentDescription = type) },
        trailingContent = {
            Row {
                IconButton(
                    onClick = { callbacks.restore(item) },
                    enabled = !busy,
                    modifier = Modifier.testTag(TrashTags.restore(item)),
                ) {
                    Icon(Icons.Outlined.RestoreFromTrash, contentDescription = stringResource(R.string.trash_restore_item, item.title))
                }
                IconButton(
                    onClick = { callbacks.purge(item) },
                    enabled = !busy,
                    modifier = Modifier.testTag(TrashTags.purge(item)),
                ) {
                    Icon(Icons.Outlined.DeleteForever, contentDescription = stringResource(R.string.trash_purge_item, item.title))
                }
            }
        },
        modifier = Modifier.testTag(TrashTags.item(item)),
    )
}

@Composable
private fun ListMessage(
    message: TrashMessage,
    /** Items are shown; offline, the message then says they are the last loaded ones. */
    loaded: Boolean,
    loading: Boolean,
    onRetry: () -> Unit,
) {
    val (text, color) =
        when (message) {
            TrashMessage.OFFLINE ->
                (if (loaded) R.string.trash_offline else R.string.trash_offline_empty) to MaterialTheme.colorScheme.secondaryContainer
            TrashMessage.FAILED -> R.string.trash_failed to MaterialTheme.colorScheme.errorContainer
        }
    Surface(color = color, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(TrashTags.MESSAGE)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (message == TrashMessage.OFFLINE) Icons.Outlined.CloudOff else Icons.Outlined.ErrorOutline, contentDescription = null)
            Text(
                stringResource(text),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
            )
            TextButton(onClick = onRetry, enabled = !loading, modifier = Modifier.testTag(TrashTags.RETRY)) {
                Text(stringResource(R.string.trash_retry))
            }
        }
    }
}

@Composable
private fun PurgeDialog(
    item: TrashItem,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Outlined.DeleteForever, contentDescription = null) },
        title = { Text(stringResource(R.string.trash_purge_title)) },
        text = { Text(stringResource(R.string.trash_purge_text, item.title)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(TrashTags.CONFIRM_DELETE)) {
                Text(stringResource(R.string.trash_purge_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.testTag(TrashTags.CONFIRM_CANCEL)) {
                Text(stringResource(R.string.trash_purge_cancel))
            }
        },
        modifier = Modifier.testTag(TrashTags.CONFIRM),
    )
}

@Composable
private fun TrashSnackbarEffect(
    snackbar: TrashSnackbar?,
    host: SnackbarHostState,
    onDismiss: (TrashSnackbar) -> Unit,
) {
    val resources = LocalResources.current
    LaunchedEffect(snackbar) {
        if (snackbar == null) return@LaunchedEffect
        val message =
            when (snackbar) {
                is TrashSnackbar.Restored -> resources.getString(R.string.trash_restored, snackbar.item.title)
                is TrashSnackbar.Purged -> resources.getString(R.string.trash_purged, snackbar.item.title)
                is TrashSnackbar.Failed ->
                    resources.getString(
                        when (snackbar.failure) {
                            TrashFailure.OFFLINE -> R.string.trash_change_offline
                            TrashFailure.FAILED -> R.string.trash_change_failed
                        },
                        snackbar.item.title,
                    )
            }
        host.showSnackbar(message = message, duration = SnackbarDuration.Short)
        onDismiss(snackbar)
    }
}
