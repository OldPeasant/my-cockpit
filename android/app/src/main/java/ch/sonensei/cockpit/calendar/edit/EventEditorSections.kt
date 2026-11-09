package ch.sonensei.cockpit.calendar.edit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ch.sonensei.cockpit.R
import java.time.ZoneId

/** Minutes in an hour, a day and a week: a reminder is named in the largest unit that divides it. */
private const val HOUR = 60
private const val DAY = 1440
private const val WEEK = 10080

/** The time zones offered: the region ids (`Europe/Zurich`), without the legacy aliases (`SystemV/…`, `Etc/…`). */
internal val TIME_ZONES: List<String> by lazy {
    ZoneId
        .getAvailableZoneIds()
        .filter { it.contains('/') && !it.startsWith("SystemV/") && !it.startsWith("Etc/") }
        .plus("UTC")
        .sorted()
}

/** The zones matching [query] (any part of the id, spaces as `_`). */
internal fun zonesMatching(query: String): List<String> {
    val q = query.trim().replace(' ', '_')
    return if (q.isEmpty()) TIME_ZONES else TIME_ZONES.filter { it.contains(q, ignoreCase = true) }
}

/** `minutes` before the start, as people say it: "At start", "2 hours before", "1 week before". */
@Composable
internal fun reminderLabel(minutes: Int): String =
    when {
        minutes == 0 -> stringResource(R.string.calendar_edit_reminder_at_start)
        minutes % WEEK == 0 -> pluralStringResource(R.plurals.calendar_edit_reminder_weeks, minutes / WEEK, minutes / WEEK)
        minutes % DAY == 0 -> pluralStringResource(R.plurals.calendar_edit_reminder_days, minutes / DAY, minutes / DAY)
        minutes % HOUR == 0 -> pluralStringResource(R.plurals.calendar_edit_reminder_hours, minutes / HOUR, minutes / HOUR)
        else -> pluralStringResource(R.plurals.calendar_edit_reminder_minutes, minutes, minutes)
    }

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun Note(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
private fun ErrorNote(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = modifier)
}

/** The time zone of a timed event; opens a searchable list of zones. */
@Composable
internal fun TimeZoneChoice(
    form: EventForm,
    enabled: Boolean,
    update: ((EventForm) -> EventForm) -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val description = stringResource(R.string.calendar_edit_time_zone_change, form.zone.id)
    TextButton(
        onClick = { picking = true },
        enabled = enabled,
        modifier = Modifier.testTag(EventEditorTags.TIME_ZONE),
    ) { Text(stringResource(R.string.calendar_edit_time_zone, form.zone.id)) }
    if (picking) {
        var query by rememberSaveable { mutableStateOf("") }
        val zones = remember(query) { zonesMatching(query) }
        AlertDialog(
            onDismissRequest = { picking = false },
            modifier = Modifier.testTag(EventEditorTags.TIME_ZONE_DIALOG),
            title = { Text(stringResource(R.string.calendar_edit_time_zone_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Note(description)
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(R.string.calendar_edit_time_zone_search)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag(EventEditorTags.TIME_ZONE_SEARCH),
                    )
                    if (zones.isEmpty()) Note(stringResource(R.string.calendar_edit_time_zone_none))
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(zones, key = { it }) { zone ->
                            Text(
                                zone.replace('_', ' '),
                                fontWeight = if (zone == form.zone.id) FontWeight.Bold else null,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            update { it.copy(zone = ZoneId.of(zone)) }
                                            picking = false
                                        }.padding(vertical = 10.dp)
                                        .testTag(EventEditorTags.zone(zone)),
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.calendar_edit_cancel)) } },
        )
    }
}

