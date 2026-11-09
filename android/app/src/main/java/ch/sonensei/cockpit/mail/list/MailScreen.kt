package ch.sonensei.cockpit.mail.list

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OutlinedFlag
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.mail.compose.ComposeMode
import ch.sonensei.cockpit.mail.compose.MailComposeRoute
import ch.sonensei.cockpit.mail.read.MailReadNavigation
import ch.sonensei.cockpit.mail.read.MailReadRoute
import ch.sonensei.cockpit.ui.PlaceholderScreen
import dagger.hilt.internal.GeneratedComponentManagerHolder
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
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
    var composing by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val searchState = rememberLazyListState()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Each answer replacing the results starts at the top (a search on the server puts new mail first, and the list
    // would otherwise keep the row it showed by its key); back from a result keeps the place. The version changes with
    // every answer, so no answer is missed when states are conflated.
    LaunchedEffect(viewModel) {
        viewModel.uiState
            .map { s -> s.search?.version }
            .distinctUntilChanged()
            .drop(1)
            .collect { if (it != null) searchState.requestScrollToItem(0) }
    }
    // Reloaded when the screen is shown and when the app comes back to it, so mail that arrived meanwhile appears.
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    val current = open?.let(UUID::fromString)
    val compose = composing?.let(ComposeMode::parse)
    if (compose != null) {
        MailComposeRoute(mode = compose, onClose = { composing = null }, modifier = modifier)
    } else if (current == null) {
        MailListContent(
            state = state,
            callbacks =
                MailListCallbacks(
                    open = {
                        if (state.isDraft(it)) composing = ComposeMode.Draft(it).encode() else open = it.toString()
                    },
                    select = viewModel::select,
                    act = viewModel::act,
                    loadMore = viewModel::loadMore,
                    loadOlder = viewModel::loadOlder,
                    retry = viewModel::refresh,
                    compose = { composing = ComposeMode.New(state.folder?.accountId).encode() },
                    search =
                        SearchCallbacks(
                            open = viewModel::openSearch,
                            submit = viewModel::search,
                            server = viewModel::searchOnServer,
                            retry = viewModel::retrySearch,
                            close = viewModel::closeSearch,
                        ),
                    folders =
                        FolderCallbacks(
                            create = viewModel::startCreateFolder,
                            rename = viewModel::startRenameFolder,
                            delete = viewModel::startDeleteFolder,
                            submit = viewModel::submitFolderEdit,
                            cancel = viewModel::cancelFolderEdit,
                        ),
                    selection =
                        SelectionCallbacks(
                            toggle = viewModel::toggleSelection,
                            clear = viewModel::clearSelection,
                            act = viewModel::actOnSelection,
                            move = viewModel::moveSelection,
                        ),
                    undo = viewModel::undo,
                    dismissUndo = viewModel::dismissUndo,
                ),
            listState = listState,
            searchState = searchState,
            modifier = modifier,
        )
    } else {
        // Previous/next step through the list the mail was opened from: the search results, or the mailbox.
        val search = state.search
        val ids = (search?.results ?: state.messages).map { it.id }
        // The last loaded mail of a mailbox fetches the next page, so "next" can go on (opening it tries once, also after
        // an earlier failure). A refresh or page load running meanwhile makes loadMore skip the request, so it asks again
        // once that load ends. A failed page load is not retried by itself (that would loop while offline): the list's
        // Retry does it, and a success clears the failure.
        val atLoadedEnd = search == null && ids.lastOrNull() == current && state.nextCursor != null
        LaunchedEffect(atLoadedEnd, current) {
            if (!atLoadedEnd) return@LaunchedEffect
            viewModel.loadMore()
            viewModel.uiState
                .map { it.canLoadAfter(current) }
                .distinctUntilChanged()
                .collect { can -> if (can) viewModel.loadMore() }
        }
        MailReadRoute(
            id = current,
            onClose = { open = null },
            onSeen = viewModel::markSeen,
            onRemoved = viewModel::removed,
            onMarked = viewModel::marked,
            accounts = state.accounts,
            onCompose = { composing = it.encode() },
            navigation = MailReadNavigation.of(ids, current) { open = it.toString() },
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
    /** "Load older from server" at the end of a folder. */
    val loadOlder: () -> Unit = {},
    val retry: () -> Unit = {},
    val compose: () -> Unit = {},
    val folders: FolderCallbacks = FolderCallbacks(),
    val search: SearchCallbacks = SearchCallbacks(),
    val selection: SelectionCallbacks = SelectionCallbacks(),
    val undo: (MailUndo) -> Unit = {},
    val dismissUndo: (MailUndo) -> Unit = {},
)

/** The multi-selection started by a long press, and its bulk actions. */
data class SelectionCallbacks(
    /** Adds or removes a mail (a long press starts the selection). */
    val toggle: (UUID) -> Unit = {},
    val clear: () -> Unit = {},
    /** A bulk action other than move. */
    val act: (MailListAction) -> Unit = {},
    val move: (MailFolder) -> Unit = {},
)

/** The search box and its results. */
data class SearchCallbacks(
    val open: () -> Unit = {},
    /** The query entered, as typed. */
    val submit: (String) -> Unit = {},
    /** Search the text in the box (as typed) on the mail server too. */
    val server: (String) -> Unit = {},
    val retry: () -> Unit = {},
    val close: () -> Unit = {},
)

/** The folder actions of the mailbox picker and its dialogs. */
data class FolderCallbacks(
    /** A new folder in the account, inside the folder when one is given. */
    val create: (UUID, MailFolder?) -> Unit = { _, _ -> },
    val rename: (MailFolder) -> Unit = {},
    val delete: (MailFolder) -> Unit = {},
    /** The open dialog's OK, with the name entered (empty when deleting). */
    val submit: (String) -> Unit = {},
    val cancel: () -> Unit = {},
)

internal object MailListTags {
    const val LIST = "mail-list"
    const val MAILBOX = "mail-mailbox"
    const val UNIFIED = "mail-mailbox-unified"
    const val EMPTY = "mail-empty"
    const val MESSAGE = "mail-message"
    const val RETRY = "mail-retry"
    const val LOADING_MORE = "mail-loading-more"
    const val LOAD_OLDER = "mail-load-older"
    const val LOADING_OLDER = "mail-loading-older"
    const val OLDER_MESSAGE = "mail-older-message"
    const val COMPOSE = "mail-compose"
    const val SEARCH_OPEN = "mail-search-open"
    const val SEARCH_FIELD = "mail-search-field"
    const val SEARCH_SUBMIT = "mail-search-submit"
    const val SEARCH_CLOSE = "mail-search-close"
    const val SEARCH_SERVER = "mail-search-server"
    const val SEARCH_FAILURES = "mail-search-failures"
    const val SEARCH_HAS_MORE = "mail-search-has-more"
    const val SEARCH_EMPTY = "mail-search-empty"
    const val SEARCH_RESULTS = "mail-search-results"

    fun row(id: UUID) = "mail-row:$id"

    fun folder(id: UUID) = "mail-folder:$id"

    fun account(id: UUID) = "mail-account:$id"

    fun newFolder(accountId: UUID) = "mail-folder-new:$accountId"

    fun folderActions(id: UUID) = "mail-folder-actions:$id"

    const val FOLDER_NEW_SUB = "mail-folder-new-sub"
    const val FOLDER_RENAME = "mail-folder-rename"
    const val FOLDER_DELETE = "mail-folder-delete"
    const val FOLDER_DIALOG = "mail-folder-dialog"
    const val FOLDER_NAME = "mail-folder-name"
    const val FOLDER_ERROR = "mail-folder-error"
    const val FOLDER_CONFIRM = "mail-folder-confirm"
    const val FOLDER_CANCEL = "mail-folder-cancel"

    fun swipe(
        id: UUID,
        action: MailListAction,
    ) = "mail-swipe:$id:$action"

    const val SELECTION_BAR = "mail-selection-bar"
    const val SELECTION_CLOSE = "mail-selection-close"
    const val SELECTION_COUNT = "mail-selection-count"
    const val SELECTION_MORE = "mail-selection-more"

    fun selectionAction(action: MailListAction) = "mail-selection-action:$action"
}

/** Rows from the end at which the next page is loaded. */
private const val LOAD_MORE_AHEAD = 10

/** The last page load (first page or next page) failed; it is only retried by the user. */
private val MailListUiState.pageFailed: Boolean
    get() = message == MailListMessage.OFFLINE || message == MailListMessage.FAILED

/** [id] is the last loaded mail of the mailbox shown, more mail exists, and nothing keeps the next page from loading. */
private fun MailListUiState.canLoadAfter(id: UUID): Boolean =
    search == null &&
        messages.lastOrNull()?.id == id &&
        nextCursor != null &&
        !loading &&
        !loadingMore &&
        !pageFailed

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
    searchState: LazyListState = rememberLazyListState(),
) {
    val snackbarHost = remember { SnackbarHostState() }
    UndoSnackbarEffect(state.undo, snackbarHost, callbacks.undo, callbacks.dismissUndo)
    // Back ends a multi-selection before it leaves the screen.
    BackHandler(enabled = state.selecting && state.search == null, onBack = callbacks.selection.clear)
    Box(modifier.fillMaxSize()) {
        val search = state.search
        if (search != null) {
            MailSearchContent(state, search, callbacks, searchState, clock)
        } else {
            MailboxContent(state, callbacks, listState, clock)
        }
        if (!state.selecting) {
            ExtendedFloatingActionButton(
                onClick = callbacks.compose,
                icon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                text = { Text(stringResource(R.string.mail_compose)) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag(MailListTags.COMPOSE),
            )
        }
        // Above the compose button, which it would otherwise cover.
        SnackbarHost(snackbarHost, Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp))
    }
    state.folderEdit?.let { FolderDialog(it, callbacks.folders) }
}

/** Offers to undo [undo] ("3 mails archived  Undo") until it is taken, dismissed or replaced by the next action. */
@Composable
private fun UndoSnackbarEffect(
    undo: MailUndo?,
    host: SnackbarHostState,
    onUndo: (MailUndo) -> Unit,
    onDismiss: (MailUndo) -> Unit,
) {
    val resources = LocalResources.current
    LaunchedEffect(undo) {
        if (undo == null) return@LaunchedEffect
        val result =
            host.showSnackbar(
                message = resources.getQuantityString(doneText(undo.action), undo.mails.size, undo.mails.size),
                actionLabel = resources.getString(R.string.mail_undo),
                duration = SnackbarDuration.Long,
            )
        if (result == SnackbarResult.ActionPerformed) onUndo(undo) else onDismiss(undo)
    }
}

private fun doneText(action: MailListAction): Int =
    when (action) {
        MailListAction.ARCHIVE -> R.plurals.mail_done_archive
        MailListAction.DELETE -> R.plurals.mail_done_delete
        MailListAction.SPAM -> R.plurals.mail_done_spam
        MailListAction.MOVE -> R.plurals.mail_done_move
        MailListAction.READ -> R.plurals.mail_done_read
        MailListAction.UNREAD -> R.plurals.mail_done_unread
        MailListAction.FLAG -> R.plurals.mail_done_flag
        MailListAction.UNFLAG -> R.plurals.mail_done_unflag
    }

/** The mailbox picker with the search action, and the mailbox's mails. */
@Composable
private fun MailboxContent(
    state: MailListUiState,
    callbacks: MailListCallbacks,
    listState: LazyListState,
    clock: Clock,
) {
    Column(Modifier.fillMaxSize()) {
        if (state.selecting) {
            SelectionBar(state, callbacks.selection)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { MailboxPicker(state, callbacks.select, callbacks.folders) }
                IconButton(onClick = callbacks.search.open, modifier = Modifier.padding(end = 8.dp).testTag(MailListTags.SEARCH_OPEN)) {
                    Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.mail_search))
                }
            }
        }
        state.message?.let { ListMessage(it, callbacks.retry) }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
        when {
            state.loaded && state.messages.isEmpty() ->
                Column {
                    Text(
                        stringResource(if (state.accounts.isEmpty()) R.string.mail_no_accounts else R.string.mail_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp).testTag(MailListTags.EMPTY),
                    )
                    if (state.canLoadOlder) LoadOlder(state, callbacks.loadOlder)
                }
            else -> Messages(state, callbacks, listState, clock)
        }
    }
}

