---
title: Readable snapshots
status: in_progress
owner: dell26/agent-1
deps: [bookmarks-api,files-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/snapshot,server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/snapshot]
prio: 3
lease_until: 2026-10-25T20:57:35Z
---
## Goal
Save a readable copy (SPEC.md §8.4).

## Acceptance criteria
- [ ] 'Save snapshot' extracts the main content (Readability-style heuristics with jsoup), stores sanitized HTML as a file
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.bookmarks.snapshot.*'` passes (Postgres via Testcontainers where persistence is involved)

## Notes
- ASSUMPTION (2026-10-25 21:25+0100 by dell26/agent-1): Save snapshot is POST /api/v1/bookmarks/{id}/snapshot (If-Match), synchronous fetch with the bookmark fetch limits (cockpit.bookmarks.fetch.*, 1MB default); controller lives in bookmarks/snapshot to stay inside touches; api/openapi.yaml and web generated client regenerated (required by convention). Fetch failures/no content -> 502 with errorCode.
- ASSUMPTION (2026-10-25 21:25+0100 by dell26/agent-1): Snapshot is a standalone sanitized HTML file (text/html, '<title> (snapshot).html') in the Attachments special folder (no Bookmarks special folder: files module outside touches); no images kept (would load remote content), CSP meta blocks remote loads; previous snapshot moved to Trash. Web reader currently shows non-Markdown files as plain text: rendering HTML snapshots there is left to the web side.
