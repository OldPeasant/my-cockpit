# Test Report

Date: 2026-11-08

Task: and-next-filters - Android Next actions: due, area/project filters and saved presets

Scope: base b8737d25f11ade96ef73cb524a7d1afc2f5d6840 .. HEAD bffd4e704 (final reviewed code). Task commits, given by the parent worker and matching the task's touches: 14dc500ea, 6a4eb538a (implementation), 33523ad8d (first test-writer tests and code review), bffd4e704 (fixer: CR-1, CR-2, CR-3, TW-1, TW-2, save race, two lint errors). This is the second test-writer pass on the final code. Working tree clean at the start of this pass (the git status snapshot). This pass changed no test files.

Requirements:
- `tasks/and-next-filters.md` acceptance criteria and its four ASSUMPTION lines
- web reference `web/src/app/features/gtd/next/filter-presets.ts` and `next-filter.ts` (preset JSON format, parsing rules)
- `reviews/and-next-filters/fixes.md` and `git diff 33523ad8d bffd4e704` (the fixed behavior checked in this pass)
- parent worker instructions

## Test files changed

First pass (committed in 33523ad8d):
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/next/NextViewModelPresetEdgeCasesTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/next/NextFilterMatchTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/next/NextScreenPresetSavingTest.kt` (new)

Fixer (bffd4e704, reviewed in this pass and left as they are):
- `NextViewModelPresetEdgeCasesTest.kt`: `two deletes back to back both stay deleted` (CR-1), `unreadable stored texts count against the preset limit` (CR-3), `a save that finishes after the dialog was cancelled and opened again leaves the new dialog open` and `a failed save that finishes after the dialog was opened again does not annotate the new dialog` (save race)
- `NextScreenPresetsTest.kt`: `the delete button has a full touch target next to the chip and leaves the chip's taps to applying` (CR-2)

Second pass (this one): no test files changed.

## Tests added or updated

Count: 0 in this pass. Earlier: 30 by the first test-writer pass (15 view model, 12 unit, 3 Compose/Robolectric) and 5 by the fixer.

