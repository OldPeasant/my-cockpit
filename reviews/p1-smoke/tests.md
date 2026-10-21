# Test Report

Date: 2026-10-21

Task: p1-smoke - Phase 1 demo script

Scope: `git diff 4b0bc8146ac73861b6551e63270b61a3a6188d21..16ba8f54c686a0d403cb17a825ce8e2dc0f61a97 -- . ':!tasks' ':!reviews'`
(base = merge-base with origin/main as given by the parent; task commits 74b59cb7, 02f36c87, 18a1f100,
25bf8f59, f6a0e4c5, 9faf689b, 81528371, bc52b35b, merged via 16ba8f54 on branch `main`), plus the
uncommitted test change of this report in
`server/src/test/kotlin/ch/sonensei/cockpit/core/auth/CsrfCookieWebTests.kt`. Changed files in the task
diff: `web/e2e/phase1/demo.spec.ts` (new e2e spec), `web/playwright.config.ts` (`phase-demos` project with
`workers: 1`, new `inbox-exclusive` project), `web/e2e/README.md`, `web/src/app/features/gtd/inbox/inbox-page.ts`
(production fix "To project") and its spec, `server/.../core/auth/infra/SecurityConfiguration.kt` (production
fix: CSRF token no longer rotated per authenticated request) and `server/.../core/auth/CsrfCookieWebTests.kt`.

Requirements:
- `tasks/p1-smoke.md` acceptance criteria 1 and 2, and its notes/assumptions
- `SPEC.md` §13.2 Phase 1 steps 1-9, §4.1 (auth, CSRF double-submit), §4.5 (capture syntax), §5.1-§5.3 (GTD)
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`
- The doc comment of `SecurityConfiguration` (XSRF-TOKEN issued when missing, replaced on login and logout,
  echoed in `X-XSRF-TOKEN` for state-changing requests; no servlet session)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/CsrfCookieWebTests.kt` (this run)
- `web/src/app/features/gtd/inbox/inbox-page.spec.ts` (earlier test-writer run, committed in the task)

## Tests added or updated

Count: 1 in this run (server); 3 in the earlier run (web). The e2e spec and the other tests were reviewed, not changed.

This run, `CsrfCookieWebTests`:
- `the token issued at login survives authenticated requests and logout still replaces it` - after login, an
  authenticated GET and POST keep working with the login's token; no `JSESSIONID` is set and the XSRF cookie
  alone does not authenticate (the security context lives only in the request, `RequestAttributeSecurityContextRepository`);
  logout with that same token still answers 204 and replaces the token; a write with the logged-out
  session and a matching double-submit pair is refused (401).

Reviewed and kept (added with the CSRF fix, c39fb343 / 3caa7480):
- `requests that carry the XSRF-TOKEN cookie keep it, so concurrent requests of the SPA agree on the token` -
  regression test of the fix: authenticated GET and POST set no new XSRF-TOKEN cookie; no servlet session.
- `a write sent with the token read before another write answered still passes, as for concurrent SPA requests` -
  the ordering case that caused the 403s in the e2e run.
- `an authenticated write without the header, with a forged one or with the token from before login is rejected` -
  CSRF is still enforced for session requests, and nothing is stored.
- `rejected requests do not open a servlet session either` - 401 and 403 paths stay session-less.
- Pre-existing tests still pass and cover login/logout rotation (`login and logout replace the XSRF-TOKEN cookie ...`,
  `logout without a session replaces ...`), cookie attributes, failed login, missing/forged header on login.
  `DeviceTokenWebTests` covers the Bearer exemption and `a session cookie still needs the CSRF token ...`;
  `SecurityConfigurationTests` covers `the server creates no servlet session`.

