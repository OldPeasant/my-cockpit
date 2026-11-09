# Test Report

Date: 2026-11-09

Task: and-notes-linked-panel - Note editor: LinkedItemsPanel

Scope: `git diff 8c9fb0bfd4705cdca507bfd391ecd72e29a9a380..6aaa8e5bc7784de183c7c2ee8622d910f162f9cd -- android` (task commit 6aaa8e5bc, after the claim commit 8c9fb0bfd and the assume commit c21f38cdb), plus the new untracked test file listed below. Production files changed: `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorScreen.kt` only. No strings were added (no `strings_notes.xml` change).

Requirements:
- `tasks/and-notes-linked-panel.md` acceptance criteria and its recorded assumption (panel only in view mode for saved notes; linked in-app items open in place via SearchItemScreen after saving the note; Screen targets ignored; web targets open in the browser)
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`
- existing test conventions (`NoteEditorRouteTest`, `NotesRouteInAppTest`)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/notes/NoteEditorLinksRouteInAppTest.kt` (new, by the test-writer)
- `android/app/src/test/java/ch/sonensei/cockpit/notes/NoteEditorLinksPanelScreenTest.kt` (added by the implementation commit; reviewed, unchanged)

## Tests added or updated

Count: 6 (new route-level Hilt/Robolectric tests; the 5 screen tests of the implementation were reviewed and kept as they are)

