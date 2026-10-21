# Code Review

Date: 2026-10-21

Task: p1-smoke - Phase 1 demo script

Scope: all committed changes of the task, `git diff 4b0bc8146ac73861b6551e63270b61a3a6188d21..183818f7 -- . ':!tasks' ':!reviews'`. This review updates the earlier review at 16ba8f54 (which replaced the one at 3e0db7c4). Between 16ba8f54 and 183818f7, `git diff 16ba8f54..183818f7 -- . ':!tasks' ':!reviews'` shows one code change only: a new test in `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/CsrfCookieWebTests.kt` (`the token issued at login survives authenticated requests and logout still replaces it`, lines 253-275). SecurityConfiguration.kt, web/playwright.config.ts, web/e2e/README.md, web/e2e/phase1/demo.spec.ts, inbox-page.ts and inbox-page.spec.ts are byte-identical to 16ba8f54. So the findings and conclusions of the 16ba8f54 review still apply, and they are kept below.

Reviewed revision: 183818f7ffc545c9d567670c45b452f4cf0b28f2

Requirements:
- tasks/p1-smoke.md acceptance criteria. 1: `web/e2e/phase1/*.spec.ts` covers demo steps 1-9 (including the tickler with the e2e clock and recurrence) and passes together with the phase 0 specs. 2: defects found are fixed here or recorded as new tasks. Also the recorded ASSUMPTIONs (the "Holiday" set-up project; the `inbox-exclusive` Playwright project outside touches).
- SPEC.md §13.2 Phase 1 steps 1-9, §4.1 (web login, CSRF), §5.3 (inbox processing)
- docs/ARCHITECTURE.md, docs/adr/0001-web-sessions-in-database.md, AGENTS.md project notes, web/e2e/README.md
- Parent instructions: review the new test (correctness, whether it asserts what it claims, flakiness, conventions), confirm the rest is unchanged, keep earlier findings; do not run Playwright or docker

Git range:
- Branch: main
- HEAD: 183818f7ffc545c9d567670c45b452f4cf0b28f2
- Base/start: 4b0bc8146ac73861b6551e63270b61a3a6188d21 (merge-base with origin/main)
- Commits reviewed: the task's code commits up to the 16ba8f54 merge, plus 183818f7 (one test added). The other commits after 16ba8f54 only change `tasks/` and `reviews/`.
- Uncommitted changes: none at the start of the review (working tree clean)

Files reviewed in full at 183818f7: CsrfCookieWebTests.kt, including its helpers `loggedIn`, `postContext` and `lastXsrfValue`. The other files were reviewed in full at 16ba8f54 (SecurityConfiguration.kt, web/playwright.config.ts, web/e2e/README.md (changed section), web/e2e/phase1/demo.spec.ts, web/e2e/gtd-inbox/inbox.spec.ts, web/e2e/support/fixtures.ts, web/e2e/support/api.ts (setClock), inbox-page.ts (diff and context), inbox-page.spec.ts (diff)) and have not changed since.

## New test at 183818f7 (CsrfCookieWebTests.kt:253-275)

I ran `server/gradlew -p server test --tests '*CsrfCookieWebTests*'`: BUILD SUCCESSFUL, 11 tests, 0 failures, 0 skipped. The new test passed in 0.3 s.

What the test does:
- It logs in through the real double-submit flow (`loggedIn()`).
- It sends an authenticated GET to `/auth/me` (200), then a POST to `/contexts` with the login token in both cookie and header (201).
- It checks that a request with the XSRF cookie but no session gets 401.
- It logs out (204) and checks that the last XSRF-TOKEN cookie set differs from the login token.
- It checks that a write with the logged-out session gets 401, even with a matching cookie/header pair.

