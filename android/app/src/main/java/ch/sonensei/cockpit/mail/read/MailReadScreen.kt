package ch.sonensei.cockpit.mail.read

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import ch.sonensei.cockpit.mail.list.MailListAction
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

/**
 * One mail, opened from a list. [onSeen] runs once it is shown (opening marks it read), [onRemoved] when it was
 * archived or deleted here; the view is closed by [onClose].
 */
@Composable
fun MailReadRoute(
    id: UUID,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onSeen: (UUID) -> Unit = {},
    onRemoved: (UUID) -> Unit = {},
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
            onRemoved(id)
            close()
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
            ),
        modifier = modifier,
    )
}

/** What the read view can ask for, so it can be tested without a view model. */
data class MailReadCallbacks(
    val back: () -> Unit = {},
    val retry: () -> Unit = {},
    val gtd: (MailGtdAction) -> Unit = {},
    val act: (MailListAction) -> Unit = {},
)

internal object MailReadTags {
    const val BACK = "mail-read-back"
    const val SUBJECT = "mail-read-subject"
    const val HEADERS = "mail-read-headers"
    const val BODY = "mail-read-body"
    const val TEXT = "mail-read-text"
    const val ERROR = "mail-read-error"
    const val RETRY = "mail-read-retry"
    const val NOTICE = "mail-read-notice"
    const val IMAGES_BLOCKED = "mail-read-images-blocked"
    const val ATTACHMENTS = "mail-read-attachments"

    fun gtd(action: MailGtdAction) = "mail-read-gtd:$action"

    fun action(action: MailListAction) = "mail-read-action:$action"
}

/**
 * The read view: toolbar (back, GTD actions, archive, delete), headers, then the sanitized HTML in [body] (a locked-down
 * WebView; replaceable in tests) or the plain text of a text-only mail.
 */
@Composable
fun MailReadContent(
    state: MailReadUiState,
    callbacks: MailReadCallbacks,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    body: @Composable (String, Modifier) -> Unit = { html, m -> MailBodyView(html, m) },
) {
    Column(modifier.fillMaxSize()) {
        Toolbar(state, callbacks)
        if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
        state.notice?.let { Notice(it) }
        val message = state.message
        when {
            message != null -> Message(message, zone, body)
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
            is MailReadNotice.ActionFailed ->
                when (notice.action) {
                    MailListAction.ARCHIVE -> stringResource(R.string.mail_archive_failed)
                    MailListAction.DELETE -> stringResource(R.string.mail_delete_failed)
                }
        }
    val color = if (notice is MailReadNotice.Created) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag(MailReadTags.NOTICE),
    )
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
        if (message.remoteImagesBlocked > 0) {
            HeaderLine(
                pluralStringResource(R.plurals.mail_read_images_blocked, message.remoteImagesBlocked, message.remoteImagesBlocked),
                Modifier.testTag(MailReadTags.IMAGES_BLOCKED),
            )
        }
    }
    HorizontalDivider(Modifier.padding(top = 8.dp))
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
