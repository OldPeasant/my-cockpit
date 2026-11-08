# Test Report

Date: 2026-11-08

Task: and-contacts-edit - Android contacts: create, edit, delete

Scope: base 45b5d48a683c20ae59293e21e3ff4c3360270974, task commits 1073bc818 and 5f8c61811 (`git diff 45b5d48a..5f8c61811 -- android`). Measured at HEAD eae697c5f26b105547c2a887549619f261afc87c. The only commit after 5f8c61811 is eae697c5f (code review report), which changes no Android files. Uncommitted inputs were the test files listed below, and nothing else.

Requirements:
- tasks/and-contacts-edit.md acceptance criteria and its two recorded assumptions (source line plus 403/405 message; ISO birthday text and photo id carried through)
- web parity reference: web/src/app/features/contacts/contact-form-model.ts (toForm, derivedName)
- docs/ARCHITECTURE.md "Coverage", AGENTS.md project notes, the parent's instructions
- existing conventions in android/app/src/test/java/ch/sonensei/cockpit/contacts and BookmarksStringsTest

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/contacts/ContactFormTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/contacts/ContactEditViewModelTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/contacts/ContactEditScreenTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/contacts/ContactsStringsTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/contacts/ContactsRepositoryTest.kt` (7 tests added)
- `android/app/src/test/java/ch/sonensei/cockpit/contacts/ContactsRouteInAppTest.kt` (8 tests added)
- `android/app/src/test/java/ch/sonensei/cockpit/contacts/ContactsScreensTest.kt` (1 existing test fixed, see below)

Fix to an existing test (the parent asked for it): in ContactsScreensTest, the test "the detail calls and mails from the header and from each number and address" failed with "no node with tag contact-phone:044 765 43 21". The new source line and the Edit/Delete row push the number rows below the visible part of the lazy detail. The test now scrolls to the row (`performScrollToNode(hasTestTag(ContactsTags.phone(...)))`) before clicking it. Every assertion it had is still there.

## Tests added or updated

Count: 65 new tests (ContactFormTest 12, ContactEditViewModelTest 15, ContactEditScreenTest 21, ContactsStringsTest 2, ContactsRepositoryTest 7, ContactsRouteInAppTest 8) and 1 updated (ContactsScreensTest).

## Requirement coverage

Command F (focused, all contacts tests plus lint): `cd android && ./gradlew --offline ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.contacts.*'` -> 112 tests, 109 passed, 3 failed (the three failures are TW-1, TW-2 and TW-3 below). `./gradlew --offline ktlintCheck` alone -> BUILD SUCCESSFUL.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 create: the form needs a name, organization or email; blanks and whitespace do not count | ContactFormTest: `a new form starts with one empty email and phone row and needs a name`, `whitespace-only names, organization and emails do not name the contact`, `a given name, family name, display name, organization or email alone is enough`, `a missing name is reported before an invalid birthday` | F | Pass |
| AC1 birthday entered as ISO text (assumption); invalid or impossible dates are refused | ContactFormTest `the birthday must be an ISO date, and blank means none`; ContactEditScreenTest `an invalid birthday is shown as the form's problem` | F | Pass |
| AC1 request mapping: trimming, blank rows dropped, blank labels sent as null, empty lists sent so removed rows are removed | ContactFormTest `the request trims every text, leaves blanks out and drops empty rows`, `an empty form sends empty lists, so removed rows are removed on the server` | F | Pass |
| AC1 edit: every field is filled from the contact and the photo id is kept (assumption) | ContactFormTest `editing a contact fills every field and keeps its photo`, `saving an unchanged contact sends what it had, the photo included`, `a contact without optional fields edits as empty texts` | F | Pass |
| AC1 display name filled in only when it was set by hand | ContactFormTest `a display name derived from the name is left empty, one set by hand is kept` | F | Pass |
| AC1 display name derived from the organization or email is left empty (web parity) | ContactFormTest `a display name the server derived from the organization or the first email is left empty, like on the web` | F | Fail (TW-1) |
| AC1 repository create, POST without If-Match | ContactsRepositoryTest `create posts the form without If-Match and reads back the saved contact`, `create reports refusals` | F | Pass |
| AC1 repository update, PATCH with If-Match set to the quoted version (`"7"`); a cleared field is sent as JSON null | ContactsRepositoryTest `update patches the contact with its version as If-Match and sends cleared fields as null`, `update reports a change made elsewhere and other refusals by status`, `writes without a connection are offline` | F | Pass |
| AC1 view model save (create or update), problem shown only after Save, no double submit, conflict (409/412) reloads the base and keeps the form, error mapping (403/405, 400, unreadable, offline), finished() | ContactEditViewModelTest (all 15 tests) | F | Pass |
| AC1 Compose form: add and remove rows (email, phone, address), typing, Save and Cancel callbacks, edit title and prefill, disabled while saving, problem and error banners | ContactEditScreenTest: the form tests (`a new contact is typed in with repeatable emails, phones and addresses, then saved`, `removing a row removes that row, not the last one`, `an address row is removed with its own button`, `editing shows the contact's fields under the edit title, and Cancel closes the form`, `while saving, Save and Cancel are disabled`, banner tests) | F | Pass |
| AC1 in-app flow: New opens the form, Save creates the contact and opens it; Cancel saves nothing; Edit then Save updates the contact and the detail shows the change | ContactsRouteInAppTest `New contact opens the form, Save creates the contact and shows it`, `Cancel on a new contact returns to the list without saving`, `Edit opens the form with the contact, Save updates it and the detail shows the change` | F | Pass (New is triggered through its click semantics action, see TW-3) |
| AC1 list New button can be tapped | ContactEditScreenTest `the list's New button opens the form`, `without a create callback the list has no New button`; ContactsRouteInAppTest `the New contact button is not covered by the shell's capture button` | F | Screen tests pass; in-app test Fail (TW-3) |
| AC1 detail after cancelling a failed save | ContactsRouteInAppTest `cancelling the form after a failed save leaves no error on the detail` | F | Fail (TW-2) |
| AC2 delete with confirmation (to Trash): the dialog asks first, confirm calls delete and shows a toast and the list, cancel keeps the contact; repository DELETE with If-Match; refusal and offline errors | ContactsRouteInAppTest `Delete asks first, and confirming moves the contact to the Trash and returns to the list`, `cancelling the delete question keeps the contact`; ContactsRepositoryTest `delete moves the contact to the Trash with its version as If-Match`, `a refused delete reports its status, a server error is offline`; ContactEditViewModelTest delete tests; ContactEditScreenTest `the detail offers Edit and Delete`, `while a delete runs the edit actions are disabled`, `a failed delete says the contact could not be deleted`, `a delete without connection says so` | F | Pass |
| AC3 synced contacts show their source, and a 403/405 says where to change the contact | ContactEditScreenTest `a contact kept in the cockpit says so in its header`, `a synced contact names its source in its header`, `source names are spelled like on the web`, `a change refused by a synced source says where to change the contact`, `a delete refused by a synced source says where to change the contact`; ContactsRouteInAppTest `a delete refused by a synced source stays on the contact and says where to change it`; ContactEditViewModelTest `refusals from a read-only source, other failures and no connection are told apart` | F | Pass |
| AC4 new strings exist in English and German with the same placeholders | ContactsStringsTest `every contacts string exists in German with the same placeholders`, `the strings of create, edit, delete and the source line are in both languages` | F | Pass |
| AC5 Unit and Compose tests cover the new behavior; ktlintCheck testDebugUnitTest lintDebug and scripts/check.sh pass | all of the above | F (ktlint and contacts tests only) | ktlintCheck: Pass. Contacts tests: Fail (3 legitimate failures, TW-1 to TW-3). Full `testDebugUnitTest`, `lintDebug` and `sh scripts/check.sh`: Not run by me (the parent runs them) |

