# Test Report

Date: 2026-10-22

Task: web-totp-reenroll - Web forced TOTP re-enrollment after restore

Scope: base 98d1e4a985b6761a52738793f8990b8bfac4548c .. HEAD 3008661a240c700e1010f413a4b4b1cbebe4b1ee
(task commits a87dd44d, b58c240a, b7a3845f, 5842f710, 3008661a, given by the parent worker and matching
`git log 98d1e4a9..HEAD`), plus the uncommitted test additions of this report in
`web/src/app/features/auth/totp-enrollment/totp-enrollment.spec.ts`. Branch: main. Web only.
This report replaces the earlier one (written for the pre-fix revision d77fd814 on base d566559d, before
the rebase); it covers the final code including the CR-1/CR-2 fixes of 3008661a.

Requirements:
- tasks/web-totp-reenroll.md acceptance criteria and its recorded assumptions (password-only login as a
  third mode reached from the recovery-code view; enrollment page at /enroll-totp, auto-start, keeps the
  return URL, offers Sign out)
- reviews/web-totp-reenroll/review.md (CR-1, CR-2) and fixes.md (fixed behavior: 401 leads to login with the
  safe return URL, 409 refreshes the session and opens the return URL, "Create a new key" button,
  replaced-key hint after a 400, no confirm while a new key is created)
- SPEC.md 4.1 / 10.2 (via the task text), docs/ARCHITECTURE.md "Coverage", docs/QUALITY.md

## Test files changed

- `web/src/app/features/auth/totp-enrollment/totp-enrollment.spec.ts` (this run; uncommitted)

Task test files already committed and re-run against the final code: `web/src/app/core/auth/auth.service.spec.ts`,
`web/src/app/core/auth/auth.guards.spec.ts`, `web/src/app/features/auth/login/login.spec.ts`, `web/src/app/app.spec.ts`,
`web/src/app/features/auth/totp-enrollment/totp-enrollment.spec.ts`.

## Tests added or updated

Count: 8 new test cases in this run (TotpEnrollment component), on top of the 30 component cases and the
login/service/guard/app-route cases already committed in the task. No existing assertion was changed or removed.

Added in this run (gaps in the CR-1/CR-2 fix code):
- "creates a new key only once when \"Create a new key\" is clicked twice quickly" (in-flight guard of `start()`)
- "clears a typed code when a new key is created" (form reset; a code of the old key is not sent)
- "hides the old key and offers to try again when creating a new key fails" (old QR/key removed, error with
  "Try again", retry shows the new key)
- "removes the replaced-key hint when the next attempt is refused for another reason" (400 then 429)
- "goes to the login page without an unsafe return URL when the session ended (401)"
- "does not claim success when confirming is no longer required (409)" (no success toast)
- "goes to the login page when the session ended while creating a new key (401)"
- "shows the error and stays on the page when confirming fails otherwise" (network error: message shown, no
  replaced-key hint, submit enabled again, no navigation; covers the previously uncovered `userMessage` fallback)

## Requirement coverage

