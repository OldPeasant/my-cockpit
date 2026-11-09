package ch.sonensei.cockpit.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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

/**
 * The note's [tags] as removable chips, with an input that adds a tag on Enter, on a comma or when it loses focus,
 * and the tags in use not on the note yet ([suggestions]) as chips to add with a tap, filtered by what is typed.
 * [onAdd] returns whether the tag was added; text that was not added stays in the input. Only a chip's close icon
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

    // A tag that is not added (a duplicate, too long, one too many) stays in the input, so nothing typed is lost.
    fun commit() {
        if (text.isBlank() || onAdd(text)) text = ""
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            tags.forEach { tag ->
                val removeLabel = stringResource(R.string.notes_tag_remove, tag)
                // Only the close icon removes the tag: a tap on the chip itself does nothing.
                InputChip(
                    selected = false,
                    onClick = {},
                    enabled = enabled,
                    label = { Text("#$tag") },
                    trailingIcon = {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = removeLabel,
                            modifier =
                                Modifier
                                    .size(16.dp)
                                    .clickable(enabled = enabled, role = Role.Button) { onRemove(tag) }
                                    .testTag(NoteTagsTags.REMOVE),
                        )
                    },
                    modifier = Modifier.testTag(NoteTagsTags.CHIP),
                )
            }
            TextField(
                value = text,
                onValueChange = { value ->
                    val (done, rest) = splitTagInput(value)
                    val refused = done.filter { it.isNotBlank() && !onAdd(it) }.map { it.trim() }
                    text = if (refused.isEmpty()) rest else (refused + rest).filter { it.isNotEmpty() }.joinToString(", ")
                },
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
