# Code Review

Date: 2026-10-26

Task: mail-connection-errors - Shared MailConnectionException handler for mail controllers

Scope: committed task changes `git diff aea7f57e..HEAD` (commits 14868695, a4241ff5)

Requirements:
- tasks/mail-connection-errors.md (acceptance criteria and recorded assumptions)
- AGENTS.md project notes (Problem Details errors, log hygiene, lint, module conventions)
- Existing conventions in server/src/main/kotlin/ch/sonensei/cockpit/core/web/ProblemDetailsAdvice.kt

Git range:
- Branch: main
- HEAD: a4241ff5
- Base/start: aea7f57ec882f32b97022f5e0ee1aca5f32f329d
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | No HTTP-level test of the shared handler for MailAccountController**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/mail/imap/MailSecretRedactionTests.kt:78`
    - Category: Tests
    - Problem: The account controller's local handler was replaced by the advice, but its tests now call `MailConnectionAdvice().handleConnectionFailure(...)` directly. Nothing sends a request to an account endpoint (for example folder sync) and checks that a `MailConnectionException` comes back as 502/503 Problem Details. The GTD path is covered end to end by `MailGtdMailServerTests` (`@SpringBootTest`, lines 127-160), which shows that the advice beats the global `ProblemDetailsAdvice` catch-all in the real context. The message and draft paths are covered through MockMvc with the advice registered.
    - Why it matters: These tests would still pass if a later change narrowed the advice's `basePackages` or moved the account controller out of the advice's scope.
    - Suggested fix: Add one MockMvc test for an account endpoint whose service throws `MailConnectionException`. Use standalone setup with `MailConnectionAdvice` and `ProblemDetailsAdvice`, or a web slice test. Assert the status, the `application/problem+json` content type, `detail` and `errorCode`.

## Notes (verified, no finding)

- Criterion 1: `git grep` finds `@ExceptionHandler(MailConnectionException::class)` only in `mail/api/MailConnectionAdvice.kt`. All four copies were removed (MailMessageController, MailAccountController, MailGtdController, and DraftController as the recorded assumption says). The imports and loggers that became unused were removed. The remaining imports are still used.
- Criterion 2 (behaviour): The advice body matches the removed handlers line for line: same status mapping (POOL_EXHAUSTED gives 503, everything else gives 502), same `detail` from `e.error.message`, same `errorCode` property, same `application/problem+json` content type, and the same INFO line (error code only) plus DEBUG line (cause). `title` and `type` still come from `ProblemDetail.forStatusAndDetail`, so they are unchanged. The only visible log difference is the logger category, which is now `MailConnectionAdvice` instead of each controller. The redaction test was updated to match.
- Precedence: `ProblemDetailsAdvice` has no `@Order`, so it runs at lowest precedence. `HIGHEST_PRECEDENCE` makes the scoped advice run before it, as the controller-local handlers did before, including Spring's cause-chain matching. Controller-local handlers that remain (`AttachmentDownloadAbortedException`, `MailSendRejectedException`, ...) still win over any advice.
- Scope widening: the advice now also covers MailActionController, MailSearchController and MailTestSupportController. The action and search services catch `MailConnectionException` themselves, so their responses do not change. Only the test-support sync endpoint (`/api/test/mail`) would now answer 502/503 instead of 500, which is harmless and matches the intent.
- Dependencies: controllers do not reference the advice, so it adds no new compile-time dependency between the mail subpackages.
- Log hygiene: no server response, credential or body is logged at INFO or above.
