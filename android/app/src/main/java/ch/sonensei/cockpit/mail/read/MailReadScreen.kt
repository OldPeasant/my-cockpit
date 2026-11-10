package ch.sonensei.cockpit.mail.read

import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.Forward
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.automirrored.outlined.ReplyAll
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.OutlinedFlag
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.core.links.ItemRef
import ch.sonensei.cockpit.core.links.LinkedItemsSection
import ch.sonensei.cockpit.core.links.LinkedItemsViewModel
import ch.sonensei.cockpit.core.links.linksViewModelKey
import ch.sonensei.cockpit.files.OpenedFiles
import ch.sonensei.cockpit.files.discardDocument
import ch.sonensei.cockpit.files.openForWriting
import ch.sonensei.cockpit.mail.compose.ComposeMode
import ch.sonensei.cockpit.mail.list.MailAccountFolders
import ch.sonensei.cockpit.mail.list.MailFolder
import ch.sonensei.cockpit.mail.list.MailListAction
import ch.sonensei.cockpit.mail.list.MoveFolderDialog
import ch.sonensei.cockpit.search.SearchItemScreen
import ch.sonensei.cockpit.search.SearchTarget
import dagger.hilt.internal.GeneratedComponentManagerHolder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

/**
 * One mail, opened from a list. [onSeen] runs once it is shown (opening marks it read), [onRemoved] when it was
 * archived, deleted or moved to spam here; the view then shows the next mail of [navigation], or closes (by [onClose])
 * when there is none. [navigation] (previous/next) is given when the mail was opened from a list of mails.
 */
