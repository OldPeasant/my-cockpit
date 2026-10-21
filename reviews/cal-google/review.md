# Code Review

Date: 2026-10-21

Task: cal-google - Google Calendar adapter (SPEC.md §6.2), third and final review round

Scope: `git diff f65037ded7c52736372b283ae7257257a35599ae..5029346c -- server`, committed changes only. The full task diff was re-checked against the criteria, with the focus on the delta since round 2 (`git diff f798eb17..5029346c -- server`: commit 10de4782 with the round-2 test-writer's tests, and commit 5029346c with the KDoc re-wrap and the removal of `GoogleEventMapping.isInstance`). Uncommitted test changes in the working tree come from a test-writer running in parallel and were not reviewed.

Requirements:
- tasks/cal-google.md acceptance criteria 1-3, and the recorded assumptions on the reminder mapping (round-1 CR-2) and the attendee mapping (round-2 CR-1)
- SPEC.md §6.2 and §4.2
- AGENTS.md project notes (module boundaries, no secrets or event content in logs or messages)
- The sync engine contract (`calendar/sync/CalendarProviderAdapter.kt`, `CalendarWriteThrough.kt`, `RemoteEventMapping.kt`)
- The earlier reports `reviews/cal-google/review.md` (round 2), `tests.md` and `fixes.md`
- The parent agent's instructions

Git range:
- Branch: main
- HEAD: 5029346c (scope given by the parent). The local branch has since been rebased onto cffbf776 (an `assume` commit) and is now at aab965924f48cc4efd89d59de40a31dc461e81c8. `git diff --stat 5029346c aab96592 -- server` is empty, so the reviewed server code is identical at both hashes.
- Base/start: f65037ded7c52736372b283ae7257257a35599ae
- Commits reviewed: the task commits 35f2550e..5029346c (rebased as e64b55a2..aab96592). New since round 2: 10de4782 and 5029346c.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Verification of round-2 findings and the delta

- **Round-2 CR-1 (attendee list replaced on every patch): accepted.** Two assumption notes in tasks/cal-google.md record it (12:45 and 13:14). `GoogleEventMapping.toBody` is unchanged (`GoogleEventMapping.kt:130-137`). This finding is not reopened.
- **Round-2 CR-2 (garbled `toRemote` KDoc): fixed.** The KDoc at `GoogleEventMapping.kt:48-54` now has a sentence on the CANCELLED status for write answers, then a separate paragraph on `calendarZone`/`defaultReminders`. It is wrapped like the rest of the file.
- **Removal of `isInstance`:** `git grep isInstance 5029346c -- server` finds no other use. The one test assertion that used it was removed with it, and the same test still asserts `isCancelled` and `key` for a cancelled instance. Nothing else changed in production code since round 2.
- **New test `a source whose connection was removed fails as removed, not as unlinked, before calling Google`** (`GoogleCalendarAdapterTests`): `FixedTokens.removed` makes `connection()` return null. So `connectionId()` gets past the provider check (`GoogleCalendarAdapter.kt:301-302`), and the failure comes from the token request with "was removed". The test checks the message, checks that "not linked" is absent, and checks that WireMock got zero requests. This covers the documented rule "a missing one fails when its token is requested" (`GoogleCalendarAdapter.kt:287-290`), which had no test before.
- **New test `the status is read as tentative or cancelled, anything else is confirmed`** (`GoogleEventMappingTests`): it covers every branch of the status mapping in `toRemote` (`GoogleEventMapping.kt:74-80`), including a missing status and an unknown one. The JSON it builds is valid in both the with-status and without-status forms.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Notes (not findings)

- I re-confirmed the round-2 conclusions on the unchanged adapter code (`GoogleCalendarAdapter.kt` has the same content at f798eb17 and 5029346c):
    - calendar list with paging, skipping deleted and freeBusyReader calendars, and the read-only flag from `accessRole`;
    - `sync:` / `page:` / `full:` cursors;
    - 410 restarts with a complete listing during an incremental run or its pages, and is an error during a full listing, so it cannot loop;
    - writes send `If-Match`, and 412 (and 404/410 on update) becomes `RemoteEventChangedException`;
    - instance ids for exceptions;
    - the "this and following" split runs as an UNTIL patch, an insert with a UUID-derived id, and an instance patch;
    - providers other than GOOGLE are refused before any token is requested.
- Security: the delta adds no logging, messages or network paths. No secrets or event content appear in the changed code or the recordings.
- `python tasks.py verify cal-google` still shows criteria 1-3 as unverified. The parent worker must record evidence, including an actual run of `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.google.*'` (Testcontainers Postgres for `GoogleCalendarSyncTests`). The reviewer did not run the suite.
- Because of the rebase, `reviewed_revision` in `quality.json` should be the final HEAD hash. The server code is identical, so this review stays fresh (docs/QUALITY.md "Freshness").

## Review conclusion

No material issues found.
