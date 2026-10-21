# Code Review

Date: 2026-10-21

Task: cal-invitations - Respond to invitations

Scope: third (final) review round. Whole final change of the task: git diff ff1970a6c3d47ae575d708aebabe0070b24dc6ec..637f7b93065226f37d4112c689151285d0f42162, excluding tasks/ and reviews/. Committed changes only. Generated files (api/openapi.yaml, web/src/app/api/generated/) were checked for consistency only. Focus: the tests added in 637f7b930.

Requirements:
- tasks/cal-invitations.md acceptance criteria and recorded assumptions
- SPEC.md section 6.2 (invitations) and section 4.2 (ETag/If-Match, Problem Details)
- AGENTS.md project notes, docs/QUALITY.md (report contract)
- Round-2 report (no findings) and reviews/cal-invitations/tests.md
- Test results reported by the parent worker at this code: `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.calendar.*' --tests '*OpenApiContractTest' jacocoTestReport` -> BUILD SUCCESSFUL, 897 tests, 0 failures. The reviewer did not re-run them.

Git range:
- Branch: main
- HEAD: 637f7b93065226f37d4112c689151285d0f42162
- Base/start: ff1970a6c3d47ae575d708aebabe0070b24dc6ec
- Commits reviewed: 6 (e62513cbe, e1266aa88, 5b74f6991, f89d7ee23, b69ed2556, 637f7b930)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Changes since round 2

- Production code, generated files and the API contract are unchanged since the code reviewed in round 2. Compared with that round's HEAD (654f0968f, before the rebase), the tree diff outside tasks/ and reviews/ is empty up to b69ed2556. Commit 637f7b930 changes only two test files and tests.md.
- The round-2 conclusions on production code still apply.

## New tests (637f7b930)

- `InvitationApiTests` "declining a series that the refetch then removes ...":
    - The test runs the fallback branch of `InvitationService.respond` (`findActiveById(id) ?: event`). The refetch with syncToken S2 lists series01 as cancelled. The test then checks three things: the request was 200 and returned the saved answer (DECLINED); the refetch request was made; the master and override were removed.
    - Before this commit, no test covered that branch. The test is correct and useful.
- `InvitationApiTests` "an answer Google returns as an unstorable event is a bad gateway ...":
    - The RRULE without FREQ makes `copyFrom` fail inside the save transaction. The test asserts the "invalid event" detail, so it checks this exact path, not some other 502.
    - It also checks that the transaction rolled back: the attendee status, external etag and rrule are unchanged, and no change events were published. `invitationEvent()` is a plain repository lookup, so this read sees the stored state.
    - The test is correct and covers the `IllegalArgumentException` -> 502 mapping.
- `MicrosoftInvitationAdapterTests` "sync gives the user's answer from responseStatus to the user's attendee only ...":
    - I checked the fixture string replacements against the `invitation(...)` template, and they match exactly: `"attendees": [` and `"responseStatus": {"response": "declined"}`. So both parts of the test test something real.
    - If `responseStatus` were applied to every attendee without an answer, Kim's status would fail. If `responseStatus` overrode a real answer in the attendee list, the second part would fail. This pins the precedence recorded in the task assumption.
    - Both delta calls start from a null delta link and hit the same URL. WireMock serves the most recent stub, so the second fetch reads the second body as intended.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.

Re-checked for the whole task diff:
- Criterion 1: `POST /api/v1/calendar/events/{id}/respond` sends the answer through the provider adapter. Google sends a PATCH of the `self` attendee with If-Match and `sendUpdates=all`. Microsoft calls the accept/decline/tentativelyAccept action with `sendResponse=true`. The local event then takes the provider's version.
- Error mapping:
    - 428 when If-Match is missing, 412 when it does not match.
    - 409 for organizer, non-attendee, read-only, unsynced or unsupported calendars, and for a change at the provider (followed by a refetch).
    - 404 when the event, or the series of an override, is in the Trash.
    - 502 Problem Details for provider failures and unstorable answers.
- The provider call runs outside any transaction. The local save is atomic. A failed refetch is only logged.
- No secrets or event contents are logged.
- `api/openapi.yaml` and the generated web client match the controller.

The round-2 observation about Graph precedence still applies. When the attendee list and `responseStatus` disagree, the attendee list wins. The new Microsoft test now pins this documented behavior. The owner should revisit it if real Graph data shows otherwise.
