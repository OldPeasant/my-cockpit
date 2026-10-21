---
title: Calendar sync engine
status: in_progress
owner: dell26/agent-6
deps: [cal-local-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar/sync,server/src/test/kotlin/ch/sonensei/cockpit/calendar/sync]
prio: 1
lease_until: 2026-10-21T00:37:56Z
extended: 
releases: 1
---
## Goal
Provider-independent two-way sync framework (SPEC.md §6.2).

## Acceptance criteria
- [ ] `CalendarProviderAdapter` interface (list calendars, incremental fetch with a sync cursor, create/update/delete with etag) + registry by source type
- [ ] Scheduler polls each source at its interval, stores cursor/last sync/error; write-through: local edits of external events go to the adapter first; 412 → refetch and return 'changed remotely' (409 to the client)
- [ ] `GET /api/v1/calendar/sources` incl. sync status; `POST …/{id}/sync`
- [ ] Tests with a fake adapter: incremental merge, deletions, conflict path
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.sync.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
- ASSUMPTION (2026-10-21 00:42+0200 by dell26/agent-2): Sync status (last attempt/success/error) and per-calendar cursors live in new tables calendar_source_sync and calendar_sync_cursor (migration in db/migration/calendar, outside the task's touches), so a sync never bumps the ETag of sources/calendars.
- ASSUMPTION (2026-10-21 00:42+0200 by dell26/agent-2): Write-through runs in the event transaction's beforeCommit: local change is flushed but uncommitted, pushed to the adapter, then the local copy takes the provider's answer; any failure rolls back. 412 -> 409 'changed remotely' plus a refetch of that calendar after rollback; other provider failures -> 502 Problem Details.
- ASSUMPTION (2026-10-21 00:42+0200 by dell26/agent-2): Deleting an event of a synced calendar deletes it at the provider and locally for good (no cockpit Trash); events and calendars removed at the provider are also deleted for good with their links.
- ASSUMPTION (2026-10-21 00:42+0200 by dell26/agent-2): Events cannot move between a synced calendar and any other calendar (409); a remote deletion of one occurrence becomes an EXDATE; remote calendar color only applies on creation (user may recolor locally).
- ASSUMPTION (2026-10-21 00:42+0200 by dell26/agent-2): Scheduler ticks every 30 s (cockpit.calendar.sync.tick) and syncs sources whose adapter pollInterval passed since the last attempt; POST /sources/{id}/sync runs synchronously, returns 200 with the status (errors in sync.lastError), 409 for LOCAL or types without adapter. GET /sources omits config (ICS URLs may hold tokens) and credentials.
- extended by dell26/agent-2: Implementation, API, openapi, ADR 0002, fake-adapter tests and code review (reviews/cal-sync-engine/review.md) are committed. Left: (1) fix review CR-2 (normalize/validate remote events; WIP test CalendarSyncOverrideTests 'an invalid remote change of a known event is skipped...' fails at line 209: Broken vs Standup) and confirm CR-1 is fixed (other override tests pass); decide on P3 CR-3..CR-5; (2) re-run test-writer + code-reviewer on final code, fixer report if needed; (3) tasks.py verify for all 6 criteria, quality.json, done. Note: core ClockConfigurationTests failed once on nanosecond precision (flaky, unrelated to this task).
- ASSUMPTION (2026-10-21 01:26+0200 by dell26/agent-2): Moved isWebUrl/ruleParts/isValidRule/isEmail into CalendarEventService companion (outside touches) so sync normalization reuses API validation; CR-4 (brief revert of an edit by a sync fetched before write-through) deferred, converges on next poll
- release by dell26/agent-2: Done: adapter interface+registry, scheduler, write-through (beforeCommit), sources API + POST sync, openapi regenerated, ADR 0002, fake-adapter tests, review round 1 fixed (CR-1,2,3,5; CR-4 deferred by assumption), re-review in reviews/cal-sync-engine/review.md. Last commit 81b1c739 adds test-writer re-run tests (uncommitted tests.md not yet written). Sync suite: 90 tests, 1 fails: CalendarSyncOverrideTests 'a provider answering a write-through with an event it cannot be stored as fails the edit with 502...' gets 500 -> production bug: normalization failure of the provider's answer in CalendarWriteThrough must map to 502 Problem Details. Left: (1) fix that; (2) re-review CR-1 (P2): after normalization drops/truncates remote fields (attendees, reminders, conference URL, description), a later local edit writes the reduced event back to the provider - fix (e.g. keep raw remote fields / only send changed fields) or record an assumption + test documenting it; CR-2 (P3, tryLock refetch skip) at discretion; (3) fixer report, then re-run test-writer (writes tests.md incl. coverage) and code-reviewer on final code; (4) tasks.py verify 1-6, quality.json, .taskcheck, done.
- ASSUMPTION (2026-10-21 02:02+0200 by dell26/agent-6): Review CR-1 accepted: normalization of remote events (drop invalid attendees/out-of-range reminders/non-http conference URLs, truncate text to API limits) is lossy and a later local edit writes the normalized event back to the provider; no raw remote copy is kept. Rare in practice; documented by a test in CalendarSyncOverrideTests. Revisit if providers' tel:/sip: dial-ins matter.
- ASSUMPTION (2026-10-21 02:02+0200 by dell26/agent-6): Review CR-2 (P3) deferred: a 412 refetch that finds a sync of the same source running is skipped; if that run already fetched the calendar, the new remote version only appears with the next poll (converges, no data loss).
- ASSUMPTION (2026-10-21 02:02+0200 by dell26/agent-6): A provider answer to a write-through that cannot be stored (normalization fails) is treated as a provider failure: rollback + 502.
