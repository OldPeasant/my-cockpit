---
title: Files: model and blob storage
status: in_progress
owner: dell26/agent-5
deps: [p0-trash-core,p0-search-core,p0-events-sse]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/files,server/src/test/kotlin/ch/sonensei/cockpit/files,server/src/main/resources/db/migration/files]
prio: 1
lease_until: 2026-10-20T15:21:11Z
extended: dell26/agent-5
---
## Goal
Folders, file metadata and on-disk blobs (SPEC.md §8.2).

## Acceptance criteria
- [ ] Migrations for folder (tree, unique name per parent) and file (name, mime, size, sha256, storage_key); blobs under `${COCKPIT_DATA_DIR}/blobs/ab/cd/<uuid>` written atomically (temp + move)
- [ ] Special folders `/Attachments/{Tasks,Notes,Mail,Journal}` created on demand and protected from deletion/rename
- [ ] Resolvers FILE/FOLDER, search indexing of names, trash contributor (purge deletes the blob)
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.files.*'` passes (Postgres via Testcontainers where persistence is involved) incl. path traversal in names rejected

## Notes
- ASSUMPTION (2026-10-20 15:59+0200 by dell26/agent-5): Deleting a folder trashes its whole subtree with one deletedAt; only trash roots (items whose parent is active) are listed in the Trash; restore brings back items with the same deletedAt, purge removes the whole subtree incl. blobs. Descendants cannot be restored/purged on their own (404).
- ASSUMPTION (2026-10-20 15:59+0200 by dell26/agent-5): Folder names unique per parent case-insensitively (lower(name)) among non-trashed folders; file names are NOT unique (like Google Drive). Restoring a folder whose name is taken renames it 'Name (2)'.
- ASSUMPTION (2026-10-20 15:59+0200 by dell26/agent-5): Special folders: a user folder already at the place (e.g. root 'Attachments') is adopted as the special folder; special folders are rejected with 409 Conflict on rename/delete. Moving is not implemented here (files-api must refuse moving special folders).
- ASSUMPTION (2026-10-20 15:59+0200 by dell26/agent-5): Blob storage key uses a random UUIDv4 (not the file id) so ab/cd spread evenly; cockpit.data-dir defaults to COCKPIT_DATA_DIR or 'data' (relative). Deep links: /files/{id} and /files/folders/{id}.
- extended by dell26/agent-5: Implementation, tests, review and fixes done and committed (179b34a); scripts/check.sh passes. Left: re-run code-reviewer on final code (review.md predates fixes), confirm tests.md matches final revision, run files Testcontainers tests, tasks.py verify for 4 criteria, write+commit quality.json, done.
