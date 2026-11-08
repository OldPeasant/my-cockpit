package ch.sonensei.cockpit.mail.compose

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.mail.list.MailAccount
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.UUID

/** Accessibility of the recipient chips: 48 dp remove target, no dead click on the body, a "Remove" custom action. */
@RunWith(AndroidJUnit4::class)
class MailComposeChipA11yTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val work = MailAccount(UUID.fromString("00000000-0000-7000-8000-000000000081"), "Work", "me@example.org")

    private val editing =
        MailComposeUiState(
            mode = ComposeMode.New(),
            phase = ComposePhase.EDITING,
            accounts = listOf(work),
            accountId = work.id,
        )

    private val calls = mutableListOf<String>()

    private fun show(state: MailComposeUiState) =
        composeRule.setContent {
            CockpitTheme {
                MailComposeContent(
                    state,
                    MailComposeCallbacks(to = { calls += "to:$it" }),
                    suggestions = RecipientSuggestions(),
                )
            }
        }

    private fun chip(index: Int) = composeRule.onNodeWithTag(MailComposeTags.recipient(RecipientField.TO, index))

    private fun customActionLabels(index: Int): List<String> =
        chip(index)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsActions.CustomActions)
            .orEmpty()
            .map { it.label }

    @Test
    fun `the remove control has a 48 dp touch target`() {
        show(editing.copy(to = "ann@example.org, "))

        composeRule
            .onNodeWithContentDescription("Remove ann@example.org")
            .assertHasClickAction()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `the chip body offers no click action`() {
        show(editing.copy(to = "Ann Example <ann@example.org>, "))

        chip(0).assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }

    @Test
    fun `the chip offers a Remove accessibility action that removes only its recipient`() {
        show(editing.copy(to = "ann@example.org, ben@example.org, "))

        assertEquals(listOf("Remove"), customActionLabels(1))
        val action =
            chip(1)
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .single()
        composeRule.runOnIdle { action.action() }

        assertEquals(listOf("to:ann@example.org, "), calls)
    }

    @Test
    fun `an invalid recipient chip also offers the Remove action`() {
        show(editing.copy(to = "not an address, "))

        assertEquals(listOf("Remove"), customActionLabels(0))
    }

    @Test
    @Config(qualifiers = "de")
    fun `the Remove action is German in a German locale`() {
        show(editing.copy(to = "ann@example.org, "))

        assertEquals(listOf("Entfernen"), customActionLabels(0))
        composeRule.onNodeWithContentDescription("Entfernen ann@example.org").assertHasClickAction()
    }

    @Test
    fun `while sending the chip is disabled and offers no Remove action`() {
        show(editing.copy(phase = ComposePhase.SENDING, to = "ann@example.org, "))

        chip(0).assertIsNotEnabled()
        assertTrue(customActionLabels(0).isEmpty())
        assertTrue(calls.isEmpty())
    }
}
