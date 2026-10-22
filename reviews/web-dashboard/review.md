# Code Review

Date: 2026-10-22

Task: web-dashboard - Dashboard

Scope: `git diff 4bdf1f58a..HEAD`: the task commits 7ba64f2c4 (was 018e04578), b1cb9da2f (was ade6627fc), 2025c746e (was 86ae4e8d2), rebased; the code is the same as 86ae4e8d2. The other commits in the range only touch `tasks/` (assumptions, heartbeats).

Requirements:
- tasks/web-dashboard.md acceptance criteria and recorded assumptions
- SPEC.md §8.7 (Dashboard), §4.7 (Settings), §9.1 (web state, SSE refresh)
- docs/ARCHITECTURE.md (stack, i18n, coverage)
- AGENTS.md project notes (touches, settings registration, generated API files)
- Parent instructions (server setting registration outside `touches`, app spec changes)

Git range:
- Branch: main
- HEAD: 2025c746ec712beaf4a625c44202dda83cd0e4db
- Base/start: 4bdf1f58a81817bbf26bbda424261ed1e6d52906
- Commits reviewed: 3 code commits (8 in range; 5 touch only `tasks/`)

Summary: 0 P0, 0 P1, 1 P2, 5 P3

Notes on verification:
- All eight widgets from SPEC.md §8.7 exist (`WIDGET_IDS`, `WIDGETS`, template cases). Show/hide and order are stored in the `dashboardWidgets` setting through `SettingsService` (PATCH with If-Match, optimistic, rolled back on error). Reset sends `null`, which goes back to the server default.
- The server setting follows the `GtdSettings` precedent: a `SettingDefinition` bean, `TextList` validation (distinct strings, max 50 items of 60 chars). `Settings` is an open model with additional properties, so `api/openapi.yaml` and the generated client did not need to change.
- API parameters used by `DashboardData` match the generated client: `listTasks` `list`/`due`, `listProjects` `stalled`, `listBookmarks` `readLater`/`read`, `listNotes` `pinned`. `listMailMessages` without `folderId`/`accountId` returns the unified Inbox (MailMessageController.kt:58-62).
- Each widget handles errors on its own: SILENT_ERRORS on every request, a retry button, and a sequence number per widget so older answers are dropped.
- Server tests and web specs were not re-run by this reviewer (Gradle cannot run here). `tasks.py verify web-dashboard` shows no recorded evidence yet. That is expected before review, and the parent still has to record it.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Quick customize clicks during an unanswered settings PATCH lose earlier changes**
    - File: `web/src/app/features/dashboard/dashboard-layout.ts:133-136`
    - Category: Correctness
    - Problem: `change()` builds the new layout from `this.slots()` when the user clicks. `SettingsService.update()` (settings.service.ts:49-54) queues saves, and a queued save shows its optimistic value only when it starts. So while one PATCH is unanswered, every later click starts from the same old layout. Example: (1) hide Upcoming, and the PATCH is in flight; (2) move Today down, which is queued as L1+move; (3) move Today down again, which is also computed from L1 because step 2 is not shown yet, and is queued as L1+move. Step 3's save overwrites step 2, so Today moves one place, not two. The same happens with hiding two different widgets: the second patch does not include the first, so that widget shows again. The UI also ignores the second click until the first PATCH returns, which makes users click again.
    - Why it matters: Users click the up/down arrows several times in a row, and on a slow connection some of those clicks are silently lost. This is the "overlapping writes" case.
    - Suggested fix: Apply each change to the newest intended layout, not the last shown one. One option is a pending layout signal in `DashboardLayout` that the next change builds on: set it at once, use it for `slots()`, and clear it when the save settles or rolls back. Another is to compute the patch inside the queued save. Add a spec that makes two or three changes before flushing the first PATCH and checks that the last PATCH body contains all of them.

## P3 Low

- [ ] **CR-2 | "N+ unread mail" says there are more messages, not more unread ones; it can show "0+"**
    - File: `web/src/app/features/dashboard/dashboard-data.ts:209-213`
    - Category: Correctness
    - Problem: `unreadMail.count` counts the unseen messages among the newest 50, but `more` is `!!mail.nextCursor`, which is true whenever the Inbox has more than 50 messages. In a normal mailbox the widget shows "3+" or "0+ unread mail" even when exactly 3 or 0 messages are unread. The approximation itself is a recorded assumption (no unread-count API). The "+" on a count of unread mail is misleading, and "0+" is confusing.
    - Suggested fix: Show "+" only when the page may have cut off unread mail, for example when the counted page contained unread messages and `nextCursor` is set. Or give the number a clearer label. A follow-up task for a server unread count could resolve this properly.

- [ ] **CR-3 | Weekly review: "overdue" styling and the Start button use different thresholds**
    - File: `web/src/app/features/dashboard/dashboard-page.html:286` and `:305`
    - Category: Correctness
    - Problem: The figure gets the `alert` class at `days > reviewDueDays` (from day 8), but "Start a weekly review" shows at `days >= reviewDueDays` (from day 7). The recorded assumption says both happen "after 7 days".
    - Suggested fix: Use the same comparison in both places, matching the assumption, and test the boundary (days = 7).

- [ ] **CR-4 | The `WEEKLY_REVIEW` change-event subscription never fires**
    - File: `web/src/app/features/dashboard/dashboard-data.ts:80`
    - Category: Integration
    - Problem: Nothing on the server publishes a change event of type `WEEKLY_REVIEW`. The `review` module calls no `ChangeEventPublisher`, and `ItemType` has no such value. The Last weekly review widget therefore only updates on an SSE resync or when the page opens again.
    - Suggested fix: Remove the dead source, or note it as waiting for a server event. A review done in another tab then shows when the page opens again, which is acceptable.

- [ ] **CR-5 | A hidden widget shown again keeps its old data, and `show()` sends a second request for widgets still loading**
    - File: `web/src/app/features/dashboard/dashboard-data.ts:118-123`, `:139-141`
    - Category: Quality
    - Problem: `reload()` ignores change events for widgets that are not shown. `show()` reloads only widgets whose status is not `ready`. So a widget hidden while its data changed and then shown again shows the old data until the next event. Also, any layout change while a widget is still loading starts a second identical request. The sequence number makes the result correct, but the request is wasted.
    - Suggested fix: Remember which widgets got an event while hidden, or reload a widget quietly when it is shown again. Skip widgets whose status is `loading`.

- [ ] **CR-6 | The page spec has a misleading test name and comment**
    - File: `web/src/app/features/dashboard/dashboard-page.spec.ts:252`, `:269-270`
    - Category: Tests
    - Problem: "moves a widget down and up from the customize panel" only moves a widget down and checks that Up is disabled. The comment "A widget shown again after the move loads its data" sits above an assertion that no request is pending. There is also no page test for the settings-load failure path (`settingsFailed`: default layout, Customize disabled).
    - Suggested fix: Rename the test or add the up move. Fix the comment. Add a test where the settings GET fails.

## Review conclusion

Material issues found; fix before completing the task. CR-1 (lost layout changes from quick clicks) should be fixed. The P3 findings are small and can be fixed together or recorded with a reason.
