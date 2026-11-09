# Fix Report

Date: 2026-11-09

Task: and-waiting-contact-mail - Android Draft email: To from the linked waiting-for contact

Scope: findings CR-1 and CR-2 of reviews/and-waiting-contact-mail/review.md (current task)

## Fixed findings

### CR-1 — Draft email: no feedback or duplicate guard while the contact loads

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/lists/WaitingScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/lists/WaitingRouteDraftMailTest.kt`

Problem:
WaitingRoute's draftMail launched a new coroutine per tap, each awaiting `viewModel.mailRecipient(task)` (a live contact lookup) and then starting its own ACTION_SENDTO, so quick repeated taps during a slow lookup opened several compose windows.

Fix:
WaitingRoute keeps a remembered set of task ids whose draft is still resolving. A tap only starts the lookup when the task id is not already in the set; the id is removed in a `finally` once the intent was launched (or the lookup failed or was cancelled), so later taps draft again. No UI change, no new state in the ViewModel.

New test `taps while the contact is still loading open a single mail` in WaitingRouteDraftMailTest: the contact lookup suspends on a CompletableDeferred, the button is clicked twice, no mail is started yet; after completing the lookup exactly one ACTION_SENDTO (To anna@example.com) is started and the lookup ran once; a later tap drafts again. With the guard temporarily disabled the test fails (a second SENDTO intent is started), confirming it detects the defect; the guard was then restored.

Validation:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.lists.WaitingRouteDraftMailTest'` with the guard disabled — Fail (expected: second SENDTO intent started)
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.*'` — Pass (BUILD SUCCESSFUL; WaitingRouteDraftMailTest 3/3, ListViewModelsTest 19/19, no failures)

## Not fixed

### CR-2 — Contact-to-address mapping is untested

Status:
Invalid (already covered, no change)

Reason:
ListViewModelsTest.kt already builds WaitingViewModel through the @Inject constructor with a mocked ContactsRepository (`waitingWithContacts`), so the production `contacts::firstEmail` wiring and the private `ContactsRepository.firstEmail` run in tests: `the injected view model takes the contact's first email address` (Ok with two emails returns the first, `get(anna)` called once), `the injected view model falls back to the text for a contact without email addresses` (Ok without emails), and `the injected view model falls back to the text when the contact cannot be loaded` (Offline and Failed(404) fall back to the text, null without text). These tests pass in the run above. The review covered only the implementation commit, while these tests came with the test-writer's changes.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.*'` — Pass

Full test suite:
- Not run (the parent worker runs `.taskcheck`)

Lint/type-check/build:
- `./gradlew ktlintFormat ktlintCheck` (android) — Pass, ktlintFormat made no further changes

`.taskcheck`:
- Not run (left to the parent worker, `sh scripts/check.sh`)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
