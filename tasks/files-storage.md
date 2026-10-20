---
title: Files: model and blob storage
status: in_progress
owner: dell26/agent-5
deps: [p0-trash-core,p0-search-core,p0-events-sse]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/files,server/src/test/kotlin/ch/sonensei/cockpit/files,server/src/main/resources/db/migration/files]
prio: 1
lease_until: 2026-10-20T16:01:33Z
extended: 
releases: 1
---
## Goal
Folders, file metadata and on-disk blobs (SPEC.md §8.2).

## Acceptance criteria
- [x] Migrations for folder (tree, unique name per parent) and file (name, mime, size, sha256, storage_key); blobs under `${COCKPIT_DATA_DIR}/blobs/ab/cd/<uuid>` written atomically (temp + move)
  - verified (2026-10-20 17:21+0200 by dell26/agent-5): cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.files.*' (Postgres via Testcontainers) at c54fce3 -> 90 tests, 0 failures; migration files/V202610201345__files_model.sql applied by Flyway, FilesEdgeCaseTests 'database rejects ... duplicate specials' + case-insensitive unique folder name tests, BlobStoreTests (ab/cd/<uuid> layout, temp+move, no temp left on failure) pass
- [x] Special folders `/Attachments/{Tasks,Notes,Mail,Journal}` created on demand and protected from deletion/rename
  - verified (2026-10-20 17:21+0200 by dell26/agent-5): cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.files.*' (Postgres via Testcontainers) at c54fce3 -> 90 tests, 0 failures; FilesServiceTests 'special folders are created on demand below Attachments, once', 'special folders cannot be deleted or renamed' (409), FilesEdgeCaseTests adoption tests pass
- [x] Resolvers FILE/FOLDER, search indexing of names, trash contributor (purge deletes the blob)
  - verified (2026-10-20 17:21+0200 by dell26/agent-5): cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.files.*' (Postgres via Testcontainers) at c54fce3 -> 90 tests, 0 failures; FilesServiceTests 'FILE and FOLDER resolvers and trash contributors are registered', 'resolvers show name, path...', 'names are searchable...', 'purging a folder deletes the whole subtree with blobs...', FilesConcurrencyTests purge/restore races pass
- [x] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.files.*'` passes (Postgres via Testcontainers where persistence is involved) incl. path traversal in names rejected
  - verified (2026-10-20 17:21+0200 by dell26/agent-5): cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.files.*' (Postgres via Testcontainers) at c54fce3 -> 90 tests, 0 failures, 0 errors, 0 skipped; incl. FileNamesTests 'path traversal, separators, control characters and blank names are rejected'

## Notes
- ASSUMPTION (2026-10-20 15:59+0200 by dell26/agent-5): Deleting a folder trashes its whole subtree with one deletedAt; only trash roots (items whose parent is active) are listed in the Trash; restore brings back items with the same deletedAt, purge removes the whole subtree incl. blobs. Descendants cannot be restored/purged on their own (404).
- ASSUMPTION (2026-10-20 15:59+0200 by dell26/agent-5): Folder names unique per parent case-insensitively (lower(name)) among non-trashed folders; file names are NOT unique (like Google Drive). Restoring a folder whose name is taken renames it 'Name (2)'.
- ASSUMPTION (2026-10-20 15:59+0200 by dell26/agent-5): Special folders: a user folder already at the place (e.g. root 'Attachments') is adopted as the special folder; special folders are rejected with 409 Conflict on rename/delete. Moving is not implemented here (files-api must refuse moving special folders).
- ASSUMPTION (2026-10-20 15:59+0200 by dell26/agent-5): Blob storage key uses a random UUIDv4 (not the file id) so ab/cd spread evenly; cockpit.data-dir defaults to COCKPIT_DATA_DIR or 'data' (relative). Deep links: /files/{id} and /files/folders/{id}.
- extended by dell26/agent-5: Implementation, tests, review and fixes done and committed (179b34a); scripts/check.sh passes. Left: re-run code-reviewer on final code (review.md predates fixes), confirm tests.md matches final revision, run files Testcontainers tests, tasks.py verify for 4 criteria, write+commit quality.json, done.
- release by dell26/agent-5: Implementation complete: all 4 criteria verified; test-writer, code-reviewer and fixer reports plus quality.json are committed (reviewed_revision 8e66e73 is the rebased reviewed code, identical task code to HEAD). sh scripts/check.sh passed on e0c16cc. Left: merge origin/wip/files-storage and run 'python tasks.py done files-storage'. If the merge with main changes task code, re-run test-writer/code-reviewer and update quality.json.
