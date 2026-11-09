# Test Report

Date: 2026-11-09

Task: and-notes-tags-suggest - Note tags editor: suggestion tap keeps refused text, suggestions filter by current part

Scope: base a9c691aebdd6a3096412726f941c5e8f989a28c6 .. HEAD c8e5b71d238da1f7f0ba18024dd552a3bc526926 (task commit c8e5b71d2), plus the uncommitted test addition in `android/app/src/test/java/ch/sonensei/cockpit/notes/NoteTagsEditorTest.kt` made by this test-writer.

Requirements:
- `tasks/and-notes-tags-suggest.md` acceptance criteria 1-3
- `reviews/and-notes-tags/review.md` CR-1, CR-2 (context, as named by the parent)
- `docs/ARCHITECTURE.md` "Coverage"

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/notes/NoteTagsEditorTest.kt`

## Tests added or updated

Count: 1 added (the parent's 2 tests from c8e5b71d2 were reviewed and kept unchanged)

Added: `a suggestion tap after several refused parts keeps them with their spacing and clears only the typed part`
(two refused parts with spaces, `home, trip, wo`; checks the typed part ` wo` is matched without its leading
space, that `onAdd` gets exactly `home`, ` trip`, `Work`, and that the input becomes `home, trip,`).

### Do the parent's tests fail against the old behavior?

Checked by reasoning on the old code (production code was not modified to run a mutation):

- Old filter `matchingTags(suggestions, text)` on `home,wo`: `normalizeTag` gives `home,wo`, which no suggestion
  contains, so no suggestion is shown. `after a refused tag, suggestions follow the part being typed ...` then
  fails at `assertEquals(2, suggestions())`; the other two tests fail when clicking `#Work` (node not found).
- Old tap handler (`onAdd(tag); text = ""`), with the new filter: the input becomes empty, so
  `assertInput("home,")` (first test), `assertInput("home,wo")` (refused-tap test) and `assertInput("home, trip,")`
  (added test) all fail.

So each fix (CR-1 filter, CR-2 tap) is caught by at least one test independently. The gap the added test closes:
several refused parts and the spacing of refused parts and of the typed part were not exercised through a tap.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: suggestions filtered by the part being typed (`splitTagInput(text).second`) | `after a refused tag, suggestions follow the part being typed and a tap keeps the refused part`; `a suggestion tap after several refused parts keeps them with their spacing and clears only the typed part`; existing `suggestions show while the input is used, filtered by what is typed, and a tap adds one` | `cd android && ./gradlew testDebugUnitTest --tests '*NoteTagsEditorTest*' createDebugUnitTestCoverageReport` | Pass |
| AC2: tap keeps refused parts, clears only the typed part on success | `after a refused tag, suggestions follow the part being typed and a tap keeps the refused part`; `a suggestion tap after several refused parts keeps them with their spacing and clears only the typed part` | same | Pass |
| AC2: if `onAdd` refuses, the input is unchanged | `tapping a suggestion that is refused leaves the input unchanged` | same | Pass |
| AC3: Compose tests cover both | the three tests above (Robolectric Compose tests) | same | Pass |
| AC3: `./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass | none | none | Not run: the parent asked not to run other Gradle tasks; the parent runs these |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests '*NoteTagsEditorTest*' createDebugUnitTestCoverageReport`

Result:

Pass - BUILD SUCCESSFUL in 29s; `TEST-ch.sonensei.cockpit.notes.NoteTagsEditorTest.xml`: tests=23, failures=0, errors=0, skipped=0.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Not run (parent instruction: no Gradle beyond the focused run; the parent runs it).

## .taskcheck

Result:

Not run (`sh scripts/check.sh` is left to the parent, per instruction).

## Bugs found

None.

## Coverage

Status: verified (whole-file, for the single production file in scope; coverage comes from `NoteTagsEditorTest` only, since the run was filtered to that class).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured from the JaCoCo XML line entries for the changed executable lines of the task diff (171 and 178; lines 112 and 177 are comments): line 171 mi=0 ci=24 mb=0 cb=8, line 178 mi=0 ci=15 mb=0 cb=2. Changed lines 2/2 covered, changed-line branches 10/10 covered.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-notes-tags-suggest; base a9c691aebdd6a3096412726f941c5e8f989a28c6 (given by parent); HEAD c8e5b71d238da1f7f0ba18024dd552a3bc526926; task commit c8e5b71d2 (given by parent, matches `git diff --name-only a9c691aeb..HEAD`).
- Source/test snapshot identifier (including uncommitted inputs): sha256 `NoteTagsEditor.kt` deddc31cb6aa8dc307478e773ae1d18c314b90743e71a365812bbc1198c53f82 (committed, unchanged); `NoteTagsEditorTest.kt` (uncommitted, with the added test) a04cb4a317582541c9c93ca5452710eebfab5bc3bdace815dfa5452a17815ea2. Same hashes before and after the run.
- Exact included files; renamed/deleted/excluded files and reasons: included `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteTagsEditor.kt`; excluded `NoteTagsEditorTest.kt` (test file). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (Gradle 9.8.1, OpenJDK 21.0.12.1); `./gradlew testDebugUnitTest --tests '*NoteTagsEditorTest*' createDebugUnitTestCoverageReport` in `android/`; selection `*NoteTagsEditorTest*`.
- Run start/completion time and exit status: started 2026-11-09T08:57:12+01:00, test suite timestamp 07:57:34Z, report written 08:57:45+01:00; BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` sha256 b25566ba833e1daf733cd557b42e47e4704b87f2939c767f463358a979c6b6bf; `index.html` alongside.
- Evidence outputs are fresh and inputs stayed unchanged: yes - test sources were recompiled (`compileDebugUnitTestKotlin` executed), test ran, report mtime is after the run start; input hashes identical before and after.
- Final run after latest test additions and production fixes: yes (no production fixes were made after c8e5b71d2).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteTagsEditor.kt` | 1068/1068 (100%) | 38/40 (95.0%) | 25/25 (100%) | 72/72 (100%) |

No files in scope are missing or unmeasured.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `NoteTagsEditor.kt:125` (1 of 2 branches) | Compiler-generated default-argument branch for `modifier: Modifier = Modifier`; not task behavior | Justified gap: no test added, pre-existing, untouched by the task |
| `NoteTagsEditor.kt:166` (1 of 4 branches) | `if (focused && !it.isFocused) commit()` in focus handling; pre-existing code, not changed by this task | Justified gap: outside task scope; focus loss with and without prior focus is already exercised by existing tests |

## Unable to test

- AC3 lint/ktlint/lintDebug and `sh scripts/check.sh` were not run here, by parent instruction (no other Gradle tasks). Follow-up: the parent runs them.
- The mutation check against the old code was done by reasoning, not by running the tests against reverted production code (production code must not be modified by the test-writer).

## Summary

Tests added and all validation passed (focused suite and task-scoped coverage; the broader suite and `.taskcheck` are left to the parent as instructed). No production defects found.