@Composable
fun MailReadRoute(
    id: UUID,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onSeen: (UUID) -> Unit = {},
    onRemoved: (UUID) -> Unit = {},
    onCompose: (ComposeMode) -> Unit = {},
    navigation: MailReadNavigation? = null,
    onMarked: (UUID, MailListAction) -> Unit = { _, _ -> },
    accounts: List<MailAccountFolders> = emptyList(),
    viewModel: MailReadViewModel = hiltViewModel(),
    attachmentsViewModel: MailAttachmentsViewModel = hiltViewModel(),
) {
    // An item opened from the linked-items panel shows over the mail; back returns to the mail.
    var linked by rememberSaveable(id) { mutableStateOf<String?>(null) }
    val item = linked?.split(':')?.let { (type, itemId) -> type to UUID.fromString(itemId) }
    if (item != null) {
        BackHandler { linked = null }
        SearchItemScreen(item.first, item.second, onClose = { linked = null }, modifier = modifier)
        return
    }
    // One view model for all mails (cleared with the Mail screen); it drops the mail when the view closes.
    LaunchedEffect(viewModel, id) { viewModel.open(id) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val links = mailLinks(state.message?.id)
    // A task or note made from the mail is linked to it: show it in the panel.
    LaunchedEffect(state.notice) { if (state.notice is MailReadNotice.Created) links?.reload() }
    val close = {
        viewModel.close()
        onClose()
    }
    BackHandler(onBack = close)
    LaunchedEffect(state.message?.id) { if (state.message != null) onSeen(id) }
    LaunchedEffect(state.mark) { state.mark?.let { onMarked(id, it.action) } }
    LaunchedEffect(state.unread) {
        if (state.unread) {
            onMarked(id, MailListAction.UNREAD)
            close()
        }
    }
    LaunchedEffect(state.removed) {
        if (state.removed) {
            // The neighbours are those of the list before the mail left it.
            val list = navigation
            val next = list?.next
            onRemoved(id)
            if (list != null && next != null) list.open(next) else close()
        }
    }
    LaunchedEffect(attachmentsViewModel, state.message?.id) { attachmentsViewModel.show(state.message?.id) }
    val attachments by attachmentsViewModel.uiState.collectAsStateWithLifecycle()
    MailReadContent(
        state = state,
        attachments = attachments,
        attachmentCallbacks = rememberAttachmentCallbacks(attachmentsViewModel),
        callbacks =
            MailReadCallbacks(
                back = close,
                retry = viewModel::retry,
                gtd = viewModel::gtd,
                act = { viewModel.act(it) },
                move = { viewModel.act(MailListAction.MOVE, it.id) },
                compose = onCompose,
                loadImages = viewModel::loadImages,
                alwaysLoadImages = viewModel::alwaysLoadImages,
            ),
        modifier = modifier,
        navigation = navigation,
        moveTargets = moveTargets(accounts, state.message),
        body = { html, m -> MailBodyView(html, m, viewModel.images, key = state.message?.id) },
        linksPanel = {
            val messageId = state.message?.id
            if (links != null && messageId != null) {
                LinkedItemsSection(
                    ItemRef(MAIL_ITEM_TYPE, messageId),
                    open = { target -> if (target is SearchTarget.InApp) linked = "${target.type}:${target.id}" },
                    viewModel = links,
                )
            }
        },
    )
}

/** The search/links type of a mail. */
private const val MAIL_ITEM_TYPE = "EMAIL"

/** The links panel's view model of the shown mail; `null` without a mail or without Hilt (screen tests with fake view models). */
@Composable
private fun mailLinks(messageId: UUID?): LinkedItemsViewModel? =
    if (messageId != null && LocalActivity.current is GeneratedComponentManagerHolder) {
        hiltViewModel(key = linksViewModelKey(ItemRef(MAIL_ITEM_TYPE, messageId)))
    } else {
        null
    }

/**
 * The list a mail was opened from (search results or a mailbox), in its order: the mail at [position] (from 0) of
 * [count], its neighbours [previous] and [next] (`null` at the ends), and [open] to show one of them.
 */
data class MailReadNavigation(
    val position: Int,
    val count: Int,
    val previous: UUID?,
    val next: UUID?,
    val open: (UUID) -> Unit,
) {
    companion object {
        /** [id]'s place in [ids], `null` when it is not (or no longer) in the list. */
        fun of(
            ids: List<UUID>,
            id: UUID,
            open: (UUID) -> Unit,
        ): MailReadNavigation? {
            val index = ids.indexOf(id)
            if (index < 0) return null
            return MailReadNavigation(index, ids.size, ids.getOrNull(index - 1), ids.getOrNull(index + 1), open)
        }
    }
}

/** The folders [message] can be moved to: the other folders of its account. */
internal fun moveTargets(
    accounts: List<MailAccountFolders>,
    message: MailMessage?,
): List<MailFolder> {
    val accountId = message?.accountId ?: return emptyList()
    return accounts
        .firstOrNull { it.account.id == accountId }
        ?.folders
        .orEmpty()
        .filterNot { it.id == message.folderId }
}

/** What the read view can ask for, so it can be tested without a view model. */
data class MailReadCallbacks(
    val back: () -> Unit = {},
    val retry: () -> Unit = {},
    val gtd: (MailGtdAction) -> Unit = {},
    val act: (MailListAction) -> Unit = {},
    /** "Move to folder" picked [MailFolder]. */
    val move: (MailFolder) -> Unit = {},
    val compose: (ComposeMode) -> Unit = {},
    /** "Load images": the blocked remote images of this mail, through the server's proxy. */
    val loadImages: () -> Unit = {},
    /** "Always for this sender": allows the sender's remote images from now on, and loads them. */
    val alwaysLoadImages: () -> Unit = {},
)

internal object MailReadTags {
    const val BACK = "mail-read-back"
    const val NAVIGATION = "mail-read-navigation"
    const val POSITION = "mail-read-position"
    const val PREVIOUS = "mail-read-previous"
    const val NEXT = "mail-read-next"
    const val SUBJECT = "mail-read-subject"
    const val HEADERS = "mail-read-headers"
    const val BODY = "mail-read-body"
    const val TEXT = "mail-read-text"
    const val ERROR = "mail-read-error"
    const val RETRY = "mail-read-retry"
    const val NOTICE = "mail-read-notice"
    const val IMAGES_BLOCKED = "mail-read-images-blocked"
    const val IMAGES_BANNER = "mail-read-images-banner"
    const val LOAD_IMAGES = "mail-read-load-images"
    const val ALWAYS_IMAGES = "mail-read-always-images"
    const val ATTACHMENTS = "mail-read-attachments"
    const val REPLY = "mail-read-reply"
    const val REPLY_ALL = "mail-read-reply-all"
    const val FORWARD = "mail-read-forward"
    const val MORE = "mail-read-more"
    const val LINKS = "mail-read-links"
    const val PAGE = "mail-read-page"

    fun gtd(action: MailGtdAction) = "mail-read-gtd:$action"

    fun action(action: MailListAction) = "mail-read-action:$action"
}

/**
 * The read view: toolbar (back, To task, To Waiting, archive, delete, a menu with "Create note", unread, flag, move and
 * spam), previous/next through the list it was opened from
 * ([navigation], when given), and below them one page that scrolls: headers, then the sanitized HTML in [body] (a
 * locked-down WebView at its full height; replaceable in tests) or the plain text of a text-only mail, then the mail's
 * linked items ([linksPanel]).
 */
@Composable
fun MailReadContent(
    state: MailReadUiState,
    callbacks: MailReadCallbacks,
    modifier: Modifier = Modifier,
    attachments: MailAttachmentsUiState = MailAttachmentsUiState(),
    attachmentCallbacks: MailAttachmentCallbacks = MailAttachmentCallbacks(),
    zone: ZoneId = ZoneId.systemDefault(),
    navigation: MailReadNavigation? = null,
    moveTargets: List<MailFolder> = emptyList(),
    body: @Composable (String, Modifier) -> Unit = { html, m -> MailBodyView(html, m) },
    linksPanel: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxSize()) {
        Toolbar(state, callbacks, moveTargets)
        navigation?.let { NavigationRow(it, enabled = !state.busy) }
        if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
        state.notice?.let { Notice(it) }
        val message = state.message
        when {
            message != null ->
                // One scroll per mail: the next mail starts at its top.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(key(message.id) { rememberScrollState() })
                        .testTag(MailReadTags.PAGE),
                ) {
                    Message(message, zone, body, callbacks.takeUnless { state.busy }, linksPanel) {
                        AttachmentList(message.attachments, attachments, attachmentCallbacks, enabled = !state.busy)
                    }
                }
            state.error != null -> Error(state.error, callbacks.retry)
        }
    }
}

