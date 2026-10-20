# Code Review

Date: 2026-10-21

Task: cal-sync-engine - Calendar sync engine

Scope: `git diff 014f681287dc65e0391dc97d55d11467249cafcd..2212fc0b35ec35695702d66719815796910ff425` (committed changes only)

Requirements:
- tasks/cal-sync-engine.md (acceptance criteria 1-6 and the recorded assumptions)
- SPEC.md §6.2 (two-way sync, remote is the source of truth, write-through, 412 handling), §4.2 (API conventions)
- docs/ARCHITECTURE.md, AGENTS.md project notes, docs/QUALITY.md
- docs/adr/0002-calendar-write-through-before-commit.md

Git range:
- Branch: main
- HEAD: 2212fc0b35ec35695702d66719815796910ff425
- Base/start: 014f681287dc65e0391dc97d55d11467249cafcd
- Commits reviewed: 5 (35059bb5, 718b349d, 796c4aa4, af0ea678, 2212fc0b)

Summary: 0 P0, 0 P1, 2 P2, 3 P3

What was checked and holds: the adapter interface covers list calendars, incremental fetch with cursor and paging,
and create/update/delete with If-Match. The registry rejects LOCAL and duplicate adapters. The scheduler ticks with
fixedDelay and syncs due sources under a per-source lock. Sync status and cursors live in separate tables (new Flyway
migration in `db/migration/calendar`, FK cascade), so a sync does not bump source/calendar ETags. Write-through runs in
`beforeCommit`; a 412 rolls back, returns 409 and refetches in a REQUIRES_NEW transaction after completion. Other
provider failures return 502 Problem Details. `GET /sources` does not return `config` or credentials. Logs of
unexpected errors contain only the exception type. `api/openapi.yaml` and the generated web client contain both
endpoints. The fake-adapter tests cover incremental merge, deletions, paging, the conflict path and split/delete
scopes. Acceptance-criterion evidence (`tasks.py verify`) has not been recorded yet; that is the parent's job.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Overrides whose series comes on a later page are dropped for good, because the cursor still advances**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/sync/CalendarSyncService.kt:288-305` (cursor stored at `:270-276`)
    - Category: Correctness
    - Problem: `mergeEvents` puts masters before overrides only within one page
      (`page.events.partition { ... }` inside `for (page in pages)`). Paged fetches (`hasMore`) are allowed to return an
      override on page N and its series master on page N+1. When that happens, `upsertOverride` finds no master, logs the
      event and counts it as skipped. The transaction then stores the new cursor anyway. The next incremental fetch does
      not send that override again, so it stays missing until it changes at the provider or the next full fetch. The only
      sign is "N events could not be stored" in `lastError`, and a retry cannot fix it.
    - Why it matters: the local copy silently loses data that the provider has (SPEC.md §6.2: remote is the source of
      truth). The paging contract in `CalendarProviderAdapter.fetchEvents` says nothing about order, so real adapters
      (Google, Graph) can return pages in this order.
    - Suggested fix: all pages are already in memory before the merge. Upsert the masters of all pages first, then the
      overrides of all pages, then apply the deletions in page order. Also consider not advancing the cursor (or
      clearing it, to force a full fetch next time) when an override was skipped for lack of its series. Add a test with
      the override on page 1 and its master on page 2.

- [ ] **CR-2 | Pulled remote events skip the event invariants that the API enforces, which blocks later edits and stores non-web conference URLs**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/sync/RemoteEventMapping.kt:194-214` (used by `CalendarSyncService.kt:316,335`)
    - Category: Correctness / Security
    - Problem: `copyFrom` stores remote title, description, location, conferenceUrl, rrule, attendees and reminders as
      they arrive. `CalendarEventService.validate` (`CalendarEventService.kt:666-702`) enforces http(s)-only
      `conferenceUrl`, length limits, a valid RRULE, attendee e-mail format and the reminder range on every local change.
      The sync path runs none of these checks.
      - The data is untrusted: anyone can put an event into the user's provider calendar by sending an invite. Such an
        event can store a `javascript:`/`data:` conference URL, or a description longer than `MAX_DESCRIPTION_LENGTH`.
        Clients that rely on the server invariant (conferenceUrl is a web URL) would then render it as a link.
      - Any later PATCH of that event runs `validate(event)` on the whole entity (e.g. `updateSingle`, line 210). It
        returns 400 on a field the user did not touch, so the event cannot be edited from the cockpit.
    - Why it matters: an invariant the API guarantees no longer holds. Untrusted external input gets a path around
      server-side validation, and pulled events can become uneditable.
    - Suggested fix: normalize remote events in the engine before storing them (the common entry point for all
      adapters). Drop a `conferenceUrl` that is not http(s), truncate overlong text to the API limits, and drop invalid
      attendees and reminders. Alternatively, have the validation used for edits skip unchanged fields of synced events.
      Add a test with a remote event that carries a `javascript:` conference URL.

## P3 Low

- [ ] **CR-3 | The search index keeps the pre-provider values after a write-through**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/sync/CalendarWriteThrough.kt:149` (index written earlier in `CalendarEventService.changed`, `CalendarEventService.kt:628-630`)
    - Category: Correctness
    - Problem: `changed()` indexes the event, then `record()` defers the provider write to `beforeCommit`. There,
      `event.copyFrom(stored)` overwrites the fields with the provider's normalized answer. The ADR and KDoc say "the
      local copies take the provider's answer (normalized fields)", but the entity is not indexed again. The next sync
      sees the same etag and SYNCED state (`isCurrent`), so it does not re-index either. Search keeps the values as the
      user typed them for as long as the event stays unchanged.
    - Why it matters: search results can differ from the stored event.
    - Suggested fix: call `indexer.index(event)` (or `index(master)` after `shareEtag`) after `copyFrom` in `write()`.

- [ ] **CR-4 | A sync whose fetch started before a write-through overwrites the user's new edit with the old remote copy**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/sync/CalendarSyncService.kt:259-269, 313`
    - Category: Correctness
    - Problem: `syncCalendar` fetches outside any transaction and without coordinating with `CalendarWriteThrough`, which
      takes no source lock. Take a fetch that returns event E at etag e1, followed by a user edit that writes E through
      and commits locally at etag e2. The merge then sees `local.externalEtag (e2) != remote.etag (e1)` and copies the
      older remote version over the local copy. The stored cursor predates the edit, so the next poll restores it. Until
      then (up to one poll interval) the user sees the edit reverted. A further edit in that window gets a spurious 409.
    - Why it matters: the edit visibly reverts for a short time. It converges, so this is low priority.
    - Suggested fix: inside the merge transaction, skip a remote event when the local row was written after the fetch
      started (e.g. compare `updatedAt` with the run's start time). Alternatively, have write-through and the merge share
      the per-source lock.

- [ ] **CR-5 | The 409 response for a remote change waits for a whole sync run of the source**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/sync/CalendarSyncService.kt:145` (called from `CalendarWriteThrough.kt:101`)
    - Category: Quality
    - Problem: `refetch` runs in `afterCompletion` on the HTTP request thread and takes the source lock with the
      blocking `withLock`. If a scheduled or manual run of the same source is in progress (all calendars, up to
      `MAX_PAGES` pages each), the user's PATCH/DELETE stays blocked until that run ends, plus the refetch's own network
      calls.
    - Why it matters: an interactive request can hang for a long time.
    - Suggested fix: use `tryLock` (a running sync brings in the new version anyway), or a timed `tryLock`.

## Review conclusion

Material issues found; fix before completing the task (CR-1 and CR-2 at P2; the P3 findings at the parent's
discretion).
