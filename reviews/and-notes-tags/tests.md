# Test Report

Date: 2026-11-09

Task: and-notes-tags - Note editor: tag chips with suggestions (Android)

Scope: re-run on the final code after fix round 2 (R2-CR-1, R2-CR-2). The task history was rebased onto a linear history after the claim, so the commit hashes the parent named (b056900b3, 8dc50f014, c298915fe, 9cfe04f3e) now correspond to f9261427b (implementation), 9bf02a1a3 (tests + first reports), f1fd442b8 (fixer CR-3..5), 8437a62f6 (test re-run) and 9f8ba2a2f (fixer R2-CR-1, R2-CR-2), identical subjects and file lists. Measured HEAD 9f8ba2a2f800b9c253bffe63423f66484a6e0675 on `main`, clean working tree. Base: the claim commit 1bf4ebbbe247327be33733ea3cfc1dabefcf9b7e; `git diff --stat 1bf4ebbbe HEAD` contains only this task's Android files, reviews and the task file, so it is used as the task diff.

Requirements:
- tasks/and-notes-tags.md acceptance criteria (edit tags as chips with suggestions from existing tags, saved with the note; strings en + de; unit/Compose tests, `ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass) and its recorded assumptions
- reviews/and-notes-tags/review.md (re-review findings CR-1/CR-2, recorded as R2-CR-1/R2-CR-2) and reviews/and-notes-tags/fixes.md "Round 2"
- Server tag contract `NoteService.normalizeTags` (trim, one leading `#`, case-insensitive dedup, 50 chars, 50 tags)
- docs/ARCHITECTURE.md "Coverage", AGENTS.md, parent instructions

## Test files changed

None in this run. Task test files re-checked against the final code: `NoteTagsEditorTest.kt` (20 tests), `NoteEditorTagsViewModelTest.kt` (21), `NotesRepositoryTest.kt`, `NoteEditorsTest.kt`, `NoteEditorChecklistLinksViewModelTest.kt`.

## Tests added or updated

Count: 0.

The fixer's round 2 tests already cover the final behavior, and coverage shows no changed line left unexecuted (73/73 in `NoteTagsEditor.kt`); the two remaining partial branches are Compose-generated or a focus combination (see Coverage). Checked against the final `take()` / `keptTagInput()` / `TagChip` code:
- R2-CR-1 (comma path): `a refused tag, a comma and a second tag typed one key at a time add only the second tag` (key-by-key typing, Enter, no part with a comma offered); `typing after a refused tag keeps its spacing and never adds a tag with a comma` (stable spacing, blur commit); `a tag typed before a comma that is not added stays in the input` (now expects `home,` and `home,wor`); `keptTagInput keeps refused parts as typed, each with its comma, then the part still typed`; view model guard `a tag containing a comma or a line break is not added`.
- R2-CR-2 (chip not clickable): `the chip has no click action, only its remove button does` (no OnClick on the chip, Button role and content description on the remove button); `tapping the chip itself does not remove the tag`; `nothing can be removed or suggested while the note cannot be edited`.

## Requirement coverage

Command F (focused): `android/gradlew -p android ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*'` -> BUILD SUCCESSFUL, 277 tests in the notes package, 0 failures, 0 skipped (NoteTagsEditorTest 20, NoteEditorTagsViewModelTest 21, NotesRepositoryTest 35).
Command C (full): `android/gradlew -p android ktlintCheck createDebugUnitTestCoverageReport lintDebug` -> BUILD SUCCESSFUL, 5294 tests, 0 failures, 0 errors, 0 skipped.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Tags load with the note; tags in use load (most used first), again on a new opening; an older load never replaces a newer one | `the note's tags load with it, and the tags in use load most used first`; `the tags in use load again when the note is opened again, not on the same opening`; `an older load of the tags in use that ends last does not replace the newer one` | F, C | Pass |
| Add: trim, leading `#`, unsaved, autosaved; case duplicate rejected; known spelling adopted; limits (blank, 50/51 chars, 50 tags); no comma or line break | `an added tag is trimmed, loses a leading hash, counts as unsaved and is autosaved with the text`; `normalizeTag trims and drops one leading hash, as the server does`; `a tag already on the note in any case is not added again`; `a tag that differs from one in use only in case takes that spelling, an unknown tag keeps its own`; `an empty, blank or hash-only tag is not added`; `a tag of 50 characters is added, one of 51 is not`; `a note takes 50 tags at most`; `a tag containing a comma or a line break is not added` | F, C | Pass |
| Remove; add+remove back needs no save | `a removed tag is autosaved, and a tag not on the note changes nothing`; `adding and removing a tag back needs no save` | F, C | Pass |
| Saved with the note (one save, server spelling adopted, tag added during a save, new note, photo-created note) | `tags are saved with the text in one save`; `the server's spelling of the saved tags is taken, so they do not count as unsaved`; `a tag added while a save runs is kept and saved next, over the version that save returned`; `a new note with only a tag is created with it`; `a photo creating a new note sends its tags and takes the server's spelling` | F, C | Pass |
| Not editable while loading / conflict; use theirs takes the other tags | `tags cannot change while the note loads or a conflict waits, and use theirs takes the other tags` | F, C | Pass |
| Suggestions: tags in use minus the note's (any case); none on load failure; filter order, max 8; tap adds | `suggestions are the tags in use not on the note yet, in any case`; `when the tags in use cannot load there are no suggestions, and tags can still be added`; `a tag on the note is not suggested`; `matchingTags puts tags starting with the text first, then those containing it, at most eight`; `suggestions show while the input is used, filtered by what is typed, and a tap adds one` | F, C | Pass |
| Repository sends `tags` only when changed (create: only when non-empty) | `changed tags are patched as the whole list, in their order`; `removing the last tag patches an empty list, so the server clears them`; `tags that only changed in order or case count as changed`; `a new note sends its tags when it has any, and no tags field without`; `saving a note patches only title and body with its version as If-Match` | F, C | Pass |
| Compose: chip is not a control; only its remove button removes (CR-3, R2-CR-2); nothing removable when disabled | `the chip has no click action, only its remove button does`; `each tag shows as a chip with a hash, and tapping its remove icon removes it`; `tapping the chip itself does not remove the tag`; `nothing can be removed or suggested while the note cannot be edited` | F, C | Pass |
| Compose: comma / Enter / blur add; blank parts add nothing | `typing a comma adds the tag typed before it and keeps what follows`; `Enter adds the typed tag and clears the input, a blank input adds nothing`; `leaving the input adds the tag typed in it`; `commas with nothing between them add no tag`; `splitTagInput splits at commas and new lines, keeping the part still typed` | F, C | Pass |
| Compose: a refused tag keeps its text, separated by its comma, on comma, Enter and blur; no tag with a comma reaches onAdd (CR-4, R2-CR-1) | `a tag typed before a comma that is not added stays in the input`; `a refused tag, a comma and a second tag typed one key at a time add only the second tag`; `typing after a refused tag keeps its spacing and never adds a tag with a comma`; `Enter keeps a typed tag that is not added`; `leaving the input keeps a typed tag that is not added`; `keptTagInput keeps refused parts as typed, each with its comma, then the part still typed` | F, C | Pass |
| Editor screen wiring (edit and rendered view) | `the editor screen shows the tags and passes add and remove to the view model callbacks`; `the rendered note shows its tags too` | F, C | Pass |
| New strings in English and German | No unit test; `notes_tag_add`, `notes_tag_remove` exist in `values/` and `values-de/` strings_notes.xml; lint `MissingTranslation`; content description "Remove tag home" asserted in English | C (lintDebug) | Pass |
| ktlintCheck testDebugUnitTest lintDebug and scripts/check.sh pass | whole suites | C; `sh scripts/check.sh` | Pass |

## Focused test execution

Command:

`android/gradlew -p android ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*'`

Result:

Pass (277 tests in 18 notes classes, 0 failures, 0 skipped; ktlintCheck incl. the test source set passed).

## Full test execution

Command:

`android/gradlew -p android ktlintCheck createDebugUnitTestCoverageReport lintDebug` (whole Android debug unit suite with JaCoCo, ktlint and Android lint)

Result:

Pass (BUILD SUCCESSFUL in 3m 37s, 5294 tests, 0 failures, 0 errors, 0 skipped; lintDebug passed).

## .taskcheck

Result:

Pass: `sh scripts/check.sh` (exit 0, about 2026-11-09T06:16Z to 06:30Z) -> tfp gate tests OK (22), server ktlint + tests, web eslint + 4768 vitest tests in 196 files + build (existing initial bundle budget warning, unrelated), android ktlint + unit tests + lint: "all checks passed". Run on HEAD 9f8ba2a2f with a clean working tree, after the coverage run (not in parallel with another Gradle run).

## Bugs found

No production defect found: every test passed against the final production code at 9f8ba2a2f.

Observations (no test failure, no fix requested):
- Tapping a suggestion sets the input to empty, so text kept in the input after a refused tag (for example `home,` when `home` is already on the note) is discarded by the tap. No requirement covers this (the web datalist replaces the input too); left as is, no test added that would pin it.
- Carried over: `NoteDraft.tags` defaults to an empty list (a future caller omitting tags would clear them; the only caller passes them), and a tag containing NUL is accepted by the client but refused by the server (400). Not reachable by normal typing.

## Coverage

Status: verified (fresh run on the final code, exit status 0).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured separately: JaCoCo `line` entries (executable lines) in report.xml intersected with the added/modified lines of `git diff -U0 1bf4ebbbe HEAD` per file: 129/129 changed executable lines covered (NoteTagsEditor 73/73, NoteEditorViewModel 39/39, NoteEditorScreen 13/13, NoteEditors 2/2, NotesRepository 2/2). Changed lines with partial branches: NoteTagsEditor.kt:125 (1/2), :166 (3/4); NoteEditorViewModel.kt:552 (1/2), :558 (9/10). Changed-branch coverage is not inferred beyond this.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-notes-tags; base 1bf4ebbbe247327be33733ea3cfc1dabefcf9b7e (claim commit; the base the parent named earlier, b6e7dc2da, predates the rebase); HEAD 9f8ba2a2f800b9c253bffe63423f66484a6e0675; task commits f9261427b, 9bf02a1a3, f1fd442b8, 8437a62f6, 9f8ba2a2f (rebased equivalents of the parent's list, matched by subject and file list); file scope from `git diff --stat 1bf4ebbbe HEAD`, which contains no other task's files.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 9f8ba2a2f with a clean working tree (no uncommitted source or test changes). sha256 before and after the run: NoteTagsEditor.kt 3694ac2c14b1af17bb2c2e34eb81b587ffede45d8b951bc6a25da2cf1d3772fe, NoteTagsEditorTest.kt 1ee3f0e75f4d92cbc62d8a118b7dc95da286c9240d9009272cdc2417d59be2ff (unchanged).
- Exact included files: android/app/src/main/java/ch/sonensei/cockpit/notes/{NoteTagsEditor.kt, NoteEditorViewModel.kt, NoteEditorScreen.kt, NoteEditors.kt, NotesRepository.kt}. Excluded: res/values/strings_notes.xml and res/values-de/strings_notes.xml (resources, not executable; checked by lintDebug). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (version as configured by AGP, not recorded separately); `android/gradlew -p android ktlintCheck createDebugUnitTestCoverageReport lintDebug` from the repository root (project dir `android/`); the whole debug unit test suite (5294 tests).
- Run start/completion time and exit status: 2026-11-09T06:12:06Z to 06:15:48Z, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, written 2026-11-09T06:15:44Z, sha256 11e4d6bf6cf8f0d0deec27e04d1da7e82fc2bdfd13b870f51758836e1e3bd17d.
- Evidence outputs are fresh and inputs stayed unchanged: yes (report written by this run, inside its time window; input hashes the same before and after; the testDebugUnitTest task executed, not up to date).
- Final run after latest test additions and production fixes: yes (includes the fixer's round 2 production change 9f8ba2a2f; no tests added afterwards).

Values below use covered/total (percentage); statements = JaCoCo INSTRUCTION counter, functions = METHOD.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteTagsEditor.kt` | 1057/1057 (100.0%) | 36/38 (94.7%) | 25/25 (100.0%) | 73/73 (100.0%) |
| `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorViewModel.kt` | 3408/3433 (99.3%) | 244/262 (93.1%) | 90/92 (97.8%) | 304/304 (100.0%) |
| `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorScreen.kt` | 4623/4729 (97.8%) | 163/187 (87.2%) | 82/101 (81.2%) | 336/342 (98.2%) |
| `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditors.kt` | 314/321 (97.8%) | 13/14 (92.9%) | 12/13 (92.3%) | 41/41 (100.0%) |
| `android/app/src/main/java/ch/sonensei/cockpit/notes/NotesRepository.kt` | 1067/1082 (98.6%) | 57/60 (95.0%) | 48/50 (96.0%) | 116/118 (98.3%) |
| Aggregate (summed counters, all 5 files measured, none missing) | 10469/10622 (98.6%) | 513/561 (91.4%) | 257/281 (91.5%) | 870/878 (99.1%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `NoteTagsEditor.kt:125` (1/2) | Compose-generated default-argument branch for `modifier` | Non-material: generated code |
| `NoteTagsEditor.kt:166` (3/4) | `focused && !it.isFocused`: the combination "not focused before and not focused now" (a focus event without a change) | Non-material; blur commit, refused text kept on blur and no commit without a previous focus are covered |
| `NoteEditorViewModel.kt:552` (1/2) | `adoptSavedTags`: `note ?: return this`; the note is always set right before it is called | Non-material defensive branch |
| `NoteEditorViewModel.kt:558` (9/10) | One short-circuit combination of `isSaved()` for a new note | Non-material; saved/unsaved by title, body and tags are each tested |
| `NoteEditorScreen.kt:184-186, 396, 515-516`; `NotesRepository.kt:266-267`; other partial branches in NoteEditorViewModel/NoteEditorScreen/NoteEditors/NotesRepository | Pre-existing code outside this task's changes | Out of task scope (every changed line is covered) |

## Unable to test

- Touch-level focus order between tapping a suggestion chip and the input losing focus (on a device a blur could commit the partly typed text before the chip's click). Robolectric does not reproduce IME/touch focus transfer; the tap and the blur commit are tested separately. Follow-up only if seen on a device.
- Real IME behaviour of the single-line input (whether a pasted line break reaches `onValueChange`): the split on line breaks and the view model guard are unit-tested, the IME itself is not. No device/emulator run is named by the criteria.
- Server spelling adoption against the real server is tested with a mocked repository answer only; no Testcontainers/end-to-end run was named by the criteria.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. No test was added in this run: the fixer's round 2 tests cover R2-CR-1 and R2-CR-2, every changed executable line is covered, and the full Android suite, ktlint and lint pass on the final code.
