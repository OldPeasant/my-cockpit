# Code Review

Date: 2026-10-20

Task: p0-web-settings - Settings page: structure and general section (third review, final code)

Scope: `git diff 7c425ce2600f4bac91c024c541bc4ba2dd144cf8..0df301561a14df382a604353110cdce650360a9d` (web/src/app/features/settings/**, web/src/app/app.routes.ts, web/src/app/app.spec.ts, web/src/app/features/README.md). Focus on fix commit 0df3015 (second review CR-1); the rest of the task re-checked against the acceptance criteria. Committed changes only. Report files under `reviews/p0-web-settings/` were read as input, not reviewed as code.

Requirements:
- `tasks/p0-web-settings.md`: 3 acceptance criteria and 3 recorded assumptions (login sync via `provideSettingsSync()`, General at `/settings`, per-row auto-save with serialized saves and a reload after 412)
- `SPEC.md` §4.2 (ETag/If-Match), §4.7 (Settings)
- `docs/ARCHITECTURE.md`, `AGENTS.md` project notes, `web/src/app/features/README.md`
- Previous review (`reviews/p0-web-settings/review.md`, second review CR-1), `reviews/p0-web-settings/fixes.md`

Git range:
- Branch: main
- HEAD: 0df301561a14df382a604353110cdce650360a9d
- Base/start: 7c425ce2600f4bac91c024c541bc4ba2dd144cf8 (merge-base with origin/main)
- Commits reviewed: 7 (code commits d21fdf4, 6a996a1, ccbf783, 0df3015; report commits 4789fb9, 9055321, 9bbf846)
- Uncommitted changes: at the time of this review the working tree had 40 added lines in `general-settings.spec.ts` that are not part of HEAD (presumably the concurrent test-writer pass). They are outside this review's scope; the parent must commit them before recording `reviewed_revision`, which would make this review predate the final revision if production code changes as well.

Commands run:
- `cd web && npx ng test --watch=false --include 'src/app/features/settings/**/*.spec.ts'` -> 3 files, 44 tests passed
- `cd web && npm run check` -> completed through the final `ng build` step ("Application bundle generation complete"); the script chains api:check, lint, e2e:typecheck, test:ci and build with `&&`, so all earlier steps passed
- `python tasks.py verify p0-web-settings` (read-only listing) -> all 3 criteria still unrecorded; recording evidence is the parent's step
- Read-only inspection: `git log`, `git show 0df3015`, full reads of `general-settings.ts`, `general-settings.html`, `settings.service.ts`, `settings.providers.ts`, `settings.routes.ts`, `settings-layout.ts`

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Status of previous findings

- **First review CR-1..CR-4, TW-1** - Resolved in ccbf783 and confirmed by the second review; the code they touched is unchanged except for the lines below.
- **Second review CR-1 (P2) reverting a row to its in-flight value while a newer save is queued** - Resolved. `save()` (`general-settings.ts:171-193`) now compares the new value with `lastSent` (the row's latest sent value) while `pending.get(field) > 0`, and with `service.settings()[field]` otherwise; `lastSent` is set before the save is queued. Traced paths:
  - A -> B (queued) -> A: third save of A is queued and sent with the ETag of the second answer; final form, service and server agree (spec "saves a label changed back to its in-flight value while a newer save is queued").
  - Theme Dark -> System -> Dark: same, and `ThemeService.mode()` ends at `dark` (spec "saves a theme picked back while a newer theme save is queued").
  - Change back to the original value while one save is in flight: `lastSent` differs, so the revert is saved, as before.
  - Earlier save fails while a later one for the row is queued: `lastSent` still holds the queued value, which is what the server will hold after it; a later failure rolls back and `refresh()` (pending 0, input equals baseline) shows the stored value.
  - Rows without pending saves keep the previous comparison with the saved settings.
  - `fixes.md` states both new tests fail without the fix. I did not repeat that; the test logic matches the fixed path when read against the code.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

Checked without findings:
- AC1: `settings.routes.ts` has a layout route with General at `''` (`pathMatch: 'full'`) and `ComingSoon` placeholders for gtd, security, calendars, integrations, mail, backup, driven by `settings-sections.ts`.
- AC2: time zone (IANA list from `Intl.supportedValuesOf` plus UTC, validated with `oneOf`), week start, theme and energy/creativity labels saved through `PATCH /settings` with `If-Match`; optimistic show plus theme apply, rollback of values and theme on error, reload after 412; `provideSettingsSync()` loads the settings on login with silent errors and applies the server theme via `ThemeService.setMode`. Stale GET answers do not overwrite newer state (`version`/`inFlight` guard).
- AC3: service and component specs cover save, rollback on error and the overlapping-save cases; `npm run check` passes on HEAD.
- The fix adds no logging, secrets or files outside the task's touches; changes outside `web/src/app/features/settings` (`app.routes.ts`, `app.spec.ts`, `features/README.md`) are the ones recorded in the task assumptions.

## Review conclusion

No material issues found.
