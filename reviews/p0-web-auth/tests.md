# Test Report

Date: 2026-10-20

Task: p0-web-auth - Web setup and login pages (SPEC.md §4.1)

Scope: `babb06f4ff5c279f43422fb658dd7be5eb9bacfc..2a1248148a9ada920a70f31f49f9240b5011dc72` on `main`
(final reviewed revision `2a12481`). Task commits, given by the parent worker and matching the
task's acceptance criteria: `e0d4772` (implementation), `677d9ec` (tests, logout error handling),
`75edee3` (first test-writer pass), `2a12481` (fixer: CR-1 to CR-4). Production paths are under
`web/` only. This second pass also adds 2 uncommitted test changes, listed below.

Requirements:
- `tasks/p0-web-auth.md` acceptance criteria 1-4 and the recorded assumptions: Retry-After parsing,
  the 60 s fallback, logout in the account menu, no auto-login after setup, open-redirect
  protection, and the /me re-check on the login page.
- The fixed behavior described in `reviews/p0-web-auth/fixes.md` (CR-1 to CR-4).
- SPEC.md §4.1, docs/ARCHITECTURE.md "Coverage", AGENTS.md, docs/QUALITY.md.

## Test files changed

First pass, committed in `75edee3`:
- `web/src/app/core/http/api-error.spec.ts`
- `web/src/app/core/auth/auth.service.spec.ts`
- `web/src/app/core/auth/auth.guards.spec.ts`
- `web/src/app/features/auth/login/login.spec.ts`
- `web/src/app/features/auth/setup/setup.spec.ts`
- `web/src/app/features/auth/setup/qr-code.spec.ts` (new)
- `web/src/app/app.spec.ts`

Fixer, committed in `2a12481`:
- `auth.guards.spec.ts`
- `login.spec.ts`
- `setup.spec.ts`
- `shell/shell.spec.ts`

This pass, uncommitted:
- `web/src/app/core/auth/auth.service.spec.ts`
- `web/src/app/app.spec.ts`

## Tests added or updated

Count: 2 new tests in this pass. In total the task's tests now run 247 web tests: 37 test
definitions from the first pass, 6 from the fixer, and 2 here. No existing test was weakened,
skipped or removed.

This pass checked that the fixes in `2a12481` are tested:
- **CR-1, guards during an outage.** Covered by the fixer's 5 guard tests (502 from `/setup/status`
  or `/me` for `authGuard`, `loginGuard` and `setupGuard`) and the shell test for a rejected
  logout navigation. Two gaps remained, and I added a test for each:
  - `AuthService` "does not cache a failed setup status: shows a toast and asks again next time".
    The guards' recovery depends on this: a cached failure would block every later navigation.
    The test also checks that the outage toast is shown, which the fixer's comments rely on.
  - `App routes with auth guards` "shows the login form (not a blank page) while the server fails,
    and recovers afterwards". This uses the real `app.routes.ts`. While the server fails, `/inbox`
    renders the login form with `returnUrl=/inbox` and no shell. Once the server answers again,
    opening `/inbox` shows the shell.
- **CR-2, backoff live region.** Covered by the fixer's updated `Login` tests. They check the visible
  text "Too many failed attempts. Try again in N s.", and they check that `[role=alert]` contains
  only the static sentence while the countdown ticks. No gap.
- **CR-3, `messages.xlf`.** Catalog content; the build and `app.i18n.spec.ts` pass. No behavioral
  test needed.
- **CR-4, URL stubs restored.** A change to test code only; it has no production behavior to test.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: setup page reachable only while setup is needed | `setupGuard` tests, including "setupGuard shows /setup when the setup status is unknown"; `App routes with auth guards`: "sends every URL to the setup page while setup is needed", "closes the setup page once setup is done" | `cd web && npm run check` | Pass |
| AC1: username/password form | `Setup`: creates the account, 12 chars + matching passwords, username pattern, field errors, 409 conflict, double submit | `cd web && npm run check` | Pass |
| AC1: QR code of the otpauth URI | `Setup`: shows the QR code...; `QrCode` (5 tests) | `cd web && npm run check` | Pass |
| AC1: recovery codes with copy/download | `Setup`: copies, clipboard refuses, downloads as text file (URL stubs restored) | `cd web && npm run check` | Pass |
| AC1: TOTP confirmation | `Setup`: saved-codes checkbox, confirms then /login, wrong code + retry, non-6-digit, double submit; `AuthService`: confirm marks setup done / a failure keeps it | `cd web && npm run check` | Pass |
| AC2: password + TOTP | `Login`: logs in with password and TOTP, incomplete form, TOTP not 6 digits, retry after 401 | `cd web && npm run check` | Pass |
| AC2: 'use a recovery code' switch | `Login`: switches to a recovery code, switches back, empty recovery code; `AuthService`: recovery code body | `cd web && npm run check` | Pass |
| AC2: remember device | `Login`: the recovery-code test sends `rememberDevice: true`; the TOTP tests send the default `false` | `cd web && npm run check` | Pass |
| AC2: backoff wait time on 429 | `Login`: backoff countdown (visible text plus a static live region), 60 s fallback, submit ignored while blocked, timer cleared on destroy; `ApiError retryAfter` (13 cases); `AuthService` 429 retryAfter | `cd web && npm run check` | Pass |
| AC3: auth guard on all shell routes | `authGuard` tests, including 2 outage tests; `App routes`: "guards the shell route %s" (20 routes), plus the new outage-and-recovery flow | `cd web && npm run check` | Pass |
| AC3: logout in the top bar menu | `Shell`: signs out from the account menu; shows login even when sign-out fails; no unhandled rejection when the navigation fails; `AuthService` logout tests | `cd web && npm run check` | Pass |
| AC3: `/login` redirects to the return URL after success | `Login` return URL tests; `loginGuard` tests, including 2 outage tests; `safeReturnUrl` table; `App routes`: "signs in on /login and returns to the URL that needed the session" | `cd web && npm run check` | Pass |
| AC4: Vitest tests cover forms and guard redirects; `npm run check` passes | all of the above | `cd web && npm run check` | Pass: api:check, lint, tests and `ng build` all completed (the steps are chained with `&&`, and the build finished). 18 files / 247 tests (count from the coverage run of the same snapshot). |

