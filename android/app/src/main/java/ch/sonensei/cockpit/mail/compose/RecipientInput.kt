package ch.sonensei.cockpit.mail.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.sonensei.cockpit.R

/**
 * A recipient field split at its last separator: [tokens] are the recipients already finished (shown as chips), [pending]
 * is what is still being typed.
 */
data class RecipientEntry(
    val tokens: List<String>,
    val pending: String,
) {
    /** The field text again: finished recipients end with a separator, so they stay chips. */
    fun text(): String = (if (tokens.isEmpty()) "" else tokens.joinToString(", ") + ", ") + pending
}

/** Splits [field] into finished recipients and the text after the last `,`/`;` outside quotes. */
fun recipientEntry(field: String): RecipientEntry {
    var quoted = false
    var last = -1
    field.forEachIndexed { i, c ->
        when {
            c == '"' -> quoted = !quoted
            (c == ',' || c == ';') && !quoted -> last = i
        }
    }
    if (last < 0) return RecipientEntry(emptyList(), field)
    return RecipientEntry(splitRecipients(field.substring(0, last)), field.substring(last + 1).trimStart())
}

/** The field text for [recipients] as finished chips (empty for none). */
fun recipientField(recipients: List<Recipient>): String = if (recipients.isEmpty()) "" else formatRecipients(recipients) + ", "

/** The addresses of the finished recipients of [field], lowercased (to skip suggestions already chosen). */
fun chosenAddresses(field: String): Set<String> =
    recipientEntry(field)
        .tokens
        .flatMap { parseRecipients(it).recipients }
        .map { it.address.lowercase() }
        .toSet()

/** [field] with the text being typed replaced by [suggestion], finished as a chip (once: an address already chosen stays one chip). */
fun chooseSuggestion(
    field: String,
    suggestion: RecipientSuggestion,
): String {
    val entry = recipientEntry(field)
    if (suggestion.address.lowercase() in chosenAddresses(field)) return entry.copy(pending = "").text()
    return entry.copy(tokens = entry.tokens + formatRecipients(listOf(Recipient(suggestion.address, suggestion.name))), pending = "").text()
}

/** [field] without its finished recipient at [index]. */
fun removeRecipient(
    field: String,
    index: Int,
): String {
    val entry = recipientEntry(field)
    if (index !in entry.tokens.indices) return field
    return entry.copy(tokens = entry.tokens.filterIndexed { i, _ -> i != index }).text()
}

/** [field] with the text being typed finished as a chip (nothing typed: unchanged). */
fun finishRecipient(field: String): String {
    val entry = recipientEntry(field)
    if (entry.pending.isBlank()) return field
    return RecipientEntry(entry.tokens + splitRecipients(entry.pending), "").text()
}

/** The suggestions shown below a recipient field while typing in it. */
data class RecipientSuggestions(
    val field: RecipientField? = null,
    val items: List<RecipientSuggestion> = emptyList(),
)

/**
 * The recipients of a field as chips (an invalid one in the error color, removable) followed by the text being typed,
 * with the [suggestions] for it below: contacts with a person icon, recent correspondents with a history icon.
 */
@Composable
internal fun RecipientInput(
    value: String,
    field: RecipientField,
    enabled: Boolean,
    suggestions: List<RecipientSuggestion>,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onLeave: () -> Unit = {},
) {
    val entry = recipientEntry(value)
    val focused = remember { mutableStateOf(false) }
    Column(modifier) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            val removeLabel = stringResource(R.string.mail_compose_remove_recipient)
            entry.tokens.forEachIndexed { index, token ->
                val recipient = parseRecipients(token).recipients.singleOrNull()
                val label = recipient?.let { it.name ?: it.address } ?: token
                RecipientChip(
                    label = label,
                    removeDescription = "$removeLabel ${recipient?.address ?: token}",
                    removeActionLabel = removeLabel,
                    invalid = recipient == null,
                    enabled = enabled,
                    onRemove = { onChange(removeRecipient(value, index)) },
                    modifier = Modifier.align(Alignment.CenterVertically).testTag(MailComposeTags.recipient(field, index)),
                )
            }
            BasicTextField(
                value = entry.pending,
                onValueChange = { onChange(entry.copy(pending = it).text()) },
                enabled = enabled,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onChange(finishRecipient(value)) }),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier =
                    Modifier
                        .weight(1f)
                        .widthIn(min = 120.dp)
                        .align(Alignment.CenterVertically)
                        .padding(vertical = 12.dp)
                        .onFocusChanged {
                            // Leaving the field hides its suggestions (a first unfocused report is no leave).
                            if (focused.value && !it.isFocused) onLeave()
                            focused.value = it.isFocused
                        }.testTag(MailComposeTags.field(field)),
            )
        }
        if (suggestions.isNotEmpty() && enabled) {
            Surface(
                tonalElevation = 3.dp,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag(MailComposeTags.suggestions(field)),
            ) {
                Column {
                    suggestions.forEach { s -> SuggestionRow(s) { onChange(chooseSuggestion(value, s)) } }
                }
            }
        }
    }
}

/**
 * One finished recipient: its [label] and a remove button with a 48 dp touch target. The chip body is no action (a stray
 * tap keeps the recipient); TalkBack reads the label and offers a "Remove" custom action besides the button.
 */
@Composable
private fun RecipientChip(
    label: String,
    removeDescription: String,
    removeActionLabel: String,
    invalid: Boolean,
    enabled: Boolean,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val container = if (invalid) colors.errorContainer else colors.surface
    val content = if (invalid) colors.onErrorContainer else colors.onSurfaceVariant
    Surface(
        shape = MaterialTheme.shapes.small,
        color = container,
        contentColor = content,
        border = if (invalid) null else BorderStroke(1.dp, colors.outlineVariant),
        modifier =
            modifier
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .semantics(mergeDescendants = true) {
                    if (!enabled) {
                        disabled()
                    } else {
                        customActions =
                            listOf(
                                CustomAccessibilityAction(removeActionLabel) {
                                    onRemove()
                                    true
                                },
                            )
                    }
                },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp).widthIn(max = 240.dp),
            )
            IconButton(onClick = onRemove, enabled = enabled, modifier = Modifier.size(MIN_TOUCH_TARGET)) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = removeDescription,
                    modifier = Modifier.size(InputChipDefaults.IconSize),
                )
            }
        }
    }
}

private const val DISABLED_ALPHA = 0.38f

// The Material minimum touch target: the close icon is the only pointer way to remove a recipient.
private val MIN_TOUCH_TARGET = 48.dp

@Composable
private fun SuggestionRow(
    suggestion: RecipientSuggestion,
    choose: () -> Unit,
) {
    val kind =
        stringResource(
            if (suggestion.contactId != null) {
                R.string.mail_compose_suggestion_contact
            } else {
                R.string.mail_compose_suggestion_recent
            },
        )
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = choose)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$kind ${suggestion.name ?: ""} ${suggestion.address}".trim() }
            .testTag(MailComposeTags.suggestion(suggestion.address)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (suggestion.contactId != null) Icons.Outlined.Person else Icons.Outlined.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            suggestion.name?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Text(
                suggestion.address,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
