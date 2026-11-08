package ch.sonensei.cockpit.gtd.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.gtd.data.ProjectStatus
import ch.sonensei.cockpit.gtd.lists.ActionOutcome
import ch.sonensei.cockpit.notes.MarkdownView
import coil3.ImageLoader
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

object ProjectFormTags {
    const val NEW = "project-new"
    const val FORM = "project-form"
    const val CANCEL = "project-form-cancel"
    const val SAVE = "project-form-save"
    const val TITLE = "project-form-title"
    const val TITLE_ERROR = "project-form-title-error"
    const val CONFLICT = "project-form-conflict"
    const val STATUS = "project-form-status"
    const val AREA = "project-form-area"
    const val DUE = "project-form-due"
    const val DUE_CLEAR = "project-form-due-clear"
    const val REVIEW = "project-form-review"
    const val REVIEW_CLEAR = "project-form-review-clear"
    const val DESCRIPTION = "project-form-description"
    const val DESCRIPTION_EDIT = "project-form-description-edit"
    const val DESCRIPTION_PREVIEW = "project-form-description-preview"
    const val PREVIEW = "project-form-preview"
    const val NO_AREA = "project-form-area-none"
    const val MENU = "project-menu"
    const val MENU_EDIT = "project-menu-edit"
    const val MENU_COMPLETE = "project-menu-complete"
    const val MENU_DROP = "project-menu-drop"
    const val MENU_REACTIVATE = "project-menu-reactivate"
    const val MENU_DELETE = "project-menu-delete"
    const val CONFIRM = "project-confirm"
    const val CONFIRM_OK = "project-confirm-ok"
    const val CONFIRM_CANCEL = "project-confirm-cancel"
    const val DESCRIPTION_VIEW = "project-description"

    fun status(status: ProjectStatus) = "project-form-status-${status.name}"

    fun area(id: java.util.UUID) = "project-form-area-$id"
}

/** The form for a new project or the edit of one: title, status (editing only), area, due and review date, description. */
@Composable
internal fun ProjectFormScreen(
    state: ProjectsUiState,
    form: ProjectFormState,
    callbacks: ProjectsCallbacks,
    imageLoader: ImageLoader?,
) {
    val draft = form.draft
    val enabled = !form.saving && !state.offline
    Column(Modifier.fillMaxSize().testTag(ProjectFormTags.FORM)) {
        Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = callbacks.cancelForm, modifier = Modifier.testTag(ProjectFormTags.CANCEL)) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.projects_form_cancel))
            }
            Text(
                stringResource(if (form.isNew) R.string.projects_new else R.string.projects_edit_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            TextButton(onClick = { callbacks.saveForm() }, enabled = enabled, modifier = Modifier.testTag(ProjectFormTags.SAVE)) {
                Text(stringResource(if (form.isNew) R.string.projects_form_create else R.string.projects_form_save))
            }
        }
        HorizontalDivider()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (form.conflict) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().testTag(ProjectFormTags.CONFLICT),
                ) {
                    Text(
                        stringResource(R.string.projects_form_conflict),
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            OutlinedTextField(
                value = draft.title,
                onValueChange = { value -> callbacks.editForm { it.copy(title = value.take(MAX_PROJECT_TITLE)) } },
                label = { Text(stringResource(R.string.projects_form_title)) },
                placeholder = { Text(stringResource(R.string.projects_form_title_placeholder)) },
                isError = form.titleMissing,
                supportingText =
                    if (form.titleMissing) {
                        {
                            Text(
                                stringResource(R.string.projects_form_title_required),
                                modifier = Modifier.testTag(ProjectFormTags.TITLE_ERROR),
                            )
                        }
                    } else {
                        null
                    },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag(ProjectFormTags.TITLE),
            )
            if (!form.isNew) {
                Choice(
                    label = stringResource(R.string.projects_form_status),
                    value = statusName(draft.status),
                    enabled = enabled,
                    tag = ProjectFormTags.STATUS,
                ) { dismiss ->
                    ProjectStatus.entries.forEach { status ->
                        DropdownMenuItem(
                            text = { Text(statusName(status)) },
                            onClick = {
                                callbacks.editForm { it.copy(status = status) }
                                dismiss()
                            },
                            modifier = Modifier.testTag(ProjectFormTags.status(status)),
                        )
                    }
                }
            }
            Choice(
                label = stringResource(R.string.projects_form_area),
                value = state.areas.firstOrNull { it.id == draft.areaId }?.title ?: stringResource(R.string.projects_no_area),
                enabled = enabled,
                tag = ProjectFormTags.AREA,
            ) { dismiss ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.projects_no_area)) },
                    onClick = {
                        callbacks.editForm { it.copy(areaId = null) }
                        dismiss()
                    },
                    modifier = Modifier.testTag(ProjectFormTags.NO_AREA),
                )
                state.areas.forEach { area ->
                    DropdownMenuItem(
                        text = { Text(area.title) },
                        onClick = {
                            callbacks.editForm { it.copy(areaId = area.id) }
                            dismiss()
                        },
                        modifier = Modifier.testTag(ProjectFormTags.area(area.id)),
                    )
                }
            }
            DateField(
                label = stringResource(R.string.projects_form_due),
                date = draft.dueDate,
                enabled = enabled,
                tag = ProjectFormTags.DUE,
                clearTag = ProjectFormTags.DUE_CLEAR,
            ) { date -> callbacks.editForm { it.copy(dueDate = date) } }
            DateField(
                label = stringResource(R.string.projects_form_review),
                date = draft.reviewDate,
                enabled = enabled,
                tag = ProjectFormTags.REVIEW,
                clearTag = ProjectFormTags.REVIEW_CLEAR,
            ) { date -> callbacks.editForm { it.copy(reviewDate = date) } }
            Description(form, enabled, callbacks, imageLoader)
        }
    }
}

