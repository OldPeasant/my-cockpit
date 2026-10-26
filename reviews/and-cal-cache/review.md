# Code Review

Date: 2026-10-26

Task: and-cal-cache - Android agenda offline read cache (SPEC 9.3)

Scope: `git diff 6e48a9c9a865f1aad9a097b2f02c265230591302..6fc162929328234442f51bc21340ecd7382c497d -- android` (final review, on the final code). Commits 993b95f05 (implementation + tests), ebd91e2b7 (tests for logout during a load, cache failures, graph wiring, cached ranges), e1758858e (fixer: calendar list stored only from a server answer; offline fallback keeps a freshly loaded calendar list), 6fc162929 (two tests for the calendars kept by the offline fallback). The first three are the rebased form of the previously reviewed 2ad1c6d34, 8aec21219, 6f394e2fb: `git diff 6f394e2fb e1758858e -- android` is empty. The only code change since the previous review is the test-only commit 6fc162929 (`git diff 6f394e2fb..6fc162929`: `CalendarViewModelCacheTest.kt` +54 lines, plus `tasks/` metadata); production code is unchanged.

Requirements:
- tasks/and-cal-cache.md acceptance criteria and recorded assumptions
- SPEC.md §9.3 "Read cache"
- docs/ARCHITECTURE.md (Android stack, Room schemas in `android/app/schemas/`)
- AGENTS.md "Project notes" (conventions, no logging of personal data)
- Previous reviews (CR-1, CR-2) and reviews/and-cal-cache/fixes.md

Git range:
- Branch: main
- HEAD: 6fc162929328234442f51bc21340ecd7382c497d
- Base/start: 6e48a9c9a865f1aad9a097b2f02c265230591302
- Commits reviewed: 4

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Previous findings

- CR-1 (view-model wiring of the logout guard untested): resolved. `CalendarViewModelRoomCacheTest` drives the view model against the in-memory `RoomAgendaCache`, holds the server answer back with a `CompletableDeferred`, calls `cache.clear()` while the load runs and asserts nothing is stored, for both the first agenda load and a `loadMore` chunk. `AgendaCacheGraphTest` checks that the production Hilt graph binds `AgendaCache` to the Room singleton and that the `SessionCache` set run on logout clears it.
- CR-2 (failed calendar list wipes cached calendars): resolved. `CalendarViewModel.load()` (line 315) stores the calendar list only from an `Ok` answer, otherwise the calendars already cached. Covered by `an agenda loaded without the calendar list keeps the cached calendars` and `... without a cache stores no calendars`.

## New tests in 6fc162929

Both tests target the offline fallback in `CalendarViewModel.load()` lines 318-328 and were checked against that code:

- `offline events without a calendar list keep the calendars loaded in this session over the cached ones`: the session first loads a renamed calendar (`Ok([renamed])`), then an older cache holding the original calendar is planted, and a day view is loaded with both events and calendars offline. It asserts the cached events, the cache's `cachedAt`, and that the session's `[renamed]` calendars are kept. This exercises the `current.calendars.ifEmpty { cached.calendars }` branch (line 325); replacing it with `cached.calendars` would fail the test. Meaningful.
- `offline events with a loaded calendar list keep a visibility choice still being written`: a `setVisible(work, false)` call is held open with a `CompletableDeferred`, then a day view loads with the calendar list `Ok` (server still reporting `visible = true`) and the events offline. It asserts the cached events and `cachedAt`, that `work` is shown hidden (pending choice applied via `withCalendars`, line 324) next to `home`, and that `pendingVisibility` still holds the choice. Applying the raw fetched list in the fallback would fail the test. The deferred is completed at the end so the test leaves no job hanging. Meaningful.

Neither test weakens or removes existing assertions; both use the existing `FakeCache`, `viewModel()` and fixture helpers and follow the file's conventions.

## Verified behavior

- AC1: every successful agenda load is stored with its calendar list in one Room transaction (`AgendaCacheDao.replace`); scrolled chunks are appended only when they continue the cached days; a refresh on a later day replaces the cache. Robolectric Room tests in `AgendaCacheTest` and view-model tests in `CalendarViewModelCacheTest`.
- AC2: offline with nothing loaded for the range shows the cached agenda from today on, or a day/week only when fully inside the cached days; `cachedAt` drives the banner with load time, disabled chips, the `setVisible` guard, no load-more and no editor open; the next `Ok` clears `cachedAt`. The calendars shown with cached events are the freshest available (loaded now, else this session's, else cached), now covered by the two new tests. `CalendarViewModelCacheTest` and `CalendarScreensCachedTest`.
- AC3: logout clears the cache through the `SessionCache` binding; writes of loads that started before it are dropped. Tested at cache, view-model and Hilt-graph level.
- Robustness: SQLite and parse errors on read/write degrade to "no cache"; Room uses destructive fallback (copy of server data only); no logging added.
- AC4: per the parent, `android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug` passed on 6fc162929 (1880 tests, 0 failures), and `sh scripts/check.sh` passed on 6f394e2fb (same production code). Not re-run by the reviewer (sub-agents cannot run Gradle here). The AC1-AC4 evidence recorded with `tasks.py verify` names 6f394e2fb; the parent may want to note the 6fc162929 run as well.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.
