package ch.sonensei.cockpit.mail.compose

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.mail.list.MailAccount
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.UUID

/** The From picker with sending identities, and the title of a continued draft. */
@RunWith(AndroidJUnit4::class)
class MailComposeIdentityScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val work = MailAccount(id(80), "Work", "me@example.org")
    private val home = MailAccount(id(81), "Home", "me@example.net")
    private val workMain = MailIdentity(id(60), work.id, "Me", "me@example.org", isDefault = true)
    private val workSupport = MailIdentity(id(61), work.id, "Support", "support@example.org", isDefault = false)

    private val editing =
        MailComposeUiState(
            mode = ComposeMode.New(),
            phase = ComposePhase.EDITING,
            accounts = listOf(home, work),
            accountId = work.id,
            identities = listOf(workMain, workSupport),
        )

    private val chosenIdentities = mutableListOf<UUID>()
    private val chosenAccounts = mutableListOf<UUID>()

    private fun show(state: MailComposeUiState) =
        composeRule.setContent {
            CockpitTheme {
                MailComposeContent(
                    state,
                    MailComposeCallbacks(identity = { chosenIdentities += it }, account = { chosenAccounts += it }),
                )
            }
        }

    @Test
    fun `From shows the default identity and lists the identities, then the other accounts`() {
        show(editing)

        composeRule.onNodeWithTag(MailComposeTags.ACCOUNT).assertTextContains("Me <me@example.org>").performClick()
        composeRule.onNodeWithTag(MailComposeTags.identity(workMain.id)).assertTextEquals("Me <me@example.org>")
        composeRule.onNodeWithTag(MailComposeTags.identity(workSupport.id)).assertTextEquals("Support <support@example.org>")
        composeRule.onNodeWithTag(MailComposeTags.account(home.id)).assertExists()
        composeRule.onNodeWithTag(MailComposeTags.account(work.id)).assertDoesNotExist()

        composeRule.onNodeWithTag(MailComposeTags.identity(workSupport.id)).performClick()
        assertEquals(listOf(workSupport.id), chosenIdentities)
        composeRule.onNodeWithTag(MailComposeTags.identity(workSupport.id)).assertDoesNotExist()
    }

    @Test
    fun `From shows the chosen identity`() {
        show(editing.copy(identityId = workSupport.id))

        composeRule.onNodeWithTag(MailComposeTags.ACCOUNT).assertTextContains("Support <support@example.org>")
    }

    @Test
    fun `another account is chosen from the same list`() {
        show(editing)

        composeRule.onNodeWithTag(MailComposeTags.ACCOUNT).performClick()
        composeRule.onNodeWithTag(MailComposeTags.account(home.id)).performClick()

        assertEquals(listOf(home.id), chosenAccounts)
    }

    @Test
    fun `once the account is locked only its identities are offered`() {
        show(editing.copy(mode = ComposeMode.Reply(id(5)), accountLocked = true))

        composeRule.onNodeWithTag(MailComposeTags.ACCOUNT).assertIsEnabled().performClick()
        composeRule.onNodeWithTag(MailComposeTags.identity(workSupport.id)).assertExists()
        composeRule.onNodeWithTag(MailComposeTags.account(home.id)).assertDoesNotExist()
    }

    @Test
    fun `a locked account without identities cannot be changed and shows its address`() {
        show(editing.copy(identities = emptyList(), accountLocked = true))

        composeRule
            .onNodeWithTag(MailComposeTags.ACCOUNT)
            .assertIsNotEnabled()
            .assertTextContains("Work <me@example.org>")
    }

    @Test
    fun `without identities the accounts are listed as before`() {
        show(editing.copy(identities = emptyList()))

        composeRule.onNodeWithTag(MailComposeTags.ACCOUNT).performClick()
        composeRule.onNodeWithTag(MailComposeTags.account(home.id)).assertExists()
        composeRule.onNodeWithTag(MailComposeTags.account(work.id)).assertExists()
    }

    @Test
    fun `the From picker cannot be opened while the mail is not editable`() {
        show(editing.copy(phase = ComposePhase.SENDING))

        composeRule.onNodeWithTag(MailComposeTags.ACCOUNT).assertIsNotEnabled()
    }

    @Test
    fun `a single identity that is not the default can still be chosen`() {
        // The account address is used while no identity is chosen (no default); its only identity must be choosable.
        val alias = workSupport
        show(editing.copy(mode = ComposeMode.Reply(id(5)), accountLocked = true, identities = listOf(alias)))

        composeRule.onNodeWithTag(MailComposeTags.ACCOUNT).assertTextContains("Work <me@example.org>")
        composeRule.onNodeWithTag(MailComposeTags.ACCOUNT).assertIsEnabled().performClick()
        composeRule.onNodeWithTag(MailComposeTags.identity(alias.id)).performClick()

        assertEquals(listOf(alias.id), chosenIdentities)
    }

    @Test
    fun `a continued draft is titled Draft`() {
        show(editing.copy(mode = ComposeMode.Draft(id(7)), accountLocked = true))

        composeRule.onNodeWithTag(MailComposeTags.TITLE).assertTextEquals("Draft")
    }

    @Test
    @Config(qualifiers = "de")
    fun `a continued draft is titled Entwurf in German`() {
        show(editing.copy(mode = ComposeMode.Draft(id(7)), accountLocked = true))

        composeRule.onNodeWithTag(MailComposeTags.TITLE).assertTextEquals("Entwurf")
    }

    @Test
    fun `without a default identity the account address is offered and can be chosen back`() {
        var addressChosen = 0
        composeRule.setContent {
            CockpitTheme {
                MailComposeContent(
                    editing.copy(identities = listOf(workSupport), identityId = workSupport.id, accountLocked = true),
                    MailComposeCallbacks(accountAddress = { addressChosen++ }),
                )
            }
        }

        composeRule.onNodeWithTag(MailComposeTags.ACCOUNT).assertTextContains("Support <support@example.org>").performClick()
        composeRule.onNodeWithTag(MailComposeTags.ACCOUNT_ADDRESS).assertTextEquals("Work <me@example.org>").performClick()

        assertEquals(1, addressChosen)
    }

    @Test
    fun `with a default identity the account address is not offered`() {
        show(editing)

        composeRule.onNodeWithTag(MailComposeTags.ACCOUNT).performClick()
        composeRule.onNodeWithTag(MailComposeTags.ACCOUNT_ADDRESS).assertDoesNotExist()
    }

    @Test
    fun `an opened draft or reply shows its identities before the accounts are loaded`() {
        show(
            editing.copy(
                mode = ComposeMode.Draft(id(7)),
                accounts = emptyList(),
                accountLocked = true,
                identityId = workMain.id,
            ),
        )

        composeRule.onNodeWithTag(MailComposeTags.ACCOUNT).assertTextContains("Me <me@example.org>").performClick()
        composeRule.onNodeWithTag(MailComposeTags.identity(workSupport.id)).performClick()

        assertEquals(listOf(workSupport.id), chosenIdentities)
    }
}
