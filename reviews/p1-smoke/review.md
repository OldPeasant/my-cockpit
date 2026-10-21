# Code Review

Date: 2026-10-21

Task: p1-smoke - Phase 1 demo script

Scope: all committed changes of the task, `git diff 5ff7da30..3e0db7c4`. The parent named `2feff1fe..d858b26a`. While this review ran, the branch was rebased onto 5ff7da30 and HEAD became 3e0db7c4. Between 2feff1fe and 5ff7da30 only `tasks/*.md` heartbeat lines changed, so the code diff of both ranges is the same. This review supersedes the earlier review.md, which covered the code before the server CSRF commit.

Reviewed revision: 3e0db7c4f1f60dce9fb8b6259366596233465d34

Requirements:
- tasks/p1-smoke.md acceptance criteria. 1: `web/e2e/phase1/*.spec.ts` covers demo steps 1-9 (including the tickler with the e2e clock and recurrence) and passes together with the phase 0 specs. 2: defects found are fixed here or recorded as new tasks. Also the recorded ASSUMPTION about the "Holiday" project.
- SPEC.md §13.2 Phase 1, §4.1 (web login, CSRF), §5.1-5.3
- docs/ARCHITECTURE.md, docs/adr/0001-web-sessions-in-database.md, AGENTS.md project notes
- Parent instructions: focus on the security impact of the CSRF change; do not run e2e or docker compose

Git range:
- Branch: main
- HEAD: 3e0db7c4f1f60dce9fb8b6259366596233465d34
- Base/start: 5ff7da30dd937bf50d3c28a127cf9693458738a3 (merge-base with origin/main; was 2feff1fe before the rebase)
- Commits reviewed: 6 (9c859ea6, 100ac91f, 38fb70c1, 0829c7a2, eaeb78f9, 3e0db7c4)
- Uncommitted changes: `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/CsrfCookieWebTests.kt` was changed in the working tree while this review ran, presumably by the parallel test-writer. These changes are not part of this review. See "Notes for the parent".

Files reviewed in full:
- server/.../core/auth/infra/SecurityConfiguration.kt
- server/.../core/auth/infra/SessionCookies.kt
- server/.../core/auth/infra/SessionAuthenticationFilter.kt
- server/.../core/auth/api/AuthController.kt
- CsrfCookieWebTests.kt (the committed version)
- web/e2e/phase1/demo.spec.ts
- web/playwright.config.ts (diff)
- web/src/app/features/gtd/inbox/inbox-page.ts
- inbox-page.spec.ts (diff)
- web/src/app/features/gtd/data/project-store.ts
- TestSupportConfiguration.kt
- ADR 0001
- the earlier review.md and fixes.md

## Security analysis of the CSRF change (3e0db7c4)

The change replaces `sessionManagement { sessionCreationPolicy(STATELESS) }` with `securityContext { securityContextRepository(RequestAttributeSecurityContextRepository()) }`.

