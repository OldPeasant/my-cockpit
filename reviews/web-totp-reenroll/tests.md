# Test Report

Date: 2026-10-22

Task: web-totp-reenroll - Web forced TOTP re-enrollment after restore

Scope: base d566559d47b65a84b1419e68a26bd3c5ef2bfd93 .. HEAD d77fd81488f22f04a4ae5c12282ff53ef069d25f
(task commits 154242f2, 13e9efea, d77fd814, given by the parent worker and matching `git log d566559d..HEAD`),
plus the uncommitted test changes of this report (listed below). Branch: main. Web only.

Requirements:
- tasks/web-totp-reenroll.md acceptance criteria and its recorded assumptions (password-only login as a
  third mode reached from the recovery-code view; enrollment page at /enroll-totp, auto-start, keeps the
  return URL, offers Sign out)
- SPEC.md 4.1 / 10.2 (via the task text), docs/ARCHITECTURE.md "Coverage", docs/QUALITY.md
- Server contract for reference: server/.../core/auth/api/TotpEnrollmentController.kt and
  domain/TotpEnrollmentService.kt (start/confirm 409 when not required, confirm 400 field error,
  429 with Retry-After, 401 without session)

## Test files changed

- `web/src/app/features/auth/totp-enrollment/totp-enrollment.spec.ts`
- `web/src/app/features/auth/login/login.spec.ts`
- `web/src/app/core/auth/auth.service.spec.ts`
- `web/src/app/core/auth/auth.guards.spec.ts`
- `web/src/app/app.spec.ts`

## Tests added or updated

Count: 32 new test cases (12 component, 5 login, 6 service, 6 guard, 3 app-route integration) plus
3 new safeReturnUrl table rows on top of the parent's existing task specs. The `App routes with auth guards`
fake in app.spec.ts was extended (state flag `enrollmentRequired`, `startTotpEnrollment`,
`confirmTotpEnrollment`); no existing assertion was changed or removed.

Added cases:
- TotpEnrollment: sign-out still navigates when logout fails; sign-out offered after a failed start (409);
  loading state while the key is created; double "Try again" click starts once; retry failing again shows
  the new error with retry enabled; double submit sends one confirmation (button disabled); space around
  the code is removed; empty code not sent; 429 without Retry-After ("Try again later."); confirm 409 shows
  the server detail and stays on the page; several field errors joined; a new code after a wrong one succeeds.
- Login: unsafe return URL dropped on the way to /enroll-totp; password-only login without enrollment opens
  the return URL; password-only without password not sent; password-only 429 starts the backoff;
  password-only non-401 error shows the server message (not the recovery-code hint).
- AuthService: confirm 429 carries retryAfter, no toast, flag kept; confirm 409 keeps the flag, no toast;
  confirm 401 does not redirect; confirm without a cached session stays logged out; logout clears the flag;
  refreshed /me clears the flag.
- Guards: loginGuard to /enroll-totp without return URL and with an unsafe one; totpEnrollmentGuard sends a
  normal session to / for an unsafe or self-referencing return URL, sends no-session users to /login without
  a return URL, sends to /login when the setup status fails; safeReturnUrl rows `/setup?step=2`,
  `/enroll-totp#top`, `/enroll-totp-help`.
- App routes (real `routes`): login with enrollment required goes through /enroll-totp (title, QR/key,
  confirm) back to /trash; a session needing enrollment is kept out of shell routes; a normal session is
  sent away from /enroll-totp without starting an enrollment.

## Requirement coverage

