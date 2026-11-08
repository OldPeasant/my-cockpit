# Test Report

Date: 2026-11-08

Task: and-mail-compose-plus - Android compose: recipient autocomplete, attach from Files

Scope: final pass after the review fixes. `git diff e60758be211736938ded1f7c25c41aa767f66077..f1cb1c11f049d82438b0b61761d81e501cc27ddb` restricted to `android/app/src/main/java/ch/sonensei/cockpit/mail/compose`, its tests in `android/app/src/test/java/ch/sonensei/cockpit/mail/compose`, and `android/app/src/main/res/values{,-de}/strings_mail_compose.xml`. Task commits 8e01c0d7, 1a20e46d, bf05d554, f1cb1c11 (HEAD, review fixes CR-1 to CR-4 and the unpaired-quote fix). Branch main. The only uncommitted change is the test file this pass extended (see below).

Requirements:
- tasks/and-mail-compose-plus.md (acceptance criteria #1-#4 and the three recorded ASSUMPTIONs)
- reviews/and-mail-compose-plus/review.md (CR-1 to CR-4) and fixes.md (fixed behavior to protect)
- docs/ARCHITECTURE.md "Coverage", AGENTS.md

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/mail/compose/MailComposeRecipientsFilesReviewFixesTest.kt`

## Tests added or updated

Count: 2 (added; no existing test changed)

The existing task tests (MailComposeRecipientsFiles*Test.kt, 84 tests before this pass, 86 now) already covered each fixed behavior: CR-1 (focus leaving To/Cc hides suggestions, never-focused fields report no leave, typing Subject is no leave by itself), CR-2 (autosave/upload/Files attach during a search, strict save/send/leave), CR-3 (case-insensitive filtering, per-field filtering, no duplicate chip), CR-4 (chip body tap keeps, close icon removes only its chip, disabled while sending), and the unpaired-quote fix. Two CR-2 paths were not pinned and were added:

- `a fragment left out by an autosave is stored once it is finished as a chip`: after the partial autosave (draft UNSAVED), finishing the fragment as a chip makes the next autosave store both recipients and the draft becomes SAVED. Guards the `partial` flag against losing the fragment.
- `a field marked invalid by an explicit save stays checked by the autosave until it is edited`: after an explicit save marks To invalid, an autosave caused by a Subject edit still fails with INVALID_ADDRESS and creates nothing (no silent drop of the marked fragment).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| #1 To/Cc/Bcc suggest contacts and recent correspondents while typing; chosen recipients as chips | MailComposeRecipientsFilesTest (debounce, last lookup wins, per-field, failed lookup, lookup mapping), ...EdgeCasesTest (lookup cancel/stale, quoting, reply chips), ...ScreenTest / ...ScreenEdgeCasesTest (chips, suggestions, choose, Done), ...ReviewFixesTest (CR-2 autosave with fragment incl. 2 new tests, CR-3 filtering/duplicates), ...ReviewFixesScreenTest (CR-1 focus leave, CR-4 close icon) | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` | Pass |
| #2 "Attach from Files" opens a cockpit Files picker | MailComposeRecipientsFilesTest (browse, retry, pick adds by fileId, too large), ...EdgeCasesTest (navigation races, offline, discard, conflict, attachment removal), ...ScreenTest (attach menu, picker list/open/pick/up, empty/failed), ...ReviewFixesTest (pick during a recipient search) | same | Pass |
| #3 New strings in English and German | MailComposeRecipientsFilesStringsTest (3 tests); lintDebug (MissingTranslation) | same | Pass |
| #4 Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass | all of the above | same; `sh scripts/check.sh` | Android Gradle command: Pass (3871 tests, 0 failures, 0 skipped). `sh scripts/check.sh`: Not run to the end: the web step failed on this shell's Node.js v20.9.0 (see ".taskcheck"); the parent must run it |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.compose.MailComposeRecipientsFilesReviewFixesTest' -q`

Result:

Pass (11 tests, 0 failures, 0 errors, 0 skipped)

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport -q` (run twice: before adding tests at 16:32-16:39, and the final run on the final test snapshot, 16:40:46-16:46 +0100)

Result:

Pass. Exit 0, ktlintCheck and lintDebug clean, 3871 unit tests across `app/build/test-results/testDebugUnitTest/*.xml`, 0 failures, 0 errors, 0 skipped. Compiler warning only in an unrelated file (EventEditorViewModelExtrasTest.kt:133, unnecessary `!!`), outside this task.

## .taskcheck

Result:

Fail (environment, not this task). `sh scripts/check.sh` (16:47-16:57 +0100, exit 3): "tfp: completion gate tests" OK (22 tests), "server: ktlint + tests" passed, then "web: eslint + tests + build" stopped because the test-writer's shell has Node.js v20.9.0 and the Angular CLI requires v22.22.3 or newer ("The Angular CLI requires a minimum Node.js version..."). The task changes no web file. The script's `npm ci` step reinstalled `web/node_modules` from the unchanged lock file during that run. The Android part of the check is the same command as the full run above (`./gradlew ktlintCheck testDebugUnitTest lintDebug`), which passed. The parent worker must run `.taskcheck` in an environment with the project's Node version before `done`.

## Bugs found

None. No production defect was found in this pass; both new tests pass against f1cb1c11.

## Coverage

Status: verified (whole-file, Android unit tests, final run after the latest test additions).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (the JaCoCo report has line counters, but no intersection with the diff was done; numbers below include pre-existing code in these files and hits from pre-existing tests).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-mail-compose-plus; base e60758be211736938ded1f7c25c41aa767f66077; HEAD f1cb1c11f049d82438b0b61761d81e501cc27ddb; commits 8e01c0d7, 1a20e46d, bf05d554, f1cb1c11 and the file scope supplied by the parent worker, checked against `git diff --stat e60758be..HEAD` (only mail/compose, its tests, the two string files, reviews/).
- Source/test snapshot identifier (including uncommitted inputs): HEAD f1cb1c11 plus uncommitted `MailComposeRecipientsFilesReviewFixesTest.kt` sha256 20cfb0daad2b089329d5b21274b770f88ac9ddb4cb7739abcd93d164829cbb4d (same hash before and after the run; `git status` showed no other change).
- Exact included files (all under `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/`): ComposeLookup.kt, ComposeModels.kt, ComposeSession.kt, FilePicker.kt, MailComposeApi.kt, MailComposeRepository.kt, MailComposeScreen.kt, MailComposeViewModel.kt, RecipientInput.kt. Excluded: the two `strings_mail_compose.xml` files (resources, no executable code; covered by the strings test and lint), test files, reviews/. No renamed or deleted files. All 9 files were present in the report (none missing).
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`; `./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport -q` in `android/`; the whole debug unit-test suite.
- Run start/completion time and exit status: 2026-11-08 16:40:46 to about 16:46 +0100, exit 0.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` (mtime 2026-11-08 16:45:04 +0100, sha256 9d087d73e3f8551b1a939888185dd8743d17c4f68df26344d6de8eed145ce03c), `index.html` next to it. Counters read from the JaCoCo XML package `ch/sonensei/cockpit/mail/compose`, per sourcefile element.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report was regenerated by this run (new mtime and new hash; the earlier run's hash was 211b3c9b...), and the test-file hash and `git status` were unchanged after the run.
- Final run after latest test additions and production fixes: yes (no production change since f1cb1c11).

Values below use covered/total (percentage); "Statements" are JaCoCo instructions.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `ComposeLookup.kt` | 262/272 (96.3%) | 28/30 (93.3%) | 10/12 (83.3%) | 28/30 (93.3%) |
| `ComposeModels.kt` | 1209/1209 (100.0%) | 121/123 (98.4%) | 63/63 (100.0%) | 154/154 (100.0%) |
| `ComposeSession.kt` | 3278/3306 (99.2%) | 287/308 (93.2%) | 79/79 (100.0%) | 315/315 (100.0%) |
| `FilePicker.kt` | 924/946 (97.7%) | 26/32 (81.2%) | 28/33 (84.8%) | 82/82 (100.0%) |
| `MailComposeApi.kt` | 255/512 (49.8%) | N/A (0 branches) | 25/53 (47.2%) | 43/58 (74.1%) |
| `MailComposeRepository.kt` | 952/978 (97.3%) | 67/72 (93.1%) | 27/28 (96.4%) | 100/100 (100.0%) |
| `MailComposeScreen.kt` | 5210/5340 (97.6%) | 174/201 (86.6%) | 105/125 (84.0%) | 434/435 (99.8%) |
| `MailComposeViewModel.kt` | 1506/1527 (98.6%) | 85/104 (81.7%) | 77/77 (100.0%) | 152/152 (100.0%) |
| `RecipientInput.kt` | 1327/1349 (98.4%) | 62/68 (91.2%) | 27/28 (96.4%) | 131/131 (100.0%) |
| **Total (summed counters, 9 files, none missing or excluded)** | 14923/15439 (96.7%) | 850/938 (90.6%) | 441/498 (88.6%) | 1439/1457 (98.8%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailComposeApi.kt:102-170` | Generated `equals`/`hashCode`/`toString`/`copy`/serializer members of the DTO data classes; the Hilt `@Provides` | Non-material. The behavior that matters (JSON of `AttachmentRefDto` leaves out unset `partId`/`fileId`) is asserted in MailComposeRecipientsFilesTest `a save adding a file sends its id next to the kept parts` and MailComposeRepositoryTest |
| `ComposeLookup.kt:36,76` | `ComposeLookup.None.folder` (preview/test stub) and the Hilt `@Binds` module | Non-material wiring |
| `ComposeLookup.kt:53` (2 branches) | Whitespace-only or empty `email` after trim | Low risk; `drops entries without address` covers a missing email |
| `ComposeSession.kt:137,285,320,325` (1 branch each) | Autosave firing when only `partial` is set (never scheduled by design); `discarded` checked again inside upload/addFile after an earlier check; `content(strict=false)` returning null inside the addFile lock (an invalid finished recipient typed while the first save runs) | Defensive re-checks of races; the reachable variants are covered (discard during the file's save, invalid recipient blocks the file, partial autosave does not loop). Left uncovered |
| `ComposeSession.kt` other partial branches (61, 72, 91, 113, 131, 219, 273, 368, 393, 414, 420, 455, 456, 473, 488) | Pre-existing compose code in the same file (load, account choice, send, conflict) | Not in this task's behavior; not chased |
| `RecipientInput.kt:124-126` | Default value of the `onLeave` parameter (the screen always passes one) | Non-material |
| `RecipientInput.kt:184` (1 branch) | Focus report while already focused (no leave) | Non-material; leave/no-leave is covered by the CR-1 screen tests |
| `RecipientInput.kt:221` (3 branches) | Suggestion content description with/without name in semantics | Covered by the suggestion screen tests on content; the remaining branches are Kotlin null-handling bytecode |
| `MailComposeViewModel.kt:183` (2 branches) | `current?.value?.editable` null cases in `suggest` (no compose started) | Low risk; `a reply that is still being prepared looks nothing up` covers the not-editable case |
| `MailComposeViewModel.kt:204,231` | Picker open/pick guard null branches | Covered for the reachable case (`the picker does not open while a reply is still being prepared`) |
| `FilePicker.kt:42,50-54,128` | Default values of `FilePickerCallbacks` lambdas and `folderId` getter default; a `when` exhaustiveness branch | Non-material |
| `MailComposeScreen.kt:137-157` and other partials | Compose `@Preview` functions and pre-existing screen code | Non-material for this task |

## Unable to test

- Real device/emulator behavior (keyboard focus moves, IME Done on a physical keyboard) is tested only through Robolectric Compose tests; no instrumented tests were run. Not required by the criteria (they name unit/Compose tests).
- Live server responses of `GET /api/v1/contacts/autocomplete` and the Files listing were not exercised end to end; the lookup is tested against MockWebServer/mocks. No follow-up needed for this task.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. Two tests were added, and the Android check (`ktlintCheck testDebugUnitTest lintDebug`) and coverage passed. `sh scripts/check.sh` could not finish in the test-writer's shell: its web step needs a newer Node.js than this shell has (v20.9.0), and the task does not touch web. The parent worker must still run `.taskcheck`.
