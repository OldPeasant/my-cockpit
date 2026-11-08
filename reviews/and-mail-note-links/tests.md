# Test Report

Date: 2026-11-08

Task: and-mail-note-links - Android mail: create note and linked items

Scope: final revision of the task, `git diff 49b1d117ae2928436695d299aa85451676ed42f4..f2c4f9e82fac5e53e74d5891d092872225f167e5 -- android` (base and final commit supplied by the parent worker; HEAD on branch main is f2c4f9e82fac5e53e74d5891d092872225f167e5, read from `.git/refs/heads/main`). This is a re-run after the fix of review finding CR-2 ("Create note" moved from the toolbar into the More menu, see `reviews/and-mail-note-links/fixes.md`). The helper had no shell access in this run (Bash was denied), so the file scope below comes from the parent's description, the first run's `git diff --name-status` and `fixes.md`, checked against the current file contents with the Read tool. Uncommitted inputs: none from this helper except this report; no test file was added or changed in this run.

Requirements:
- `tasks/and-mail-note-links.md` acceptance criteria and recorded assumptions, in particular the latest one: "Create note" is the first item of the read view's More menu (supersedes the toolbar-icon assumption); the links panel sits below the body, capped at 240dp
- `reviews/and-mail-note-links/fixes.md` (CR-1, CR-2) and the parent worker's instructions
- Existing conventions in `android/app/src/test/java/ch/sonensei/cockpit/mail/read/`

Changed production files in scope:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadScreen.kt` (MoreMenu with "Create note" as first item, tag `MailReadTags.gtd(MailGtdAction.NOTE)`; `MenuItem` takes a String tag; NOTE created/failed notices; `linksPanel` slot under tag `MailReadTags.LINKS`; route wiring of `LinkedItemsSection`, `mailLinks`, reload after a Created notice, linked-item overlay)
- `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadRepository.kt` (`MailGtdAction.NOTE` mapped to `MailApi.toNote`)
- `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailApi.kt` (Retrofit method `toNote`, POST `api/v1/mail/messages/{id}/to-note`; declarative)
- `android/app/src/main/res/values/strings_mail.xml`, `android/app/src/main/res/values-de/strings_mail.xml` (resources)

## Test files changed

No test file was changed by the helper in this run. The task's test files at the final revision (written in the first run, adapted by the fixer for CR-2) are:

- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadNoteLinksTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadNoteGermanTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadNoteViewModelTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadRepositoryTest.kt` (task commit added "create note posts to to-note and returns the note's title")

## Tests added or updated

Count: 0 in this run (no important gap found that unit/Compose tests can close without Hilt test infrastructure).

Review of the final tests against the final code:
- `MailReadNoteLinksTest` now opens the menu (`MailReadTags.MORE`) before using the note item. This matches `MoreMenu`: the item is the first `MenuItem`, calls `callbacks.gtd(MailGtdAction.NOTE)` through `pick` (which closes the menu), and the menu only expands when `open && enabled` (mail loaded, no action running). The tests check the click (and that the menu closes), the English label, that the note tag is absent from the toolbar and sits above "Mark unread" in the menu, that More is disabled and the item absent while busy, without a mail and after a failed load, the NOTE notices, the links slot (below HTML and text-only bodies, while busy, absent without a mail and for all three load errors), and the route's create/failure flows.
- New regression test "on a 320dp wide window the menu button is shown at full size" (`@Config(qualifiers = "w320dp-h470dp")`): every toolbar button (back, task, waiting, archive, delete, more) is displayed, at least 40dp wide (M3 IconButton bounds; the 48dp touch target is not part of the bounds) and its unclipped right edge lies inside the root. With the old 7-icon toolbar the More button was squeezed to roughly 24dp, so this test protects CR-2.
- `MailReadNoteGermanTest` opens the menu and finds "Notiz erstellen" as item text; the German notices are unchanged.
- `MailReadNoteViewModelTest` and `MailReadRepositoryTest` are unaffected by CR-2 (no view model or repository change).

## Requirement coverage

All results below were run by the parent worker at f2c4f9e82 (the helper cannot run Gradle).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. "Create note" action from the read view, same server call as web (POST /api/v1/mail/messages/{id}/to-note) | `MailReadRepositoryTest` create note posts to to-note; `MailReadNoteLinksTest` create note in the menu asks for a note and closes the menu, create note is not in the toolbar but the first item of the menu, create note waits while an action runs, without a mail / after a failed load create note cannot be reached, the route creates a note from the mail and announces it, the route reports a note the server could not create; `MailReadNoteViewModelTest` (5 tests) | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` (parent) | Pass |
| CR-2 regression: toolbar fits a 320dp-wide window | `MailReadNoteLinksTest` on a 320dp wide window the menu button is shown at full size | same command (parent) | Pass |
| 2. The read view shows the linked items panel for the mail | `MailReadNoteLinksTest` the linked items panel is shown below a mail, shown below a text-only mail and while an action runs, without a mail there is no linked items panel, a failed load shows no linked items panel, the route shows the links area for a loaded mail | same command (parent) | Pass (partial: the Hilt-provided `LinkedItemsSection` inside the route is not exercised, see Unable to test) |
| 3. New strings in English and German | `MailReadNoteLinksTest` create note has an English label, notice tests; `MailReadNoteGermanTest` (3 tests) | same command (parent) | Pass |
| 4. Unit/Compose tests cover the new behavior; `./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass | all of the above | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` (parent); `sh scripts/check.sh` | Gradle command: Pass (parent). `sh scripts/check.sh`: Not run by the helper (needs Gradle); the parent must run it |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` (run by the parent at f2c4f9e82; it includes the mail.read package: 305 tests, among them MailReadNoteLinksTest, MailReadNoteGermanTest, MailReadNoteViewModelTest, MailReadRepositoryTest)