How the fixed behavior is covered (checked against the diff, so no new test was needed):
- Preset writes run one at a time (Mutex in `write()`): `two deletes back to back both stay deleted` holds the first store open, starts a second delete and checks both writes ([B,C] then [C]) and that both presets are gone. Saves use the same `write()` path, so the test also covers a save that overlaps a delete.
- Delete is an IconButton next to the chip: the CR-2 Compose test checks the 48x48 dp touch size and that a tap on the chip applies while a tap on the button deletes. `presets are applied and deleted, the active one is selected` still passes.
- The 50 limit counts all stored texts: CR-3 test (49 readable + 1 unreadable, new name -> TOO_MANY, no store call). The existing limit tests check that a same-name replace still works.
- Apply and save use the shown filter: the TW-1 and TW-2 tests, which failed in the first pass, now pass.
- Editor session counter: the two save-race tests, plus `cancelling while a save is running keeps the dialog closed when the save fails` and `a second save while one is running is ignored`.
- Plural too-many message: `the save dialog sends the name and shows why saving failed` checks the English "other" text ("You can save up to 50 presets. Delete one first."). The "one" form can't happen, because MAX_PRESETS is 50.
- Panel height via LocalWindowInfo: it only changes how the half-window height is computed, so lint covers it (ConfigurationScreenWidthHeight fixed, lintDebug clean). The existing filter tests reach every chip with `performScrollTo`, so the scrolling panel is exercised. No test checks the exact pixel height, because that would only repeat the formula.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Due filter (overdue/today/this week/any) | NextFilterMatchTest: `a due filter needs a due date`, `the week window runs over a month and year end`, `due windows are read only from their exact wire names`, `every filter counts as active`; NextScreenPresetsTest: `due chips toggle the due window and any clears it` | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport -q` (parent, on bffd4e704) | Pass |
| Area/project filter (client-side, area via cached project) | NextFilterMatchTest: `an area filter matches only tasks whose cached project is in that area`, `a project filter does not need the project cache`, `an area with a project of another area matches nothing`; NextViewModelPresetEdgeCasesTest: `a preset with an unknown area and project applies, shows nothing and stays active`; NextScreenPresetsTest: `area and project are chosen from menus` | same | Pass |
| Presets: apply / save current / delete | NextViewModelPresetEdgeCasesTest: `tapping a preset that is not shown as active applies it, also when its context was deleted` (TW-1), `saving after the chosen context was deleted stores the filter the screen shows` (TW-2), `a second save while one is running is ignored`, `cancelling while a save is running keeps the dialog closed when the save fails`, `a save that finishes after the dialog was cancelled and opened again leaves the new dialog open`, `a failed save that finishes after the dialog was opened again does not annotate the new dialog`, `two deletes back to back both stay deleted`, `unreadable stored texts count against the preset limit`, `deleting a preset that is already gone writes nothing and reports nothing`, `applying a preset clears the message of a failed delete`, `a context deleted while its preset is active drops the context filter and the preset is no longer active`; NextScreenPresetsTest: `presets are applied and deleted, the active one is selected`, `the delete button has a full touch target next to the chip and leaves the chip's taps to applying`, `a failed delete is shown above the list` | same | Pass |
| Presets read/written through shared `nextActionPresets` setting, same as web | NextViewModelPresetEdgeCasesTest: `settings that load only on the save keep the stored presets`, `presets loaded after the screen opened are shown`, `a settings load that throws does not break the screen and a save reports a failure`, `a write that throws reports a failure and keeps the dialog open`, `without a settings store a save reports offline`, `saving keeps unreadable stored texts as they are`, `presets changed on another device replace the shown ones`; NextFilterMatchTest: `the settings repository backs the presets`, `blanks around a preset's name and filter values are trimmed`, `fractional, negative and non-numeric estimates in a preset are left out`, `a preset whose name or filter has the wrong JSON type is handled`, `saving under a new name appends, the same name in another case replaces only the first match`; parent's NextPresetsTest and NextViewModelPresetsTest | same | Pass |
| Save dialog UI states | NextScreenPresetSavingTest: `while saving the name and the save button are disabled, cancel still works`, `a failed save shows why in the dialog and can be tried again`, `an empty name is reported in the dialog`; NextScreenPresetsTest: `the save dialog sends the name and shows why saving failed` | same | Pass |
| New strings in English and German | NextScreenPresetsTest: `the new strings are German on a German device` (parent's test) | same | Pass |
| ktlintCheck testDebugUnitTest lintDebug pass | whole Android unit suite (3672 tests) | same | Pass (exit 0; 3672 tests, 0 failures, 0 errors, 0 skipped; ktlintCheck clean; lintDebug clean) |
| `sh scripts/check.sh` passes | n/a (project check) | `sh scripts/check.sh` | Not run by the test-writer (sub-agents can't run Gradle here). The parent worker runs it before `done`, and `done` runs it again on the merged result |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport -q` (parent worker, on bffd4e704; the run includes all 145 tests in `ch.sonensei.cockpit.gtd.next.*`)

Result:

Pass: 145 tests in `ch.sonensei.cockpit.gtd.next.*`, 0 failures. The fixer ran these too: `./gradlew --offline ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.next.*'`, which passed.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport -q` (parent worker, on bffd4e704)

Result:

Pass: exit 0; 3672 tests, 0 failures, 0 errors, 0 skipped; ktlintCheck clean; lintDebug clean.

## .taskcheck

Result:

Not run by the test-writer: sub-agents can't run Gradle in this environment, and `sh scripts/check.sh` also covers server and web. The parent worker runs it before `tasks.py done`. Its Android part (`ktlintCheck testDebugUnitTest lintDebug`) passed in the full run above.

## Bugs found

None open on bffd4e704.

The first pass found TW-1 (tapping a non-active preset cleared the filter after its context was deleted) and TW-2 (saving after the chosen context was deleted stored the deleted context id), plus the save-race observation. The fixer fixed all three in bffd4e704. The tests that encode them now pass: `tapping a preset that is not shown as active applies it, also when its context was deleted`, `saving after the chosen context was deleted stores the filter the screen shows`, and the two save-race tests. This pass checked the final code and found no new defects.

## Coverage

Status: verified (task-scoped, from the parent's fresh JaCoCo run on bffd4e704).

Metric: whole-file coverage of task-changed production files. JaCoCo INSTRUCTION is shown as Statements and METHOD as Functions.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-next-filters; base b8737d25f11ade96ef73cb524a7d1afc2f5d6840; HEAD bffd4e704; commits 14dc500ea, 6a4eb538a, 33523ad8d, bffd4e704 from the parent worker, checked against `git diff --stat` of the range and the task's touches.
- Source/test snapshot identifier (including uncommitted inputs): HEAD bffd4e704, working tree clean. This pass made no test changes, so the measured inputs equal HEAD.
- Exact included files: `gtd/next/NextViewModel.kt`, `gtd/next/NextPresets.kt`, `gtd/next/NextPresetsUi.kt`, `gtd/next/NextScreen.kt`, `core/settings/UserSettings.kt` (outside touches, recorded as an ASSUMPTION). Excluded: `res/values/strings_next_today.xml` and `res/values-de/strings_next_today.xml` (resources, not executable), and all test files. No renamed or deleted production files.
- Provider/version, command, working directory, and test selection: JaCoCo, the version configured in the Android build (`createDebugUnitTestCoverageReport`). Command `./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport -q` in `android/`, whole debug unit suite. Scoped summary by `python3 -I .tmp/next_cov.py android/app/build/reports/coverage/test/debug/report.xml` (sums the provider's covered/missed counters per scoped source file), output in `.tmp/cov.txt`.
- Run start/completion time and exit status: report written 2026-11-08 16:09:05 +0100; exit 0 (parent run).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 0e66c103cb3a32a3816e7deadccd9bf9f76845f95940a0ddedf12b92189c80e2.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report has the fixer-era line numbers: for example, the uncovered `catching` rethrow is at NextViewModel.kt:401-402 and the ChoiceMenu dismiss lambda at NextScreen.kt:397, both matching bffd4e704. No sources changed after the run.
- Final run after latest test additions and production fixes: yes (bffd4e704 is the final code, and this pass added no tests).

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `gtd/next/NextViewModel.kt` | 1945/1961 (99.2%) | 132/136 (97.1%) | 101/102 (99.0%) | 213/215 (99.1%) |
| `gtd/next/NextPresets.kt` | 638/638 (100.0%) | 77/77 (100.0%) | 17/17 (100.0%) | 68/68 (100.0%) |
| `gtd/next/NextPresetsUi.kt` | 1044/1068 (97.8%) | 27/32 (84.4%) | 20/20 (100.0%) | 90/92 (97.8%) |
| `gtd/next/NextScreen.kt` | 3053/3102 (98.4%) | 78/81 (96.3%) | 81/101 (80.2%) | 270/270 (100.0%) |
| `core/settings/UserSettings.kt` | 733/744 (98.5%) | 74/78 (94.9%) | 31/34 (91.2%) | 65/65 (100.0%) |
| Aggregate (summed counters, all 5 files; no file missing or unmeasured) | 7413/7513 (98.7%) | 388/404 (96.0%) | 250/274 (91.2%) | 706/710 (99.4%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `NextScreen.kt:85-103` (all lines partly covered; 19 uncovered methods `NextCallbacks._init_$lambda$0..18`) | None. These are the default no-op lambdas (`= {}`) of the `NextCallbacks` data class parameters. They run only when a screen test leaves a callback at its default and then triggers it. The real wiring, `NextCallbacks.of(viewModel)`, is covered. | Justified gap: they are no-op defaults with no behavior. This is not the Hilt wrapper: `NextScreen` (lines 66-72) and `NextRoute`'s body (76-81) are fully covered, through `NextScreenInAppTest` (Hilt activity) and `NextRouteTest`. |
| `NextScreen.kt:75` (4 instructions, 1 of 2 branches missed) | The `hiltViewModel()` default of `NextRoute`'s `viewModel` parameter, in the Compose default-mask code. `NextRouteTest` always passes `viewModel =` explicitly. | Justified gap: generated default-argument code. The default path runs on the app's real navigation path. No behavior to assert beyond what the in-app test covers. |
| `NextScreen.kt:397` (ChoiceMenu `onDismissRequest = { open = false }`) | Closing the area/project menu without choosing. | Justified gap, low risk: it is a one-line state reset. Choosing from the menu (which closes it) is tested in `area and project are chosen from menus`. |
| `NextScreen.kt:388` (1 of 2 branches missed) | The fallback to the title when the selected area/project id is not among the choices (for example an unknown id from a preset). | Low risk. The view-model side of unknown ids is tested (`a preset with an unknown area and project applies, shows nothing and stays active`). The fallback label is not checked in Compose. No test added. |
| `NextPresetsUi.kt:146,152,153` (FAILED message text not rendered in a Compose test) | Text of the generic "could not be changed" message. | Low risk: the view model emitting FAILED is tested (`a write that throws reports a failure and keeps the dialog open`, `a settings load that throws ...`). NAME_REQUIRED, TOO_MANY, CONFLICT and OFFLINE texts are rendered in Compose tests. |
| `NextPresetsUi.kt:45,46` (3 of 12 branches missed) | Combinations of the visibility condition (for example a message shown with no presets and no filter). | Low risk: the hidden and shown cases are tested (`presets are hidden without presets or a filter to save`, `a failed delete is shown above the list`). |
| `NextViewModel.kt:401,402` (`catching` rethrows CancellationException) | Cancelling the view model during a write (the Mutex is released by `withLock`). | Justified gap: a structured-concurrency passthrough. The failure path (other exceptions -> null) is covered. |
| `NextViewModel.kt:292,301` (null editor branches of `editPresetName`/`savePreset`) | Calls while no dialog is open are ignored. | Justified gap: defensive no-ops; the UI can't call them without an open dialog. |
| `NextViewModel.kt:324` (`it?.copy` with a null editor) | After the session check, the editor can only be null if the session didn't change, which open and cancel both do. | Justified gap: effectively unreachable since the session counter was added. Both race outcomes are tested. |
| `NextViewModel.kt:127,134,150` | Default `today` value, one branch of `firstLoadFailed`, the `PresetSettings.NONE` store lambda. | Non-material: defaults and pre-existing state. `without a settings store a save reports offline` covers the NONE store result. |
| `UserSettings.kt:86,93,124,125,127,130` | 86 is pre-existing mail code. 93 is one branch of `strings()` (the setting is not an array). 124-130 are data-class default-argument code and `isEmpty` short-circuits. | Non-material. Wrong JSON types for presets are tested in NextFilterMatchTest (`a preset whose name or filter has the wrong JSON type is handled`). |

No coverage threshold is defined for tasks in AGENTS.md or docs/ARCHITECTURE.md for this metric, so the values are reported without pass/fail.

## Unable to test

- `sh scripts/check.sh`: the test-writer can't run Gradle; the parent worker runs it before `done`.
- Areas offline (the area menu is empty because `observeAreas` is online only): covered only indirectly through the view model's areas flow; no dedicated test. No follow-up needed: this is an ASSUMPTION of the task.
- The exact filter-panel height (half the window via LocalWindowInfo): not asserted, because a test would only repeat the formula. Scrolling inside the panel is exercised by the `performScrollTo` filter tests.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. On the final revision bffd4e704, TW-1, TW-2 and the save race are fixed and their tests pass. All 3672 Android unit tests pass, ktlintCheck and lintDebug are clean, and task-scoped coverage is 706/710 lines (99.4%) and 388/404 branches (96.0%). The remaining gaps are justified above.