Not covered by this report: running the pages in a browser against a real server. The criteria do
not ask for it, and no Playwright run was done.

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include src/app/core/auth --include src/app/app.spec.ts --include src/app/shell/shell.spec.ts --include src/app/features/auth`

Result:

Pass: 7 files, 151 tests.

The first attempt of the new route test failed because of a mistake in the test: it navigated to
the URL the router was already on, and the router ignores such a navigation. I corrected the test,
not production code.

## Full test execution

Command:

`cd web && npm run check` (api:check, `ng lint --max-warnings=0`, `ng test --watch=false`, `ng build`)

Result:

Pass. The build finished ("Application bundle generation complete"), and it runs only after
api:check, lint and the tests succeed. `npx prettier --check` passes on both changed spec files.
Prettier was not run over `src/app/api/generated`.

## .taskcheck

Result:

Pass. `sh scripts/check.sh` ran from 2026-10-20T13:52:02+02:00 to 13:52:28+02:00 and ended with
"== all checks passed". It covers the server check, the web check, and android ktlint, unit tests
and lint.

## Bugs found

None. All tests pass against `2a12481`. The fixed guard, logout and backoff behavior matches
`fixes.md`.

## Coverage

Status: verified. This is a fresh run with exit status 0, and every scoped TypeScript file and
template was measured. `core/auth/index.ts` has no executable statements (it only re-exports) and
is not in the report.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (the existing tooling has no changed-line report).

Evidence:
- **Task and scope.** Task `p0-web-auth`; base `babb06f4ff5c279f43422fb658dd7be5eb9bacfc`; measured
  HEAD `2a1248148a9ada920a70f31f49f9240b5011dc72`. The task commits `e0d4772`, `677d9ec`, `75edee3`
  and `2a12481` came from the parent worker. The file scope is
  `git diff --name-only babb06f4..HEAD -- web/src` without `*.spec.ts`, `.scss` and
  `locale/messages.xlf` (translation catalog, not executable).
- **Snapshot.** HEAD `2a12481` with no production changes. Uncommitted test files (sha256):
  - `web/src/app/app.spec.ts`: `81ca4d4cae2e6ad1c2cc096596d939b2451143056bd36887ea0855bdc630d049`
  - `web/src/app/core/auth/auth.service.spec.ts`:
    `98b76090ce562581870d812328fe342f44e980a148162fc83d9244c9deb58498`

  The hashes were identical before and after the coverage run. The only other uncommitted change
  in the working tree is `reviews/p0-web-auth/review.md`, which belongs to the reviewer and is not
  a test input.
- **Included files** (all under `web/src/app/`):
  - `app.routes.ts`
  - `core/auth/auth.guards.ts`
  - `core/auth/auth.service.ts`
  - `core/auth/testing.ts` (a test helper, counted as task code)
  - `core/http/api-error.ts`
  - `features/auth/auth-layout.ts`
  - `features/auth/login/login.ts`
  - `features/auth/login/login.html`
  - `features/auth/setup/qr-code.ts`
  - `features/auth/setup/setup.ts`
  - `features/auth/setup/setup.html`
  - `shell/shell.ts`
  - `shell/shell.html`
- **Excluded files:**
  - `core/auth/index.ts`: re-export barrel with no executable code; not in the report.
  - `auth.scss`, `setup.scss`: styles.
  - `locale/messages.xlf`: i18n catalog.
  - `angular.json`, `package.json`, `package-lock.json`, `docs/ARCHITECTURE.md`: config and docs.
  - Spec files.

  No renames or deletions.
- **Provider and command.** Vitest 5.0.5 with v8 coverage, run through `ng test`. Command:
  `cd web && npm run coverage`, in `web/`, over the full web suite (18 files, 247 tests).
- **Run time and exit status.** 2026-10-20T13:51:43+02:00 to 13:51:54+02:00; exit 0; all tests
  passed.
- **Reports.**
  - `web/coverage/web/coverage-final.json`: sha256
    `4975c054185e0cf3171a3b2bc9a7f1b9a6e174c37f37c08b4f70708e047790d7`, mtime 13:51:52.18.
  - `web/coverage/web/coverage-summary.json`: sha256
    `09d4a5125de6c694116469a8c850af49a4f72a0f11da8a7002da715cf4b70a12`, mtime 13:51:52.18.
  - Also `lcov.info` and `lcov-report/`.
- **Freshness.** The reports are fresh: both mtimes fall inside the run window and both hashes
  differ from the first pass. The inputs did not change during the run.
- **Final run.** Yes: this run came after the fixer's production changes in `2a12481` and after the
  last test addition.

Values below use covered/total (percentage); zero-denominator metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/app.routes.ts` | 18/18 (100%) | N/A (0/0) | 17/17 (100%) | 18/18 (100%) |
| `web/src/app/core/auth/auth.guards.ts` | 31/31 (100%) | 21/21 (100%) | 4/4 (100%) | 28/28 (100%) |
| `web/src/app/core/auth/auth.service.ts` | 32/32 (100%) | 18/18 (100%) | 10/10 (100%) | 26/26 (100%) |
| `web/src/app/core/auth/testing.ts` | 6/6 (100%) | 1/1 (100%) | 4/4 (100%) | 6/6 (100%) |
| `web/src/app/core/http/api-error.ts` | 37/37 (100%) | 39/39 (100%) | 7/7 (100%) | 33/33 (100%) |
| `web/src/app/features/auth/auth-layout.ts` | 11/11 (100%) | 8/9 (88.88%) | 1/1 (100%) | 5/5 (100%) |
| `web/src/app/features/auth/login/login.ts` | 61/62 (98.38%) | 29/31 (93.54%) | 10/10 (100%) | 52/52 (100%) |
| `web/src/app/features/auth/login/login.html` | 76/76 (100%) | 10/10 (100%) | 2/2 (100%) | 39/39 (100%) |
| `web/src/app/features/auth/setup/qr-code.ts` | 27/27 (100%) | 12/12 (100%) | 4/4 (100%) | 19/19 (100%) |
| `web/src/app/features/auth/setup/setup.ts` | 67/68 (98.52%) | 31/34 (91.17%) | 12/12 (100%) | 58/58 (100%) |
| `web/src/app/features/auth/setup/setup.html` | 149/149 (100%) | 18/18 (100%) | 4/4 (100%) | 90/90 (100%) |
| `web/src/app/shell/shell.ts` | 45/45 (100%) | 22/22 (100%) | 13/13 (100%) | 36/36 (100%) |
| `web/src/app/shell/shell.html` | 89/90 (98.88%) | 6/6 (100%) | 6/7 (85.71%) | 64/64 (100%) |
| **Aggregate (summed counters, 13 files above)** | 649/652 (99.54%) | 215/221 (97.29%) | 94/95 (98.95%) | 474/474 (100%) |