/** The reminders of the event: removable rows, and presets or a custom number of minutes to add. */
@Composable
internal fun ReminderSection(
    form: EventForm,
    enabled: Boolean,
    update: ((EventForm) -> EventForm) -> Unit,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var custom by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle(stringResource(R.string.calendar_edit_reminders))
        if (form.reminders.isEmpty()) Note(stringResource(R.string.calendar_edit_no_reminders))
        form.reminders.forEach { minutes ->
            val label = reminderLabel(minutes)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag(EventEditorTags.reminder(minutes))) {
                Text(label, modifier = Modifier.weight(1f))
                if (enabled) {
                    IconButton(
                        onClick = { update { it.withoutReminder(minutes) } },
                        modifier = Modifier.testTag(EventEditorTags.reminderRemove(minutes)),
                    ) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.calendar_edit_reminder_remove, label)) }
                }
            }
        }
        if (enabled) {
            Box {
                OutlinedButton(onClick = { adding = true }, modifier = Modifier.testTag(EventEditorTags.REMINDER_ADD)) {
                    Text(stringResource(R.string.calendar_edit_add_reminder))
                }
                DropdownMenu(expanded = adding, onDismissRequest = { adding = false }) {
                    REMINDER_PRESETS.forEach { minutes ->
                        DropdownMenuItem(
                            text = { Text(reminderLabel(minutes)) },
                            enabled = minutes !in form.reminders,
                            onClick = {
                                update { it.withReminder(minutes) }
                                adding = false
                            },
                            modifier = Modifier.testTag(EventEditorTags.reminderPreset(minutes)),
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.calendar_edit_reminder_custom)) },
                        onClick = {
                            adding = false
                            custom = true
                        },
                        modifier = Modifier.testTag(EventEditorTags.REMINDER_CUSTOM),
                    )
                }
            }
        }
    }
    if (custom) {
        CustomReminderDialog(
            add = { minutes ->
                update { it.withReminder(minutes) }
                custom = false
            },
            dismiss = { custom = false },
        )
    }
}

/** The minutes of a custom reminder, `null` unless a whole number from 0 to [MAX_REMINDER_MINUTES]. */
internal fun customReminderMinutes(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 0..MAX_REMINDER_MINUTES }

@Composable
private fun CustomReminderDialog(
    add: (Int) -> Unit,
    dismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val minutes = customReminderMinutes(text)
    val invalid = text.isNotBlank() && minutes == null
    AlertDialog(
        onDismissRequest = dismiss,
        modifier = Modifier.testTag(EventEditorTags.REMINDER_CUSTOM_DIALOG),
        title = { Text(stringResource(R.string.calendar_edit_reminder_custom_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.calendar_edit_reminder_custom_label)) },
                singleLine = true,
                isError = invalid,
                supportingText =
                    if (invalid) {
                        { Text(stringResource(R.string.calendar_edit_reminder_custom_invalid, MAX_REMINDER_MINUTES)) }
                    } else {
                        null
                    },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.testTag(EventEditorTags.REMINDER_CUSTOM_MINUTES),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { minutes?.let(add) },
                enabled = minutes != null,
                modifier = Modifier.testTag(EventEditorTags.REMINDER_CUSTOM_OK),
            ) { Text(stringResource(R.string.calendar_edit_ok)) }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.calendar_edit_cancel)) } },
    )
}

@Composable
private fun statusLabel(status: AttendeeStatus): String =
    stringResource(
        when (status) {
            AttendeeStatus.ACCEPTED -> R.string.calendar_edit_guest_accepted
            AttendeeStatus.DECLINED -> R.string.calendar_edit_guest_declined
            AttendeeStatus.TENTATIVE -> R.string.calendar_edit_guest_tentative
            AttendeeStatus.DELEGATED -> R.string.calendar_edit_guest_delegated
            AttendeeStatus.NEEDS_ACTION -> R.string.calendar_edit_guest_pending
        },
    )

/**
 * The user's answer to the invitation (SPEC.md §6.2): Accept / Maybe / Decline, the current one selected. Sending one
 * answers at the provider, which tells the organizer.
 */
@Composable
internal fun ResponseSection(
    state: EventEditorUiState,
    respond: (AttendeeStatus) -> Unit,
) {
    val current = state.ownStatus
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag(EventEditorTags.RESPONSE)) {
        SectionTitle(stringResource(R.string.calendar_edit_respond_title))
        Note(
            if (current == null || current == AttendeeStatus.NEEDS_ACTION || current == AttendeeStatus.DELEGATED) {
                stringResource(R.string.calendar_edit_respond_none)
            } else {
                stringResource(R.string.calendar_edit_respond_current, statusLabel(current))
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            INVITATION_RESPONSES.forEach { answer ->
                val label = stringResource(responseLabel(answer))
                val modifier = Modifier.testTag(EventEditorTags.respond(answer)).semantics { selected = answer == current }
                if (answer == current) {
                    Button(onClick = { respond(answer) }, enabled = state.canRespond, modifier = modifier) { Text(label) }
                } else {
                    OutlinedButton(onClick = { respond(answer) }, enabled = state.canRespond, modifier = modifier) { Text(label) }
                }
            }
        }
    }
}