@Composable
private fun Toolbar(
    state: MailReadUiState,
    callbacks: MailReadCallbacks,
    moveTargets: List<MailFolder>,
) {
    val enabled = state.message != null && !state.busy
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = callbacks.back, modifier = Modifier.testTag(MailReadTags.BACK)) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.mail_read_back))
        }
        Spacer(Modifier.weight(1f))
        ToolbarButton(Icons.Outlined.Inbox, R.string.mail_read_to_task, MailReadTags.gtd(MailGtdAction.TASK), enabled) {
            callbacks.gtd(MailGtdAction.TASK)
        }
        ToolbarButton(Icons.Outlined.HourglassEmpty, R.string.mail_read_to_waiting, MailReadTags.gtd(MailGtdAction.WAITING), enabled) {
            callbacks.gtd(MailGtdAction.WAITING)
        }
        ToolbarButton(Icons.Outlined.Archive, R.string.mail_archive, MailReadTags.action(MailListAction.ARCHIVE), enabled) {
            callbacks.act(MailListAction.ARCHIVE)
        }
        ToolbarButton(Icons.Outlined.Delete, R.string.mail_delete, MailReadTags.action(MailListAction.DELETE), enabled) {
            callbacks.act(MailListAction.DELETE)
        }
        MoreMenu(state.message?.flagged == true, enabled, callbacks, moveTargets)
    }
}

/**
 * The overflow of the toolbar: create a note from the mail, mark unread, flag or remove the flag, move to folder (of the
 * same account) and spam. "Create note" sits here, not in the toolbar, so the toolbar still fits a 320dp-wide window.
 */
@Composable
private fun MoreMenu(
    flagged: Boolean,
    enabled: Boolean,
    callbacks: MailReadCallbacks,
    moveTargets: List<MailFolder>,
) {
    var open by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    val pick: (() -> Unit) -> Unit = { action ->
        open = false
        action()
    }
    Box {
        ToolbarButton(Icons.Outlined.MoreVert, R.string.mail_more_actions, MailReadTags.MORE, enabled) { open = true }
        DropdownMenu(expanded = open && enabled, onDismissRequest = { open = false }) {
            MenuItem(Icons.Outlined.NoteAdd, R.string.mail_read_to_note, MailReadTags.gtd(MailGtdAction.NOTE)) {
                pick { callbacks.gtd(MailGtdAction.NOTE) }
            }
            MenuItem(Icons.Outlined.MarkEmailUnread, R.string.mail_mark_unread, MailReadTags.action(MailListAction.UNREAD)) {
                pick { callbacks.act(MailListAction.UNREAD) }
            }
            if (flagged) {
                MenuItem(Icons.Outlined.OutlinedFlag, R.string.mail_unflag, MailReadTags.action(MailListAction.UNFLAG)) {
                    pick { callbacks.act(MailListAction.UNFLAG) }
                }
            } else {
                MenuItem(Icons.Outlined.Flag, R.string.mail_flag, MailReadTags.action(MailListAction.FLAG)) {
                    pick { callbacks.act(MailListAction.FLAG) }
                }
            }
            MenuItem(
                Icons.AutoMirrored.Outlined.DriveFileMove,
                R.string.mail_move,
                MailReadTags.action(MailListAction.MOVE),
                moveTargets.isNotEmpty(),
            ) {
                pick { moving = true }
            }
            MenuItem(Icons.Outlined.Report, R.string.mail_spam, MailReadTags.action(MailListAction.SPAM)) {
                pick { callbacks.act(MailListAction.SPAM) }
            }
        }
    }
    if (moving) {
        MoveFolderDialog(
            folders = moveTargets,
            onPick = {
                moving = false
                callbacks.move(it)
            },
            onDismiss = { moving = false },
        )
    }
}

