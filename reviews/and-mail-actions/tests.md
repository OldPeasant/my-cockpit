# Test Report

Date: 2026-11-08

Task: and-mail-actions - Android mail: unread, flag, move, spam, multi-select

Scope: `git diff 0d22fac191a21a2445375034c4005d4688842244..8c3d6ce7b4fb317fa3990f6d759ddf54e2b52faa -- android` (task commits 52f8828c7, 9d132f730, 7ffe7bfcb, e888d6e33, 8c3d6ce7b, as given by the parent worker), plus one new untracked test file from this run. Branch `main`, HEAD 8c3d6ce7b4fb317fa3990f6d759ddf54e2b52faa. This is the final test-writer run on the final code. Earlier runs' tests are committed in 7ffe7bfcb and 8c3d6ce7b.

Requirements:
- `tasks/and-mail-actions.md`: 5 acceptance criteria and 3 ASSUMPTIONs (undo scope, bulk move limited to one account, multi-select only in the mailbox list and spam in the read view overflow menu)
- `docs/ARCHITECTURE.md` "Coverage" and "Code conventions" (en+de strings, lint `MissingTranslation`)
- `AGENTS.md` (Android check command, test data is fictitious)
- `reviews/and-mail-actions/fixes.md` and `review.md` (CR-1, CR-2, T-1 to T-3 regressions)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailActionsInAppTest.kt` (new, untracked, sha256 `006dbe14143fb7d1ed217f799d4b621fc8258a8eb1b0fec546d9df24cc2f6eeb`)

No existing test was changed, weakened, skipped or deleted. No production code was changed.

## Tests added or updated

Count: 4 (all in `MailActionsInAppTest`)

Gap closed: the existing tests (452 in `mail.list` + `mail.read`) check the list view model, the read view model, the repositories and both screens (`MailListContent`, `MailReadContent`, `MailReadRoute` with fake callbacks), each on its own. No test ran the new wiring in `MailRoute`, where `onMarked = viewModel::marked`, `accounts = state.accounts` and the selection/undo callbacks connect the real `MailListViewModel` and `MailReadViewModel`. The new Hilt + Robolectric test launches `MainActivity` with mocked `MailRepository` and `MailReadRepository`. The mocked reloads always return the old state, so the list can only show a change if the action actually reached it.

- `mark unread in the read view returns to the list, which shows the mail unread again`: mail 1 starts read. More, then Mark unread, closes the read view by itself. The list shows 2 unread dots, `reader.act(UNREAD)` is sent once, and `markRead` is never sent.
- `a flag set in the read view shows on the list row after going back`
- `the read view's move picker offers the other folders of the list's account, and the moved mail leaves the list`: the picker gets the list's folders. It shows Projects but not the INBOX the mail is in, sends MOVE with the target, goes on to the next mail (the existing behaviour after a removal), and after back the list no longer shows the mail.
- `a long press selects rows, bulk archive takes them out, and Undo moves them back to their folder`: a long press, then a tap, gives "2 selected". Bulk Archive sends `actAll([1,2], ARCHIVE)`, the rows leave and the bar closes. The snackbar "2 mails archived" Undo sends `actAll([1,2], MOVE, inbox)` and the rows come back.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Read view menu: mark unread returns to the list | `MailReadActionsViewModelTest` (mark unread asks the server and then returns..., waits for the mark read of the opening, refused mark unread stays open), `MailReadMenuScreenTest` (the route's mark unread reports UNREAD and closes; the route's refused unread stays open), new `MailActionsInAppTest` (mark unread in the read view returns to the list...) | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` | Pass |
| AC1 flag/unflag | `MailReadActionsViewModelTest` (flag shows at once..., refused flag taken back, unflag/offline unflag, same change twice, answer for a closed mail), `MailReadMenuScreenTest` (flagged mail offers remove flag; route toggles and reports), new in-app flag test | same | Pass |
| AC1 move with a folder picker of the same account | `MailReadActionsViewModelTest` (move sends target and closes; refused move; move without folder not sent), `MailReadMenuScreenTest` (picker of the account's other folders, cancel, disabled without targets, `moveTargets`, route moves), `MailReadActionsRepositoryTest` (target folder on the wire only for MOVE), new in-app move test | same | Pass |
| AC1 spam in the menu | `MailReadMenuScreenTest` (menu offers..., spam asks for its action), `MailReadSpamGermanTest`, `MailReadScreenTest` | same | Pass |
| AC2 Long press starts multi-select | `MailSelectionScreenTest` (long press asks to select, toggle while selecting, bar, back/close end selection), `MailActionsViewModelTest` (selection start/add/remove, ends on mailbox switch/search, reload keeps listed), new in-app bulk test | same | Pass |
| AC2 bulk archive/delete/spam/read/unread/flag/move | `MailActionsViewModelTest` (bulk mark, bulk archive/delete/spam, flag/unflag, Archive folder, move with folder, move restrictions/one account, partial failures, offline/refused), `MailSelectionScreenTest` (bulk buttons, unread/unflag variants, spam in overflow, move picker, two accounts cannot move), `MailActionsRepositoryTest` (wire format, batching at 1000, partial results, failures), new in-app bulk archive test | same | Pass |
| AC3 swipe actions stay; optimistic updates with undo | `MailSelectionScreenTest` (swipes rest while selecting and work again afterwards; snackbar Undo; timeout dismiss; per-action text; failures reported), `MailActionsViewModelTest` (a swipe offers its undo; undo of marks and moves incl. CR-1/CR-2/T-1/T-2/T-3 regressions, Trash delete and unknown folder offer no undo, reverse-order answers, reload overtaking, mailbox switch), existing swipe tests in `MailListViewModelTest`/`MailListScreenTest`, new in-app undo test | same | Pass |
| AC4 en + de strings | `MailActionsGermanTest` (selection bar, move picker, undo snackbar, failed undo, read view menu in German), `MailReadSpamGermanTest`. `lintDebug` (`MissingTranslation`) passed. A key comparison of `values/strings_mail.xml` and `values-de/strings_mail.xml` found 79 keys each and no difference. | same, plus a Python key-set comparison (`python3 -I -c ...`) | Pass |
| AC5 tests cover the new behavior; Android check and `sh scripts/check.sh` pass | all of the above | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport`; `sh scripts/check.sh` | Pass |

## Focused test execution

Command:

`android/gradlew -p android testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.list.MailActionsInAppTest'`

Result:

Pass: 4 tests, 0 failures, 0 errors, 0 skipped. The first attempt failed in the move test because the test expected the list to show after the move. The read view opens the next mail after any removal, an existing behaviour from the navigation task. The test was corrected to expect that, then press back and check the list.

## Full test execution

Command:

`android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` (run from the clone root, which is equivalent to `cd android && ./gradlew ...`). `testDebugUnitTest` re-executed: it was not up to date, because the new test file changed its inputs.

Result:

Pass: BUILD SUCCESSFUL in 4m 14s (12:06:28 to 12:10:44 +01:00). JUnit XML: 3173 tests, 0 failures, 0 errors, 0 skipped. Of these, 452 are in `mail.list` and `mail.read`. ktlintCheck and lintDebug both passed.

## .taskcheck

Result:

Pass: `sh scripts/check.sh` exit 0, "== all checks passed" (12:11:26 to 12:13:17 +01:00, about 1 min 51 s). The web build printed its existing bundle-budget warning, which has nothing to do with this task.

## Bugs found

None. No production defect was found. Every new and existing test passes on the final code.

## Coverage

Status: verified (fresh run on the final production code plus the new test).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured. The JaCoCo XML per-line entries of each file were intersected with the added/modified lines of `git diff -U0 0d22fac19..HEAD` (lines that JaCoCo does not report as executable are not counted). Result: 477/477 changed executable lines covered (100.0%). The branches on lines containing a changed line are 332/349 (95.1%). This is a line-level approximation: JaCoCo reports branches per line, not per changed token.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-mail-actions`. Base 0d22fac191a21a2445375034c4005d4688842244 and commits 52f8828c7, 9d132f730, 7ffe7bfcb, e888d6e33, 8c3d6ce7b come from the parent worker. HEAD is 8c3d6ce7b4fb317fa3990f6d759ddf54e2b52faa. The file scope is `git diff --name-only 0d22fac19..HEAD -- android/app/src/main`, which matches the task's `touches` list.
- Source/test snapshot identifier (including uncommitted inputs): `git ls-files -s android/app/src | sha256sum` = `d0b08adf8efcaf4bb7386605b202ec325b37e180a701ea4df25e9020726ea3be`, the same before and after the run. The only uncommitted input is the new test file (sha256 `006dbe14...6eeb`, see above), also unchanged after the run.
- Exact included files: `mail/list/MailApi.kt`, `MailListViewModel.kt`, `MailModels.kt`, `MailMoveDialog.kt`, `MailRepository.kt`, `MailScreen.kt`, `mail/read/MailReadRepository.kt`, `MailReadScreen.kt`, `MailReadViewModel.kt` (all under `android/app/src/main/java/ch/sonensei/cockpit/`). Excluded: `res/values/strings_mail.xml` and `res/values-de/strings_mail.xml` (resources, no executable code; checked by lint and the German tests). No renamed or deleted files. All 9 files are in the report.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin and AGP `enableUnitTestCoverage` (the JaCoCo version is the toolchain default, not pinned in `android/app/build.gradle.kts`). Command `android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport`, run from the clone root, over the full Android debug unit test suite.
- Run start/completion time and exit status: 2026-11-08T12:06:28+01:00 to 12:10:44+01:00, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` (mtime 12:10:07, sha256 `e682b816ee4fbd75ef7973d2b91884bda1893779992b2d68f07140ae8d6d8124`, JaCoCo session `dell26-c36bb736`) and `index.html` in the same folder. These files are Git-ignored.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The test task re-executed because the new test changed its inputs, the report mtime falls within the run, and the snapshot hashes were identical before and after.
- Final run after latest test additions and production fixes: yes. No production code changed after e888d6e33/8c3d6ce7b, and the run includes this report's new test.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. JaCoCo has no separate statement counter: "Statements" shows JaCoCo INSTRUCTION counters.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `mail/list/MailApi.kt` | 663/1109 (59.8%) | N/A (0/0) | 82/115 (71.3%) | 91/126 (72.2%) |
| `mail/list/MailListViewModel.kt` | 4216/4261 (98.9%) | 340/356 (95.5%) | 109/109 (100.0%) | 393/393 (100.0%) |
| `mail/list/MailModels.kt` | 990/992 (99.8%) | 97/98 (99.0%) | 63/63 (100.0%) | 116/116 (100.0%) |
| `mail/list/MailMoveDialog.kt` | 245/245 (100.0%) | N/A (0/0) | 11/11 (100.0%) | 28/28 (100.0%) |
| `mail/list/MailRepository.kt` | 867/871 (99.5%) | 62/64 (96.9%) | 27/27 (100.0%) | 101/101 (100.0%) |
| `mail/list/MailScreen.kt` | 7911/8092 (97.8%) | 284/322 (88.2%) | 167/197 (84.8%) | 631/639 (98.7%) |
| `mail/read/MailReadRepository.kt` | 856/871 (98.3%) | 75/76 (98.7%) | 36/38 (94.7%) | 71/71 (100.0%) |
| `mail/read/MailReadScreen.kt` | 4577/4688 (97.6%) | 211/233 (90.6%) | 91/104 (87.5%) | 338/342 (98.8%) |
| `mail/read/MailReadViewModel.kt` | 1301/1308 (99.5%) | 105/108 (97.2%) | 57/57 (100.0%) | 127/127 (100.0%) |
| **Aggregate (summed counters, 9 of 9 files, no exclusions or unmeasured files)** | 21626/22437 (96.4%) | 1174/1257 (93.4%) | 643/721 (89.2%) | 1896/1943 (97.6%) |

These are whole-file numbers. They include code that existed before the task, and hits from tests that existed before it.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `mail/list/MailApi.kt`: 71-240 (serializer/DTO lines) | Generated `@Serializable` code of DTOs and fields that existed before the task (unused fields, copy/equals). The 3 changed lines of this task (`targetFolderId`, `flagged`) are covered. | Not material. The wire format is tested in `MailActionsRepositoryTest` and `MailReadActionsRepositoryTest`. |
| `mail/list/MailListViewModel.kt:352` (`checkNotNull(it.folderId)` null branch) | Unreachable: `undoFor` offers no undo for a mail without a known folder, which is tested ("a move of a mail in no known folder offers no undo"). | Defensive code, no test added. |
| `mail/list/MailListViewModel.kt` 121, 136, 375, 455, 457, 516, 520, 569, 574, 625, 670, 690, 691 | Mostly code that existed before the task (folder lookup, search, folder edit, reload). On the task's lines, 375 is the `search?.let` null case of `marked` and 670 is the `positions[m.id] == null` fallback of `restored`. Every caller passes positions for every mail, so that fallback is unreachable. | Not material, no test added. |
| `mail/list/MailScreen.kt` 145, 212, 315, 557, 605, 642, 902, 976 and branches at 117-130, 346-357, 388, 458, 516, 532, 839, ... | Compose default-argument bitmask branches, `Unit`/closing-brace lines of exhaustive `when`s, and `mails.isNotEmpty()` at 458 (the bar only shows while something is selected). Lines 145 and 212 are the `MailRoute` effects that existed before the task. | Not material. The behaviour is covered by `MailSelectionScreenTest` and the new `MailActionsInAppTest`. |
| `mail/read/MailReadScreen.kt` 388, 393, 397, 418 and branches 291, 311, 359, 362, ... | Closing lines of exhaustive `when`s in notice texts, and Compose default arguments. The `open && enabled` false case at 291 is covered by "while an action runs the menu cannot be opened" in its observable form. | Not material. |
| `mail/read/MailReadViewModel.kt:156,164` (`it.message?.copy` with `message == null`) | Unreachable: the menu is disabled without a message, and `run` acts only on an opened mail. | Defensive, no test added. |
| `mail/read/MailReadRepository.kt:126` (unreadable `folderId` string) | A malformed folder id from the server becomes `null`, so the move picker then lists all folders of the account. Low risk. The same mapping in the list is tested ("a list entry reads its folder, an unreadable folder id is unknown"). | Left uncovered; low risk, optional follow-up. |

## Unable to test

- Real device gestures (a physical long press and swipe on hardware, or the snackbar on a device) were not run. They are exercised through Robolectric Compose (`performTouchInput { longClick() }`, swipe semantics actions). The criteria name unit/Compose tests, so no follow-up is needed for this task.
- A live server round-trip (the real `/mail/actions` endpoint) is out of scope for Android unit tests. The wire format is checked against a MockWebServer in the repository tests.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. One in-app integration test class (4 tests) was added to cover the list/read wiring through the real view models. The full Android check (3173 tests), lint and `sh scripts/check.sh` all pass. 477/477 changed executable lines are covered.
