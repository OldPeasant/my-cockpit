# Code Review

Date: 2026-10-26

Task: and-new-view - Android combined New view

Scope: `git diff f070fb101e6dfce5f3faca962050d2908e5c864c..09981b0e4` (commits 86793ac25, 09981b0e4): new package `android/app/src/main/java/ch/sonensei/cockpit/newitems`, its tests, the `New` destination in `ui/navigation/CockpitDestination.kt`, `ui/CockpitShell.kt`, `res/values/strings.xml`, `res/values/strings_new.xml`.

Requirements:
- tasks/and-new-view.md (acceptance criteria and the three recorded assumptions)
- SPEC.md §5.3 "New view", §7.3, §9.2, §9.3
- AGENTS.md project notes, docs/ARCHITECTURE.md conventions
- Web counterpart `web/src/app/features/new` (new-feed.ts, new-page.ts)
- Existing Android code: `mail/list/MailScreen.kt`, `mail/read`, `gtd/data/GtdRepositories.kt`, `auth/AuthRepository.kt` (SessionCache contract), `calendar/agenda/AgendaCache.kt`

Git range:
- Branch: main
- HEAD: 09981b0e4
- Base/start: f070fb101e6dfce5f3faca962050d2908e5c864c
- Commits reviewed: 2

Summary: 0 P0, 1 P1, 2 P2, 2 P3

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | A mail load still running at logout puts the previous user's mails back into the in-memory cache**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/newitems/NewViewModel.kt:245` (also `:172`, `:205`; cache in `NewStores.kt:30-39`)
    - Category: Security
    - Problem: `refresh()` and `loadMore()` run in `viewModelScope`, which is not tied to the screen being shown or to the session. When they finish they call `remember()`, which sets `cache.last` without checking whether a logout cleared the cache since the request started. Here is how it happens: the New screen starts a refresh (`LifecycleResumeEffect`). The user switches to Settings and logs out. `SessionCache.clear()` sets `last = null`. Then the in-flight response arrives and `remember()` writes the old session's unified-inbox page (subjects, senders, snippets) back into the singleton. The next `NewViewModel`, created after the next login (possibly as another user), starts from `cache.last` (`NewViewModel.kt:111`). It shows those mails at once, and while offline it keeps showing them as "Mail as loaded at ...".
    - Why it matters: The recorded assumption (and the reason the cache is a `SessionCache` at all) is that a logout drops the mail. `AgendaCache` (`calendar/agenda/AgendaCache.kt:43-47`) fixes exactly this race with a session counter ("after a SessionCache clear the writes of loads started before it are dropped"). The New cache does not follow that pattern, so mail metadata can leak across sessions on a shared device.
    - Suggested fix: Give `NewMailCache` a session/generation counter that `clear()` increments. Capture it when a load starts and have `remember()` (or a `store(session, ...)` method) drop the write when it no longer matches. Add a test: clear the cache while a fake `MailRepository` call is suspended, complete the call, and check that `cache.last` stays `null`.

## P2 Medium

- [ ] **CR-2 | Offline (or after a failed mail load), the All filter hides cached GTD Inbox tasks with no hint and no way to show them**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/newitems/NewModels.kt:46` (fed from `NewViewModel.kt:118`)
    - Category: Correctness
    - Problem: `mergeNew` holds back tasks older than the oldest loaded mail whenever `nextCursor != null`. When the mail part is offline or failed and the cached page has a cursor, "Load older messages" can never succeed. Every cached Inbox task older than that page then stays hidden under All for as long as the server is unreachable. Tasks without a UUIDv7 timestamp (`at == null` becomes `Instant.MIN`) are always hidden while more mail exists. Nothing tells the user that tasks are being held back.
    - Why it matters: Criterion 1 requires "shows cached data offline". The GTD Inbox comes from the Room cache and is fully available offline, but the default view hides part of it. The hold-back only makes sense while the next page can actually be loaded.
    - Suggested fix: Pass `moreMail` to `mergeNew` only while the mail part is neither offline nor failed (e.g. `m.nextCursor != null && !m.offline && !m.failed`). Add a model or view-model test for the offline case.

- [ ] **CR-3 | Main paging and concurrency paths of NewViewModel are untested**
    - File: `android/app/src/test/java/ch/sonensei/cockpit/newitems/NewViewModelTest.kt` (covers `NewViewModel.kt:140-249`)
    - Category: Tests
    - Problem: At 09981b0e4 the view-model tests cover the first load plus cache, offline from the cache, filter persistence, a successful `toTask`, and `removed`. These paths have no tests: the `refresh()` merge that keeps older pages (`:151-158`), `loadMore()` (appending, dedup, offline/failure), a refresh cancelling a running `loadMore`, `toTask` offline/failure notices and the double-tap guard, the session clear of `NewMailCache` (`NewStores.kt:75`), and `PreferencesNewFilterStore` reading an unknown or malformed stored value (`NewStores.kt:56`).
    - Why it matters: Most of the view model's logic sits in the page merge and the request interplay. A regression there (lost pages, a wrong cursor, a stuck `loadingMore`) would not be caught. The stored-filter read is the kind of read-back that must not break startup.
    - Suggested fix: Add focused tests for these paths. The test-writer ran in parallel and may have added some. Check what is still missing on the final revision.

## P3 Low

- [ ] **CR-4 | Returning from the mail read view or the task view scrolls the New list back to the top**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/newitems/NewScreen.kt:267`
    - Category: Quality
    - Problem: `Rows` builds its `LazyColumn` without a hoisted `LazyListState`. Opening a row replaces `NewContent` with the read view or the task view, so the list's scroll position is lost when the user goes back. After "Load older messages" the user lands at the top again. `MailRoute` (`mail/list/MailScreen.kt:105`, `:128`) hoists `rememberLazyListState()` in the route for exactly this reason.
    - Why it matters: Working through a long merged list one row at a time is the main use of this view.
    - Suggested fix: Create `rememberLazyListState()` in `NewRoute` and pass it through `NewContent` to `Rows`, as `MailRoute` does.

- [ ] **CR-5 | An opened task is looked up in the filtered and held-back rows, so a mail refresh can close it**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/newitems/NewViewModel.kt:72` (used at `NewScreen.kt:147-151`)
    - Category: Correctness
    - Problem: `NewUiState.task(id)` searches `rows`, not the Inbox data. If a refresh shrinks the loaded mail window while a task is open, the task can drop out of `rows` even though it is still in the Inbox, and `NewRoute` sets `open = null`. This happens when the new first page no longer overlaps the loaded pages, or after process death, when the cache is gone and the first page has a cursor. The resume refresh triggers it, for example when the user comes back to the app.
    - Why it matters: The task view closes on the user for no visible reason.
    - Suggested fix: Look the task up in the unfiltered Inbox list (e.g. keep `inbox.data` in the UI state, or expose a separate lookup), so that only a task actually processed closes the view.

## Review conclusion

Material issues found; fix before completing the task.

Verified without findings: the navigation entry (More list, route `new`, string, destination test order), the newest-first merge and its tie-break, each filter and its SharedPreferences persistence (unknown values fall back to All), tapping a row to open the task view or `MailReadRoute` with seen/removed callbacks, "→ Inbox task" (disabled offline, guarded against double taps, mail stays, Inbox refreshed after success), the offline banner, no logging of mail or task content in the new code, no mail written to disk, and `NewMailCache` registered as a `SessionCache`. Acceptance-criterion evidence is not recorded yet (`tasks.py verify and-new-view` shows both criteria open). That is the parent worker's remaining step, not a finding.
