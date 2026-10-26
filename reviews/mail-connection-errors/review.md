# Code Review

Date: 2026-10-26

Task: mail-connection-errors - Shared MailConnectionException handler for mail controllers

Scope: final task code, `git diff 9468e8e0..HEAD -- server` (the three code commits; the commits that only touch `reviews/` and `tasks/` were excluded)

Requirements:
- tasks/mail-connection-errors.md (acceptance criteria and the two recorded assumptions)
- AGENTS.md project notes (Problem Details errors, log hygiene, lint, module conventions, test data is fictitious)
- Existing conventions in server/src/main/kotlin/ch/sonensei/cockpit/core/web/ProblemDetailsAdvice.kt and the existing `PostgresTestBase` Spring tests
- Previous review of this task (CR-1, no HTTP-level test for MailAccountController)

Git range:
- Branch: main
- HEAD: 8ff9fde5732a34a1acf2dfda038a1dd62cf050f3
- Base/start: 9468e8e0454ca451fe469edeb5d4ea50ef4d106a
- Commits reviewed: 3 code commits (50134a4d, 471762e1, 24615432, which are the rebased af3c5ccb, b5ea3a54, 953306f0); 3e38740e and 8ff9fde5 only add files under reviews/. The working tree had no uncommitted changes.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Previous finding CR-1 (resolved)

`server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailConnectionAdviceWebTests.kt` is a `@SpringBootTest` + `@AutoConfigureMockMvc` test that runs in the real application context, so the real `ProblemDetailsAdvice` catch-all is present. It sends one request to each of the four controllers that used to have their own handler. For MailAccountController this is `POST /api/v1/mail/accounts/{id}/folders/sync`, which calls `folders.sync(id)` at MailAccountController.kt:233-235. I checked each stub against the real call site: `reads.read(id, remoteImages)`, `folders.sync(id)`, `gtd.toTask(id)` and `drafts.send(id, ifMatch)`. The test runs every `MailConnectionError` value through all four controllers. It asserts the status (503 or 502), `application/problem+json`, `status`, `title`, the absent default `type`, `detail` and `errorCode`, and checks that the fictitious cause text does not appear in the body. It also checks that other exceptions still reach the global advice (404 and 500, with no `errorCode`). This test would fail if the advice's `basePackages` or precedence changed, so it covers the gap CR-1 described.

## Notes (verified, no finding)

- Criterion 1: `@ExceptionHandler(MailConnectionException::class)` now appears only in `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailConnectionAdvice.kt:26`. All four copies were removed: MailMessageController, MailAccountController, MailGtdController, and DraftController. Removing DraftController's copy is covered by the recorded assumption. Imports and loggers that became unused were removed. The ones left (for example `HttpStatus`, `ProblemDetail`, `ExceptionHandler` and `log` in DraftController for `MailSendRejectedException`, and `HttpStatus` and `MailConnectionError` in MailAccountController) are still used.
- Criterion 2: The advice body is the same as the removed handlers line for line: POOL_EXHAUSTED gives 503 and everything else 502, the `detail` is `e.error.message`, the `errorCode` property and the `application/problem+json` content type are the same, and so are the INFO line (error code only) and the DEBUG line (cause). Title and type still come from `ProblemDetail.forStatusAndDetail`. The only visible difference is the logger category, which is now `MailConnectionAdvice`. MailSecretRedactionTests was updated to capture that logger, so the INFO-redaction check still observes the real log output.
- Precedence: `@Order(HIGHEST_PRECEDENCE)` together with `basePackages = ["ch.sonensei.cockpit.mail"]` puts the advice ahead of the unordered global advice, but only for mail controllers. Controller-local handlers that remain (`AttachmentDownloadAbortedException`, `MailSendRejectedException`) still take priority over any advice.
- Scope widening: the advice also applies to the other controllers in the mail package (action, search, test support). Their responses either stay the same or change from a generic 500 to the intended 502/503. The previous review noted this as acceptable, and nothing in the final code changes that.
- Standalone MockMvc tests (MailMessageControllerTests, DraftControllerTests) now register `MailConnectionAdvice` explicitly. This replaces the controller-local handlers they used to rely on, and no test assertions were weakened or removed.
- Security and log hygiene: no credentials, server responses or mail content are logged at INFO or above. Test data is fictitious (`example.test`, `fictitious-server-secret`).
- Test style: the new Spring test follows the existing `PostgresTestBase` + `@MockkBean` pattern used by other server web tests. The mail suite passing at 1299 tests was taken from the parent's report and was not re-run for this review.

## Review conclusion

No material issues found.
