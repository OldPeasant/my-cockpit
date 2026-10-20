# Code Review

Date: 2026-10-20

Task: p0-web-settings - Settings page: structure and general section (second review, final code)

Scope: `git diff b63ff7b2327ae54ac592c645bc55f34e29ec4fc9..729d8b46b038b47360fb60b4c0e87b1d72c7ef8d` (web/src/app/features/settings/**, web/src/app/app.routes.ts, web/src/app/app.spec.ts, web/src/app/features/README.md). Focus on the fix commit 020f466 (TW-1, CR-1..CR-4); the rest of the task diff re-checked against the acceptance criteria. Review reports under `reviews/p0-web-settings/` read as input, not reviewed as code.

Requirements:
- `tasks/p0-web-settings.md` acceptance criteria (3) and its 3 recorded assumptions (login sync via `provideSettingsSync()`, General at `/settings`, per-row auto-save with serialized saves and reload after 412)
- `SPEC.md` §4.2 (ETag/If-Match optimistic locking), §4.7 (Settings)
- `docs/ARCHITECTURE.md`, `AGENTS.md` project notes, `web/src/app/features/README.md`
- Previous review (CR-1..CR-4), `reviews/p0-web-settings/fixes.md`, `reviews/p0-web-settings/tests.md`

Git range:
- Branch: main
- HEAD: 729d8b46b038b47360fb60b4c0e87b1d72c7ef8d
- Base/start: b63ff7b2327ae54ac592c645bc55f34e29ec4fc9 (`git merge-base HEAD origin/main`)
- Commits reviewed: 5 (e40391d settings page sections and general section; 5d38fdf more tests, test report; d477e1e review report; 020f466 fix stale loads and form refresh after saves; 729d8b4 final test pass and report). No uncommitted changes.

Commands run:
- `cd web && npx ng test --watch=false --include 'src/app/features/settings/**/*.spec.ts'` -> 3 test files passed, 42 tests passed
- `cd web && npm run check` -> exit 0: api:check ok, `ng lint` "All files pass linting.", e2e typecheck ok, `ng test` 22 files / 317 tests passed, `ng build` complete
- `python tasks.py verify p0-web-settings` (read-only listing) -> all 3 criteria still unrecorded; the parent records evidence after this review
- Read-only inspection: `git log`, `git diff`, full reads of `settings.service.ts`, `general/general-settings.ts`, the template event bindings, `settings.providers.ts`, `settings.routes.ts`, the new/changed specs and the README diff

Summary: 0 P0, 0 P1, 1 P2, 0 P3

## Status of previous findings

- **CR-1 (P1) stale load overwrites a saved change / check red** - Resolved. `SettingsService.load()` (`settings.service.ts:32-42`) records `version` at start and keeps the current state (values, theme, ETag) when a change was shown since or a PATCH is in flight. Traced paths: two concurrent GETs at login/page open (first answer wins, second returns the shown state, the page shows it); GET answering during or after a PATCH (dropped, PATCH answer's ETag kept, covered by the new spec at `settings.service.spec.ts:240`); the 412 reload in `save()` starts after the rollback's `show()` with `inFlight` at 0 and is applied; a load after the last change is applied (spec `:264`). The TW-1 test passes unchanged; `npm run check` passes.
- **CR-2 (P2) save answer resets the whole label group** - Resolved for the reported case. Per-input `baseline` plus per-row `pending` count (`general-settings.ts:134-137, 174-185, 216-223`) only overwrite inputs unchanged since they were filled or sent, and skip rows with a save still pending. Covered by the specs for typing in a sibling label on success and failure, and for a second theme click on success and failure. See CR-1 below for a new edge case this logic introduced.
- **CR-3 (P2) only the failed row refreshed after a 412** - Resolved. `refresh()` runs after every save answer over all rows without pending saves; the extended 412 spec asserts the reloaded time zone, week start and label are shown, and a new spec checks that a time zone being typed is kept.
- **CR-4 (P3) README missing the shell provider** - Resolved. Row for `settings.providers.ts -> provideSettingsSync()` added; the other rows only changed column padding.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Reverting a row to its in-flight value while a newer save is queued leaves the form showing a value that was never saved**
    - File: `web/src/app/features/settings/general/general-settings.ts:168-171` (with `:216-223` and `settings.service.ts:49-54`)
    - Category: Correctness
    - Problem: `save()` skips a change when it equals `service.settings()[field]`. That signal holds the optimistic value of the save that is currently running, not of saves that are queued behind it (`update()` only shows a patch when its turn in the `saving` chain comes). Sequence: LOW label "Low" -> "A" (PATCH 1 in flight, shown "A") -> "B" (queued, `pending`=2, baseline "B") -> back to "A" before PATCH 1 answers: `sameValue` against "A" returns early, no save, baseline stays "B". PATCH 1 answers, then PATCH 2 saves "B". `refresh()` then sees raw "A" !== baseline "B" and leaves the input alone, so the form shows "A" with the row state "Saved" while server and service hold "B". For the theme row the toggle shows Dark while `ThemeService` applies the saved System. Same for week start and time zone. Before the fix, the final reset at least showed the stored "B"; the baseline logic introduced in 020f466 makes the mismatch persistent until the page is reloaded.
    - Why it matters: Overlapping writes end with the UI silently disagreeing with stored settings; the user believes the value they see was saved. It needs a change back to the earlier value within one PATCH round trip, so it is uncommon but realistic on slow connections (labels and theme clicks).
    - Suggested fix: When the row has a save pending (`pending.get(field) > 0`), compare the new value with the last value sent for that row (e.g. the trimmed baseline) instead of `service.settings()`, or simply do not skip while saves for the row are pending. Add a component test: Dark -> System (queued) -> Dark before the first PATCH answers; assert a third PATCH with `theme: 'dark'` is sent (or that the toggle shows the stored value).

## P3 Low

None.

Checked without findings: AC1 (general at `''` with `pathMatch: 'full'`, six `ComingSoon` placeholders gtd/security/calendars/integrations/mail/backup); AC2 (IANA zone list plus UTC with validation, week start, theme, energy/creativity labels via `PATCH /settings` with `If-Match`, optimistic show and rollback of values and theme, reload after 412, login sync applying the server theme via `ThemeService.setMode` with silent errors); AC3 (service and component specs cover save and rollback on error; `npm run check` green). New fix code adds no logging, no secrets and no changes outside the recorded touches exceptions (`app.routes.ts`, `app.spec.ts`, `features/README.md`). Criterion evidence is not yet recorded with `tasks.py verify`; that is the parent's next step, not a finding.

## Review conclusion

Material issues found; fix before completing the task.
