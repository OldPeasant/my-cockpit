package ch.sonensei.cockpit.gtd.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.gtd.data.Creativity
import ch.sonensei.cockpit.gtd.data.Energy
import ch.sonensei.cockpit.gtd.data.GtdTask
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

@Composable
fun InboxScreen(
    modifier: Modifier = Modifier,
    viewModel: InboxViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    InboxContent(state, InboxCallbacks.of(viewModel), modifier)
}

/** What the Inbox screen can ask for; one function per user action, so the screen can be tested without a view model. */
data class InboxCallbacks(
    val startProcessing: () -> Unit = {},
    val stopProcessing: () -> Unit = {},
    val setEnergy: (Energy?) -> Unit = {},
    val setCreativity: (Creativity?) -> Unit = {},
    val setEstimate: (Int?) -> Unit = {},
    val toggleContext: (UUID) -> Unit = {},
    val choose: (InboxActionKind) -> Unit = {},
    val updatePrompt: ((InboxPrompt) -> InboxPrompt) -> Unit = {},
    val cancelPrompt: () -> Unit = {},
    val confirmPrompt: () -> Unit = {},
    val dismissMessage: () -> Unit = {},
) {
    companion object {
        fun of(vm: InboxViewModel) =
            InboxCallbacks(
                vm::startProcessing,
                vm::stopProcessing,
                vm::setEnergy,
                vm::setCreativity,
                vm::setEstimate,
                vm::toggleContext,
                vm::choose,
                vm::updatePrompt,
                vm::cancelPrompt,
                vm::confirmPrompt,
                vm::dismissMessage,
            )
    }
}

internal object InboxTags {
    const val OFFLINE = "inbox-offline"
    const val LIST = "inbox-list"
    const val PROCESS = "inbox-process"
    const val CURRENT = "inbox-current"
    const val LEFT = "inbox-left"
    const val MESSAGE = "inbox-message"
    const val PROMPT_TEXT = "inbox-prompt-text"
    const val PROMPT_CONFIRM = "inbox-prompt-confirm"

    fun action(kind: InboxActionKind) = "inbox-action-${kind.name.lowercase()}"
}

@Composable
fun InboxContent(
    state: InboxUiState,
    callbacks: InboxCallbacks,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        if (state.offline) OfflineBanner(state.refreshedAt)
        state.message?.let { MessageBanner(it, callbacks.dismissMessage) }
        if (state.processing) {
            ProcessingMode(state, callbacks)
        } else {
            InboxList(state, callbacks)
        }
    }
}

@Composable
private fun OfflineBanner(refreshedAt: Instant?) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().testTag(InboxTags.OFFLINE)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CloudOff, contentDescription = null)
            Spacer(Modifier.padding(start = 12.dp))
            val text =
                refreshedAt?.let {
                    val time = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).format(it.atZone(ZoneId.systemDefault()))
                    stringResource(R.string.inbox_offline, time)
                } ?: stringResource(R.string.inbox_offline_never)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun MessageBanner(
    message: InboxMessage,
    onDismiss: () -> Unit,
) {
    val text =
        when (message) {
            InboxMessage.OFFLINE -> R.string.inbox_message_offline
            InboxMessage.CONFLICT -> R.string.inbox_message_conflict
            InboxMessage.FAILED -> R.string.inbox_message_failed
        }
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().testTag(InboxTags.MESSAGE)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(text),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
            )
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.inbox_dismiss)) }
        }
    }
}

@Composable
private fun InboxList(
    state: InboxUiState,
    callbacks: InboxCallbacks,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    state.refreshedAt == null && state.tasks.isEmpty() && !state.offline -> stringResource(R.string.inbox_loading)
                    else -> pluralStringResource(R.plurals.inbox_count, state.tasks.size, state.tasks.size)
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = callbacks.startProcessing,
                enabled = state.actionsEnabled,
                modifier = Modifier.testTag(InboxTags.PROCESS),
            ) { Text(stringResource(R.string.inbox_process)) }
        }
        HorizontalDivider()
        if (state.tasks.isEmpty() && state.refreshedAt != null) {
            Text(
                stringResource(R.string.inbox_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
        LazyColumn(Modifier.fillMaxSize().testTag(InboxTags.LIST)) {
            items(state.tasks, key = { it.id }) { task ->
                ListItem(
                    headlineContent = { Text(task.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent =
                        task.notesMd?.takeIf { it.isNotBlank() }?.let { notes ->
                            { Text(notes, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ProcessingMode(
    state: InboxUiState,
    callbacks: InboxCallbacks,
) {
    val left = state.tasks.size
    val total = state.processed + left
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = callbacks.stopProcessing) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.inbox_stop))
            }
            Text(
                pluralStringResource(R.plurals.inbox_left, left, left),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f).testTag(InboxTags.LEFT),
            )
        }
        LinearProgressIndicator(
            progress = { if (total == 0) 1f else state.processed.toFloat() / total },
            modifier = Modifier.fillMaxWidth(),
        )
        val task = state.current
        if (task == null) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.inbox_all_done), style = MaterialTheme.typography.headlineSmall)
                OutlinedButton(onClick = callbacks.stopProcessing) { Text(stringResource(R.string.inbox_back_to_list)) }
            }
            return
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            CurrentItem(task)
            ClarificationPanel(state, callbacks)
            HorizontalDivider()
            val prompt = state.prompt
            if (prompt == null) {
                Actions(state.actionsEnabled, callbacks.choose)
            } else {
                PromptPanel(prompt, state, callbacks)
            }
        }
    }
}

