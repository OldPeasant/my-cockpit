# Test Report

Date: 2026-11-09

Task: and-notes-tags - Note editor: tag chips with suggestions (Android)

Scope: task commit range 28a7c555da91462a187b1774f4ebf824777d5445..acf2ddefb8e40b4c6ab8bab5b91a34747cc83e6c on `main`. The parent named task commit 00c7e5be0; it was rebased to acf2ddefb8e40b4c6ab8bab5b91a34747cc83e6c meanwhile (the commits between are board-only: heartbeat/assume). `git diff 00c7e5be0 acf2ddefb -- android` is empty, so the measured Android code is identical to the commit the parent named.

Requirements:
- tasks/and-notes-tags.md acceptance criteria (edit tags as chips with suggestions from existing tags, saved with the note; strings en + de; unit/Compose tests, ktlintCheck testDebugUnitTest lintDebug and scripts/check.sh pass)
- Parent instructions (behaviour list to cover)
- Server contract for tags: server/src/main/kotlin/ch/sonensei/cockpit/notes/domain/NoteService.kt `normalizeTags` (trim, one leading `#` removed, case-insensitive dedup, max 50 chars, max 50 tags)
- docs/ARCHITECTURE.md "Coverage", AGENTS.md

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/notes/NoteEditorTagsViewModelTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/notes/NoteTagsEditorTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/notes/NotesRepositoryTest.kt` (4 tests added, 1 test input updated)
- `android/app/src/test/java/ch/sonensei/cockpit/notes/NoteEditorsTest.kt` (fixture: stub for `repo.tags()`)
- `android/app/src/test/java/ch/sonensei/cockpit/notes/NoteEditorChecklistLinksViewModelTest.kt` (constructor call uses a named `linkSearch =` argument)

## Tests added or updated

Count: 34 added (19 view model, 11 Compose/pure, 4 repository), 3 existing test files updated.

Updates to existing tests (none weakens an assertion; see TW-1):
- `NoteEditorChecklistLinksViewModelTest`: the trailing lambda `{ q -> ... }` now bound to the new last constructor parameter `tagSource`, so the test sources did not compile. Changed to `linkSearch = { q -> ... }`, identical behaviour.
- `NoteEditorsTest.setUp`: `coEvery { repo.tags() } returns NotesResult.Ok(emptyList())`. `NoteEditors` now calls `notes.tags()` on every open; the strict mock had no answer, 8 tests failed with MockKException.
- `NotesRepositoryTest` `saving a note patches only title and body with its version as If-Match`: the draft now carries the base note's tags (`tags = listOf("home")`). Without it the draft means "remove all tags" and `tags: []` is (correctly) sent. The assertion that only `title` and `bodyMd` are patched is unchanged. Clearing all tags is covered by the new test `removing the last tag patches an empty list, so the server clears them`.

## Requirement coverage

Command F (focused): `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*'` -> BUILD SUCCESSFUL, 266 tests, 0 failures.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Tags load with the note; tags in use load (most used first) | `the note's tags load with it, and the tags in use load most used first`; `the tags in use load again when the note is opened again, not on the same opening` | F | Pass |
| Add: trim, leading `#` removed, unsaved, autosaved with the note | `an added tag is trimmed, loses a leading hash, counts as unsaved and is autosaved with the text`; `normalizeTag trims and drops one leading hash, as the server does` | F | Pass |
| Add: a case duplicate is rejected | `a tag already on the note in any case is not added again` | F | Pass |
| Add: known spelling adopted | `a tag that differs from one in use only in case takes that spelling, an unknown tag keeps its own` | F | Pass |
| Add: limits (empty, 50/51 chars, 50 tags) | `an empty, blank or hash-only tag is not added`; `a tag of 50 characters is added, one of 51 is not`; `a note takes 50 tags at most` | F | Pass |
| Remove | `a removed tag is autosaved, and a tag not on the note changes nothing`; `adding and removing a tag back needs no save` | F | Pass |
| Saved with the note via autosave (NoteDraft with tags), server spelling adopted | `tags are saved with the text in one save`; `the server's spelling of the saved tags is taken, so they do not count as unsaved`; `a tag added while a save runs is kept and saved next, over the version that save returned`; `a new note with only a tag is created with it`; `a photo creating a new note sends its tags and takes the server's spelling` | F | Pass |
| Not editable while loading / during a conflict; use theirs takes the other tags | `tags cannot change while the note loads or a conflict waits, and use theirs takes the other tags` | F | Pass |
| Suggestions exclude tags already on the note (any case); failure to load leaves none | `suggestions are the tags in use not on the note yet, in any case`; `when the tags in use cannot load there are no suggestions, and tags can still be added`; `a tag on the note is not suggested` | F | Pass |
| Repository sends `tags` only when changed (and for a create only when non-empty) | `changed tags are patched as the whole list, in their order`; `removing the last tag patches an empty list, so the server clears them`; `tags that only changed in order or case count as changed`; `a new note sends its tags when it has any, and no tags field without`; `saving a note patches only title and body with its version as If-Match` | F | Pass |
| Compose: chip removal | `each tag shows as a chip with a hash, and tapping its remove icon removes it`; `nothing can be removed or suggested while the note cannot be edited` | F | Pass |
| Compose: typing + comma adds; Enter adds; blur adds | `typing a comma adds the tag typed before it and keeps what follows`; `Enter adds the typed tag and clears the input, a blank input adds nothing`; `leaving the input adds the tag typed in it`; `splitTagInput splits at commas and new lines, keeping the part still typed` | F | Pass |
| Compose: suggestion tap adds; filter order and limit | `suggestions show while the input is used, filtered by what is typed, and a tap adds one`; `matchingTags puts tags starting with the text first, then those containing it, at most eight` | F | Pass |
| Editor screen wiring (edit and rendered view) | `the editor screen shows the tags and passes add and remove to the view model callbacks`; `the rendered note shows its tags too` | F | Pass |
| New strings in English and German | No unit test; `notes_tag_add` and `notes_tag_remove` exist in both `values/` and `values-de/` strings_notes.xml (diff inspected), and `cd android && ./gradlew lintDebug` (MissingTranslation) passed | `./gradlew lintDebug` | Pass |
| ktlintCheck testDebugUnitTest lintDebug and scripts/check.sh pass | whole suites | see below | Pass (after the test updates of TW-1; at the task commit as delivered testDebugUnitTest did not compile) |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*'`