Assessment:
- **Correct and useful.** The last assertion is new coverage. Logout invalidates the session token on the server. A replayed `COCKPIT_SESSION` with a valid double-submit pair passes CSRF and is still refused with 401. The XSRF-cookie-only 401 also confirms that the CSRF cookie carries no authentication.
- **Not flaky.** The test uses no time-dependent waits. TOTP comes from the injected clock, as in the other tests. Context names are unique, and `@BeforeEach` resets the account.
- **Conventions.** Kotest matchers, fictitious data and the file's existing helpers are used. The test compiles and runs. I did not run ktlint; it is part of `./gradlew check`, which the parent runs via `.taskcheck`.
- **Partly overclaims what it asserts.** See CR-2 and CR-3. Both are P3: the behavior the title names is already asserted precisely by the existing test at lines 169-204.

## Security: CSRF change (SecurityConfiguration.kt:74-79), unchanged since 16ba8f54

The STATELESS session policy was replaced by an explicit `RequestAttributeSecurityContextRepository`. I re-checked this independently:

- **Why the 403 happened.** Setting `sessionCreationPolicy(...)` explicitly makes `SessionManagementConfigurer` add a `SessionManagementFilter`. With a repository that never holds a context, that filter treated every cookie-authenticated request as a new authentication and ran `CsrfAuthenticationStrategy`, which rotates the token. Without the explicit property, no such filter is added, and the token is not rotated per request. The test "requests that carry the XSRF-TOKEN cookie keep it" shows this: no `Set-Cookie: XSRF-TOKEN` on an authenticated GET or POST.
- **Still stateless.** The context repository is request-scoped, the request cache is disabled, and the CSRF repository is cookie-based. The tests assert `getSession(false) == null` for login, read and write (CsrfCookieWebTests.kt:203), and for 401 and 403 responses (CsrfCookieWebTests.kt:240-251). ADR 0001 still holds.
- **CSRF still enforced.** `CsrfFilter`, the `spa()` handler, the repository and the ignore list are unchanged. The test at CsrfCookieWebTests.kt:218-238 rejects an authenticated write in three cases: no header, a forged header, and the token from before login. It also checks that none of those writes was stored. Login and logout still rotate the token explicitly in AuthController; tests cover that.
- **Session fixation.** Not applicable. No servlet session is used, and each login issues a fresh random `COCKPIT_SESSION` token.

Result: no security regression found.

## Playwright project ordering (web/playwright.config.ts), unchanged since 16ba8f54

- `phase-demos` has no dependencies and runs with `workers: 1`. The phase 0 and phase 1 files run one at a time, and each resets the stack in `afterAll`. `chromium` depends on `phase-demos`, and `inbox-exclusive` depends on `chromium`. So inbox processing runs after every other spec, and the phase demos always finish before it.
- `chromium`'s `testIgnore` excludes `gtd-inbox/`, and `inbox-exclusive` matches only `gtd-inbox/`. No spec runs twice, and no spec is dropped from a full run.
- Account sharing works across projects. `fixtures.ts` stores the account in `workerInfo.project.outputDir/.e2e-state`. No project overrides `outputDir`, so `inbox-exclusive` workers find the account that `chromium` stored and reuse it.
- `inbox.spec.ts` empties the inbox through the API before it starts. Once nothing else runs at the same time, its exact counts are deterministic.
- The README describes the new project. The change is outside `touches`, and the task notes record it as an ASSUMPTION.

## Acceptance criteria

- **Criterion 1 (coverage).** demo.spec.ts has one serial test per SPEC §13.2 Phase 1 step 1-9, plus a set-up step:
  - the tickler uses `setClock` and `page.clock` (+2 days);
  - recurrence covers FIXED from Monday to Monday, and AFTER_COMPLETION from a late completion;
  - `afterAll` resets the stack.

  For "passes", the parent reports two runs at 16ba8f54: `npm --prefix web run e2e -- --project=phase-demos` -> 15 passed (phase 0 tests 1-5, phase 1 tests 0-9), and the full `npm --prefix web run e2e` -> 29 passed, with `inbox-exclusive` run and passed. The e2e-relevant code is unchanged since 16ba8f54: the only later code change is a server unit test. I did not re-run Playwright, as instructed.
