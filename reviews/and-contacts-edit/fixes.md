# Fix Report

Date: 2026-11-08

Task: and-contacts-edit - Android contacts: create, edit, delete

Scope: current task. Findings CR-1 to CR-9 from `reviews/and-contacts-edit/review.md` and TW-1 to TW-3 from `reviews/and-contacts-edit/tests.md`. Base 45b5d48a683c20ae59293e21e3ff4c3360270974, task commits 117b9aa76, b6b620ce4, 3d525f89f. The parent allowed one change outside `touches`: `ui/navigation/CockpitDestination.kt` (TW-3). Changes are uncommitted; the parent commits them.

All paths below are relative to `android/app/src/`. Line numbers refer to the working tree after the fixes.

## Fixed findings

### CR-1 — ContactDetailRoute always called hiltViewModel() for the edit view model

Files changed:
- `main/java/ch/sonensei/cockpit/contacts/ContactsScreen.kt` (ContactDetailRoute, about lines 178-188, 254-262)

Problem:
`editViewModel: ContactEditViewModel = hiltViewModel()` ran in non-Hilt activities too, so BirthdayContactTest (plain ComponentActivity) would crash.

Fix:
The parameter is now `editViewModel: ContactEditViewModel? = null`. Inside a Hilt activity (`LocalActivity.current is GeneratedComponentManagerHolder`, the same guard as the links view model) the route gets one from Hilt. Without Hilt it has none, and the `edit` and `delete` callbacks are `null`, so Edit and Delete are not shown. The delete dialog and the edit form also need the view model.

Validation:
- `./gradlew --offline testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.edit.BirthdayContactTest'` (with the contacts tests) — Pass
- full `./gradlew --offline ktlintCheck testDebugUnitTest lintDebug` — Pass

### CR-3 — Unsaved form edits were lost on activity recreation

Files changed:
- `main/java/ch/sonensei/cockpit/contacts/ContactsViewModels.kt:156` (`ContactEditUiState.session`), `:169-186` (`start(contact, session)`)
- `main/java/ch/sonensei/cockpit/contacts/ContactsScreen.kt` (ContactEditRoute, about lines 143-147)

Problem:
`LaunchedEffect(contact?.id) { viewModel.start(contact) }` ran again after a rotation and reset the form, including the `saving` flag of a running save.

Fix:
ContactEditRoute keeps a form session token in `rememberSaveable` and passes it to `start`. If the same session is started again (because the screen was recreated), `start` does nothing. While a save runs, `start` never restarts. It only adopts the new session, so the running save still closes the form that is shown. Calls without a session (the existing unit tests) behave as before.

Validation:
- New test `ContactEditViewModelTest` "starting the same form session again keeps what was typed, a new session starts over" — Pass
- `./gradlew --offline ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.contacts.*'` — Pass

### CR-4 — Edit view model state leaked across contacts and screens

Files changed:
- `main/java/ch/sonensei/cockpit/contacts/ContactsScreen.kt:103`, `:130-131` (`editViewModelKey`), ContactDetailRoute about line 185, ContactEditRoute about lines 149-161
- `main/java/ch/sonensei/cockpit/contacts/ContactsViewModels.kt:188-189` (`cancel()`), `:243` (`finished()` also clears the session)

Problem:
One activity-scoped ContactEditViewModel served every contact and the new-contact form. As a result, `error`, `deleted`, `saving` and `saved` showed up on other contacts, and a pending `saved` could close the next form.

Fix:
- The edit view model is now keyed per contact (`contact-edit:{id}`) and there is one for new contacts (`contact-edit:new`), like the links view model. A delete or save outcome can no longer appear on another contact (cases b, c, d).
- Cancel and Back in the form call the new `viewModel.cancel()`, which clears problem, error, detail and session (case a).
- The form closes only on a `saved` that belongs to its own session (`state.session == session`). A value left over from an earlier form no longer closes a new one (case e).

Validation:
- `ContactsRouteInAppTest` (all, including TW-2 below) — Pass
- `ContactEditViewModelTest` "a save refused as invalid keeps what the server said is wrong" (checks that `cancel()` clears the error) — Pass

### TW-2 — Cancelling the form after a failed save left an error on the detail

Files changed:
- same as CR-4 (`cancel()` in ContactsViewModels.kt:189; ContactEditRoute cancel and Back in ContactsScreen.kt)

