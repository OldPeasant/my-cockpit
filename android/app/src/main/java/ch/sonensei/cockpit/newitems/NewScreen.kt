package ch.sonensei.cockpit.newitems

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.task.LocalTaskOpener
import ch.sonensei.cockpit.mail.compose.ComposeMode
import ch.sonensei.cockpit.mail.compose.MailComposeRoute
import ch.sonensei.cockpit.mail.list.MailSummary
import ch.sonensei.cockpit.mail.read.MailReadRoute
import ch.sonensei.cockpit.mail.read.notArchived
import ch.sonensei.cockpit.ui.PlaceholderScreen
import dagger.hilt.internal.GeneratedComponentManagerHolder
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

/**
 * The New screen. Its view models come from Hilt, so outside a Hilt activity (the shell's navigation tests, previews)
 * it shows the placeholder instead of failing.
 */
@Composable
fun NewScreen(modifier: Modifier = Modifier) {
    if (LocalActivity.current is GeneratedComponentManagerHolder) {
        NewRoute(modifier)
    } else {
        PlaceholderScreen(R.string.nav_new, Icons.Outlined.NewReleases, modifier)
    }
}

/**
 * What is open on top of the list (kept across configuration changes). Tasks are not opened here: they open the shell's
 * task detail screen, which lies on top of the whole New screen.
 */
internal sealed interface NewOpen {
    data class Mail(
        val id: UUID,
    ) : NewOpen

    fun encode(): String =
        when (this) {
            is Mail -> "mail:$id"
        }

    companion object {
        fun parse(value: String): NewOpen? {
            val (kind, id) = value.split(':', limit = 2).takeIf { it.size == 2 } ?: return null
            val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return null
            return when (kind) {
                "mail" -> Mail(uuid)
                else -> null
            }
        }
    }
}

/**
 * The list, or the mail opened from it; back returns to the list. A task opens the task detail screen (SPEC.md §5.3),
 * and back from there returns to this list with its filter (view model) and scroll position (saved [listState]).
 */
@Composable
fun NewRoute(
    modifier: Modifier = Modifier,
    viewModel: NewViewModel = hiltViewModel(),
) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var composing by rememberSaveable { mutableStateOf<String?>(null) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Kept here, so the list keeps its position while a mail is open on top of it; saved while the task detail is.
    val listState = rememberLazyListState()
    val taskOpener = LocalTaskOpener.current
    // Reloaded when the screen is shown and when the app comes back to it, so mail that arrived meanwhile appears.
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    val compose = composing?.let(ComposeMode::parse)
    // "Next action & archive" made a task: its detail opens, back returns to this list.
    LaunchedEffect(state.openTask) {
        state.openTask?.let { taskId ->
            viewModel.taskOpened()
            taskOpener?.open(taskId, null)
        }
    }
    when (val current = open?.let(NewOpen::parse)) {
        null ->
            NewContent(
                state = state,
                callbacks =
                    NewCallbacks(
                        setFilter = viewModel::setFilter,
                        open = { open = it.encode() },
                        openTask = { task -> taskOpener?.open(task.id, task) },
                        toTask = viewModel::toTask,
                        nextAction = viewModel::nextAction,
                        loadMore = viewModel::loadMore,
                        retry = viewModel::retry,
                        dismissNotice = viewModel::dismissNotice,
                    ),
                modifier = modifier,
                listState = listState,
            )
        is NewOpen.Mail ->
            if (compose != null) {
                MailComposeRoute(mode = compose, onClose = { composing = null }, modifier = modifier)
            } else {
                MailReadRoute(
                    id = current.id,
                    onClose = { open = null },
                    onSeen = viewModel::markSeen,
                    onRemoved = viewModel::removed,
                    onCompose = { composing = it.encode() },
                    modifier = modifier,
                )
            }
    }
}

/** What the list can ask for, so it can be tested without a view model. */
internal data class NewCallbacks(
    val setFilter: (NewFilter) -> Unit = {},
    val open: (NewOpen) -> Unit = {},
    /** Opens an Inbox task in the task detail screen. */
    val openTask: (GtdTask) -> Unit = {},
    val toTask: (UUID) -> Unit = {},
    /** "Next action & archive" for a mail. */
    val nextAction: (UUID) -> Unit = {},
    val loadMore: () -> Unit = {},
    val retry: () -> Unit = {},
    val dismissNotice: () -> Unit = {},
)