@Composable
private fun MenuItem(
    icon: ImageVector,
    label: Int,
    tag: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.testTag(tag),
    )
}

/** "3 of 12" with previous and next (disabled at the ends and while an action runs). */
@Composable
private fun NavigationRow(
    navigation: MailReadNavigation,
    enabled: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp).testTag(MailReadTags.NAVIGATION),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.mail_read_position, navigation.position + 1, navigation.count),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).testTag(MailReadTags.POSITION),
        )
        ToolbarButton(
            Icons.Outlined.KeyboardArrowUp,
            R.string.mail_read_previous,
            MailReadTags.PREVIOUS,
            enabled && navigation.previous != null,
        ) {
            navigation.previous?.let(navigation.open)
        }
        ToolbarButton(Icons.Outlined.KeyboardArrowDown, R.string.mail_read_next, MailReadTags.NEXT, enabled && navigation.next != null) {
            navigation.next?.let(navigation.open)
        }
    }
}

@Composable
private fun ToolbarButton(
    icon: ImageVector,
    label: Int,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.testTag(tag)) {
        Icon(icon, contentDescription = stringResource(label))
    }
}

@Composable
private fun Notice(notice: MailReadNotice) {
    val text =
        when (notice) {
            is MailReadNotice.Created ->
                when (notice.action) {
                    MailGtdAction.TASK -> stringResource(R.string.mail_read_task_created, notice.title)
                    MailGtdAction.WAITING -> stringResource(R.string.mail_read_waiting_created, notice.title)
                    MailGtdAction.NOTE -> stringResource(R.string.mail_read_note_created, notice.title)
                }
            is MailReadNotice.GtdFailed ->
                when (notice.action) {
                    MailGtdAction.TASK -> stringResource(R.string.mail_read_task_failed)
                    MailGtdAction.WAITING -> stringResource(R.string.mail_read_waiting_failed)
                    MailGtdAction.NOTE -> stringResource(R.string.mail_read_note_failed)
                }
            is MailReadNotice.ActionFailed -> actionFailed(notice)
            MailReadNotice.ImagesFailed -> stringResource(R.string.mail_read_images_failed)
            MailReadNotice.SenderNotSaved -> stringResource(R.string.mail_read_sender_not_saved)
        }
    val color = if (notice is MailReadNotice.Created) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag(MailReadTags.NOTICE),
    )
}

/** "Could not …", followed by the server's reason when it gave one. */
@Composable
private fun actionFailed(notice: MailReadNotice.ActionFailed): String {
    val failed =
        when (notice.action) {
            MailListAction.ARCHIVE -> stringResource(R.string.mail_archive_failed)
            MailListAction.DELETE -> stringResource(R.string.mail_delete_failed)
            MailListAction.SPAM -> stringResource(R.string.mail_spam_failed)
            MailListAction.MOVE -> stringResource(R.string.mail_move_failed)
            MailListAction.READ, MailListAction.UNREAD, MailListAction.FLAG, MailListAction.UNFLAG ->
                stringResource(R.string.mail_mark_failed)
        }
    val reason =
        when (val code = notice.errorCode) {
            null -> null
            NO_ROLE_FOLDER ->
                roleFolder(notice.action)?.let { stringResource(R.string.mail_read_no_role_folder, stringResource(it)) }
                    ?: stringResource(R.string.mail_read_action_error, code)
            else -> stringResource(R.string.mail_read_action_error, code)
        }
    return if (reason == null) failed else "$failed $reason"
}

