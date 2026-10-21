# Code Review

Date: 2026-10-21

Task: cal-google - Google Calendar adapter (SPEC.md §6.2), third and final review round (updated for cc5e7a7d)

Scope: `git diff f65037ded7c52736372b283ae7257257a35599ae..cc5e7a7d5a3d1e63789350de671b80766d9b7b8e -- server`, committed changes only. I re-checked the full task diff against the criteria. Two deltas got the closest review:
- Since round 2: commit 10de4782 (round-2 test-writer tests) and commit 5029346c (KDoc re-wrap, removal of `GoogleEventMapping.isInstance`). After the rebase these are f26864a9 and aab96592.
- Since the first version of this round-3 report: `git diff aab96592..cc5e7a7d -- server`, which adds one test.

Uncommitted working-tree changes were not reviewed.

Requirements:
- tasks/cal-google.md acceptance criteria 1-3, and the recorded assumptions on the reminder mapping (round-1 CR-2) and the attendee mapping (round-2 CR-1)
- SPEC.md §6.2 (including "fallback poll every 5 min", SPEC.md:325) and §4.2
- AGENTS.md project notes (module boundaries, no secrets or event content in logs or messages)
- The sync engine contract (`calendar/sync/CalendarProviderAdapter.kt`, `CalendarWriteThrough.kt`, `RemoteEventMapping.kt`)
- The earlier reports `reviews/cal-google/review.md` (round 2), `tests.md` and `fixes.md`
- The parent agent's and the coordinator's instructions

Git range:
- Branch: main
- HEAD: cc5e7a7d5a3d1e63789350de671b80766d9b7b8e
- Base/start: f65037ded7c52736372b283ae7257257a35599ae
- Commits reviewed: the task commits e64b55a2..cc5e7a7d (11 commits after the `assume` commit cffbf776). The pre-rebase hashes were 35f2550e..5029346c, and `git diff --stat 5029346c aab96592 -- server` is empty, so the rebase changed no server code. The only commit after aab96592 is cc5e7a7d. In `server` it touches only `GoogleCalendarAdapterTests.kt`; its other changes are in `reviews/cal-google/`.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Verification of round-2 findings and the deltas

- **Round-2 CR-1 (attendee list replaced on every patch): accepted.** Two assumption notes in tasks/cal-google.md record it. `GoogleEventMapping.toBody` is unchanged (`GoogleEventMapping.kt:130-137`). This finding is not reopened.
- **Round-2 CR-2 (garbled `toRemote` KDoc): fixed.** The KDoc at `GoogleEventMapping.kt:48-54` now has one sentence on the CANCELLED status for write answers and a separate paragraph on `calendarZone`/`defaultReminders`, wrapped like the rest of the file.
- **Removal of `isInstance`:** `git grep isInstance` finds no other use in `server`. The one test assertion that used it was removed with it, and the same test still asserts `isCancelled` and `key` for a cancelled instance.
- **New test `a source whose connection was removed fails as removed, not as unlinked, before calling Google`** (10de4782): `FixedTokens.removed` makes `connection()` return null. `connectionId()` therefore passes the provider check (`GoogleCalendarAdapter.kt:301-302`), and the failure comes from the token request with "was removed". The test asserts:
    - the message;
    - that "not linked" is absent;
    - zero WireMock requests.

  This covers the documented rule at `GoogleCalendarAdapter.kt:287-290`.
- **New test `the status is read as tentative or cancelled, anything else is confirmed`** (10de4782): it covers every branch of the status mapping in `toRemote` (`GoogleEventMapping.kt:74-80`), including a missing status and an unknown one.
- **New test `the adapter serves Google sources and falls back to polling every 5 minutes`** (cc5e7a7d, `GoogleCalendarAdapterTests.kt`, "registration" section): it asserts `sourceType == CalendarSourceType.GOOGLE` and `pollInterval == Duration.ofMinutes(5)`. Both match `GoogleCalendarAdapter.kt:51-52`. The interval matches the SPEC.md §6.2 "fallback poll every 5 min", and the source type is what the engine's adapter registry selects by.
    - The added `java.time.Duration` import is placed in sorted order, and `CalendarSourceType` was already imported.
    - The test pins a SPEC-defined value, so it checks behavior rather than implementation detail. It is small and has no side effects on the shared WireMock fixture.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Notes (not findings)

- No production code changed since round 2 except the KDoc and the `isInstance` removal. `GoogleCalendarAdapter.kt` is the same at f798eb17 and cc5e7a7d. The round-2 conclusions on criteria 1-2 still hold:
    - calendar list with paging and filtering;
    - `sync:` / `page:` / `full:` cursors;
    - 410 starts a complete listing during an incremental run, and is an error during a full listing (no loop);
    - `If-Match` on writes, with 412 (and 404/410 on update) becoming `RemoteEventChangedException`;
    - instance ids for exceptions;
    - the "this and following" split;
    - non-Google connections refused before a token is requested.
- Security: the deltas add no logging, messages or network paths. No secrets or event content appear in them.
- `python tasks.py verify cal-google` still showed criteria 1-3 as unverified when checked. The parent worker must record evidence, including an actual run of `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.google.*'` (Testcontainers Postgres for `GoogleCalendarSyncTests`). The reviewer did not run the suite.
- `reviewed_revision` in `quality.json` should be cc5e7a7d5a3d1e63789350de671b80766d9b7b8e, or a later hash that changes only `tasks/`/`reviews/`.

## Review conclusion

No material issues found. The test added in cc5e7a7d is correct, matches SPEC.md §6.2 and the adapter, and needs no changes.
