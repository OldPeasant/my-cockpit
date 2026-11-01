# Code Review

Date: 2026-11-01

Task: and-dashboard - Android: Dashboard as start screen

Scope: `git diff 183eee95..d3998a04`, Android app only (`android/`); committed changes only, no uncommitted changes present at review start.

Requirements:
- tasks/and-dashboard.md (acceptance criteria and the four recorded ASSUMPTION notes)
- SPEC.md §8.7 (Dashboard widgets), §9.2 (Android architecture)
- docs/ARCHITECTURE.md, AGENTS.md project notes (lint, EN/DE strings, no secrets in logs, feature folders, server unchanged)
- Reference implementation: web/src/app/features/dashboard (dashboard-layout.ts, dashboard-data.ts, dashboard-page.html)
- Parent instructions: Android only, server unchanged, endpoints as used by the web dashboard

Git range:
- Branch: main
- HEAD: d3998a04
- Base/start: 183eee95ee445858870ba38a652db9c55e3df370
- Commits reviewed: 3 (cf463bc9 implementation, 62be029c tests, d3998a04 tests)

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Upcoming event dates use a fixed, non-localized pattern**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/dashboard/DashboardScreen.kt:267`
    - Category: Quality
    - Problem: `DateTimeFormatter.ofPattern("EEE d MMM")` hard-codes the English field order and punctuation, and takes the JVM default locale implicitly. In German this renders e.g. "Mo. 2 Nov." instead of the usual "Mo., 2. Nov.". The task list in the same file uses `ofLocalizedDate(FormatStyle.MEDIUM)`, and `CalendarScreen` derives its pattern per locale.
    - Why it matters: The criterion asks for EN/DE UI; a date prefix on every Upcoming row reads slightly wrong in German.
    - Suggested fix: Build the pattern per locale (e.g. `android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEdMMM")`) or reuse the calendar feature's helper.

- [ ] **CR-2 | `DashboardEvent.key` is computed but never used by the UI**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/dashboard/DashboardRepository.kt:92`, `:287`
    - Category: Quality
    - Problem: The `key` field (`"$eventId:${occurrence ?: date}"`) is only read by `DashboardRepositoryTest` (line 339); `Events()` in `DashboardScreen.kt:268` renders with a plain `forEach` and no key.
    - Why it matters: Dead data in the model suggests an identity contract nothing relies on.
    - Suggested fix: Drop the field (and its test assertion), or use it where identity matters.

## Notes (verified, no finding)

- All eight §8.7 widgets are loaded from the same endpoints and parameters as `web/.../dashboard-data.ts` (tasks overdue/today, occurrences from local midnight, habits due/done, inbox tasks + unified-inbox unread mail with the "never 0+" rule, waiting+overdue, active stalled projects, weekly review status, unread read-later bookmarks, pinned notes; page size 50, 7 upcoming days). No server files changed.
- `parseLayout` mirrors the web `parseLayout` (order, `:hidden`, unknown/repeated/non-string entries skipped, missing widgets appended shown, non-array = default); the layout is read only.
- Each widget loads in its own coroutine and maps errors per widget (`IOException`/5xx = offline, 4xx/serialization/`IllegalArgumentException`/`DateTimeException` = failed; `CancellationException` rethrown), so one failure stays in its card; a failed reload keeps the last data under the error line, as assumed.
- `refresh()` cancels the previous load job, so an older refresh cannot overwrite a newer one; `refreshing` is reset at the end of every completed job, and a cancelled job is always superseded by a new one that sets it.
- Start destination is `Dashboard`, it is a bottom-bar item (5 items, Today moved to the top of More as assumed); `open()` routes bottom-bar targets via `navigateToTopLevel` and More targets on top. Card destinations match the web links.
- EN and DE resource files have the same string/plural names and placeholders (also asserted by `DashboardStringsTest`). No logging added.
- Tests cover layout parsing, repository queries and error mapping, ViewModel ordering/isolation/refresh/race, Compose card content and navigation, and in-app start/login routing. I did not run Gradle (a test-writer was running in parallel in the same clone); the parent must record the `./gradlew ktlintCheck testDebugUnitTest lintDebug` result as evidence for criterion 6.

## Review conclusion

No material issues found.