Result:

Pass (BUILD SUCCESSFUL, all unit tests pass, as reported by the parent worker)

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Pass (run by the parent worker at f2c4f9e82, BUILD SUCCESSFUL). Server and web suites were not part of this run; the task changes only `android/`.

## .taskcheck

Result:

Not run by the helper (`sh scripts/check.sh` needs Gradle); the parent worker runs it before `done`.

## Bugs found

None. The final tests match the final production code (menu placement, enabled rules, tags, strings), and the parent's run passed.

## Coverage

Status: unavailable - the test-writer helper cannot run Gradle in this environment, so the configured Android coverage command (`cd android && ./gradlew createDebugUnitTestCoverageReport`, JaCoCo via AGP, report `android/app/build/reports/coverage/test/debug/report.xml`) was not executed. No coverage numbers are reported; none from earlier runs or other scopes are substituted.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-mail-note-links; base 49b1d117ae2928436695d299aa85451676ed42f4; final commit f2c4f9e82fac5e53e74d5891d092872225f167e5; scope supplied by the parent (`git diff 49b1d117a..f2c4f9e82 -- android`) and `fixes.md`; not re-listed with git in this run (no shell).
- Source/test snapshot identifier (including uncommitted inputs): commit f2c4f9e82fac5e53e74d5891d092872225f167e5, clean apart from this report; no measurement taken.
- Exact included files; renamed/deleted/excluded files and reasons: `mail/read/MailReadScreen.kt`, `mail/read/MailReadRepository.kt`, `mail/list/MailApi.kt` (interface, no executable lines of its own). Excluded: both `strings_mail.xml` files (resources, not instrumented). No renames or deletions known.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP; `./gradlew createDebugUnitTestCoverageReport` in `android/`; not run.
- Run start/completion time and exit status: not run.
- Generated report paths and report hashes/run identifier: none.
- Evidence outputs are fresh and inputs stayed unchanged: not applicable, no run.
- Final run after latest test additions and production fixes: no coverage run (helper cannot run Gradle); the parent's test run is on the final revision f2c4f9e82.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadScreen.kt` | not measured | not measured | not measured | not measured |
| `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadRepository.kt` | not measured | not measured | not measured | not measured |
| `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailApi.kt` | not measured | not measured | not measured | not measured |

Known gaps from reading the code (expected uncovered in unit tests):

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailReadScreen.kt:188-193` `mailLinks`, Hilt branch `hiltViewModel(key = linksViewModelKey(...))` | Criterion 2: the panel's view model for the shown mail | Not reachable in Robolectric screen tests: needs an activity that is a `GeneratedComponentManagerHolder` (Hilt). Justified gap; needs a Hilt test activity, an instrumented test or e2e |
| `MailReadScreen.kt:170-179` route `linksPanel` lambda calling `LinkedItemsSection(ItemRef("EMAIL", id), ...)` and its `open` handler | Criterion 2: panel content for the mail; opening an in-app linked item over the mail | Same reason (links view model is null without Hilt). The slot is covered with a fake panel; `LinkedItemsSection` has its own tests from and-links-panel |
| `MailReadScreen.kt:126` `links?.reload()` after a Created notice | Panel refreshes after a note or task was created | The effect runs in the route tests but `links` is null, so the reload itself is not verified. Justified gap, same reason |
| `MailReadScreen.kt:114-120` linked-item overlay (`item != null`, `BackHandler`, `SearchItemScreen`) | Back from a linked item returns to the mail | Not reachable without the Hilt panel. Justified gap, same reason |

Follow-up for the parent if task-scoped numbers are wanted: run `cd android && ./gradlew createDebugUnitTestCoverageReport` and read the `MailReadScreen.kt` and `MailReadRepository.kt` counters (INSTRUCTION, BRANCH, LINE, METHOD) from `android/app/build/reports/coverage/test/debug/report.xml`.

## Unable to test

- Running any test, lint, `.taskcheck` or coverage: the helper cannot run Gradle here; the test and lint results above are the parent's run.
- The Hilt-only route paths: the real `LinkedItemsSection` inside `MailReadRoute` (Hilt-provided `LinkedItemsViewModel` for `ItemRef("EMAIL", {messageId})`), its reload after a Created notice, and opening an in-app linked item over the mail and returning with back. They need a Hilt-enabled activity; follow-up is an instrumented or e2e test, or accepting the gap since the panel component is tested in and-links-panel and the wiring follows ContactDetailRoute.
- The end-to-end server call against a running backend: covered at the HTTP level by `MailReadRepositoryTest` with MockWebServer only.

## Summary

Tests and task-scoped behavior coverage are sufficient for the final revision; no production defects found and no further tests were necessary. The CR-2 adaptations match the final code (Create note is the first More menu item, unreachable while busy, without a mail or after a failed load) and the new 320dp test protects the toolbar width. The parent's run at f2c4f9e82 (`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`) passed; `sh scripts/check.sh` is still for the parent to run. Coverage was not measured (no Gradle for the helper), and the Hilt-only route paths of the links panel remain untested by unit tests.
