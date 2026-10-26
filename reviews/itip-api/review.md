# Code Review

Date: 2026-10-26

Task: itip-api - API + web UI to send iTIP invitations for local events

Scope: the final revision of the task, `git diff 50d799687..5377b19e3`. Its six commits are 43711d4db (server endpoint), 46318b536 (web dialog), 5fea44596 (more tests), 0acb182e7 (fixes for CR-1 and CR-2), 158a6345c (one more spec test) and 5377b19e3 (200 response documented as JSON only). The generated `api/openapi.yaml` and `web/src/app/api/generated/` were checked against the controller but not reviewed line by line. This is a re-review. Earlier passes covered the code up to 5d1abde18, which after a rebase is 158a6345c with the same patch-id. This update adds 5377b19e3.

Requirements:
- tasks/itip-api.md acceptance criteria and its recorded assumptions (endpoint shape, no If-Match, 422/502/503 mapping, web placement and "save first" behavior)
- SPEC.md §6.2 (sending invites for LOCAL events by email), §4.2 (Problem Details, UUIDs)
- docs/ARCHITECTURE.md and AGENTS.md "Project notes" (module boundaries, logging rules, generated files)
- Parent instructions (MailConnectionAdvice is scoped to the mail package, so the controller handles mail exceptions itself)
- Existing contract test CalendarApiContentTypeTests ("the contract documents successful calendar responses as JSON only")

Git range:
- Branch: main
- HEAD: 5377b19e3241fe5eecfb2e1ac0bd67a67d066bcd
- Base/start: 50d799687a48adf11f8a4e90c0f8947be439aa8c
- Commits reviewed: 6

Summary: 0 P0, 0 P1, 0 P2, 0 P3 open (CR-1 and CR-2 from the first pass are resolved)

## Commands and results

- `git log --oneline 50d799687..HEAD`: the six commits listed under Scope.
- `git diff --stat 50d799687..HEAD`: 14 files, +1407/-2. Server: ItipController.kt (new), ItipApiTests.kt (new). Web: event-dialog.{ts,html,spec.ts}, invitation-dialog{,.spec}.ts (new). Plus the generated openapi and client.
- `git show 5377b19e3`: 3 files. ItipController.kt (+5/-1), api/openapi.yaml (-3), send-event-invitations.ts (1 line changed).
- Read in full in earlier passes: ItipController.kt, ItipService.kt, ItipApiTests.kt, invitation-dialog.ts, invitation-dialog.spec.ts, the relevant parts of event-dialog.ts and its new specs, MailConnectionAdvice.kt, DraftController send-rejected handler, MailSender.kt, MailSessions.credentials. Re-read for this pass: the ItipController annotations and imports, and CalendarApiContentTypeTests.
- `grep accept: web/src/app/api/generated/fn/calendars`: 13 calendar client functions send `accept: 'application/json'`, and none sends `application/problem+json`. The regenerated invitations client now matches them.
- I did not run Gradle or npm. Results reported by the parent and the test-writer:
    - On 5377b19e3: `server/gradlew -p server updateOpenApi ktlintCheck test --tests '*CalendarApiContentTypeTests' --tests '*ItipApiTests' --tests '*OpenApiContractTest'` passes, and the web calendar edit specs give 168 passed.
    - On 158a6345c (then 5d1abde18): `npm run coverage` gave 3789 passed.
    - On 0acb182e7: server ItipApiTests 7/7 pass, ktlintCheck is clean, and `npm run lint` passes.
    - On the first revision: ItipMessagesTests and ItipReplyProcessorTests pass, `api:check` is up to date and `ng build` works.

## Verification of acceptance criteria

