# Code Review

Date: 2026-10-21

Task: cal-microsoft - Microsoft Graph calendar adapter

Scope: committed changes of task cal-microsoft, `git diff 3c988b99..a304832e -- server/` (adapter, Graph client, mapping, recurrence, Windows zones, WireMock recordings, adapter/recurrence/sync tests). The test-writer's uncommitted test changes are not included.

Requirements:
- tasks/cal-microsoft.md acceptance criteria 1-3 and its recorded assumptions (window 365/730 days with a weekly relisting, relisting on any removal, derived EXDATEs, diff-only updates, zone mapping)
- SPEC.md §6.2 (Graph delta queries, 5 min poll, remote is the source of truth, 412 leads to a refetch and "changed remotely", THIS/FOLLOWING/ALL mapped to provider semantics)
- Adapter contract `calendar/sync/CalendarProviderAdapter.kt`; engine use in `CalendarSyncService.kt`, `CalendarWriteThrough.kt`, `api/CalendarEventService.kt` (splitSeries, ruleUntilBefore)
- AGENTS.md project conventions (no secrets or event content in logs and user-visible messages, lint), docs/QUALITY.md

Git range:
- Branch: main
- HEAD: a304832e
- Base/start: 3c988b9962d7e0e8df381d739035c8be9f821f6a
- Commits reviewed: 3 (bd4802b6, d0016245, a304832e)

