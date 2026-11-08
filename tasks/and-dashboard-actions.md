---
title: Android dashboard: tappable items, review button, layout editing
status: todo
owner: 
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/dashboard,android/app/src/test/java/ch/sonensei/cockpit/dashboard,android/app/src/main/res/values/strings_dashboard.xml,android/app/src/main/res/values-de/strings_dashboard.xml]
prio: 2
lease_until: 
releases: 1
extended: 
---
## Goal
On Android only task items in dashboard widgets are tappable; the review widget has no Start/Continue button and
the layout cannot be edited (web: `dashboard-page.html`).

## Acceptance criteria
- [ ] Items of every widget open their item (events, projects, bookmarks, notes, mails)
- [ ] The weekly review widget has Start/Continue review (opens the review flow from and-review-flow, or the review screen until then)
- [ ] "Customize": show/hide widgets and reorder (long-press drag + accessibility actions), reset; saved in the same setting the web uses
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 18:51+0100 by rudi/agent-1): Mails: the dashboard lists no individual mails (web neither); the Inbox card's 'unread emails' line opens Mail and its 'tasks to process' line the GTD Inbox, as the web readouts do.
- ASSUMPTION (2026-11-08 18:52+0100 by rudi/agent-1): Events open the event editor on top of the dashboard (birthdays their contact), more than the web's link to /calendar; without calendars (offline) the Calendar screen opens. Projects open via ProjectOpenRequests in the Projects screen; read-later bookmarks open their URL in the browser like the Bookmarks screen (Bookmarks screen if no browser); pinned notes open the note editor on top of the dashboard.
- ASSUMPTION (2026-11-08 18:52+0100 by rudi/agent-1): Customize and its layout panel sit below the cards (not in a header as on the web), so the first card stays at the top; each change is saved at once via PATCH /settings dashboardWidgets with If-Match, a stale ETag re-reads and re-sends once (last layout wins), a failure rolls back and shows a message.
- ASSUMPTION (2026-11-08 18:52+0100 by rudi/agent-1): Added EventEditorCallbacks.of(editor) in calendar/edit (outside touches) so the dashboard and CalendarWithEditor share the editor wiring instead of duplicating 19 callbacks.
- ASSUMPTION (2026-11-08 18:52+0100 by rudi/agent-1): Review button: 'Continue the open review' while one is open, 'Start a weekly review' when none was done or the last is >= 7 days old (web REVIEW_DUE_DAYS); both open the Review screen (and-review-flow is done).
- release by rudi/agent-1: Implementation complete (c467bd9b) incl. fixes for first review (0d80259e, reviews/and-dashboard-actions/{review,fixes}.md) and WIP tests (f7732ddc, d8a941f3 adds DashboardRouteInAppTest). 'cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug' passes at d8a941f3. Left: code changed after the first review, so re-run test-writer (must write reviews/and-dashboard-actions/tests.md with coverage; none exists yet) and code-reviewer on final code; fix any findings; tasks.py verify criteria 1-5; run sh scripts/check.sh; write+commit quality.json; done.