Problem:
After a failed save, Cancel left `error` set, and the detail showed "The contact could not be deleted."

Fix:
Cancel and Back call `viewModel.cancel()` before `onDone(null)`, which clears the error.

Validation:
- `ContactsRouteInAppTest` "cancelling the form after a failed save leaves no error on the detail" — Pass (it failed before)

### CR-5 — A delete conflict showed the save-conflict text, and retrying could not work

Files changed:
- `main/java/ch/sonensei/cockpit/contacts/ContactsScreen.kt` (ContactDetailRoute, `LaunchedEffect(editState.error)` about lines 206-209; `deleteErrorText` about line 780)
- `main/res/values/strings_contacts.xml:67`, `main/res/values-de/strings_contacts.xml:67` (`contacts_delete_conflict`)

Problem:
A 409/412 on delete showed "Your changes are kept: save again...", and the detail was not reloaded, so the next Delete failed again with the old version.

Fix:
When the detail's action error is CONFLICT, the route calls `viewModel.refresh()` (the detail view model), so the next Delete sends the current version. `deleteErrorText` maps CONFLICT to the new `contacts_delete_conflict` text. EN: "It was changed elsewhere in the meantime. The contact was reloaded: check it and delete again." DE: "Er wurde inzwischen anderswo geändert. Der Kontakt wurde neu geladen: Prüfe ihn und lösche ihn erneut."

Validation:
- `ContactsStringsTest` (EN/DE parity and placeholders) — Pass
- full `testDebugUnitTest` and `lintDebug` — Pass
- No dedicated Compose test for the reload was added (see the note for the parent below).

### CR-6 — Display name derivation differed from the server and the web

Files changed:
- `main/java/ch/sonensei/cockpit/contacts/ContactForm.kt:103` (`of()`), `:126-133` (new `derivedName`)

Problem:
`of()` treated the display name as derived only when it equalled "given family". A display name derived from the organization or the first email was kept as if set by hand.

Fix:
New `internal fun derivedName(contact)`: given and family name (trimmed), else the organization (trimmed), else the first email (trimmed), like `ContactService.derivedName` and the web's `derivedName`. `of()` leaves the field empty when the display name equals it.

Validation:
- `ContactFormTest` "a display name the server derived from the organization or the first email is left empty, like on the web" — Pass (this is TW-1)
- `ContactFormTest` "a display name derived from the name is left empty, one set by hand is kept" — Pass

### TW-1 — Organization- or email-derived display name was turned into a hand-set one

Files changed:
- same as CR-6

Problem / Fix:
Same as CR-6.

Validation:
- `ContactFormTest` TW-1 test — Pass (it failed before)

### CR-7 — Server validation errors (400) showed only a generic text

Files changed:
- `main/java/ch/sonensei/cockpit/contacts/ContactsRepository.kt:39-47` (`ContactsResult.Failed.detail`), `:117-120` (`ContactsHttpFailure.detail`), `:124-151` (`body()` reads the error body on 400; new `problemDetail()`)
- `main/java/ch/sonensei/cockpit/contacts/ContactsViewModels.kt:140` (`ContactSaveError.INVALID`), `:151` (`errorDetail`), `:211-220`, `:246-256` (400 maps to INVALID for saves; deletes keep FAILED)
- `main/java/ch/sonensei/cockpit/contacts/ContactEditScreen.kt:96`, `:183-195` (`saveErrorText` takes the detail)
- `main/res/values/strings_contacts.xml:61-62`, `main/res/values-de/strings_contacts.xml:61-62` (`contacts_save_invalid`, `contacts_save_invalid_detail`)

Problem:
A 400 (for example an invalid email address) showed only "The contact could not be saved."

Fix:
On a 400 the repository reads the Problem Details body. The detail is the field errors joined with "; " (`emails[0].address is not an email address`), else the `detail`. It is kept only in memory and is never logged. A save refused with 400 becomes `ContactSaveError.INVALID`, and the form shows "The server did not accept the contact: {detail}" (or "... Check its fields." without a detail), in EN and DE. A delete answered with 400 sends no form and so still maps to FAILED ("could not be deleted"), as the existing delete test expects.

