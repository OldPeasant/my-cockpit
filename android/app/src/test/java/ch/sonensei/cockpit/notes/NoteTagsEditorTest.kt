package ch.sonensei.cockpit.notes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

/** The tag chips of the note editor: removing a chip, typing tags, picking a suggestion. */
@RunWith(AndroidJUnit4::class)
class NoteTagsEditorTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val added = mutableListOf<String>()
    private val removed = mutableListOf<String>()
    private var tags by mutableStateOf(listOf<String>())
    private var enabled by mutableStateOf(true)
    private lateinit var focus: FocusManager
    private val note =
        Note(UUID.fromString("00000000-0000-7000-8000-000000000001"), null, "Trip", "Body", false, listOf("travel"), Instant.EPOCH, 1)

    private fun show(
        initial: List<String>,
        suggestions: List<String> = emptyList(),
    ) {
        tags = initial
        composeRule.setContent {
            focus = LocalFocusManager.current
            CockpitTheme {
                NoteTagsEditor(
                    tags = tags,
                    suggestions = suggestions.filter { it !in tags },
                    enabled = enabled,
                    onAdd = { tag ->
                        added += tag
                        tags = tags + tag.trim()
                        true
                    },
                    onRemove = { tag ->
                        removed += tag
                        tags = tags - tag
                    },
                )
            }
        }
    }

    private fun chips() = composeRule.onAllNodesWithTag(NoteTagsTags.CHIP).fetchSemanticsNodes().size

    private fun suggestions() = composeRule.onAllNodesWithTag(NoteTagsTags.SUGGESTION).fetchSemanticsNodes().size

    /** The text typed in the tag input (its placeholder does not count). */
    private fun assertInput(text: String) {
        composeRule
            .onNodeWithTag(NoteTagsTags.INPUT)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text)))
    }

    @Test
    fun `each tag shows as a chip with a hash, and tapping its remove icon removes it`() {
        show(listOf("home", "work"))

        assertEquals(2, chips())
        composeRule.onNodeWithText("#home").assertExists()
        composeRule.onNodeWithText("#work").assertExists()
        composeRule.onNodeWithContentDescription("Remove tag work").performClick()

        assertEquals(listOf("work"), removed)
        assertEquals(1, chips())
        composeRule.onNodeWithText("#work").assertDoesNotExist()
    }

    @Test
    fun `typing a comma adds the tag typed before it and keeps what follows`() {
        show(emptyList())

        composeRule.onNodeWithTag(NoteTagsTags.INPUT).performTextInput("home,")
        assertEquals(listOf("home"), added)
        assertInput("")

        composeRule.onNodeWithTag(NoteTagsTags.INPUT).performTextInput("a, b,wor")
        assertEquals(listOf("home", "a", " b"), added)
        assertInput("wor")
        assertEquals(3, chips())
    }

    @Test
    fun `Enter adds the typed tag and clears the input, a blank input adds nothing`() {
        show(emptyList())

        composeRule.onNodeWithTag(NoteTagsTags.INPUT).performTextInput("errands")
        assertEquals(emptyList<String>(), added)
        composeRule.onNodeWithTag(NoteTagsTags.INPUT).performImeAction()
        assertEquals(listOf("errands"), added)
        assertInput("")

        composeRule.onNodeWithTag(NoteTagsTags.INPUT).performTextInput("   ")
        composeRule.onNodeWithTag(NoteTagsTags.INPUT).performImeAction()
        assertEquals(listOf("errands"), added)
    }

    @Test
    fun `leaving the input adds the tag typed in it`() {
        show(emptyList())

        composeRule.onNodeWithTag(NoteTagsTags.INPUT).performTextInput("travel")
        composeRule.runOnIdle { focus.clearFocus() }

        composeRule.runOnIdle { assertEquals(listOf("travel"), added) }
        assertInput("")
    }

    @Test
    fun `suggestions show while the input is used, filtered by what is typed, and a tap adds one`() {
        show(listOf("home"), suggestions = listOf("Work", "travel", "home", "network"))
        assertEquals("no suggestions before the input is used", 0, suggestions())

        composeRule.onNodeWithTag(NoteTagsTags.INPUT).performTextInput("wor")
        assertEquals(2, suggestions())
        composeRule.onNodeWithText("#travel").assertDoesNotExist()

        composeRule.onNodeWithText("#network").performClick()

        assertEquals(listOf("network"), added)
        assertEquals(listOf("home", "network"), tags)
        assertInput("")
    }

    @Test
    fun `a tag on the note is not suggested`() {
        show(listOf("home"), suggestions = listOf("Work", "home"))

        composeRule.onNodeWithTag(NoteTagsTags.INPUT).performTextInput("o")

        assertEquals(1, suggestions())
        composeRule.onNodeWithText("#Work").assertExists()
    }

    @Test
    fun `nothing can be removed or suggested while the note cannot be edited`() {
        enabled = false
        show(listOf("home"), suggestions = listOf("Work"))

        composeRule.onNodeWithTag(NoteTagsTags.CHIP).performClick()

        assertEquals(emptyList<String>(), removed)
        assertEquals(0, suggestions())
        assertEquals(1, chips())
    }

    @Test
    fun `the editor screen shows the tags and passes add and remove to the view model callbacks`() {
        val calls = mutableListOf<String>()
        composeRule.setContent {
            CockpitTheme {
                NoteEditorContent(
                    NoteEditorUiState(note = note, tags = listOf("travel"), knownTags = listOf("home"), editing = true),
                    NoteEditorCallbacks(
                        addTag = {
                            calls += "add $it"
                            true
                        },
                        removeTag = { calls += "remove $it" },
                    ),
                    imageLoader = null,
                )
            }
        }

        composeRule.onNodeWithText("#travel").assertExists()
        composeRule.onNodeWithTag(NoteTagsTags.INPUT).performTextInput("ho")
        composeRule.onNodeWithTag(NoteTagsTags.SUGGESTION).performClick()
        composeRule.onNodeWithTag(NoteTagsTags.CHIP).performClick()

        assertEquals(listOf("add home", "remove travel"), calls)
    }

    @Test
    fun `the rendered note shows its tags too`() {
        composeRule.setContent {
            CockpitTheme {
                NoteEditorContent(
                    NoteEditorUiState(note = note, title = "Trip", body = "Body", tags = listOf("travel")),
                    NoteEditorCallbacks(),
                    imageLoader = null,
                )
            }
        }

        composeRule.onNodeWithText("#travel").assertExists()
    }

    @Test
    fun `splitTagInput splits at commas and new lines, keeping the part still typed`() {
        assertEquals(emptyList<String>() to "home", splitTagInput("home"))
        assertEquals(listOf("home") to "", splitTagInput("home,"))
        assertEquals(listOf("a", " b", "") to " c", splitTagInput("a, b,, c"))
        assertEquals(listOf("one") to "two", splitTagInput("one\ntwo"))
        assertEquals(emptyList<String>() to "", splitTagInput(""))
    }

    @Test
    fun `matchingTags puts tags starting with the text first, then those containing it, at most eight`() {
        val known = listOf("network", "Work", "home", "homework", "travel")

        assertEquals(listOf("Work", "network", "homework"), matchingTags(known, "wor"))
        assertEquals(listOf("home", "homework"), matchingTags(known, " #HOME "))
        assertEquals(emptyList<String>(), matchingTags(known, "zzz"))
        assertEquals(known, matchingTags(known, ""))
        val many = (1..12).map { "tag$it" }
        assertEquals(many.take(8), matchingTags(many, ""))
        assertEquals(many.take(8), matchingTags(many, "tag"))
    }
}
