# Code Review

Date: 2026-11-08

Task: and-next-filters - Android Next actions: due, area/project filters and saved presets

Scope: git diff b8737d25f..bffd4e704 (whole task diff, committed changes only), second review pass on the final code

Reviewed revision: bffd4e70439887e4e13a40f166392a91d5cf7fa4

Requirements:
- tasks/and-next-filters.md acceptance criteria and recorded ASSUMPTION lines
- AGENTS.md project notes, SPEC.md, docs/ARCHITECTURE.md
- Web reference: web/src/app/features/gtd/next/filter-presets.ts, next-filter.ts
- Server: GtdSettings.kt (nextActionPresets: TextList maxItems 50), TaskService due=week (today..today+6)
- Android settings infra: core/settings/UserSettingsRepository.kt (update stores the new state and ETag before it returns; a conflict reloads), UserSettings.kt
- Earlier reports: reviews/and-next-filters/review.md (first pass, CR-1..CR-3), tests.md (TW-1, TW-2, save-race observation), fixes.md

Git range:
- Branch: main
- HEAD: bffd4e70439887e4e13a40f166392a91d5cf7fa4
- Base/start: b8737d25f11ade96ef73cb524a7d1afc2f5d6840
- Commits reviewed: 4 (14dc500ea, 6a4eb538a, 33523ad8d, bffd4e704)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Disposition of earlier findings

- **CR-1 (P2) Overlapping preset writes lose updates: fixed.** `NextViewModel.write` (`NextViewModel.kt:339`) now holds the `presetWrites` Mutex around ensureLoaded, read, change and store. Because `UserSettingsRepository.update` stores the accepted settings (and reloads on 409/412) before it returns, the next write inside the lock reads the list the previous write produced. Cancellation is safe: `withLock` releases the lock in a finally block, and `catching` passes `CancellationException` on, so a cleared view model cannot leave the lock held. The new test `two deletes back to back both stay deleted` fails without the lock (the second delete would call store while the first is still waiting, so `storeCalls` would be 2) and checks the exact sequence of writes, so it is a real regression test.
- **CR-2 (P3) 18 dp delete touch target: fixed.** Each preset is a `Row` of the `FilterChip` (apply) and an `IconButton` (delete, 48 dp touch target, 18 dp icon, content description with the preset name) inside the `FlowRow` (`NextPresetsUi.kt:51`). The chip and the delete button are siblings, so their touch areas do not overlap. A Row with two children wraps as one unit in the FlowRow, so a chip never ends up on a different line from its delete button. The test `the delete button has a full touch target next to the chip and leaves the chip's taps to applying` checks the 48x48 dp touch size and that each element triggers its own callback.
- **CR-3 (P3) Limit counts only readable presets: fixed.** `savePreset` compares `texts.size` (the list that is actually written) against `MAX_PRESETS` for a new name, and still allows replacing a preset with the same name (`NextViewModel.kt:317`). Covered by `unreadable stored texts count against the preset limit`.
- **TW-1 Tapping a non-active preset cleared the filter after its context was deleted: fixed.** `applyPreset` compares the shown filter (`uiState.value.filter`), the same value the active chip is computed from (`NextViewModel.kt:275`). Covered by `tapping a preset that is not shown as active applies it, also when its context was deleted`.
- **TW-2 Saving stored a deleted context id: fixed.** `savePreset` saves `uiState.value.filter` (`NextViewModel.kt:310`). Covered by `saving after the chosen context was deleted stores the filter the screen shows`.
- **Save-race observation (tests.md): fixed.** A save captures `editorSession` and only updates the editor if the session has not changed since. The counter is increased on open and on cancel (`NextViewModel.kt:282`, `:295`, `:323`). Two new tests cover a late success and a late failure after the dialog was cancelled and opened again. If a save was cancelled while it was running, it may still be committed and the preset appears in the list. That is correct, because the server may already have accepted it.
- **Lint fixes:** `LocalWindowInfo.current.containerSize.height` halved and converted with `LocalDensity` replaces `screenHeightDp` (`NextScreen.kt:237`). It is snapshot state, so the limit updates when the window size changes. `next_preset_too_many` is now a `plurals` resource in both languages with matching `%1$d` placeholders, read with `pluralStringResource(..., MAX_PRESETS, MAX_PRESETS)`.

## Checked in this pass (no finding)

- **Is `uiState.value` stale when it is not collected?** `applyPreset`, `savePreset`, `openSavePreset` and the earlier `chooseArea` read `uiState.value`, which `stateIn(WhileSubscribed(5s))` keeps up to date only while someone collects it. These methods are only reachable from the screen, which collects with `collectAsStateWithLifecycle`. The tests collect it in `backgroundScope`. With the view model's `Main.immediate` dispatcher, an earlier filter change is reflected before the next tap. I found no path where a stale value changes the result.
- **Acceptance criterion 1 (filters):** due chips (Any / Overdue / Today / Next 7 days), with "Any" clearing the filter and the windows matching the server's `due` values. The area and project menus filter on the client side through the cached project's areaId. The project list is narrowed to the chosen area, and choosing another area clears a project from a different area.
- **Acceptance criterion 2 (presets):** apply, save and delete go through `UserSettingsRepository` (`nextActionPresets`, PATCH with If-Match) in the web wire format: key order, empty fields omitted, same-name replace ignoring case. Unreadable texts are kept. Settings that are not loaded never wipe presets, because the write yields NotLoaded and sends no PATCH.
- **Acceptance criterion 3 (strings):** all new strings and the plural exist in `values/` and `values-de/` with matching placeholders. A Compose test checks the German strings.
- **Acceptance criterion 4 (tests and checks):** model, parse/encode, view model edge cases (ordering, cancel and reopen, load failures, store exceptions, remote changes) and Compose UI tests cover the new behavior. The tests assert behavior through callbacks and state, not implementation details. The parent worker reports that `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` exits 0 on bffd4e704 (3672 tests, 0 failures, task-file line coverage 99.4%). This review did not run Gradle. Whether `sh scripts/check.sh` passes remains for the parent worker to record.
- **Scope:** the only change outside `touches` is the small `UserSettings.kt` extension, which is recorded as an assumption. There are no secrets, and logging is unchanged.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.
