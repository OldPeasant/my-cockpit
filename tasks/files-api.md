---
title: Files API: upload, download, operations
status: in_progress
owner: dell26/agent-3
deps: [files-storage,runner-push-failure-loop]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/files,server/src/test/kotlin/ch/sonensei/cockpit/files,server/src/main/resources/db/migration/files]
prio: 1
lease_until: 2026-10-20T19:13:22Z
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
- ASSUMPTION (2026-10-20 20:57+0200 by dell26/agent-3): API layout: /api/v1/folders (POST, GET/PATCH/DELETE {id}, GET {id}/zip), /api/v1/files (multipart POST, GET {id}, GET {id}/content, PATCH, DELETE), /api/v1/drive?folderId= (cursor listing: folders first then files, by lower(name), id) and /api/v1/drive/usage, /api/v1/uploads (POST, GET {id}, PUT {id}/chunks/{n}, POST {id}/complete, DELETE {id}); PATCH bodies {name, parentId|folderId} with null = drive root; If-Match required for PATCH/DELETE
- ASSUMPTION (2026-10-20 20:57+0200 by dell26/agent-3): Resumable uploads: size declared at POST /uploads, chunk size chosen by the server (cockpit.files.upload-chunk-size, default 8 MB), chunks 0-based with exact lengths; sha256 is sent with POST /complete (client can hash while streaming). Missing chunks -> 409 'Chunks missing: n,...'; sha/size mismatch -> 400 field errors, nothing stored, upload stays open. Session in table upload_session (no FK to folder), chunks under COCKPIT_DATA_DIR/uploads/<id>/<n>; sessions older than 7 days deleted lazily when a new upload is created (no scheduler). A repeated complete after success returns 404 (not idempotent).
- ASSUMPTION (2026-10-20 20:57+0200 by dell26/agent-3): Max file size cockpit.files.max-size=COCKPIT_FILES_MAX_SIZE (default 5GB); spring.servlet.multipart.max-file-size follows it (413 from Spring), max-request-size unlimited; a declared upload size above the max is 400 (field size). Multipart accepts one 'file' part per request (clients upload multiple files with several requests); mime from the part's content type, else guessed from the name
