---
title: Full-text search in PDFs and text files
status: in_progress
owner: dell26/agent-2
deps: [files-preview,p0-search-core]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/files/fulltext,server/src/test/kotlin/ch/sonensei/cockpit/files/fulltext]
prio: 3
lease_until: 2026-10-26T13:05:54Z
---
## Goal
Search file contents (SPEC.md §4.4).

## Acceptance criteria
- [ ] Text extracted (PDFBox) in a background job, size-limited, indexed; search finds it
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.files.fulltext.*'` passes (Postgres via Testcontainers where persistence is involved)

## Notes
- ASSUMPTION (2026-10-26 13:36+0100 by dell26/agent-2): Extracted text is cached in a new table file_text (not backed up: rebuilt from blobs after a restore); FilesService.indexed(file) now re-reads it so rename/move/restore keep the body. Small edit outside touches (FilesService, build.gradle.kts pdfbox line, test application.properties scheduler switch).
- ASSUMPTION (2026-10-26 13:36+0100 by dell26/agent-2): Limits (cockpit.files.fulltext.*): max-file-size 20MB, max-chars 100000 (SearchIndexer body limit), max-pages 500; job every 30s (initial 1 min). Supported: application/pdf and the text types the preview treats as text.
