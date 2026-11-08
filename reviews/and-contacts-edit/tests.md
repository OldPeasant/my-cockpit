# Test Report

Date: 2026-11-08

Task: and-contacts-edit - Android contacts: create, edit, delete

Scope: base 51965a2123e13d9f5babc4cd3dbc23b26251e33b (main), task commits 52ac12361, b50dbaa4f, f1318e81e, ee30b903b, e7ff7d8f3 (fixer, final production code), then df6952c67 (re-review and this round's tests) and 6e40c2dd5 (the parent's lint fix in a test). Measured at HEAD 6e40c2dd59a72ecace01aadd803428115dc28754. The scope comes from the parent's commit list, checked with `git diff --stat 51965a212 HEAD -- android`. `git diff e7ff7d8f3 HEAD -- android/app/src/main` is empty, so the production code measured is the fixer's final code. At measurement time there were no uncommitted source or test changes.

Requirements:
- tasks/and-contacts-edit.md acceptance criteria 1-5 and its recorded assumptions (source line plus 403/405 message; ISO birthday text; photo id carried through; Contacts in `withOwnFab`; CR-8 accepted)
- reviews/and-contacts-edit/fixes.md (fixes CR-1, CR-3 to CR-9, TW-1 to TW-3, F-1), plus the parent's request for a delete-conflict test (the code-reviewer's P3 gap)
- docs/ARCHITECTURE.md "Coverage", AGENTS.md project notes
- existing conventions in android/app/src/test/java/ch/sonensei/cockpit/contacts

## Test files changed

This round (committed by the parent in df6952c67 and 6e40c2dd5):