/**
 * Shown instead of the mailbox picker while mails are selected: the count, archive, delete, read/unread and flag (each
 * as the selection needs it: "Mark read" while one is unread, "Remove flag" when all are flagged), and in the overflow
 * "Move to folder" (when all are of one account) and spam.
 */
@Composable
private fun SelectionBar(
    state: MailListUiState,
    callbacks: SelectionCallbacks,
) {
    val mails = state.selection
    val read = if (mails.any { !it.seen }) MailListAction.READ else MailListAction.UNREAD
    val flag = if (mails.isNotEmpty() && mails.all { it.flagged }) MailListAction.UNFLAG else MailListAction.FLAG
    val targets = state.moveTargets
    var menu by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp).testTag(MailListTags.SELECTION_BAR),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = callbacks.clear, modifier = Modifier.testTag(MailListTags.SELECTION_CLOSE)) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.mail_selection_close))
        }
        Text(
            pluralStringResource(R.plurals.mail_selection_count, state.selected.size, state.selected.size),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).testTag(MailListTags.SELECTION_COUNT),
        )
        if (state.canArchive) SelectionButton(Icons.Outlined.Archive, R.string.mail_archive, MailListAction.ARCHIVE, callbacks.act)
        SelectionButton(Icons.Outlined.Delete, R.string.mail_delete, MailListAction.DELETE, callbacks.act)
        if (read == MailListAction.READ) {
            SelectionButton(Icons.Outlined.MarkEmailRead, R.string.mail_mark_read, read, callbacks.act)
        } else {
            SelectionButton(Icons.Outlined.MarkEmailUnread, R.string.mail_mark_unread, read, callbacks.act)
        }
        if (flag == MailListAction.FLAG) {
            SelectionButton(Icons.Outlined.Flag, R.string.mail_flag, flag, callbacks.act)
        } else {
            SelectionButton(Icons.Outlined.OutlinedFlag, R.string.mail_unflag, flag, callbacks.act)
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag(MailListTags.SELECTION_MORE)) {
                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.mail_more_actions))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.mail_move)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, contentDescription = null) },
                    enabled = targets.isNotEmpty(),
                    onClick = {
                        menu = false
                        moving = true
                    },
                    modifier = Modifier.testTag(MailListTags.selectionAction(MailListAction.MOVE)),
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.mail_spam)) },
                    leadingIcon = { Icon(Icons.Outlined.Report, contentDescription = null) },
                    onClick = {
                        menu = false
                        callbacks.act(MailListAction.SPAM)
                    },
                    modifier = Modifier.testTag(MailListTags.selectionAction(MailListAction.SPAM)),
                )
            }
        }
    }
    if (moving) {
        MoveFolderDialog(
            folders = targets,
            onPick = {
                moving = false
                callbacks.move(it)
            },
            onDismiss = { moving = false },
        )
    }
}

