# Code Review

Date: 2026-10-20

Task: p0-web-devices - Settings: security and devices

Scope: full task diff `git diff fc7370b..HEAD` on the final code (after fixes), including a check of the
fixes for the earlier findings CR-1..CR-4 (`reviews/p0-web-devices/fixes.md`). Committed changes only;
working tree was clean apart from Git-ignored `.tmp/`.

Reviewed revision: 4ad721f1309fb30017d3928652ef4bb6458f6e19

Requirements:
- `tasks/p0-web-devices.md` (acceptance criteria 1-4 and recorded assumptions)
- `SPEC.md` §4.1 (devices listed in settings and revocable; recovery codes)
- `docs/ARCHITECTURE.md`, `docs/QUALITY.md`, `AGENTS.md` project notes
- existing conventions in `server/.../core/auth` and `web/src/app/features/settings`

Git range:
- Branch: main
- HEAD: 4ad721f1309fb30017d3928652ef4bb6458f6e19
- Base/start: fc7370b5a9d9212f5a2633f1c8dc3e7a375e35d2 (merge-base with origin/main)
- Commits reviewed: 7 (7de2f0c, b435ee5, edbf935, 5b82924, a58dd28, 8d6695c, 4ad721f)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Earlier findings (previous review, pre-fix code)

- CR-1 (overlapping revokes share one `revoking` id / unnamed error): fixed. `revoking` is a
  `ReadonlySet<string>` added to on start and removed in `finally`; the button binds
  `revoking().has(device.id)`; `revokeError` carries the device name. Test "keeps each revoke button
  disabled while its own request runs, when revokes overlap" covers it.
- CR-2 (409 not documented): fixed. `@ApiResponse(responseCode = "409")` added in
  `RecoveryCodeController.kt`, `api/openapi.yaml` regenerated (409 present), `api:check` reports the
  generated client up to date.
- CR-3 (`cdkFocusInitial` ignored with `autoFocus: 'dialog'`): fixed. The dialog is opened without
  `autoFocus`, so the Material default honours `cdkFocusInitial` on Cancel; test "focuses Cancel when the
  revoke dialog opens".
- CR-4 (focus lost when elements disappear): fixed. `focusIfLost` (after next render, only when
  `activeElement` is `<body>`) moves focus to the codes title, next/previous enabled revoke button or the
  Devices heading; `doneWithCodes` refocuses the TOTP input. Covered by the "keyboard focus" tests.

Note: `fixes.md` names the fix commit `e46ff3d`; in the task history it is `a58dd28`. Both have the same
`git patch-id` (6b56e86a...), i.e. `a58dd28` is the rebased copy. Not a code issue.

## Acceptance criteria

1. Lists devices (name, last used) with revoke + confirm dialog: met. `security-settings.html` shows
   `device.name` and `lastSeenAt | date`; `revoke()` opens `RevokeDeviceDialog` and only calls DELETE on
   `true`; cancel/dismiss keep the device; 404 drops the row; other errors are shown with the device
   name; load error has a retry.
2. Server `POST /api/v1/auth/recovery-codes` requires current TOTP, UI shows codes once: met.
   `RecoveryCodeService` checks the throttle, an active account (409 otherwise) and the TOTP, then
   replaces all codes with hashed new ones; response is `Cache-Control: no-store`; request/response/result
   `toString` hide secrets; logs carry no codes. The UI keeps codes only in a component signal, cleared by
   "I saved them" or leaving the page.
3. Vitest tests for device list and revoke flow; `npm run check` passes: met (see commands).
4. `./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` passes with Postgres via Testcontainers for
   the endpoint: met. `RecoveryCodeWebTests` extends `PostgresTestBase` (11 tests) and passed.

## Commands run

- `git rev-parse HEAD` -> 4ad721f1309fb30017d3928652ef4bb6458f6e19
- `git diff fc7370b..HEAD`, `git log fc7370b..HEAD` -> 7 commits, 23 files
- `git diff e46ff3d^ e46ff3d | git patch-id` vs `git diff a58dd28^ a58dd28 | git patch-id` -> identical
- `cd web && npm run -s check` -> pass: "src/app/api/generated is up to date", "All files pass
  linting", Test Files 24 passed (24), build complete; one build warning "bundle initial exceeded maximum
  budget ... by 19 bytes with a total of 500.02 kB" (see CR-1)
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` -> BUILD SUCCESSFUL; JUnit XML
  for the recovery-code suites: RecoveryCodeWebTests 11/0 failures, RecoveryCodeSecretHidingTests 2/0
  failures, plus a 5-test suite matching `*RecoveryCode*` with 0 failures

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Initial bundle now exceeds the 500 kB warning budget by 19 bytes**
    - File: `web/angular.json:45` (budget), growth likely from `web/src/app/features/settings/security/security-settings.ts:3-15`
    - Category: Quality
    - Problem: `npm run check` on HEAD prints "bundle initial exceeded maximum budget. Budget 500.00 kB was
      not met by 19 bytes with a total of 500.02 kB". A web check log from before the fix commit
      (`.tmp/review-web-check.log`, 15:59) reports an initial total of 498.14 kB (main 36.17 kB); the log
      after the fixes (`.tmp/web-check.log`, 16:22, same pre-rebase base) reports 500.02 kB (main 37.96 kB).
      The fix commit introduced `afterNextRender`/`viewChildren`, which no other app code uses; since
      `@angular/core` is bundled into eager chunks, that is the probable cause, though not proven by a
      build of the base.
    - Why it matters: the budget is the project's guard against initial-bundle growth; every later task
      will now see this warning and may ignore real growth, or attribute it to itself.
    - Suggested fix: confirm the cause (e.g. `ng build --stats-json` on HEAD vs. fc7370b). Then either
      accept it deliberately (raise `maximumWarning` slightly and record a `tasks.py assume`), or avoid the
      extra core API in the eager bundle. Non-blocking.

## Review conclusion

No material issues found. The earlier findings CR-1..CR-4 are properly fixed; one low-priority,
non-blocking observation (bundle budget warning) remains for the parent worker to dispose.