Focused command (F): `cd web && npx ng test --watch=false --include src/app/features/auth --include src/app/core/auth --include src/app/app.spec.ts`
(8 files, 207 tests at the first run; the enrollment spec alone was re-run with 38 tests after the last addition).
Full command (C): `cd web && npm run check`.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: login with password + recovery code | Login "switches to a recovery code and sends it...", "opens the TOTP enrollment instead of the app when the server requires it" | F, C | Pass |
| AC1: password only (third mode) | Login "logs in with the password alone...", "explains a refused password-only login...", "opens the return URL after a password-only login...", "does not send a password-only login without a password", "starts the backoff when a password-only login is throttled", "shows the server message on other errors of a password-only login" | F, C | Pass |
| AC1: totpEnrollmentRequired routes to enrollment instead of the app | Login enrollment tests (with/without/unsafe return URL); guards "forced TOTP re-enrollment" block; App routes "leads a login that needs TOTP enrollment...", "keeps a session that needs TOTP enrollment out of every shell route"; AuthService "knows from the login..." | F, C | Pass |
| AC2: starts enrollment, shows QR code + secret | TotpEnrollment "starts the enrollment and shows the QR code and the key", loading state, retry tests, "creates a new key on request and shows it", "creates a new key only once...", "hides the old key and offers to try again when creating a new key fails"; AuthService "starts the TOTP re-enrollment..."; App routes integration | F, C | Pass |
| AC2: confirms with a TOTP code, then opens the app | TotpEnrollment "confirms with the code, then opens the return URL", double submit, unsafe return URL, "accepts a new code after a wrong one...", "does not confirm while a new key is being created", "clears a typed code when a new key is created"; AuthService "clears the enrollment flag..."; App routes integration (lands on /trash) | F, C | Pass |
| AC2: wrong code shows the server's validation error | TotpEnrollment "shows the server's validation error for a wrong code and clears it", "joins several server validation messages", 429 with/without Retry-After, replaced-key hint tests, "shows the error and stays on the page when confirming fails otherwise"; AuthService 400/409/429/401 confirm tests | F, C | Pass |
| CR-1 fix: 401/409 on start/confirm leave the page | TotpEnrollment 409 confirm/start, 409 with failing refresh, 401 confirm/start/new key, 401 with `/` and with an unsafe return URL, "does not claim success when confirming is no longer required (409)" | F, C | Pass |
| CR-2 fix: new key button, replaced-key hint, no confirm while creating | "creates a new key on request...", "hints at a replaced key...", "does not hint at a replaced key when throttled", "removes the replaced-key hint when the next attempt is refused...", "does not confirm while a new key is being created", "cannot create a new key while a confirmation is running" | F, C | Pass |
| AC3: `npm run check` passes with unit tests for the new flow | whole web suite, lint, api:check, e2e:typecheck, build | C | Pass (exit 0, 134 files, 2758 tests) |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include src/app/features/auth --include src/app/core/auth --include src/app/app.spec.ts`

Result:

Pass: 8 files, 207 tests (with the first 7 additions). After the 8th addition:
`cd web && npx ng test --watch=false --include src/app/features/auth/totp-enrollment` -> Pass, 1 file, 38 tests.
All new tests passed on the first run; no production defect surfaced.

## Full test execution

Command:

`cd web && npm run check`

Result:

Pass (exit 0, 2026-10-22T03:23:00+02:00 to 03:24:27+02:00, with all 8 additions): api:check,
`ng lint --max-warnings=0` ("All files pass linting."), e2e:typecheck, test:ci 134 files / 2758 tests passed,
`ng build` complete.

## .taskcheck

Result:

Not run by the test-writer: `.taskcheck` (`sh scripts/check.sh`) also runs the server and Android parts,
which this web-only task does not touch; its web part is `npm run check`, run above. The fixer reported a
passing `sh scripts/check.sh` on 3008661a's code; the parent worker runs `.taskcheck` before `done`.

## Bugs found

None. No test failed against production code. The earlier observations TW-O1 to TW-O3 (no new-key action,
409 on start not leading to the app, 401 on confirm not leading to login) are resolved by 3008661a and are
now covered by tests.

## Coverage

Status: verified (fresh run, exit 0, after the latest test additions on the final production code).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-totp-reenroll;
  base 98d1e4a985b6761a52738793f8990b8bfac4548c; HEAD 3008661a240c700e1010f413a4b4b1cbebe4b1ee; commits a87dd44d,
  b58c240a, b7a3845f, 5842f710, 3008661a from the parent worker, checked with `git diff --stat 98d1e4a9..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): production files unmodified at HEAD
  (git blob ids: app.routes.ts 82d973de, auth.guards.ts dc5fa1d5, auth.service.ts fc641063, login.ts d0c7ccc0,
  totp-enrollment.ts 58cc4f32, totp-enrollment.html 0684071e). Only uncommitted test input:
  totp-enrollment.spec.ts sha256 215c4bca5cc01505e9ff0b493d327a3581e0ccd67b49b4daedf4ac01d9248be6, identical
  before and after the final run. (`reviews/web-totp-reenroll/review.md` was also modified in the working
  tree during this run, not by the test-writer; it is not a test or source input.)