- **Criterion 2 (defects).** Three defects were found and fixed in this task:
  - inbox 'To project' did not set the project's next step (inbox-page.ts:285-288, with unit tests);
  - the server rotated the CSRF token on every authenticated request (with tests, now including the 183818f7 test);
  - inbox e2e isolation (the Playwright project).

  The notes record the fixes outside `touches`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Any failing spec in `chromium` silently skips the inbox processing e2e**
    - File: `web/playwright.config.ts:47`
    - Category: Tests
    - Problem: `inbox-exclusive` lists `dependencies: ['chromium']`. Playwright runs a dependent project only when all its dependencies pass. With `retries: 0`, one failing or flaky spec anywhere in the regular suite causes `gtd-inbox/inbox.spec.ts` to be reported as "did not run" instead of passed or failed. A real inbox regression can then hide behind an unrelated failure. web/e2e/README.md says the project runs "after all other specs" but does not mention this skip behavior.
    - Why it matters: it weakens the signal of a full e2e run. This matters most for criterion evidence such as "N passed", where a skipped inbox spec is easy to overlook.
    - Suggested fix: add one sentence to web/e2e/README.md saying that a failure in the regular specs skips `inbox-exclusive`, and that `npm run e2e -- --no-deps --project=inbox-exclusive` reruns it alone. A longer-term option, outside this task's touches: make `inbox.spec.ts` count only its own items, so it no longer needs exclusivity.

- [ ] **CR-2 | The new test does not assert the "survives" in its name**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/CsrfCookieWebTests.kt:257-258`
    - Category: Tests
    - Problem: the test is named `the token issued at login survives authenticated requests ...`, but it never checks that the GET and POST responses leave the XSRF-TOKEN cookie alone. The 201 on the write does not prove it either. The double-submit check only compares the request's cookie and header, and both still carry the login token. So the write would also pass if the preceding GET had rotated the token (the original bug).
    - Why it matters: if the rotation regression came back, this test would still pass. Its name suggests it guards that behavior. The existing test at lines 191 and 201 does assert it (`getCookie(XSRF_COOKIE).shouldBeNull()`), so there is no coverage gap today. But the new test overstates what it proves.
    - Suggested fix: add `read.response.getCookie(XSRF_COOKIE).shouldBeNull()` and the same for `write`. Or rename the test to what it does assert: CSRF cookie alone is not authentication, and a logged-out session is refused even with a valid double-submit pair.

- [ ] **CR-3 | The JSESSIONID assertion cannot fail under MockMvc**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/CsrfCookieWebTests.kt:259-262`
    - Category: Tests
    - Problem: the test checks that no `Set-Cookie` header contains `JSESSIONID`. Under MockMvc this can never fail. When code calls `request.getSession(true)`, it gets a `MockHttpSession`, and `MockHttpServletResponse` never writes a `JSESSIONID` cookie; only a real servlet container does that. The comment says this shows "the login lives only in the request", but the assertion is vacuous.
    - Why it matters: a reader may count this as coverage for statelessness when it adds none. The meaningful check is `request.getSession(false).shouldBeNull()`, which the file already uses at lines 203 and 250.
    - Suggested fix: replace the loop with `listOf(read, write).forEach { it.request.getSession(false).shouldBeNull() }`, or drop it, since line 203 already covers read and write.

Summary: 0 P0, 0 P1, 0 P2, 3 P3

## Notes for the parent (not findings)

- When you record evidence for a full e2e run, check that `inbox-exclusive` actually ran (CR-1). Your reported full run at 16ba8f54 says it did.
- CR-2 and CR-3 concern test precision only. Production behavior is correct, and the existing tests cover it. If you fix them, only test lines change. The helpers then need to re-run on the new revision, per docs/QUALITY.md "Freshness". Accepting them as-is is also reasonable.
- From the earlier review, not verified by running it: the pre-existing README sentence from p0-smoke, "Every run includes them, also with a spec filter". Reading the Playwright 1.64.1 runner, CLI file filters seem to apply to dependency projects too, when those projects are also selected. If so, a spec-filtered run may skip the phase demos. This is outside this task's scope.

## Review conclusion

No material issues found.