1. POST endpoint, Problem Details, openapi regenerated: met.
    - `POST /api/v1/calendar/events/{id}/invitations` takes `{method, accountId, recipients?}`, validates them with Bean Validation and returns `{method, recipients}`.
    - The errors are Problem Details. 400, 404 and 409 come from the global advice through ItipService's ApiExceptions. 422 SEND_REJECTED and 502/503 with `errorCode` come from controller-local handlers, which mirror DraftController and MailConnectionAdvice.
    - The openapi diff matches the annotations: required fields, enum, minItems/maxItems, and every error response. Since 5377b19e3 the 200 response lists only `application/json` with the SendInvitationsResponse schema, and every error response lists `application/problem+json`.
    - ItipApiTests cover the 200 REQUEST path to every attendee except the organizer, a CANCEL to chosen recipients, and 400 for each validation case without sending (including more than MAX_ITIP_RECIPIENTS recipients). They also cover 404, 409 when there are no guests, 422, 502 (with a check that the cause text does not leak) and 503. The service tests cover the 409 for synced calendars.
2. Web event detail offers "Send invitations" for local events with attendees: met.
    - `invitable()` requires edit mode, not virtual, a writable LOCAL calendar and saved guests. The button is hidden while loading or after a failed load.
    - Unsaved changes block sending with a "save first" alert.
    - The specs confirm that the button is hidden for read-only local calendars. They also confirm that an override sends its own id and a generated occurrence sends the series master id, as the recorded assumption says.

## Review of 5377b19e3

- The controller keeps `produces = [JSON, PROBLEM_JSON]`, so error handlers can still answer with `application/problem+json`. Only the documented 200 content changed: it is now an explicit `Content(mediaType = APPLICATION_JSON_VALUE, schema = SendInvitationsResponse)`. This is what CalendarApiContentTypeTests requires for every 2xx response under `/api/v1/calendar/`.
- The regenerated openapi drops only the `application/problem+json` entry under 200. The 200 schema ref is unchanged.
- The generated client now sends `Accept: application/json` instead of `application/problem+json`. All other calendar client functions do the same. Spring's Jackson converter can write a ProblemDetail as `application/json`, so error bodies still parse, and the dialog's error handling does not depend on the response media type. The server tests that check `contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON)` on errors (ItipApiTests lines 164-195) still pass, as the parent reports.
- No behavior, logging or security-relevant code changed. I found no issues.

## Status of earlier findings

- **CR-1 (P3), resolved.** Failed account load showed "Add a mail account", with no retry.
    - The load error is now separate from the send error, and the dialog shows a "Try again" button.
    - Each load attempt resets the loading state and the error. Loads cannot overlap.
    - The specs cover an ApiError and a generic failure, a retry that works, and (since 158a6345c) a retry that fails again.
- **CR-2 (P3), resolved.** "To:" listed the organizer, who gets no mail.
    - The selected account's address is now left out of the list, compared case-insensitively. A spec covers this, including a change of account.

## Security and logging

- INFO logs contain only a count of rejected recipients or the MailConnectionError name. The exception cause is logged only at DEBUG. No message body, credentials or addresses are logged at INFO or above.
- The 502 detail is the fixed `MailConnectionError.message`. The test asserts that the cause text is not in the body.
- The endpoint sits under the authenticated `/api/v1` tree. The tests use CSRF the same way as the other endpoints.
- The later commits touch no logging or security-relevant code.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None open. CR-1 and CR-2 are resolved; see "Status of earlier findings".

Considered and not reported:
- The `MailConnectionException` handler duplicates MailConnectionAdvice, and the `MailSendRejectedException` handler duplicates DraftController's. This is documented in the code and follows the existing pattern, and widening the mail advice to the calendar package would couple the modules. Acceptable as is.
- Suppose the user's only guest is the selected account's own address. Then the "To:" line is empty, and the server answers 409, which the dialog shows inline. This is recoverable, so it is not a finding.

Correction of the earlier review: the previous version listed "the 200 response also lists `application/problem+json`" as acceptable, because AuthController and others do the same. That was wrong for this module. The existing contract test CalendarApiContentTypeTests requires calendar success responses to be documented as JSON only, and it failed on this endpoint in `.taskcheck`. The earlier review missed that test. 5377b19e3 fixes the contract, so nothing is open.

## Review conclusion

No material issues found. The final revision 5377b19e3 meets both acceptance criteria. Both P3 findings from the first pass are resolved and tested, and the invitations contract now passes the calendar JSON-only content-type rule.
