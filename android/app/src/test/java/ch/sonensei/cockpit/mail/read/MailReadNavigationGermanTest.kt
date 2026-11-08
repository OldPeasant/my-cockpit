package ch.sonensei.cockpit.mail.read

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Previous/next in the German locale: the position and the TalkBack labels exist there too. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "de")
class MailReadNavigationGermanTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val message =
        MailMessage(
            id = id(2),
            subject = "Quartalsbericht",
            from = "Anna Beispiel <anna@example.org>",
            to = listOf("ich@example.org"),
            cc = emptyList(),
            date = Instant.parse("2026-10-21T06:00:00Z"),
            seen = true,
            html = "<p>Hallo</p>",
            text = "Hallo",
            remoteImagesBlocked = 0,
            attachments = emptyList(),
        )

    private fun show() =
        composeRule.setContent {
            CockpitTheme {
                MailReadContent(
                    MailReadUiState(message = message),
                    MailReadCallbacks(),
                    zone = ZoneId.of("Europe/Zurich"),
                    navigation = MailReadNavigation.of(listOf(id(1), id(2), id(3)), id(2)) {},
                    body = { _, modifier -> Box(modifier.testTag(MailReadTags.BODY)) },
                )
            }
        }

    @Test
    fun `the position is German`() {
        show()
        composeRule.onNodeWithTag(MailReadTags.POSITION).assertTextEquals("2 von 3")
    }

    @Test
    fun `previous and next have German TalkBack labels`() {
        show()
        composeRule.onNodeWithContentDescription("Vorherige Mail").assertIsEnabled()
        composeRule.onNodeWithContentDescription("Nächste Mail").assertIsEnabled()
    }
}
