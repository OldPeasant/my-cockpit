# Code Review

Date: 2026-10-21

Task: cal-microsoft - Microsoft Graph calendar adapter

Scope: committed changes of task cal-microsoft, `git diff 8e5e5540..d6768df0 -- server/` (adapter, Graph client, mapping, recurrence, Windows zones, WireMock recordings, adapter/mapping/recurrence/zone/sync tests). Re-review of the final code after the fixes for the first review's findings. No uncommitted changes.

Requirements:
- tasks/cal-microsoft.md acceptance criteria 1-3 and its recorded assumptions (window 365/730 days with a weekly relisting, relisting on any removal, derived EXDATEs, diff-only updates, zone mapping)
- SPEC.md §6.2 (Graph delta queries, remote is the source of truth, 412 is "changed remotely", THIS/FOLLOWING/ALL mapped to provider semantics)
- Adapter contract `calendar/sync/CalendarProviderAdapter.kt`; engine use in `CalendarSyncService.kt` (paging, merge, removeMissing), `CalendarWriteThrough.kt` (write order, If-Match, 409/502 mapping), `api/CalendarEventService.kt` (splitSeries, updateSeries, carryExceptions, endSeriesBefore)
- Previous review CR-1 to CR-6 (superseded by this report), reviews/cal-microsoft/fixes.md, reviews/cal-microsoft/tests.md
- AGENTS.md conventions (no secrets or event content in logs or user-visible messages, lint), docs/QUALITY.md

Git range:
- Branch: main
- HEAD: d6768df0
- Base/start: 8e5e55401eeeb1f82be22b4e6cd95201065e9b1d (`git merge-base HEAD origin/main`)
- Commits reviewed: 7 (0ac19687, 5dcb94ba, e00161a9, 4d660771, 8c6e0a5c, cb96c5b9, d6768df0), all "cal-microsoft:"

Commands run:
- `git log --oneline 8e5e5540..HEAD`, `git diff --stat 8e5e5540..HEAD -- server/` -> 7 commits, 23 files under `server/`, 5231 insertions
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.microsoft.*'` -> BUILD SUCCESSFUL, test task executed; JUnit results: GraphEventMappingTests 44, GraphRecurrenceTests 37, MicrosoftCalendarAdapterTests 55, MicrosoftCalendarSyncTests 4 (Postgres via Testcontainers), WindowsZonesTests 20; 160 tests, 0 failures, 0 errors, 0 skipped

Status of the previous findings:
- CR-1 (FOLLOWING split loses earlier exceptions): resolved. `updateEvent` reads the series' exceptions in the window before a `recurrence` PATCH, and `restoreExceptions` re-patches those that Graph turned back into plain occurrences and that the new pattern still has (MicrosoftCalendarAdapter.kt:339-357, 437-460). Covered by WireMock tests (restore with the old fields, no restore after the split point, best effort on failure). In the engine's FOLLOWING flow, the later overrides are deleted at Graph before the master PATCH (CalendarWriteThrough order), so only the earlier ones are restored. This is correct. See the new CR-1 below for a follow-on effect on ALL edits.
- CR-2 (events addressed via `/me/events`): resolved. Every event read, instance listing, PATCH and DELETE uses `/me/calendars/{calendar}/events/{event}` (EVENT, eventPath). Tested, including a shared calendar.
- CR-3 (exception moved more than 35 days away): resolved. `findInstance` falls back to the whole window (lines 407-418). Tested for delete and update.
- CR-4 (attendee types reset to required): resolved by `keepAttendeeTypes` (GraphEventMapping.kt:204-221). Unit and WireMock tests cover it, including case-insensitive matching.
- CR-5 (unbounded calendar paging): resolved. The MAX_PAGES guard in `listCalendars` is tested.
- CR-6 (formatter-mangled conditionals): resolved (MicrosoftCalendarAdapter.kt:118-120, GraphEventMapping.kt:152-155).

