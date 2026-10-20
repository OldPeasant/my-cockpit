# Test Report

Date: 2026-10-20

Task: p0-web-devices - Settings: security and devices

Scope: `git diff 3ff1ad46f32daf1f2c6613517d588473b4919dbd..4cc1173f10f3a99365b8442a164cea57660a37c5` on branch
`main` (task commits `7a6354d` server endpoint, `5292346` web section, `cd051b1` tests, `417630f` review,
`ff19946` fixes for CR-1..CR-4, `4cc1173` fix report), plus the uncommitted test addition listed below.
This is a re-run on the final code after the fixes; the previous `tests.md` (measured at `b174ab7`, before the
fixes) is replaced. Task ownership: parent-supplied base and commit list, cross-checked against
`tasks/p0-web-devices.md` acceptance criteria and `touches`, and `reviews/p0-web-devices/fixes.md`.

Reviewed revision: `4cc1173f10f3a99365b8442a164cea57660a37c5` plus the working-tree change to
`web/src/app/features/settings/security/security-settings.spec.ts` (sha256
`3cdf9d4122bc858216b3b6439f46665d21e197d27395e28fed3c9a1ef416b4a9`). No production file differs from
`4cc1173`. Once the parent commits the test, that commit is the tested revision.

Requirements:
- `tasks/p0-web-devices.md` acceptance criteria 1-4 and recorded assumptions (shared LoginThrottle, wrong TOTP is
  400 field `totp`, all old codes replaced by 10 new ones, codes held only in component state)
- `reviews/p0-web-devices/review.md` findings CR-1..CR-4 and `reviews/p0-web-devices/fixes.md` (per-device pending
  revoke set, named revoke error, 409 documented in OpenAPI, Cancel focused in the dialog, focus management)
- `SPEC.md` §4.1, `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`

## Test files changed

- `web/src/app/features/settings/security/security-settings.spec.ts` (2 tests added in the "keyboard focus" block)

## Tests added or updated

Count: 2

- `skips a next row whose own revoke is still pending when moving the focus`: the focus moves (CR-4) and the
  per-device pending set (CR-1) together. Laptop's revoke is still running, so its button is disabled; Tablet is
  revoked with the keyboard, and the focus goes to the previous row (Pixel 9), not to the disabled next one. When
  Laptop's late answer arrives, the focus stays on Pixel 9.
- `focuses the heading when the only neighbouring row is still being revoked`: if the only remaining row is still
  pending, the focus goes to the Devices heading. The later answer then shows the empty state.

All the other fix behaviour was already tested by the tests from `ff19946`, so no other tests were needed:
overlapping revokes, the named error, the dialog's initial focus, focus moving to the next row, the previous row
and the heading, the 404 path, focus the user moved elsewhere, and the codes title and TOTP field focus.
The 409 response (CR-2) is tested by `an authenticated request without an active account is a 409 and creates no
codes`. Its documentation is checked by `OpenApiContractTest` and by `npm run api:check` inside `npm run check`.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: lists devices (name, last used) | `lists the devices with name and last use`, `says so when there are no devices`, `shows a load error with a retry`, `shows the loading state until the device list arrives` | `cd web && npm run -s check` | Pass |
| AC1: revoke + confirm dialog | `revokes a device after the confirmation`, `keeps the device when the confirmation is cancelled`, `does not revoke when the dialog is dismissed without an answer`, `shows a failed revoke and keeps the device`, `drops a device that was already revoked elsewhere`, `revokes the last device and then shows the empty state`, `focuses Cancel when the revoke dialog opens` | `cd web && npm run -s check` | Pass |
| CR-1: per-device pending set, named error | `disables only the revoke button of the device being revoked while it is pending`, `keeps each revoke button disabled while its own request runs, when revokes overlap`, `shows a failed revoke and keeps the device`, `skips a next row whose own revoke is still pending when moving the focus` (new) | `cd web && npm run -s check` | Pass |
| CR-4: focus management | `moves the focus to the next row after a revoke, then the previous one, then the heading`, `moves the focus on when a device was already revoked elsewhere`, `leaves the focus where the user moved it while the revoke ran`, `focuses the new codes, and the code field again once they are saved`, `skips a next row whose own revoke is still pending when moving the focus` (new), `focuses the heading when the only neighbouring row is still being revoked` (new) | `cd web && npm run -s check` | Pass |
| AC2: server `POST /api/v1/auth/recovery-codes` needs a current TOTP, replaces codes | `RecoveryCodeWebTests` (11 tests: success + hashes stored, used code replaced, wrong/missing TOTP 400, throttling, throttle reset, throttled correct code, whitespace, malformed body, session + CSRF, 409 without active account, nothing logged), `RecoveryCodeSecretHidingTests` (2), `RecoveryCodesTests` (5) | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*' --tests '*OpenApiContractTest' jacocoTestReport ktlintCheck` | Pass |
| CR-2: 409 documented | `OpenApiContractTest`, `npm run api:check` ("src/app/api/generated is up to date") | same server command; `cd web && npm run -s check` | Pass |
| AC2: UI shows the codes once | `shows new recovery codes once, for a current TOTP code`, `does not keep the codes after the page is left`, `explains a wrong TOTP code and a throttled attempt`, the TOTP validation `it.each`, `sends the code only once while the request is pending`, `re-enables the form after a failed attempt ...`, `asks to try later ...`, `shows a generic message ...`, `keeps the copy label when the clipboard refuses` | `cd web && npm run -s check` | Pass |
| AC3: Vitest tests cover device list and revoke flow; `npm run check` passes | `security-settings.spec.ts`, `security.service.spec.ts`, `settings.routes.spec.ts` | `cd web && npm run -s check` | Pass (24 files, 359 tests) |
| AC4: `./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` passes, Postgres via Testcontainers | `RecoveryCodeWebTests` extends `PostgresTestBase` | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*' --tests '*OpenApiContractTest' jacocoTestReport ktlintCheck` | Pass (21 suites, 179 tests, 0 failures/errors/skipped) |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include src/app/features/settings`

