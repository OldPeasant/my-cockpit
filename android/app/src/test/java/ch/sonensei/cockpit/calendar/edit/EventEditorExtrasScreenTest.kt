package ch.sonensei.cockpit.calendar.edit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.calendar.agenda.CalendarInfo
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** The new editor sections, driven by states the tests control: reminders, guests, time zone, invitations, links. */
@RunWith(AndroidJUnit4::class)
class EventEditorExtrasScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val work = CalendarInfo(id(90), "Work", null, true, false, 1)
    private val day = LocalDate.parse("2026-10-21")
    private val ada = Attendee("ada@example.org", "Ada Example", AttendeeStatus.ACCEPTED)
    private val bob = Attendee("bob@example.org", null, AttendeeStatus.DECLINED)
    private val form =
        EventForm(
            title = "Planning",
            calendarId = work.id,
            startDate = day,
            startTime = LocalTime.of(9, 0),
            endDate = day,
            endTime = LocalTime.of(10, 0),
            zone = ZoneId.of("Europe/Zurich"),
        )
    private val editable = EventEditorUiState(open = true, form = form, calendars = listOf(work), localCalendars = setOf(work.id))
    private val account = MailAccount(id(70), "Work mail", "me@example.org")
    private val other = MailAccount(id(71), "Private mail", "me@example.net")

    private val calls = mutableListOf<String>()

    private fun show(initial: EventEditorUiState): (EventEditorUiState) -> Unit {
        var state by mutableStateOf(initial)
        composeRule.setContent {
            CockpitTheme {
                EventEditorContent(
                    state = state,
                    callbacks =
                        EventEditorCallbacks(
                            update = { change -> state = state.copy(form = state.form?.let(change)) },
                            setGuestQuery = { calls += "query:$it" },
                            addGuest = { calls += "add:${it?.email}" },
                            removeGuest = { calls += "remove:$it" },
                            invite = { calls += "invite" },
                            updateInvitation = { change -> state = state.copy(invitation = state.invitation?.let(change)) },
                            sendInvitation = { calls += "send" },
                            dismissInvitation = { calls += "dismiss" },
                            retryAccounts = { calls += "retry" },
                            createLinked = { calls += "create:$it" },
                            reloadLinks = { calls += "reload-links" },
                            respond = { calls += "respond:$it" },
                            reload = { calls += "reload" },
                        ),
                )
            }
        }
        return { state = it }
    }

    /** [text] is shown inside the node tagged [tag] (rows and columns don't merge their texts). */
    private fun assertInside(
        tag: String,
        text: String,
        substring: Boolean = true,
    ) {
        composeRule.onNodeWithTag(tag).performScrollTo()
        composeRule.onNode(hasAnyAncestor(hasTestTag(tag)) and hasText(text, substring = substring)).assertExists()
    }

    // --- Reminders

    @Test
    fun `a preset reminder is added, shown in words and removed`() {
        show(editable)
        composeRule.onNodeWithText("No reminders.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(EventEditorTags.REMINDER_ADD).performScrollTo().performClick()
        composeRule.onNodeWithTag(EventEditorTags.reminderPreset(60)).performClick()
        assertInside(EventEditorTags.reminder(60), "1 hour")
        composeRule.onNodeWithText("No reminders.").assertDoesNotExist()

        // A preset already set can't be added again.
        composeRule.onNodeWithTag(EventEditorTags.REMINDER_ADD).performScrollTo().performClick()
        composeRule.onNodeWithTag(EventEditorTags.reminderPreset(60)).assertIsNotEnabled()
        composeRule.onNodeWithTag(EventEditorTags.reminderPreset(10080)).performClick()
        assertInside(EventEditorTags.reminder(10080), "1 week")

        composeRule.onNodeWithTag(EventEditorTags.reminderRemove(60)).performScrollTo().performClick()
        composeRule.onNodeWithTag(EventEditorTags.reminder(60)).assertDoesNotExist()
        composeRule.onNodeWithTag(EventEditorTags.reminder(10080)).assertExists()
    }

    @Test
    fun `a custom reminder takes the minutes before the start and refuses other input`() {
        show(editable)
        composeRule.onNodeWithTag(EventEditorTags.REMINDER_ADD).performScrollTo().performClick()
        composeRule.onNodeWithTag(EventEditorTags.REMINDER_CUSTOM).performClick()
        composeRule.onNodeWithTag(EventEditorTags.REMINDER_CUSTOM_DIALOG).assertIsDisplayed()
        composeRule.onNodeWithTag(EventEditorTags.REMINDER_CUSTOM_OK).assertIsNotEnabled()
        composeRule.onNodeWithTag(EventEditorTags.REMINDER_CUSTOM_MINUTES).performTextInput("50000")
        composeRule.onNodeWithText("Enter a number from 0 to 40320.").assertIsDisplayed()
        composeRule.onNodeWithTag(EventEditorTags.REMINDER_CUSTOM_OK).assertIsNotEnabled()
        composeRule.onNodeWithTag(EventEditorTags.REMINDER_CUSTOM_MINUTES).performTextReplacement("45")
        composeRule.onNodeWithTag(EventEditorTags.REMINDER_CUSTOM_OK).assertIsEnabled().performClick()
        composeRule.onNodeWithTag(EventEditorTags.REMINDER_CUSTOM_DIALOG).assertDoesNotExist()
        assertInside(EventEditorTags.reminder(45), "45 min before")

        // Cancelling adds nothing.
        composeRule.onNodeWithTag(EventEditorTags.REMINDER_ADD).performScrollTo().performClick()
        composeRule.onNodeWithTag(EventEditorTags.REMINDER_CUSTOM).performClick()
        composeRule.onNodeWithTag(EventEditorTags.REMINDER_CUSTOM_MINUTES).performTextInput("90")
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithTag(EventEditorTags.reminder(90)).assertDoesNotExist()
    }

    @Test
    fun `reminders of a read-only event are shown without add or remove`() {
        show(editable.copy(form = form.withReminder(1440), calendars = listOf(work.copy(readOnly = true))))
        assertInside(EventEditorTags.reminder(1440), "1 day")
        composeRule.onNodeWithTag(EventEditorTags.reminderRemove(1440)).assertDoesNotExist()
        composeRule.onNodeWithTag(EventEditorTags.REMINDER_ADD).assertDoesNotExist()
    }

    // --- Time zone

    @Test
    fun `the time zone picker searches zones and changes the zone of the event`() {
        show(editable)
        composeRule
            .onNodeWithTag(EventEditorTags.TIME_ZONE)
            .performScrollTo()
            .assertTextContains("Time zone: Europe/Zurich")
            .performClick()
        composeRule.onNodeWithTag(EventEditorTags.TIME_ZONE_DIALOG).assertIsDisplayed()
        composeRule.onNodeWithTag(EventEditorTags.TIME_ZONE_SEARCH).performTextInput("new york")
        composeRule.onNodeWithTag(EventEditorTags.zone("America/New_York")).assertTextContains("America/New York").performClick()
        composeRule.onNodeWithTag(EventEditorTags.TIME_ZONE_DIALOG).assertDoesNotExist()
        composeRule.onNodeWithTag(EventEditorTags.TIME_ZONE).assertTextContains("Time zone: America/New_York")
    }

    @Test
    fun `a search without matches says so and cancelling keeps the zone`() {
        show(editable)
        composeRule.onNodeWithTag(EventEditorTags.TIME_ZONE).performScrollTo().performClick()
        composeRule.onNodeWithTag(EventEditorTags.TIME_ZONE_SEARCH).performTextInput("Atlantis")
        composeRule.onNodeWithText("No time zone matches.").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithTag(EventEditorTags.TIME_ZONE).assertTextContains("Time zone: Europe/Zurich")
    }

    @Test
    fun `the time zone of a read-only event can't be changed`() {
        show(editable.copy(calendars = listOf(work.copy(readOnly = true))))
        composeRule.onNodeWithTag(EventEditorTags.TIME_ZONE).performScrollTo().assertIsNotEnabled()
    }

    // --- Guests

    @Test
    fun `guests show their answers and can be removed and added in a local calendar`() {
        show(editable.copy(form = form.copy(attendees = listOf(ada, bob)), guestQuery = "car"))
        assertInside(EventEditorTags.guest(ada.email), "Ada Example")
        assertInside(EventEditorTags.guest(ada.email), ada.email)
        composeRule.onNodeWithTag(EventEditorTags.guestStatus(ada.email)).assertTextContains("Accepted")
        composeRule.onNodeWithTag(EventEditorTags.guestStatus(bob.email)).assertTextContains("Declined")
        composeRule.onNodeWithTag(EventEditorTags.guestRemove(bob.email)).performScrollTo().performClick()

        composeRule.onNodeWithTag(EventEditorTags.GUEST_INPUT).performScrollTo().performTextReplacement("carol")
        composeRule.onNodeWithTag(EventEditorTags.GUEST_ADD).performScrollTo().performClick()
        assertEquals(listOf("remove:${bob.email}", "query:carol", "add:null"), calls)
    }

    @Test
    fun `every answer of a guest has its label`() {
        val guests = AttendeeStatus.entries.mapIndexed { i, status -> Attendee("guest$i@example.org", status = status) }
        show(editable.copy(form = form.copy(attendees = guests)))
        val labels = listOf("No answer yet", "Accepted", "Declined", "Maybe", "Delegated")
        guests.zip(labels).forEach { (guest, label) ->
            composeRule.onNodeWithTag(EventEditorTags.guestStatus(guest.email)).performScrollTo().assertTextContains(label)
        }
    }

    @Test
    fun `suggestions are listed and picked, an invalid address says so`() {
        val carol = Attendee("carol@example.org", "Carol Example")
        show(editable.copy(guestQuery = "carol", suggestions = listOf(carol), guestInvalid = true))
        composeRule.onNodeWithText("No guests.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Enter an email address.").performScrollTo().assertIsDisplayed()
        composeRule
            .onNodeWithTag(
                EventEditorTags.suggestion(carol.email),
            ).performScrollTo()
            .assertTextContains("Carol Example")
            .performClick()
        assertEquals(listOf("add:${carol.email}"), calls)
    }

    @Test
    fun `guests of a synced calendar are read-only, a synced event without guests shows no section`() {
        val update = show(editable.copy(form = form.copy(attendees = listOf(ada)), localCalendars = emptySet()))
        composeRule.onNodeWithTag(EventEditorTags.guestStatus(ada.email)).performScrollTo().assertTextContains("Accepted")
        composeRule.onNodeWithTag(EventEditorTags.guestRemove(ada.email)).assertDoesNotExist()
        composeRule.onNodeWithTag(EventEditorTags.GUEST_INPUT).assertDoesNotExist()
        composeRule.onNodeWithText("Guests are managed by the event's calendar provider.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(EventEditorTags.INVITE).assertDoesNotExist()

        update(editable.copy(localCalendars = emptySet()))
        composeRule.onNodeWithTag(EventEditorTags.GUESTS).assertDoesNotExist()
    }

    @Test
    fun `send invitations is offered for a saved event with guests, and says when to save first or what was sent`() {
        val saved = editable.copy(form = form.copy(attendees = listOf(ada)), savedAttendees = listOf(ada.email))
        val update = show(saved)
        composeRule
            .onNodeWithTag(EventEditorTags.INVITE)
            .performScrollTo()
            .assertTextContains("Send invitations")
            .performClick()
        assertEquals(listOf("invite"), calls)

        update(saved.copy(saveBeforeInviting = true))
        composeRule
            .onNodeWithTag(EventEditorTags.INVITE_SAVE_FIRST)
            .performScrollTo()
            .assertTextContains("Save your changes before sending invitations.")

        update(saved.copy(invitationSent = InvitationSent(InvitationMethod.REQUEST, 2)))
        composeRule.onNodeWithTag(EventEditorTags.INVITE_SENT).performScrollTo().assertTextContains("Invitation sent to 2 guests.")
        update(saved.copy(invitationSent = InvitationSent(InvitationMethod.CANCEL, 1)))
        composeRule.onNodeWithTag(EventEditorTags.INVITE_SENT).performScrollTo().assertTextContains("Cancellation sent to 1 guest.")

        update(saved.copy(creating = true))
        composeRule.onNodeWithTag(EventEditorTags.INVITE).assertDoesNotExist()
    }

    // --- Invitation dialog

    private val prompt =
        InvitationUiState(
            eventId = id(1),
            title = "Planning",
            recipients = listOf(ada.email, bob.email),
            removed = listOf("carol@example.org"),
            afterSave = true,
            accounts = listOf(account, other),
            accountId = account.id,
        )

    /** A phone-sized screen: Robolectric's default (320x470dp) clips the dialog's account picker (see the test report). */
    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `the prompt after a save names the guests, the method, removed guests and the account`() {
        show(editable.copy(invitation = prompt))
        composeRule.onNodeWithTag(EventEditorTags.INVITE_DIALOG).assertIsDisplayed()
        composeRule.onNodeWithText("The event is saved. Tell its guests by mail?").assertIsDisplayed()
        composeRule.onNodeWithText("To: ada@example.org, bob@example.org").assertIsDisplayed()
        composeRule.onNodeWithTag(EventEditorTags.inviteMethod(InvitationMethod.REQUEST)).assertIsSelected()
        composeRule.onNodeWithTag(EventEditorTags.inviteMethod(InvitationMethod.CANCEL)).performClick()
        composeRule.onNodeWithTag(EventEditorTags.inviteMethod(InvitationMethod.CANCEL)).assertIsSelected()

        composeRule
            .onNodeWithTag(
                EventEditorTags.INVITE_TELL_REMOVED,
            ).assertTextContains("carol@example.org", substring = true)
            .assertIsSelected()
        composeRule.onNodeWithTag(EventEditorTags.INVITE_TELL_REMOVED).performClick().assertIsNotSelected()

        composeRule.onNodeWithTag(EventEditorTags.INVITE_ACCOUNT).assertTextContains("Work mail (me@example.org)").performClick()
        composeRule.onNodeWithTag(EventEditorTags.inviteAccount(other.id.toString())).performClick()
        composeRule.onNodeWithTag(EventEditorTags.INVITE_ACCOUNT).assertTextContains("Private mail (me@example.net)")

        composeRule.onNodeWithTag(EventEditorTags.INVITE_SEND).assertIsEnabled().performClick()
        composeRule.onNodeWithTag(EventEditorTags.INVITE_SKIP).assertTextContains("Don't send").performClick()
        assertEquals(listOf("send", "dismiss"), calls)
    }

    @Test
    fun `the prompt says when accounts load, fail or are missing, and a failed send`() {
        val update = show(editable.copy(invitation = prompt.copy(accounts = null, accountId = null)))
        composeRule.onNodeWithText("Loading mail accounts…").assertIsDisplayed()
        composeRule.onNodeWithTag(EventEditorTags.INVITE_SEND).assertIsNotEnabled()

        update(editable.copy(invitation = prompt.copy(accounts = null, accountId = null, loadFailed = true)))
        composeRule.onNodeWithText("The mail accounts could not be loaded.").assertIsDisplayed()
        composeRule.onNodeWithTag(EventEditorTags.INVITE_RETRY).performClick()

        update(editable.copy(invitation = prompt.copy(accounts = emptyList(), accountId = null)))
        composeRule.onNodeWithTag(EventEditorTags.INVITE_NO_ACCOUNTS).assertIsDisplayed()
        composeRule.onNodeWithTag(EventEditorTags.INVITE_SEND).assertIsNotEnabled()

        update(editable.copy(invitation = prompt.copy(failed = true)))
        composeRule.onNodeWithTag(EventEditorTags.INVITE_FAILED).assertTextContains("The invitation could not be sent. Try again.")

        update(editable.copy(invitation = prompt.copy(sending = true)))
        composeRule.onNodeWithTag(EventEditorTags.INVITE_SEND).assertIsNotEnabled()
        composeRule.onNodeWithTag(EventEditorTags.INVITE_SKIP).assertIsNotEnabled()
        composeRule.onNodeWithTag(EventEditorTags.INVITE_ACCOUNT).assertIsNotEnabled()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun `the prompt on request has no after-save text, only a cancel`() {
        show(editable.copy(invitation = prompt.copy(afterSave = false, removed = emptyList(), accounts = listOf(account))))
        composeRule.onNodeWithText("The event is saved. Tell its guests by mail?").assertDoesNotExist()
        composeRule.onNodeWithTag(EventEditorTags.INVITE_TELL_REMOVED).assertDoesNotExist()
        // A single account: nothing to choose.
        composeRule.onNodeWithTag(EventEditorTags.INVITE_ACCOUNT).assertIsNotEnabled()
        composeRule.onNodeWithTag(EventEditorTags.INVITE_SKIP).assertTextContains("Cancel")
    }

    @Test
    fun `a prompt only for removed guests offers no method`() {
        show(editable.copy(invitation = prompt.copy(recipients = emptyList())))
        composeRule.onNodeWithTag(EventEditorTags.inviteMethod(InvitationMethod.REQUEST)).assertDoesNotExist()
        composeRule.onNodeWithTag(EventEditorTags.INVITE_TELL_REMOVED).assertIsDisplayed()
        composeRule.onNodeWithTag(EventEditorTags.INVITE_SEND).assertIsEnabled()
    }

    // --- Linked items

    @Test
    fun `the links panel lists items with type and subtitle and creates a task or a note`() {
        val items =
            listOf(
                LinkedItem(id(50), LinkedType.TASK, id(10), "Prepare slides", "Inbox"),
                LinkedItem(id(51), LinkedType.JOURNAL_ENTRY, id(11), "Retro", " "),
                LinkedItem(id(52), LinkedType.EMAIL, id(12), null, null),
            )
        show(editable.copy(links = LinksUiState(items = items)))
        assertInside(EventEditorTags.link(id(50).toString()), "Prepare slides")
        assertInside(EventEditorTags.link(id(50).toString()), "Task · Inbox")
        // A blank subtitle is left out.
        assertInside(EventEditorTags.link(id(51).toString()), "Journal entry", substring = false)
        assertInside(EventEditorTags.link(id(52).toString()), "Item no longer available")
        composeRule.onNodeWithTag(EventEditorTags.CREATE_TASK).performScrollTo().performClick()
        composeRule.onNodeWithTag(EventEditorTags.CREATE_NOTE).performScrollTo().performClick()
        assertEquals(listOf("create:TASK", "create:NOTE"), calls)
    }

    @Test
    fun `the links panel shows loading, empty, failure with retry and the result of a create`() {
        val update = show(editable.copy(links = LinksUiState(loading = true)))
        composeRule.onNodeWithText("Loading linked items…").performScrollTo().assertIsDisplayed()

        update(editable.copy(links = LinksUiState()))
        composeRule.onNodeWithText("No linked items.").performScrollTo().assertIsDisplayed()

        update(editable.copy(links = LinksUiState(failed = true)))
        composeRule.onNodeWithTag(EventEditorTags.LINKS_RETRY).performScrollTo().performClick()
        assertEquals(listOf("reload-links"), calls)

        update(editable.copy(links = LinksUiState(creating = LinkedKind.TASK)))
        composeRule.onNodeWithTag(EventEditorTags.CREATE_TASK).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(EventEditorTags.CREATE_NOTE).assertIsNotEnabled()

        val results =
            listOf(
                LinkedResult(LinkedKind.TASK, LinkedCreateResult.Created("Planning (2026-10-21)")) to
                    "Added to Inbox: Planning (2026-10-21)",
                LinkedResult(LinkedKind.NOTE, LinkedCreateResult.Created("Planning (2026-10-21)")) to "Note created: Planning (2026-10-21)",
                LinkedResult(LinkedKind.TASK, LinkedCreateResult.NotLinked("x")) to "could not be linked",
                LinkedResult(LinkedKind.NOTE, LinkedCreateResult.NotLinked("x")) to "The note was created, but",
                LinkedResult(LinkedKind.TASK, LinkedCreateResult.Failed) to "The task could not be created.",
                LinkedResult(LinkedKind.NOTE, LinkedCreateResult.Failed) to "The note could not be created.",
            )
        results.forEach { (result, text) ->
            update(editable.copy(links = LinksUiState(result = result)))
            composeRule.onNodeWithTag(EventEditorTags.LINKS_RESULT).performScrollTo().assertTextContains(text, substring = true)
        }
    }

    @Test
    fun `every type of linked item is named`() {
        val labels =
            mapOf(
                LinkedType.TASK to "Task",
                LinkedType.PROJECT to "Project",
                LinkedType.NOTE to "Note",
                LinkedType.EVENT to "Event",
                LinkedType.EMAIL to "Email",
                LinkedType.FILE to "File",
                LinkedType.FOLDER to "Folder",
                LinkedType.CONTACT to "Contact",
                LinkedType.BOOKMARK to "Bookmark",
                LinkedType.JOURNAL_ENTRY to "Journal entry",
                LinkedType.HABIT to "Habit",
                LinkedType.OTHER to "Item",
            )
        assertEquals(LinkedType.entries.toSet(), labels.keys)
        val items = LinkedType.entries.mapIndexed { i, type -> LinkedItem(id(200 + i), type, id(300 + i), "Linked ${type.name}", null) }
        show(editable.copy(links = LinksUiState(items = items)))
        items.forEach { item ->
            assertInside(EventEditorTags.link(item.linkId.toString()), labels.getValue(item.type), substring = false)
        }
    }

    @Test
    fun `a links reload keeps the shown items, and nothing is created while the event saves`() {
        val item = LinkedItem(id(60), LinkedType.NOTE, id(61), "Minutes", null)
        val update = show(editable.copy(links = LinksUiState(loading = true, items = listOf(item))))
        assertInside(EventEditorTags.link(id(60).toString()), "Minutes")
        composeRule.onNodeWithText("Loading linked items…").assertDoesNotExist()
        composeRule.onNodeWithTag(EventEditorTags.CREATE_TASK).performScrollTo().assertIsEnabled()

        update(editable.copy(saving = true, links = LinksUiState(items = listOf(item))))
        composeRule.onNodeWithTag(EventEditorTags.CREATE_TASK).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(EventEditorTags.CREATE_NOTE).assertIsNotEnabled()
    }

    @Test
    fun `a new event has no links panel`() {
        show(editable.copy(creating = true))
        composeRule.onNodeWithTag(EventEditorTags.LINKS).assertDoesNotExist()
    }

    // --- The answer to an invitation

    private val me = Attendee("me@example.org", "Me", AttendeeStatus.NEEDS_ACTION)
    private val invited =
        editable.copy(
            form = form.copy(attendees = listOf(ada, me)),
            localCalendars = emptySet(),
            invitationCalendars = setOf(work.id),
            ownAddresses = setOf(me.email),
        )

    @Test
    fun `an unanswered invitation offers accept, maybe and decline and sends the tapped one`() {
        show(invited)
        assertInside(EventEditorTags.RESPONSE, "Your answer")
        assertInside(EventEditorTags.RESPONSE, "You have not answered yet.")
        INVITATION_RESPONSES.forEach {
            composeRule
                .onNodeWithTag(EventEditorTags.respond(it))
                .performScrollTo()
                .assertIsEnabled()
                .assertIsNotSelected()
        }
        composeRule.onNodeWithTag(EventEditorTags.respond(AttendeeStatus.ACCEPTED)).assertTextContains("Accept")
        composeRule.onNodeWithTag(EventEditorTags.respond(AttendeeStatus.TENTATIVE)).assertTextContains("Maybe")
        composeRule.onNodeWithTag(EventEditorTags.respond(AttendeeStatus.DECLINED)).assertTextContains("Decline").performClick()
        composeRule.onNodeWithTag(EventEditorTags.respond(AttendeeStatus.TENTATIVE)).performClick()
        assertEquals(listOf("respond:DECLINED", "respond:TENTATIVE"), calls)
    }

    @Test
    fun `the current answer is named and selected`() {
        show(invited.copy(form = form.copy(attendees = listOf(ada, me.copy(status = AttendeeStatus.TENTATIVE)))))
        assertInside(EventEditorTags.RESPONSE, "You answered: Maybe")
        composeRule.onNodeWithTag(EventEditorTags.respond(AttendeeStatus.TENTATIVE)).performScrollTo().assertIsSelected()
        composeRule.onNodeWithTag(EventEditorTags.respond(AttendeeStatus.ACCEPTED)).assertIsNotSelected()
        composeRule.onNodeWithTag(EventEditorTags.respond(AttendeeStatus.DECLINED)).assertIsNotSelected()
    }

    @Test
    fun `a delegated or unknown own answer counts as not answered and selects no button`() {
        val update = show(invited.copy(form = form.copy(attendees = listOf(ada, me.copy(status = AttendeeStatus.DELEGATED)))))
        assertInside(EventEditorTags.RESPONSE, "You have not answered yet.")
        INVITATION_RESPONSES.forEach {
            composeRule.onNodeWithTag(EventEditorTags.respond(it)).performScrollTo().assertIsNotSelected()
        }
        update(invited.copy(ownAddresses = emptySet()))
        assertInside(EventEditorTags.RESPONSE, "You have not answered yet.")
        composeRule.onNodeWithTag(EventEditorTags.respond(AttendeeStatus.ACCEPTED)).performScrollTo().assertIsEnabled()
    }

    @Test
    fun `while an answer is sent or after a conflict the answers are disabled`() {
        val update = show(invited.copy(responding = AttendeeStatus.ACCEPTED))
        INVITATION_RESPONSES.forEach {
            composeRule.onNodeWithTag(EventEditorTags.respond(it)).performScrollTo().assertIsNotEnabled()
        }
        update(invited.copy(message = EditorMessage.CONFLICT))
        composeRule.onNodeWithTag(EventEditorTags.respond(AttendeeStatus.ACCEPTED)).performScrollTo().assertIsNotEnabled()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `a refused answer is explained`() {
        show(invited.copy(message = EditorMessage.RESPOND_FAILED))
        composeRule
            .onNodeWithText(
                "The answer could not be sent. Reload the event and try again, or check that you are invited and not the organizer.",
            ).assertIsDisplayed()
        composeRule.onNodeWithTag(EventEditorTags.respond(AttendeeStatus.ACCEPTED)).performScrollTo().assertIsEnabled()
        composeRule.onNodeWithTag(EventEditorTags.RELOAD).assertTextContains("Reload").performClick()
        assertEquals(listOf("reload"), calls)
    }

    @Test
    fun `no answers are offered for a local calendar, a new event or one without guests`() {
        val update = show(invited.copy(invitationCalendars = emptySet()))
        composeRule.onNodeWithTag(EventEditorTags.RESPONSE).assertDoesNotExist()
        update(invited.copy(creating = true))
        composeRule.onNodeWithTag(EventEditorTags.RESPONSE).assertDoesNotExist()
        update(invited.copy(form = form))
        composeRule.onNodeWithTag(EventEditorTags.RESPONSE).assertDoesNotExist()
    }
}