The route-level tests run `MainActivity` with Hilt (`@BindValue` fakes for `NotesRepository` and `LinksRepository`), because `LinkedItemsSection` and `SearchItemScreen` render nothing outside a Hilt activity, so `NoteEditorRouteTest` cannot reach the new route code.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: `LinkedItemsPanel` for the note (slot shown below a saved note's text in the view) | `NoteEditorLinksPanelScreenTest`: `a saved note shows its links panel below the text in the view`, `a saved note with an empty text still shows its links panel` | `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*'` | Pass |
| AC1: the real panel is loaded for this note (`ItemRef("NOTE", id)`) and lists its links | `NoteEditorLinksRouteInAppTest`: `the opened note shows the links panel with its linked items, loaded for that note` | same | Pass |
| Assumption: no panel for an unsaved note, while loading, or while editing | `NoteEditorLinksPanelScreenTest`: `a new note that is not saved yet has no links panel`, `a note still loading has no links panel`, `the panel is hidden while editing and back in the view`; `NoteEditorLinksRouteInAppTest`: `the links panel is gone while the note is edited` | same | Pass |
| Assumption: a linked in-app item opens in place; closing it returns to the note, not the list | `NoteEditorLinksRouteInAppTest`: `a linked note opens in place, and its close returns to the note instead of the list`, `the system back from a linked note returns to the note` | same | Pass |
| Assumption: the note is saved before the linked item is opened | `NoteEditorLinksRouteInAppTest`: `a change not saved yet is saved before the linked note is opened` (a checklist tick waiting for its 1 s autosave is saved before the linked note is loaded) | same | Pass |
| Assumption: Screen targets are ignored (no navigation from the editor) | `NoteEditorLinksRouteInAppTest`: `a linked item without an Android screen keeps the note open` | same | Pass |
| Assumption: web targets open in the browser | Not in this task's code: `LinkedItemsSection` (from `and-links-panel`) opens them before calling `open`; the `Web` branch in the route is unreachable | - | Not testable in task scope (see Coverage) |
| AC2: new strings in en + de | No strings added by the task (diff touches no `strings_notes.xml`); `lintDebug` (MissingTranslation) passes | `cd android && ./gradlew testDebugUnitTest lintDebug` | Pass (no new strings) |
| AC3: tests cover the new behavior; ktlintCheck, testDebugUnitTest, lintDebug and `sh scripts/check.sh` pass | all of the above | `./gradlew ktlintCheck`; `./gradlew testDebugUnitTest lintDebug`; `sh scripts/check.sh` | Pass |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*'`

Result:

Pass - 23 suites, 332 tests, 0 failures, 0 errors, 0 skipped (incl. `NoteEditorLinksRouteInAppTest` 6/6, `NoteEditorLinksPanelScreenTest` 5/5, `NoteEditorRouteTest` 12/12, `NotesRouteInAppTest` 6/6).

`cd android && ./gradlew ktlintCheck` -> Pass (after wrapping three long lines in the new test).

## Full test execution

Command:

`cd android && ./gradlew testDebugUnitTest lintDebug`

Result:

Pass - 434 suites, 5355 tests, 0 failures, 0 errors, 0 skipped; lintDebug passed.

## .taskcheck

Result:

Pass - `sh scripts/check.sh` ended with "== all checks passed" (2026-11-09 ~08:18 +01:00; web build printed only the existing initial-bundle budget warning).

## Bugs found

None.

## Coverage

Status: verified (filtered run: the notes test package only, see test selection).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured from the JaCoCo XML `line` entries (`mi`/`ci`/`mb`/`cb`) of `NoteEditorScreen.kt`, intersected with the added lines of the task diff (new-file lines 61-62, 64-65, 153-160, 231-243, 247-248, 276-277, 363; JaCoCo lists 18 executable ones). Lines: 18/18 (100%) with at least one covered instruction; branches on changed lines: 11/12 (91.7%). Line 157 is partly covered (5 of 28 instructions missed).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-notes-linked-panel; base 8c9fb0bfd4705cdca507bfd391ecd72e29a9a380 (given by the parent, the claim commit); HEAD 6aaa8e5bc7784de183c7c2ee8622d910f162f9cd; task commit 6aaa8e5bc (plus assume commit c21f38cdb, tasks only). Scope from `git diff 8c9fb0bfd..HEAD -- android`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 6aaa8e5bc plus untracked `NoteEditorLinksRouteInAppTest.kt` sha256 146e4509395515513150ab1f15dc12a5c90e3f920c58c953b21a782986540fd2; `NoteEditorScreen.kt` sha256 d53f37e16c0bae4a6c924cc2e2bf978e19608b10b5c9ca9ffb6f2d1bad70567c; `NoteEditorLinksPanelScreenTest.kt` sha256 36247a07efcae19d863988768edc782948c0eb10be565a69a58938a7712451f4.
- Exact included files; renamed/deleted/excluded files and reasons: included `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorScreen.kt`. Excluded: the test file (test code), `tasks/and-notes-linked-panel.md` (task metadata). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`; `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*' createDebugUnitTestCoverageReport` in `android/`; test selection: package `ch.sonensei.cockpit.notes` (332 tests).
- Run start/completion time and exit status: 2026-11-09T08:10:34+01:00 to 08:11:06+01:00; exit 0 (no failures).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` (written 08:11:06, sha256 408d33da6b68760edc81a875abe55cd84ff6ec7e14929b8cdeb4326bb7740657); exec `android/app/build/outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec` (08:11:04).
- Evidence outputs are fresh and inputs stayed unchanged: the test-result XML timestamps are 07:10:44Z-07:11:04Z (the run), the test file changed (ktlint fix) just before the run so Gradle re-ran the tests; the hashes above were taken before the run and no source or test file was edited afterwards.
- Final run after latest test additions and production fixes: yes (no production fixes were made).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorScreen.kt` | N/A (JaCoCo has no statement counter; instructions 5804/5921 (98.0%)) | 296/375 (78.9%) | 93/114 (81.6%) | 372/378 (98.4%) |

Aggregate = this single file; no scoped files are missing or unmeasured. The whole-file branch and method gaps are mostly in pre-existing editor code and Compose-generated lambdas outside this task.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `NoteEditorScreen.kt:157` (body of `BackHandler { opened = null }`) | System back while a linked item is open returns to the note | Behavior is tested (`the system back from a linked note returns to the note`), but there the opened note editor's own `BackHandler` (registered later) handles back and closes it via `onClose`, so this outer handler is only a fallback for opened items without their own back handling (e.g. a task detail). Low risk; not added (it would need the tasks feature's fakes). |
| `NoteEditorScreen.kt:240` (`is SearchTarget.Web` branch) | Web targets open in the browser | Unreachable from this route: `LinkedItemsSection` opens web targets itself and passes only in-app and Screen targets to `open`. The `Screen` alternative is covered. Justified gap. |

## Unable to test

- Web links opening the browser from the note editor: handled inside `LinkedItemsSection` (task `and-links-panel`, tested there), not by this task's code; no follow-up needed here.
- The outer `BackHandler` fallback for an opened item type without its own back handling (see Coverage); low risk, optional follow-up.
- Note on the save-before-open test: it asserts the order "saved" before "get Packing list". It relies on `saveNow()` running on `Dispatchers.Main.immediate` (synchronously in the click) while the autosave waits 1 s; it was not mutation-checked, since production code must not be changed by the test-writer.

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient; no production defects found.
