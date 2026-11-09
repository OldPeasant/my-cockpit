package ch.sonensei.cockpit.notes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import ch.sonensei.cockpit.R

internal object NoteTagsTags {
    const val CHIP = "note-tag-chip"
    const val REMOVE = "note-tag-remove"
    const val INPUT = "note-tag-input"
    const val SUGGESTION = "note-tag-suggestion"
}

/** How many suggestions show below the tag input at most. */
private const val MAX_SUGGESTIONS = 8

/** The tags typed so far (`a, b, c`) and the text after the last comma, still being typed. */
internal fun splitTagInput(text: String): Pair<List<String>, String> {
    val parts = text.split(',', '\n')
    return parts.dropLast(1) to parts.last()
}

/**
 * The tag input after adding: the [refused] parts as typed, each followed by its comma, then the part still being
 * typed ([rest], none after Enter or losing focus).
 */
internal fun keptTagInput(
    refused: List<String>,
    rest: String?,
): String = if (rest == null) refused.joinToString(",") else (refused + rest).joinToString(",")

/** The [known] tags (in their order) that start with or contain [typed], those starting with it first. */
internal fun matchingTags(
    known: List<String>,
    typed: String,
): List<String> {
    val q = NoteEditorViewModel.normalizeTag(typed)
    if (q.isEmpty()) return known.take(MAX_SUGGESTIONS)
    val (starts, rest) = known.filter { it.contains(q, ignoreCase = true) }.partition { it.startsWith(q, ignoreCase = true) }
    return (starts + rest).take(MAX_SUGGESTIONS)
}

/** Material's alpha for the content of a disabled chip. */
private const val DISABLED_CONTENT_ALPHA = 0.38f

/** Material's alpha for the outline of a disabled chip. */
private const val DISABLED_BORDER_ALPHA = 0.12f

/**
 * A tag on the note, shaped like an input chip. The chip itself has no action (it is not clickable and is not announced
 * as a control); only its remove button, labelled for screen readers, removes the tag.
 */
@Composable
private fun TagChip(
    tag: String,
    enabled: Boolean,
    onRemove: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.small,
        color = Color.Transparent,
        contentColor = if (enabled) colors.onSurfaceVariant else colors.onSurface.copy(alpha = DISABLED_CONTENT_ALPHA),
        border = BorderStroke(1.dp, if (enabled) colors.outline else colors.onSurface.copy(alpha = DISABLED_BORDER_ALPHA)),
        modifier = Modifier.testTag(NoteTagsTags.CHIP),
    ) {
        Row(Modifier.height(32.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("#$tag", style = MaterialTheme.typography.labelLarge)
            IconButton(onClick = onRemove, enabled = enabled, modifier = Modifier.size(32.dp).testTag(NoteTagsTags.REMOVE)) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.notes_tag_remove, tag), Modifier.size(16.dp))
            }
        }
    }
}

/**
 * The note's [tags] as chips, with an input that adds a tag on Enter, on a comma or when it loses focus,
 * and the tags in use not on the note yet ([suggestions]) as chips to add with a tap, filtered by what is typed.
 * [onAdd] returns whether the tag was added; text that was not added stays in the input. Only a chip's remove button
 * removes its tag.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteTagsEditor(
    tags: List<String>,
    suggestions: List<String>,
    enabled: Boolean,
    onAdd: (String) -> Boolean,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var focused by rememberSaveable { mutableStateOf(false) }

    // Adds the tags typed in [value] (all of them when [final], else those before the last comma). A tag that is not
    // added (a duplicate, too long, one too many) stays in the input as typed, still separated by its comma, so nothing
    // typed is lost and the next tag is never joined to it. No tag passed to onAdd contains a comma.
    fun take(
        value: String,
        final: Boolean,
    ) {
        val (done, rest) = splitTagInput(value)
        val candidates = if (final) done + rest else done
        val refused = candidates.filter { it.isNotBlank() && !onAdd(it) }
        text = keptTagInput(refused, if (final) null else rest)
    }

    fun commit() = take(text, final = true)

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            tags.forEach { tag -> TagChip(tag, enabled, onRemove = { onRemove(tag) }) }
            TextField(
                value = text,
                onValueChange = { take(it, final = false) },
                enabled = enabled,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.notes_tag_add)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                textStyle = MaterialTheme.typography.labelLarge,
                colors =
                    TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                    ),
                modifier =
                    Modifier
                        .widthIn(min = 120.dp, max = 240.dp)
                        .onFocusChanged {
                            if (focused && !it.isFocused) commit()
                            focused = it.isFocused
                        }.testTag(NoteTagsTags.INPUT),
            )
        }
        val shown = if (enabled && (focused || text.isNotBlank())) matchingTags(suggestions, text) else emptyList()
        if (shown.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                shown.forEach { tag ->
                    SuggestionChip(
                        onClick = {
                            onAdd(tag)
                            text = ""
                        },
                        label = { Text("#$tag") },
                        modifier = Modifier.testTag(NoteTagsTags.SUGGESTION),
                    )
                }
            }
        }
    }
}