Result:

Pass (5 files, 84 tests)

## Full test execution

Command:

`cd web && npm run -s check` (api:check, ESLint, e2e typecheck, Vitest, `ng build`)

Result:

Pass: "src/app/api/generated is up to date", "All files pass linting", Test Files 24 passed, Tests 359 passed,
build complete. The build printed one warning: "bundle initial exceeded maximum budget ... by 19 bytes with a
total of 500.02 kB" (a warning threshold, not an error). This run does not show whether the task caused it,
because the task's section is a lazy chunk (`security-settings` 13.53 kB). It is reported for information only.

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*' --tests '*OpenApiContractTest' jacocoTestReport ktlintCheck`

Result:

Pass: BUILD SUCCESSFUL in 2m 22s. JUnit XML: 21 suites, 179 tests, 0 failures, 0 errors, 0 skipped
(`RecoveryCodeWebTests` 11, `RecoveryCodeSecretHidingTests` 2, `RecoveryCodesTests` 5, `OpenApiContractTest` 1).

## .taskcheck

Result:

Pass: `sh scripts/check.sh` -> "== all checks passed" (server, web and android). It ran from
2026-10-20T16:26:04+02:00 to 16:31:47+02:00, about 5m43s. That is above the 2-minute warning in `done` but
within the 15-minute timeout. Other work on the machine was running at the same time (Gradle, Vitest).

## Bugs found

None.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (the tooling has no changed-line mode). Every measured hand-written file is
100% on lines, so every executable changed line in those files is covered too.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `p0-web-devices`; base
  `3ff1ad46f32daf1f2c6613517d588473b4919dbd`; HEAD `4cc1173f10f3a99365b8442a164cea57660a37c5`; the parent
  supplied the commits (`git log 3ff1ad4..HEAD`), cross-checked against the task file and `fixes.md`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `4cc1173` plus the modified
  `web/src/app/features/settings/security/security-settings.spec.ts`, sha256 `3cdf9d41...b4a9` (full hash above).
  `git status` and the hash were the same before and after all runs.
- Exact included files: `git diff --name-only 3ff1ad4..HEAD -- server/src/main web/src android/app/src/main`
  without `*.spec.ts`:
  - measured: `server/.../core/auth/api/RecoveryCodeController.kt`, `server/.../core/auth/domain/RecoveryCodeService.kt`,
    `web/src/app/features/settings/security/security-settings.ts`, `.../security.service.ts`,
    `.../revoke-device-dialog.ts`, `web/src/app/features/settings/settings.routes.ts`;
  - `security-settings.html` is not reported separately: Angular compiles the template into the component, and
    the provider has no separate file entry for it. The `.scss` file has no executable behaviour, so it is excluded;
  - generated client (`web/src/app/api/generated/**`): excluded because it is generated code.
    `fn/auth/regenerate-recovery-codes.ts` was measured anyway: 7/7 statements, 1/2 branches, 3/3 functions,
    7/7 lines. `functions.ts`, `index.ts`, `models.ts` and the two `models/*.ts` files have no entry in the
    report. They contain only type and re-export lines with no executable statements;
  - no renamed or deleted production files.