Earlier run, `inbox-page.spec.ts` (still valid; the file is unchanged since, sha256 below):
- `does not preselect the captured project when it is not active`
- `does not set a next step when the move into a stalled project fails`
- `sets only one next step when two items go into the same stalled project`
- plus the four tests the parent added with the fix (preselect captured project and set it as next step with
  If-Match, new project becomes next step, project with a next step is kept, failing next-step call still counts
  the item as processed).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 / §13.2 steps 1-9 (incl. tickler with e2e clock, recurrence) | `web/e2e/phase1/demo.spec.ts` `Phase 1 demo › set-up, 1.`-`9.` | `cd web && npm run e2e -- --project=phase-demos` | Not run in this test-writer run: the parent is running the e2e stack (phase-demos) and reports the result. An earlier run at 2b1d34bb (before the CSRF fix and the `inbox-exclusive` project) passed 15/15; that result is stale for the final code. |
| AC1: passes together with phase 0 specs | `Phase 0 demo › 1.`-`5.` in the same project | as above | Not run here (see above) |
| AC1 step 2 / AC2 defect: inbox "To project" preselects the captured project and makes the item the next step of a stalled project | 7 unit tests in `inbox-page.spec.ts` listed above | `cd web && npx ng test --watch=false --include src/app/features/gtd/inbox/inbox-page.spec.ts`; `cd web && npm run check` | Pass (61 tests in the file; full web suite 1520 passed) |
| AC2 defect: CSRF token not rotated per authenticated request (concurrent SPA requests got 403) | `requests that carry the XSRF-TOKEN cookie keep it ...`, `a write sent with the token read before another write answered still passes ...`, `the token issued at login survives authenticated requests and logout still replaces it` | `server/gradlew -p server test --tests '*Csrf*' --tests '*SecurityConfigurationTests*' --tests '*LoginWebTests*' --tests '*DeviceTokenWebTests*'` | Pass |
| CSRF still enforced after the fix (missing, forged, stale token; Bearer exempt; session cookie needs token) | `an authenticated write without the header, with a forged one or with the token from before login is rejected`, `a missing or mismatching header is rejected with 403`, `DeviceTokenWebTests` `bearer access works ...`, `a session cookie still needs the CSRF token ...`, `SecurityConfigurationTests` `state-changing requests outside setup require a CSRF token` | as above | Pass |
| Login and logout still replace the token | `login and logout replace the XSRF-TOKEN cookie ...`, `logout without a session replaces ...`, `the token issued at login survives ... logout still replaces it` | as above | Pass |
| Still stateless (no servlet session, context not kept between requests) | `the server creates no servlet session`, `rejected requests do not open a servlet session either`, `requests that carry the XSRF-TOKEN cookie keep it ...`, `the token issued at login survives ...` | as above | Pass |
| AC2 defect: inbox e2e spec isolated in `inbox-exclusive` project (`web/playwright.config.ts`) | `web/e2e/gtd-inbox/*.spec.ts` in project `inbox-exclusive` | full `cd web && npm run e2e` | Not run: Playwright excluded by the parent for this run; config type-checks in `npm run check` (`e2e:typecheck`) |

## Focused test execution

Command:

`server/gradlew -p server test --tests '*Csrf*' --tests '*SecurityConfigurationTests*' --tests '*LoginWebTests*' --tests '*DeviceTokenWebTests*'`

Result:

Pass (BUILD SUCCESSFUL; JUnit XML: CsrfCookieWebTests 11, DeviceTokenWebTests 9, LoginWebTests 14,
SecurityConfigurationTests 7+2, TestSupportLoginWebTests 9; 52 tests, 0 failures, 0 skipped)

Command:

`cd web && npx ng test --watch=false --include src/app/features/gtd/inbox/inbox-page.spec.ts`

Result:

Pass (1 file, 61 tests)

## Full test execution

Command:

`server/gradlew -p server test jacocoTestReport`, then `server/gradlew -p server check`

Result:

Pass (exit 0, BUILD SUCCESSFUL; 2808 tests, 0 failures, 0 skipped; `check` with ktlint main/test passed)

Command:

`cd web && npm run check` (api:check, eslint `--max-warnings=0`, e2e typecheck, `ng test --watch=false`, `ng build`)

