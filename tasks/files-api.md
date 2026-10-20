---
title: Files API: upload, download, operations
status: todo
owner:
deps: [files-storage]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/files,server/src/test/kotlin/ch/sonensei/cockpit/files,server/src/main/resources/db/migration/files]
prio: 1
---
## Goal
File operations (SPEC.md §8.2).

## Acceptance criteria
- [ ] Multipart upload; chunked resumable upload for > 50 MB (`POST /uploads`, `PUT /uploads/{id}/chunks/{n}`, `POST /uploads/{id}/complete` verifies size + sha256); max size from config (default 5 GB)
- [ ] Download with `Range` support and correct `Content-Disposition` (RFC 6266 filename*); folder download as streamed zip
- [ ] Rename, move (no cycles), delete (trash), new folder, list folder (cursor), disk usage endpoint
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.files.*'` passes (Postgres via Testcontainers where persistence is involved) incl. resume after a missing chunk and a 60 MB chunked upload
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