/** A labelled value that opens a menu of [items] (which get a function closing the menu). */
@Composable
private fun Choice(
    label: String,
    value: String,
    enabled: Boolean,
    tag: String,
    items: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.testTag(tag)) { Text(value) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) { items { open = false } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(
    label: String,
    date: LocalDate?,
    enabled: Boolean,
    tag: String,
    clearTag: String,
    onChange: (LocalDate?) -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { picking = true }, enabled = enabled, modifier = Modifier.testTag(tag)) {
                Text(
                    date?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
                        ?: stringResource(R.string.projects_form_date_pick),
                )
            }
            if (date != null) {
                TextButton(onClick = { onChange(null) }, enabled = enabled, modifier = Modifier.testTag(clearTag)) {
                    Text(stringResource(R.string.projects_form_date_clear))
                }
            }
        }
    }
    if (picking) {
        // The picker works in UTC midnights.
        val initial = (date ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    picking = false
                }) { Text(stringResource(R.string.projects_form_ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.projects_form_cancel)) } },
        ) { DatePicker(pickerState) }
    }
}

/** The description in Markdown: edited as text, or previewed rendered like the detail shows it. */
@Composable
private fun Description(
    form: ProjectFormState,
    enabled: Boolean,
    callbacks: ProjectsCallbacks,
    imageLoader: ImageLoader?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.projects_form_description),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = !form.preview,
                onClick = { if (form.preview) callbacks.togglePreview() },
                label = { Text(stringResource(R.string.projects_form_description_edit)) },
                modifier = Modifier.testTag(ProjectFormTags.DESCRIPTION_EDIT),
            )
            FilterChip(
                selected = form.preview,
                onClick = { if (!form.preview) callbacks.togglePreview() },
                label = { Text(stringResource(R.string.projects_form_description_preview)) },
                modifier = Modifier.testTag(ProjectFormTags.DESCRIPTION_PREVIEW),
            )
        }
        if (form.preview) {
            val text = form.draft.description
            Box(Modifier.fillMaxWidth().testTag(ProjectFormTags.PREVIEW)) {
                if (text.isBlank()) {
                    Text(
                        stringResource(R.string.projects_form_preview_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    MarkdownView(text, imageLoader)
                }
            }
        } else {
            OutlinedTextField(
                value = form.draft.description,
                onValueChange = { value -> callbacks.editForm { it.copy(description = value) } },
                placeholder = { Text(stringResource(R.string.projects_form_description_hint)) },
                minLines = DESCRIPTION_MIN_LINES,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag(ProjectFormTags.DESCRIPTION),
            )
        }
    }
}

