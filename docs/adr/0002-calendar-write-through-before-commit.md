# 0002: Calendar edits are written through to the provider before the local commit

Status: accepted (cal-sync-engine, 2026-10-21)

## Context

SPEC.md §6.2: for external calendars the provider is the source of truth, local edits are written through immediately
(no offline edits), and a `412` from the provider makes the user retry. The event service (`calendar.api`) already
implements every edit (series scopes THIS/FOLLOWING/ALL, overrides, EXDATEs) on the local rows; duplicating that logic
per provider, or computing provider calls up front, would split one behavior in two places.

## Decision

- Provider adapters implement `calendar.sync.CalendarProviderAdapter` (list calendars, fetch changes since a cursor,
  create/update/delete with etag), iCalendar-style: a series by UID, an override by UID + recurrence id. They are
  Spring beans, found by source type in `CalendarProviderRegistry`.
- The event service edits the local rows as for local calendars and `record`s each created/changed/deleted event in
  `CalendarWriteThrough`. In the transaction's `beforeCommit` the recorded events of synced calendars go to the adapter
  (series before their overrides); the local rows then take the provider's answer (UID, etag, fields). A failure throws
  and rolls the whole edit back: `412` → `409` "changed remotely" and the calendar is fetched again after the rollback
  (new transaction); other provider failures → `502`.
- Pulling (`CalendarSyncService`) calls the provider outside transactions and merges each calendar's changes together
  with its new cursor in one transaction. Sync status and cursors have their own tables, so a sync never changes the
  version (ETag) of sources or calendars.

## Consequences

- One implementation of edit semantics; adapters only map single-event writes to their provider.
- A database transaction stays open during the provider calls of an edit (a few HTTP requests, user-initiated only).
- If the provider accepts a write and the local commit then fails, the next sync brings the provider's state in (remote
  wins), so the copies converge.
- Events of synced calendars are deleted for good (the provider has its own trash) and cannot move between calendars.