internal object NewTags {
    const val LIST = "new-list"
    const val EMPTY = "new-empty"
    const val LOADING = "new-loading"
    const val OFFLINE = "new-offline"
    const val ERROR = "new-error"
    const val RETRY = "new-retry"
    const val NOTICE = "new-notice"
    const val LOAD_MORE = "new-load-more"

    fun filter(filter: NewFilter) = "new-filter-${filter.name.lowercase()}"

    fun row(row: NewRow) = "new-row:${row.key}"

    fun source(row: NewRow) = "new-source:${row.key}"

    fun toTask(id: UUID) = "new-to-task:$id"

    fun nextAction(id: UUID) = "new-next-action:$id"
}

/** The source filter, the banners and the merged rows, newest first. */
@Composable
internal fun NewContent(
    state: NewUiState,
    callbacks: NewCallbacks,
    modifier: Modifier = Modifier,
    clock: Clock = Clock.systemDefaultZone(),
    listState: LazyListState = rememberLazyListState(),
) {
    Column(modifier.fillMaxSize()) {
        Filters(state.filter, callbacks.setFilter)
        if (state.offline) OfflineBanner(state, clock, callbacks.retry)
        if (!state.offline && (state.inboxFailed || state.mailFailed)) ErrorBanner(state, callbacks.retry)
        state.notice?.let { Notice(it, callbacks.dismissNotice) }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
        when {
            state.rows.isNotEmpty() -> Rows(state, callbacks, listState, clock)
            state.firstLoad ->
                Text(
                    stringResource(R.string.new_loading),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(24.dp).testTag(NewTags.LOADING),
                )
            else ->
                Text(
                    stringResource(R.string.new_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp).testTag(NewTags.EMPTY),
                )
        }
    }
}

@Composable
private fun Filters(
    selected: NewFilter,
    select: (NewFilter) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NewFilter.entries.forEach { filter ->
            FilterChip(
                selected = filter == selected,
                onClick = { select(filter) },
                label = {
                    Text(
                        stringResource(
                            when (filter) {
                                NewFilter.ALL -> R.string.new_filter_all
                                NewFilter.INBOX -> R.string.new_filter_inbox
                                NewFilter.MAIL -> R.string.new_filter_mail
                            },
                        ),
                    )
                },
                modifier = Modifier.testTag(NewTags.filter(filter)),
            )
        }
    }
}

@Composable
private fun Rows(
    state: NewUiState,
    callbacks: NewCallbacks,
    listState: LazyListState,
    clock: Clock,
) {
    val today = LocalDate.now(clock)
    LazyColumn(Modifier.fillMaxSize().testTag(NewTags.LIST), state = listState) {
        items(state.rows, key = { it.key }) { row ->
            when (row) {
                is NewRow.Task -> TaskRow(row, today, clock, callbacks.openTask)
                is NewRow.Mail -> MailRow(row, state, today, clock, callbacks)
            }
            HorizontalDivider()
        }
        // Only the mail part is paged; the Inbox filter shows every task already.
        if (state.moreMail && state.filter != NewFilter.INBOX) {
            item(key = NewTags.LOAD_MORE) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    OutlinedButton(
                        onClick = callbacks.loadMore,
                        enabled = !state.loadingMore && !state.loading,
                        modifier = Modifier.testTag(NewTags.LOAD_MORE),
                    ) { Text(stringResource(R.string.new_load_more)) }
                }
            }
        }
    }
}

