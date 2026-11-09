# Test Report

Date: 2026-11-09

Task: and-notes-linked-panel - Note editor: LinkedItemsPanel (round 2, on the final code after fix CR-1)

Scope: `git diff 9fd07318fb01864c36cd0950d41f412a8fabd24a..e07cfb3b536a19219f7a3de40e8bcda074b5cecc -- android` (task commits da6ecadbf implementation, 9e6b8e797 route tests and round-1 reports, e07cfb3b5 fix CR-1 and fixer report). Production files changed: `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorScreen.kt` only. No strings were added (no `strings_notes.xml` change). Working tree clean at the start; no uncommitted inputs.

Requirements:
- `tasks/and-notes-linked-panel.md` acceptance criteria and its recorded assumption (panel only in view mode for saved notes; linked in-app items open in place via SearchItemScreen after saving the note; Screen targets ignored; web targets open in the browser)
- `reviews/and-notes-linked-panel/review.md` CR-1 and `reviews/and-notes-linked-panel/fixes.md` (the panel's links load again on a new saved note version and when the note is opened again)
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`
- existing test conventions (`NoteEditorRouteTest`, `NotesRouteInAppTest`)

## Test files changed

- None in round 2. Reviewed, unchanged:
- `android/app/src/test/java/ch/sonensei/cockpit/notes/NoteEditorLinksRouteInAppTest.kt` (8 tests: 6 from round 1, 2 added by the fixer for CR-1)
- `android/app/src/test/java/ch/sonensei/cockpit/notes/NoteEditorLinksPanelScreenTest.kt` (5 tests from the implementation commit)

## Tests added or updated

Count: 0 (round 2). The final code is covered by the 13 task tests listed above; no meaningful gap was found that needs a new test (see Coverage and Unable to test).

Adequacy check of the CR-1 fix (`LaunchedEffect(links, state.note?.version)` calling `reload()` only when the panel already shows this note):
- the reload on a new saved version is tested (`a new version of the note saved with an inline link loads the panel's links again`, exactly 2 `list` calls, and the new task link appears);
- the reload on reopening the note is tested (`the note opened again loads its links again`, 2 `list` calls);
- the "no double load on first open" branch (panel not yet showing the note, so `takeIf` is false) is asserted by `the opened note shows the links panel with its linked items, loaded for that note` (`exactly = 1`);
- the fixer reports both CR-1 tests failed with `reload()` disabled, so they catch the bug;
- a revision restore takes the same path (it produces a new note version, the `LaunchedEffect` key), so no separate route test was added for it.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: `LinkedItemsPanel` for the note (slot shown below a saved note's text in the view) | `NoteEditorLinksPanelScreenTest`: `a saved note shows its links panel below the text in the view`, `a saved note with an empty text still shows its links panel` | `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*' --rerun createDebugUnitTestCoverageReport` | Pass |
| AC1: the real panel is loaded for this note (`ItemRef("NOTE", id)`) and lists its links, loaded once on first open | `NoteEditorLinksRouteInAppTest`: `the opened note shows the links panel with its linked items, loaded for that note` | same | Pass |
| AC1 / CR-1: the panel loads the links again on a new saved version of the note | `NoteEditorLinksRouteInAppTest`: `a new version of the note saved with an inline link loads the panel's links again` | same | Pass |
| AC1 / CR-1: the panel loads the links again when the note is opened again | `NoteEditorLinksRouteInAppTest`: `the note opened again loads its links again` | same | Pass |
| Assumption: no panel for an unsaved note, while loading, or while editing | `NoteEditorLinksPanelScreenTest`: `a new note that is not saved yet has no links panel`, `a note still loading has no links panel`, `the panel is hidden while editing and back in the view`; `NoteEditorLinksRouteInAppTest`: `the links panel is gone while the note is edited` | same | Pass |
| Assumption: a linked in-app item opens in place; closing it returns to the note, not the list | `NoteEditorLinksRouteInAppTest`: `a linked note opens in place, and its close returns to the note instead of the list`, `the system back from a linked note returns to the note` | same | Pass |
| Assumption: the note is saved before the linked item is opened | `NoteEditorLinksRouteInAppTest`: `a change not saved yet is saved before the linked note is opened` | same | Pass |
| Assumption: Screen targets are ignored (no navigation from the editor) | `NoteEditorLinksRouteInAppTest`: `a linked item without an Android screen keeps the note open` | same | Pass |
| Assumption: web targets open in the browser | Not in this task's code: `LinkedItemsSection` (from `and-links-panel`) opens them before calling `open`; the `Web` branch in the route is unreachable | - | Not testable in task scope (see Coverage) |
| AC2: new strings in en + de | No strings added by the task (diff touches no `strings_notes.xml`); `lintDebug` (MissingTranslation) passes | `cd android && ./gradlew testDebugUnitTest lintDebug` | Pass (no new strings) |
| AC3: tests cover the new behavior; ktlintCheck, testDebugUnitTest, lintDebug pass | all of the above | `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*' createDebugUnitTestCoverageReport`; `cd android && ./gradlew testDebugUnitTest lintDebug` | Pass |
| AC3: `sh scripts/check.sh` passes | - | - | Not run in round 2: left to the parent worker (run before `done`) |

## Focused test execution

Commands (in `android/`, one Gradle at a time):

`./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*' createDebugUnitTestCoverageReport`

Result: Pass (exit 0, no output). Gradle reused the up-to-date test results of an earlier identical run, so the tests were then forced to run again:

`./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*' --rerun createDebugUnitTestCoverageReport`

Result:

Pass - 23 suites, 340 tests, 0 failures, 0 errors, 0 skipped (suite timestamps 2026-11-09T07:25:12Z to 07:25:31Z; incl. `NoteEditorLinksRouteInAppTest` 8/8, `NoteEditorLinksPanelScreenTest` 5/5, `NoteEditorRouteTest` 12/12, `NotesRouteInAppTest` 6/6).

## Full test execution

Command:

`cd android && ./gradlew testDebugUnitTest lintDebug`

Result:

Pass - 434 suites, 5357 tests, 0 failures, 0 errors, 0 skipped (suite timestamps 07:26:07Z to 07:29:18Z); lintDebug passed (exit 0, only JVM CDS warnings).

## .taskcheck

Result:

Not run in round 2 (the parent worker runs `sh scripts/check.sh` before `done`; round 1 passed on the pre-fix revision).

## Bugs found

None.

## Coverage

Status: verified (filtered run: the notes test package only, see test selection).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured from the JaCoCo XML `line` entries (`mi`/`ci`/`mb`/`cb`) of `NoteEditorScreen.kt`, intersected with the added lines of `git diff -U0 9fd07318f..HEAD` (new-file lines 6, 62-65, 67-68, 71, 157-171, 242-256, 260-271, 298-299, 385; JaCoCo lists 29 of them as executable: 157, 160-163, 165-171, 242-246, 248-249, 252, 254-256, 266-270, 385). Lines: 29/29 (100%) with at least one covered instruction. Branches on changed lines: 29/30 (96.7%); the missed one is line 252 (`is SearchTarget.Web`). Line 168 is partly covered (5 missed instructions: the body of the fallback `BackHandler`).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-notes-linked-panel; base 9fd07318fb01864c36cd0950d41f412a8fabd24a (given by the parent); HEAD e07cfb3b536a19219f7a3de40e8bcda074b5cecc; task commits da6ecadbf, 9e6b8e797, e07cfb3b5. Scope from `git diff 9fd07318f..HEAD -- android`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD e07cfb3b5, clean tree; `NoteEditorScreen.kt` sha256 c228323054a8d0fc5c50b3351e35c48bf1712ebeb585b9a903b820b1fae5c15c; `NoteEditorLinksRouteInAppTest.kt` sha256 8aeac3e7729b01885be5c021847bf1c75748aa20faeadda143ed1fb12ca5fd13; `NoteEditorLinksPanelScreenTest.kt` sha256 36247a07efcae19d863988768edc782948c0eb10be565a69a58938a7712451f4 (identical before and after the run).
- Exact included files; renamed/deleted/excluded files and reasons: included `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorScreen.kt`. Excluded: the two test files (test code). Reports under `reviews/` are outside `android`. No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`; `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*' --rerun createDebugUnitTestCoverageReport` in `android/`; test selection: package `ch.sonensei.cockpit.notes` (340 tests).
- Run start/completion time and exit status: tests 2026-11-09T08:25:12+01:00 to 08:25:31+01:00, report written 08:25:33; exit 0.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` (08:25:33, sha256 deaecdf0339a5e62e3768b404e59f6d3c3d4c6a4e45b170b379d491ab4d510e8); exec `android/app/build/outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec` (08:25:31).
- Evidence outputs are fresh and inputs stayed unchanged: `--rerun` forced the test task to execute (suite timestamps inside the run window); the source/test hashes match before and after. The later full-suite run did not regenerate `report.xml`.
- Final run after latest test additions and production fixes: yes (measured on e07cfb3b5, which includes fix CR-1; no tests added in round 2).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorScreen.kt` | N/A (JaCoCo has no statement counter; instructions 6009/6126 (98.1%)) | 314/393 (79.9%) | 95/116 (81.9%) | 384/390 (98.5%) |

Aggregate = this single file; no scoped files are missing or unmeasured. The whole-file branch and method gaps are mostly in pre-existing editor code (e.g. lines 211-213, 410-420, 591-592) and Compose-generated lambdas outside this task.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `NoteEditorScreen.kt:168` (body of `BackHandler { opened = null }`) | System back while a linked item is open returns to the note | Behavior is tested (`the system back from a linked note returns to the note`), but there the opened note editor's own `BackHandler` (registered later) handles back and closes it via `onClose`, so this outer handler is only a fallback for opened items without their own back handling (e.g. a task detail). Low risk; not added (it would need the tasks feature's fakes). |
| `NoteEditorScreen.kt:252` (`is SearchTarget.Web` branch) | Web targets open in the browser | Unreachable from this route: `LinkedItemsSection` opens web targets itself and passes only in-app and Screen targets to `open`. The `Screen` alternative is covered. Justified gap. |

## Unable to test

- Web links opening the browser from the note editor: handled inside `LinkedItemsSection` (task `and-links-panel`, tested there), not by this task's code; no follow-up needed here.
- The outer `BackHandler` fallback for an opened item type without its own back handling (see Coverage); low risk, optional follow-up.
- Revision restore reloading the panel: not tested separately at route level; it goes through the same `state.note?.version` key that the "new version" test covers.
- Note on the save-before-open test: it asserts the order "saved" before "get Packing list". It relies on `saveNow()` running on `Dispatchers.Main.immediate` (synchronously in the click) while the autosave waits 1 s; it was not mutation-checked, since production code must not be changed by the test-writer.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. No tests were added in round 2; focused, full Android unit and lint validation passed on the final code (`sh scripts/check.sh` left to the parent worker).
