# Code Review

Date: 2026-11-08

Task: and-next-filters - Android Next actions: due, area/project filters and saved presets

Scope: git diff b8737d25f..HEAD (task commits 14dc500ea, 6a4eb538a), committed changes only

Requirements:
- tasks/and-next-filters.md acceptance criteria and recorded ASSUMPTION lines
- AGENTS.md project notes, SPEC.md, docs/ARCHITECTURE.md
- Web reference: web/src/app/features/gtd/next/filter-presets.ts, next-filter.ts
- Server: GtdSettings.kt (nextActionPresets: TextList maxItems 50, maxLength 2000), TaskService due=week (today..today+6)
- Android settings infra: core/settings/UserSettingsRepository.kt, UserSettings.kt

Git range:
- Branch: main
- HEAD: 6a4eb538a9dda1850c0dd51eeb802c1c42b8fa53
- Base/start: b8737d25f11ade96ef73cb524a7d1afc2f5d6840
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 1 P2, 2 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Overlapping preset writes lose updates despite If-Match (a deleted preset comes back)**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/next/NextViewModel.kt:323`
    - Category: Correctness
    - Problem: `write` reads `presetSettings.current()` and builds the new list before calling `store`, and nothing in the view model serializes writes or blocks the UI while one is running (`removePreset` launches immediately; the delete icon in `NextPresetsUi.kt:61` stays active). `UserSettingsRepository.update` takes the ETag inside its lock at send time (`UserSettingsRepository.kt:174`), not when the list was read. Sequence: delete A with stored [A,B,C] builds [B,C] and PATCHes; before it answers, delete B reads the still-unchanged state [A,B,C], builds [A,C] and waits on the repository lock. When the first PATCH succeeds the repository stores the new ETag, the second PATCH is sent with that fresh ETag and succeeds, and A is written back. The same happens with save-then-delete. The web app does not have this problem because its settings update is applied optimistically before the next change is computed.
    - Why it matters: a user action (delete or save) is silently undone on the server and on every device, although the task promises If-Match-protected writes to the shared setting.
    - Suggested fix: serialize preset writes in the view model with a `Mutex` around read, change and store (so the second change is computed from the result of the first), or ignore/disable preset actions while a write is pending. Add a view model test with a suspending fake `store` that issues two deletes back to back and asserts both are gone.

## P3 Low

- [ ] **CR-2 | Preset delete icon has an 18 dp touch target**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/next/NextPresetsUi.kt:58`
    - Category: Quality
    - Problem: the delete action is a `clickable` `Icon` sized 18 dp inside the chip; the code comment explains why an `IconButton` was avoided, but the result is a destructive action well below the 48 dp minimum touch target (Material / Android accessibility guidance), right next to the chip's apply action.
    - Why it matters: easy mis-taps between apply and delete, harder for users with motor impairments.
    - Suggested fix: enlarge the hit area (e.g. `Modifier.minimumInteractiveComponentSize()` on a separate element next to the chip, or a long-press/menu delete), keeping the chip tap for apply.

- [ ] **CR-3 | The 50-preset limit counts only readable presets**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/next/NextViewModel.kt:303`
    - Category: Correctness
    - Problem: `known = NextPresets.read(texts)` skips unreadable texts, but unreadable texts are kept on write (`NextPresets.saved`), and the server's limit (`maxItems = 50`) counts all texts. With 50 stored texts of which some are unreadable, the app sends 51 items, the server answers 400 and the user sees the generic "could not be changed" message instead of TOO_MANY.
    - Why it matters: misleading error message in a rare state.
    - Suggested fix: compare `texts.size` (the list actually written) against `MAX_PRESETS`.

## Notes (verified, no finding)

- Wire format matches the web app both ways: same keys and order (`name`, `filter{context,energy,creativity,maxMinutes,area,project,due}`), empty fields omitted, name trimmed, same-name (any case) replace in place; unknown values are dropped on read like `parseFilter`.
- Due windows match the server (`TaskService` WEEK = today..today+6) and the web chips; "Any" clears the due filter.
- Unloaded settings never wipe presets: `texts == null` yields NotLoaded and no PATCH (covered by `does not write while the settings are not loaded`); conflicts (409/412/428) reload via the repository and surface CONFLICT.
- All 21 new strings exist in `values/` and `values-de/` with matching placeholders.
- Tests cover parsing/encoding, due/area/project filtering, save/apply/delete, conflict/offline/refused writes, the 50 limit and the Compose UI including German strings. Gradle was run by the parent worker (focused gtd.next and core.settings tests reported passing); this review did not run Gradle.

## Review conclusion

Material issues found; fix before completing the task.
