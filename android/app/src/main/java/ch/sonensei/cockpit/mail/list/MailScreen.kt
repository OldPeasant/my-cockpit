package ch.sonensei.cockpit.mail.list

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.mail.read.MailReadRoute
import ch.sonensei.cockpit.ui.PlaceholderScreen
import dagger.hilt.internal.GeneratedComponentManagerHolder
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

/**
 * The Mail screen. Its view models come from Hilt, so outside a Hilt activity (the shell's navigation tests,
 * previews) it shows the placeholder instead of failing.
 */
@Composable
fun MailScreen(modifier: Modifier = Modifier) {
    if (LocalActivity.current is GeneratedComponentManagerHolder) {
        MailRoute(modifier)
    } else {
        PlaceholderScreen(R.string.nav_mail, Icons.Outlined.Mail, modifier)
    }
}

/** The list, or one mail opened from it (kept across configuration changes); back returns to the list where it was. */
@Composable
fun MailRoute(
    modifier: Modifier = Modifier,
    viewModel: MailListViewModel = hiltViewModel(),
) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Reloaded when the screen is shown and when the app comes back to it, so mail that arrived meanwhile appears.
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    val current = open?.let(UUID::fromString)
    if (current == null) {
        MailListContent(
            state = state,
            callbacks =
                MailListCallbacks(
                    open = { open = it.toString() },
                    select = viewModel::select,
                    act = viewModel::act,
                    loadMore = viewModel::loadMore,
                    retry = viewModel::refresh,
                ),
            listState = listState,
            modifier = modifier,
        )
    } else {
        MailReadRoute(
            id = current,
            onClose = { open = null },
            onSeen = viewModel::markSeen,
            onRemoved = viewModel::removed,
            modifier = modifier,
        )
    }
}

/** What the list can ask for, so it can be tested without a view model. */
data class MailListCallbacks(
    val open: (UUID) -> Unit = {},
    val select: (Mailbox) -> Unit = {},
    val act: (UUID, MailListAction) -> Unit = { _, _ -> },
    val loadMore: () -> Unit = {},
    val retry: () -> Unit = {},
)

internal object MailListTags {
    const val LIST = "mail-list"
    const val MAILBOX = "mail-mailbox"
    const val UNIFIED = "mail-mailbox-unified"
    const val EMPTY = "mail-empty"
    const val MESSAGE = "mail-message"
    const val RETRY = "mail-retry"
    const val LOADING_MORE = "mail-loading-more"

    fun row(id: UUID) = "mail-row:$id"

    fun folder(id: UUID) = "mail-folder:$id"

    fun account(id: UUID) = "mail-account:$id"

    fun swipe(
        id: UUID,
        action: MailListAction,
    ) = "mail-swipe:$id:$action"
}

/** Rows from the end at which the next page is loaded. */
private const val LOAD_MORE_AHEAD = 10

/**
 * The mailbox picker and the mails, newest first. A row swiped to the right is archived, to the left deleted (also
 * offered as accessibility actions); the next page loads as the end comes near.
 */
@Composable
fun MailListContent(
    state: MailListUiState,
    callbacks: MailListCallbacks,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    clock: Clock = Clock.systemDefaultZone(),
) {
    Column(modifier.fillMaxSize()) {
        MailboxPicker(state, callbacks.select)
        state.message?.let { ListMessage(it, callbacks.retry) }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
        when {
            state.loaded && state.messages.isEmpty() ->
                Text(
                    stringResource(if (state.accounts.isEmpty()) R.string.mail_no_accounts else R.string.mail_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp).testTag(MailListTags.EMPTY),
                )
            else -> Messages(state, callbacks, listState, clock)
        }
    }
}

@Composable
private fun Messages(
    state: MailListUiState,
    callbacks: MailListCallbacks,
    listState: LazyListState,
    clock: Clock,
) {
    val loadMore by rememberUpdatedState(callbacks.loadMore)
    // Asks again whenever a page can be loaded while near the end: after a load ends, fails, or finds more mail.
    val canLoadMore by rememberUpdatedState(state.nextCursor != null && !state.loading && !state.loadingMore)
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val nearEnd =
                info.totalItemsCount > 0 &&
                    (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - LOAD_MORE_AHEAD
            nearEnd && canLoadMore
        }.distinctUntilChanged()
            .collect { load -> if (load) loadMore() }
    }
    val today = LocalDate.now(clock)
    LazyColumn(Modifier.fillMaxSize().testTag(MailListTags.LIST), state = listState) {
        items(state.messages, key = { it.id }) { mail ->
            SwipeRow(mail, state.canArchive, callbacks) {
                MailRow(mail, state.account(mail.accountId)?.takeIf { state.showAccounts }, today, clock, callbacks.open)
            }
            HorizontalDivider()
        }
        if (state.loadingMore) {
            item(key = MailListTags.LOADING_MORE) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp).testTag(MailListTags.LOADING_MORE))
            }
        }
    }
}

@Composable
private fun SwipeRow(
    mail: MailSummary,
    canArchive: Boolean,
    callbacks: MailListCallbacks,
    content: @Composable () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState()
    val archive = stringResource(R.string.mail_archive)
    val delete = stringResource(R.string.mail_delete)
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = canArchive,
        enableDismissFromEndToStart = true,
        onDismiss = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> callbacks.act(mail.id, MailListAction.ARCHIVE)
                SwipeToDismissBoxValue.EndToStart -> callbacks.act(mail.id, MailListAction.DELETE)
                SwipeToDismissBoxValue.Settled -> Unit
            }
        },
        backgroundContent = { SwipeBackground(dismissState.dismissDirection) },
        modifier =
            Modifier.semantics {
                customActions =
                    listOfNotNull(
                        CustomAccessibilityAction(archive) {
                            callbacks.act(mail.id, MailListAction.ARCHIVE)
                            true
                        }.takeIf { canArchive },
                        CustomAccessibilityAction(delete) {
                            callbacks.act(mail.id, MailListAction.DELETE)
                            true
                        },
                    )
            },
    ) {
        content()
    }
}