private fun responseLabel(answer: AttendeeStatus): Int =
    when (answer) {
        AttendeeStatus.ACCEPTED -> R.string.calendar_edit_respond_accept
        AttendeeStatus.DECLINED -> R.string.calendar_edit_respond_decline
        else -> R.string.calendar_edit_respond_maybe
    }

/**
 * The guests with their answers. In a local calendar they are added (typed, or picked from matching contacts) and
 * removed, and invitations can be sent for the saved event; providers own the guests of synced calendars.
 */
@Composable
internal fun GuestSection(
    state: EventEditorUiState,
    form: EventForm,
    callbacks: EventEditorCallbacks,
) {
    val editable = state.attendeesEditable
    if (form.attendees.isEmpty() && !editable) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag(EventEditorTags.GUESTS)) {
        SectionTitle(stringResource(R.string.calendar_edit_guests))
        if (form.attendees.isEmpty()) Note(stringResource(R.string.calendar_edit_no_guests))
        form.attendees.forEach { guest ->
            val shown = guest.name ?: guest.email
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag(EventEditorTags.guest(guest.email))) {
                Column(Modifier.weight(1f)) {
                    Text(shown)
                    if (guest.name != null) Note(guest.email)
                    Text(
                        statusLabel(guest.status),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag(EventEditorTags.guestStatus(guest.email)),
                    )
                }
                if (editable) {
                    IconButton(
                        onClick = { callbacks.removeGuest(guest.email) },
                        modifier = Modifier.testTag(EventEditorTags.guestRemove(guest.email)),
                    ) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.calendar_edit_guest_remove, shown)) }
                }
            }
        }
        if (editable) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.guestQuery,
                    onValueChange = callbacks.setGuestQuery,
                    label = { Text(stringResource(R.string.calendar_edit_guest_email)) },
                    singleLine = true,
                    isError = state.guestInvalid,
                    supportingText = if (state.guestInvalid) ({ Text(stringResource(R.string.calendar_edit_guest_invalid)) }) else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { callbacks.addGuest(null) }),
                    modifier = Modifier.weight(1f).testTag(EventEditorTags.GUEST_INPUT),
                )
                OutlinedButton(onClick = { callbacks.addGuest(null) }, modifier = Modifier.testTag(EventEditorTags.GUEST_ADD)) {
                    Text(stringResource(R.string.calendar_edit_add_guest))
                }
            }
            state.suggestions.forEach { suggestion ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { callbacks.addGuest(suggestion) }
                        .padding(vertical = 6.dp)
                        .testTag(EventEditorTags.suggestion(suggestion.email)),
                ) {
                    Text(suggestion.name ?: suggestion.email)
                    if (suggestion.name != null) Note(suggestion.email)
                }
            }
        } else {
            Note(stringResource(R.string.calendar_edit_guests_read_only))
        }
        if (state.invitable) {
            OutlinedButton(onClick = callbacks.invite, modifier = Modifier.testTag(EventEditorTags.INVITE)) {
                Text(stringResource(R.string.calendar_edit_invite))
            }
        }
        if (state.saveBeforeInviting) {
            ErrorNote(stringResource(R.string.calendar_edit_invite_save_first), Modifier.testTag(EventEditorTags.INVITE_SAVE_FIRST))
        }
        state.invitationSent?.let { sent ->
            Note(
                pluralStringResource(
                    if (sent.method == InvitationMethod.CANCEL) {
                        R.plurals.calendar_edit_invite_cancel_sent
                    } else {
                        R.plurals.calendar_edit_invite_request_sent
                    },
                    sent.count,
                    sent.count,
                ),
                Modifier.testTag(EventEditorTags.INVITE_SENT),
            )
        }
    }
}