## Focused test execution

Command:

`cd android && ./gradlew --offline ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.contacts.*'`

Result:

Fail: 112 tests, 3 failed, ktlint clean. The failing tests are exactly the three production defects below:
- `ch.sonensei.cockpit.contacts.ContactFormTest` "a display name the server derived from the organization or the first email is left empty, like on the web" (TW-1)
- `ch.sonensei.cockpit.contacts.ContactsRouteInAppTest` "cancelling the form after a failed save leaves no error on the detail" (TW-2)
- `ch.sonensei.cockpit.contacts.ContactsRouteInAppTest` "the New contact button is not covered by the shell's capture button" (TW-3)

Test classes to run: ContactFormTest, ContactEditViewModelTest, ContactEditScreenTest, ContactsStringsTest, ContactsRepositoryTest, ContactsRouteInAppTest, ContactsScreensTest (plus the unchanged ContactsModelsTest, ContactIntentsTest, ContactsViewModelsTest).

## Full test execution

Command:

`cd android && ./gradlew testDebugUnitTest` / `sh scripts/check.sh`

Result:

Not run: I changed only files in the contacts test package, and the parent runs the full suite and `.taskcheck`. It fails while TW-1 to TW-3 are open.

## .taskcheck

Result:

Not run (the parent runs it; it fails while TW-1 to TW-3 are open).

## Bugs found

### Editing a contact named after its organization or email turns the derived display name into a hand-set one

Finding ID:

`TW-1`

Test:

`ContactFormTest` "a display name the server derived from the organization or the first email is left empty, like on the web"

Production source:

`android/app/src/main/java/ch/sonensei/cockpit/contacts/ContactForm.kt:101-106` (`ContactForm.of`, the displayName takeIf)

Requirement:

AC1 (edit contacts like the web's contact-form, named in the task Goal). The KDoc of `of()` itself says the display name is filled in "only when it was set by hand (differs from the derived one)". The server and the web (`derivedName` in contact-form-model.ts) derive the display name from given plus family name, else the organization, else the first email address.

Expected behavior:

A contact without given or family name whose display name equals its organization (or its first email) opens with an empty display name field. If the organization later changes, the display name follows it.

Actual behavior:

`of()` compares only against given plus family name, and keeps the display name whenever that is empty. A company contact "Beispiel AG" opens with "Beispiel AG" in the display name field. Saving sends it as a hand-set display name, so the name no longer follows organization changes. Test output: `expected:<[]> but was:<[Beispiel AG]>`.

Severity:

Low

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

### Cancelling the form after a failed save shows a stale error on the detail ("could not be deleted")

Finding ID:

`TW-2`

Test:

`ContactsRouteInAppTest` "cancelling the form after a failed save leaves no error on the detail"

Production source:

`android/app/src/main/java/ch/sonensei/cockpit/contacts/ContactsScreen.kt` `ContactDetailRoute` (the edit route's `onDone(null)` path near line 176; `actionError = editState.error` near line 241) and `ContactEditRoute` (cancel and BackHandler call `onDone(null)` without `viewModel.finished()`)

Requirement:

AC1 and AC2: the error banner on the detail is documented as "Why the last delete did not work" (`actionError` KDoc).

Expected behavior:

The user edits a contact, Save fails (for example a 400), and the user cancels. The detail then shows no action error, because no delete was tried.

Actual behavior:

The edit view model keeps `error = FAILED`. The detail renders it as `actionError`, so "The contact could not be deleted." appears (tag `contact-action-error` exists). The same stale error also shows after an offline or 403 save. Because ContactsRoute and ContactDetailRoute get the same `ContactEditViewModel` instance from Hilt, a failed New-contact save that is cancelled can also leak its error onto the next opened contact.

Severity:

Medium

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

### The New contact button sits under the shell's Capture button

Finding ID:

`TW-3`

Test:

`ContactsRouteInAppTest` "the New contact button is not covered by the shell's capture button"

Production source:

`android/app/src/main/java/ch/sonensei/cockpit/contacts/ContactsScreen.kt` (`ContactsListContent`, ExtendedFloatingActionButton aligned BottomEnd with 16 dp padding) together with `android/app/src/main/java/ch/sonensei/cockpit/ui/navigation/CockpitDestination.kt:72` (`withOwnFab = setOf(Calendar, Mail, Notes, Files)`, which does not include Contacts). The likely fix is in the shared navigation file, outside this task's `touches`, so the parent has to decide how to handle it.

Requirement:

AC1 (create contacts: the New button must be usable). The shell's convention is that screens with their own corner button are listed in `CockpitDestination.withOwnFab`, which moves Capture into the app bar.

Expected behavior:

On the Contacts screen the shell's Capture FAB (`capture-button:fab`) is not shown, so tapping New contact opens the form.

Actual behavior:

The semantics dump in Robolectric shows New contact at (220,318)-(304,374) and the Capture FAB at (248,318)-(304,374), the same corner. A tap on the center of New lands on Capture, and the form never opens (the touch-based New flow tests timed out waiting for the form). The New-flow route tests now trigger the button through its click semantics action, so they test the flow itself. This dedicated test covers the overlap.

Severity:

Medium

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

### Observations (not failing tests, for the parent to judge)

- O-1: A delete answered with 409/412 shows the save-conflict text ("Your changes are kept: save again to replace that version."), which does not fit a delete. The detail is not reloaded, so the next Delete is sent with the same old version and fails again. No test was written because the required behavior is not specified.
- O-2: `ContactForm.toRequest()` trims leading whitespace of notes. The web uses `trimEnd()` for `notesMd`, which keeps indentation in Markdown. This is minor, and no test pins either behavior.

## Coverage

Status: verified for the passing tests, partial for the task. The run excludes the 3 failing tests, because the Android coverage task needs a passing test task and changing that would need a configuration change outside my authority.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-contacts-edit; base 45b5d48a683c20ae59293e21e3ff4c3360270974; measured HEAD eae697c5f26b105547c2a887549619f261afc87c; task commits 1073bc818 and 5f8c61811 as given by the parent, checked with `git diff --stat 45b5d48a..HEAD -- android` (8 files) and `git log 5f8c61811..HEAD` (only the review report commit).
- Source/test snapshot identifier (including uncommitted inputs): sha256 over the sha256sums of `android/app/src/test/java/ch/sonensei/cockpit/contacts/*.kt` and `android/app/src/main/java/ch/sonensei/cockpit/contacts/*.kt` = 26b9e2b14d3856e942654d076e7c85da322f84931c4d6336c210ee10e5575257, taken at 2026-11-08T20:50:57Z just before the run. No production files were uncommitted.
- Exact included files: ContactForm.kt, ContactEditScreen.kt, ContactsRepository.kt, ContactsViewModels.kt, ContactsScreen.kt, ContactsModels.kt (all in `android/app/src/main/java/ch/sonensei/cockpit/contacts/`). Excluded: `res/values/strings_contacts.xml` and `res/values-de/strings_contacts.xml` (resources, not instrumented; ContactsStringsTest covers them). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo through AGP `enableUnitTestCoverage`; command `./gradlew --offline testDebugUnitTest {test filters} createDebugUnitTestCoverageReport` in `android/`. Test selection: every contacts test class, except that ContactFormTest and ContactsRouteInAppTest were filtered by method-name prefix to leave out exactly the 3 failing tests (109 tests ran, 0 failures, per the test-results XML).
- Run start/completion time and exit status: about 2026-11-08T20:51:08Z to 20:51:25Z; BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, written 2026-11-08T20:51:25Z, sha256 1d2ecbbfe2ae6957942caae4934f20162eba78796a6da38ad740d2796035aea4.
- Evidence outputs are fresh and inputs stayed unchanged: the report timestamp matches this run, the test-results XML files were rewritten at the same time with 109 tests, and no file was edited during the run.
- Final run after latest test additions and production fixes: yes for the test additions; no production fixes have been made yet. After TW-1 to TW-3 are fixed, these numbers are stale and coverage must be run again.

Values below use covered/total (percentage). JaCoCo INSTRUCTION is shown in the Statements column.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `contacts/ContactForm.kt` | 844/852 (99.1%) | 58/64 (90.6%) | 33/33 (100.0%) | 88/88 (100.0%) |
| `contacts/ContactEditScreen.kt` | 3191/3308 (96.5%) | 57/69 (82.6%) | 56/65 (86.2%) | 209/210 (99.5%) |
| `contacts/ContactsRepository.kt` | 367/398 (92.2%) | 18/20 (90.0%) | 22/23 (95.7%) | 42/48 (87.5%) |
| `contacts/ContactsViewModels.kt` | 1198/1206 (99.3%) | 88/92 (95.7%) | 48/48 (100.0%) | 134/134 (100.0%) |
| `contacts/ContactsScreen.kt` | 5868/6274 (93.5%) | 215/273 (78.8%) | 123/137 (89.8%) | 462/478 (96.7%) |
| `contacts/ContactsModels.kt` | 784/790 (99.2%) | 54/58 (93.1%) | 43/43 (100.0%) | 73/73 (100.0%) |
| Aggregate (summed counters, all 6 files; nothing unmeasured) | 12252/12828 (95.5%) | 490/576 (85.1%) | 325/349 (93.1%) | 1008/1031 (97.8%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `ContactsScreen.kt:193-195, 201-203, 218-227` | Opening related or linked items in-app or on the web from the detail, and the links panel without Hilt | Existing code that this task did not change (related-item navigation from earlier tasks). Left uncovered |
| `ContactsScreen.kt:471, 565` and partial branches in Compose functions | Closing braces of `when`/`if` and Compose-generated default and skip branches | JaCoCo artifacts, not behavior |
| `ContactEditScreen.kt:192` and partial branches 180-325 | End of the exhaustive `when` in `saveErrorText`; Compose-generated branches of the private field composables | Artifacts. Every error and problem text is asserted in ContactEditScreenTest |
| `ContactsRepository.kt:23, 86-87, 94-96` | Hilt module provider; IllegalArgumentException and DateTimeException catch arms | Existing code; the provider is used only by the Hilt graph (the in-app test replaces the repository) |
| `ContactForm.kt:115-120` (partial) | `orEmpty()` on null address parts in `of()` | Covered for non-null and null parts in some fields. Not material |
| Execution paths of the 3 failing tests | Same code as the passing tests cover (ContactForm.of; the detail route's cancel path) | Left out of this run only because they fail. Run coverage again after the fixes |

## Unable to test

- `lintDebug`, the full Android `testDebugUnitTest` and `sh scripts/check.sh` (AC5): not run by me. The parent runs them, and they fail while TW-1 to TW-3 are open.
- Visual overlap on real device sizes for TW-3: shown in Robolectric's default window through semantics bounds; not checked on a device.
- O-1 and O-2: no requirement defines the behavior, so the parent decides.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion (TW-1 Low, TW-2 Medium, TW-3 Medium; TW-3's likely fix is in `ui/navigation/CockpitDestination.kt`, outside the task's `touches`).