Result:

Pass ("All files pass linting."; 83 test files, 1520 tests passed; build complete)

Command:

`cd web && npm run e2e -- --project=phase-demos`

Result:

Not run (by instruction: the parent is running the e2e stack concurrently and reports the result)

## .taskcheck

Result:

Not run by this test-writer as a whole (`sh scripts/check.sh`); its server (`./gradlew check`) and web
(`npm run check`) parts were run above and pass. The task does not change `android/`. The parent runs
`.taskcheck` before `done`.

## Bugs found

None. No test exposed a production defect.

Observations for the parent (not production defects, no fix required by the fixer), kept from the earlier run:

- `TW-1` (Low, spec fidelity): SPEC.md §13.2 step 2 says "Plan holiday" goes to a **new** project "Holiday", but
  step 1 captures it with `#Holiday`, which the capture parser only resolves for existing projects. The e2e spec
  creates "Holiday" in a set-up step; the "new project" path is exercised on a separate item. Recorded as an
  assumption in `tasks/p1-smoke.md`.
- `TW-2` (Low, test strength): in step 6 the FIXED task is due Monday and completed Monday, so its next date is the
  same under FIXED and AFTER_COMPLETION; the AFTER_COMPLETION task (due Friday, completed Monday) does tell the
  modes apart. No change made.
- `TW-3` (Low, scope note): `SecurityConfiguration.kt` and `web/playwright.config.ts` are outside the task's
  `touches` (`web/e2e/phase1`); the task notes already record this.

## Coverage

Status: verified (server and web unit coverage); the e2e spec and Playwright config are test code and not instrumented.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured.
- `inbox-page.ts`: added lines (`git diff -U0 4b0bc814 16ba8f54`: +167..168, +170..173, +285..288) intersected with
  lcov `DA` lines: executable added lines 3/3 (100%): L168 (56 hits), L287 (10), L288 (10); the multi-line statement
  at L170-173 maps to the branch set at L170. Branches on added lines (`BRDA`): 6/6 (L170 `??` chain 4/4, L288
  `if (project?.stalled)` 2/2).