- Provider/version, command, working directory, and test selection:
  - web: Vitest 5.0.5 + `@vitest/coverage-v8`, `npm --prefix web run -s coverage` (= `ng test --watch=false --coverage ...`),
    all 24 spec files (359 tests);
  - server: JaCoCo (Gradle `jacoco` plugin), Gradle 9.8.1, `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*' --tests '*OpenApiContractTest' jacocoTestReport ktlintCheck`.
- Run start/completion time and exit status: web 2026-10-20T16:22:22+02:00 to 16:22:44+02:00, exit 0;
  server 16:23:04+02:00 to 16:25:27+02:00, exit 0 (BUILD SUCCESSFUL).
- Generated report paths and report hashes/run identifier:
  `web/coverage/web/coverage-final.json` sha256 `7184db22eefe8f72426806a9edf5ac2db6df4dadef52f75dc1ee443808a4f958`,
  `web/coverage/web/coverage-summary.json` sha256 `d14095c4df7f731135340f9d01410bcf35f0a4213faa9058596f182ccadfa385`
  (both written 16:22:44); `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256
  `25e03a634fb46570971766a7d6b253c85e4d44cce6079ea2ebb01b0c448dcd06` (written 16:25:27).
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report modification times fall inside each
  run's window, the JUnit timestamps are from 2026-10-20T14:24-14:25Z, and the snapshot was unchanged.
- Final run after latest test additions and production fixes: yes. These runs include the fix commit `ff19946`
  and the 2 new tests.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.
Server "Statements" are JaCoCo instructions and "Functions" are JaCoCo methods.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/api/RecoveryCodeController.kt` | 109/109 (100%) | 4/4 (100%) | 8/8 (100%) | 26/26 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/domain/RecoveryCodeService.kt` | 150/150 (100%) | 10/10 (100%) | 7/7 (100%) | 25/25 (100%) |
| `web/src/app/features/settings/security/security-settings.ts` | 89/89 (100%) | 44/47 (93.61%) | 23/23 (100%) | 73/73 (100%) |
| `web/src/app/features/settings/security/security.service.ts` | 10/10 (100%) | 5/5 (100%) | 4/4 (100%) | 7/7 (100%) |
| `web/src/app/features/settings/security/revoke-device-dialog.ts` | 23/23 (100%) | 8/9 (88.88%) | 1/1 (100%) | 13/13 (100%) |
| `web/src/app/features/settings/settings.routes.ts` | 5/5 (100%) | N/A (0/0) | 4/4 (100%) | 5/5 (100%) |

Aggregates use summed counters:
- Server (2 files): instructions 259/259 (100%), branches 14/14 (100%), methods 15/15 (100%), lines 51/51 (100%).
- Web, hand-written (4 files): statements 127/127 (100%), branches 57/61 (93.44%), functions 32/32 (100%),
  lines 98/98 (100%).
- Not in these aggregates: the generated client (excluded as generated), `security-settings.html` (compiled into
  the component, no separate entry) and `security-settings.scss` (no executable behaviour).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `security-settings.ts:132` branch 1 (`this.codes() ?? []`) | Copy while no codes are shown | Justified gap: the Copy button exists only while `codes()` is non-null, so this branch cannot be reached |
| `security-settings.ts:146`, `:147` branch 1 (`devices()?.… ?? -1` / `?? null`) | Revoke finishing while the list is null | Justified gap: a revoke can start only from a rendered list. `load()` (which sets the list to null) can be reached only from the load-error view, where no rows exist |
| `revoke-device-dialog.ts:42` branch 0 | Source-mapped branch in the compiled inline template, with no behaviour of its own | Justified gap: Cancel, confirm and dismiss are all tested |
| `generated/fn/auth/regenerate-recovery-codes.ts:19` branch 1 | Generated request builder without a body | Excluded as generated. The service always sends a body |

## Unable to test

- Real-browser focus behaviour (`afterNextRender` focus moves, `cdkFocusInitial` with real layout) runs only in
  jsdom. The dialog-focus test overrides `InteractivityChecker.isVisible` because jsdom has no layout. No
  Playwright scenario names this task's criteria, so no browser run was made. This is a follow-up only if a
  human wants E2E evidence. The acceptance criteria require Vitest, not E2E.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. Two tests were added for the combined
pending-revoke and focus-move behaviour. The web check, the server auth tests with Testcontainers, and
`.taskcheck` all pass on the final fixed code.

Note for the parent: the new tests change `web/src/app/features/settings/security/security-settings.spec.ts`,
which is outside `reviews/`. Under the `docs/QUALITY.md` freshness rule (patch-id over the task diff excluding
`tasks/` and `reviews/`), the commit that holds this test becomes the `reviewed_revision`, and the code-reviewer's
evidence for `4cc1173` is stale for that change.
