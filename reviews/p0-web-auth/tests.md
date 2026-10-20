# Test Report

Date: 2026-10-20

Task: p0-web-auth - Web setup and login pages (SPEC.md §4.1)

Scope: `babb06f4ff5c279f43422fb658dd7be5eb9bacfc..677d9ec6cba8084824f381e11dab6ba43cf27e3c` on `main`
(task commits `e0d4772`, `677d9ec`, given by the parent worker and matching the task's acceptance
criteria), production paths under `web/` only, plus the uncommitted test files listed below.

Requirements:
- `tasks/p0-web-auth.md` acceptance criteria 1-4 and the recorded assumptions (Retry-After parsing,
  60 s fallback, logout in the account menu, no auto-login after setup, open-redirect protection,
  /me re-check on the login page)
- SPEC.md §4.1, docs/ARCHITECTURE.md "Coverage", AGENTS.md, docs/QUALITY.md

## Test files changed

- `web/src/app/core/http/api-error.spec.ts`
- `web/src/app/core/auth/auth.service.spec.ts`
- `web/src/app/core/auth/auth.guards.spec.ts`
- `web/src/app/features/auth/login/login.spec.ts`
- `web/src/app/features/auth/setup/setup.spec.ts`
- `web/src/app/features/auth/setup/qr-code.spec.ts` (new)
- `web/src/app/app.spec.ts`

## Tests added or updated

Count: 37 new test definitions (about 67 executed cases including `it.each` rows); no existing test
was changed, weakened or skipped.

- `api-error.spec.ts` / `retryAfter (Retry-After header)`: delay-seconds with and without a problem
  body; `0`, padded ` 15 `, `900`; undefined for no header, empty, blank, HTTP-date, negative,
  decimal, text; undefined when constructed directly.
- `qr-code.spec.ts`: accessible `role=img` + label; square viewBox with 2-module quiet zone and only
  unit squares inside it; drawn squares equal the dark modules of the symbol; finder patterns in the
  three expected corners (orientation); redraw on value change.
- `app.spec.ts` / `App routes with auth guards` (real `app.routes.ts`, fake AuthService): every URL
  goes to `/setup` while setup is needed (page title "Set up My Cockpit"); `/setup` closes to
  `/login` after setup; every shell route (`/`, all NAV_SECTIONS, unknown URL) without a session ends
  on `/login?returnUrl=...` with no shell rendered; full flow `/trash` -> login form -> back on
  `/trash`; logged-in user on `/login?returnUrl=/notes` goes to `/notes`.
- `login.spec.ts`: 429 without Retry-After counts down from 60 s; a submit (Enter) during the backoff
  is ignored and accepted after it; the countdown interval is cleared on destroy; a double submit
  sends one request (button disabled while pending); switching back from recovery code to TOTP;
  an empty recovery code is not sent; retry after 401.
- `setup.spec.ts`: field errors of a 400 are shown joined; a 400 without field errors before
  enrollment shows the server message (not the "code not valid" text); double submit of account
  creation and of the TOTP confirmation each call the server once; "Copy codes" stays when the
  clipboard refuses; a non-6-digit confirmation code is rejected.
- `auth.service.spec.ts`: non-401 `/me` failure is rethrown and the next call asks again; login 401
  causes neither toast nor login redirect; recovery code + rememberDevice body; failed logout shows a
  toast and rethrows; startSetup posts to `/api/v1/setup`, returns the enrollment, 409 has no toast;
  a failed TOTP confirmation keeps setup required.
- `auth.guards.spec.ts`: logged-in user without return URL -> `/`; return URL pointing to `/login`
  does not loop; `safeReturnUrl` rows for `undefined`, relative `inbox`, `/`, a path with fragment.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Setup page only reachable while setup is needed | `setupGuard` tests; `App routes with auth guards`: "sends every URL to the setup page while setup is needed", "closes the setup page once setup is done" | `npm --prefix web run check` | Pass |
