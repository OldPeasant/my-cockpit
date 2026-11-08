# Fix Report

Date: 2026-11-08

Task: and-next-filters - Android Next actions: due, area/project filters and saved presets

Scope: current task; findings CR-1, CR-2, CR-3 (reviews/and-next-filters/review.md), TW-1, TW-2 and the save-race observation (reviews/and-next-filters/tests.md), and the two Android Lint errors reported by the parent worker (ConfigurationScreenWidthHeight, PluralsCandidate).

## Fixed findings

### CR-1 — Overlapping preset writes lose updates

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/next/NextViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/next/NextViewModelPresetEdgeCasesTest.kt`

Problem:
`write()` read the stored list, built the change and stored it without serialization, so a second delete/save started while the first was in flight was computed from the old list and wrote the first change back.

Fix:
A `Mutex` (`presetWrites`) wraps load, read, change and store in `write()`, so each change is computed from the list the previous write stored.

Validation:
- New test `two deletes back to back both stay deleted` (suspending fake store; second delete waits, both presets are gone, writes are [B,C] then [C]).
- `./gradlew --offline ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.next.*'` — Pass

### CR-2 — Preset delete icon has an 18 dp touch target

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/next/NextPresetsUi.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/next/NextScreenPresetsTest.kt`

Problem:
The delete action was an 18 dp clickable `Icon` inside the chip, next to the chip's apply tap.

Fix:
Each preset is now a `Row` of the `FilterChip` (apply, tag `NextTags.preset(i)`) and an `IconButton` after it, outside the chip (delete, tag `NextTags.presetDelete(i)`, 48 dp touch target, 18 dp icon, content description string `next_preset_delete`). Since the button is outside the chip it does not take the chip's taps.

Validation:
- New Compose test `the delete button has a full touch target next to the chip and leaves the chip's taps to applying` (touch size 48x48 dp; chip tap applies, button tap deletes).
- Existing `presets are applied and deleted, the active one is selected` unchanged and passing.
- `./gradlew --offline testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.next.*'` — Pass

### CR-3 — The 50-preset limit counts only readable presets

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/next/NextViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/next/NextViewModelPresetEdgeCasesTest.kt`

Problem:
The limit compared the readable presets against `MAX_PRESETS`, although unreadable texts are kept on write and counted by the server.

Fix:
The check uses `texts.size` (the list actually written) for a new name; same-name replace is still allowed.

Validation:
- New test `unreadable stored texts count against the preset limit` (49 readable + 1 unreadable, new name -> TOO_MANY, no store call).
- Focused run — Pass

### TW-1 — Tapping a non-active preset cleared the filter after its context was deleted

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/next/NextViewModel.kt`

Problem:
`applyPreset` compared the hidden raw filter (still holding the deleted context id) with the preset filter.

Fix:
`applyPreset` compares the shown filter (`uiState.value.filter`, which drops a deleted context, the same filter the "active" chip is computed from) with the preset filter.

Validation:
- Existing test `tapping a preset that is not shown as active applies it, also when its context was deleted` — Pass

### TW-2 — Saving after the chosen context was deleted stored the deleted context id

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/next/NextViewModel.kt`

Problem:
`savePreset` saved the raw `filter.value`.

Fix:
`savePreset` saves the shown filter (`uiState.value.filter`).

Validation:
- Existing test `saving after the chosen context was deleted stores the filter the screen shows` — Pass

### SAVE-RACE — A late save closed or annotated a newly opened dialog (test-writer observation)

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/next/NextViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/next/NextViewModelPresetEdgeCasesTest.kt`

Problem:
After cancel and reopen while a save was running, the finishing save closed (success) or annotated (failure) the new dialog.

Fix:
A private editor session counter is incremented on open and cancel; a save captures it and only updates the editor if the session is unchanged. `PresetEditor` is unchanged (no public contract change).

Validation:
- New tests `a save that finishes after the dialog was cancelled and opened again leaves the new dialog open` and `a failed save that finishes after the dialog was opened again does not annotate the new dialog`.
- Existing `cancelling while a save is running keeps the dialog closed when the save fails` and `a second save while one is running is ignored` — Pass

### LINT-1 — ConfigurationScreenWidthHeight in NextScreen.kt

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/next/NextScreen.kt`

Problem:
The filter panel's half-screen max height used `LocalConfiguration.current.screenHeightDp`.

Fix:
Uses `LocalWindowInfo.current.containerSize.height` (pixels) halved and converted with `LocalDensity`.

Validation:
- `./gradlew --offline lintDebug` — Pass

### LINT-2 — PluralsCandidate for next_preset_too_many

Files changed:
- `android/app/src/main/res/values/strings_next_today.xml`
- `android/app/src/main/res/values-de/strings_next_today.xml`
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/next/NextPresetsUi.kt`

Problem:
The count message was a plain string with a number placeholder.

Fix:
`next_preset_too_many` is now a `plurals` resource (quantities one/other, English and German) read with `pluralStringResource`. The "other" texts are unchanged, so the existing assertion on "You can save up to 50 presets. Delete one first." still holds; no test change needed.

Validation:
- `./gradlew --offline lintDebug` — Pass
- `the save dialog sends the name and shows why saving failed` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew --offline ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.next.*'` — Pass (first run: one new assertion used layout size, IconButton lays out 40 dp with a 48 dp touch area; changed to touch-size assertions, then all passed)

Full test suite:
- `cd android && ./gradlew --offline testDebugUnitTest` — Pass

Lint/type-check/build:
- `cd android && ./gradlew --offline ktlintCheck` — Pass (after `ktlintFormat` reformatted chain continuations in NextViewModelPresetEdgeCasesTest.kt)
- `cd android && ./gradlew --offline lintDebug` — Pass

`.taskcheck`:
- Not run by the fixer (`sh scripts/check.sh` covers server and web too; left to the parent worker).

Remaining unresolved findings:
0

Note: `applyPreset` and `savePreset` read `uiState.value`, which is current while the screen collects the state (always the case while presets can be tapped); all view model tests collect it.

## Final status

All validated findings fixed and validation passed.
