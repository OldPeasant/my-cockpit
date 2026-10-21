# Code Review

Date: 2026-10-21

Task: p1-smoke - Phase 1 demo script

Scope: all committed changes of the task, `git diff 4b0bc8146ac73861b6551e63270b61a3a6188d21..HEAD -- . ':!tasks' ':!reviews'`. The task commits are 74b59cb7, 02f36c87, 18a1f100, 25bf8f59, f6a0e4c5, 9faf689b, 81528371 and bc52b35b, merged via 16ba8f54. This review replaces the earlier review.md (rev 3e0db7c4). Since that review, the task's code changed in three files only: `web/playwright.config.ts` (the new `inbox-exclusive` project), `web/e2e/README.md`, and `server/.../core/auth/CsrfCookieWebTests.kt` (4 new tests). `web/e2e/phase1/demo.spec.ts`, the inbox fix and SecurityConfiguration.kt are byte-identical to 3e0db7c4. They were re-read for this review anyway.

Reviewed revision: 16ba8f54c686a0d403cb17a825ce8e2dc0f61a97

Requirements:
- tasks/p1-smoke.md acceptance criteria. 1: `web/e2e/phase1/*.spec.ts` covers demo steps 1-9 (including the tickler with the e2e clock and recurrence) and passes together with the phase 0 specs. 2: defects found are fixed here or recorded as new tasks. Also the recorded ASSUMPTIONs (the "Holiday" set-up project; the `inbox-exclusive` Playwright project outside touches).
- SPEC.md §13.2 Phase 1 steps 1-9, §4.1 (web login, CSRF), §5.3 (inbox processing)
- docs/ARCHITECTURE.md, docs/adr/0001-web-sessions-in-database.md, AGENTS.md project notes, web/e2e/README.md
- Parent instructions: focus on the security impact of the CSRF change and on Playwright project ordering and dependencies; do not run Playwright or docker compose

Git range:
- Branch: main
- HEAD: 16ba8f54c686a0d403cb17a825ce8e2dc0f61a97
- Base/start: 4b0bc8146ac73861b6551e63270b61a3a6188d21 (merge-base with origin/main)
- Commits reviewed: 8 task commits plus the merge commit 16ba8f54
- Uncommitted changes: none at the start of the review (working tree clean)

Files reviewed in full: SecurityConfiguration.kt, CsrfCookieWebTests.kt, web/playwright.config.ts, web/e2e/README.md (changed section), web/e2e/phase1/demo.spec.ts, web/e2e/gtd-inbox/inbox.spec.ts, web/e2e/support/fixtures.ts, web/e2e/support/api.ts (setClock), inbox-page.ts (diff and context), inbox-page.spec.ts (diff). I also read the Playwright 1.64.1 runner source (`node_modules/playwright/lib/runner/index.js`) to see how project dependencies and file filters interact.

## Security: CSRF change (SecurityConfiguration.kt:74-79)

The STATELESS session policy was replaced by an explicit `RequestAttributeSecurityContextRepository`. I re-checked this independently:

- **Why the 403 happened.** Setting `sessionCreationPolicy(...)` explicitly makes `SessionManagementConfigurer` add a `SessionManagementFilter`. With a repository that never holds a context, that filter treated every cookie-authenticated request as a new authentication and ran `CsrfAuthenticationStrategy`, which rotates the token. Without the explicit property, no such filter is added, and the token is not rotated per request. The new test "requests that carry the XSRF-TOKEN cookie keep it" shows this: no `Set-Cookie: XSRF-TOKEN` on an authenticated GET or POST.
- **Still stateless.** The context repository is request-scoped, the request cache is disabled, and the CSRF repository is cookie-based. The new tests assert `getSession(false) == null` for login, read and write (CsrfCookieWebTests.kt:203), and for 401 and 403 responses (CsrfCookieWebTests.kt:239-251). ADR 0001 still holds.
- **CSRF still enforced.** `CsrfFilter`, the `spa()` handler, the repository and the ignore list are unchanged. The new test at CsrfCookieWebTests.kt:217-236 rejects an authenticated write in three cases: no header, a forged header, and the token from before login. It also checks that none of those writes was stored. Login and logout still rotate the token explicitly in AuthController; existing tests cover that.
- **Session fixation.** Not applicable. No servlet session is used, and each login issues a fresh random `COCKPIT_SESSION` token.

Result: no security regression found.

## Playwright project ordering (web/playwright.config.ts)

- `phase-demos` has no dependencies and runs with `workers: 1`. The phase 0 and phase 1 files run one at a time, and each resets the stack in `afterAll`. `chromium` depends on `phase-demos`, and `inbox-exclusive` depends on `chromium`. So inbox processing runs after every other spec, and the phase demos always finish before it. Neither the phase demos' stack reset nor their clock moves can overlap with the inbox spec.
- `chromium`'s `testIgnore` excludes `gtd-inbox/`, and `inbox-exclusive` matches only `gtd-inbox/`. No spec runs twice, and no spec is dropped from a full run.
- Account sharing works across projects. `fixtures.ts` stores the account in `workerInfo.project.outputDir/.e2e-state`. No project overrides `outputDir`, so `inbox-exclusive` workers find the account that `chromium` stored and reuse it. They do not re-run setup on a stack that is already set up.
- `inbox.spec.ts` empties the inbox through the API before it starts. Once nothing else runs at the same time, its exact counts ("3 items left") are deterministic.
- The README describes the new project. The change is outside `touches`, and the task notes record it as an ASSUMPTION.

## Acceptance criteria

- **Criterion 1 (coverage).** demo.spec.ts has one serial test per SPEC §13.2 Phase 1 step 1-9, plus a set-up step:
  - the tickler uses `setClock` and `page.clock` (+2 days);
  - recurrence covers FIXED from Monday to Monday, and AFTER_COMPLETION from a late completion;
  - `afterAll` resets the stack.

  The "passes" part needs the Playwright run against the stack. The parent is doing that run; this review did not do it.
- **Criterion 2 (defects).** Three defects were found and fixed in this task:
  - inbox 'To project' did not set the project's next step (inbox-page.ts:285-288, with unit tests);
  - the server rotated the CSRF token on every authenticated request (with tests);
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

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Notes for the parent (not findings)

- Criterion 1 still needs the phase-demos Playwright run (phase 0 and phase 1) against the e2e stack at this revision. When you record evidence for a full run, check that `inbox-exclusive` actually ran (CR-1).
- I did not verify one pre-existing README sentence (from p0-smoke, not changed here) by running it: "Every run includes them, also with a spec filter". Reading the Playwright 1.64.1 runner, CLI file filters seem to apply to dependency projects too, when those projects are also selected (the default, with no `--project`). If so, a spec-filtered run may skip the phase demos. This is outside this task's scope.

## Review conclusion

No material issues found.