private const val NO_ROLE_FOLDER = "NO_ROLE_FOLDER"

/** The folder role an action moves the mail to; `null` for the others. */
private fun roleFolder(action: MailListAction): Int? =
    when (action) {
        MailListAction.ARCHIVE -> R.string.mail_folder_archive
        MailListAction.DELETE -> R.string.mail_folder_trash
        MailListAction.SPAM -> R.string.mail_folder_spam
        else -> null
    }

@Composable
private fun Error(
    error: MailReadError,
    retry: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val text =
            when (error) {
                MailReadError.OFFLINE -> R.string.mail_read_offline
                MailReadError.FAILED -> R.string.mail_read_failed
                MailReadError.GONE -> R.string.mail_read_gone
            }
        Text(stringResource(text), modifier = Modifier.testTag(MailReadTags.ERROR))
        if (error != MailReadError.GONE) {
            TextButton(onClick = retry, modifier = Modifier.testTag(MailReadTags.RETRY)) { Text(stringResource(R.string.mail_retry)) }
        }
    }
}

@Composable
private fun Message(
    message: MailMessage,
    zone: ZoneId,
    body: @Composable (String, Modifier) -> Unit,
    callbacks: MailReadCallbacks?,
    linksPanel: @Composable () -> Unit,
    attachments: @Composable () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(MailReadTags.HEADERS),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            message.subject.ifBlank { stringResource(R.string.mail_no_subject) },
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.testTag(MailReadTags.SUBJECT),
        )
        Text(
            stringResource(R.string.mail_read_from, message.from),
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (message.to.isNotEmpty()) HeaderLine(stringResource(R.string.mail_read_to, message.to.joinToString()))
        if (message.cc.isNotEmpty()) HeaderLine(stringResource(R.string.mail_read_cc, message.cc.joinToString()))
        message.date?.let { HeaderLine(formatFull(it, zone)) }
    }
    if (message.attachments.isNotEmpty()) attachments()
    if (message.remoteImagesBlocked > 0) ImagesBanner(message, callbacks)
    ReplyButtons(message.id, callbacks?.compose)
    HorizontalDivider()
    // The body at its full height: the page scrolls, not the body.
    if (message.html.isNotBlank()) {
        body(message.html, Modifier.fillMaxWidth())
    } else {
        SelectionContainer(Modifier.fillMaxWidth()) {
            Text(
                message.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp).testTag(MailReadTags.TEXT),
            )
        }
    }
    // After the mail (as in the web app), all of them, reached by scrolling to the end.
    Column(Modifier.fillMaxWidth().testTag(MailReadTags.LINKS)) {
        linksPanel()
    }
}

/**
 * "N remote images were blocked." with "Load images" and, when the mail has a sender address, "Always for this
 * sender"; the buttons wait while an action runs ([callbacks] `null`).
 */
@Composable
private fun ImagesBanner(
    message: MailMessage,
    callbacks: MailReadCallbacks?,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag(MailReadTags.IMAGES_BANNER),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.HideImage, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(
                    pluralStringResource(R.plurals.mail_read_images_blocked, message.remoteImagesBlocked, message.remoteImagesBlocked),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(MailReadTags.IMAGES_BLOCKED),
                )
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = { callbacks?.loadImages?.invoke() },
                    enabled = callbacks != null,
                    modifier = Modifier.testTag(MailReadTags.LOAD_IMAGES),
                ) { Text(stringResource(R.string.mail_read_load_images)) }
                if (message.fromAddress != null) {
                    TextButton(
                        onClick = { callbacks?.alwaysLoadImages?.invoke() },
                        enabled = callbacks != null,
                        modifier = Modifier.testTag(MailReadTags.ALWAYS_IMAGES),
                    ) { Text(stringResource(R.string.mail_read_always_images)) }
                }
            }
        }
    }
}

/** Reply, reply all and forward start a draft from the mail (SPEC.md §7.3); disabled while an action runs. */
@Composable
private fun ReplyButtons(
    id: UUID,
    compose: ((ComposeMode) -> Unit)?,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ReplyButton(Icons.AutoMirrored.Outlined.Reply, R.string.mail_read_reply, MailReadTags.REPLY, compose != null) {
            compose?.invoke(ComposeMode.Reply(id))
        }
        ReplyButton(Icons.AutoMirrored.Outlined.ReplyAll, R.string.mail_read_reply_all, MailReadTags.REPLY_ALL, compose != null) {
            compose?.invoke(ComposeMode.ReplyAll(id))
        }
        ReplyButton(Icons.AutoMirrored.Outlined.Forward, R.string.mail_read_forward, MailReadTags.FORWARD, compose != null) {
            compose?.invoke(ComposeMode.Forward(id))
        }
    }
}