@Composable
private fun CurrentItem(task: GtdTask) {
    Column(Modifier.testTag(InboxTags.CURRENT), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(task.title, style = MaterialTheme.typography.headlineMedium)
        task.notesMd?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ClarificationPanel(
    state: InboxUiState,
    callbacks: InboxCallbacks,
) {
    val enabled = state.actionsEnabled
    val c = state.clarification
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ChipRow(stringResource(R.string.inbox_energy)) {
            Energy.entries.forEach { energy ->
                Chip(stringResource(energy.label()), c.energy == energy, enabled) {
                    callbacks.setEnergy(
                        if (c.energy ==
                            energy
                        ) {
                            null
                        } else {
                            energy
                        },
                    )
                }
            }
        }
        ChipRow(stringResource(R.string.inbox_creativity)) {
            Creativity.entries.forEach { creativity ->
                Chip(stringResource(creativity.label()), c.creativity == creativity, enabled) {
                    callbacks.setCreativity(if (c.creativity == creativity) null else creativity)
                }
            }
        }
        ChipRow(stringResource(R.string.inbox_estimate)) {
            ESTIMATE_OPTIONS.forEach { minutes ->
                Chip(stringResource(R.string.inbox_estimate_minutes, minutes), c.timeEstimateMin == minutes, enabled) {
                    callbacks.setEstimate(if (c.timeEstimateMin == minutes) null else minutes)
                }
            }
        }
        if (state.contexts.isNotEmpty()) {
            ChipRow(stringResource(R.string.inbox_contexts)) {
                state.contexts.forEach { context ->
                    Chip(context.name, context.id in c.contextIds, enabled) { callbacks.toggleContext(context.id) }
                }
            }
        }
    }
}

@Composable
private fun ChipRow(
    label: String,
    chips: @Composable () -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
    }
}

@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(selected = selected, onClick = onClick, enabled = enabled, label = { Text(label) })
}

private val ACTION_GROUPS =
    listOf(
        R.string.inbox_group_act to listOf(InboxActionKind.DONE, InboxActionKind.NEXT, InboxActionKind.PROJECT),
        R.string.inbox_group_defer to
            listOf(InboxActionKind.WAITING, InboxActionKind.SCHEDULE, InboxActionKind.TICKLER, InboxActionKind.SOMEDAY),
        R.string.inbox_group_drop to listOf(InboxActionKind.TRASH),
    )

