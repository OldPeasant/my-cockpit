# Code Review

Date: 2026-10-20

Task: p0-web-settings - Settings page: structure and general section (final review, revision 4571766)

Scope: `git diff 7c425ce2600f4bac91c024c541bc4ba2dd144cf8..4571766254c7335ee15ae21bddec336f28b060ba` (web/src/app/features/settings/**, web/src/app/app.routes.ts, web/src/app/app.spec.ts, web/src/app/features/README.md). Production code was reviewed at 0df3015 (third review, no findings). This pass covers commit 4571766, which `git diff 0df3015 4571766 -- web/` confirms only adds two tests to `web/src/app/features/settings/general/general-settings.spec.ts`; no production code changed. Committed changes only; the working tree was clean. Report files under `reviews/p0-web-settings/` were read as input, not reviewed as code.

Requirements:
- `tasks/p0-web-settings.md`: 3 acceptance criteria and 3 recorded assumptions (login sync via `provideSettingsSync()`, General at `/settings`, per-row auto-save with serialized saves and a reload after 412)
- `SPEC.md` §4.2 (ETag/If-Match), §4.7 (Settings)
- `docs/ARCHITECTURE.md`, `AGENTS.md` project notes, `web/src/app/features/README.md`
- Previous reviews of this task, `reviews/p0-web-settings/fixes.md`

Git range:
- Branch: main
- HEAD: 4571766254c7335ee15ae21bddec336f28b060ba
- Base/start: 7c425ce2600f4bac91c024c541bc4ba2dd144cf8 (merge-base with origin/main)
- Commits reviewed: 8 (code commits d21fdf4, 6a996a1, ccbf783, 0df3015; test commit 4571766; report commits 4789fb9, 9055321, 9bbf846)
- Uncommitted changes: none

Commands run:
- `git diff 0df3015 4571766 -- web/` -> 40 added lines in `general-settings.spec.ts` only
- `cd web && npx ng test --watch=false --include 'src/app/features/settings/**/*.spec.ts'` -> 3 files, 46 tests passed (44 before plus the 2 new ones)
- Earlier pass at 0df3015: `cd web && npm run check` -> passed through the final `ng build`; production code is unchanged since then

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Review of the test addition (4571766)

- **"does not send a label again that only gains spaces while its save is in flight"** - Edits LOW to `Tired`, then to `  Tired ` while the first PATCH is pending. `save()` (`general-settings.ts:171-193`) trims the value and, with `pending > 0`, compares against `lastSent` (`Tired`), so no second PATCH is sent. The test asserts `http.expectNone` for PATCH and the `Saved` state; `afterEach(http.verify())` also guards against stray requests. This covers the whitespace-only edge case of the `lastSent` comparison.
- **"saves the original label when it is changed back while its save is in flight"** - Edits LOW to `Tired`, then back to `Low` while the first PATCH is pending. `Low` differs from `lastSent`, so a second PATCH is queued and sent after the first answer with `If-Match: "2"` and the full label group; the final input shows `Low`. This is the regression case for the original revert behavior that the 0df3015 fix had to keep.
- Both tests use the existing helpers (`setup`, `editLabel`, `tick`, `fakeSettings`), assert observable behavior (requests, body, headers, rendered values) rather than internals, and use fictitious data. No existing test was changed, removed or skipped.

## Status of previous findings

- **First review CR-1..CR-4, TW-1** - Resolved in ccbf783, confirmed by the second review.
- **Second review CR-1 (P2)** - Resolved in 0df3015, confirmed by the third review; the new tests add coverage around the same comparison.

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
- AC2: time zone (IANA list from `Intl.supportedValuesOf` plus UTC, validated with `oneOf`), week start, theme and energy/creativity labels saved through `PATCH /settings` with `If-Match`; optimistic show plus theme apply, rollback of values and theme on error, reload after 412; `provideSettingsSync()` loads the settings on login with silent errors and applies the server theme via `ThemeService.setMode`. Stale GET answers do not overwrite newer state.
- AC3: service and component specs cover save, rollback on error and the overlapping-save cases; the settings suite passes on 4571766 (46 tests).
- No logging, secrets or files outside the task's touches were added by 4571766.

## Review conclusion

No material issues found.
