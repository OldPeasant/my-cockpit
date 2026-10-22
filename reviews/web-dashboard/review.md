# Code Review

Date: 2026-10-22

Task: web-dashboard - Dashboard

Scope: `git diff d54b8b3c0186aa51a8db33aa61e2f7ef7fad5c3a..9c2e59fce1eb6b70fd38c800ece61a9d6e2c0bbc` (merge base with origin/main to HEAD), 7 commits: 754456300 (server setting), fe13b3300 (dashboard), e3f6ce790 (app spec), 9d27831c5 (tests and reports), 6a54f71c5 (fixes for CR-1..CR-6), adda0e2ca (more tests), 9c2e59fce (fix for CR-7). This is the final review of the whole task. It checks each earlier finding against the current code and reviews the test additions in adda0e2ca and 9c2e59fce. Uncommitted changes (for example spec files from a parallel test-writer) are not included.

Requirements:
- tasks/web-dashboard.md acceptance criteria and recorded assumptions
- SPEC.md §8.7 (Dashboard), §4.7 (Settings), §9.1 (web state, SSE refresh)
- docs/ARCHITECTURE.md (stack, conventions, coverage)
- AGENTS.md project notes (touches, settings registration, generated API files)
- reviews/web-dashboard/fixes.md (the fixes being checked)
- Parent instructions (final review, check CR-1..CR-7, review the tests in adda0e2ca and 9c2e59fce)

Git range:
- Branch: main
- HEAD: 9c2e59fce1eb6b70fd38c800ece61a9d6e2c0bbc
- Base/start: d54b8b3c0186aa51a8db33aa61e2f7ef7fad5c3a (merge base with origin/main)
- Commits reviewed: 7

Summary: 0 P0, 0 P1, 0 P2, 0 P3 open. CR-1..CR-7 from the earlier reviews are fixed in the current code.

Notes on verification:
- Criterion 1: all eight widgets from SPEC.md §8.7 are there (`WIDGET_IDS`, `WIDGETS`, one template case per widget, data in `DashboardData.fetch`). Show/hide and order are saved in the `dashboardWidgets` setting (server `DashboardSettings`: a TextList of at most 50 distinct items, each up to 60 chars, default empty), through `SettingsService.update` with If-Match. `api/openapi.yaml` lists `Settings` with `additionalProperties: true`, and module settings such as `nextActionPresets` are not listed there either, so no regeneration is needed. The server setting is registered in `SettingsServiceTests.REGISTERED_KEYS` and tested in `DashboardSettingsWebTests` (default empty, order kept, duplicate and non-string items give 400). The setting is stored in `user_settings` like the other settings.
- Criterion 2: the layout, data and page specs cover persistence: the PATCH body, the If-Match chain, rollback, quick changes in a row, reset (shown at once, refused, followed by a change), loading the settings before a change, a failed settings load, and a 412 with a reload in between. I did not run `npm run check` myself. At review time the task file has verify evidence for criterion 1 only, so the parent still has to record criterion 2.
- CR-7 fix (`dashboard-layout.ts:151-172`): a reset now stays in `pending` until its save settles (the `finally` drops it). Applying `() => DEFAULT_LAYOUT` is idempotent, so showing it on top of `stored()` is safe. A change queued after the reset waits on `this.saving`, so it runs only after the reset has settled. It is then built on the server's default (an empty list, so `DEFAULT_LAYOUT`) or, if the reset failed, on the rolled-back layout. On a refused reset the `finally` drops it and `SettingsService.save` has already brought back the previous values. The new spec "shows a reset, and a change made after it, while the reset save is unanswered" checks `slots()`/`visible()` while the reset PATCH is unanswered, after it answers, and the body of the following PATCH.
- Tests in adda0e2ca: the data specs cover a failed quiet reload after a widget is shown again (old data stays, no error), a visible reload of a widget whose first load failed while it was hidden, and an older quiet reload overtaken by a newer change-event reload (the older answer is dropped through `seq`). The layout specs cover a change dropped when the settings cannot load (no PATCH, and the next change saves on the loaded layout), several changes made before the settings load, a queued change built on another device's layout after a 412 (new ETag `"9"` is sent), and a refused reset. Each test asserts behavior (request bodies, headers, shown state) and matches the current code paths. The spec "applies every change made before the settings are loaded" flushes every settings GET with `http.match`, because each early change calls `ensureLoaded()` while the settings are still null. `FilterPresets.ensureLoaded` works the same way and `SettingsService.load` drops late answers, so this is existing project behavior and not a defect of this task.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [x] **CR-1 | Quick customize clicks during an unanswered settings PATCH lose earlier changes** (fixed in 6a54f71c5)
    - File: `web/src/app/features/dashboard/dashboard-layout.ts:151-172`
    - Category: Correctness
    - Problem: `change()` built the new layout from `slots()`. A save queued behind an unanswered PATCH was not shown yet, so the next click started from the same old layout.
    - Why it matters: Quick clicks were lost without any message.
    - Suggested fix: Build each change on the newest intended layout.
    - Status: Fixed. `DashboardLayout` keeps a `pending` list that `slots()` applies, and its own save queue. Each change is computed from `stored()` after the earlier saves have settled. Specs cover quick changes in a row, a refused change between later ones, and the 412 case (adda0e2ca).

