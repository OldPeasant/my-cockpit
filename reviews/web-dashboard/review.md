# Code Review

Date: 2026-10-22

Task: web-dashboard - Dashboard

Scope: `git diff d83c9595c..HEAD` (`origin/main..HEAD`), 5 commits: f700c5c5f (server setting), e57165509 (dashboard), 3749014bf (app spec), 4a9791ca0 (tests and reports), f4a9b4903 (fixes for CR-1..CR-6). This is the second review, after the fixes. It covers the whole task diff against main and checks each earlier finding against the current code. Uncommitted spec changes from a parallel test-writer are not included.

Requirements:
- tasks/web-dashboard.md acceptance criteria and recorded assumptions (including the 10:38 assumption about the review fixes)
- SPEC.md §8.7 (Dashboard), §4.7 (Settings), §9.1 (web state, SSE refresh)
- docs/ARCHITECTURE.md (stack, i18n, coverage)
- AGENTS.md project notes (touches, settings registration, generated API files)
- reviews/web-dashboard/fixes.md (the fixes being checked)
- Parent instructions (focus on the pending-change queue in dashboard-layout.ts and on `DashboardData.show`)

Git range:
- Branch: main
- HEAD: f4a9b4903ed678efdc4316e7576e54d57c35703d
- Base/start: d83c9595c3450a47b1d5d3ba5ccb579a366cc077 (origin/main, merge base)
- Commits reviewed: 5

Summary: 0 P0, 0 P1, 0 P2, 1 P3 open (CR-7). CR-1..CR-6 from the first review are fixed.

Notes on verification:
- Criterion 1: all eight widgets from SPEC.md §8.7 are there (`WIDGET_IDS`, `WIDGETS`, template cases). Show/hide and order are saved in the `dashboardWidgets` setting (server `DashboardSettings`, TextList with at most 50 distinct items of up to 60 chars) through `SettingsService` PATCH with If-Match. The server part and the app/i18n spec changes have not changed since the first review.
- Criterion 2: the layout, data and page specs cover persistence: the PATCH body, the If-Match chain, rollback, quick changes in a row, reset, loading the settings before a change, and a failed settings load. I did not run `npm run check` (the fixer reports it passed: 164 files, 3343 tests). The task file has no recorded `verify` evidence yet, and the parent still has to record it.
- Pending-change queue (`dashboard-layout.ts:151-167`): each change is shown at once through `pending`. Its step runs only after the previous `settings.update()` has settled, so `apply(this.stored())` works on the server's accepted value or on the rollback. On a 412, `SettingsService.save` reloads before it rethrows, so the next step starts from fresh settings and the fresh ETag. The `finally` drop also covers a failed settings load. Moves are not idempotent, but they are not applied twice: the change leaves `pending` in the same synchronous step that hands it to `SettingsService`, and that service shows it on its next microtask, before any render.
- `DashboardData.show` (`dashboard-data.ts:126-137`): a ready widget that is shown again reloads quietly. A failed widget, or one never requested, loads visibly. A widget whose first load is running (status `loading` and a `seq` entry) is left alone. Widgets that stay shown are not reloaded. The specs cover each branch.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [x] **CR-1 | Quick customize clicks during an unanswered settings PATCH lose earlier changes** (fixed in f4a9b4903)
    - File: `web/src/app/features/dashboard/dashboard-layout.ts:151-167`
    - Category: Correctness
    - Problem: `change()` built the new layout from `slots()`. A save queued behind an unanswered PATCH was not shown yet, so the next click started from the same old layout.
    - Why it matters: Quick clicks were lost without any message.
    - Suggested fix: Build each change on the newest intended layout.
    - Status: Fixed. `DashboardLayout` keeps a `pending` list that `slots()` applies, and its own save queue. Each change is computed from `stored()` after the earlier saves have settled. Specs: "builds quick changes on each other while a save is unanswered" (4 changes, every PATCH body and the ETag chain), "drops a refused change but keeps the quick changes made after it", "builds a change made right after a reset on the default layout". Checked against `SettingsService.update/save`. See the note above.

## P3 Low

