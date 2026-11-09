# Test Report

Date: 2026-11-08

Task: and-journal-full - Android journal: other days, mood, tags, calendar, on this day

Scope: `git diff 3a37b31dd2ac35bea57c67d10ca75a632c22e4c4..bc8e86ff5ae0a35b664f80f659b12fb5ebbe92fa -- android/` (base = `git merge-base HEAD origin/main`; task commits 9cb2c85d, 75ffde56, 60018728, 41597913, 4ef85c16, bc8e86ff; branch main). The working tree was clean at the start; the only uncommitted file is the test added by this run (see below).

Requirements:
- `tasks/and-journal-full.md` acceptance criteria and its three recorded assumptions
- SPEC.md §8.5 (journal), `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, AGENTS.md project notes
- Previous reports `reviews/and-journal-full/review.md` and `fixes.md` (CR-1 photo day after process death, CR-2 on-this-day loading state)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/journal/JournalDatePickerScreenTest.kt` (new, untracked)

## Tests added or updated

Count: 2

Existing journal tests (about 130 in 16 files, written earlier for this task) were read and mapped to the criteria below. They already cover day navigation, saving before leaving a day, mood, tags, the month calendar, on this day, the race/late-answer cases, the CR-1/CR-2 fixes, locale and German strings. The one real gap found: the date picker test only confirmed the already selected day, so neither choosing a *different* day in the picker nor the picker's "no day after today" restriction was tested. Added:

- `another day chosen in the picker is the day opened`: opens the picker on 2026-10-05, taps October 12, OK -> `openDay(2026-10-12)` and the dialog closes.
- `days up to today can be chosen, days after today cannot`: with today 2026-10-22, the picker's October 22 is enabled, October 23 and 31 are not.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. Previous/next day, Today, date picker; other days editable | `JournalDaysViewModelTest` (previous/next day, no day after today, Today and date picker open any day which can be edited, autosave before leaving, day not saved / conflict stays open, failed load can be left); `JournalDaysRaceViewModelTest` (late loads, navigation during save, Today cancels navigation, no navigation during upload, after midnight); `JournalDaysScreenTest` (day bar, date picker confirm, DAY_NOT_SAVED); `JournalDaysScreenStatesTest` (cancelled picker); new `JournalDatePickerScreenTest` (2 tests) | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` | Pass |
| 2. Mood selector and tags editing | `JournalDaysViewModelTest` (mood set/changed/cleared, tags list/dedupe/tidy, mood alone creates entry, conflict keeps mood/tags); `JournalDaysRaceViewModelTest` (tags order/case, clearing tags, locked while loading/conflict); `JournalDaysScreenTest` + `JournalDaysScreenStatesTest` (mood choices, tags field, tidy on focus loss, locked during conflict); `JournalRepositoryTest` (draft mood/tags sent, text-only save keeps them); `JournalTextTest` (tag parsing, draft comparison) | same | Pass |
| 3. Month calendar marking days with entries, tap opens the day | `JournalDaysViewModelTest` (marks, follows opened day, browse months, retry failed month, saved entry marked); `JournalDaysRaceViewModelTest` + `JournalLateAnswersViewModelTest` (stale month answers, saves during month load); `JournalDaysScreenTest` (marks, tap opens, future day disabled, retry); `JournalLocaleScreenTest`; `JournalTextTest` (week layout) | same | Pass |
| 4. "On this day" in earlier years | `JournalDaysViewModelTest`, `JournalLateAnswersViewModelTest` (late/failed answers, loading flag of CR-2), `JournalDaysRaceViewModelTest` (stale answer, retry), `JournalDaysScreenTest` + `JournalDaysScreenStatesTest` (list, excerpt, open, none, failed, progress while loading), `JournalRepositoryTest` (earlier years only) | same | Pass |
| 5. New strings in English and German | `JournalGermanTest` (every EN string/plural has a DE one and vice versa; German UI); lint `MissingTranslation` in `lintDebug` | same | Pass |
| 6. Unit/Compose tests cover the new behavior; Android check passes | all of the above; `JournalPhotoAfterRestoreRouteTest` and `JournalPhotoDayChangedScreenTest` for CR-1 | same | Pass (Android part). `sh scripts/check.sh`: Not run: the parent asked not to run it, its server part needs Docker (Testcontainers), which is unavailable here |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.journal.JournalDatePickerScreenTest'`

Result:

Pass (2 tests, 0 failures, 0 skipped)

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport --console=plain`

Result:

Pass: BUILD SUCCESSFUL in 4m 31s; 4584 unit tests, 0 failures, 0 errors, 0 skipped (summed from `app/build/test-results/testDebugUnitTest/*.xml`); ktlintCheck and lintDebug passed.

## .taskcheck

Result:

Not run: per the parent's instruction (no Docker here, so the server part of `sh scripts/check.sh` cannot pass). The Android part of the check (`ktlintCheck testDebugUnitTest lintDebug`) passed above. Web was not touched by this task.

## Bugs found

None. No test exposed a production defect.

Observation (not a defect, no fix required): `JournalDraft.isEmpty` (`JournalRepository.kt:46`) is not used by production code; the "an entry is only created once it has text, a mood or a tag" rule works through `isSaved()` comparing the draft with the empty draft. It is tested in `JournalTextTest` only.

## Coverage

Status: verified (fresh run on the final test snapshot, exit status 0).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured separately. Method: added/modified lines from `git diff -U0 3a37b31d..HEAD` per file, intersected with the lines JaCoCo reports as executable in `report.xml`; a line counts as covered when its covered-instruction count (`ci`) is > 0. Result: 503/505 changed executable lines (99.6%); branches on changed lines 288/327 (88.1%; this is the branch counter of changed lines, not a separate changed-branch analysis).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-journal-full`; base `3a37b31dd2ac35bea57c67d10ca75a632c22e4c4` (`git merge-base HEAD origin/main`, given by the parent); HEAD `bc8e86ff5ae0a35b664f80f659b12fb5ebbe92fa`; task commits = the six commits base..HEAD, all touching only `android/` and `reviews/and-journal-full/`, matching the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD bc8e86ff plus one untracked file `android/app/src/test/java/ch/sonensei/cockpit/journal/JournalDatePickerScreenTest.kt`, sha256 `b09263e15f547cd5594a889b8d668675cf731332d1316c35fba7c23102e79771`; `git status --short` and the hash were identical before (21:01:50) and after (21:06:31) the run.
- Exact included files; renamed/deleted/excluded files and reasons: included `android/app/src/main/java/ch/sonensei/cockpit/journal/{JournalRepository,JournalScreen,JournalText,JournalViewModel}.kt`. Excluded: `android/app/src/main/res/values/strings_journal.xml` and `values-de/strings_journal.xml` (resources, not instrumentable; checked by `JournalGermanTest` and lint `MissingTranslation`). No renamed or deleted files. No scoped file is missing from the report.
- Provider/version, command, working directory, and test selection: JaCoCo 0.8.15.202606040825 via AGP `enableUnitTestCoverage`; `./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport --console=plain` in `android/`; full debug unit test suite (`testDebugUnitTest` re-executed because the test sources changed).
- Run start/completion time and exit status: 2026-11-08 21:01:55 to about 21:06:25 +01:00, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` (sha256 `8dbdc7af04401ff034c9593bbaddbc4174871ab65c4b913df607f27bb591d31f`, mtime 2026-11-08 21:06:24 +01:00), `.../index.html`.
- Evidence outputs are fresh and inputs stayed unchanged: yes; the report mtime is within the run, the test count (4584 = the previous 4582 + 2 new) shows the suite ran with the new test, and the snapshot was unchanged.
- Final run after latest test additions and production fixes: yes (covers the CR-1/CR-2 fixes in bc8e86ff and the tests added here).

Values below use covered/total (percentage) from the JaCoCo XML sourcefile counters in package `ch/sonensei/cockpit/journal`. Statements = JaCoCo INSTRUCTION counter (JaCoCo has no statement counter).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `JournalViewModel.kt` | 2922/2981 (98.0%) | 182/204 (89.2%) | 78/78 (100.0%) | 254/256 (99.2%) |
| `JournalScreen.kt` | 6719/6877 (97.7%) | 275/337 (81.6%) | 119/138 (86.2%) | 502/511 (98.2%) |
| `JournalRepository.kt` | 787/809 (97.3%) | 39/42 (92.9%) | 46/50 (92.0%) | 91/95 (95.8%) |
| `JournalText.kt` | 228/228 (100.0%) | 6/6 (100.0%) | 4/4 (100.0%) | 21/21 (100.0%) |
| **Total (summed counters, 4 of 4 Kotlin files; 2 string resource files excluded)** | 10656/10895 (97.8%) | 502/589 (85.2%) | 247/270 (91.5%) | 868/883 (98.3%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `JournalViewModel.kt:413-414` | Upload finishing after the shown day changed -> PHOTO_DAY_CHANGED | Justified gap: unreachable today, `openDay` refuses while `uploading` is set (tested by `no other day opens while a photo uploads`); defensive guard, as the review also notes. |
| `JournalViewModel.kt` partial branches (e.g. 157, 249, 298, 340) | Kotlin null-safety/`?.`/short-circuit branches of guards | All guard outcomes that matter (stale answers, navigation during save/upload, conflict, unloaded) are tested; remaining branches are compiler-generated combinations. No test added. |
| `JournalScreen.kt:203-204, 225-231` | Gallery result callback, camera app missing (`ActivityNotFoundException`), gallery launch | Pre-existing code (not changed by this task); the camera path of the route is exercised by `JournalPhotoAfterRestoreRouteTest`. No test added. |
| `JournalScreen.kt:702, 779, 781` | SAVING status label, PHOTO_UNREADABLE / PHOTO_FAILED banner texts | Pre-existing lines outside the task's diff. No test added. |
| `JournalScreen.kt:418, 425` (partial) | Date picker year restriction; picker confirmed without a selection | Day-level restriction now tested (`days up to today can be chosen, days after today cannot`); the year callback only saw years up to today in the tests (the year list was not opened), and a confirm without a selection (picker starts with the shown day selected; only reachable by clearing the text input mode) is not tested. Low risk: the view model also clamps any day after today (`no day after today can be opened`). |
| `JournalScreen.kt:191-246` partial branches | Compose-generated `rememberSaveable`/lambda-stability branches in `JournalRoute` | Not behavior; no test added. |
| `JournalRepository.kt:192-199` | `IllegalArgumentException`/`DateTimeException` mapping to `Failed(null)` | Pre-existing code outside the task's diff; `SerializationException` mapping is tested (`an unreadable answer is a failure without a status`). |
| `JournalRepository.kt:46` (partial), `:223` (partial) | `JournalDraft.isEmpty` short-circuits (unused in production); non-2xx with null body | Not behavior of the task (see observation); `:223` is pre-existing. |

## Unable to test

- `sh scripts/check.sh` (criterion 6): not run here because its server part needs Docker for Testcontainers. Follow-up: the parent records criterion 6's check part as skipped with that reason or runs it where Docker exists.
- Real camera/gallery apps and true process death: replaced by Robolectric with a stand-in `ActivityResultRegistry` and `StateRestorationTester` (existing `JournalPhotoAfterRestoreRouteTest`); no device here.

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient; no production defects found.