Result:

Pass (266 tests in the notes package, 0 failures, 0 skipped; new classes: NoteEditorTagsViewModelTest 19, NoteTagsEditorTest 11; NotesRepositoryTest 35).

## Full test execution

Command:

`cd android && ./gradlew createDebugUnitTestCoverageReport` (runs the whole Android unit test suite instrumented) and `cd android && ./gradlew lintDebug`

Result:

Pass (both BUILD SUCCESSFUL; the coverage run took 6 min 25 s).

## .taskcheck

Result:

Pass: `sh scripts/check.sh` -> tfp gate tests OK (22), server ktlint + tests, web eslint + 4752 vitest tests + build, android ktlint + unit tests + lint: "all checks passed". The web build prints its existing initial bundle budget warning (not related to this task).

## Bugs found

No production defect was found: every new test passed against the production code as committed.

Test-suite breakage found and fixed in test files (not a production defect):

### Task commit broke the Android unit test build and 9 existing tests

Finding ID:

`TW-1`

Test:

`NoteEditorChecklistLinksViewModelTest` (compile error), 8 tests in `NoteEditorsTest` (MockKException: no answer for `NotesRepository.tags()`), `NotesRepositoryTest.saving a note patches only title and body with its version as If-Match` (sent keys included `tags`)

Production source:

`android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorViewModel.kt:128` (new last constructor parameter `tagSource`), `NoteEditors.kt:80` (`NoteTagSource { notes.tags() }`), `NotesRepository.kt:175` (tags sent when they differ from the base)

Requirement:

