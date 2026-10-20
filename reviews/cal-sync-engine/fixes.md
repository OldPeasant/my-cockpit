# Fix Report

Date: 2026-10-21

Task: cal-sync-engine - Calendar sync engine

Scope: Current task. Findings CR-1, CR-2, CR-3 and CR-5 from `reviews/cal-sync-engine/review.md`. CR-4 was deferred by the parent worker. Code fix commit: `dbf10078810e457406f5ddc8d104a6d49cde193a`. Base `d19902e3b1201d579d56258b477b7652b4c6b4a1`.

## Fixed findings

### CR-1 — Overrides whose series comes on a later page are dropped, and the cursor still advances

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/sync/CalendarSyncService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/sync/CalendarSyncOverrideTests.kt`

Problem:
`mergeEvents` put masters before overrides only within a single page. If an override arrived on an earlier page than its series, it was skipped. The new cursor was stored anyway, so later incremental fetches never sent that override again.

Fix:
- `mergeEvents` now collects the last change per key over all pages (event, or deletion). This keeps a "deleted on page 1, re-created on page 2" sequence correct.
- It then applies the changes in this order: series and single events of all pages, then overrides of all pages, then deletions in page order.
- If an override is skipped because its series is missing locally, `syncCalendar` deletes the cursor so the next run does a full fetch. This only happens when the run started from a cursor (a full fetch would not help) and the series was not itself skipped as invalid in this run (otherwise every poll would be a full fetch).
- The skipped override is still reported in `lastError`.

Validation:
- New tests: "an override on an earlier page than its series is stored with it", "an incremental override without its series is reported and makes the next sync a full one", "a deletion on an earlier page than a re-creation of the same event keeps the event" — Pass
- `./gradlew test --tests 'ch.sonensei.cockpit.calendar.*'` — Pass

### CR-2 — Pulled remote events skip the event invariants that the API enforces

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/sync/RemoteEventMapping.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/sync/CalendarSyncService.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarEventService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/sync/CalendarSyncOverrideTests.kt`

Problem:
Remote title, description, location, conferenceUrl, rrule, attendees and reminders were stored unchecked. That data is untrusted. It allowed non-web conference URLs (for example `javascript:`) and overlong text, and a later PATCH then failed validation on fields the user never touched. An invalid remote change of a known event was also half-applied (title changed before `setTiming` threw), which made the WIP test fail.

Fix:
- New `RemoteEvent.normalized()` in `RemoteEventMapping.kt`, using the limits and checks of `CalendarEventService`:
  - title, description and location are cut to the API limits; a blank title becomes "(no title)";
  - a conferenceUrl that is not http(s) is dropped;
  - attendees without a valid e-mail address are dropped, overlong names are cut, and the list is capped at `MAX_ATTENDEES`;
  - reminders out of range are dropped, and the list is capped at `MAX_REMINDERS`;
  - a series RRULE loses its `RRULE:` prefix, like the event API stores it.
- An invalid series RRULE, or times that end before they start, throw `IllegalArgumentException`. The event is then skipped and reported. The RRULE is not dropped: dropping it would turn the series into a single event, and the next local edit would write that back to the provider.
- `CalendarSyncService` normalizes before any change to the local entity (a new `store` helper), so an invalid remote change leaves the local copy untouched. `copyFrom` also normalizes, so the write-through answer goes through the same rules.
- To reuse the API checks instead of duplicating them, `isWebUrl`, a new `isValidRule` and a new `isEmail` moved into the `CalendarEventService` companion object (together with the private `ruleParts`). `validate` calls them, so its behavior is unchanged. This is a small refactor in `calendar/api`, outside the task's `touches`.

Validation:
- WIP test "an invalid remote change of a known event is skipped and leaves the local copy unchanged" (commit 2495afe4, unchanged) — Pass
- New test "remote events are kept within the API's limits - non-web conference URLs, invalid attendees and reminders are dropped" (also PATCHes the pulled event successfully) — Pass
- New test "a remote series with an invalid RRULE is skipped and reported, a prefixed one is stored without prefix" — Pass
- Existing event API tests in `ch.sonensei.cockpit.calendar.api.*` (validation unchanged) — Pass

### CR-3 — Search index keeps pre-provider values after a write-through

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/sync/CalendarWriteThrough.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/sync/FakeCalendarAdapter.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/sync/CalendarSyncOverrideTests.kt`

Problem:
The event was indexed before the provider write. `copyFrom(stored)` then replaced its fields with the provider's answer without indexing it again.

Fix:
`CalendarWriteThrough` now injects `CalendarEventIndexer` and calls `indexer.index(event)` after `copyFrom(stored)`. The master is not re-indexed after `shareEtag`, because `shareEtag` changes only the etag, which is not indexed. The fake adapter got a `normalizeWrites` hook (reset in `reset()`) to simulate a provider that normalizes what it is sent.

Validation:
- New test "after a write-through, search shows the provider's normalized answer" — Pass

### CR-5 — The 409 response waits for a whole sync run of the source

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/sync/CalendarSyncService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/sync/CalendarSyncOverrideTests.kt`

Problem:
`refetch` used a blocking `withLock`, so the user's request thread waited for a running sync of the same source.

Fix:
`refetch` now uses `tryLock()` and returns at once when a run is in progress. A run in progress fetches the calendar too.

Validation:
- New test "a refetch does not wait for a sync of the same source that is in progress" — Pass. A background sync is blocked in the fetch hook; `refetch` returns in under 5 s without fetching.

## Not fixed

### CR-4 — A sync whose fetch started before a write-through overwrites the user's new edit

Status:
Out of scope

Reason:
The parent worker deferred it: the copy converges on the next poll. No code changed for it.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat` — Pass (no remaining changes)
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.*'` — Pass (322 tests, 0 failures; all 19 tests of `CalendarSyncOverrideTests` ran)

Full test suite:
- `cd server && ./gradlew check` — 2309 tests, 1 failure: `ClockConfigurationTests."the application clock is the system clock in UTC"`. It compared `2026-10-20T23:23:35.148768Z` with `...148768554Z` (nanosecond precision). This is the flaky core test already noted in the task file and is unrelated to these changes. Re-run alone, `./gradlew test --tests 'ch.sonensei.cockpit.core.persistence.ClockConfigurationTests'` — Pass.

Lint/type-check/build:
- `cd server && ./gradlew check -x test` (ktlint and the other non-test check tasks) — Pass

`.taskcheck`:
- Not run (left to the parent worker; `sh scripts/check.sh` covers web and android as well)

Remaining unresolved findings:
1 (CR-4, deferred by the parent)

## Final status

All validated findings fixed and validation passed. CR-4 was deferred by the parent. The only failure in the full suite is the known flaky `ClockConfigurationTests` precision test outside this task, which passed when re-run.
