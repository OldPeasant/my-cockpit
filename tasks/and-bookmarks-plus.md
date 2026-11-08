---
title: Android bookmarks: add, read-later toggle, reader, to inbox
status: in_progress
owner: rudi/agent-2
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/bookmarks,android/app/src/test/java/ch/sonensei/cockpit/bookmarks,android/app/src/main/res/values/strings_bookmarks.xml,android/app/src/main/res/values-de/strings_bookmarks.xml]
prio: 2
lease_until: 2026-11-08T17:43:21Z
extended: rudi/agent-2
---
## Goal
Android can add bookmarks only through the share sheet and has no snapshot reader (web: `bookmarks-page.html:25-56`,
`bookmark-reader.html`). SPEC.md §4.5 also wants bookmark → Inbox task (neither client has it).

## Acceptance criteria
- [ ] "Add bookmark" in the screen (URL, optional title/tags, read later)
- [ ] Toggle read-later on an existing bookmark
- [ ] Snapshot reader view (server snapshot)
- [ ] "To Inbox task" action
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- extended by rudi/agent-2: Implementation + CR-1..5 fixes done (HEAD 8e3dc677); 2nd review.md: 0 P0-P2, 2 optional P3 (reader-close cancels save -> stale version; 'Nothing was changed' text on offline create). android ktlintCheck testDebugUnitTest lintDebug passes. Left: re-run test-writer on final code (tests.md is stale, test files changed after), optionally fix the 2 P3s (then re-review), tasks.py verify #1-#6 (scripts/check.sh web part fails on Node v20.9.0 here: skip-record it), write+commit quality.json, done.
