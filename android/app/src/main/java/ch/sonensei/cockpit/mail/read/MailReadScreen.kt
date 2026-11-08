package ch.sonensei.cockpit.mail.read

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.automirrored.outlined.Forward
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.automirrored.outlined.ReplyAll
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Report
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.mail.compose.ComposeMode
import ch.sonensei.cockpit.mail.list.MailListAction
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
    viewModel: MailReadViewModel = hiltViewModel(),
) {
    // One view model for all mails (cleared with the Mail screen); it drops the mail when the view closes.
    LaunchedEffect(viewModel, id) { viewModel.open(id) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val close = {
        viewModel.close()
        onClose()
    }
    BackHandler(onBack = close)
    LaunchedEffect(state.message?.id) { if (state.message != null) onSeen(id) }
    LaunchedEffect(state.removed) {
        if (state.removed) {
            // The neighbours are those of the list before the mail left it.
            val list = navigation
            val next = list?.next
            onRemoved(id)
            if (list != null && next != null) list.open(next) else close()
        }
    }
    MailReadContent(
        state = state,
        callbacks =
            MailReadCallbacks(
                back = close,
                retry = viewModel::retry,
                gtd = viewModel::gtd,
                act = viewModel::act,
                compose = onCompose,
                loadImages = viewModel::loadImages,
                alwaysLoadImages = viewModel::alwaysLoadImages,
            ),
        modifier = modifier,
        navigation = navigation,
        body = { html, m -> MailBodyView(html, m, viewModel.images) },
    )
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

/** What the read view can ask for, so it can be tested without a view model. */
data class MailReadCallbacks(
    val back: () -> Unit = {},
    val retry: () -> Unit = {},
    val gtd: (MailGtdAction) -> Unit = {},
    val act: (MailListAction) -> Unit = {},
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

    fun gtd(action: MailGtdAction) = "mail-read-gtd:$action"

    fun action(action: MailListAction) = "mail-read-action:$action"
}

/**
 * The read view: toolbar (back, GTD actions, archive, delete, spam), previous/next through the list it was opened from
 * ([navigation], when given), headers, then the sanitized HTML in [body] (a locked-down WebView; replaceable in tests)
 * or the plain text of a text-only mail.
 */
@Composable
fun MailReadContent(
    state: MailReadUiState,
    callbacks: MailReadCallbacks,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    navigation: MailReadNavigation? = null,
    body: @Composable (String, Modifier) -> Unit = { html, m -> MailBodyView(html, m) },
) {
    Column(modifier.fillMaxSize()) {
        Toolbar(state, callbacks)
        navigation?.let { NavigationRow(it, enabled = !state.busy) }
        if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
        state.notice?.let { Notice(it) }
        val message = state.message
        when {
            message != null -> Message(message, zone, body, callbacks.takeUnless { state.busy })
            state.error != null -> Error(state.error, callbacks.retry)
        }
    }
}

@Composable
private fun Toolbar(
    state: MailReadUiState,
    callbacks: MailReadCallbacks,
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
        ToolbarButton(Icons.Outlined.Report, R.string.mail_spam, MailReadTags.action(MailListAction.SPAM), enabled) {
            callbacks.act(MailListAction.SPAM)
        }
    }
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
                }
            is MailReadNotice.GtdFailed ->
                when (notice.action) {
                    MailGtdAction.TASK -> stringResource(R.string.mail_read_task_failed)
                    MailGtdAction.WAITING -> stringResource(R.string.mail_read_waiting_failed)
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
        }
    val reason =
        when (val code = notice.errorCode) {
            null -> null
            NO_ROLE_FOLDER -> stringResource(R.string.mail_read_no_role_folder, stringResource(roleFolder(notice.action)))
            else -> stringResource(R.string.mail_read_action_error, code)
        }
    return if (reason == null) failed else "$failed $reason"
}

private const val NO_ROLE_FOLDER = "NO_ROLE_FOLDER"

/** The folder role an action moves the mail to. */
private fun roleFolder(action: MailListAction) =
    when (action) {
        MailListAction.ARCHIVE -> R.string.mail_folder_archive
        MailListAction.DELETE -> R.string.mail_folder_trash
        MailListAction.SPAM -> R.string.mail_folder_spam
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
private fun ColumnScope.Message(
    message: MailMessage,
    zone: ZoneId,
    body: @Composable (String, Modifier) -> Unit,
    callbacks: MailReadCallbacks?,
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
        if (message.attachments.isNotEmpty()) {
            HeaderLine(
                stringResource(R.string.mail_read_attachments, message.attachments.joinToString { it.name }),
                Modifier.testTag(MailReadTags.ATTACHMENTS),
            )
        }
    }
    if (message.remoteImagesBlocked > 0) ImagesBanner(message, callbacks)
    ReplyButtons(message.id, callbacks?.compose)
    HorizontalDivider()
    if (message.html.isNotBlank()) {
        body(message.html, Modifier.fillMaxWidth().weight(1f))
    } else {
        SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
            Text(
                message.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp).testTag(MailReadTags.TEXT),
            )
        }
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

private fun formatFull(
    instant: Instant,
    zone: ZoneId,
): String = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(instant.atZone(zone))
