---
title: Readable snapshots
status: todo
owner:
deps: [bookmarks-api,files-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/snapshot,server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/snapshot]
prio: 3
---
## Goal
Save a readable copy (SPEC.md §8.4).

## Acceptance criteria
- [ ] 'Save snapshot' extracts the main content (Readability-style heuristics with jsoup), stores sanitized HTML as a file
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.bookmarks.snapshot.*'` passes (Postgres via Testcontainers where persistence is involved)

## Notes