Focused command (F): `cd web && npx ng test --watch=false --include='src/app/core/auth/*.spec.ts' --include='src/app/features/auth/**/*.spec.ts'`
plus `npx ng test --watch=false --include='src/app/app.spec.ts'`. Full command: `cd web && npm run check`.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: login with password + recovery code | Login "switches to a recovery code and sends it...", "opens the TOTP enrollment instead of the app when the server requires it" | F | Pass |
| AC1: password only (third mode) | Login "logs in with the password alone...", "explains a refused password-only login...", "opens the return URL after a password-only login...", "does not send a password-only login without a password", "starts the backoff when a password-only login is throttled", "shows the server message on other errors of a password-only login" | F | Pass |
| AC1: totpEnrollmentRequired routes to enrollment instead of the app | Login enrollment tests (with/without/unsafe return URL); guards "forced TOTP re-enrollment" block; App routes "leads a login that needs TOTP enrollment...", "keeps a session that needs TOTP enrollment out of every shell route"; AuthService "knows from the login..." | F + app.spec | Pass |
| AC2: starts enrollment, shows QR code + secret | TotpEnrollment "starts the enrollment and shows the QR code and the key", loading state, retry tests, start 409; AuthService "starts the TOTP re-enrollment..."; App routes integration | F + app.spec | Pass |
| AC2: confirms with a TOTP code, then opens the app | TotpEnrollment "confirms with the code, then opens the return URL", double submit, unsafe return URL, retry after wrong code; AuthService "clears the enrollment flag once the new TOTP is confirmed"; App routes integration (lands on /trash) | F + app.spec | Pass |
| AC2: wrong code shows the server's validation error | TotpEnrollment "shows the server's validation error for a wrong code and clears it", "joins several server validation messages", 409, 429 with/without Retry-After; AuthService 400/409/429/401 confirm tests | F | Pass |
| AC3: `npm run check` passes with unit tests for the new flow | whole web suite, lint, api:check, e2e:typecheck, build | `cd web && npm run check` | Pass (exit 0, 134 files, 2740 tests) |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/core/auth/*.spec.ts' --include='src/app/features/auth/**/*.spec.ts'`

Result:

Pass: 7 files, 145 tests. `npx ng test --watch=false --include='src/app/app.spec.ts'`: Pass, 45 tests.
(One new test initially failed because it typed 8 characters into a field with `maxlength="7"`, which
Angular also enforces as a validator; the test was wrong and was changed to a realistic 7-character input.)

## Full test execution

Command:

`cd web && npm run check`

Result:

Pass (exit 0): api:check, `ng lint --max-warnings=0` ("All files pass linting."), e2e:typecheck,
test:ci 134 files / 2740 tests passed, `ng build` complete.

## .taskcheck

Result:

Not run: `.taskcheck` (`sh scripts/check.sh`) also runs the server and Android parts, which this
web-only task does not touch; its web part is `npm run check`, run above. The parent worker runs
`.taskcheck` before `done`.

## Bugs found

None. No test failed against production code.