- [ ] **CR-7 | Reset is not shown optimistically, and changes made right after it show on the old layout until the reset PATCH answers**
    - File: `web/src/app/features/dashboard/dashboard-layout.ts:142-144`, `:159-162`
    - Category: Correctness
    - Problem: `reset()` puts `() => DEFAULT_LAYOUT` in `pending`. Its step then drops the change and calls `settings.update({ dashboardWidgets: null })`. `SettingsService.save` removes `null` values from what it shows (`withoutResets`, settings.service.ts:58, :93-95), so while the reset PATCH runs, `stored()` is still the old layout and nothing in `pending` shows the reset. The dashboard therefore goes back to the old layout until the server answers. A change queued after the reset (for example hiding Today) is drawn on the old layout during that time. The final saved layout is correct, which the spec at dashboard-layout.spec.ts:242-261 checks, but no spec checks what shows while the reset is in flight.
    - Why it matters: Every other customize action shows at once. On a slow connection, Reset looks as if it did nothing, the user may click it again, and quick changes made after it briefly show on the wrong base. This is display only. No data is lost.
    - Suggested fix: Keep a reset change in `pending` until its save settles. `() => DEFAULT_LAYOUT` is idempotent, so applying it on top of `stored()` is safe. For example, call `drop` before `update` only when `reset` is false. Add a check of `slots()` while the reset PATCH is unanswered.

- [x] **CR-2 | "N+ unread mail" says there are more messages, not more unread ones; it can show "0+"** (fixed in f4a9b4903)
    - File: `web/src/app/features/dashboard/dashboard-data.ts:217-231`
    - Category: Correctness
    - Problem: `more` was `!!mail.nextCursor`.
    - Suggested fix: Show "+" only when the page may have cut off unread mail.
    - Status: Fixed. `more` is now `unread > 0 && !!mail.nextCursor`, recorded as an assumption. Spec: "flags the unread mail count as cut only when the cut page has unread mail".

- [x] **CR-3 | Weekly review: "overdue" styling and the Start button use different thresholds** (fixed in f4a9b4903)
    - File: `web/src/app/features/dashboard/dashboard-page.html:286`, `:305`
    - Category: Correctness
    - Problem: The figure used `>`, the button used `>=`.
    - Suggested fix: Use the same comparison in both places.
    - Status: Fixed. Both use `days >= reviewDueDays`, recorded as an assumption. The boundary spec checks 6 and 7 days.

- [x] **CR-4 | The `WEEKLY_REVIEW` change-event subscription never fires** (fixed in f4a9b4903)
    - File: `web/src/app/features/dashboard/dashboard-data.ts:73-87`
    - Category: Integration
    - Problem: The subscription was for an event the server never sends.
    - Suggested fix: Remove it, or document that it waits for a server event.
    - Status: Fixed. The `weeklyReview` source list is empty, the doc comment and the assumption explain why, and a spec checks that no reload happens on change events.

- [x] **CR-5 | A hidden widget shown again keeps its old data, and `show()` sends a second request for widgets still loading** (fixed in f4a9b4903)
    - File: `web/src/app/features/dashboard/dashboard-data.ts:126-137`
    - Category: Quality
    - Problem: A ready widget shown again kept stale data, and a widget still loading was requested again.
    - Suggested fix: Reload a widget quietly when it is shown again, and skip widgets that are loading.
    - Status: Fixed. See the note above. Data specs: "reloads a loaded widget quietly when it is shown again after being hidden", "does not request a widget again while its first load is running". Page spec: "keeps a widget's data on screen while it reloads after being shown again".

- [x] **CR-6 | The page spec has a misleading test name and comment** (fixed in f4a9b4903)
    - File: `web/src/app/features/dashboard/dashboard-page.spec.ts:257`, `:274`
    - Category: Tests
    - Problem: The test name and comment did not match what the test does.
    - Suggested fix: Rename the test and fix the comment.
    - Status: Fixed. The test is renamed and the comment corrected. The test-writer's "moves a widget up" spec covers the up move, and the settings-failure page spec already existed.

## Review conclusion

No material issues found. CR-1..CR-6 are fixed in the current code. CR-7 is a small display problem (Reset is not optimistic) and does not block the task: fix it or record it with a reason. The parent still has to record the `verify` evidence for both criteria and run `.taskcheck`.