/** Sends invitations or a cancellation to the guests, over a mail account the user picks. */
@Composable
internal fun InvitationDialog(
    prompt: InvitationUiState,
    callbacks: EventEditorCallbacks,
) {
    AlertDialog(
        onDismissRequest = { if (!prompt.sending) callbacks.dismissInvitation() },
        modifier = Modifier.testTag(EventEditorTags.INVITE_DIALOG),
        title = { Text(stringResource(R.string.calendar_edit_invite)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (prompt.afterSave) Text(stringResource(R.string.calendar_edit_invite_after_save))
                Text(prompt.title, style = MaterialTheme.typography.titleSmall)
                if (prompt.recipients.isNotEmpty()) {
                    Note(stringResource(R.string.calendar_edit_invite_to, prompt.recipients.joinToString(", ")))
                    InvitationMethod.entries.forEach { method ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = prompt.method == method,
                                        role = Role.RadioButton,
                                        onClick = { callbacks.updateInvitation { it.copy(method = method) } },
                                    ).testTag(EventEditorTags.inviteMethod(method)),
                        ) {
                            RadioButton(selected = prompt.method == method, onClick = null)
                            Text(
                                stringResource(
                                    if (method == InvitationMethod.REQUEST) {
                                        R.string.calendar_edit_invite_request
                                    } else {
                                        R.string.calendar_edit_invite_cancel
                                    },
                                ),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
                if (prompt.removed.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = prompt.tellRemoved,
                                    role = Role.Checkbox,
                                    onClick = { callbacks.updateInvitation { it.copy(tellRemoved = !it.tellRemoved) } },
                                ).testTag(EventEditorTags.INVITE_TELL_REMOVED),
                    ) {
                        Checkbox(checked = prompt.tellRemoved, onCheckedChange = null)
                        Text(
                            stringResource(R.string.calendar_edit_invite_tell_removed, prompt.removed.joinToString(", ")),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                AccountChoice(prompt, callbacks)
                if (prompt.failed) {
                    ErrorNote(
                        stringResource(R.string.calendar_edit_invite_failed),
                        Modifier.testTag(EventEditorTags.INVITE_FAILED),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = callbacks.sendInvitation,
                enabled = prompt.canSend,
                modifier = Modifier.testTag(EventEditorTags.INVITE_SEND),
            ) { Text(stringResource(R.string.calendar_edit_invite_send)) }
        },
        dismissButton = {
            TextButton(
                onClick = callbacks.dismissInvitation,
                enabled = !prompt.sending,
                modifier = Modifier.testTag(EventEditorTags.INVITE_SKIP),
            ) {
                Text(stringResource(if (prompt.afterSave) R.string.calendar_edit_invite_skip else R.string.calendar_edit_cancel))
            }
        },
    )
}

@Composable
private fun AccountChoice(
    prompt: InvitationUiState,
    callbacks: EventEditorCallbacks,
) {
    val accounts = prompt.accounts
    when {
        prompt.loadFailed ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                ErrorNote(stringResource(R.string.calendar_edit_invite_load_failed), Modifier.weight(1f))
                TextButton(onClick = callbacks.retryAccounts, modifier = Modifier.testTag(EventEditorTags.INVITE_RETRY)) {
                    Text(stringResource(R.string.calendar_edit_retry))
                }
            }
        accounts == null -> Note(stringResource(R.string.calendar_edit_invite_loading))
        accounts.isEmpty() ->
            ErrorNote(
                stringResource(R.string.calendar_edit_invite_no_accounts),
                Modifier.testTag(EventEditorTags.INVITE_NO_ACCOUNTS),
            )
        else -> {
            var expanded by rememberSaveable { mutableStateOf(false) }
            val selected = accounts.firstOrNull { it.id == prompt.accountId }
            Column {
                SectionTitle(stringResource(R.string.calendar_edit_invite_account))
                Box {
                    OutlinedButton(
                        onClick = { expanded = true },
                        enabled = !prompt.sending && accounts.size > 1,
                        modifier = Modifier.fillMaxWidth().testTag(EventEditorTags.INVITE_ACCOUNT),
                    ) { Text(selected?.let { "${it.name} (${it.email})" } ?: "—") }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        accounts.forEach { account ->
                            DropdownMenuItem(
                                text = { Text("${account.name} (${account.email})") },
                                onClick = {
                                    callbacks.updateInvitation { it.copy(accountId = account.id) }
                                    expanded = false
                                },
                                modifier = Modifier.testTag(EventEditorTags.inviteAccount(account.id.toString())),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun typeLabel(type: LinkedType): String =
    stringResource(
        when (type) {
            LinkedType.TASK -> R.string.calendar_edit_link_type_task
            LinkedType.PROJECT -> R.string.calendar_edit_link_type_project
            LinkedType.NOTE -> R.string.calendar_edit_link_type_note
            LinkedType.EVENT -> R.string.calendar_edit_link_type_event
            LinkedType.EMAIL -> R.string.calendar_edit_link_type_email
            LinkedType.FILE -> R.string.calendar_edit_link_type_file
            LinkedType.FOLDER -> R.string.calendar_edit_link_type_folder
            LinkedType.CONTACT -> R.string.calendar_edit_link_type_contact
            LinkedType.BOOKMARK -> R.string.calendar_edit_link_type_bookmark
            LinkedType.JOURNAL_ENTRY -> R.string.calendar_edit_link_type_journal
            LinkedType.HABIT -> R.string.calendar_edit_link_type_habit
            LinkedType.OTHER -> R.string.calendar_edit_link_type_other
        },
    )

/**
 * The items linked to the event (read-only: links are added and removed where the items are), with "Create task from
 * event" and "Create note for event" (SPEC.md §6.3).
 */
@Composable
internal fun LinksSection(
    links: LinksUiState,
    canCreate: Boolean,
    callbacks: EventEditorCallbacks,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag(EventEditorTags.LINKS)) {
        HorizontalDivider()
        SectionTitle(stringResource(R.string.calendar_edit_links))
        when {
            links.loading && links.items.isEmpty() -> Note(stringResource(R.string.calendar_edit_links_loading))
            links.failed ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ErrorNote(stringResource(R.string.calendar_edit_links_failed), Modifier.weight(1f))
                    TextButton(onClick = callbacks.reloadLinks, modifier = Modifier.testTag(EventEditorTags.LINKS_RETRY)) {
                        Text(stringResource(R.string.calendar_edit_retry))
                    }
                }
            links.items.isEmpty() -> Note(stringResource(R.string.calendar_edit_links_none))
        }
        links.items.forEach { item ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag(EventEditorTags.link(item.linkId.toString()))) {
                Text(item.title ?: stringResource(R.string.calendar_edit_link_unavailable))
                Note(listOfNotNull(typeLabel(item.type), item.subtitle?.takeIf { it.isNotBlank() }).joinToString(" · "))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { callbacks.createLinked(LinkedKind.TASK) },
                enabled = canCreate && links.creating == null,
                modifier = Modifier.weight(1f).testTag(EventEditorTags.CREATE_TASK),
            ) { Text(stringResource(R.string.calendar_edit_create_task)) }
            OutlinedButton(
                onClick = { callbacks.createLinked(LinkedKind.NOTE) },
                enabled = canCreate && links.creating == null,
                modifier = Modifier.weight(1f).testTag(EventEditorTags.CREATE_NOTE),
            ) { Text(stringResource(R.string.calendar_edit_create_note)) }
        }
        links.result?.let { LinkedResultText(it) }
    }
}

@Composable
private fun LinkedResultText(result: LinkedResult) {
    val task = result.kind == LinkedKind.TASK
    val modifier = Modifier.testTag(EventEditorTags.LINKS_RESULT)
    when (val r = result.result) {
        is LinkedCreateResult.Created ->
            Note(stringResource(if (task) R.string.calendar_edit_task_created else R.string.calendar_edit_note_created, r.title), modifier)
        is LinkedCreateResult.NotLinked ->
            ErrorNote(
                stringResource(if (task) R.string.calendar_edit_task_not_linked else R.string.calendar_edit_note_not_linked),
                modifier,
            )
        LinkedCreateResult.Failed ->
            ErrorNote(stringResource(if (task) R.string.calendar_edit_task_failed else R.string.calendar_edit_note_failed), modifier)
    }
}
