# Code Review

Date: 2026-10-21

Task: cal-sync-engine - Calendar sync engine

Scope: Re-review after fixes. Committed task changes `git diff d19902e3b1201d579d56258b477b7652b4c6b4a1..4d95ab3bc6ae33e8a3f726bb09c8df184e35cb40`, excluding heartbeat/assume/claim commits and other tasks' commits. The focus is on the fix commits 9fcf7279 (WIP tests) and c0bb5fc5 (fixes for CR-1, CR-2, CR-3, CR-5 of the previous review); the rest of the task diff was re-checked for interactions with them.

Requirements:
- tasks/cal-sync-engine.md (acceptance criteria 1-6 and the recorded assumptions, including the CR-4 deferral and the move of the validation helpers into the `CalendarEventService` companion)
- SPEC.md §6.2 (two-way sync, remote is the source of truth, write-through), §4.2 (API conventions)
- docs/ARCHITECTURE.md, AGENTS.md project notes, docs/QUALITY.md
- docs/adr/0002-calendar-write-through-before-commit.md
- Previous review: reviews/cal-sync-engine/review.md (2212fc0b revision); fixer report: reviews/cal-sync-engine/fixes.md

Git range:
- Branch: main
- HEAD: 4d95ab3bc6ae33e8a3f726bb09c8df184e35cb40
- Base/start: d19902e3b1201d579d56258b477b7652b4c6b4a1
- Commits reviewed: 9 task commits (5ebdd5b1, 36a6ea73, b2ea7087, 318f9822, 52c77f5b, 43b63bd7, 9fcf7279, c0bb5fc5, 4d95ab3b); no uncommitted changes when the review started

Summary: 0 P0, 0 P1, 1 P2, 1 P3

## Status of the previous findings

- CR-1 (overrides on an earlier page than their series): resolved. `mergeEvents` (`CalendarSyncService.kt:298-340`) now takes the last change per key over all pages. It applies all masters, then all overrides, then the deletions. An override that is skipped for lack of its series in an incremental run clears the cursor (`:275`), so the next run is a full fetch. Already-invalid series are excluded, and a full fetch never clears the cursor, so this cannot loop. Tests cover override-before-series, an orphaned incremental override followed by a full refetch, and delete-then-re-create across pages.
- CR-2 (remote events skip the API invariants): resolved for storage. `RemoteEvent.normalized()` (`RemoteEventMapping.kt:31-51`) uses the API's limits and the shared `isWebUrl`/`isValidRule`/`isEmail` helpers. `store()` normalizes before anything on the entity changes, so an invalid remote change leaves the local copy untouched. The timing checks match `EventCommands.kt:165,183`. `validate` behavior is unchanged. See the new CR-1 below for a side effect on write-through.
- CR-3 (search index after write-through): resolved. `CalendarWriteThrough.kt:153` re-indexes after `copyFrom(stored)`, and a test checks `search_document`.
- CR-4: deferred by the parent and recorded as an assumption in the task file. Not re-reported.
- CR-5 (409 waits for a whole sync run): resolved. `refetch` uses `tryLock` (`CalendarSyncService.kt:145-154`), and a test checks that it returns without fetching while a run holds the lock. See the new CR-2 below for a remaining gap.

Not verified here: I did not re-run the Gradle suites. A test-writer may be using the same clone at the same time, and a parallel Gradle run would interfere with it. The fixer reports `./gradlew test --tests 'ch.sonensei.cockpit.calendar.*'` -> 322 passed. Acceptance-criterion evidence (`tasks.py verify cal-sync-engine`) is still unrecorded for all 6 criteria. Recording it is the parent's job before `done`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | After normalization, an unrelated local edit deletes the dropped or truncated remote data at the provider**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/sync/RemoteEventMapping.kt:39-49` (write-back via `toRemote` at `:93-108`, used by `CalendarWriteThrough.kt:141,148`)
    - Category: Correctness
    - Problem: `normalized()` permanently changes the local copy:
        - It drops attendees that fail `isEmail`, and keeps at most `MAX_ATTENDEES` (200) attendees.
        - It drops reminders outside `0..40320` minutes.
        - It drops a `conferenceUrl` that is not http(s). Besides `javascript:`, this also hits legitimate `tel:`/`sip:` dial-in URIs an adapter may map there.
        - It truncates title, description and location.

      On every later local edit of the event, write-through sends the whole local event (`adapter.updateEvent(..., event.toRemote(uid), ...)`). A user who changes only the location therefore also removes those attendees, reminders and conference URL at the provider and truncates its description. This happens even though the user never touched those fields. For attendees, providers usually notify the removed people. The fixer kept the series RRULE on purpose for exactly this reason ("dropping the rule ... the next local edit would write that back to the provider"), but did not apply the same reasoning to the other fields. The CR-2 test (`CalendarSyncOverrideTests`, "remote events are kept within the API's limits ...") PATCHes the pulled event but does not check what reached the fake provider, so this goes unnoticed.
    - Why it matters: data the provider owns is lost on the provider side (SPEC.md §6.2: remote is the source of truth), and the loss is triggered by an edit of an unrelated field. A meeting with more than 200 attendees, or one with a single malformed attendee address, loses participants at the provider.
    - Suggested fix: separate what is stored or rendered from what is written back. One option: sanitize only what is unsafe to expose (the non-web `conferenceUrl`) and let `validate` skip invariants of fields a PATCH did not change on synced events. Another option: write back only the fields the user changed, by merging onto the provider's current version. If the lossy behavior is accepted instead, record it with `tasks.py assume`. Either way, add a test that edits a normalized event and asserts what the fake adapter received (attendees, conferenceUrl, description).

## P3 Low

- [ ] **CR-2 | A refetch skipped with `tryLock` can leave the 409's "now shows the new version" untrue until the next poll**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/sync/CalendarSyncService.kt:146-147` (message at `CalendarWriteThrough.kt:159`)
    - Category: Correctness
    - Problem: the comment says "A run in progress fetches the new version as well". That only holds if the running run fetches this calendar after the remote change. If the run has already fetched or merged this calendar (it handles calendars one after another, and the network fetch happens before the merge transaction), the new remote version is not pulled. The refetch is then dropped instead of deferred. The client gets 409 "the cockpit now shows the new version, please retry", reloads, and still sees the old version. A retry gets another 412 -> 409, which only works if that second refetch finds the lock free. Otherwise the user waits for the next poll interval.
    - Why it matters: the conflict flow in criterion 2 ("412 -> refetch") is not guaranteed while a sync of the same source runs. The effect is a confusing extra 409, not data loss, and it converges.
    - Suggested fix: do not drop a refetch that loses the race. Put the calendar id in a per-source "refetch after this run" set, and have the run holding the lock process that set before it releases the lock. Alternatively, run the refetch asynchronously with a blocking lock, off the request thread. Correct the comment either way.

## Review conclusion

Material issues found; fix before completing the task. CR-1 (P2) causes loss of provider data on write-back, so fix it or explicitly accept it as an assumption with a test that documents the behavior. CR-2 (P3) is at the parent's discretion. The previous findings CR-1, CR-2, CR-3 and CR-5 are resolved. CR-4 remains deferred by recorded assumption.