@Composable
private fun ReplyButton(
    icon: ImageVector,
    label: Int,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.testTag(tag)) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
        Text(stringResource(label))
    }
}

@Composable
private fun HeaderLine(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * The attachment actions of [viewModel] with the system's parts: "Open" hands the downloaded file to another app, "Save
 * to device" asks where with the system "save as" picker. The view model keeps these callbacks past the activity: they
 * hold the application, never the activity.
 */
@Composable
private fun rememberAttachmentCallbacks(viewModel: MailAttachmentsViewModel): MailAttachmentCallbacks {
    val context = LocalContext.current
    val app = context.applicationContext
    val resolver = app.contentResolver
    LaunchedEffect(viewModel) {
        viewModel.opens.collect { open ->
            if (!OpenedFiles.launch(context, OpenedFiles.view(context, open.file, viewType(open)))) viewModel.noApp()
        }
    }
    // Saveable: the activity may be recreated while the "save as" picker is open.
    // With its mail: after the process was restored, the answer can come before the mail is shown again.
    var saving by rememberSaveable(stateSaver = PendingSaveSaver) { mutableStateOf<PendingSave?>(null) }
    val pickSaveTarget =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
            val pending = saving
            saving = null
            if (uri != null && pending != null) {
                viewModel.save(pending.attachment, { openForWriting(resolver, uri) }, pending.mail) { discardDocument(resolver, uri) }
            }
        }
    val target = { attachment: MailAttachment ->
        { mail: UUID -> OpenedFiles.target(app, cacheId(mail, attachment), attachment.name) }
    }
    return remember(viewModel, pickSaveTarget) {
        MailAttachmentCallbacks(
            open = { viewModel.open(it, target(it)) },
            preview = { viewModel.preview(it, target(it)) },
            saveToDevice = {
                saving = PendingSave(it, viewModel.shown)
                pickSaveTarget.launch(it.name)
            },
            saveToFiles = viewModel::pickFolder,
            dismissNotice = viewModel::dismissNotice,
            closePreview = viewModel::closePreview,
            openPreviewed = viewModel::openPreviewed,
            openFolder = viewModel::openFolder,
            goToFolder = viewModel::goToFolder,
            saveHere = viewModel::saveToFiles,
            closePicker = viewModel::closePicker,
        )
    }
}

/** The mail's type for the attachment; for none or a generic one, the type its file extension stands for. */
private fun viewType(open: OpenAttachment): String =
    open.mime?.takeUnless { it.substringBefore(';').trim().equals(OCTET_STREAM, ignoreCase = true) }
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(open.file.extension.lowercase())
        ?: OCTET_STREAM

private const val OCTET_STREAM = "application/octet-stream"

/** The attachment waiting for the "save as" picker's answer, and its [mail] (`null`: none was shown). */
private data class PendingSave(
    val attachment: MailAttachment,
    val mail: UUID?,
)

/** A [PendingSave], kept as strings in the saved state. */
private val PendingSaveSaver =
    Saver<PendingSave?, List<String>>(
        save = { p ->
            p?.attachment?.let {
                listOf(it.partId, it.name, it.sizeBytes?.toString().orEmpty(), it.contentType.orEmpty(), p.mail?.toString().orEmpty())
            }
        },
        restore = {
            PendingSave(
                MailAttachment(it[0], it[1], it[2].toLongOrNull(), it[3].ifEmpty { null }),
                it.getOrNull(4)?.takeIf(String::isNotEmpty)?.let(UUID::fromString),
            )
        },
    )

/**
 * The cache folder of an attachment opened in another app: one per mail and part, so the name it is shown with stays
 * its own and two mails with the same part never share a file.
 */
private fun cacheId(
    mail: UUID,
    attachment: MailAttachment,
): UUID = UUID.nameUUIDFromBytes("mail-attachment/$mail/${attachment.partId}/${attachment.name}".toByteArray())

private fun formatFull(
    instant: Instant,
    zone: ZoneId,
): String = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(instant.atZone(zone))
