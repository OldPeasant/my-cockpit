# Test Report

Date: 2026-11-09

Task: and-notes-editor - Android note editor: tags, checklists, links, revisions

Scope: integration task. Immutable base 9929d802f1cfc9c5903b9693901885a9321caa5e (parent of the first task commit
f897053c2 "and-notes-editor: WIP checklist toggle and [[ link query helpers"), measured HEAD
a84e8ba57448cc91c892843f88dd8f21d56f4a6b (merge of the obsolete wip branch, no net change). Task ownership: the task's
own WIP commit f897053c2 plus its four subtasks' commits (and-notes-checklist-links 2b78d6469..26c33c9c8,
and-notes-tags e37ba2e51..8999c019e, and-notes-revisions 409e7c8ed..3cda32069, and-notes-linked-panel
fd5ec2730..23561727f), restricted to the task's `touches` (notes package, notes tests, strings_notes.xml EN/DE).
Excluded from the diff 9929d802f..HEAD: `android/.../mail/compose/RecipientInput.kt` (task and-mail-chip-a11y, not this
task). During this run HEAD moved to 1ba8b693e, which only adds `reviews/and-notes-editor/review.md` (no code change).

Requirements:
- tasks/and-notes-editor.md acceptance criteria
- docs/ARCHITECTURE.md "Coverage" and Android conventions (EN + DE strings, lint MissingTranslation)
- AGENTS.md project notes; parent instructions (integration gaps only, no duplicates of subtask tests)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/notes/NoteEditorIntegrationTest.kt` (new)

## Tests added or updated

Count: 3

Gap found: every feature had its own view model tests (fake repository) and screen tests (`NoteEditorContent` with fake
callbacks), and the links panel had Hilt route tests. No test drove the tags, checklist, `[[` link and revisions
features through `NoteEditorRoute` wired to one real `NoteEditorViewModel`, so the route's callback wiring for these
features (`addTag`, `removeTag`, `toggleChecklist`, `searchLinks`, `openRevisions`, `selectRevision`, `restoreRevision`)
and the features' combined saves were untested. The new Robolectric/Compose tests (fake repository via mockk, fake link
search and tag source, fictitious data) cover that:

- `a suggested tag and a checklist tap in the view are saved together, in one save like an edit`: focusing the tag
  input shows the 2 tags in use as suggestions; picking `#travel` and tapping the checklist item `passport` in the
  rendered note give UNSAVED; after the autosave delay exactly one save goes out, with the toggled body and the tag;
  the chip stays and the new version is taken.
- `typing a link searches through the view model, and the picked item is saved as type and id`: typing `[[boo` in the
  body searches `boo` only after the 250 ms pause; the hit shows; picking it inserts `[[task:{id}]]`, clears the
  suggestions; Done saves the body with the link.
- `restoring an earlier version from the menu saves the checklist toggle first, then shows the restored text with the tags`:
  checklist toggle (unsaved), menu, Earlier versions, 2 revisions listed, select the older one, Restore: the toggled
  text is saved first, then `restoreRevision(savedVersion, 1)` runs (verified in order); the dialog closes and the view
  shows the restored title and body with the note's tag chip; status SAVED.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Edit tags (chips with suggestions from existing tags) | NoteEditorIntegrationTest (tag suggestion + save); existing NoteTagsEditorTest, NoteEditorTagsViewModelTest | `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*' createDebugUnitTestCoverageReport -q` | Pass |
| Tapping a checklist item in preview toggles it (saved like an edit) | NoteEditorIntegrationTest (tests 1 and 3); existing NoteEditorChecklistLinksScreenTest, NoteEditorChecklistLinksViewModelTest, MarkdownChecklistLinksTest | same | Pass |
| Typing `[[` offers search-as-you-type and inserts `[[type:id]]` | NoteEditorIntegrationTest (link test); existing NoteEditorChecklistLinksScreenTest, NoteEditorChecklistLinksViewModelTest, MarkdownChecklistLinksTest | same | Pass |
| Revisions: list and restore | NoteEditorIntegrationTest (restore test); existing NoteRevisionsDialogTest, NoteEditorRevisionsViewModelTest, NoteRevisionsRepositoryTest | same | Pass |
| `LinkedItemsPanel` for the note | existing NoteEditorLinksPanelScreenTest, NoteEditorLinksRouteInAppTest (Hilt, real route) | same | Pass |
| New strings in English and German | 15 new keys in `values/strings_notes.xml`, the same 15 in `values-de/strings_notes.xml` (diff 9929d802f..HEAD); lint MissingTranslation in `sh scripts/check.sh`; NoteRevisionsDialogTest German test | `sh scripts/check.sh` | Pass |
| Unit/Compose tests cover new behavior; `ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass | all of the above; full Android unit suite | `cd android && ./gradlew testDebugUnitTest -q` and `sh scripts/check.sh` | Pass |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.NoteEditorIntegrationTest'`

Result:

Pass (3 tests, 0 failures). After a ktlint formatting fix in the new file (2 chain-method-continuation findings,
test-only), the notes package run below repeated it.

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*' createDebugUnitTestCoverageReport -q`

Result:

Pass: ktlint clean; 24 notes test classes, 343 tests, 0 failures, 0 errors, 0 skipped. NotePhotoFilesTest did not flake.

## Full test execution

Command:

`cd android && ./gradlew testDebugUnitTest -q`

Result:

Pass: 5367 tests, 0 failures, 0 errors, 0 skipped.

## .taskcheck

Result:

Pass: `sh scripts/check.sh` printed "== all checks passed" (tfp gate 22 tests OK, server ktlint + tests, web eslint +
4768 vitest tests + build, android ktlint + unit tests + lint).

## Bugs found

None. All new tests passed against the current production code.

## Unable to test

- Real-device/emulator behavior (soft keyboard, actual touch targets) is not exercised; Robolectric Compose tests stand
  in, as for the rest of the Android suite. No follow-up needed for this task.
- The links panel inside `NoteEditorRoute` needs Hilt; it is covered by the existing NoteEditorLinksRouteInAppTest. The
  new tests use a non-Hilt activity, so the panel is absent there (by design of `noteLinks`).

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient; no production defects found.

## Coverage

Status: verified (whole-file, notes package tests only).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-notes-editor; base
  9929d802f1cfc9c5903b9693901885a9321caa5e; HEAD a84e8ba57448cc91c892843f88dd8f21d56f4a6b; commits listed under Scope;
  files from `git diff --name-only 9929d802f..HEAD -- android/app/src/main` limited to the task's touches.
- Source/test snapshot identifier (including uncommitted inputs): HEAD a84e8ba57 plus untracked
  `NoteEditorIntegrationTest.kt` sha256 2d54e42b5c82af072f09c0afabf827d86169795868106253e46bf4061200139e (unchanged
  after the runs).
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/notes/` Markdown.kt, MarkdownView.kt,
  NoteEditorScreen.kt, NoteEditorViewModel.kt, NoteEditors.kt, NoteRevisionsDialog.kt, NoteTagsEditor.kt,
  NotesModels.kt, NotesRepository.kt. No renames or deletions. Excluded: `mail/compose/RecipientInput.kt` (other
  task), strings XML (resources, no executable code), test files.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`;
  `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*' createDebugUnitTestCoverageReport -q`
  in `android/`; test selection: all `ch.sonensei.cockpit.notes.*` tests (343).
- Run start/completion time and exit status: 2026-11-09T08:44:00+01:00 to 08:44:57+01:00, exit 0.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`
  (written 08:44:56), sha256 2b4a47473641b915f0a2423f4955b98794cd504b4191658f8477b190e278e4fb.
- Evidence outputs are fresh and inputs stayed unchanged: yes; the test task reran (new test file input), the report
  timestamp lies inside the run, and the test file hash and production files were unchanged afterwards (HEAD moved only by
  a reviews-only commit).
- Final run after latest test additions and production fixes: yes (no production fixes were made).

Values below use covered/total (percentage); JaCoCo counters INSTRUCTION (statements), BRANCH, METHOD, LINE.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `notes/Markdown.kt` | 1362/1380 (98.7%) | 94/100 (94.0%) | 48/48 (100.0%) | 135/135 (100.0%) |
| `notes/MarkdownView.kt` | 1443/1467 (98.4%) | 74/84 (88.1%) | 12/12 (100.0%) | 137/138 (99.3%) |
| `notes/NoteEditorScreen.kt` | 5564/5680 (98.0%) | 207/231 (89.6%) | 95/116 (81.9%) | 384/390 (98.5%) |
| `notes/NoteEditorViewModel.kt` | 4117/4146 (99.3%) | 299/318 (94.0%) | 111/113 (98.2%) | 360/360 (100.0%) |
| `notes/NoteEditors.kt` | 314/321 (97.8%) | 13/14 (92.9%) | 12/13 (92.3%) | 41/41 (100.0%) |
| `notes/NoteRevisionsDialog.kt` | 1120/1130 (99.1%) | 61/62 (98.4%) | 21/25 (84.0%) | 96/96 (100.0%) |
| `notes/NoteTagsEditor.kt` | 1057/1057 (100.0%) | 36/38 (94.7%) | 25/25 (100.0%) | 73/73 (100.0%) |
| `notes/NotesModels.kt` | 148/148 (100.0%) | N/A (0/0) | 23/23 (100.0%) | 33/33 (100.0%) |
| `notes/NotesRepository.kt` | 1217/1235 (98.5%) | 63/66 (95.5%) | 52/54 (96.3%) | 127/131 (96.9%) |
| Aggregate (summed counters, 9 files, none missing) | 16342/16564 (98.7%) | 847/913 (92.8%) | 399/429 (93.0%) | 1386/1397 (99.2%) |

Function counts for Compose files include generated lambdas, so missed "methods" there are mostly synthetic.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `NoteEditorScreen.kt:211-213` | camera app missing (ActivityNotFoundException), photo feature of an earlier task | justified gap: not in this task's criteria |
| `NoteEditorScreen.kt:464`, `591-592` | SAVING status label, photo offline/failed banner texts (earlier task) | justified gap: pre-existing, outside the criteria |
| `NoteEditorScreen.kt:338-339, 350, 410-413` (partial branches) | link panel guard combinations, suggestion info text cases | covered in behavior by NoteEditorChecklistLinksScreenTest and the new link test; remaining combinations are defensive |
| `NoteEditorViewModel.kt` partial branches (187, 215, 309, 321, 372, 474, 522, 532, 565, 581, 611, 632, 636, 652, 684) | guards in open/save/restore/delete/link search | all lines executed; remaining branch sides are defensive combinations, already tested in behavior by the subtask tests |
| `MarkdownView.kt:157` and partial branches | `when` exhaustiveness tail; rendering variants | justified gap: no behavior |
| `NotesRepository.kt:284-285, 292-293` | rethrow of CancellationException, IllegalArgumentException mapping in the shared `load` | justified gap: pre-existing generic error mapping, not changed by this task |