@Composable
private fun TaskRow(
    row: NewRow.Task,
    today: LocalDate,
    clock: Clock,
    openTask: (GtdTask) -> Unit,
) {
    val onClick = { openTask(row.task) }
    RowFrame(row, Icons.Outlined.Inbox, R.string.new_source_inbox, today, clock, onClick = onClick) {
        Text(row.task.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        row.task.notesMd?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MailRow(
    row: NewRow.Mail,
    state: NewUiState,
    today: LocalDate,
    clock: Clock,
    callbacks: NewCallbacks,
) {
    val mail = row.mail
    RowFrame(row, Icons.Outlined.Mail, R.string.new_source_mail, today, clock, onClick = { callbacks.open(NewOpen.Mail(mail.id)) }) {
        MailText(mail)
        val enabled = state.canConvert && mail.id !in state.converting
        // Wraps to two lines on narrow screens (320dp) instead of squeezing the buttons.
        FlowRow {
            TextButton(
                onClick = { callbacks.toTask(mail.id) },
                enabled = enabled,
                modifier = Modifier.testTag(NewTags.toTask(mail.id)),
            ) { Text(stringResource(R.string.new_to_task)) }
            TextButton(
                onClick = { callbacks.nextAction(mail.id) },
                enabled = enabled,
                modifier = Modifier.testTag(NewTags.nextAction(mail.id)),
            ) { Text(stringResource(R.string.new_next_action)) }
        }
    }
}

@Composable
private fun MailText(mail: MailSummary) {
    val unread = stringResource(R.string.mail_unread)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!mail.seen) {
            Box(
                Modifier
                    .padding(end = 6.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .semantics { contentDescription = unread },
            )
        }
        Text(
            mail.subject.ifBlank { stringResource(R.string.new_no_subject) },
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (mail.seen) FontWeight.Normal else FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    val from = listOf(mail.sender, mail.snippet).filter { it.isNotBlank() }.joinToString(" – ")
    if (from.isNotEmpty()) {
        Text(
            from,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A row: the source marker, the item's [content] and its date; tapping it opens the item. */
@Composable
private fun RowFrame(
    row: NewRow,
    icon: ImageVector,
    sourceLabel: Int,
    today: LocalDate,
    clock: Clock,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp)
                .testTag(NewTags.row(row)),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(sourceLabel),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f).testTag(NewTags.source(row)),
                )
                row.at?.let {
                    Text(
                        formatDate(it, today, clock),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun OfflineBanner(
    state: NewUiState,
    clock: Clock,
    retry: () -> Unit,
) {
    val format = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
    val details =
        listOfNotNull(
            state.inboxRefreshedAt?.takeIf { state.inboxOffline }?.let {
                stringResource(R.string.new_offline_inbox_at, format.format(it.atZone(clock.zone)))
            },
            if (state.mailOffline) {
                state.mailLoadedAt?.let { stringResource(R.string.new_offline_mail_at, format.format(it.atZone(clock.zone))) }
                    ?: stringResource(R.string.new_offline_mail_never)
            } else {
                null
            },
        )
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().testTag(NewTags.OFFLINE)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CloudOff, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(
                (listOf(stringResource(R.string.new_offline)) + details).joinToString(" "),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
            )
            TextButton(onClick = retry, enabled = !state.loading, modifier = Modifier.testTag(NewTags.RETRY)) {
                Text(stringResource(R.string.new_retry))
            }
        }
    }
}

@Composable
private fun ErrorBanner(
    state: NewUiState,
    retry: () -> Unit,
) {
    val text =
        listOfNotNull(
            stringResource(R.string.new_inbox_failed).takeIf { state.inboxFailed },
            stringResource(R.string.new_mail_failed).takeIf { state.mailFailed },
        ).joinToString(" ")
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().testTag(NewTags.ERROR)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
            TextButton(onClick = retry, enabled = !state.loading, modifier = Modifier.testTag(NewTags.RETRY)) {
                Text(stringResource(R.string.new_retry))
            }
        }
    }
}

@Composable
private fun Notice(
    notice: NewNotice,
    dismiss: () -> Unit,
) {
    val (text, color) =
        when (notice) {
            is NewNotice.TaskCreated ->
                stringResource(R.string.new_task_created, notice.title) to
                    MaterialTheme.colorScheme.secondaryContainer
            NewNotice.TaskOffline -> stringResource(R.string.new_task_offline) to MaterialTheme.colorScheme.errorContainer
            NewNotice.TaskFailed -> stringResource(R.string.new_task_failed) to MaterialTheme.colorScheme.errorContainer
            NewNotice.NextActionFailed -> stringResource(R.string.mail_next_action_failed) to MaterialTheme.colorScheme.errorContainer
            is NewNotice.NotArchived -> notArchived(notice.subject, notice.errorCode) to MaterialTheme.colorScheme.errorContainer
        }
    Surface(color = color, modifier = Modifier.fillMaxWidth().testTag(NewTags.NOTICE)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
            TextButton(onClick = dismiss) { Text(stringResource(R.string.new_dismiss)) }
        }
    }
}

/** Today's items by time, older ones by date. */
private fun formatDate(
    instant: Instant,
    today: LocalDate,
    clock: Clock,
): String {
    val local = instant.atZone(clock.zone)
    return if (local.toLocalDate() == today) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(local)
    } else {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).format(local)
    }
}
