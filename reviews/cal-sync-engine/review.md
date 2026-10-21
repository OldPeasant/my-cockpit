# Code Review

Date: 2026-10-21

Task: cal-sync-engine - Calendar sync engine

Scope: Final re-review of the final code. The scope is the task commits in `git log 70342ac8ffe1424392fe1349185a8ad173543648..b70efd14` with "cal-sync-engine" in the subject: 5cad3296, 0d73c50c, 2930c32e, b904da97, 5489a214, e23e97bf, 470124b5, 4b473e41, e123d6eb, 4c125b5a, bfab6fd9, c172e0d9, fb5b7ca5, b70efd14. The focus is on the last code change, c172e0d9:
- An invalid provider answer to a write-through now returns 502.
- A new test documents the accepted lossy write-back (previous CR-1).
- The refetch comment was corrected (previous CR-2).

I re-checked the rest of the task diff for interactions with this change and for remaining issues.

Requirements:
- tasks/cal-sync-engine.md: acceptance criteria 1-6 and the recorded assumptions, including the 02:02 assumptions by dell26/agent-6 that accept CR-1, defer CR-2, and map an invalid provider answer to rollback + 502
- SPEC.md §6.2, §4.2
- docs/ARCHITECTURE.md, AGENTS.md project notes, docs/QUALITY.md
- docs/adr/0002-calendar-write-through-before-commit.md
- Previous review (reviews/cal-sync-engine/review.md, revision 4c125b5a), fixer report (reviews/cal-sync-engine/fixes.md, Round 2), test report (reviews/cal-sync-engine/tests.md)

Git range:
- Branch: main
- HEAD: b70efd14
- Base/start: 70342ac8ffe1424392fe1349185a8ad173543648
- Commits reviewed: 14 task commits (listed above). The working tree was clean when the review started.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Status of the previous findings

- **CR-1 (P2, lossy write-back after normalization): accepted by assumption, and the docs and test match.**
    - The task file records the assumption: normalization is lossy, a later local edit writes the normalized event back, and no raw remote copy is kept.
    - The KDoc of `RemoteEvent.normalized()` (`RemoteEventMapping.kt:21-30`) describes what is dropped and cut.
    - The new test in `CalendarSyncOverrideTests.kt:350-371` ("a local edit of a normalized event writes the normalized fields back to the provider (accepted by assumption)") asserts what reached the fake adapter: the new location, no `tel:` conference URL, only the valid attendee and only the valid reminder.
    - The behavior is exactly as described in the assumption, and nothing worse.
- **CR-2 (P3, `tryLock` refetch skip): deferred by assumption, and the comment now matches.**
    - The task file records the deferral.
    - The comment at `CalendarSyncService.kt:146-147` now says that a running sync may already have fetched the calendar, so the new version arrives with the next poll.
    - The behavior is unchanged. The effect is still limited to an extra 409 and convergence on the next poll, as described.
- **Test-writer finding (500 instead of 502 for a provider answer that cannot be stored): resolved.**
    - `CalendarWriteThrough.kt:151-155` wraps `event.copyFrom(stored)` and turns an `IllegalArgumentException` into a `CalendarProviderException`.
    - The existing handler (`:164-166`) then logs it at WARN and returns 502 Problem Details through `providerFailed`.
    - The exception is raised in `beforeCommit`, so the transaction rolls back.
    - The messages come from `normalized()` (`RemoteEventMapping.kt:33-37`). They are static texts without event content, so the WARN log and the 502 `detail` expose no titles, descriptions or addresses. This complies with the AGENTS.md logging rule.
    - `normalized()` runs before `copyFrom` changes any field (`RemoteEventMapping.kt:70`), so `setTiming` cannot throw on a half-applied entity. A rollback would discard the entity anyway.
    - The wrap only covers `copyFrom`. An `IllegalArgumentException` raised in the adapter call itself is not reclassified.
    - The test at `CalendarSyncOverrideTests.kt:401-412` covers it (502, local title unchanged).
- **Round 1 CR-1, CR-2, CR-3 and CR-5:** still resolved, as confirmed in the previous review. Nothing in c172e0d9 touches them.
- **Round 1 CR-4:** still deferred by the recorded assumption. Not re-reported.

## Verification performed

- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.sync.*'` -> BUILD SUCCESSFUL. The four sync test classes ran: 25 + 18 + 31 + 17 = 91 tests, 0 failures, 0 errors, 0 skipped (per `build/test-results/test/TEST-ch.sonensei.cockpit.calendar.sync.*.xml`).
- I did not run the wider `calendar.*` suite, `OpenApiContractTest` or `.taskcheck` again. tests.md reports them passing for this exact code: 337 calendar tests, OpenApiContractTest passes, `.taskcheck` passes. c172e0d9 changes no API surface, so `api/openapi.yaml` is unaffected.
- Acceptance-criterion evidence (`tasks.py verify`) is the parent's job and was not inspected or changed here.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. The final change correctly maps an unstorable provider answer to rollback + 502 without leaking event content. The accepted CR-1 and deferred CR-2 match their recorded assumptions, and the documentation test and comment match them too. The sync suite passes (91 tests).
