---
title: Android dashboard: tappable items, review button, layout editing
status: in_progress
owner: dell26/agent-1
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/dashboard,android/app/src/test/java/ch/sonensei/cockpit/dashboard,android/app/src/main/res/values/strings_dashboard.xml,android/app/src/main/res/values-de/strings_dashboard.xml]
prio: 2
lease_until: 2026-11-09T02:37:37Z
releases: 0
extended: 
hold: 
---
## Goal
On Android only task items in dashboard widgets are tappable; the review widget has no Start/Continue button and
the layout cannot be edited (web: `dashboard-page.html`).

## Acceptance criteria
- [x] Items of every widget open their item (events, projects, bookmarks, notes, mails)
  - verified (2026-11-08 20:28+0100 by felix/agent-1): cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug --continue -> 4468 tests, all dashboard tests pass incl. DashboardRouteInAppTest (project/bookmark/event editor/birthday contact/note editor) and DashboardActionsScreenTest item openers + Inbox mail/tasks lines; only failure auth/AppStartRoutingTest, which also fails on origin/main with the task files reverted
- [x] The weekly review widget has Start/Continue review (opens the review flow from and-review-flow, or the review screen until then)
  - verified (2026-11-08 20:28+0100 by felix/agent-1): same run -> DashboardActionsScreenTest review button states (Start/Continue) and DashboardRouteInAppTest review opens Review screen pass
- [x] "Customize": show/hide widgets and reorder (long-press drag + accessibility actions), reset; saved in the same setting the web uses
  - verified (2026-11-08 20:28+0100 by felix/agent-1): same run -> DashboardActionsScreenTest (checkbox toggle, a11y move actions, long-press drag, reset), DashboardSaveLayoutTest (PATCH /settings dashboardWidgets with If-Match, retry), DashboardSlotsTest pass
- [x] New strings are in English and German (`res/values/` + `res/values-de/`)
  - verified (2026-11-08 20:28+0100 by felix/agent-1): same run: lintDebug (MissingTranslation) passed; DashboardActionsScreenTest German strings test passes; 14 new strings in values/ and values-de/strings_dashboard.xml
- [~] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass
  - skipped (2026-11-08 20:38+0100 by felix/agent-1): ktlintCheck+lintDebug pass and all dashboard tests pass, but testDebugUnitTest fails on auth/AppStartRoutingTest 'logging out from settings clears the cached files' (ComposeTimeout at line 167), which fails identically on origin/main with this task's files reverted; sh scripts/check.sh was killed after ~20 min in this session

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 18:51+0100 by rudi/agent-1): Mails: the dashboard lists no individual mails (web neither); the Inbox card's 'unread emails' line opens Mail and its 'tasks to process' line the GTD Inbox, as the web readouts do.
- ASSUMPTION (2026-11-08 18:52+0100 by rudi/agent-1): Events open the event editor on top of the dashboard (birthdays their contact), more than the web's link to /calendar; without calendars (offline) the Calendar screen opens. Projects open via ProjectOpenRequests in the Projects screen; read-later bookmarks open their URL in the browser like the Bookmarks screen (Bookmarks screen if no browser); pinned notes open the note editor on top of the dashboard.
- ASSUMPTION (2026-11-08 18:52+0100 by rudi/agent-1): Customize and its layout panel sit below the cards (not in a header as on the web), so the first card stays at the top; each change is saved at once via PATCH /settings dashboardWidgets with If-Match, a stale ETag re-reads and re-sends once (last layout wins), a failure rolls back and shows a message.
- ASSUMPTION (2026-11-08 18:52+0100 by rudi/agent-1): Added EventEditorCallbacks.of(editor) in calendar/edit (outside touches) so the dashboard and CalendarWithEditor share the editor wiring instead of duplicating 19 callbacks.
- ASSUMPTION (2026-11-08 18:52+0100 by rudi/agent-1): Review button: 'Continue the open review' while one is open, 'Start a weekly review' when none was done or the last is >= 7 days old (web REVIEW_DUE_DAYS); both open the Review screen (and-review-flow is done).
- release by rudi/agent-1: Implementation complete (c467bd9b) incl. fixes for first review (0d80259e, reviews/and-dashboard-actions/{review,fixes}.md) and WIP tests (f7732ddc, d8a941f3 adds DashboardRouteInAppTest). 'cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug' passes at d8a941f3. Left: code changed after the first review, so re-run test-writer (must write reviews/and-dashboard-actions/tests.md with coverage; none exists yet) and code-reviewer on final code; fix any findings; tasks.py verify criteria 1-5; run sh scripts/check.sh; write+commit quality.json; done.
- release by felix/agent-1: Re-review done (reviews/.../review.md: prior fixes hold, 1 new P3 a11y: Customize row label read twice - accept as non-blocking) and test-writer done (tests.md, no new tests, dashboard coverage 98.6% lines). Both reviewed code 78ac21c00 (android unchanged at d990e77c3). Left: full 'cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug' failed twice on a flaky out-of-task test files/FilePreviewScreenTest (different case each run, passes alone) so lintDebug not yet run; rerun (or run lintDebug separately), sh scripts/check.sh, tasks.py verify 1-5, write quality.json (fixer not_required; CR P3 accepted), done.
- release by felix/agent-1: All review evidence done and committed (quality.json 7907203d3, reviewed_revision 78ac21c00), criteria 1-4 verified. Blocker: android testDebugUnitTest fails on PRE-EXISTING main failure auth/AppStartRoutingTest 'logging out from settings clears the cached files' (reproduced on origin/main with task files reverted, so not this task). Left: once that test is fixed on main (or confirmed flaky), rerun cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug and sh scripts/check.sh (it ran >20 min here, killed), re-verify #5 with real evidence, done.
