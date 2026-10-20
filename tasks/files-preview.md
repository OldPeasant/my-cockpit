---
title: Thumbnails and previews
status: in_progress
owner: dell26/agent-6
deps: [files-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/files,server/src/test/kotlin/ch/sonensei/cockpit/files,server/src/main/resources/db/migration/files]
prio: 2
lease_until: 2026-10-20T23:27:22Z
---
## Goal
Image thumbnails and text previews (SPEC.md §8.2).

## Acceptance criteria
- [ ] Thumbnails (Thumbnailator, 256 px and 1024 px, cached on disk) for jpeg/png/webp/gif; EXIF orientation honored
- [ ] `GET /files/{id}/preview` for text/markdown (first 256 KB, UTF-8 safe); PDFs/audio/video served inline with correct mime for client-side preview
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.files.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
