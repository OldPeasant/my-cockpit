---
title: Files: model and blob storage
status: in_progress
owner: dell26/agent-5
deps: [p0-trash-core,p0-search-core,p0-events-sse]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/files,server/src/test/kotlin/ch/sonensei/cockpit/files,server/src/main/resources/db/migration/files]
prio: 1
lease_until: 2026-10-20T14:15:34Z
---
## Goal
Folders, file metadata and on-disk blobs (SPEC.md §8.2).

## Acceptance criteria
- [ ] Migrations for folder (tree, unique name per parent) and file (name, mime, size, sha256, storage_key); blobs under `${COCKPIT_DATA_DIR}/blobs/ab/cd/<uuid>` written atomically (temp + move)
- [ ] Special folders `/Attachments/{Tasks,Notes,Mail,Journal}` created on demand and protected from deletion/rename
- [ ] Resolvers FILE/FOLDER, search indexing of names, trash contributor (purge deletes the blob)
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.files.*'` passes (Postgres via Testcontainers where persistence is involved) incl. path traversal in names rejected

## Notes
