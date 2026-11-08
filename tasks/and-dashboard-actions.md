---
title: Android dashboard: tappable items, review button, layout editing
status: in_progress
owner: rudi/agent-1
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/dashboard,android/app/src/test/java/ch/sonensei/cockpit/dashboard,android/app/src/main/res/values/strings_dashboard.xml,android/app/src/main/res/values-de/strings_dashboard.xml]
prio: 2
lease_until: 2026-11-08T17:59:14Z
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
