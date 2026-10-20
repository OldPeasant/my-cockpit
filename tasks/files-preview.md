---
title: Thumbnails and previews
status: in_progress
owner: dell26/agent-6
deps: [files-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/files,server/src/test/kotlin/ch/sonensei/cockpit/files,server/src/main/resources/db/migration/files]
prio: 2
lease_until: 2026-10-21T00:02:37Z
---
## Goal
Image thumbnails and text previews (SPEC.md §8.2).

## Acceptance criteria
- [ ] Thumbnails (Thumbnailator, 256 px and 1024 px, cached on disk) for jpeg/png/webp/gif; EXIF orientation honored
- [ ] `GET /files/{id}/preview` for text/markdown (first 256 KB, UTF-8 safe); PDFs/audio/video served inline with correct mime for client-side preview
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.files.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
- ASSUMPTION (2026-10-21 01:14+0200 by dell26/agent-6): Thumbnail endpoint is GET /files/{id}/thumbnail?size=256|1024 (default 256); JPEG output for JPEG sources, PNG for PNG/WebP/GIF (keeps alpha); never upscaled; images above 100 MP rejected with 422
- ASSUMPTION (2026-10-21 01:14+0200 by dell26/agent-6): Unsupported types for /thumbnail and /preview answer 415 Problem Details; undecodable images 422. SVG is excluded from inline preview/thumbnails (script/XSS risk)
- ASSUMPTION (2026-10-21 01:14+0200 by dell26/agent-6): Text preview covers text/*, JSON, XML, YAML; served as text/markdown for Markdown and text/plain otherwise (so HTML is never rendered), with X-Preview-Truncated header; invalid UTF-8 bytes become U+FFFD
- ASSUMPTION (2026-10-21 01:14+0200 by dell26/agent-6): Added TwelveMonkeys imageio-webp 3.12.0 since JDK ImageIO cannot decode WebP; thumbnails cached at data-dir/thumbnails/<size>/<storage key>.<ext> and deleted when the blob is purged
