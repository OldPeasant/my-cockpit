---
title: Android bookmarks: add, read-later toggle, reader, to inbox
status: in_progress
owner: dell26/agent-3
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/bookmarks,android/app/src/test/java/ch/sonensei/cockpit/bookmarks,android/app/src/main/res/values/strings_bookmarks.xml,android/app/src/main/res/values-de/strings_bookmarks.xml]
prio: 2
lease_until: 2026-11-08T18:31:03Z
extended: 
releases: 1
---
## Goal
Android can add bookmarks only through the share sheet and has no snapshot reader (web: `bookmarks-page.html:25-56`,
`bookmark-reader.html`). SPEC.md §4.5 also wants bookmark → Inbox task (neither client has it).

## Acceptance criteria
- [x] "Add bookmark" in the screen (URL, optional title/tags, read later)
  - verified (2026-11-08 19:12+0100 by dell26/agent-3): BookmarksAddReaderViewModelTest + BookmarksAddReaderScreenTest (add dialog: URL, optional title/tags, read later, server field errors) via cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug -> BUILD SUCCESSFUL, 4387 tests 0 failures (161 bookmarks tests)
- [ ] Toggle read-later on an existing bookmark
- [ ] Snapshot reader view (server snapshot)
- [ ] "To Inbox task" action
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- extended by rudi/agent-2: Implementation + CR-1..5 fixes done (HEAD 8e3dc677); 2nd review.md: 0 P0-P2, 2 optional P3 (reader-close cancels save -> stale version; 'Nothing was changed' text on offline create). android ktlintCheck testDebugUnitTest lintDebug passes. Left: re-run test-writer on final code (tests.md is stale, test files changed after), optionally fix the 2 P3s (then re-review), tasks.py verify #1-#6 (scripts/check.sh web part fails on Node v20.9.0 here: skip-record it), write+commit quality.json, done.
- release by rudi/agent-2: All 4 features + EN/DE strings implemented; 3 review rounds done, latest review.md (HEAD 720f70a0): 0 P0-P2, 2 optional P3 (CR-1 rename shadowing local taskId in BookmarksRepository.toInbox; CR-2 reader reopened during running save shows no saving state). android ktlintCheck testDebugUnitTest lintDebug passes on 720f70a0. LEFT: (1) optionally fix the 2 P3s via fixer (CR-1 is a 1-line rename); (2) re-run test-writer on final code (tests.md is stale) and, if code changed, code-reviewer again; (3) tasks.py verify #1-#6 (scripts/check.sh web part fails on Node v20.9.0 in this env: record it as --skip with that reason); (4) write+commit reviews/and-bookmarks-plus/quality.json; (5) tasks.py done.