## P3 Low

- [x] **CR-7 | Reset is not shown optimistically, and changes made right after it show on the old layout until the reset PATCH answers** (fixed in 9c2e59fce)
    - File: `web/src/app/features/dashboard/dashboard-layout.ts:159-163`
    - Category: Correctness
    - Problem: The reset change was dropped from `pending` before `settings.update({ dashboardWidgets: null })`, and `SettingsService` cannot show a `null` reset, so the old layout showed until the server answered.
    - Why it matters: On a slow connection, Reset looked as if it did nothing, and changes made after it showed on the wrong layout for a short time.
    - Suggested fix: Keep a reset change in `pending` until its save settles.
    - Status: Fixed. The reset branch returns `settings.update(...)` without `drop`, so the reset shows until the `finally`. The class comment says so. Spec: "shows a reset, and a change made after it, while the reset save is unanswered" (dashboard-layout.spec.ts:263-291).

- [x] **CR-2 | "N+ unread mail" says there are more messages, not more unread ones; it can show "0+"** (fixed in 6a54f71c5)
    - File: `web/src/app/features/dashboard/dashboard-data.ts:217-231`
    - Category: Correctness
    - Problem: `more` was `!!mail.nextCursor`.
    - Suggested fix: Show "+" only when the page may have cut off unread mail.
    - Status: Fixed. `more` is `unread > 0 && !!mail.nextCursor`, recorded as an assumption, and covered by a spec.

- [x] **CR-3 | Weekly review: "overdue" styling and the Start button use different thresholds** (fixed in 6a54f71c5)
    - File: `web/src/app/features/dashboard/dashboard-page.html:286`, `:305`
    - Category: Correctness
    - Problem: The figure used `>`, the button used `>=`.
    - Suggested fix: Use the same comparison in both places.
    - Status: Fixed. Both use `days >= reviewDueDays`, recorded as an assumption. The boundary spec checks 6 and 7 days.

- [x] **CR-4 | The `WEEKLY_REVIEW` change-event subscription never fires** (fixed in 6a54f71c5)
    - File: `web/src/app/features/dashboard/dashboard-data.ts:73-87`
    - Category: Integration
    - Problem: The subscription was for an event the server never sends.
    - Suggested fix: Remove it, or document that it waits for a server event.
    - Status: Fixed. The `weeklyReview` source list is empty, and the doc comment and an assumption explain why. A spec checks that no reload happens on change events.

- [x] **CR-5 | A hidden widget shown again keeps its old data, and `show()` sends a second request for widgets still loading** (fixed in 6a54f71c5)
    - File: `web/src/app/features/dashboard/dashboard-data.ts:126-137`
    - Category: Quality
    - Problem: A ready widget shown again kept stale data, and a widget still loading was requested again.
    - Suggested fix: Reload a widget quietly when it is shown again, and skip widgets that are loading.
    - Status: Fixed. A ready widget shown again reloads quietly. A failed widget, or one never requested, loads visibly. A widget whose first load is running is left alone. The specs cover each branch, including the edge cases added in adda0e2ca.

- [x] **CR-6 | The page spec has a misleading test name and comment** (fixed in 6a54f71c5)
    - File: `web/src/app/features/dashboard/dashboard-page.spec.ts`
    - Category: Tests
    - Problem: The test name and comment did not match what the test does.
    - Suggested fix: Rename the test and fix the comment.
    - Status: Fixed. The test is renamed and the comment corrected.

## Review conclusion

No material issues found. CR-1..CR-7 are fixed in the current code, and the test additions in adda0e2ca and 9c2e59fce are sound. The parent still has to record `verify` evidence for criterion 2 (`cd web && npm run check`), run `.taskcheck` and write `quality.json`.
