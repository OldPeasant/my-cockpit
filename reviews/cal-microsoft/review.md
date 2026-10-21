# Code Review

Date: 2026-10-21

Task: cal-microsoft - Microsoft Graph calendar adapter

Scope: round 3. Committed changes of task cal-microsoft, `git diff $(git merge-base HEAD origin/main)..HEAD` = `git diff bf0bb627..7fe28108`. This covers the adapter, Graph client, mapping, recurrence and Windows zones under `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft`, their tests and WireMock recordings, and the review artifacts. The focus is the round-2 fixes for the second review (commit 66e6bcf6: `RestoredEtags`, an empty diff succeeds whatever the etag, `instancesOrNull` for a 404 from `/instances`) and the round-3 tests (7fe28108). There were no uncommitted changes when the review started.

Requirements:
- tasks/cal-microsoft.md acceptance criteria 1-3 and its recorded assumptions, including the one from 2026-10-21 14:24 (an empty diff succeeds whatever the If-Match; restored-exception etags are kept in memory only, bounded to 1000)
- SPEC.md §6.2 (Graph delta, remote is the source of truth, 412 means "changed remotely", THIS/FOLLOWING/ALL)
- docs/ARCHITECTURE.md conventions; AGENTS.md (lint, no secrets or event content in logs)
- Engine contract: `calendar/sync/CalendarProviderAdapter.kt` and `CalendarWriteThrough.kt` (write order: removed overrides, then series, then overrides; `ifMatch = event.externalEtag`; `seriesShareEtag` is false for Microsoft)
- The second review (below the separator), reviews/cal-microsoft/fixes.md (round 2), reviews/cal-microsoft/tests.md (round 3)

Git range:
- Branch: main
- HEAD: 7fe28108c3caac04eca398f0e54ab0256732d4d1
- Base/start: bf0bb62744a3a79cfafff0dbcd3dd33e042e3241 (`git merge-base HEAD origin/main`)
- Commits reviewed: 11 (c9acc395, d27c8d95, 872768e6, ebce036c, 0f10f5d9, f9a81009, 972a78a3, d5a6f72e, 66e6bcf6, 7042c2a5, 7fe28108), all "cal-microsoft:"

Commands run:
- `git log --oneline bf0bb627..HEAD`, `git diff --stat bf0bb627..HEAD` -> 11 commits, 26 files, 6221 insertions, all in the task's `touches` paths and `reviews/cal-microsoft/`
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.microsoft.*' -Pkotlin.daemon.jvmargs=-Xmx3g` -> BUILD SUCCESSFUL. JUnit results from 2026-10-21T12:41Z: GraphEventMappingTests 44, GraphRecurrenceTests 37, MicrosoftCalendarAdapterTests 67, MicrosoftCalendarSyncTests 5 (Postgres via Testcontainers), WindowsZonesTests 20. Total 173 tests, 0 failures, 0 errors, 0 skipped.
- `cd server && ./gradlew ktlintMainSourceSetCheck` -> pass

Status of the second review's findings:
- CR-1 (P2, false 409 on an ALL pattern change of a series with overrides): resolved.
    - `writeInstance` (MicrosoftCalendarAdapter.kt:388-413) returns Graph's instance without a write when the diff is empty. A non-empty diff is sent with `If-Match` = `restored.current(...)` (line 396). That is Graph's current etag only when `restoreExceptions` recorded the pair (current etag, the exception's old etag) and the caller's etag is that old etag. In every other case it is the caller's etag.
    - `restoreExceptions` (lines 455-485) records the pair for exceptions it re-patched and for exceptions Graph kept unchanged. It records nothing for a failed restore or for an exception Graph kept with other fields.
    - I traced this against `CalendarWriteThrough.push`. For an ALL edit, the master is written first, then the overrides carried as UPDATED, each with its stale `externalEtag`. Each override then either takes the empty-diff path or the PATCH is sent with the restored etag. A remote edit after the restore changes the instance etag, the pair no longer matches, and the caller's etag is sent (412, then 409).
    - The empty-diff rule is safe. The desired body carries every mapped field of the local copy, so a remote change to any mapped field makes the diff non-empty, and the PATCH then goes out with the caller's If-Match. The rule is recorded as an assumption.
    - Covered by the engine + WireMock + Postgres sync test (`changing the pattern of a whole series with an exception succeeds ...`) and by eight adapter tests: stale etag, remote edit after the restore, failed restore, kept-unchanged, kept-changed, cancel, and the empty and non-empty series diffs.
- CR-2 (P3, 404 from `/instances` gave 502): resolved. `findInstance` (lines 420-431) uses `instancesOrNull` for both lookups and returns null on a 404. A change of an occurrence then raises `RemoteEventChangedException`, a delete with a recurrence id returns null, and creating an override fails with a provider error. `instances()` still throws on a 404. It is only used after the master was just read or written, which is a narrow race that the next poll resolves. Tests cover a 404 on the first lookup and on the whole-window lookup.

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | One class KDoc line was not rewrapped after the round-2 edit**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/MicrosoftCalendarAdapter.kt:53`
    - Category: Quality
    - Problem: The round-2 change inserted text into the class KDoc without rewrapping it. Line 53 is now 168 characters long. The rest of the KDoc wraps at about 120 characters, and the project's ktlint style uses 140 for code. ktlint does not flag it, because the check passes.
    - Why it matters: This is only a small readability inconsistency in the main description of the adapter's push behavior. It does not affect behavior.
    - Suggested fix: Rewrap the paragraph (lines 49-57) like the surrounding KDoc. Only do this together with any other change, because it alters a changed line and would make the review evidence stale.

Checked and not reported:
- `RestoredEtags` is synchronized, bounded (an LRU-style eviction in insertion order), keyed by calendar and instance id, and holds only etags, with no event content. It is in memory only, as the recorded assumption says. An entry is not forgotten after an empty-diff write, which does no harm: the stored etag then equals the current one, so `current` returns the caller's etag unchanged.
- `seriesShareEtag` is false for Microsoft, so the engine's `shareEtag` does not overwrite the per-instance etags the adapter returns.
- A cancelled override after a pattern change is deleted with the restored etag (line 398), and the entry is dropped afterwards.
- Logs and exception messages added in round 2 contain only fixed texts and HTTP status codes.
- The round-3 tests assert the requests Graph receives (method, path and `If-Match`, and the absence of unexpected PATCH or DELETE requests), not internal state. No existing test was weakened, except for the two "unchanged ... whatever the etag" tests. Their expectation changed on purpose to the behavior that the second review's CR-1 asked for and the 14:24 assumption records.
- Acceptance criteria: AC1 (calendars, calendarView/delta, writes, occurrence edits, FOLLOWING split) is implemented and covered by the WireMock and sync tests. AC2: the tests run against the recordings in `src/test/resources/.../microsoft`. AC3: the focused command passes (see above), and the persistence tests use Postgres via Testcontainers.

## Review conclusion

No material issues found. The second review's CR-1 (P2) and CR-2 (P3) are resolved and covered by tests, and the focused suite passes (173 tests, 0 failures). The only new finding is a P3 cosmetic KDoc wrap. It does not block completion. The full `.taskcheck` (`sh scripts/check.sh`) has not been run by this review and remains the parent's responsibility.

---

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