Validation:
- New test `ContactsRepositoryTest` "a refused save says which fields the server found wrong" — Pass
- New test `ContactEditViewModelTest` "a save refused as invalid keeps what the server said is wrong" — Pass
- Test change: in `ContactEditViewModelTest` "refusals from a read-only source, other failures and no connection are told apart", the case `Failed(400) to FAILED` is now `Failed(400) to INVALID`, and a `Failed(404) to FAILED` case was added. The old expectation contradicts this finding (CR-7 asks for a separate 400 outcome), so it had to change. No assertion was removed.

### CR-8 — Always sending photoFileId fails edits when the photo file is gone

Files changed:
- none beyond CR-7

Problem:
The generated client always sends `photoFileId`, so the server checks the file again and answers 400 if it was removed from the drive.

Fix:
Accepted, as the parent directed. Because of CR-7, such a 400 now shows the server's field error (for example `photoFileId ...`) instead of the generic text. A request that leaves the field out needs a client or server change outside this task's touches. The parent may record this with `tasks.py assume`.

Validation:
- Covered by the CR-7 tests (the 400 detail is surfaced).

### CR-9 — Notes lost leading whitespace; German quotes were mixed

Files changed:
- `main/java/ch/sonensei/cockpit/contacts/ContactForm.kt:89-90`
- `main/res/values-de/strings_contacts.xml:5, 65, 66`
- `test/java/ch/sonensei/cockpit/contacts/ContactFormTest.kt:127-128`

Problem:
`notesMd = notes.trimToNull()` also removed leading indentation, which matters in Markdown. The new German strings used «…» where the file uses „…“.

Fix:
`notesMd = notes.trimEnd().takeIf { it.isNotEmpty() }`, so the field is null when blank, like the web's `trimEnd()`. `contacts_empty`, `contacts_delete_text` and `contacts_trashed` now use „…“. ContactFormTest "the request trims every text, leaves blanks out and drops empty rows" had expected the leading spaces of the notes to be removed (`"Prefers mail"`). It now expects `"  Prefers mail"`, the behavior this finding requires, and a comment explains why.

Validation:
- `ContactFormTest` — Pass

### TW-3 — The shell's Capture FAB covered the contacts New FAB

Files changed:
- `main/java/ch/sonensei/cockpit/ui/navigation/CockpitDestination.kt:71-72` (outside touches, accepted by the parent)
- `test/java/ch/sonensei/cockpit/capture/entry/CaptureButtonTest.kt` (companion `OWN_FAB`)
- `test/java/ch/sonensei/cockpit/ui/FabClearanceTest.kt` (companion `OWN_FAB`)

Problem:
Contacts was missing from `CockpitDestination.withOwnFab`, so the Capture FAB sat on top of New contact.

Fix:
Added `Contacts` to `withOwnFab`, and the KDoc now mentions "new contact". CaptureButtonTest and FabClearanceTest each keep their own copy of the own-FAB set, and both failed until Contacts was added to it (`clearance on Contacts expected 72dp`, and `capture-button:fab` was expected on Contacts). Contacts is now in both sets. This follows directly from the approved change and weakens no assertion. These two test files are outside the task's touches, so the parent should know about them.

Validation:
- `ContactsRouteInAppTest` "the New contact button is not covered by the shell's capture button" — Pass (it failed before)
- `CockpitShellSearchTest`, `CaptureButtonTest`, `FabClearanceTest` — Pass (in the full run)

## Additional fix (not in the supplied findings)

### F-1 — lintDebug error in a German string added by this task

Files changed:
- `main/res/values-de/strings_contacts.xml:46`

Problem:
`./gradlew lintDebug` failed with `"Strasse" is a common misspelling; did you mean "Straße"? [Typos]` on `contacts_street`, a string added by this task. This blocks acceptance criterion 5.

Fix:
`Strasse` changed to `Straße`, which matches the web's German translation (`messages.de.xlf`) and the other Android German strings, which use ß. No test refers to the old text.

Validation:
- `./gradlew --offline ktlintCheck testDebugUnitTest lintDebug` — Pass

## Not fixed

### CR-2 — No tests for the new behavior in the reviewed commits

Status:
Invalid (no longer valid)

Reason:
The test-writer added 65 tests in commit 3d525f89f. This fix round adds 3 more (ContactsRepositoryTest 1, ContactEditViewModelTest 2). No code change was needed for this finding. Not covered by a dedicated test: the CR-5 detail reload after a delete conflict, and the session behavior during an actual rotation in Compose. The VM-level session test covers the logic. The parent may ask the test-writer for these on the re-run.