- `SecurityConfiguration.kt`: added executable line L79 (`.securityContext { ... RequestAttributeSecurityContextRepository() }`)
  covered (JaCoCo `ci=10, mi=0`; L80 `ci=6, mi=0`); the import and comment lines are not executable. 1/1 (100%).
  No branches on changed lines.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `p1-smoke`; base
  `4b0bc8146ac73861b6551e63270b61a3a6188d21`; HEAD `16ba8f54c686a0d403cb17a825ce8e2dc0f61a97`; task commits as listed
  in Scope, supplied by the parent and matched against the diff and the task notes.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 16ba8f54 plus uncommitted
  `CsrfCookieWebTests.kt` sha256 `edd644b45befa6837b04d41229b98876c0d859fca17e91855893e23c11ce4ff7`;
  `SecurityConfiguration.kt` sha256 `ca200015cf681c4f3146f82075d5931ebd221852df558855386035a20b55c39a`,
  `inbox-page.ts` sha256 `5a5d0459de03e6200d9c4e36c3990daa60bd14f5d2bb9d54b76eeb6d201cf93f`,
  `inbox-page.spec.ts` sha256 `a37871973d61dd35a5a580441547dbcf3eba61bf2aa7dca8447dced6bcc99320` (all three unchanged
  from HEAD). `reviews/p1-smoke/review.md` was modified in the working tree by another helper; not a test input.
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/infra/SecurityConfiguration.kt`,
  `web/src/app/features/gtd/inbox/inbox-page.ts`. Excluded: `web/e2e/phase1/demo.spec.ts`,
  `web/src/app/features/gtd/inbox/inbox-page.spec.ts`, `CsrfCookieWebTests.kt` (tests), `web/playwright.config.ts`
  (test runner configuration), `web/e2e/README.md` (docs). No renamed or deleted files.
- Provider/version, command, working directory, and test selection:
  - Server: JaCoCo via the Gradle `jacoco` plugin (Gradle 9.8.1, plugin-default JaCoCo version);
    `server/gradlew -p server test jacocoTestReport` from the repo root; full server suite (2808 tests).
  - Web: Vitest 5.0.5 with `@vitest/coverage-v8`; `npm run coverage` in `web/`; full web unit suite (83 files, 1520 tests).
- Run start/completion time and exit status: server 2026-10-21T04:08:41Z - 04:13:38Z, exit 0; web started
  2026-10-21T04:09:07Z, report written 04:09:34Z, all tests passed.
- Generated report paths and report hashes/run identifier:
  `server/build/reports/jacoco/test/jacocoTestReport.xml` (sha256 `f0a04ad29bdff20b9277e859d363bf41ce9c530d1b251a4d0f77efdec654de0d`,
  written 06:13:37 +0200); `web/coverage/web/coverage-summary.json` (sha256
  `d99f85b1a698c4c819602531cc318ecc2431fa406eea47b437bbbc9b7d70c7e3`, written 06:09:34 +0200),
  `web/coverage/web/coverage-final.json` (sha256 `43d3753060fee1dae581974221ce4be814255f462429afb2983dca29e0f0a2fb`),
  `web/coverage/web/lcov.info` (sha256 `edf641a38105e4e5699ae0ceea8ae5aa273f080856148a1577bde531e1218925`).
- Evidence outputs are fresh and inputs stayed unchanged: both reports were written by these runs (timestamps
  inside the run windows; Gradle reran tests because the test source changed, Vitest rewrites `coverage/`); the
  input hashes above were taken after the runs and the test file was last edited before them.
- Final run after latest test additions and production fixes: yes (includes the test added in this run; no
  production change after it).

Values below use covered/total (percentage). Server: JaCoCo `INSTRUCTION` as statements, `BRANCH`, `METHOD`, `LINE`.
Web: v8 via Vitest.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/infra/SecurityConfiguration.kt` | 261/263 instr. (99.24%) | 2/4 (50.00%) | 10/10 (100%) | 37/37 (100%) |
| `web/src/app/features/gtd/inbox/inbox-page.ts` | 154/158 (97.46%) | 87/91 (95.60%) | 37/38 (97.36%) | 126/127 (99.21%) |

Aggregates are not summed across the two providers (different counter kinds); per-file values above. No scoped
production file is missing or unmeasured.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `SecurityConfiguration.kt:108` (2 branches: `as? CsrfToken` null / `?.token` in `CsrfCookieFilter`) | none for this task: the CSRF filter always sets the token attribute before this filter; the null case is defensive | Pre-existing, not changed by the task; left uncovered |
| `inbox-page.ts:292` (`default: return` in `apply`) | none: only reached for actions without an effect | Pre-existing, defensive; left uncovered |
| `inbox-page.ts:160`, `:223` branches (`if (!task) return`) | guards for no current item; callers already check `current()` | Pre-existing defensive guards; left uncovered |
| `inbox-page.ts:40` branch (component decorator) | compiler-generated branch | Not material |

## Unable to test

- The phase 0 + phase 1 Playwright run (AC1) and the `inbox-exclusive` project ordering were not run by this
  test-writer, by the parent's instruction (the parent runs the e2e stack now). The parent must record that run as
  the evidence for AC1; the earlier 15/15 pass predates the CSRF fix and the config change.
- A real browser sending truly concurrent requests is not reproduced in MockMvc; the ordering is simulated
  deterministically (token read before another write answered). The e2e run is the end-to-end check of that fix.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. One server test was added for the
CSRF fix (token kept across authenticated requests, still replaced on logout, context not kept between requests);
focused auth tests (52), the full server suite (2808) with `check`, and `npm run check` (1520 web tests) pass.
The Playwright phase-demos run was not part of this run and must come from the parent.