Commands run:
- `git log --oneline 3c988b99..HEAD`, `git diff --stat 3c988b99..a304832e` -> 3 commits, 21 files, 3822 insertions, all under `server/`
- `git show a304832e:<test files>` -> read the committed test versions (the working tree may hold the test-writer's edits)
- `./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.microsoft.*'` -> not run by the reviewer (the sandbox denied running Gradle). Instead I read the latest JUnit results in `server/build/test-results/test/` (2026-10-21T10:23Z): GraphRecurrenceTests 33/0 failures, MicrosoftCalendarAdapterTests 20/0, MicrosoftCalendarSyncTests 4/0 (Postgres via `PostgresTestBase`). These are the parent's or test-writer's results; I did not reproduce them.

Summary: 0 P0, 0 P1, 2 P2, 4 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | "This and following" sends a changed `recurrence` for the old series, and nothing puts back its earlier exceptions**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/MicrosoftCalendarAdapter.kt:337-349` (with `GraphEventMapping.kt:180-183`)
    - Category: Correctness
    - Problem: The adapter's own design premise (KDoc of `GraphEventMapping.changes`: "a series keeps its exceptions unless its pattern changed", and the recorded assumption about diff-only updates) is that Graph/Exchange drops a series' exceptions when its `recurrence` is patched. A FOLLOWING split always patches the old master's `recurrence` (new `range.endDate`; the diff sends the whole `recurrence` object). `updateEvent` then re-deletes the EXDATE instances (`deleteOccurrences`), but it never re-applies the overrides before the split point (e.g. the moved 19 October occurrence in the recordings). So at Microsoft those occurrences go back to the pattern, and the next complete listing removes the local overrides (`removeMissing`). The user's earlier changes to the series are then lost on both sides. The engine test `changing this and following occurrences ...` cannot detect this, because after the PATCH the stubbed `/instances` still returns the old exception.
    - Why it matters: AC1 names the 'following' split. On Exchange, a split would silently revert earlier occurrence edits, and attendees of those occurrences may be notified.
    - Suggested fix: After a `recurrence` change of an existing series, re-apply the overrides the engine still holds for the kept range. The adapter cannot see local overrides, so one option is to compare the instances before and after the PATCH and re-patch exceptions that disappeared, using the pre-PATCH copy. Alternatively, record the limitation as an assumption if a human accepts it. Add a WireMock test where the post-PATCH `/instances` no longer contains the exception. Confirm the Graph behaviour against a real recording.

- [ ] **CR-2 | Events are read and written via `/me/events/{id}`, which fails for calendars of other mailboxes listed in `/me/calendars`; a 404 is read as "gone"**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/MicrosoftCalendarAdapter.kt:567` (used at 196-197, 334-335, 446-450, 460-463)
    - Category: Integration
    - Problem: `listCalendars` uses `/me/calendars`. That also returns calendars shared with the user from other mailboxes, and they are writable when `canEdit` is true. Delta and create use `/me/calendars/{calendar}/...`, but series reads, instances, PATCH and DELETE use `/me/events/{id}`, which only resolves items in the signed-in user's own mailbox. For such a calendar: `readSeries` treats the 404 as "series deleted" (returns `emptyList()`), so the series never syncs and a complete listing deletes any local copy. `delete` treats 404 as success ("Already gone"), so a cockpit deletion reports success while the event stays at Microsoft. PATCH reports "deleted at Microsoft" and starts a refetch loop.
    - Why it matters: Series in a shared calendar disappear silently, and deletions silently do nothing. Nothing reaches the user as an error.
    - Suggested fix: Address events through the calendar they live in: `/me/calendars/{calendar}/events/{id}` and `.../instances`. `calendarExternalId` is available in every adapter method; pass it down to `readSeries`, `findInstance`, `patch` and `delete`. Alternatively, mark non-owned calendars read-only or skip them in `listCalendars` and record that as an assumption.

## P3 Low

- [ ] **CR-3 | An exception moved more than 35 days from its original slot cannot be found for edit or delete**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/MicrosoftCalendarAdapter.kt:397-409, 577`
    - Category: Correctness
    - Problem: `findInstance` lists instances whose actual start lies within ±35 days of the original start. An override moved further away is not found. `deleteEvent` then returns as if it were already gone, so the local deletion succeeds but Graph keeps the occurrence, and it comes back on the next sync. `updateEvent` throws `RemoteEventChangedException` every time, so the override can never be edited from the cockpit.
    - Why it matters: Rare, but the failure is silent (delete) or permanent (edit).
    - Suggested fix: Look the instance up by the override's last known Graph id, or search the whole sync window when the narrow search finds nothing.

- [ ] **CR-4 | Any attendee change rewrites every attendee as `required`**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/GraphEventMapping.kt:159-164, 223-233`
    - Category: Correctness
    - Problem: `toRemote` drops Graph's attendee `type` (required/optional/resource), and `toBody` writes `required` for everyone. The diff avoids this while the attendees are unchanged. When one attendee is added or removed, though, the whole array is sent and optional or resource attendees become required.
    - Why it matters: A silent change of meeting data at Microsoft.
    - Suggested fix: When building the PATCH, keep the `type` of attendees Graph already has (match by email), or record the limitation as an assumption.

- [ ] **CR-5 | `listCalendars` follows `@odata.nextLink` without a page bound**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/MicrosoftCalendarAdapter.kt:76-80`
    - Category: Quality
    - Problem: `instances` stops at `MAX_PAGES`, and the engine bounds delta paging. The calendar listing loops for as long as Graph returns a next link, so a repeated or cyclic link would never end.
    - Why it matters: Resilience; the sync thread could hang.
    - Suggested fix: Apply the same `MAX_PAGES` guard as in `instances`.

- [ ] **CR-6 | Formatter-mangled conditionals reduce readability**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/MicrosoftCalendarAdapter.kt:116-124`, `GraphEventMapping.kt:150-157`
    - Category: Quality
    - Problem: `if (state.mode ==\n NEXT\n) { state.seen } else { emptySet() }` and `if (rule ==\n null\n)` are ktlint wrapping artifacts of long expressions.
    - Why it matters: Small readability cost in code that is already dense.
    - Suggested fix: Extract a local (`val continuing = state.mode == NEXT`) or use `event.rrule?.let { ... } ?: body.putNull(...)`, so the formatter keeps each condition on one line.

Checked and not reported:
- Delta cursor: paging and delta links are stored relative to the configured API URL, and the host of a Graph link is ignored, so the token only goes to the configured host. Links outside the API path are refused (tested). The `seen` set keeps each series to one master and instances read per run. 410 and links older than 7 days start a complete listing, and removals switch to a complete listing in the same run. The engine treats the run as complete because one page of it is (`pages.any { it.complete }`).
- EXDATE derivation: expander occurrences in the window, minus the instances' `originalStart` within 12 h, minus the 2-day edges. Exceptions are keyed by their original start. All-day originals at local midnight match UTC midnight within the tolerance.
- RRULE mapping: endDate becomes UNTIL at 23:59:59 in the series zone, and UNTIL becomes the date of the last occurrence it allows. The engine's `ruleUntilBefore` (occurrence minus 1 s) maps to the day before. Unsupported rules are refused before any request.
- If-Match/412: PATCH and DELETE send the stored etag, and 412 maps to `RemoteEventChangedException`. An empty diff still checks the etag against the fetched copy.
- Messages and logs: token, response bodies and event fields stay out of exception messages and logs. OAuth errors are mapped to fixed texts.
- Events outside the sync window are deleted locally on a complete listing. This follows the recorded window assumption, so it is not reported as a defect; humans may want to revisit it.

## Review conclusion

Material issues found; fix before completing the task. There are no P0/P1 findings. CR-1 (earlier exceptions lost on a "this and following" split, untested) and CR-2 (shared calendars read and deleted through the wrong path, with 404 read as success) are P2 issues the parent should fix or explicitly accept with an assumption. CR-3 to CR-6 are low-priority follow-ups. The focused test suite was not re-run by the reviewer (Gradle execution was denied in this sandbox); the latest recorded results show 57 tests, 0 failures.