@Composable
private fun Actions(
    enabled: Boolean,
    onChoose: (InboxActionKind) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ACTION_GROUPS.forEach { (group, kinds) ->
            Column {
                Text(
                    stringResource(group),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    kinds.forEach { kind ->
                        val modifier = Modifier.testTag(InboxTags.action(kind))
                        val label = @Composable { Text(stringResource(kind.label())) }
                        when (kind) {
                            InboxActionKind.DONE, InboxActionKind.NEXT ->
                                Button(
                                    onClick = { onChoose(kind) },
                                    enabled = enabled,
                                    modifier = modifier,
                                ) { label() }
                            InboxActionKind.TRASH ->
                                OutlinedButton(
                                    onClick = { onChoose(kind) },
                                    enabled = enabled,
                                    modifier = modifier,
                                ) { label() }
                            else -> FilledTonalButton(onClick = { onChoose(kind) }, enabled = enabled, modifier = modifier) { label() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PromptPanel(
    prompt: InboxPrompt,
    state: InboxUiState,
    callbacks: InboxCallbacks,
) {
    val update = callbacks.updatePrompt
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (prompt.kind) {
            InboxActionKind.WAITING -> {
                Text(stringResource(R.string.inbox_prompt_waiting), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = prompt.text,
                    onValueChange = { text -> update { it.copy(text = text) } },
                    label = { Text(stringResource(R.string.inbox_prompt_waiting_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(InboxTags.PROMPT_TEXT),
                )
            }
            InboxActionKind.SCHEDULE, InboxActionKind.TICKLER -> DatePrompt(prompt, state.today, update)
            InboxActionKind.PROJECT -> {
                Text(stringResource(R.string.inbox_prompt_project), style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.projects.forEach { project ->
                        Chip(project.title, prompt.projectId == project.id, true) { update { it.copy(projectId = project.id) } }
                    }
                    Chip(
                        stringResource(R.string.inbox_prompt_new_project),
                        prompt.projectId == null,
                        true,
                    ) { update { it.copy(projectId = null) } }
                }
                if (prompt.projectId == null) {
                    OutlinedTextField(
                        value = prompt.text,
                        onValueChange = { text -> update { it.copy(text = text) } },
                        label = { Text(stringResource(R.string.inbox_prompt_new_project_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag(InboxTags.PROMPT_TEXT),
                    )
                }
            }
            else -> Unit
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = callbacks.confirmPrompt,
                enabled = state.actionsEnabled && prompt.isValid(state.today),
                modifier = Modifier.testTag(InboxTags.PROMPT_CONFIRM),
            ) { Text(stringResource(prompt.kind.confirmLabel())) }
            TextButton(onClick = callbacks.cancelPrompt) { Text(stringResource(R.string.inbox_cancel)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePrompt(
    prompt: InboxPrompt,
    today: LocalDate,
    update: ((InboxPrompt) -> InboxPrompt) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    val title = if (prompt.kind == InboxActionKind.SCHEDULE) R.string.inbox_prompt_schedule else R.string.inbox_prompt_tickler
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
    Text(
        DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).format(prompt.date),
        style = MaterialTheme.typography.headlineSmall,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            R.string.inbox_date_tomorrow to today.plusDays(1),
            R.string.inbox_date_week to today.plusWeeks(1),
            R.string.inbox_date_month to today.plusMonths(1),
        ).forEach { (label, date) ->
            Chip(stringResource(label), prompt.date == date, true) { update { it.copy(date = date) } }
        }
        Chip(stringResource(R.string.inbox_date_pick), false, true) { picking = true }
    }
    if (!prompt.isValid(today)) {
        Text(
            stringResource(R.string.inbox_prompt_tickler_future),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (picking) {
        // The picker works in UTC midnights.
        val pickerState =
            rememberDatePickerState(
                initialSelectedDateMillis =
                    prompt.date
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant()
                        .toEpochMilli(),
            )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        update { it.copy(date = date) }
                    }
                    picking = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.inbox_cancel)) } },
        ) { DatePicker(pickerState) }
    }
    Spacer(Modifier.height(4.dp))
}

private fun Energy.label() =
    when (this) {
        Energy.LOW -> R.string.inbox_energy_low
        Energy.MEDIUM -> R.string.inbox_energy_medium
        Energy.HIGH -> R.string.inbox_energy_high
    }

private fun Creativity.label() =
    when (this) {
        Creativity.ROUTINE -> R.string.inbox_creativity_routine
        Creativity.MIXED -> R.string.inbox_creativity_mixed
        Creativity.CREATIVE -> R.string.inbox_creativity_creative
    }

private fun InboxActionKind.label() =
    when (this) {
        InboxActionKind.DONE -> R.string.inbox_action_done
        InboxActionKind.NEXT -> R.string.inbox_action_next
        InboxActionKind.PROJECT -> R.string.inbox_action_project
        InboxActionKind.WAITING -> R.string.inbox_action_waiting
        InboxActionKind.SCHEDULE -> R.string.inbox_action_schedule
        InboxActionKind.TICKLER -> R.string.inbox_action_tickler
        InboxActionKind.SOMEDAY -> R.string.inbox_action_someday
        InboxActionKind.TRASH -> R.string.inbox_action_trash
    }

private fun InboxActionKind.confirmLabel() =
    when (this) {
        InboxActionKind.WAITING -> R.string.inbox_confirm_waiting
        InboxActionKind.SCHEDULE -> R.string.inbox_confirm_schedule
        InboxActionKind.TICKLER -> R.string.inbox_confirm_tickler
        else -> R.string.inbox_confirm_project
    }