- Exact included files; renamed/deleted/excluded files and reasons: included web/src/app/core/auth/auth.service.ts,
  web/src/app/core/auth/auth.guards.ts, web/src/app/features/auth/login/login.ts,
  web/src/app/features/auth/totp-enrollment/totp-enrollment.ts, web/src/app/app.routes.ts.
  Excluded: web/src/app/core/auth/index.ts (re-exports only, no executable code; absent from the v8 report),
  web/src/app/core/auth/testing.ts and all *.spec.ts (test code), web/src/app/features/auth/auth.scss (styles).
  login.html and totp-enrollment.html are templates compiled into their components; v8 reports no separate
  counters for them, so they are covered only indirectly through the component DOM assertions. No renames
  or deletions.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + @vitest/coverage-v8 5.0.5;
  `npm run coverage` in web/; whole web suite (134 files, 2758 tests).
- Run start/completion time and exit status: 2026-10-22T03:24:27+02:00 to 03:25:39+02:00, exit 0.
- Generated report paths and report hashes/run identifier: web/coverage/web/coverage-summary.json
  (sha256 45756634fb107d8af7d383f9c71db0b6f7abf5d7c89752d898c3cc6fe5370f19, written 03:25:38),
  web/coverage/web/coverage-final.json (sha256 699dd6de904a139b37b2fd8ff9c3694ea80aeba8cb592b25c4e6c8b6fc4109eb).
- Evidence outputs are fresh and inputs stayed unchanged: yes (report mtime at run end, hashes differ from the
  intermediate run; spec hash and production blobs identical before and after).
- Final run after latest test additions and production fixes: yes (includes 3008661a and all 8 additions).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/core/auth/auth.service.ts` | 39/39 (100%) | 20/20 (100%) | 13/13 (100%) | 31/31 (100%) |
| `web/src/app/core/auth/auth.guards.ts` | 47/47 (100%) | 35/35 (100%) | 6/6 (100%) | 43/43 (100%) |
| `web/src/app/features/auth/login/login.ts` | 69/70 (98.57%) | 40/42 (95.23%) | 12/12 (100%) | 58/58 (100%) |
| `web/src/app/features/auth/totp-enrollment/totp-enrollment.ts` | 73/75 (97.33%) | 35/38 (92.10%) | 9/9 (100%) | 63/63 (100%) |
| `web/src/app/app.routes.ts` | 19/19 (100%) | N/A (0/0) | 18/18 (100%) | 19/19 (100%) |
| Aggregate (summed counters, 5 files; index.ts excluded as above) | 247/250 (98.80%) | 130/135 (96.30%) | 58/58 (100%) | 214/214 (100%) |

An intermediate run (same command, 03:21:05 to 03:22:37, exit 0, with the first 7 additions) showed
totp-enrollment.ts at 72/75 statements, 34/38 branches, 62/63 lines: line 151 (`return error.userMessage`,
a confirm failure that is neither a field error nor a 429) was uncovered. The 8th test covers it.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `web/src/app/features/auth/totp-enrollment/totp-enrollment.ts:151` (statement + branch 146 else) | confirm fails with a network/server error: show the message, stay on the page | Covered now: "shows the error and stays on the page when confirming fails otherwise" |
| `web/src/app/features/auth/totp-enrollment/totp-enrollment.ts:67, 94` (statements + if branches) | rethrow of a non-ApiError from start/confirm | Not tested: defensive programming-error path; it ends as an unhandled rejection in the global error handler, no user-visible behavior to assert |
| `web/src/app/features/auth/login/login.ts:131` (statement + if branch) | rethrow of a non-ApiError in showError (pre-existing code) | Not tested: defensive path, outside this task's change |
| `web/src/app/features/auth/login/login.ts:50`, `totp-enrollment.ts:32` (branch #0) | class declaration line; compiler-generated branch, no source behavior | Non-material, no test possible from source |

## Unable to test

- Real browser flow against the real server (restore without secrets, then login and enrollment end to end):
  not part of the acceptance criteria (AC3 names unit tests and `npm run check`); no Playwright test added.
- Multi-tab/multi-session races against the real server (another tab replacing the key, another session
  confirming first): simulated in unit tests with 400/401/409 responses; not run against a server.
- Actual QR code scannability by an authenticator app: only the rendered QR SVG presence is asserted.
- Template-only coverage counters (login.html, totp-enrollment.html): not reported separately by v8.

## Summary

Tests added and all validation passed. No production defects found in the final revision (3008661a);
task-scoped coverage is 98.80% statements / 96.30% branches with only defensive rethrow paths uncovered.
