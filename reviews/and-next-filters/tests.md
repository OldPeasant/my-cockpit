# Test Report

Date: 2026-11-08

Task: and-next-filters - Android Next actions: due, area/project filters and saved presets

Scope: base b8737d25f11ade96ef73cb524a7d1afc2f5d6840 .. HEAD 6a4eb538a9dda1850c0dd51eeb802c1c42b8fa53 (task commits 14dc500ea, 6a4eb538a, given by the parent worker and matching the task's touches), plus the three new untracked test files listed below. No other uncommitted changes.

Requirements:
- `tasks/and-next-filters.md` acceptance criteria and its four ASSUMPTION lines
- web reference `web/src/app/features/gtd/next/filter-presets.ts` and `next-filter.ts` (preset JSON format, parsing rules)
- parent worker instructions (edge cases to probe)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/gtd/next/NextViewModelPresetEdgeCasesTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/next/NextFilterMatchTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/next/NextScreenPresetSavingTest.kt` (new)

The parent's own tests (NextPresetsTest, NextViewModelPresetsTest, NextScreenPresetsTest, adjusted NextScreenTest/NextRouteTest) were reviewed and left unchanged.

## Tests added or updated

Count: 30 (15 view model, 12 unit, 3 Compose/Robolectric)

## Requirement coverage

STATUS OF THIS SECTION: the test-writer cannot run Gradle in this environment. The results below are "Not run" until the parent runs the command and the report is updated with the outcome.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Due filter (overdue/today/this week/any) | NextFilterMatchTest: `a due filter needs a due date`, `the week window runs over a month and year end`, `due windows are read only from their exact wire names`, `every filter counts as active` | `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.next.*'` | Not run: Gradle not available to the test-writer |
| Area/project filter (client-side, area via cached project) | NextFilterMatchTest: `an area filter matches only tasks whose cached project is in that area`, `a project filter does not need the project cache`, `an area with a project of another area matches nothing`; NextViewModelPresetEdgeCasesTest: `a preset with an unknown area and project applies, shows nothing and stays active` | same | Not run: same |
| Presets: apply / save current / delete | NextViewModelPresetEdgeCasesTest: `a second save while one is running is ignored`, `cancelling while a save is running keeps the dialog closed when the save fails`, `deleting a preset that is already gone writes nothing and reports nothing`, `applying a preset clears the message of a failed delete`, `a context deleted while its preset is active drops the context filter and the preset is no longer active`, `tapping a preset that is not shown as active applies it, also when its context was deleted` (expected to FAIL, TW-1), `saving after the chosen context was deleted stores the filter the screen shows` (expected to FAIL, TW-2) | same | Not run: same |
| Presets read/written through shared `nextActionPresets` setting, same as web | NextViewModelPresetEdgeCasesTest: `settings that load only on the save keep the stored presets`, `presets loaded after the screen opened are shown`, `a settings load that throws does not break the screen and a save reports a failure`, `a write that throws reports a failure and keeps the dialog open`, `without a settings store a save reports offline`, `saving keeps unreadable stored texts as they are`, `presets changed on another device replace the shown ones`; NextFilterMatchTest: `the settings repository backs the presets`, `blanks around a preset's name and filter values are trimmed`, `fractional, negative and non-numeric estimates in a preset are left out`, `a preset whose name or filter has the wrong JSON type is handled`, `saving under a new name appends, the same name in another case replaces only the first match` | same | Not run: same |
| Save dialog UI states | NextScreenPresetSavingTest: `while saving the name and the save button are disabled, cancel still works`, `a failed save shows why in the dialog and can be tried again`, `an empty name is reported in the dialog` | same | Not run: same |
| New strings in English and German | covered by the parent's NextScreenPresetsTest (`the new strings are German on a German device`); no test added | same | Not run by the test-writer |
| ktlintCheck testDebugUnitTest lintDebug and scripts/check.sh pass | n/a (build criterion) | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`; `sh scripts/check.sh` | Not run by the test-writer; the parent runs them |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.next.*' --tests 'ch.sonensei.cockpit.core.settings.*'`

Result:

Not run: Gradle is not available to the test-writer sub-agent here (shell commands beyond read-only inspection are denied). Requested from the parent worker.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport`

Result:

Not run by the test-writer (parent is running it).

## .taskcheck

Result:

Not run by the test-writer (no Gradle access).

## Bugs found

These two are found by code inspection and encoded as tests; they are expected to fail until fixed. Confirm with the focused run.

### Tapping a preset that is not selected clears the filter after its context was deleted

Finding ID:

`TW-1`

Test:

`NextViewModelPresetEdgeCasesTest.tapping a preset that is not shown as active applies it, also when its context was deleted`

Production source:

`android/app/src/main/java/ch/sonensei/cockpit/gtd/next/NextViewModel.kt:267` (applyPreset compares the raw filter) vs. `:208` and `:221` (the shown filter drops a deleted context; the active preset is computed on the shown filter)

Requirement:

Saved presets: apply; "Applying the active preset clears the filter (web behavior)" (ASSUMPTION) - only the active one.

Expected behavior:

After the preset's context was deleted elsewhere, the chip is not selected (active is null); tapping it applies the preset (shown filter: due TODAY).

Actual behavior:

applyPreset sees the internal filter (still holding the deleted context id) equal to the preset filter and clears the whole filter, so a non-selected chip acts as "clear".

Severity:

Low

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

### Saving after the chosen context was deleted stores the deleted context id

Finding ID:

`TW-2`

Test:

`NextViewModelPresetEdgeCasesTest.saving after the chosen context was deleted stores the filter the screen shows`

Production source:

`android/app/src/main/java/ch/sonensei/cockpit/gtd/next/NextViewModel.kt:298` (savePreset uses `filter.value`, not the shown filter)

Requirement:

Saved presets: "save current" filter; presets are shared with web.

Expected behavior:

The saved preset holds the filter the screen shows (the deleted context was dropped: only due TODAY).

Actual behavior:

The preset is stored with the deleted context id. Android never shows it as active (shown filter differs), and on web the stale context id goes to the server query.

Severity:

Low

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

Observation (not encoded as a failing test, behavior is not specified): if the user cancels the save dialog and reopens it while a save is still running, the finishing save closes (success) or annotates (failure) the newly opened dialog (`NextViewModel.kt:309`). Low risk; parent to decide.

## Coverage

Status: unavailable - the test-writer cannot run Gradle; the only JaCoCo report present (`android/app/build/reports/coverage/test/debug/report.xml`, written 2026-11-08 14:05:30 +0100) predates the task commits (it has no NextPresets.kt or NextPresetsUi.kt), so it is stale and its numbers are not used.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-next-filters; base b8737d25f11ade96ef73cb524a7d1afc2f5d6840; HEAD 6a4eb538a9dda1850c0dd51eeb802c1c42b8fa53; commits 14dc500ea, 6a4eb538a from the parent worker, checked against `git diff --stat` of that range.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 6a4eb538a plus the three untracked test files above.
- Exact included files: `gtd/next/NextViewModel.kt`, `gtd/next/NextPresets.kt`, `gtd/next/NextPresetsUi.kt`, `gtd/next/NextScreen.kt`, `core/settings/UserSettings.kt`. Excluded: the two `strings_next_today.xml` resource files (not executable), test files.
- Provider/version, command, working directory, and test selection: JaCoCo via `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` (parent run, pending).
- Run start/completion time and exit status: not available.
- Generated report paths and report hashes/run identifier: not available.
- Evidence outputs are fresh and inputs stayed unchanged: no.
- Final run after latest test additions and production fixes: no, pending.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `gtd/next/NextViewModel.kt` | N/A | N/A | N/A | N/A |
| `gtd/next/NextPresets.kt` | N/A | N/A | N/A | N/A |
| `gtd/next/NextPresetsUi.kt` | N/A | N/A | N/A | N/A |
| `gtd/next/NextScreen.kt` | N/A | N/A | N/A | N/A |
| `core/settings/UserSettings.kt` | N/A | N/A | N/A | N/A |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| all scoped files | not measured | Follow-up: run the coverage command after the new tests are in place, then run `python3 -I .tmp/next_cov.py android/app/build/reports/coverage/test/debug/report.xml` (prints summed JaCoCo counters and partly/un-covered lines per scoped file) |

## Unable to test

- Running any test, lint or coverage: Gradle is not available to the test-writer; the parent must run them.
- Areas offline (area menu empty because `observeAreas` is online only): covered only indirectly (VM areas flow); no dedicated test.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion. (Provisional: the new tests have not been run yet; TW-1 and TW-2 are expected to fail, the rest to pass.)