Criterion 3: `./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass.

Expected behavior:

The existing unit tests compile and pass with the task change.

Actual behavior:

At acf2ddefb as committed, `:app:compileDebugUnitTestKotlin` failed with "Argument type mismatch" (the lambda was expected to be a `NoteTagSource`, a suspend function without parameters), so no Android unit test ran. After fixing the compile error, 9 tests failed for the two reasons above.

Severity:

High for the gate (it would have failed `.taskcheck`), but no production change is needed.

Fix required:

No production fix. Resolved by the three test updates listed under "Tests added or updated"; the parent must commit those test files together with the new ones.

Observations (no test failure, not required by the criteria, no fix requested):
- `NoteDraft.tags` defaults to an empty list, so a future caller that builds a `NoteDraft` without tags for a note that has tags would clear them. Today the only caller (`NoteEditorUiState.draft`) always passes the tags.
- The client does not reject a tag with a NUL character, which the server refuses with 400 (`NoteService.normalizeTags`); the save would then show FAILED. Not reachable from normal typing.

## Coverage

Status: verified (fresh run, exit status 0).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured separately: executable lines (JaCoCo `line` entries) intersected with lines added/modified in `git diff -U0 28a7c555..acf2ddefb`; 123/123 changed executable lines covered (NoteTagsEditor 70/70, NoteEditorViewModel 36/36, NoteEditorScreen 13/13, NoteEditors 2/2, NotesRepository 2/2). Changed-branch coverage is not inferred from this.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-notes-tags; base 28a7c555da91462a187b1774f4ebf824777d5445 (from the parent); HEAD acf2ddefb8e40b4c6ab8bab5b91a34747cc83e6c; single task code commit acf2ddefb (rebased 00c7e5be0, Android content identical), the other commits in the range are board commits.
- Source/test snapshot identifier (including uncommitted inputs): production = HEAD acf2ddefb (no uncommitted production changes). Uncommitted test files, sha256: NoteEditorTagsViewModelTest.kt 437d06f36a2633bbc3abc8b39f93018bb62076eb4648300e695d7bbf44d9dbcb, NoteTagsEditorTest.kt cc2b506d75805dd5749122ebb044a3c5f59fa40153826c50a960fd0b87f4415c, NotesRepositoryTest.kt 1f29ef0006d659e3ba3597b9f034061563445d34de0448f482c1398509e02069, NoteEditorsTest.kt 34d389cf5157c3f9a8d9d945dc3c9850815fe3043a66f3e3f3704bd8c496987a, NoteEditorChecklistLinksViewModelTest.kt a9c6b75f932c1072e021075e269f3c4925866b3dd92f662c5fd5c3b51df7ed81. No test or source edits were made during or after the run.
- Exact included files: android/app/src/main/java/ch/sonensei/cockpit/notes/{NoteTagsEditor.kt, NoteEditorViewModel.kt, NoteEditorScreen.kt, NoteEditors.kt, NotesRepository.kt}. Excluded: res/values/strings_notes.xml and res/values-de/strings_notes.xml (resources, not executable; checked by lintDebug). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (version as configured by AGP, not recorded separately); `./gradlew createDebugUnitTestCoverageReport` in `android/`; the whole debug unit test suite.
- Run start/completion time and exit status: 2026-11-09T03:54Z to 2026-11-09T04:00:51Z, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, written 2026-11-09T04:00:45Z, sha256 556bc56c96033d0de7bc841dd8b9a3179e8b353c4013d63127b3f78438b77ce4.
- Evidence outputs are fresh and inputs stayed unchanged: yes (report written by this run; the test files were hashed before the run and not edited after).
- Final run after latest test additions and production fixes: yes (no production fixes were made).

Values below use covered/total (percentage); statements = JaCoCo INSTRUCTION counter, functions = METHOD.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteTagsEditor.kt` | 923/925 (99.8%) | 22/26 (84.6%) | 23/23 (100.0%) | 70/70 (100.0%) |
| `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorViewModel.kt` | 3380/3405 (99.3%) | 236/254 (92.9%) | 90/92 (97.8%) | 301/301 (100.0%) |
| `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorScreen.kt` | 4623/4729 (97.8%) | 163/187 (87.2%) | 82/101 (81.2%) | 336/342 (98.2%) |
| `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditors.kt` | 314/321 (97.8%) | 13/14 (92.9%) | 12/13 (92.3%) | 41/41 (100.0%) |
| `android/app/src/main/java/ch/sonensei/cockpit/notes/NotesRepository.kt` | 1067/1082 (98.6%) | 57/60 (95.0%) | 48/50 (96.0%) | 116/118 (98.3%) |
| Aggregate (summed counters, all 5 files measured, none missing) | 10307/10462 (98.5%) | 491/541 (90.8%) | 255/279 (91.4%) | 864/872 (99.1%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `NoteTagsEditor.kt:78` (1/2 branches) | Compose-generated default-argument branch for `modifier` | Non-material: generated code |
| `NoteTagsEditor.kt:129` (1/4), `:134` (2/8) | Focus/visibility combinations, e.g. text non-blank while unfocused (the text is cleared on blur, so not reachable by user input) | Non-material; visible/hidden suggestions, blur commit and disabled state are tested |
| `NoteEditorViewModel.kt:544` (1/2) | `adoptSavedTags`: `note ?: return this`; the note is always set right before it is called | Non-material defensive branch |
| `NoteEditorViewModel.kt:550` (1/10) | One short-circuit combination of `isSaved()` | Non-material; saved/unsaved by title, body and tags are each tested |
| `NoteEditorScreen.kt:184-186, 396, 515-516`; `NotesRepository.kt:266-267` | Pre-existing code outside this task's changes | Out of task scope (all changed lines are covered) |

## Unable to test

- Touch-level focus order between tapping a suggestion chip and the input losing focus (on a device a blur could commit the partly typed text before the chip's click). Robolectric does not reproduce IME/touch focus transfer; the tap and the blur commit are tested separately. Follow-up only if seen on a device.
- Server spelling adoption against the real server (that other notes' spelling wins) is tested with a mocked repository answer only; no Testcontainers/end-to-end run was named by the criteria.

## Summary

Tests added and all validation passed. No production defect found. The task commit as delivered broke the Android unit test build and 9 existing tests (TW-1); this was fixed in test files only, and the parent must commit those updates with the new tests.