@Composable
private fun SelectionButton(
    icon: ImageVector,
    label: Int,
    action: MailListAction,
    act: (MailListAction) -> Unit,
) {
    IconButton(onClick = { act(action) }, modifier = Modifier.testTag(MailListTags.selectionAction(action))) {
        Icon(icon, contentDescription = stringResource(label))
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
    // Asks again whenever a page can be loaded while near the end: after a load ends or finds more mail. A failed load
    // is not retried by itself (that would loop while offline); the Retry button does it, and a success clears it.
    val canLoadMore by rememberUpdatedState(state.nextCursor != null && !state.loading && !state.loadingMore && !state.pageFailed)
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
    // The bottom padding keeps the last row clear of the compose button.
    LazyColumn(Modifier.fillMaxSize().testTag(MailListTags.LIST), state = listState, contentPadding = PaddingValues(bottom = 88.dp)) {
        items(state.messages, key = { it.id }) { mail ->
            // While selecting, a tap picks a mail instead of opening it, and the swipes rest.
            val selecting = state.selecting
            SwipeRow(mail, state.canArchive, callbacks, enabled = !selecting) {
                MailRow(
                    mail = mail,
                    account = state.account(mail.accountId)?.takeIf { state.showAccounts },
                    today = today,
                    clock = clock,
                    open = if (selecting) callbacks.selection.toggle else callbacks.open,
                    selected = mail.id in state.selected,
                    onLongClick = callbacks.selection.toggle,
                )
            }
            HorizontalDivider()
        }
        if (state.loadingMore) {
            item(key = MailListTags.LOADING_MORE) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp).testTag(MailListTags.LOADING_MORE))
            }
        }
        if (state.canLoadOlder) {
            item(key = MailListTags.LOAD_OLDER) { LoadOlder(state, callbacks.loadOlder) }
        }
    }
}

