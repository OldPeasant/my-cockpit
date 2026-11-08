# Fix Report

Date: 2026-11-08

Task: and-mail-note-links - Android mail: create note and linked items

Scope: current task; findings CR-1 and CR-2 from reviews/and-mail-note-links/review.md

## Fixed findings

### CR-2 — Toolbar needs 344dp and squeezes the overflow menu on 320dp-wide windows

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadNoteLinksTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadNoteGermanTest.kt`

Problem:
The toolbar Row held back plus six 48dp icon buttons (7 x 48dp + 8dp padding = 344dp). In a Row the unweighted
children are measured in order, so on a 320dp-wide window the last one, the "More" button (mark unread, flag, move,
spam), got only about 24dp and was clipped.

Fix:
- "Create note" is no longer a toolbar icon. It is now the first item of the More menu (DropdownMenu in MoreMenu). It
  keeps the test tag `MailReadTags.gtd(MailGtdAction.NOTE)`, the NoteAdd icon and the string `mail_read_to_note`, and
  calls `callbacks.gtd(MailGtdAction.NOTE)` through the existing `pick`, which also closes the menu. The toolbar is
  back to 6 x 48dp + 8dp = 296dp, which fits 320dp.
- `MenuItem` now takes the test tag as a `String` instead of a `MailListAction`. The existing items pass
  `MailReadTags.action(...)`, so their tags are unchanged.
- KDoc of `MailReadContent` and `MoreMenu` updated to match. Nothing else in the read view changed. The menu still
  opens only when a mail is loaded and no action is running (`expanded = open && enabled`), so "Create note" is still
  unavailable while an action runs or when there is no mail.
- Tests adapted in MailReadNoteLinksTest: the click, label, busy, no-mail and failed-load tests, plus both route
  tests, now open the menu (`MailReadTags.MORE`) first. When the menu is disabled they check that the More button is
  disabled and the note item does not exist. The route success test now checks that More is enabled again after the
  action, instead of checking the note item, which closes with the menu. The test "sits after the GTD actions and
  before archive" was replaced by "create note is not in the toolbar but the first item of the menu", which checks
  that the note tag is absent from the toolbar and that the note item sits above "Mark unread".
- New regression test `on a 320dp wide window the menu button is shown at full size`
  (`@Config(qualifiers = "w320dp-h470dp")`). For every toolbar button (back, task, waiting, archive, delete, more) it
  checks assertIsDisplayed and assertWidthIsAtLeast(48.dp), and that the button's unclipped right edge is inside the
  root.
- MailReadNoteGermanTest: the German label test now opens the menu and finds "Notiz erstellen" as menu item text (the
  item's icon has no content description, like the other menu items).
- MailReadScreenTest needed no change: its `actionTags` list (TASK, WAITING, ARCHIVE, DELETE, MORE) is again the
  complete toolbar.

Validation:
- Static check only: this sub-agent cannot run Gradle. The edits follow the file's existing ktlint_official style
  (lines under 140 characters, multi-line calls with trailing commas). The parent must run
  `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` — Not run

Follow-up for the parent: the task's first recorded assumption ("Create note is a 7th toolbar icon (NoteAdd) next to
To task/To Waiting, as on the web") no longer describes the code. Record a new assumption: "Create note" is the first
item of the read view's More menu so the toolbar fits 320dp, which differs from the web's toolbar placement.

### CR-1 — New UI behavior has no Compose/screen tests (criterion 4)

Files changed:
- None beyond the CR-2 test adaptations above.

Problem:
The reviewed commit added only a repository test for the new behavior.

Fix:
Checked the test-writer's tests (MailReadNoteLinksTest, MailReadNoteGermanTest, MailReadNoteViewModelTest) against
the review's list:
- Note action calls `callbacks.gtd(NOTE)` and is unavailable while busy, without a mail and after a failed load:
  covered, now through the menu.
- NOTE created and failed notices in English and German: covered.
- `linksPanel` rendered below the body under `MailReadTags.LINKS` (HTML and text-only mails, busy), and absent without
  a mail or after a failed load: covered.
- View model `gtd(NOTE)` success, failure, busy and duplicate-click handling: covered in MailReadNoteViewModelTest.
- Route-level NOTE create and failure with the mail staying open: covered.
- Route-level panel reload after a `Created` notice, and opening a linked item and returning with back: not covered.
  These paths need the Hilt `LinkedItemsViewModel`, which `mailLinks()` leaves out in non-Hilt screen tests (the
  route test only checks that the LINKS area exists). They follow the same pattern as ContactDetailRoute. No further
  tests were added, because doing so would need Hilt test infrastructure beyond this fix.

Validation:
- Test-writer reported these tests passing on the earlier code. The adapted versions must be re-run by the parent
  (see CR-2) — Not run

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.read.*'` — Not run (Gradle unavailable in this sub-agent; parent to run)

Full test suite:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` — Not run (parent to run)

Lint/type-check/build:
- Not run (parent to run ktlintCheck and lintDebug as above)

`.taskcheck`:
- Not run (`sh scripts/check.sh`, parent to run)

Remaining unresolved findings:
0

## Final status

All validated findings fixed; validation must still be run by the parent worker because Gradle cannot run in this sub-agent.