- **Why the 403 happened.** Calling `sessionCreationPolicy(...)` explicitly makes Spring Security's `SessionManagementConfigurer` add a `SessionManagementFilter`. For STATELESS, that filter's repository never holds a context. So every request the cookie filter authenticated looked like a fresh login, and the composite session-authentication strategy ran. That strategy includes `CsrfAuthenticationStrategy`, which issues a new XSRF-TOKEN. Two concurrent SPA requests then disagree on the token. The diagnosis is correct. Without the explicit property, the configurer adds no `SessionManagementFilter`.
- **Stateless is kept.** The security context repository is set explicitly to the request-attribute repository, so the default does not fall back to `HttpSessionSecurityContextRepository`. No component in this chain creates an `HttpSession`: the request cache is disabled, the CSRF repository is the cookie one, and there is no form login. The new test checks `getSession(false) == null` for login, a read and a write. ADR 0001 ("no `HttpSession`, no `JSESSIONID`") still holds.
- **Session fixation.** Nothing to protect here. The servlet session is not used. Each successful login issues a new random `COCKPIT_SESSION` token through `LoginService` (AuthController.kt:89), so a planted cookie value is never upgraded to a logged-in session. The removed filter added no fixation protection for this login flow: the controller does the login, not a Spring authentication filter.
- **Token rotation on login and logout.** Still done explicitly: `cookies.rotateCsrfToken` at AuthController.kt:90 and AuthController.kt:116. Existing tests cover it: "login and logout replace the XSRF-TOKEN cookie" (with exactly one Set-Cookie on login), "logout without a session replaces the XSRF-TOKEN cookie too", and "a failed login keeps the XSRF-TOKEN cookie". Only the rotation on *every authenticated request* is gone. That rotation was never intended (see the class KDoc) and gave no security benefit with a double-submit cookie.
- **CSRF is still enforced.** `CsrfFilter` is unchanged: same repository, same `spa()` handler, same ignore list. Tests cover authenticated writes with a session cookie and no or forged header: DeviceTokenWebTests:148, DeviceTokenEdgeCaseTests:292/303, LinkWebTests:240/309, RecoveryCodeWebTests:123, SecurityConfigurationTests:64. They also cover login without a header, with a forged header, or with a header but no cookie (CsrfCookieWebTests). The test-support chain (e2e profile only, TestSupportConfiguration.kt:41) keeps STATELESS. It has CSRF disabled and authorization `permitAll`, so the rotation never applied there.

Result: no security regression found. The change is the smallest correct fix, and the code comment at SecurityConfiguration.kt:75-78 explains why the obvious STATELESS setting must not be used.

## Other checks

- **Inbox 'To project' fix** (inbox-page.ts:167-174, 285-288). The server never sets a project's next step on a move (ProjectService.kt:113 sets it only in `setNextStep`). So the client call is required for SPEC §13.2 step 2 ("becomes its next step"). Conditions are handled:
  - the call happens only when the store sees the project as stalled;
  - it runs after a successful move;
  - a failure is ignored, and the item stays processed;
  - the captured project is preselected only if it is open.
  The unit tests cover all of these, including two items going into the same stalled project, where only one next step is set.
- **Demo script** (demo.spec.ts). Steps 1-9 match SPEC.md §13.2, as the earlier review found. Earlier findings CR-1 and CR-2 are resolved:
  - the ASSUMPTION is recorded in tasks/p1-smoke.md;
  - the "New project" path runs end to end (lines 145-167);
  - the negative "hidden" checks wait for a positive load signal first (lines 231-244).
  The tickler uses `setClock` plus `page.clock`, and the recurrence covers FIXED and AFTER_COMPLETION. `afterAll` resets the stack.
- **Earlier intermittent failure.** fixes.md reports one e2e run where "Buy milk" stayed in the inbox list after `n`. That is consistent with the CSRF 403 that 3e0db7c4 fixes: concurrent writes during fast keyboard processing. Whether it is gone can only be confirmed by the e2e run the parent is doing now.
- **playwright.config.ts.** `workers: 1` on the `phase-demos` project is a per-project option (supported by the installed Playwright, as the earlier review checked) and is right for scripts that reset shared state.
- **Scope.** The server and inbox fixes are outside `touches: [web/e2e/phase1]`. Criterion 2 explicitly allows fixing found defects here, and the task note records it.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Notes for the parent (not findings)

- Criterion 1 needs the phase 0 and phase 1 Playwright run against the stack with 3e0db7c4. This review did not run it (as instructed).
- The uncommitted working-tree version of CsrfCookieWebTests.kt (not reviewed) has `import jakarta.servlet.http.Cookie` between the `io.kotest` imports. ktlint's import ordering will probably reject that. Run `./gradlew ktlintFormat` or check before committing it. If test changes are committed after this review, only the test file changes. Per docs/QUALITY.md "Freshness", decide whether this review must be re-run for the new revision.

## Review conclusion

No material issues found.
