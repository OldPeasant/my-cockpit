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

# Round 2

Date: 2026-10-21

Scope: Current task. Findings of the second review round: the test-writer's failing test (TW, High) and CR-1 (P2) and CR-2 (P3) of the re-review in `reviews/cal-sync-engine/review.md`. The parent worker applied candidate changes in commit `17528b0f70784a9027893f4c31f5c5d97837ebe7`. This round checked them against the findings and the task assumptions. They resolve the findings, so no further code changes were needed. Base `93da59385314233b39dd326aaaa5c9c55aa9a09a`.

## Fixed findings

### TW — A provider answer that cannot be stored fails the write-through with 500 instead of 502

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/sync/CalendarWriteThrough.kt` (commit 17528b0f)

Problem:
`event.copyFrom(stored)` normalizes the provider's answer and throws `IllegalArgumentException` when the answer cannot be stored, for example when it ends before it starts or has an invalid RRULE. Nothing in `write` caught it, so the request failed with 500.

Fix:
`copyFrom` is wrapped, and an `IllegalArgumentException` becomes a `CalendarProviderException`. The existing handler logs it at WARN (the message names only the failed check, not event content) and returns 502 Problem Details through `providerFailed`. `copyFrom` normalizes before it changes anything, and the exception is thrown from `beforeCommit`, so the transaction rolls back and the local copy stays unchanged. This matches the task assumption "A provider answer to a write-through that cannot be stored ... rollback + 502". The test-writer's test is unchanged.

Validation:
- `CalendarSyncOverrideTests` "a provider answering a write-through with an event it cannot be stored as fails the edit with 502, the local copy stays" — Pass

### CR-1 — After normalization, a local edit writes the reduced event back to the provider

Files changed:
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/sync/CalendarSyncOverrideTests.kt` (commit 17528b0f)

Problem:
`normalized()` drops invalid attendees, out-of-range reminders and non-http(s) conference URLs, and truncates text. Write-through sends the whole local event, so an unrelated later edit removes that data at the provider.

Fix:
The parent accepted the behavior and recorded it as a task assumption. No production code changed. The new test "a local edit of a normalized event writes the normalized fields back to the provider (accepted by assumption)" documents the behavior, as the review asked. It pulls an event with a `tel:` conference URL, an invalid attendee and a negative reminder, PATCHes only the location, and asserts what the fake adapter received: the new location, no conference URL, only the valid attendee, and only the valid reminder. The test does not cover text truncation. That uses the same write path, so this round did not add a test for it.

Validation:
- `CalendarSyncOverrideTests` "a local edit of a normalized event writes the normalized fields back to the provider (accepted by assumption)" — Pass

### CR-2 — The refetch comment claimed a run in progress always fetches the new version

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/sync/CalendarSyncService.kt` (commit 17528b0f)

Problem:
The comment at the `tryLock` in `refetch` said a run in progress fetches the new version as well. That is not true when the run has already fetched this calendar.

Fix:
The parent deferred the behavior by task assumption. Only the comment changed. It now says that the running sync may already have fetched this calendar, so the new version then arrives with the next poll. This matches the assumption. The behavior is unchanged.

Validation:
- `CalendarSyncOverrideTests` "a refetch does not wait for a sync of the same source that is in progress" — Pass

## Not fixed

None in this round. The product behavior behind CR-1 is accepted and the behavior behind CR-2 is deferred, both by recorded task assumptions. Only the test and the comment the parent asked for were in scope. CR-4 from round 1 remains deferred.

## Validation summary

Focused tests:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.*'` — Pass (337 tests, 0 failures, 0 skipped; all 25 tests of `CalendarSyncOverrideTests` ran)

Full test suite:
- Not run in this round. Round 1 ran it, and this round adds no code changes beyond 17528b0f, which touches only the calendar sync package.

Lint/type-check/build:
- `cd server && ./gradlew ktlintCheck` — Pass

`.taskcheck`:
- Not run (left to the parent worker)

Remaining unresolved findings:
0 open. Accepted or deferred by assumption: CR-1 (round 2, accepted), CR-2 (round 2, deferred) and CR-4 (round 1, deferred).

## Final status

All validated findings fixed and validation passed. Round 1: CR-1, CR-2, CR-3 and CR-5 were fixed, and CR-4 was deferred by the parent. Round 2: the TW finding is fixed (502 Problem Details). CR-1 is accepted by task assumption and documented by a test. CR-2 is deferred by task assumption, and its comment was corrected. Round 1's full suite showed only the known flaky `ClockConfigurationTests` precision failure, which is outside this task.
