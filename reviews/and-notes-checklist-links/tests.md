# Test Report

Date: 2026-11-08

Task: and-notes-checklist-links - Note editor: checklist toggle in preview and [[ link autocomplete

Scope: base 1f9b236cecc7df569344fa6ebb10786079d68d8f..HEAD 926e9f4396b075009dce16c0cd5f3d0bac4bc425 (branch main). Task commits after the parent's rebase: 46272b6c6, d3508053f, 926e9f439 (same code as the commits 2e03999cf, 15be26a4d, dc174e159 named by the parent; `git diff dc174e159 HEAD` only touches `tasks/and-notes-checklist-links.md`). Only Android, package `ch.sonensei.cockpit.notes`.

Requirements:
- tasks/and-notes-checklist-links.md (acceptance criteria and the ASSUMPTION note: 250 ms debounce, global search over all item types, up to 6 hits, inserts `[[<lowercase type>:<id>]]`, checklist tap only while the note is editable)
- docs/ARCHITECTURE.md "Coverage", AGENTS.md
- Existing conventions in NoteEditorViewModelTest.kt, NotesScreensTest.kt, MarkdownViewTest.kt (JUnit4, MockK, kotlinx-coroutines-test, Robolectric + Compose test rule)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/notes/NoteEditorChecklistLinksViewModelTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/notes/NoteEditorChecklistLinksScreenTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/notes/MarkdownTest.kt` (updated expectations, see below)

## Tests added or updated

Count: 15 (9 ViewModel tests, 4 Compose/Robolectric tests, 2 updated MarkdownTest expectations)

`MarkdownTest`: 2 existing tests failed at the task HEAD (`parses headings, paragraphs, lists, checklists, quotes, code and rules`, `nested items have a depth, an unclosed code block runs to the end`). The task added `MdBlock.ListItem.line` (the body line of a checklist item, which the toggle needs), and the old expectations did not include it. I updated the expected values to the line numbers (4, 5 and 2) instead of loosening the assertions. The parent's commits did not change this file, so `testDebugUnitTest` failed at HEAD 926e9f439 without this update.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Tapping a checklist item in preview toggles it, saved like an edit (ViewModel) | `toggling a checklist item changes the body, marks it unsaved and autosaves like an edit`, `a checked item is unchecked, and toggling back needs no save`, `a line that is no checklist item, or out of range, changes nothing`, `nothing is toggled while the note loads or a conflict waits` | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` | Pass |
| Tapping a checklist item in preview (UI) | `tapping a checklist item in the view toggles it by its line`, `checklist items cannot be tapped during a conflict` | same | Pass |
| `[[` search-as-you-type (ViewModel: 250 ms debounce, trimmed query, all groups, same query not searched again, empty/blank query no search, null clears and cancels, Offline/Failed errors, older hits kept while the next search waits, stale results never overwrite newer ones) | `link search runs once typing pauses for 250 ms, with the trimmed query, and lists all hits`, `the same query again does not search again`, `an empty or blank query shows the hint without searching, null clears the suggestions`, `offline and failed searches say so`, `older hits stay while the next query waits, and a slow older search never overwrites newer results` | same | Pass |
| `[[` inserts `[[type:id]]` (UI) and shows at most 6 hits | `typing a link reports its query, and picking a hit inserts the link`, `the suggestion list shows at most six hits, and says when the search is offline or empty` | same | Pass |
| Markdown helpers (toggleChecklist, linkQuery, insertLink, parse line numbers) | existing `MarkdownChecklistLinksTest` (parent), updated `MarkdownTest` | same | Pass |
| New strings in en + de | covered by `lintDebug` (MissingTranslation); UI test checks only that the panel exists in the offline state, not the strings | same | Pass |
| ktlintCheck testDebugUnitTest lintDebug pass | full Android run | same | Pass |
| `sh scripts/check.sh` passes | none | Not run: the parent runs `.taskcheck` | Not run: left to the parent |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests '*NoteEditorChecklistLinks*' -q`

Result:

Pass (ViewModel test 9/9, Screen test 4/4, from the JUnit XML in `app/build/test-results/testDebugUnitTest`)

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport -q`

Result:

Pass (exit 0, 2026-11-08 22:42:36 to 22:48:03 +01:00). Earlier runs: the first failed on the 2 outdated `MarkdownTest` expectations (4760 tests, 2 failed). The second failed on a ktlint `chain-method-continuation` issue in my new test. I fixed both before the final run.

## .taskcheck

Result:

Not run (Android part run in full above; the parent runs `sh scripts/check.sh` before `done`)

## Bugs found

No production defects found.

## Coverage

Status: verified (fresh run, exit 0)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured. Changed lines were checked by hand against the uncovered-line list below.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-notes-checklist-links; base 1f9b236cecc7df569344fa6ebb10786079d68d8f; HEAD 926e9f4396b075009dce16c0cd5f3d0bac4bc425; parent-supplied commits and file list, checked with `git diff --stat 1f9b236ce..HEAD`
- Source/test snapshot identifier (including uncommitted inputs): HEAD 926e9f439 plus uncommitted test files with sha256: NoteEditorChecklistLinksViewModelTest.kt 9da81a5e...400b, NoteEditorChecklistLinksScreenTest.kt f234765a...ab16, MarkdownTest.kt bae8f50c...f558. No production files are modified.
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/notes/{Markdown.kt,MarkdownView.kt,NoteEditorViewModel.kt,NoteEditorScreen.kt,NoteEditors.kt}`. Excluded: `res/values{,-de}/strings_notes.xml` (resources, not executable; checked by lint). Nothing was renamed or deleted.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`; `./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport -q` in `android/`; all debug unit tests
- Run start/completion time and exit status: 2026-11-08T22:42:36+01:00 to 22:48:03+01:00, exit 0
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, mtime 2026-11-08 22:48:03, sha256 b2bae4b8bd0abee492cfe9448cce0ac80ad94d737dbe188f2e6ae4060b70a299
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report mtime matches the end of the run, and the test-file hashes are the same after the run.
- Final run after latest test additions and production fixes: yes (no production fixes)

Values below use covered/total (percentage). Statements = JaCoCo INSTRUCTION, Functions = METHOD.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `notes/Markdown.kt` | 1362/1380 (98.7%) | 94/100 (94.0%) | 48/48 (100%) | 135/135 (100%) |
| `notes/MarkdownView.kt` | 1437/1467 (98.0%) | 72/84 (85.7%) | 12/12 (100%) | 137/138 (99.3%) |
| `notes/NoteEditorViewModel.kt` | 2979/3001 (99.3%) | 212/228 (93.0%) | 78/80 (97.5%) | 271/271 (100%) |
| `notes/NoteEditorScreen.kt` | 4467/4569 (97.8%) | 163/187 (87.2%) | 79/96 (82.3%) | 323/329 (98.2%) |
| `notes/NoteEditors.kt` | 303/310 (97.7%) | 13/14 (92.9%) | 11/12 (91.7%) | 40/40 (100%) |
| Aggregate (summed counters, all 5 files, none unmeasured) | 10548/10727 (98.3%) | 554/613 (90.4%) | 228/248 (91.9%) | 906/913 (99.2%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `NoteEditorViewModel.kt:229` (q == null branch) | not reachable: a null query is handled on line 228 | Justified gap (compiler branch) |
| `NoteEditorViewModel.kt:241` (else: the result belongs to an older query) | stale-result guard. Cancelling the job on a new query already prevents this, so the else branch is not reachable in a single-threaded test | Behavior covered by `older hits stay ... never overwrites newer results` (the slow search finishes after a newer query and does not overwrite it). The branch itself is left uncovered. |
| `NoteEditorViewModel.kt:115` (default `NoteLinkSearch`) | default constructor argument, used by the existing tests without a search | Non-material |
| `NoteEditorScreen.kt:252` (partial: links null / not editable while `[[` is typed) | panel hidden when the editor is not editable | Low risk. The panel is hidden before the VM answers (asserted). The not-editable case is not tested separately because the body field is disabled then. |
| `NoteEditorScreen.kt:322-325, 332` (panel texts for failed, blank-query hint, searching, none; error colour) | informational texts in the suggestion panel | Offline state rendered in a test. The other texts are not asserted (time budget). They are presentation only, and the strings are checked by lint. Follow-up optional. |
| `NoteEditorScreen.kt:305` (selection not collapsed) | no link query while text is selected | Not tested. Low risk. |
| `MarkdownView.kt:73` (partial: `block.line < 0` for a checklist item) | not reachable from parse for a checklist item | Non-material defensive branch |
| `NoteEditors.kt:33` (Hilt `NoteLinkSearchModule` provider) | DI glue to `SearchRepository.search(q, emptySet())` | Not testable without a Hilt graph test. Non-material. |
| Other uncovered lines in these files (e.g. NoteEditorScreen.kt 73-88 data-class defaults, 124-209 route wiring) | pre-existing or default-argument code outside this task's changes | Not in task scope |

## Unable to test

- `NoteLinkSearchModule` (Hilt wiring to the global search over all types): needs an instrumented or Hilt test, which this project does not have for notes. A follow-up is optional; the wiring is one line.
- End-to-end on a device (real keyboard, IME cursor handling): not run, because there is no emulator in this environment. The Robolectric Compose tests cover the logic.

## Summary

Tests added and all validation passed. No production defects found. Two outdated `MarkdownTest` expectations, which failed at the task HEAD, were updated to the new `line` field. The parent should commit this test change, or the check fails.
