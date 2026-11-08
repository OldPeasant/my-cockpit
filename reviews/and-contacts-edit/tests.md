# Test Report

Date: 2026-11-08

Task: and-contacts-edit - Android contacts: create, edit, delete

Scope: base 51965a2123e13d9f5babc4cd3dbc23b26251e33b (main), task commits 52ac12361, b50dbaa4f, f1318e81e, ee30b903b, e7ff7d8f3 (fixer, final code), merged as HEAD 6f8b9e0ec04f76c443d426ea1f336f32eea2649f. Scope source: the parent's commit list, checked with `git diff --stat 51965a212 HEAD -- android` (18 files). `git diff e7ff7d8f3 HEAD -- android/app/src/main` is empty, so the production code at HEAD is the fixer's final code. Uncommitted inputs: only the five test files listed below (this re-run's additions).

Requirements:
- tasks/and-contacts-edit.md acceptance criteria 1-5 and its recorded assumptions (source line plus 403/405 message; ISO birthday text; photo id carried through; Contacts in `withOwnFab`; CR-8 accepted)
- reviews/and-contacts-edit/fixes.md (fixes CR-1, CR-3 to CR-9, TW-1 to TW-3, F-1) and the parent's request for the delete-conflict test (code-reviewer P3 gap)
- docs/ARCHITECTURE.md "Coverage", AGENTS.md project notes
- existing conventions in android/app/src/test/java/ch/sonensei/cockpit/contacts

## Test files changed

In this re-run (uncommitted, for the parent to run and commit):

- `android/app/src/test/java/ch/sonensei/cockpit/contacts/ContactsRouteInAppTest.kt` (4 tests added)
- `android/app/src/test/java/ch/sonensei/cockpit/contacts/ContactEditScreenTest.kt` (3 tests added)
- `android/app/src/test/java/ch/sonensei/cockpit/contacts/ContactEditViewModelTest.kt` (1 test added)
- `android/app/src/test/java/ch/sonensei/cockpit/contacts/ContactsRepositoryTest.kt` (2 tests added)
- `android/app/src/test/java/ch/sonensei/cockpit/contacts/ContactsStringsTest.kt` (1 test added, 1 extended with the fixer's new strings)

Already committed in the task (earlier test-writer and fixer rounds): ContactFormTest, ContactEditViewModelTest, ContactEditScreenTest, ContactsStringsTest (new), ContactsRepositoryTest, ContactsRouteInAppTest, ContactsScreensTest (extended); CaptureButtonTest and FabClearanceTest (own-FAB copies updated by the fixer for TW-3).

## Tests added or updated

Count: 11 added and 1 extended in this re-run (on top of the 65 from the first round and 3 from the fixer).

Added in this re-run:
- ContactsRouteInAppTest: `a delete that hit a change made elsewhere reloads the contact, and Delete again uses its current version` (CR-5, the code-reviewer's P3 gap: the first DELETE answers 412, the detail fetches the contact again and shows the other device's change, the delete-conflict text appears, and the second Delete is sent with the reloaded contact, version 5, then returns to the list); `a delete refused on one contact shows no error on another contact` (CR-4); `what was typed into the form survives a configuration change` (CR-3, `scenario.recreate()` while editing); `a save the server refused as invalid shows what the server found wrong in the form` (CR-7 end to end)
- ContactEditScreenTest: `a save the server refused as invalid shows what it found wrong, or asks to check the fields` (CR-7, with and without detail); `a delete that hit a change made elsewhere says the contact was reloaded, not that edits are kept` (CR-5 text); `outside a Hilt activity the detail route opens the contact without edit actions instead of failing` (CR-1)
- ContactEditViewModelTest: `a form started again while its save runs keeps the save, which then closes the form shown now` (CR-3, the `start` while saving branch, ContactsViewModels.kt:179-183, which no test reached)
- ContactsRepositoryTest: `a problem without usable field errors falls back to its detail, and nothing usable gives no detail` (CR-7 `problemDetail` branches); `a refused update says which fields the server found wrong, other refusals carry no detail` (CR-7/CR-8: a 400 on PATCH such as a gone `photoFileId` carries the field error, a 403 body is not read)
- ContactsStringsTest: `German contacts strings quote with the low-high quotes the rest of the app uses` (CR-9); the required-keys list now includes `contacts_save_invalid`, `contacts_save_invalid_detail`, `contacts_delete_conflict`, and `contacts_save_invalid_detail` is checked for its `%1$s` placeholder

## Requirement coverage

Command P (the parent's run, 2026-11-08 22:17 +0100): `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport --continue` -> BUILD SUCCESSFUL, EXIT 0 (`.tmp/and-gradle.log`). `testDebugUnitTest` was UP-TO-DATE there, so its result is the earlier execution on the same inputs (final production code, tests before this re-run's additions). It does not include the 11 tests added in this re-run.

Command N (needed for the new tests): `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.contacts.*' createDebugUnitTestCoverageReport`. Not run by me: Gradle is denied to this sub-agent. The parent must run it.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 create: name/organization/email required, blanks ignored, ISO birthday, request mapping (trim, drop empty rows, notes `trimEnd` per CR-9) | ContactFormTest (all 12) | P | Pass |
| AC1 edit: fields filled from the contact, photo id kept, derived display name (name, organization, first email) left empty (TW-1/CR-6) | ContactFormTest `editing a contact fills every field and keeps its photo`, `a display name derived from the name is left empty, one set by hand is kept`, `a display name the server derived from the organization or the first email is left empty, like on the web` | P | Pass |
| AC1 repository create/update with If-Match, cleared fields as null, refusals by status, 400 Problem detail (CR-7) | ContactsRepositoryTest create/update/`a refused save says which fields the server found wrong` | P | Pass |
| AC1 CR-7/CR-8 detail parsing fallbacks; a 400 on PATCH carries the field error, a 403 does not | ContactsRepositoryTest `a problem without usable field errors ...`, `a refused update says which fields ...` | N | Not run: Gradle denied to the test-writer; parent runs N |
| AC1 view model save/conflict/error mapping incl. 400 to INVALID with detail, session keeping (CR-3), cancel clears errors (TW-2) | ContactEditViewModelTest (17 committed tests) | P | Pass |
| AC1 CR-3 start while a save runs keeps the save and adopts the new session | ContactEditViewModelTest `a form started again while its save runs ...` | N | Not run: Gradle denied; parent runs N |
| AC1 Compose form with repeatable emails, phones, addresses; banners; disabled while saving | ContactEditScreenTest form tests | P | Pass |
| AC1 CR-7 banner shows the server's detail, or the generic invalid text | ContactEditScreenTest `a save the server refused as invalid ...`; ContactsRouteInAppTest `a save the server refused as invalid shows what the server found wrong in the form` | N | Not run: Gradle denied; parent runs N |
| AC1 in-app New/Save, Cancel, Edit/Save; New not covered by Capture (TW-3); cancel after failed save leaves no error (TW-2) | ContactsRouteInAppTest New/Cancel/Edit tests, `the New contact button is not covered by the shell's capture button`, `cancelling the form after a failed save leaves no error on the detail`; CaptureButtonTest, FabClearanceTest | P | Pass |
| AC1 CR-3 typed form survives an activity recreation | ContactsRouteInAppTest `what was typed into the form survives a configuration change` | N | Not run: Gradle denied; parent runs N |
| AC1 CR-1 detail route outside Hilt shows the contact without Edit/Delete | BirthdayContactTest (existing, plain ComponentActivity); ContactEditScreenTest `outside a Hilt activity the detail route ...` | P (BirthdayContactTest), N (new test) | Pass (BirthdayContactTest); new test Not run: Gradle denied |
| AC2 delete with confirmation to the Trash, cancel keeps it, refusals and offline | ContactsRouteInAppTest delete tests; ContactsRepositoryTest delete tests; ContactEditViewModelTest delete tests; ContactEditScreenTest delete banners | P | Pass |
| AC2 CR-5 delete conflict: reload, delete-specific text, retry with the current version | ContactsRouteInAppTest `a delete that hit a change made elsewhere reloads the contact, and Delete again uses its current version`; ContactEditScreenTest `a delete that hit a change made elsewhere says the contact was reloaded ...` | N | Not run: Gradle denied; parent runs N |
| AC2 CR-4 a delete error on one contact does not show on another | ContactsRouteInAppTest `a delete refused on one contact shows no error on another contact` | N | Not run: Gradle denied; parent runs N |
| AC3 source line and 403/405 message naming the source | ContactEditScreenTest source/refusal tests; ContactsRouteInAppTest `a delete refused by a synced source ...` | P | Pass |
| AC4 EN + DE strings, same placeholders | ContactsStringsTest (2 committed tests) | P | Pass |
| AC4 CR-9 German quotes „…“; new fixer strings present in both languages | ContactsStringsTest `German contacts strings quote with ...` and the extended key list | N | Not run: Gradle denied; parent runs N |
| AC5 ktlintCheck, testDebugUnitTest, lintDebug pass | whole suite | P | Pass for the code and tests before this re-run; must be repeated with the new tests (N plus full suite) |
| AC5 `sh scripts/check.sh` | whole project | Not run by me | Not run: Gradle denied; the parent runs `.taskcheck` |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.contacts.*'`

Result:

Not run: Gradle is denied to this sub-agent (permission denial on `./gradlew`). The new tests were checked by reading only: imports added, no line over 140 characters, the multiline-assignment style of ktlint_official kept. The parent must run it, including ktlintCheck, before relying on them.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport --continue` (run by the parent)

Result:

Pass (EXIT 0, 2026-11-08 22:17 +0100) for the final production code and the tests committed before this re-run. Not yet run with this re-run's 11 new tests.

## .taskcheck

Result:

Not run by me (Gradle denied; the parent runs `sh scripts/check.sh`).

## Bugs found

No new production defect was confirmed: I could not execute the new tests. Reading the code, I expect them to pass. TW-1, TW-2 and TW-3 from the first round are fixed in e7ff7d8f3, and their tests passed in command P.

### Observation (not a failing test, for the parent to judge)

Finding ID:

`TW-4`

Test:

None (not written: no requirement says what a reopened contact shows)

Production source:

`android/app/src/main/java/ch/sonensei/cockpit/contacts/ContactsScreen.kt:185` (activity-scoped `ContactEditViewModel` keyed `contact-edit:{id}`) and `:258` (`actionError = editState.error`)

Requirement:

AC2 (the action error is "why the last delete did not work").

Expected behavior:

Unclear. Possibly no delete error when the user leaves a contact and opens it again.

Actual behavior (from reading the code, not run):

The per-contact edit view model lives as long as the activity, and nothing clears `error` when the detail is left with Back. If a delete of Anna fails (for example 403), Back, then Anna is opened again, the old "could not be deleted" or "comes from Google" banner probably shows again. Another contact is not affected (that is now tested, CR-4).

Severity:

Low

Fix required:

`No — observation only; the parent decides whether it is in scope.`

## Coverage

Status: partial. Fresh JaCoCo data for the final production code, but measured before this re-run's 11 tests. The parent must run command N or the full suite again to get final numbers. The values below are not final sign-off coverage.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-contacts-edit; base 51965a2123e13d9f5babc4cd3dbc23b26251e33b; HEAD 6f8b9e0ec04f76c443d426ea1f336f32eea2649f; task commits from the parent, checked with `git diff --stat 51965a212 HEAD -- android`.
- Source/test snapshot identifier (including uncommitted inputs): production code = commit e7ff7d8f3 (`git diff e7ff7d8f3 HEAD -- android/app/src/main` is empty). The measured tests are the committed tests at HEAD. The five uncommitted test files of this re-run are not in this measurement.
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/contacts/{ContactForm,ContactEditScreen,ContactsRepository,ContactsViewModels,ContactsScreen,ContactsModels}.kt` and `android/app/src/main/java/ch/sonensei/cockpit/ui/navigation/CockpitDestination.kt` (TW-3 change outside touches). Excluded: `res/values/strings_contacts.xml`, `res/values-de/strings_contacts.xml` (resources, not instrumented; covered by ContactsStringsTest). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo through AGP unit-test coverage (`createDebugUnitTestCoverageReport`); `./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport --continue` in `android/`, run by the parent; all unit tests (testDebugUnitTest UP-TO-DATE, so the execution data is from the last real run on the same inputs).
- Run start/completion time and exit status: completed 2026-11-08 22:17:24 +0100 (log `.tmp/and-gradle.log`, BUILD SUCCESSFUL in 16s), EXIT 0.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, written 2026-11-08 22:17:24.54 +0100, sha256 4440f0a6fac0b555e443de949411d68881955592515d1ffa494427e3d8400515.
- Evidence outputs are fresh and inputs stayed unchanged: the report was written by this run, and production code has not changed since e7ff7d8f3. The test inputs changed after the run (this re-run's additions), so the numbers are a lower bound for the final tests.
- Final run after latest test additions and production fixes: no. After the latest production fixes, yes. After this re-run's test additions, no, because Gradle is denied to me. The parent must run it.

Values below use covered/total (percentage). JaCoCo INSTRUCTION is in the Statements column, METHOD in Functions.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `contacts/ContactForm.kt` | 881/889 (99.1%) | 68/74 (91.9%) | 34/34 (100.0%) | 90/90 (100.0%) |
| `contacts/ContactEditScreen.kt` | 3242/3390 (95.6%) | 62/76 (81.6%) | 56/65 (86.2%) | 212/213 (99.5%) |
| `contacts/ContactsRepository.kt` | 616/658 (93.6%) | 45/60 (75.0%) | 30/30 (100.0%) | 60/65 (92.3%) |
| `contacts/ContactsViewModels.kt` | 1348/1378 (97.8%) | 105/112 (93.8%) | 53/53 (100.0%) | 150/152 (98.7%) |
| `contacts/ContactsScreen.kt` | 6189/6525 (94.9%) | 261/312 (83.7%) | 125/138 (90.6%) | 478/490 (97.6%) |
| `contacts/ContactsModels.kt` | 784/790 (99.2%) | 54/58 (93.1%) | 43/43 (100.0%) | 73/73 (100.0%) |
| `ui/navigation/CockpitDestination.kt` | 389/389 (100.0%) | 9/9 (100.0%) | 13/13 (100.0%) | 38/38 (100.0%) |
| Aggregate (summed counters, all 7 files; none unmeasured; string resources excluded) | 13449/14019 (95.9%) | 604/701 (86.2%) | 354/376 (94.1%) | 1101/1121 (98.2%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `ContactsScreen.kt:780` and the `CONFLICT` branch of the `LaunchedEffect` at :205-207 | CR-5 delete-conflict text and reload | Added ContactEditScreenTest `a delete that hit a change made elsewhere says ...` and ContactsRouteInAppTest `a delete that hit a change made elsewhere reloads the contact, ...` (not yet run) |
| `ContactsViewModels.kt:181-182` (`start` while saving) | CR-3: a recreated form must not restart a running save | Added ContactEditViewModelTest `a form started again while its save runs ...` (not yet run) |
| `ContactsRepository.kt:126-149` partial branches (`problemDetail`, 400-only body read) | CR-7/CR-8 server detail | Added ContactsRepositoryTest `a problem without usable field errors ...` and `a refused update says ...` (not yet run) |
| `ContactsRepository.kt:95-96, 103-105` | Cancellation rethrow; IllegalArgumentException/DateTimeException catch arms | Defensive arms of existing code; not reachable without a malformed generated model. Left uncovered |
| `ContactsScreen.kt:210-212, 235-244` | Opening linked/related items in-app or on the web from the detail | Code from earlier tasks (and-links-panel), not changed in behavior here. Left uncovered |
| `ContactsScreen.kt:488`, `ContactEditScreen.kt:196` and partial branches in composables | Closing braces of exhaustive `when`, Compose default and skip branches | JaCoCo artifacts, not behavior |
| `ContactForm.kt:111-116` partial | `orEmpty()` on null address parts | Covered for both null and non-null values in other fields. Not material |

## Unable to test

- Running any of the 11 new tests, ktlintCheck on them, the full `testDebugUnitTest`, `lintDebug` and `sh scripts/check.sh`: Gradle is denied to this sub-agent. Follow-up: the parent runs `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport --continue` and `sh scripts/check.sh`. If a new test fails, the parent should check whether the test is wrong or the code is, and must not delete it without that analysis.
- Real device rotation and the FAB overlap on real device sizes: covered in Robolectric only.
- TW-4: no requirement defines it; the parent decides.

## Summary

Meaningful task behavior remains untested and requires follow-up: tests were added for every remaining gap from the fixes (CR-1, CR-3, CR-4, CR-5 with the reviewer's P3 delete-conflict retry, CR-7, CR-8, CR-9), but Gradle is denied to me, so they have not run yet. The parent must run them and refresh the coverage numbers. The final production code passed the full suite and lint in the parent's run, and no production defect was confirmed (TW-4 is a low-severity observation).