The aggregate excludes `core/auth/index.ts`, which has no executable code and is not in the report.
No scoped file is missing instrumentation. The task defines no coverage threshold:
ARCHITECTURE.md says coverage is not a gate. The fixer's changes in `auth.guards.ts` (the outage
`catch` blocks), in `shell.ts` (`.catch(() => false)`) and in `login.html` (the split backoff
notice) are fully covered.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `login.ts:107` branch (`!(error instanceof ApiError)` -> rethrow) | An unexpected non-API error is not swallowed | Justified gap: a defensive rethrow. Triggering it from a template submit causes an unhandled rejection in the test runner. Low risk. |
| `setup.ts:140` branch (the same rethrow in `run`) | Same as above | Justified gap, for the same reason. |
| `setup.ts:130` branch (`enrollment()?.recoveryCodes ?? []`) | Copy/download before enrollment | Not reachable: the buttons render only when an enrollment exists. |
| `login.ts:40`, `setup.ts:51`, `auth-layout.ts:42` branches (class declaration lines) | None: an Angular compiler-generated factory branch | Not material. |
| `shell.html:9` brand link click listener | Pre-existing shell behavior, not changed by this task | Outside the task's responsibility. |

## Unable to test

- **The QR code in a real browser, scanned with an authenticator app.** This needs a device.
  Instead, the test checks that the SVG matches the `qrcode` library's symbol for the URI.
  Follow-up only if an E2E task covers setup.
- **End-to-end setup and login against the real server**, with cookies, the server's real
  Retry-After, and a real outage instead of a mocked 502. The criteria do not ask for this, and it
  was not run here (no Playwright run).
- **Screen reader announcements of the backoff notice.** The test checks the DOM structure: only
  the static sentence is inside `role="alert"`. It cannot check what an actual screen reader says.

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient; no production
defects found.