/**
 * The end of a folder's cached mail: "Load older from server" (SPEC.md §7.2), its progress, and why the last attempt
 * added nothing. The message does not block anything; the button tries again.
 */
@Composable
private fun LoadOlder(
    state: MailListUiState,
    loadOlder: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        state.olderMessage?.let { message ->
            val text =
                when (message) {
                    OlderMessage.OFFLINE -> R.string.mail_older_offline
                    OlderMessage.FAILED -> R.string.mail_older_failed
                    OlderMessage.NOTHING_OLDER -> R.string.mail_nothing_older
                }
            Text(
                stringResource(text),
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (message ==
                        OlderMessage.NOTHING_OLDER
                    ) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                modifier = Modifier.padding(bottom = 8.dp).testTag(MailListTags.OLDER_MESSAGE),
            )
        }
        if (state.loadingOlder) {
            Text(
                stringResource(R.string.mail_loading_older),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag(MailListTags.LOADING_OLDER))
        }
        OutlinedButton(onClick = loadOlder, enabled = !state.loadingOlder, modifier = Modifier.testTag(MailListTags.LOAD_OLDER)) {
            Text(stringResource(R.string.mail_load_older))
        }
    }
}

@Composable
private fun SwipeRow(
    mail: MailSummary,
    canArchive: Boolean,
    callbacks: MailListCallbacks,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState()
    val archive = stringResource(R.string.mail_archive)
    val delete = stringResource(R.string.mail_delete)
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = enabled && canArchive,
        enableDismissFromEndToStart = enabled,
        gesturesEnabled = enabled,
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
                    ).takeIf { enabled }.orEmpty()
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
internal fun MailRow(
    mail: MailSummary,
    account: MailAccount?,
    today: LocalDate,
    clock: Clock,
    open: (UUID) -> Unit,
    selected: Boolean = false,
    /** A long press (starts or changes the multi-selection); `null` where rows cannot be selected. */
    onLongClick: ((UUID) -> Unit)? = null,
) {
    val unread = stringResource(R.string.mail_unread)
    val selectedLabel = stringResource(R.string.mail_selected)
    Surface(color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { open(mail.id) },
                    onLongClick = onLongClick?.let { { it(mail.id) } },
                    onLongClickLabel = onLongClick?.let { stringResource(R.string.mail_select) },
                ).semantics { if (onLongClick != null) this.selected = selected }
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag(MailListTags.row(mail.id)),
            verticalAlignment = Alignment.Top,
        ) {
            // The unread dot, or the check of a selected mail.
            Box(Modifier.padding(top = 6.dp).width(16.dp)) {
                if (selected) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = selectedLabel,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                } else if (!mail.seen) {
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
                    Text(it.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    }
}

@Composable
private fun MailboxPicker(
    state: MailListUiState,
    select: (Mailbox) -> Unit,
    folderActions: FolderCallbacks,
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        account.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier =
                            Modifier
                                .weight(1f, fill = false)
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .testTag(MailListTags.account(account.id)),
                    )
                    IconButton(
                        onClick = {
                            expanded = false
                            folderActions.create(account.id, null)
                        },
                        modifier = Modifier.testTag(MailListTags.newFolder(account.id)),
                    ) {
                        Icon(
                            Icons.Outlined.CreateNewFolder,
                            contentDescription = stringResource(R.string.mail_folder_new_in, account.label),
                        )
                    }
                }
                folders.forEach { folder ->
                    val label = folderLabel(folder)
                    // The actions sit beside the row, not in it, so a tap on the row always opens the folder.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DropdownMenuItem(
                            text = { Text(label, modifier = Modifier.padding(start = (folder.depth * 12).dp)) },
                            onClick = {
                                expanded = false
                                select(Mailbox.Folder(account.id, folder.id))
                            },
                            modifier = Modifier.weight(1f).testTag(MailListTags.folder(folder.id)),
                        )
                        FolderMenu(folder, label, folderActions) { expanded = false }
                    }
                }
            }
        }
    }
}