Summary: 0 P0, 0 P1, 1 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | A series (ALL) change of the pattern fails with 409 when the series has overrides, although Microsoft has already applied it**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/MicrosoftCalendarAdapter.kt:355` (restoreExceptions) and `:396-400` (writeInstance If-Match), together with `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarEventService.kt:405-411`
    - Category: Integration
    - Problem: For an ALL edit that changes the rule but keeps an override's occurrence (for example a new end date, an extra weekday or a COUNT change), `updateSeries`/`carryExceptions` keeps the override and records it as UPDATED. CalendarWriteThrough writes the master first: `updateEvent` PATCHes `recurrence`, and by the adapter's own premise Exchange drops the exceptions. `restoreExceptions` then re-patches them, which gives each instance a new etag. The override is written next with `ifMatch = override.externalEtag`, which is the etag of the old exception. `writeInstance` finds the restored instance. If the diff is empty, it throws `RemoteEventChangedException` because the etags differ (line 397). Otherwise the PATCH is sent with the stale If-Match and Graph answers 412. Without a restore, the instance is a plain occurrence with a new etag, so the result is the same. The client gets 409 "changed remotely, please retry" and the local transaction rolls back. But the master PATCH and the restores have already been committed at Microsoft, and the refetch then shows the change the user was told had failed. The same stale etag stays on overrides restored by a FOLLOWING split until the next sync. An edit of such an override within that time gets a spurious 409. No test runs an ALL rule change on a series with overrides through the engine (MicrosoftCalendarSyncTests covers FOLLOWING without restore, plus create and 412).
    - Why it matters: AC1 names writes and occurrence edits. Changing the recurrence of a Microsoft series that has any edited occurrence reliably reports a failure that did not happen. This misleads the user and invites a retry.
    - Suggested fix: Make the follow-up override write after a pattern change succeed. One option: let `writeInstance` accept an instance this adapter restored in the same write batch, for example by remembering, per series, the etags `restoreExceptions` produced and treating a write whose diff is then empty as current. Another option: treat an empty diff as success whatever the etag is (nothing is overwritten). Document whichever option is chosen, or record the limitation with `tasks.py assume`. Add a sync test (engine + WireMock): an ALL rule change on a series with an override in the window ends in 200, and the override keeps Graph's new etag.

## P3 Low

- [ ] **CR-2 | A series deleted at Microsoft gives 502 instead of "changed remotely" when one of its occurrences is written**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/MicrosoftCalendarAdapter.kt:242-252` (used by `findInstance` at 407-418)
    - Category: Correctness
    - Problem: `instances()` passes the `/instances` response to `expectOk`, so a 404 (the series master no longer exists) becomes a plain `CalendarProviderException("Fetching the occurrences of a series failed at Microsoft (HTTP 404)")`. Other paths treat 404 as a remote change: the master GET in `updateEvent` (line 332), `patch` (503) and `delete` (518). The contract also says that deleting something the provider no longer has succeeds. When a series was deleted in Outlook and the cockpit has not synced yet, several paths reach `findInstance` and return 502 with no refetch: a THIS edit or cancel of an occurrence, the override deletions of a FOLLOWING split or an ALL move, and `deleteEvent` with a recurrence id. Retries keep failing until the next 5-minute poll removes the series.
    - Why it matters: The user gets a "provider failed" error instead of the intended 409 plus refresh. A delete of something that is already gone is not reported as a success.
    - Suggested fix: In `findInstance` (or `instances`), map 404 to "no instance". Then `updateEvent` throws `RemoteEventChangedException`, `deleteEvent` returns null and `createEvent` fails as today. Add a WireMock test with a 404 from `/instances`.

Checked and not reported:
- Delta paging and cursor: the restart conditions (no cursor, RESTART, links older than 7 days) are correct. 410 starts a full listing only outside a full one. A removal switches to a full listing in the same run. The `seen` set prevents a series from being read twice per run. The engine's `pages.any { it.complete }` gives the intended removeMissing over the merged pages. Paging links are kept relative to the configured API URL, so the token cannot reach another host.
- Engine integration of FOLLOWING: deleted later overrides go first, then the old master PATCH (UNTIL to endDate, earlier exceptions restored, earlier EXDATEs re-deleted), then the new series POST with `transactionId`, then its overrides through `findInstance` on the new Graph id. `ruleUntilBefore` (occurrence minus 1 s) maps to the day before.
- Logs and messages: only fixed texts, HTTP status codes, OAuth error codes and parse messages of dates or recurrence types. No token, response body or event content.
- Events outside the sync window (and series EXDATEs/overrides outside it) are not kept. This follows the recorded window assumption.

## Review conclusion

Material issues found; fix before completing the task. There are no P0/P1 findings, and CR-1 to CR-6 of the first review are resolved and tested. The new CR-1 (P2) is a deterministic false 409 on an ALL pattern change of a series with overrides, caused by the stale override etag after the master's pattern PATCH. The parent should fix it or explicitly accept it with an assumption. CR-2 (P3) is a small error-mapping follow-up. The focused test suite passes (160 tests, 0 failures).
