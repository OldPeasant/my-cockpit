# Test Report

Date: 2026-11-09

Task: and-notes-tags - Note editor: tag chips with suggestions (Android)

Scope: re-run on the final code. Base b6e7dc2da474587819a07f6ccd4f4e6e03f29c6e; task commits b056900b3 (implementation), 8dc50f014 (tests + first reports), c298915fe (fixer: review findings CR-3..5). Measured HEAD ae6047d03ef0cdc92f317150ab5352492b245234 on `main`. main was merged after c298915fe; `git diff c298915fe HEAD -- android` only touches `auth/AppStartRoutingTest.kt` (another task), so the task's Android production files at HEAD are identical to c298915fe. Scope restricted to the files changed by the three task commits (`git show --stat`).

Requirements:
- tasks/and-notes-tags.md acceptance criteria (edit tags as chips with suggestions from existing tags, saved with the note; strings en + de; unit/Compose tests, `ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass) and its recorded assumptions
- reviews/and-notes-tags/review.md (CR-3 only the close icon removes, CR-4 a refused tag keeps its text, CR-5 loadKnownTags cancels the previous load) and reviews/and-notes-tags/fixes.md
- Server tag contract `NoteService.normalizeTags` (trim, one leading `#`, case-insensitive dedup, 50 chars, 50 tags)
- docs/ARCHITECTURE.md "Coverage", AGENTS.md, parent instructions

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/notes/NoteTagsEditorTest.kt` (this run: 2 tests added, uncommitted)

Task test files already committed and re-checked: `NoteEditorTagsViewModelTest.kt`, `NoteTagsEditorTest.kt`, `NotesRepositoryTest.kt`, `NoteEditorsTest.kt`, `NoteEditorChecklistLinksViewModelTest.kt`.

## Tests added or updated

Count: 2 added in this run (no existing test changed).

- `leaving the input keeps a typed tag that is not added`: CR-4 on the third path through `commit()` (focus loss). The fixer's tests cover the comma and Enter paths only.
- `commas with nothing between them add no tag`: the blank-part branch of the fixer's new refused-tag filter (`NoteTagsEditor.kt:116`), which no test reached (branch 4/6 before, 6/6 after).

Fixer's changes, already covered by tests in c298915fe (checked, all pass):
- CR-3: `tapping the chip itself does not remove the tag` (a tap on the chip label removes nothing); `each tag shows as a chip with a hash, and tapping its remove icon removes it`; `nothing can be removed or suggested while the note cannot be edited` (now taps both the chip and the close icon while disabled); the screen wiring test now removes via the close icon.
- CR-4: `a tag typed before a comma that is not added stays in the input`, `Enter keeps a typed tag that is not added`.
- CR-5: `an older load of the tags in use that ends last does not replace the newer one` (deferred answer controls the order, no real delays).

## Requirement coverage

Command F (focused): `android/gradlew -p android ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*'` -> BUILD SUCCESSFUL, 272 tests in the notes package, 0 failures, 0 skipped (NoteEditorTagsViewModelTest 20, NoteTagsEditorTest 16, NotesRepositoryTest 35).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Tags load with the note; tags in use load (most used first), again on a new opening | `the note's tags load with it, and the tags in use load most used first`; `the tags in use load again when the note is opened again, not on the same opening` | F | Pass |
| An older tags-in-use load never replaces a newer one (CR-5) | `an older load of the tags in use that ends last does not replace the newer one` | F | Pass |
| Add: trim, leading `#`, unsaved, autosaved; case duplicate rejected; known spelling adopted; limits (blank, 50/51 chars, 50 tags) | `an added tag is trimmed, loses a leading hash, counts as unsaved and is autosaved with the text`; `normalizeTag trims and drops one leading hash, as the server does`; `a tag already on the note in any case is not added again`; `a tag that differs from one in use only in case takes that spelling, an unknown tag keeps its own`; `an empty, blank or hash-only tag is not added`; `a tag of 50 characters is added, one of 51 is not`; `a note takes 50 tags at most` | F | Pass |
| Remove; add+remove back needs no save | `a removed tag is autosaved, and a tag not on the note changes nothing`; `adding and removing a tag back needs no save` | F | Pass |
| Saved with the note (one save, server spelling adopted, tag added during a save, new note, photo-created note) | `tags are saved with the text in one save`; `the server's spelling of the saved tags is taken, so they do not count as unsaved`; `a tag added while a save runs is kept and saved next, over the version that save returned`; `a new note with only a tag is created with it`; `a photo creating a new note sends its tags and takes the server's spelling` | F | Pass |
| Not editable while loading / conflict; use theirs takes the other tags | `tags cannot change while the note loads or a conflict waits, and use theirs takes the other tags` | F | Pass |
| Suggestions: tags in use minus the note's (any case); none on load failure; filter order, max 8 | `suggestions are the tags in use not on the note yet, in any case`; `when the tags in use cannot load there are no suggestions, and tags can still be added`; `a tag on the note is not suggested`; `matchingTags puts tags starting with the text first, then those containing it, at most eight` | F | Pass |
| Repository sends `tags` only when changed (create: only when non-empty) | `changed tags are patched as the whole list, in their order`; `removing the last tag patches an empty list, so the server clears them`; `tags that only changed in order or case count as changed`; `a new note sends its tags when it has any, and no tags field without`; `saving a note patches only title and body with its version as If-Match` | F | Pass |
| Compose: only the close icon removes a tag (CR-3); nothing removable when disabled | `each tag shows as a chip with a hash, and tapping its remove icon removes it`; `tapping the chip itself does not remove the tag`; `nothing can be removed or suggested while the note cannot be edited` | F | Pass |
| Compose: comma / Enter / blur add; blank parts add nothing | `typing a comma adds the tag typed before it and keeps what follows`; `Enter adds the typed tag and clears the input, a blank input adds nothing`; `leaving the input adds the tag typed in it`; `commas with nothing between them add no tag` (new); `splitTagInput splits at commas and new lines, keeping the part still typed` | F | Pass |
| Compose: a refused tag keeps its text on comma, Enter and blur (CR-4) | `a tag typed before a comma that is not added stays in the input`; `Enter keeps a typed tag that is not added`; `leaving the input keeps a typed tag that is not added` (new) | F | Pass |
| Compose: suggestion tap adds | `suggestions show while the input is used, filtered by what is typed, and a tap adds one` | F | Pass |
| Editor screen wiring (edit and rendered view) | `the editor screen shows the tags and passes add and remove to the view model callbacks`; `the rendered note shows its tags too` | F | Pass |
| New strings in English and German | No unit test; `notes_tag_add`, `notes_tag_remove` exist in `values/` and `values-de/` strings_notes.xml; lint `MissingTranslation` | `android/gradlew -p android createDebugUnitTestCoverageReport lintDebug` | Pass |
| ktlintCheck testDebugUnitTest lintDebug and scripts/check.sh pass | whole suites | F; coverage run (full testDebugUnitTest, 5289 tests) + lintDebug; `sh scripts/check.sh` | Pass |

## Focused test execution

Command:

`android/gradlew -p android ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*'`

Result:

Pass (272 tests, 0 failures, 0 skipped; ktlintCheck incl. the test source set passed).

## Full test execution

Command:

`android/gradlew -p android createDebugUnitTestCoverageReport lintDebug` (whole Android debug unit suite, instrumented, plus lint)

Result:

Pass (BUILD SUCCESSFUL, 5289 tests, 0 failures). An earlier coverage attempt (before the second added test) failed once on `ch.sonensei.cockpit.files.FilePreviewScreenTest > an image that failed to load says so, and retry loads it again` (expected 2 but was 1); that class passed when run alone (`android/gradlew -p android testDebugUnitTest --tests 'ch.sonensei.cockpit.files.FilePreviewScreenTest'`) and in both later full runs. It is in the `files` package, outside this task: treated as a load-related flake, no numbers from that failed run are used.

## .taskcheck

Result:

Pass: `sh scripts/check.sh` -> tfp gate tests OK (22), server ktlint + tests, web eslint + 4768 vitest tests + build (existing initial bundle budget warning, unrelated), android ktlint + unit tests + lint: "all checks passed". Run on HEAD ae6047d03 plus the uncommitted `NoteTagsEditorTest.kt`.

## Bugs found

No production defect found: every test passed against the production code at c298915fe (identical at HEAD).

Observations (no test failure, no fix requested):
- A refused tag is put back joined with `", "` while the text still being typed is not trimmed, so typing `home, wor` with `home` refused leaves `home,  wor` (two spaces). Cosmetic only; the next commit trims.
- The tag chip keeps `onClick = {}` with `enabled`, so it is still a clickable, focusable node that does nothing (TalkBack announces an action without effect). The close icon has its own `Role.Button` and content description, so removal stays accessible. Left to the reviewer.
- Carried over from the first run: `NoteDraft.tags` defaults to an empty list (a future caller omitting tags would clear them; the only caller passes them), and a tag containing NUL is accepted by the client but refused by the server (400). Not reachable by normal typing.

## Coverage

Status: verified (fresh run after the last test change, exit status 0).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured separately: JaCoCo `line` entries (executable lines) at HEAD intersected with the lines `git blame HEAD` attributes to the task code commits b056900b3 and c298915fe: 127/127 changed executable lines covered (NoteTagsEditor 72/72, NoteEditorViewModel 38/38, NoteEditorScreen 13/13, NoteEditors 2/2, NotesRepository 2/2). Changed-branch coverage is not inferred from this.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-notes-tags; base b6e7dc2da474587819a07f6ccd4f4e6e03f29c6e (from the parent); HEAD ae6047d03ef0cdc92f317150ab5352492b245234; task commits b056900b3, 8dc50f014, c298915fe (parent); file scope from `git show --stat` of those commits.
- Source/test snapshot identifier (including uncommitted inputs): HEAD ae6047d03 with one uncommitted file, `android/app/src/test/java/ch/sonensei/cockpit/notes/NoteTagsEditorTest.kt` sha256 f32283f6aa5a1838796d1778d89128d6496279567c5ad71afb504b89d00e39f8 (hashed before and after the coverage run, unchanged). No uncommitted production changes.
- Exact included files: android/app/src/main/java/ch/sonensei/cockpit/notes/{NoteTagsEditor.kt, NoteEditorViewModel.kt, NoteEditorScreen.kt, NoteEditors.kt, NotesRepository.kt}. Excluded: res/values/strings_notes.xml and res/values-de/strings_notes.xml (resources, not executable; checked by lintDebug). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (version as configured by AGP, not recorded separately); `android/gradlew -p android createDebugUnitTestCoverageReport lintDebug` from the repository root (project dir `android/`); the whole debug unit test suite.
- Run start/completion time and exit status: 2026-11-09T04:37:37Z to about 04:43Z, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, written 2026-11-09T04:41:27Z, sha256 08e5aaed7923a43cee6699fe087db1d1ac6e706e9892bb69437fd74bc4b1ab9b.
- Evidence outputs are fresh and inputs stayed unchanged: yes (report written by this run; the test file hash is the same before and after).
- Final run after latest test additions and production fixes: yes (after both added tests; the fixer's production changes c298915fe are included).

Values below use covered/total (percentage); statements = JaCoCo INSTRUCTION counter, functions = METHOD.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteTagsEditor.kt` | 979/981 (99.8%) | 32/36 (88.9%) | 23/23 (100.0%) | 72/72 (100.0%) |
| `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorViewModel.kt` | 3391/3416 (99.3%) | 238/256 (93.0%) | 90/92 (97.8%) | 303/303 (100.0%) |
| `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorScreen.kt` | 4623/4729 (97.8%) | 163/187 (87.2%) | 82/101 (81.2%) | 336/342 (98.2%) |
| `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditors.kt` | 314/321 (97.8%) | 13/14 (92.9%) | 12/13 (92.3%) | 41/41 (100.0%) |
| `android/app/src/main/java/ch/sonensei/cockpit/notes/NotesRepository.kt` | 1067/1082 (98.6%) | 57/60 (95.0%) | 48/50 (96.0%) | 116/118 (98.3%) |
| Aggregate (summed counters, all 5 files measured, none missing) | 10374/10529 (98.5%) | 503/553 (91.0%) | 255/279 (91.4%) | 868/876 (99.1%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `NoteTagsEditor.kt:116` (was 4/6 branches) | Fixer's refused-tag filter: blank parts between commas must not reach onAdd | Test added: `commas with nothing between them add no tag` (now 6/6) |
| `NoteTagsEditor.kt:79` (1/2) | Compose-generated default-argument branch for `modifier` | Non-material: generated code |
| `NoteTagsEditor.kt:135` (3/4), `:140` (6/8) | Focus/visibility combinations (e.g. focus gained while already focused; text non-blank while unfocused and disabled) | Non-material; blur commit, kept text on blur, visible/hidden suggestions and disabled state are tested |
| `NoteEditorViewModel.kt:550` (1/2) | `adoptSavedTags`: `note ?: return this`; the note is always set right before it is called | Non-material defensive branch |
| `NoteEditorViewModel.kt:556` (9/10) | One short-circuit combination of `isSaved()` for a new note | Non-material; saved/unsaved by title, body and tags are each tested |
| `NoteEditorViewModel.kt:345` (7/8 in this run, 8/8 in the run before) | Pre-existing `save()` guard (commit 08915b4b5, not this task); hit varies with test timing | Out of task scope |
| `NoteEditorScreen.kt:184-186, 396, 515-516`; `NotesRepository.kt:266-267`; remaining partial branches in those files | Pre-existing code outside this task's changes | Out of task scope (all changed lines are covered) |

## Unable to test

- Touch-level focus order between tapping a suggestion chip and the input losing focus (on a device a blur could commit the partly typed text before the chip's click). Robolectric does not reproduce IME/touch focus transfer; the tap and the blur commit are tested separately. Follow-up only if seen on a device.
- The CR-3 test taps the start of a long chip label, because the close icon's minimum touch target (48 dp) covers most of a short chip; on a real device a tap on a short chip may still hit the close icon. Behaviour on device not verified (no device/emulator run named by the criteria).
- Server spelling adoption against the real server is tested with a mocked repository answer only; no Testcontainers/end-to-end run was named by the criteria.

## Summary

Tests added and all validation passed. The fixer's changes (CR-3, CR-4, CR-5) were already covered by tests; two tests were added for the blur path of CR-4 and the blank-part branch of the new refused-tag filter. No production defect found. The parent must commit the updated `NoteTagsEditorTest.kt` together with this report.