/** The overflow menu of one folder: a new subfolder, and renaming and deleting where the server allows it. */
@Composable
private fun FolderMenu(
    folder: MailFolder,
    label: String,
    folderActions: FolderCallbacks,
    closePicker: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val pick: (() -> Unit) -> Unit = { action ->
        open = false
        closePicker()
        action()
    }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag(MailListTags.folderActions(folder.id))) {
            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.mail_folder_actions, label))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.mail_folder_new_sub)) },
                leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) },
                onClick = { pick { folderActions.create(folder.accountId, folder) } },
                modifier = Modifier.testTag(MailListTags.FOLDER_NEW_SUB),
            )
            if (folder.canRename) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.mail_folder_rename)) },
                    leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null) },
                    onClick = { pick { folderActions.rename(folder) } },
                    modifier = Modifier.testTag(MailListTags.FOLDER_RENAME),
                )
            }
            if (folder.canDelete) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.mail_folder_delete)) },
                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                    onClick = { pick { folderActions.delete(folder) } },
                    modifier = Modifier.testTag(MailListTags.FOLDER_DELETE),
                )
            }
        }
    }
}

/**
 * The dialog of [edit]: the name of a new or renamed folder, or the confirmation of a delete. Server errors show in
 * it, and it stays open until the server did it or it is cancelled.
 */
