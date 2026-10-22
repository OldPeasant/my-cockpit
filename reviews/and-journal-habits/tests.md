# Test Report

Date: 2026-10-22

Task: and-journal-habits - Android journal and habits

Scope: base `f3c2102802292ecc2e011934b7c70f3128d02bb9`..HEAD `629ca2f7a06ea36369a7a1b5fcd4932a655f2f48` (task commits 3c75b4067, 64762cd29, 629ca2f7a; ownership from the task file and the parent's commit list), plus uncommitted working-tree changes present at measurement time: the fixer's in-progress edit of `JournalRepository.kt` / `JournalRepositoryTest.kt` (428 as conflict, review finding) and the test files below.

Requirements:
- `tasks/and-journal-habits.md` acceptance criteria and recorded assumptions (today's entry only, device clock; habits one-tap check sends targetCount / 0)
- `SPEC.md` §8.5, §9.2 item 8 (via the task notes)
- `docs/ARCHITECTURE.md` "Coverage"; `AGENTS.md`
- Parent instructions (do not edit `JournalRepositoryTest.kt`)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/journal/JournalViewModelTest.kt` (extended)
- `android/app/src/test/java/ch/sonensei/cockpit/journal/JournalScreenTest.kt` (extended)
- `android/app/src/test/java/ch/sonensei/cockpit/journal/JournalRouteInAppTest.kt` (new, Hilt + Robolectric in MainActivity)
- `android/app/src/test/java/ch/sonensei/cockpit/habits/HabitsViewModelTest.kt` (extended)
- `android/app/src/test/java/ch/sonensei/cockpit/habits/HabitsScreenTest.kt` (extended)
- `android/app/src/test/java/ch/sonensei/cockpit/habits/HabitsRouteInAppTest.kt` (new, Hilt + Robolectric in MainActivity)

## Tests added or updated

Count: 23 new tests (existing tests unchanged; the focused run's 74 also includes the fixer's concurrent JournalRepositoryTest change)

- JournalViewModelTest (+9): text typed while a save runs is saved next, over the version that save returned; text typed while a save runs into a conflict is the text kept; writing goes on after a resolved conflict, over the kept version; keeping my text when the server already has the same text ends as saved (FAILS, TW-1); discarding my text when the entry was deleted elsewhere leaves an empty day in the editor; a photo uploaded while a conflict came up goes in at the cursor when the user keeps their text; a second photo while one uploads is ignored; a load the server refused says so, and a retry is ignored once the entry is loaded; saving again without changes sends nothing.
- JournalScreenTest (+6): photo goes in at the cursor, which follows text inserted from elsewhere; uploading replaces photo buttons by progress; deleted-elsewhere conflict labels and no competing retry; refused save text + retry; loading state allows no writing; photo problem can be dismissed.
- JournalRouteInAppTest (+2): Journal destination in MainActivity is the real screen (not the placeholder), loads today's entry and saves edited text when the editor is left; the shell's back arrow leaves the Journal and the text typed last is still saved (onCleared -> JournalSaveScope).
- HabitsViewModelTest (+3): two habits checked off at the same time with answers in reverse order (one success, one offline); a check-off answered after a reload updates the reloaded list; reload while loading is ignored.
- HabitsScreenTest (+2): done habit with target above one shows no partial progress; first load shows only the progress bar.
- HabitsRouteInAppTest (+1): Habits destination in MainActivity is the real list, a tap checks the habit off and the row shows the server's new stats.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Today's journal entry (load, render, edit) | JournalRouteInAppTest `the Journal screen shows today's entry...`; JournalViewModelTest `opens today's entry...`, `a day without an entry opens in the editor`, load-failure tests; JournalScreenTest | `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.journal.*' --tests 'ch.sonensei.cockpit.habits.*'` | Pass |
| AC1 ... with photo | JournalViewModelTest photo tests (insert at cursor, conflict + keepMine/useTheirs, second photo ignored, upload errors); JournalScreenTest cursor/uploading tests | same | Pass. Camera/gallery launcher callbacks in `JournalRoute` not executed (see Unable to test) |
| AC1 Habit check-off list | HabitsRouteInAppTest; HabitsViewModelTest (check, uncheck, double tap, parallel checks, reload race, failures); HabitsScreenTest | same | Pass |
| AC2 journal tests pass, covering entry autosave (Robolectric/Compose UI) | JournalViewModelTest autosave/ordering/conflict tests; JournalScreenTest; JournalRouteInAppTest | same | Fail: 1 of 74 fails (TW-1, production defect) |
| AC2 habits check-off tests | Habits* tests above | same | Pass |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.journal.*' --tests 'ch.sonensei.cockpit.habits.*'` (run 2026-10-22 ~13:49-13:50 +02:00)

Result:

Fail: 74 tests completed, 1 failed (`JournalViewModelTest > keeping my text when the server already has the same text ends as saved`: `expected:<SAVED> but was:<UNSAVED>`). All other 73 pass, including the 3 new Hilt in-app tests. This run included the fixer's uncommitted `JournalRepository.kt` / `JournalRepositoryTest.kt` changes as they were at that moment.

`cd android && ./gradlew ktlintCheck` -> BUILD SUCCESSFUL (new test files are lint-clean).

## Full test execution

Command:

`cd android && ./gradlew testDebugUnitTest` (not run by the test-writer)

Result:

Not run: it would fail on TW-1, and the fixer was changing production code at the same time. Before these tests were added, the parent's full run (`./gradlew createDebugUnitTestCoverageReport`, full Android unit suite) exited 0.

## .taskcheck

Result:

Not run: `sh scripts/check.sh` would fail on TW-1 until it is fixed. The parent must run it after the fix.

## Bugs found

### Keeping my text after a conflict whose server text equals mine leaves the status stuck at "Unsaved changes"

Finding ID:

`TW-1`

Test:

`JournalViewModelTest > keeping my text when the server already has the same text ends as saved`

Production source:

`android/app/src/main/java/ch/sonensei/cockpit/journal/JournalViewModel.kt:181` (`keepMine` sets `status = SaveStatus.UNSAVED` unconditionally), together with `:164` (`save()` returns early when `isSaved()` without updating the status)

Requirement:

AC2 entry autosave. The save status shown to the user must match reality: "Unsaved changes" means a save is still pending.

Expected behavior:

After `keepMine()`, if the body equals the server's current entry (`conflict.current.bodyMd`, or `""` when the entry was deleted and the body is empty), the status is `SAVED` (e.g. by using `withStatus()` instead of a fixed `UNSAVED`). Otherwise the existing save over the server's version runs.

Actual behavior:

The status stays `UNSAVED` ("Unsaved changes") and no save ever runs to clear it, until the user types again. A realistic path is covered by the test: a save reaches the server but its answer is lost (Offline), the retry gets a 412 conflict whose current entry already has this text, and the user taps "Keep my version".

Severity:

Low

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

## Coverage

Status: partial-failed. This is diagnostic only, not verified task coverage. The test task failed (TW-1), so the documented command `./gradlew createDebugUnitTestCoverageReport` cannot complete. The report below comes from the JaCoCo execution data of the focused (journal + habits only) run. Production code was also changing during the run (fixer).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (the existing tooling has no changed-line report).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-journal-habits; base f3c2102802292ecc2e011934b7c70f3128d02bb9; HEAD 629ca2f7a06ea36369a7a1b5fcd4932a655f2f48; commits 3c75b4067, 64762cd29, 629ca2f7a from the parent and `git log base..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): sha256 of the production files after the run: JournalRepository.kt 5b1e5f3a2663be47... (uncommitted fixer edit), JournalScreen.kt 1927391e0fd7fe1c..., JournalViewModel.kt 8f5f4f62db478d6c..., HabitsRepository.kt 50c382dac066524..., HabitsScreen.kt cb2f76dc4b47bb71..., HabitsViewModel.kt 7772d7f5564cd08c.... Test files: working tree as listed above, plus the fixer's uncommitted JournalRepositoryTest.kt.
- Exact included files: the 6 Kotlin files under `android/app/src/main/java/ch/sonensei/cockpit/{journal,habits}/`. Excluded: `strings_journal.xml`, `strings_habits.xml` (resources, no executable code). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`; `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.journal.*' --tests 'ch.sonensei.cockpit.habits.*'`, then `./gradlew createDebugUnitTestCoverageReport -x testDebugUnitTest`, in `android/`.
- Run start/completion time and exit status: tests ~13:49:50-13:50:35 +02:00, exit nonzero (1 failure); report 13:50:38-13:50:42 +02:00, exit 0.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 c7eb13228cf36d58e2adf306fc158801b94d66331c7fb87105f177a4c6194ba2. This overwrote the parent's earlier full-suite report.
- Evidence outputs are fresh and inputs stayed unchanged: the report is fresh from this run's exec data. Inputs were not stable: the fixer was editing JournalRepository during the run.
- Final run after latest test additions and production fixes: no. It must be rerun after the TW-1 fix and the fixer's 428 change.

Values below use covered/total (percentage), from the diagnostic focused run. Only journal + habits tests ran, so lines covered elsewhere only by shell/navigation tests (placeholder branch) show as missed here.

| Changed file | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `journal/JournalRepository.kt` | 474/539 (87.9%) | 22/26 (84.6%) | 29/35 (82.9%) | 68/78 (87.2%) |
| `journal/JournalViewModel.kt` | 1272/1279 (99.5%) | 79/90 (87.8%) | 44/44 (100%) | 124/124 (100%) |
| `journal/JournalScreen.kt` | 2604/2895 (89.9%) | 129/178 (72.5%) | 39/58 (67.2%) | 177/203 (87.2%) |
| `habits/HabitsRepository.kt` | 357/394 (90.6%) | 9/10 (90.0%) | 28/30 (93.3%) | 55/63 (87.3%) |
| `habits/HabitsViewModel.kt` | 454/454 (100%) | 18/18 (100%) | 19/19 (100%) | 47/47 (100%) |
| `habits/HabitsScreen.kt` | 1532/1614 (94.9%) | 56/72 (77.8%) | 25/29 (86.2%) | 126/131 (96.2%) |
| Aggregate (summed, all 6 files, none missing) | 6693/7175 (93.3%) | 313/394 (79.4%) | 184/215 (85.6%) | 597/646 (92.4%) |

The parent's earlier full-suite run (before these tests, exit 0) gave lines/branches of: JournalViewModel 120/124, 69/90; JournalScreen 142/203, 61/178; HabitsScreen 116/131, 47/72. The others are unchanged. The new in-app tests raise JournalScreen from 142 to 177 lines (route wiring) and HabitsScreen from 116 to 126 lines.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `JournalScreen.kt:125-136, 146-162` (camera/gallery launcher callbacks, `NotePhotoFiles` create/clear, ActivityNotFoundException path) | AC1 photo from camera or gallery | Not tested: it needs system activity results (TakePicture/PickVisualMedia) in Robolectric. The upload/insert logic behind it is tested in the view model. Follow-up possible with a test `ActivityResultRegistry`. |
| `JournalScreen.kt:79, 108` and `HabitsScreen.kt:57, 78` (non-Hilt placeholder branch, default callback lambdas) | Shell navigation outside Hilt | Covered by shell tests in the full suite; not run in this focused run |
| `JournalScreen.kt:254, 330-333` (SAVING status text; LOAD_FAILED/PHOTO_UNREADABLE/PHOTO_FAILED banner strings) | Plain string mapping | Not material |
| `HabitsScreen.kt:227, 229` (LOAD_FAILED/CHECK_FAILED strings) | Plain string mapping | Not material |
| `JournalRepository.kt:138-155` (conflict refetch offline/failed, unreadable answers), `:54` (Hilt module) | Save conflict error paths | Not touched by the test-writer: the fixer owns `JournalRepositoryTest.kt` in this round (428 change). The fixer or reviewer should add a test for a conflict whose refetch is offline. |
| `HabitsRepository.kt:70, 113-123` (Hilt module, serialization/date error mapping) | Malformed server answers | Low risk, same pattern as the other repositories |

## Unable to test

- Camera/gallery system pickers (`JournalRoute` launchers) under Robolectric: not executed. The photo flow from picked URI to upload is covered only from `addPhoto` downward. Follow-up is optional.
- Real-device or browser behaviour: out of scope for this AC, which names unit/Robolectric tests.
- The 1 s autosave delay inside the in-app test: the Robolectric main looper does not advance coroutine delays reliably. The in-app tests trigger the immediate save (leaving the editor, leaving the screen) instead. The delay itself is covered with virtual time in JournalViewModelTest.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion (TW-1, Low). After the fix, the parent must rerun:

1. `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.journal.*' --tests 'ch.sonensei.cockpit.habits.*'`
2. `cd android && ./gradlew ktlintCheck`
3. `cd android && ./gradlew createDebugUnitTestCoverageReport` (full suite + fresh coverage; replaces the diagnostic numbers above)
4. `sh scripts/check.sh` (`.taskcheck`)