/** The detail's menu: edit, complete, drop or reactivate, delete. */
@Composable
internal fun ProjectMenu(
    state: ProjectsUiState,
    detail: ProjectDetailState,
    callbacks: ProjectsCallbacks,
) {
    var open by remember { mutableStateOf(false) }
    val status = detail.project.status
    val enabled = !state.readOnly && detail.project.id !in state.busy
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag(ProjectFormTags.MENU)) {
            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.projects_menu))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            @Composable
            fun item(
                label: Int,
                tag: String,
                action: () -> Unit,
            ) = DropdownMenuItem(
                text = { Text(stringResource(label)) },
                onClick = {
                    open = false
                    action()
                },
                enabled = enabled,
                modifier = Modifier.testTag(tag),
            )
            item(R.string.projects_edit, ProjectFormTags.MENU_EDIT, callbacks.editProject)
            if (status != ProjectStatus.COMPLETED) {
                item(R.string.projects_complete, ProjectFormTags.MENU_COMPLETE) { callbacks.setStatus(ProjectStatus.COMPLETED) }
            }
            if (status != ProjectStatus.DROPPED) {
                item(R.string.projects_drop, ProjectFormTags.MENU_DROP) { callbacks.setStatus(ProjectStatus.DROPPED) }
            }
            if (status != ProjectStatus.ACTIVE) {
                item(R.string.projects_reactivate, ProjectFormTags.MENU_REACTIVATE) { callbacks.setStatus(ProjectStatus.ACTIVE) }
            }
            item(R.string.projects_delete, ProjectFormTags.MENU_DELETE, callbacks.requestDelete)
        }
    }
}

/** Asks before finishing a project with open tasks, or before deleting one. */
@Composable
internal fun ProjectConfirmDialog(
    title: String,
    confirm: ProjectConfirm,
    callbacks: ProjectsCallbacks,
) {
    val (heading, button) =
        when (confirm) {
            is ProjectConfirm.Finish ->
                if (confirm.status == ProjectStatus.COMPLETED) {
                    R.string.projects_confirm_complete_title to R.string.projects_confirm_complete
                } else {
                    R.string.projects_confirm_drop_title to R.string.projects_confirm_drop
                }
            is ProjectConfirm.Delete -> R.string.projects_confirm_delete_title to R.string.projects_confirm_delete
        }
    AlertDialog(
        onDismissRequest = callbacks.cancelConfirm,
        modifier = Modifier.testTag(ProjectFormTags.CONFIRM),
        title = { Text(stringResource(heading, title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val (open, plural) =
                    when (confirm) {
                        is ProjectConfirm.Finish -> confirm.openTasks to R.plurals.projects_confirm_open_tasks
                        is ProjectConfirm.Delete -> confirm.openTasks to R.plurals.projects_confirm_delete_tasks
                    }
                if (confirm is ProjectConfirm.Delete) Text(stringResource(R.string.projects_confirm_delete_text))
                if (open != null && open > 0) Text(pluralStringResource(plural, open, open))
            }
        },
        confirmButton = {
            TextButton(onClick = callbacks.confirm, modifier = Modifier.testTag(ProjectFormTags.CONFIRM_OK)) {
                Text(stringResource(button))
            }
        },
        dismissButton = {
            TextButton(onClick = callbacks.cancelConfirm, modifier = Modifier.testTag(ProjectFormTags.CONFIRM_CANCEL)) {
                Text(stringResource(R.string.projects_form_cancel))
            }
        },
    )
}

/** Shows how a project change went: done, offline, failed, or changed elsewhere (reloaded). */
@Composable
internal fun ProjectMessageEffect(
    message: ProjectMessage?,
    host: SnackbarHostState,
    onDismiss: (ProjectMessage) -> Unit,
) {
    val resources = LocalResources.current
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        val text =
            when (message.outcome) {
                ActionOutcome.DONE ->
                    resources.getString(
                        when (message.kind) {
                            ProjectMessage.Kind.CREATED -> R.string.projects_created
                            ProjectMessage.Kind.SAVED -> R.string.projects_saved
                            ProjectMessage.Kind.COMPLETED -> R.string.projects_completed
                            ProjectMessage.Kind.DROPPED -> R.string.projects_dropped
                            ProjectMessage.Kind.REACTIVATED -> R.string.projects_reactivated
                            ProjectMessage.Kind.DELETED -> R.string.projects_deleted
                        },
                        message.title,
                    )
                ActionOutcome.OFFLINE -> resources.getString(R.string.tasks_message_offline)
                ActionOutcome.CONFLICT -> resources.getString(R.string.projects_message_conflict, message.title)
                ActionOutcome.FAILED -> resources.getString(R.string.tasks_message_failed)
            }
        host.showSnackbar(text, duration = SnackbarDuration.Short)
        onDismiss(message)
    }
}

/** The name of every status, also active (for the form's choice). */
@Composable
private fun statusName(status: ProjectStatus): String =
    stringResource(
        when (status) {
            ProjectStatus.ACTIVE -> R.string.projects_status_active
            ProjectStatus.ON_HOLD -> R.string.projects_status_on_hold
            ProjectStatus.SOMEDAY -> R.string.projects_status_someday
            ProjectStatus.COMPLETED -> R.string.projects_status_completed
            ProjectStatus.DROPPED -> R.string.projects_status_dropped
        },
    )

private const val DESCRIPTION_MIN_LINES = 6