@Composable
private fun FolderDialog(
    edit: FolderEdit,
    folderActions: FolderCallbacks,
) {
    val folder = edit.folder
    val label = folder?.let { folderLabel(it) }
    var name by rememberSaveable(edit.kind, edit.accountId, folder?.id) {
        mutableStateOf(if (edit.kind == FolderEditKind.RENAME) folder?.name.orEmpty() else "")
    }
    val deleting = edit.kind == FolderEditKind.DELETE
    val title =
        when (edit.kind) {
            FolderEditKind.CREATE ->
                if (label ==
                    null
                ) {
                    stringResource(R.string.mail_folder_new_title)
                } else {
                    stringResource(R.string.mail_folder_new_sub_title, label)
                }
            FolderEditKind.RENAME -> stringResource(R.string.mail_folder_rename_title, label.orEmpty())
            FolderEditKind.DELETE -> stringResource(R.string.mail_folder_delete_title, label.orEmpty())
        }
    val confirm =
        when (edit.kind) {
            FolderEditKind.CREATE -> R.string.mail_folder_create
            FolderEditKind.RENAME -> R.string.mail_folder_save
            FolderEditKind.DELETE -> R.string.mail_folder_delete_confirm
        }
    val canConfirm = !edit.busy && (deleting || name.isNotBlank())
    val submit = { if (canConfirm) folderActions.submit(if (deleting) "" else name) }
    AlertDialog(
        onDismissRequest = folderActions.cancel,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (deleting) {
                    Text(stringResource(R.string.mail_folder_delete_text))
                } else {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.mail_folder_name)) },
                        singleLine = true,
                        enabled = !edit.busy,
                        isError = edit.error != null,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier.fillMaxWidth().testTag(MailListTags.FOLDER_NAME),
                    )
                }
                if (edit.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                edit.error?.let {
                    Text(
                        stringResource(folderErrorText(it)),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag(MailListTags.FOLDER_ERROR),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = canConfirm, modifier = Modifier.testTag(MailListTags.FOLDER_CONFIRM)) {
                Text(stringResource(confirm), color = if (deleting && canConfirm) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        },
        dismissButton = {
            TextButton(onClick = folderActions.cancel, modifier = Modifier.testTag(MailListTags.FOLDER_CANCEL)) {
                Text(stringResource(R.string.mail_folder_cancel))
            }
        },
        modifier = Modifier.testTag(MailListTags.FOLDER_DIALOG),
    )
}

private fun folderErrorText(error: FolderEditError): Int =
    when (error) {
        FolderEditError.NAME_TAKEN -> R.string.mail_folder_error_name_taken
        FolderEditError.INVALID_NAME -> R.string.mail_folder_error_invalid_name
        FolderEditError.UNREACHABLE -> R.string.mail_folder_error_unreachable
        FolderEditError.CANNOT_DELETE -> R.string.mail_folder_error_cannot_delete
        FolderEditError.FAILED -> R.string.mail_folder_error_failed
    }

/** Special folders by their role (servers name them in many ways), the others by name. */
@Composable
internal fun folderLabel(folder: MailFolder): String =
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
internal fun ListMessage(
    message: MailListMessage,
    retry: () -> Unit,
) {
    val text =
        when (message) {
            MailListMessage.OFFLINE -> R.string.mail_offline
            MailListMessage.FAILED -> R.string.mail_failed
            MailListMessage.ARCHIVE_FAILED -> R.string.mail_archive_failed
            MailListMessage.DELETE_FAILED -> R.string.mail_delete_failed
            MailListMessage.FOLDER_CHANGED -> R.string.mail_folder_changed
            MailListMessage.SPAM_FAILED -> R.string.mail_spam_failed
            MailListMessage.MOVE_FAILED -> R.string.mail_move_failed
            MailListMessage.MARK_FAILED -> R.string.mail_mark_failed
            MailListMessage.UNDO_FAILED -> R.string.mail_undo_failed
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