Observations for the parent (not acceptance-criteria violations, no failing test, no fix required for
this task's criteria):
- TW-O1 (Low): when another session's start replaced the pending secret, confirm answers 400 "is not a
  valid code for the new TOTP secret" (server docs: the session "must start again"); the page offers no
  "create a new key" action while an enrollment is shown, so the user has to reload the page.
  Source: web/src/app/features/auth/totp-enrollment/totp-enrollment.html (retry only in the startError branch).
- TW-O2 (Low): a 409 on start (re-enrollment no longer required) shows the server detail and a "Try again"
  button that repeats the 409, instead of leading to the app; Sign out works (tested).
- TW-O3 (Low): a 401 on confirm (session ended, e.g. another session confirmed first) shows the error
  message but does not lead to the login page, because the call uses SKIP_LOGIN_REDIRECT; Sign out works.

## Coverage

Status: verified (fresh run, exit 0, after the latest test additions; no production code changed).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-totp-reenroll;
  base d566559d47b65a84b1419e68a26bd3c5ef2bfd93; HEAD d77fd81488f22f04a4ae5c12282ff53ef069d25f; commits
  154242f2, 13e9efea, d77fd814 from the parent worker, checked with `git diff --stat d566559d..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): production files unchanged at HEAD
  (sha256 prefixes auth.service.ts 5bbbef27a9c1, auth.guards.ts f0b5b6979b95, login.ts b53ea029931a,
  totp-enrollment.ts f484e7139c44, app.routes.ts 72a3b5cce503). Uncommitted test files (sha256 prefixes,
  identical before and after the run): app.spec.ts 5551507d31f2, auth.guards.spec.ts 19ad19eafc38,
  auth.service.spec.ts 4d1ca6e844be, login.spec.ts ac0f521c19d3, totp-enrollment.spec.ts d775f88ca7d8.
- Exact included files; renamed/deleted/excluded files and reasons: included web/src/app/core/auth/auth.service.ts,
  web/src/app/core/auth/auth.guards.ts, web/src/app/features/auth/login/login.ts,
  web/src/app/features/auth/totp-enrollment/totp-enrollment.ts, web/src/app/app.routes.ts.
  Excluded: web/src/app/core/auth/index.ts (re-exports only, no executable code; absent from the v8
  report), web/src/app/core/auth/testing.ts and all *.spec.ts (test code), web/src/app/features/auth/auth.scss
  (styles). login.html and totp-enrollment.html are templates compiled into their components; v8 reports
  no separate counters for them, so they are covered only indirectly through the component tests (DOM
  assertions). No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + @vitest/coverage-v8 5.0.5;
  `npm run coverage` in web/; whole web suite (134 files, 2740 tests).
- Run start/completion time and exit status: 2026-10-22T02:47:56+02:00 to 02:48:48+02:00, exit 0.
- Generated report paths and report hashes/run identifier: web/coverage/web/coverage-summary.json
  (sha256 ab1fada00923d7615fb89a37486262a2a9ff31915ec12fccb9ce30f302fa904b), web/coverage/web/coverage-final.json
  (sha256 37b634a50e4450cfd1543df6fe3254319c8618f7abbe41102016538a9ad6e70b), both written 02:48:48.
- Evidence outputs are fresh and inputs stayed unchanged: yes (report mtimes at run end; test and
  production hashes identical before and after).
- Final run after latest test additions and production fixes: yes (no production fixes were made).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/core/auth/auth.service.ts` | 39/39 (100%) | 20/20 (100%) | 13/13 (100%) | 31/31 (100%) |
| `web/src/app/core/auth/auth.guards.ts` | 47/47 (100%) | 35/35 (100%) | 6/6 (100%) | 43/43 (100%) |
| `web/src/app/features/auth/login/login.ts` | 69/70 (98.57%) | 40/42 (95.23%) | 12/12 (100%) | 58/58 (100%) |
| `web/src/app/features/auth/totp-enrollment/totp-enrollment.ts` | 55/57 (96.49%) | 24/27 (88.88%) | 7/7 (100%) | 47/47 (100%) |
| `web/src/app/app.routes.ts` | 19/19 (100%) | N/A (0/0) | 18/18 (100%) | 19/19 (100%) |
| Aggregate (summed counters, 5 files; index.ts excluded as above) | 229/232 (98.71%) | 119/124 (95.97%) | 56/56 (100%) | 198/198 (100%) |

Before the app.spec.ts additions, app.routes.ts had 18/19 statements and 17/18 functions: the
`loadComponent` of the new /enroll-totp route was never executed. The new App-routes tests cover it.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `web/src/app/features/auth/login/login.ts:131` (statement + if branch) | rethrow of a non-ApiError in showError (pre-existing code) | Not tested: defensive programming-error path, outside this task's change |
| `web/src/app/features/auth/totp-enrollment/totp-enrollment.ts:64, 89` (statements + if branches) | rethrow of a non-ApiError from start/confirm | Not tested: defensive path; the rethrow ends as an unhandled rejection in the global error handler, no user-visible behavior to assert |
| `web/src/app/features/auth/login/login.ts:50`, `totp-enrollment.ts:32` (if branch #0) | class declaration line; compiler-generated branch (decorator/field init), no source behavior | Non-material, no test possible from source |

## Unable to test

- Real browser flow against the real server (restore without secrets, then login and enrollment end to end):
  not part of the acceptance criteria (AC3 names unit tests and `npm run check`); no Playwright test added.
- Actual QR code scannability by an authenticator app: only the rendered QR SVG presence is asserted.
- Template-only coverage counters (login.html, totp-enrollment.html): not reported separately by v8.

## Summary

Tests added and all validation passed. No production defects found; three low-severity UX observations
(TW-O1 to TW-O3) are listed for the parent to decide on.