@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue) {
    val (icon, label, color, alignment) =
        when (direction) {
            SwipeToDismissBoxValue.StartToEnd ->
                SwipeLook(
                    Icons.Outlined.Archive,
                    R.string.mail_archive,
                    MaterialTheme.colorScheme.secondaryContainer,
                    Alignment.CenterStart,
                )
            SwipeToDismissBoxValue.EndToStart ->
                SwipeLook(Icons.Outlined.Delete, R.string.mail_delete, MaterialTheme.colorScheme.errorContainer, Alignment.CenterEnd)
            SwipeToDismissBoxValue.Settled -> return
        }
    Box(Modifier.fillMaxSize().background(color).padding(horizontal = 24.dp), contentAlignment = alignment) {
        Icon(icon, contentDescription = stringResource(label))
    }
}

private data class SwipeLook(
    val icon: ImageVector,
    val label: Int,
    val color: Color,
    val alignment: Alignment,
)

@Composable
private fun MailRow(
    mail: MailSummary,
    account: MailAccount?,
    today: LocalDate,
    clock: Clock,
    open: (UUID) -> Unit,
) {
    val unread = stringResource(R.string.mail_unread)
    Surface {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open(mail.id) }
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag(MailListTags.row(mail.id)),
            verticalAlignment = Alignment.Top,
        ) {
            // The unread dot.
            Box(Modifier.padding(top = 6.dp).width(16.dp)) {
                if (!mail.seen) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .semantics { contentDescription = unread },
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        mail.sender,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (mail.seen) FontWeight.Normal else FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (mail.hasAttachments) {
                        Icon(
                            Icons.Outlined.AttachFile,
                            contentDescription = stringResource(R.string.mail_has_attachments),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    if (mail.flagged) {
                        Icon(
                            Icons.Outlined.Flag,
                            contentDescription = stringResource(R.string.mail_flagged),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    mail.date?.let {
                        Text(
                            formatDate(it, today, clock),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                Text(
                    mail.subject.ifBlank { stringResource(R.string.mail_no_subject) },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (mail.seen) FontWeight.Normal else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (mail.snippet.isNotBlank()) {
                    Text(
                        mail.snippet,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                account?.let {
                    Text(it.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    }
}

@Composable
private fun MailboxPicker(
    state: MailListUiState,
    select: (Mailbox) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val title = state.folder?.let { folderLabel(it) } ?: stringResource(R.string.mail_unified)
    val choose = stringResource(R.string.mail_choose_mailbox)
    Box(Modifier.padding(horizontal = 8.dp)) {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag(MailListTags.MAILBOX).semantics { contentDescription = "$choose: $title" },
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.mail_unified)) },
                onClick = {
                    expanded = false
                    select(Mailbox.Unified)
                },
                modifier = Modifier.testTag(MailListTags.UNIFIED),
            )
            state.accounts.forEach { (account, folders) ->
                HorizontalDivider()
                Text(
                    account.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).testTag(MailListTags.account(account.id)),
                )
                folders.forEach { folder ->
                    DropdownMenuItem(
                        text = { Text(folderLabel(folder), modifier = Modifier.padding(start = (folder.depth * 12).dp)) },
                        onClick = {
                            expanded = false
                            select(Mailbox.Folder(account.id, folder.id))
                        },
                        modifier = Modifier.testTag(MailListTags.folder(folder.id)),
                    )
                }
            }
        }
    }
}

/** Special folders by their role (servers name them in many ways), the others by name. */
@Composable
private fun folderLabel(folder: MailFolder): String =
    when (folder.role) {
        FolderRole.INBOX -> stringResource(R.string.mail_folder_inbox)
        FolderRole.DRAFTS -> stringResource(R.string.mail_folder_drafts)
        FolderRole.SENT -> stringResource(R.string.mail_folder_sent)
        FolderRole.ARCHIVE -> stringResource(R.string.mail_folder_archive)
        FolderRole.SPAM -> stringResource(R.string.mail_folder_spam)
        FolderRole.TRASH -> stringResource(R.string.mail_folder_trash)
        null -> folder.name
    }

@Composable
private fun ListMessage(
    message: MailListMessage,
    retry: () -> Unit,
) {
    val text =
        when (message) {
            MailListMessage.OFFLINE -> R.string.mail_offline
            MailListMessage.FAILED -> R.string.mail_failed
            MailListMessage.ARCHIVE_FAILED -> R.string.mail_archive_failed
            MailListMessage.DELETE_FAILED -> R.string.mail_delete_failed
        }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f).testTag(MailListTags.MESSAGE),
        )
        TextButton(onClick = retry, modifier = Modifier.testTag(MailListTags.RETRY)) { Text(stringResource(R.string.mail_retry)) }
    }
}

/** Today's mail by time, older mail by date. */
private fun formatDate(
    instant: Instant,
    today: LocalDate,
    clock: Clock,
): String {
    val local = instant.atZone(clock.zone)
    val style = FormatStyle.SHORT
    return if (local.toLocalDate() == today) {
        DateTimeFormatter.ofLocalizedTime(style).format(local)
    } else {
        DateTimeFormatter.ofLocalizedDate(style).format(local)
    }
}