- `android/app/src/test/java/ch/sonensei/cockpit/contacts/ContactsRouteInAppTest.kt` (4 tests added)
- `android/app/src/test/java/ch/sonensei/cockpit/contacts/ContactEditScreenTest.kt` (3 tests added)
- `android/app/src/test/java/ch/sonensei/cockpit/contacts/ContactEditViewModelTest.kt` (1 test added)
- `android/app/src/test/java/ch/sonensei/cockpit/contacts/ContactsRepositoryTest.kt` (2 tests added)
- `android/app/src/test/java/ch/sonensei/cockpit/contacts/ContactsStringsTest.kt` (1 test added, 1 extended with the fixer's new strings)

Lint fix by the parent (6e40c2dd5): in ContactEditScreenTest `outside a Hilt activity the detail route ...`, lintDebug reported `ViewModelConstructorInComposable` because `ContactDetailViewModel(repo)` was built inside `setContent`. The view model is now built before `setContent` (line 330) and passed in. Every assertion is unchanged.

Earlier rounds (already committed in the task): ContactFormTest, ContactEditViewModelTest, ContactEditScreenTest, ContactsStringsTest (new); ContactsRepositoryTest, ContactsRouteInAppTest, ContactsScreensTest (extended); CaptureButtonTest and FabClearanceTest (the fixer updated their own-FAB copies for TW-3).

## Tests added or updated

Count: 11 added and 1 extended in this round, on top of the first round's 65 and the fixer's 3.

Added in this round:
- ContactsRouteInAppTest:
  - `a delete that hit a change made elsewhere reloads the contact, and Delete again uses its current version`: CR-5, the code-reviewer's P3 gap. The first DELETE gets a 412. The detail fetches the contact again, shows the other device's change and the delete-conflict text. The second Delete is sent with the reloaded contact (version 5) and returns to the list.
  - `a delete refused on one contact shows no error on another contact` (CR-4).
  - `what was typed into the form survives a configuration change` (CR-3, `scenario.recreate()` while editing).
  - `a save the server refused as invalid shows what the server found wrong in the form` (CR-7 end to end).
- ContactEditScreenTest:
  - `a save the server refused as invalid shows what it found wrong, or asks to check the fields` (CR-7)
  - `a delete that hit a change made elsewhere says the contact was reloaded, not that edits are kept` (CR-5)
  - `outside a Hilt activity the detail route opens the contact without edit actions instead of failing` (CR-1)
- ContactEditViewModelTest:
  - `a form started again while its save runs keeps the save, which then closes the form shown now` (CR-3: the branch in `start` taken while a save runs)
- ContactsRepositoryTest:
  - `a problem without usable field errors falls back to its detail, and nothing usable gives no detail` (CR-7)
  - `a refused update says which fields the server found wrong, other refusals carry no detail` (CR-7/CR-8)
- ContactsStringsTest:
  - `German contacts strings quote with the low-high quotes the rest of the app uses` (CR-9)
  - Extended: the required-key list now includes `contacts_save_invalid`, `contacts_save_invalid_detail` and `contacts_delete_conflict`.

## Requirement coverage

Command P (the parent's run at HEAD 6e40c2dd5, 2026-11-08, report written 22:30:17 +0100): `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport --continue`. Result: BUILD SUCCESSFUL in 3m 31s, EXIT 0, 4783 tests, 0 failures, lint 0 errors (`.tmp/and-gradle.log`; `testDebugUnitTest` and `lintDebug` actually ran, they were not up to date).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 create: name, organization or email required; blanks ignored; ISO birthday; request mapping (trim, drop empty rows, notes `trimEnd` per CR-9) | ContactFormTest (all 12) | P | Pass |
| AC1 edit: fields filled from the contact; photo id kept; a derived display name (from the name, organization or first email) is left empty (TW-1/CR-6) | ContactFormTest edit and display-name tests | P | Pass |
| AC1 repository create/update with If-Match; cleared fields sent as null; refusals by status; 400 Problem detail, its fallbacks, 403 without detail (CR-7/CR-8) | ContactsRepositoryTest create/update tests, `a refused save says which fields ...`, `a problem without usable field errors ...`, `a refused update says which fields ...` | P | Pass |
| AC1 view model: save, conflict, error mapping (400 maps to INVALID with detail), session kept across recreation and while a save runs (CR-3), cancel clears errors (TW-2) | ContactEditViewModelTest (all 18) | P | Pass |
| AC1 Compose form: repeatable emails, phones and addresses; banners, including the invalid-save detail (CR-7); disabled while saving | ContactEditScreenTest form tests | P | Pass |
| AC1 in-app: New/Save, Cancel, Edit/Save; New not covered by Capture (TW-3); no error after cancelling a failed save (TW-2); typed form survives recreation (CR-3); 400 detail shown (CR-7) | ContactsRouteInAppTest; CaptureButtonTest; FabClearanceTest | P | Pass |
| AC1 CR-1: the detail route outside Hilt shows the contact without Edit or Delete | BirthdayContactTest; ContactEditScreenTest `outside a Hilt activity ...` | P | Pass |
| AC2 delete with confirmation to the Trash; cancel keeps the contact; refusals and offline | ContactsRouteInAppTest delete tests; ContactsRepositoryTest delete tests; ContactEditViewModelTest delete tests; ContactEditScreenTest delete banners | P | Pass |
| AC2 CR-5 delete conflict: reload, a delete-specific text, retry with the current version | ContactsRouteInAppTest `a delete that hit a change made elsewhere reloads ...`; ContactEditScreenTest `a delete that hit a change made elsewhere says ...` | P | Pass |
| AC2 CR-4: a delete error on one contact does not show on another | ContactsRouteInAppTest `a delete refused on one contact ...` | P | Pass |
| AC3 source line, and a 403/405 message naming the source | ContactEditScreenTest source and refusal tests; ContactsRouteInAppTest `a delete refused by a synced source ...` | P | Pass |
| AC4 EN and DE strings with the same placeholders; German quotes „…“ (CR-9) | ContactsStringsTest (all 3) | P | Pass |
| AC5 ktlintCheck, testDebugUnitTest, lintDebug pass | whole Android suite | P | Pass |
| AC5 `sh scripts/check.sh` | whole project | Not run by me | Not run: Gradle is denied to this sub-agent; the parent runs `.taskcheck` |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport --continue` (run by the parent; Gradle is denied to this sub-agent, so no separate focused run was made, and the full run contains every contacts test)

Result:

Pass (4783 tests, 0 failures, including all contacts tests)

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport --continue` (run by the parent at HEAD 6e40c2dd5)

Result:

Pass (BUILD SUCCESSFUL, EXIT 0, 4783 tests, 0 failures, ktlint clean, lint 0 errors)

## .taskcheck

Result:

Not run by me (Gradle is denied to this sub-agent; the parent runs `sh scripts/check.sh`)

## Bugs found

No production defect found. TW-1, TW-2 and TW-3 from the first round were fixed in e7ff7d8f3, and their tests pass.

### Observation: an old delete error may show again on a reopened contact

Finding ID:

`TW-4`

Test:

None. No requirement defines what a reopened contact shows, so no test was written.

Production source:

`android/app/src/main/java/ch/sonensei/cockpit/contacts/ContactsScreen.kt:185` (activity-scoped `ContactEditViewModel` keyed `contact-edit:{id}`) and `:258` (`actionError = editState.error`)

Requirement:

AC2 (the action error explains why the last delete did not work)

Expected behavior:

Not defined. Possibly the detail shows no delete error when the user leaves a contact and opens it again.

Actual behavior:

From reading the code; not run. The per-contact edit view model lives as long as the activity, and Back does not clear `error`. A failed delete on Anna, then Back, then opening Anna again probably shows the old banner again. Other contacts are not affected (tested, CR-4).

Severity:

Low

Fix required:

`No — observation only; the parent decides whether it is in scope.`

## Coverage

Status: verified (fresh run on the final code and tests, exit 0)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, task-owned commits/file-scope source: and-contacts-edit. Base 51965a2123e13d9f5babc4cd3dbc23b26251e33b. HEAD 6e40c2dd59a72ecace01aadd803428115dc28754. Task commits come from the parent and were checked with `git diff --stat 51965a212 HEAD -- android`.
- Source/test snapshot identifier, including uncommitted inputs: commit 6e40c2dd5. At measurement time the working tree had no uncommitted source or test changes. Production code is identical to e7ff7d8f3 (empty diff).
- Included files:
  - `android/app/src/main/java/ch/sonensei/cockpit/contacts/{ContactForm,ContactEditScreen,ContactsRepository,ContactsViewModels,ContactsScreen,ContactsModels}.kt`
  - `android/app/src/main/java/ch/sonensei/cockpit/ui/navigation/CockpitDestination.kt` (the TW-3 change outside `touches`)
- Excluded files: `res/values/strings_contacts.xml` and `res/values-de/strings_contacts.xml`. They are resources and are not instrumented; ContactsStringsTest covers them. No renamed or deleted files.
- Provider and command: JaCoCo through AGP unit-test coverage (`createDebugUnitTestCoverageReport`). The parent ran `./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport --continue` in `android/`. Test selection: all debug unit tests (4783).
- Completion and exit status: report written 2026-11-08 22:30:17 +0100; BUILD SUCCESSFUL in 3m 31s; EXIT 0.
- Report: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 3dea1a290fbdd53190f3bd70aa32d506c6021783c93aa8ad97e8770056573b17.
- Fresh outputs, unchanged inputs: `testDebugUnitTest` ran in this build (it was not up to date). The report timestamp matches the run. No production change was made after it, and the test inputs are those of commit 6e40c2dd5.
- Final run after the latest test additions and production fixes: yes.

Values below are covered/total (percentage). The Statements column shows JaCoCo instructions, and Functions shows methods.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `contacts/ContactForm.kt` | 881/889 (99.1%) | 68/74 (91.9%) | 34/34 (100.0%) | 90/90 (100.0%) |
| `contacts/ContactEditScreen.kt` | 3273/3390 (96.5%) | 64/76 (84.2%) | 56/65 (86.2%) | 212/213 (99.5%) |
| `contacts/ContactsRepository.kt` | 632/658 (96.0%) | 57/60 (95.0%) | 30/30 (100.0%) | 60/65 (92.3%) |
| `contacts/ContactsViewModels.kt` | 1367/1378 (99.2%) | 106/112 (94.6%) | 53/53 (100.0%) | 152/152 (100.0%) |
| `contacts/ContactsScreen.kt` | 6212/6525 (95.2%) | 266/312 (85.3%) | 125/138 (90.6%) | 479/490 (97.8%) |
| `contacts/ContactsModels.kt` | 784/790 (99.2%) | 54/58 (93.1%) | 43/43 (100.0%) | 73/73 (100.0%) |
| `ui/navigation/CockpitDestination.kt` | 389/389 (100.0%) | 9/9 (100.0%) | 13/13 (100.0%) | 38/38 (100.0%) |
| Aggregate (summed counters, all 7 files; none unmeasured; string resources excluded) | 13538/14019 (96.6%) | 624/701 (89.0%) | 354/376 (94.1%) | 1104/1121 (98.5%) |

What this round's tests now reach:
- `ContactsScreen.kt:780` (the delete-conflict text) and the CONFLICT reload at :205-207
- `ContactsViewModels.kt:181-182` (`start` while a save runs)
- most `problemDetail` branches in `ContactsRepository.kt:138-150`

Lines that were uncovered in the run before these tests are now covered.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `ContactsRepository.kt:95-96, 103-105` | Cancellation rethrow; catch branches for IllegalArgumentException and DateTimeException | Defensive branches in older code. Reaching them needs a malformed generated model. Left uncovered |
| `ContactsRepository.kt:63, 126-127` partial | Paging loop limit; a non-success response with a body, a 2xx response without one | Older paging code. The 400 and non-400 cases are tested; the remaining combination is not material |
| `ContactsScreen.kt:210-212, 235-244` | Opening linked or related items, in the app or on the web, from the detail | Code from earlier tasks (and-links-panel); its behavior did not change here. Left uncovered |
| `ContactsScreen.kt:488`, `ContactEditScreen.kt:196`, and partial branches in composables | Closing braces of exhaustive `when` expressions; Compose default and skip branches | JaCoCo artifacts, not behavior |
| `ContactForm.kt:111-116` partial | `orEmpty()` on null address parts | Null and non-null values are covered in other fields. Not material |
| `ContactsViewModels.kt:213, 218, 248` partial | Conflict on a new contact (no base to reload); detail only for INVALID | A conflict on create cannot happen (POST has no If-Match). The detail mapping is tested for INVALID and FAILED |

## Unable to test

- `sh scripts/check.sh`: Gradle is denied to this sub-agent; the parent runs it.
- Real-device rotation, and the FAB overlap on real screen sizes: covered in Robolectric only.
- TW-4: no requirement defines the expected behavior; the parent decides.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. All fixes (CR-1, CR-3 to CR-9, TW-1 to TW-3) and every acceptance criterion have passing tests, including the code-reviewer's P3 test for delete-conflict reload and retry. TW-4 is a low-severity observation for the parent to judge.
