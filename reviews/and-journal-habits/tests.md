# Test Report

Date: 2026-10-22

Task: and-journal-habits - Android journal and habits

Scope: base `f3c2102802292ecc2e011934b7c70f3128d02bb9`..`43c5a2346b865d449869b3a78ef74a8c721a3033` (task commits e495ab72d, 54a47eb10, 0ac22a651, 43c5a2346, after the parent's rebase; ownership from the task file and the parent's commit list). The fix commit 43c5a2346 contains CR-1 (HTTP 428 on a create is a conflict, `JournalRepository.kt`) and TW-1 (`keepMine` derives the status with `withStatus()`, `JournalViewModel.kt`). Measured state: 43c5a2346 plus one uncommitted test edit by the test-writer (`JournalRepositoryTest.kt`, +2 tests).

Requirements:
- `tasks/and-journal-habits.md` acceptance criteria and recorded assumptions (today's entry only, device clock; habits one-tap check sends targetCount / 0)
- `SPEC.md` §8.5, §9.2 item 8 (via the task notes)
- `docs/ARCHITECTURE.md` "Coverage"; `AGENTS.md`
- Parent instructions; code review finding CR-1

## Test files changed

Over the whole task, from the test-writer (the earlier ones are already committed in 43c5a2346):

- `android/app/src/test/java/ch/sonensei/cockpit/journal/JournalViewModelTest.kt` (extended, committed)
- `android/app/src/test/java/ch/sonensei/cockpit/journal/JournalScreenTest.kt` (extended, committed)
- `android/app/src/test/java/ch/sonensei/cockpit/journal/JournalRouteInAppTest.kt` (new, committed)
- `android/app/src/test/java/ch/sonensei/cockpit/habits/HabitsViewModelTest.kt` (extended, committed)
- `android/app/src/test/java/ch/sonensei/cockpit/habits/HabitsScreenTest.kt` (extended, committed)
- `android/app/src/test/java/ch/sonensei/cockpit/habits/HabitsRouteInAppTest.kt` (new, committed)
- `android/app/src/test/java/ch/sonensei/cockpit/journal/JournalRepositoryTest.kt` (this round, uncommitted, +2 tests; sha256 2c359a68c19d10bea2f6499855f9cb12c25f45bb901589275e7d0c61b8716ec7)

## Tests added or updated

Count: 25 by the test-writer (23 in the first round, 2 in this round). No existing test was changed or weakened.

This round (re-check of the fix commit):
- `JournalRepositoryTest > a conflict whose current entry cannot be fetched is reported as that problem, never as a conflict`: 412 then the refetch answers 503, giving `JournalSaveResult.Offline`. 428 (the CR-1 path) then the refetch answers 403, giving `JournalSaveResult.Failed(403)`. The save stays retryable and the user is never offered a conflict decision without the server's data.
- `JournalRepositoryTest > an unreadable answer is a failure without a status`: malformed JSON on load gives `JournalResult.Failed(null)`, and malformed JSON on save gives `JournalSaveResult.Failed(null)`.

The fixes themselves are already covered:
- CR-1: the fixer's `a create refused because the day got an entry elsewhere is a conflict` (428), plus the 428 + refetch failure case above.
- TW-1: `JournalViewModelTest > keeping my text when the server already has the same text ends as saved`. It failed before the fix and passes now.

First round (committed): 9 JournalViewModelTest (autosave ordering while a save runs, typing into a conflict, writing after a resolved conflict, TW-1, discarding over a deleted entry, photo during a conflict plus keepMine, second photo ignored, refused load and retry, no-op save), 6 JournalScreenTest (cursor follows inserted text, uploading state, deleted-conflict labels, refused-save retry, loading state, photo message dismiss), 2 JournalRouteInAppTest (real screen in MainActivity, saves when the editor is left; shell back still saves the text typed last), 3 HabitsViewModelTest (parallel checks answered in reverse order, check answered after a reload, reload while loading ignored), 2 HabitsScreenTest, 1 HabitsRouteInAppTest (real list in MainActivity, tap checks off).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Today's journal entry (load, render, edit) | JournalRouteInAppTest (2); JournalViewModelTest load/edit tests; JournalScreenTest; JournalRepositoryTest load tests | `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.journal.*' --tests 'ch.sonensei.cockpit.habits.*'` | Pass |
| AC1 ... with photo | JournalViewModelTest photo tests; JournalScreenTest cursor/uploading/camera/gallery callback tests; JournalRepositoryTest upload test | same | Pass. The system camera/gallery launcher callbacks were not executed (see Unable to test) |
| AC1 Habit check-off list | HabitsRouteInAppTest; HabitsViewModelTest; HabitsScreenTest; HabitsRepositoryTest | same | Pass |
| AC2 journal tests pass, covering entry autosave (Robolectric/Compose UI tests for screens) | JournalViewModelTest (25), JournalScreenTest (13), JournalRouteInAppTest (2), JournalRepositoryTest (13) | same | Pass (53/53 journal) |
| AC2 habits check-off tests | HabitsViewModelTest (10), HabitsScreenTest (8), HabitsRouteInAppTest (1), HabitsRepositoryTest (4) | same | Pass (23/23 habits) |
| CR-1 a create answered 428 is a conflict | JournalRepositoryTest 428 tests | same | Pass |
| TW-1 keepMine status | JournalViewModelTest `keeping my text when the server already has the same text ends as saved` | same | Pass |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.journal.*' --tests 'ch.sonensei.cockpit.habits.*'` (on 43c5a2346 plus the 2 new tests, 2026-10-22 ~13:56 +02:00, per the result XML timestamps)

Result:

Pass: BUILD SUCCESSFUL. 76 tests, 0 failures, 0 errors, 0 skipped. Per class: JournalRepositoryTest 13, JournalViewModelTest 25, JournalScreenTest 13, JournalRouteInAppTest 2, HabitsRepositoryTest 4, HabitsViewModelTest 10, HabitsScreenTest 8, HabitsRouteInAppTest 1. ktlint is clean.

## Full test execution

Command:

`cd android && ./gradlew createDebugUnitTestCoverageReport` (full Android unit suite plus coverage, test-writer run on 43c5a2346 plus the 2 new tests)

Result:

Pass: BUILD SUCCESSFUL in 1m 49s (exit 0). Earlier, the parent ran `./gradlew lintDebug createDebugUnitTestCoverageReport` on 43c5a2346 without the 2 new tests: exit 0, 1688 tests, 0 failures.

## .taskcheck

Result:

Not run by the test-writer. The parent runs `sh scripts/check.sh` before `done`.

## Bugs found

### TW-1 (fixed in 43c5a2346): keeping my text after a conflict whose server text equals mine left the status stuck at "Unsaved changes"

Finding ID:

`TW-1`

Test:

`JournalViewModelTest > keeping my text when the server already has the same text ends as saved`

Production source:

`android/app/src/main/java/ch/sonensei/cockpit/journal/JournalViewModel.kt:181` (`keepMine`)

Requirement:

AC2 entry autosave: the status shown must match reality.

Expected behavior:

After `keepMine()`, if the text equals the server's current entry, the status is SAVED.

Actual behavior:

Before 43c5a2346 the status stayed UNSAVED and no save ever cleared it. Fixed in 43c5a2346 (`withStatus()`); the test passes now.

Severity:

Low

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.` Done in 43c5a2346 and verified by the focused and full runs above.

No new production defects were found in this round.

## Coverage

Status: verified (fresh full-suite run, exit 0, on 43c5a2346 plus the 2 new uncommitted tests).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (the existing tooling has no changed-line report).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-journal-habits; base f3c2102802292ecc2e011934b7c70f3128d02bb9; HEAD 43c5a2346b865d449869b3a78ef74a8c721a3033; commits e495ab72d, 54a47eb10, 0ac22a651, 43c5a2346 (`git log base..HEAD`, from the parent).
- Source/test snapshot identifier (including uncommitted inputs): production code is the committed state at 43c5a2346 (no uncommitted production changes, per `git status`). The only uncommitted input is `JournalRepositoryTest.kt`, sha256 2c359a68c19d10bea2f6499855f9cb12c25f45bb901589275e7d0c61b8716ec7. `reviews/and-journal-habits/review.md` is also modified in the working tree; it is not a test input.
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/journal/{JournalRepository,JournalViewModel,JournalScreen}.kt`, `.../habits/{HabitsRepository,HabitsViewModel,HabitsScreen}.kt`. Excluded: `res/values/strings_journal.xml`, `strings_habits.xml` (resources, no executable code). No renames or deletions. No scoped file is missing from the report.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`; `./gradlew createDebugUnitTestCoverageReport` in `android/`; full Android unit suite.
- Run start/completion time and exit status: about 13:56:30-13:58:19 +02:00 on 2026-10-22, exit 0.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` (written 13:58:18 +02:00), sha256 f86d4dde2a063197447390001e33018aa83b967f69225d2fd43d457fdf957b44; exec data `android/app/build/outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec`, sha256 20c5ae714c5dd05e3c73187a92b6178dd716397bac910eaf8bd034e1731d2749.
- Evidence outputs are fresh and inputs stayed unchanged: yes. Gradle reran the tests because the test input changed, and the report timestamp falls inside this run. The working tree before and after the run held only the test edit and the review.md change.
- Final run after latest test additions and production fixes: yes.

Values below use covered/total (percentage).

| Changed file | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `journal/JournalRepository.kt` | 506/539 (93.9%) | 25/26 (96.2%) | 29/35 (82.9%) | 73/78 (93.6%) |
| `journal/JournalViewModel.kt` | 1270/1277 (99.5%) | 79/90 (87.8%) | 44/44 (100%) | 124/124 (100%) |
| `journal/JournalScreen.kt` | 2624/2895 (90.6%) | 130/178 (73.0%) | 39/58 (67.2%) | 178/203 (87.7%) |
| `habits/HabitsRepository.kt` | 357/394 (90.6%) | 9/10 (90.0%) | 28/30 (93.3%) | 55/63 (87.3%) |
| `habits/HabitsViewModel.kt` | 454/454 (100%) | 18/18 (100%) | 19/19 (100%) | 47/47 (100%) |
| `habits/HabitsScreen.kt` | 1552/1614 (96.2%) | 57/72 (79.2%) | 25/29 (86.2%) | 127/131 (96.9%) |
| Aggregate (summed, all 6 files, none missing or excluded) | 6763/7173 (94.3%) | 318/394 (80.7%) | 184/215 (85.6%) | 604/646 (93.5%) |

Compared with the parent's run on 43c5a2346 before this round's tests: JournalRepository went from 68/78 to 73/78 lines and from 22/26 to 25/26 branches. The other files are unchanged.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `JournalScreen.kt:125-136, 146-162` (camera/gallery `ActivityResult` callbacks, `NotePhotoFiles` create/clear, `ActivityNotFoundException` path) | AC1 photo from the camera or gallery | Not tested: needs system activity results under Robolectric (a test `ActivityResultRegistry`). The upload and insert logic is tested in the view model, and the button-to-callback wiring is tested in JournalScreenTest. Optional follow-up. |
| `JournalScreen.kt:79` (non-Hilt placeholder branch), `254` (SAVING text), `330-333` (LOAD_FAILED / PHOTO_UNREADABLE / PHOTO_FAILED strings) | Placeholder outside Hilt; string mapping | Not material |
| `JournalRepository.kt:54` (Hilt `@Provides`), `145-146, 151-152` (IllegalArgumentException / DateTimeException catch arms), branch at `176` | Defensive mapping of malformed answers | The SerializationException arm is now tested. The other arms are the same mapping with no distinct behavior. Not material. |
| `HabitsRepository.kt:70, 113-123` (Hilt module, malformed-answer catch arms) | Defensive mapping | Same pattern as the journal; low risk |
| `HabitsScreen.kt:57, 207, 227, 229` (default lambda, `when` end, LOAD_FAILED / CHECK_FAILED strings) | String mapping | Not material |

## Unable to test

- The system camera and gallery pickers in `JournalRoute`: not executed under Robolectric. The flow is covered from `addPhoto` downward only. Optional follow-up.
- The real 1 s autosave delay inside the in-app tests: the in-app tests trigger the immediate saves instead (leaving the editor, leaving the screen). The delay itself is covered with virtual time in JournalViewModelTest.

## Summary

Tests and task-scoped coverage are sufficient; no production defects remain open. TW-1 is fixed and verified in 43c5a2346. This round added 2 JournalRepositoryTest tests (uncommitted). Focused run: 76/76 pass. The full Android suite with coverage passes (exit 0).