| AC1 username/password form | `Setup`: creates the account, 12 chars + matching passwords, username pattern, field errors, 409 conflict, double submit | `npm --prefix web run check` | Pass |
| AC1 QR code of the otpauth URI | `Setup`: shows the QR code...; `QrCode` (5 tests) | `npm --prefix web run check` | Pass |
| AC1 recovery codes with copy/download | `Setup`: copies, clipboard refuses, downloads as text file | `npm --prefix web run check` | Pass |
| AC1 TOTP confirmation | `Setup`: saved-codes checkbox, confirms then /login, wrong code + retry, non-6-digit, double submit; `AuthService`: confirm marks setup done / failure keeps it | `npm --prefix web run check` | Pass |
| AC2 password + TOTP | `Login`: logs in with password and TOTP, incomplete form, TOTP not 6 digits, retry after 401 | `npm --prefix web run check` | Pass |
| AC2 'use a recovery code' switch | `Login`: switches to a recovery code, switches back, empty recovery code; `AuthService` recovery code body | `npm --prefix web run check` | Pass |
| AC2 remember device | `Login`: recovery-code test sends `rememberDevice: true`; default `false` in TOTP tests | `npm --prefix web run check` | Pass |
| AC2 backoff wait time on 429 | `Login`: backoff countdown, 60 s fallback, submit ignored while blocked, timer cleared on destroy; `ApiError retryAfter` (13 cases); `AuthService` 429 retryAfter | `npm --prefix web run check` | Pass |
| AC3 auth guard on all shell routes | `authGuard` tests; `App routes with auth guards`: "guards the shell route %s" (20 routes) | `npm --prefix web run check` | Pass |
| AC3 logout in the top bar menu | `Shell`: signs out from the account menu; shows login even when sign-out fails; `AuthService` logout tests | `npm --prefix web run check` | Pass |
| AC3 `/login` redirects to the return URL after success | `Login` return URL tests; `loginGuard` tests; `safeReturnUrl` table; `App routes`: "signs in on /login and returns to the URL that needed the session" | `npm --prefix web run check` | Pass |
| AC4 Vitest tests cover forms and guard redirects; `npm run check` passes | all of the above | `npm --prefix web run check` (equivalent to `cd web && npm run check`) | Pass: exit 0, lint clean, 18 files / 239 tests, build OK |

Not covered by this report: browser/E2E execution of the pages against a real server (not named by
the criteria; no Playwright run was done).

## Focused test execution

Command:

`npm --prefix web exec -- ng test --watch=false --include src/app/core/auth --include src/app/core/http/api-error.spec.ts --include src/app/features/auth --include src/app/app.spec.ts`

Result:

Pass (7 files, 158 tests; afterwards `--include src/app/features/auth`: 3 files, 36 tests, Pass)

## Full test execution

Command:

`npm --prefix web run check` (api:check, `ng lint --max-warnings=0`, `ng test --watch=false`, `ng build`)

Result:

Pass: exit 0, "All files pass linting.", 18 test files / 239 tests passed, build complete.

## .taskcheck

Result:

Pass: `sh scripts/check.sh` (the `.taskcheck` command) exit 0 at 2026-10-20 13:43:01-13:44:39
+02:00 (tfp gate tests, server check, web check, android ktlint/unit tests/lint, "all checks passed").

## Bugs found

None. No production defect was found; all new tests pass against the implementation at `677d9ec`.

## Coverage

Status: verified (fresh run, exit 0, all scoped TypeScript files and templates measured; `index.ts` is
a pure re-export barrel without executable statements and is absent from the report).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (the existing tooling has no changed-line report).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `p0-web-auth`;
  base `babb06f4ff5c279f43422fb658dd7be5eb9bacfc`; HEAD `677d9ec6cba8084824f381e11dab6ba43cf27e3c`;
  task commits `e0d4772`, `677d9ec` from the parent worker; scope from
  `git diff --name-only babb06f4..HEAD -- web/src` minus `*.spec.ts` and `.scss`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `677d9ec` with no production
  changes; uncommitted test files (sha256): `app.spec.ts` 013b9795...cb5f,
  `auth.guards.spec.ts` 4072b323...1bb8, `auth.service.spec.ts` 56dc01d3...7c7b,
  `api-error.spec.ts` 70faf8af...a421, `login.spec.ts` c594fc1f...ac27,
  `setup.spec.ts` ba788a15...35ab, `qr-code.spec.ts` (new) 5851cc84...346e. Hashes identical before
  and after the final coverage run.
- Exact included files: `web/src/app/app.routes.ts`, `core/auth/auth.guards.ts`,
  `core/auth/auth.service.ts`, `core/auth/testing.ts` (test helper, counted as task code),
  `core/http/api-error.ts`, `features/auth/auth-layout.ts`, `features/auth/login/login.ts`,
  `features/auth/login/login.html`, `features/auth/setup/qr-code.ts`, `features/auth/setup/setup.ts`,
  `features/auth/setup/setup.html`, `shell/shell.ts`, `shell/shell.html` (all under `web/src/app/`).
  Excluded: `core/auth/index.ts` (re-export barrel, no executable code, not in report);
  `auth.scss`, `setup.scss` (styles); `angular.json`, `package.json`, `package-lock.json`,
  `docs/ARCHITECTURE.md` (config/docs); spec files. No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 +
  `@vitest/coverage-v8` 5.0.5 via `ng test`; `npm --prefix web run coverage` (runs in `web/`); full
  web suite (18 files, 239 tests).