## Validation summary

Focused tests:
- `cd android && ./gradlew --offline ktlintFormat compileDebugKotlin` — Pass (Gradle was available in this sandbox)
- `cd android && ./gradlew --offline ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.contacts.*' --tests 'ch.sonensei.cockpit.calendar.edit.BirthdayContactTest' --tests 'ch.sonensei.cockpit.ui.CockpitShellSearchTest'` — Pass (the first run had one failure, the ContactFormTest notes expectation, which was then updated for CR-9)
- `cd android && ./gradlew --offline ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.contacts.*'` — Pass (after the repository test was added)

Full test suite:
- `cd android && ./gradlew --offline ktlintCheck testDebugUnitTest lintDebug` — first run: 4772 tests, 2 failed (CaptureButtonTest and FabClearanceTest, both caused by their own-FAB copies, fixed as described under TW-3). Second run: unit tests passed, and lintDebug failed on "Strasse" (F-1). Final run: Pass (BUILD SUCCESSFUL, no output with -q).

Lint/type-check/build:
- ktlintCheck, compileDebugKotlin, lintDebug — Pass

`.taskcheck`:
- `sh scripts/check.sh` — Pass ("== all checks passed"; the web bundle budget warning was already there and is not related)

Remaining unresolved findings:
0 (CR-2 is no longer valid; CR-8 is accepted as directed, and its 400 message comes through CR-7)

## Final status

All validated findings fixed and validation passed.

## Round 2

Date: 2026-11-08

Scope: the re-review finding in reviews/and-contacts-edit/review.md (round 2) and one lint fix in a test. No production code changed in this round.

### CR-1 (round 2) — no test checks that a delete conflict (409/412) reloads the contact

Severity: P3

Files changed:
- `android/app/src/test/java/ch/sonensei/cockpit/contacts/ContactsRouteInAppTest.kt` (test-writer, commit df6952c67)

Problem:
The CR-5 code reloads the detail after a delete conflict (ContactsScreen.kt:205-207), but no test covered it.

Fix:
The test-writer added the test `a delete that hit a change made elsewhere reloads the contact, and Delete again uses its current version`. The fixer read it and confirmed it covers the finding:
- `repo.get(annaId)` returns `anna` first, then `reloaded` (title "CEO", version 5). `repo.delete` returns `Failed(412)` first, then `Ok(Unit)`.
- First Delete and confirm: the test waits for the conflict message ("It was changed elsewhere in the meantime. The contact was reloaded: check it and delete again."), then for the reloaded detail ("CEO, Beispiel AG"), and checks `coVerify(exactly = 2) { repo.get(annaId) }`. So the reload really happened.
- Second Delete and confirm: the test waits for the list row `ContactsTags.contact(annaId)`, so the screen went back to the list. It checks `repo.delete(anna)` once and `repo.delete(reloaded)` once, so the second delete sent the new version.
The fixer changed no code for this finding.

Validation:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport --continue` (run by the parent) — Pass (BUILD SUCCESSFUL, 4783 tests, 0 failures)

### Lint — ViewModelConstructorInComposable in ContactEditScreenTest

Files changed:
- `android/app/src/test/java/ch/sonensei/cockpit/contacts/ContactEditScreenTest.kt` (parent, commit 6e40c2dd5)

Problem:
`lintDebug` reported ViewModelConstructorInComposable at ContactEditScreenTest.kt:331. A new test built `ContactDetailViewModel(repo)` inside `setContent`.

Fix:
The view model is now built before `setContent` and passed in as `viewModel = viewModel`. The test does the same thing, and the warning was fixed, not suppressed. The fixer checked the diff.

Validation:
- Same parent run as above — Pass (lintDebug clean)

### Round 2 validation summary

Focused tests:
- Covered by the parent's full Android run below.

Full test suite:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport --continue` — Pass (4783 tests, 0 failures), run by the parent

Lint/type-check/build:
- ktlintCheck, lintDebug — Pass (same run)

`.taskcheck`:
- Not run by the fixer in this round. The parent runs it before `done`.

Remaining unresolved findings:
0

### Round 2 final status

All validated findings fixed and validation passed.
