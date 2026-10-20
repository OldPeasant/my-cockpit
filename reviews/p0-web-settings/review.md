# Code Review

Date: 2026-10-20

Task: p0-web-settings - Settings page: structure and general section

Scope: `git diff 40301a8f4aee5144bf58cc316d75f43698e73517..5e3573ae266678820b5cb366ef9770a2e2a783d5` (web/src/app/features/settings/**, web/src/app/app.routes.ts, web/src/app/app.spec.ts; `reviews/p0-web-settings/tests.md` read as input, not reviewed as code)

Requirements:
- `tasks/p0-web-settings.md` acceptance criteria (3) and the 3 recorded assumptions (login sync via `provideSettingsSync()`, General at `/settings`, per-row auto-save with serialized saves and reload after 412)
- `SPEC.md` §4.2 (ETag/If-Match optimistic locking), §4.7 (Settings)
- `docs/ARCHITECTURE.md`, `AGENTS.md` project notes, `web/src/app/features/README.md`
- Test-writer report `reviews/p0-web-settings/tests.md` (TW-1)

Git range:
- Branch: main
- HEAD: 5e3573ae266678820b5cb366ef9770a2e2a783d5
- Base/start: 40301a8f4aee5144bf58cc316d75f43698e73517
- Commits reviewed: 2 (a75b722 settings page sections and general section; 5e3573a more tests, test report). No uncommitted changes.

Commands run:
- `cd web && npx ng test --watch=false --include 'src/app/features/settings/**/*.spec.ts'` -> 3 files, 34 tests: 33 passed, 1 failed (`SettingsService > keeps a saved change when an older load answers after it`: expected 'dark', received 'light'), i.e. TW-1 reproduces at HEAD.
- Read-only inspection: `git log`, `git diff`, source/spec/template reads, `grep` of i18n ids and `AuthService`/`ThemeService`.

Summary: 0 P0, 1 P1, 2 P2, 1 P3

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | Stale settings load overwrites a saved change (TW-1 confirmed); `npm run check` is red at HEAD**
    - File: `web/src/app/features/settings/settings.service.ts:28-33` (with `settings.providers.ts:26`, `general/general-settings.ts:151`; failing test `settings.service.spec.ts:218`)
    - Category: Correctness
    - Problem: `load()` unconditionally calls `accept()` (values + ETag) and `theme.setMode()` with whatever its GET returned, regardless of how old the request is. Opening `/settings` right after login starts two GETs at once (login sync and the page). If the sync GET answers last, after a successful PATCH, it restores the pre-save values, the old theme and the old ETag; the next save then gets a 412, an error toast and a rollback. Refinement of TW-1: the same happens while a PATCH is still in flight (the optimistic value and theme are replaced by the server's old state until the PATCH answers), and the stale ETag is the worse part, because it turns the user's next unrelated change into a failed save. The deliberately failing test keeps AC3 ("`cd web && npm run check` passes") unmet at HEAD, which is why this is P1 for the task; the defect on its own is P2 (needs reversed response order, self-heals after the 412 reload).
    - Why it matters: Out-of-order async results overwriting newer state; breaks the If-Match chain the serialized saves were designed to keep (assumption 3, SPEC §4.2), and the task's check cannot pass.
    - Suggested fix: Give loads a request identity and drop any GET answer whose request started before the latest accepted response (a sequence counter incremented on every request and recorded on accept), or share a single in-flight load promise between the sync provider, the page and `save()`; additionally route `load()` through the same `saving` queue or ignore load answers while a save is pending. Keep the TW-1 test and make it pass.

## P2 Medium

- [ ] **CR-2 | A save answer resets the whole label group, wiping a label the user is typing in**
    - File: `web/src/app/features/settings/general/general-settings.ts:171` and `:176` (via `showField`, `:205-207`)
    - Category: Correctness
    - Problem: Labels save on each input's `change` event (template `(change)="save('energyLabels')"`), but on success or failure `showField(field, settings)` calls `reset()` on the whole `energyLabels`/`creativityLabels` group. Edit LOW, tab to MEDIUM and start typing: when the LOW PATCH answers, MEDIUM's DOM value is overwritten with the server value and the typed text is lost (on failure, also rolled back). The same reset makes a quickly re-clicked theme toggle jump back to the first choice until the queued second save completes.
    - Why it matters: Silent loss of user input in normal, quick keyboard use; the save latency window is exactly when the user moves to the next field of the same group.
    - Suggested fix: Only write back controls that the user has not changed since the save started (e.g. skip reset when the control's current raw value differs from the value that was sent, or patch only the keys that were sent and leave dirty siblings alone). Add a component test: edit LOW, then type in MEDIUM before flushing the PATCH, and assert MEDIUM keeps the typed text.

- [ ] **CR-3 | After a 412 only the failed row is refreshed; other rows show stale values while the service (and theme) holds the reloaded ones**
    - File: `web/src/app/features/settings/general/general-settings.ts:173-177`
    - Category: Correctness
    - Problem: A 412 means another tab/device changed the settings. The service reloads them (`settings.service.ts:62-64`), and that reload also applies the server theme via `ThemeService.setMode`. The component then only calls `showField(field, current)` for the row that failed. If the other device changed, for example, the theme or time zone, those rows keep showing the old value: the page is visibly dark while the toggle shows Light, and `save()` compares against the reloaded values (`sameValue(value, saved[field])`), so re-selecting the displayed value is treated as a change and picking the actually-current value is not saved. The test `shows the reloaded server value after a 412` (spec line 244) covers only the failed row.
    - Why it matters: The form no longer matches the stored settings after exactly the concurrent-edit case the 412 handling exists for (assumption 3: "after a 412 the settings are reloaded").
    - Suggested fix: On failure, re-show all rows from `service.settings()` that the user has not edited (or simply `show(current)` when no other save is pending). Extend the 412 component test so the reload returns a different value in another row and assert it is displayed.

## P3 Low

- [ ] **CR-4 | Feature README does not list the new shell provider**
    - File: `web/src/app/features/README.md` (table "Not routed, but provided to the shell"; `app.routes.ts:35` now adds `provideSettingsSync()`)
    - Category: Quality
    - Problem: `app.routes.ts` now provides `provideSettingsSync()` from `settings/settings.providers.ts` to the shell, but the README table that documents exactly these shell providers (capture, search) was not updated; the README only says the settings feature "syncs" the theme.
    - Why it matters: The README is the map other feature tasks use to find shell-level wiring; an undocumented environment initializer that issues a GET on every login is easy to miss or duplicate.
    - Suggested fix: Add a row `settings/ | settings.providers.ts -> provideSettingsSync() | loads GET /settings on login and applies the server theme`. (The file is outside the task's `touches`; one line, same as the `app.routes.ts` exception already recorded.)

Checked without findings: AC1 child routes and placeholders (general at `''` with `pathMatch: 'full'`, six `ComingSoon` placeholders, titles, layout nav with `ariaCurrentWhenActive`, `routerLinkActiveOptions exact`); optimistic update and rollback of values and theme on non-412 errors and network errors; serialized saves sending the latest ETag; `null` resets in a patch; time zone validation against the IANA list plus UTC, blur restore and Enter save; blank/over-long label validation and trimming; login sync effect (silent errors, re-runs on user change, `untracked` load); i18n ids reused only with identical source text (`@@nav.settings`, `@@settings.general`, `@@theme.*`); a11y labelling of all inputs, toggle group and select; the `app.spec.ts` change keeps placeholder tests for every other section. No secrets or sensitive logging in the diff.

## Review conclusion

Material issues found; fix before completing the task.