- Run start/completion time and exit status: 2026-10-20T13:42:36+02:00 to 13:42:46+02:00, exit 0.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json`
  (sha256 e43a0285a67f6e5710eb6c08f473afff50ab70336ed01c1b8cd504c59858f720, mtime 13:42:46.73),
  `web/coverage/web/coverage-summary.json` (sha256
  d6630989579e9645eeabd2e93ac8263d4db116e962575dc5b6839307d74e9b02), `lcov.info`, `lcov-report/`.
- Evidence outputs are fresh and inputs stayed unchanged: yes (report rewritten by this run, mtime
  inside the run window; test file hashes unchanged; no production edits).
- Final run after latest test additions and production fixes: yes (after the last test addition; no
  production fixes were made).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/app.routes.ts` | 18/18 (100%) | N/A (0/0) | 17/17 (100%) | 18/18 (100%) |
| `web/src/app/core/auth/auth.guards.ts` | 26/26 (100%) | 21/21 (100%) | 4/4 (100%) | 23/23 (100%) |
| `web/src/app/core/auth/auth.service.ts` | 32/32 (100%) | 18/18 (100%) | 10/10 (100%) | 26/26 (100%) |
| `web/src/app/core/auth/testing.ts` | 6/6 (100%) | 1/1 (100%) | 4/4 (100%) | 6/6 (100%) |
| `web/src/app/core/http/api-error.ts` | 37/37 (100%) | 39/39 (100%) | 7/7 (100%) | 33/33 (100%) |
| `web/src/app/features/auth/auth-layout.ts` | 11/11 (100%) | 8/9 (88.88%) | 1/1 (100%) | 5/5 (100%) |
| `web/src/app/features/auth/login/login.ts` | 61/62 (98.38%) | 29/31 (93.54%) | 10/10 (100%) | 52/52 (100%) |
| `web/src/app/features/auth/login/login.html` | 71/71 (100%) | 10/10 (100%) | 2/2 (100%) | 39/39 (100%) |
| `web/src/app/features/auth/setup/qr-code.ts` | 27/27 (100%) | 12/12 (100%) | 4/4 (100%) | 19/19 (100%) |
| `web/src/app/features/auth/setup/setup.ts` | 67/68 (98.52%) | 31/34 (91.17%) | 12/12 (100%) | 58/58 (100%) |
| `web/src/app/features/auth/setup/setup.html` | 149/149 (100%) | 18/18 (100%) | 4/4 (100%) | 90/90 (100%) |
| `web/src/app/shell/shell.ts` | 44/44 (100%) | 22/22 (100%) | 12/12 (100%) | 36/36 (100%) |
| `web/src/app/shell/shell.html` | 89/90 (98.88%) | 6/6 (100%) | 6/7 (85.71%) | 64/64 (100%) |
| **Aggregate (summed counters, 13 files above)** | 638/641 (99.53%) | 215/221 (97.29%) | 93/94 (98.94%) | 469/469 (100%) |

Aggregate excludes `core/auth/index.ts` (no executable code, not in the report). No scoped file is
missing instrumentation. No task-level threshold is defined (ARCHITECTURE.md: coverage is not a gate).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `login.ts:107` branch (`!(error instanceof ApiError)` -> rethrow) | Unexpected non-API error is not swallowed | Justified gap: defensive rethrow; triggering it from a template submit produces an unhandled rejection in the test runner. Low risk. |
| `setup.ts:140` branch (same rethrow in `run`) | Same as above | Justified gap, same reason. |
| `setup.ts:130` branch (`enrollment()?.recoveryCodes ?? []`) | Copy/download before enrollment | Not reachable: the buttons render only when an enrollment exists. |
| `login.ts:40`, `setup.ts:51`, `auth-layout.ts:42` branches (class declaration lines) | None: Angular compiler-generated factory branch | Not material. |
| `shell.html:9` brand link click listener | Pre-existing shell behavior, not changed by this task | Outside task responsibility. |
| `setup.ts:100` (`confirm` double submit) | Was uncovered in the first run | Covered: "confirms the code only once when submitted twice quickly". |

## Unable to test

- Real browser rendering of the QR code and scanning it with an authenticator app: needs a device;
  the test checks the SVG matches the `qrcode` library's symbol for the URI instead. Follow-up only
  if an E2E task covers setup.
- End-to-end setup/login against the real server (cookies, actual Retry-After from the server's
  backoff): not named by the criteria and not run here (no Playwright run).

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient; no production
defects found.
