---
title: Bookmarks API
status: in_progress
owner: dell26/agent-3
deps: [p0-items-links,p0-search-core,p0-trash-core]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/bookmarks,server/src/test/kotlin/ch/sonensei/cockpit/bookmarks,server/src/main/resources/db/migration/bookmarks]
prio: 3
lease_until: 2026-10-21T21:14:03Z
extended: 
releases: 1
---
## Goal
Bookmarks and read-later (SPEC.md §8.4).

## Acceptance criteria
- [x] CRUD, tags, read_later/read_at; on save fetch title/description/favicon (OpenGraph) asynchronously with SSRF protection, timeouts and size limits
  - verified (2026-10-21 22:55+0200 by dell26/agent-3): cd server && ./gradlew test --tests 'ch.sonensei.cockpit.bookmarks.*' at e788d313 -> pass; BookmarkWebTests/BookmarkEdgeCaseTests (Postgres Testcontainers, WireMock pages) cover CRUD, tags, read_later/read, async OpenGraph title/description/favicon; PageFetcherTests/EdgeCase/SizeLimitTests cover SSRF (private addresses, ports, schemes, redirects), 15s deadline and 1MB limit
- [x] Resolver BOOKMARK, search, trash
  - verified (2026-10-21 22:55+0200 by dell26/agent-3): BookmarkWebTests + BookmarkEdgeCaseTests (resolver BOOKMARK, search incl. readable URL, trash/restore) via ./gradlew test --tests 'ch.sonensei.cockpit.bookmarks.*' -> pass
- [x] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.bookmarks.*'` passes (Postgres via Testcontainers where persistence is involved) with WireMock pages
  - verified (2026-10-21 22:55+0200 by dell26/agent-3): cd server && ./gradlew test --tests 'ch.sonensei.cockpit.bookmarks.*' --tests '*OpenApiContractTest' at e788d313 (Postgres via Testcontainers PostgresTestBase, WireMock pages) -> 110 tests, 0 failures
- [x] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag
  - verified (2026-10-21 22:55+0200 by dell26/agent-3): api/openapi.yaml regenerated in 1737c020 with UPDATE_OPENAPI=1; ./gradlew test --tests '*OpenApiContractTest' without the flag at e788d313 -> pass, api/ unchanged (git diff --quiet api)

## Notes
- ASSUMPTION (2026-10-21 20:39+0200 by dell26/agent-3): favicon is stored as the absolute http(s) URL of the site's icon (link rel=icon, else apple-touch-icon, else /favicon.ico); the server does not download the icon itself. Clients may load it through an image proxy if CSP requires.
- ASSUMPTION (2026-10-21 20:39+0200 by dell26/agent-3): Automatic metadata fetch (after create or a URL change) only fills empty title/description/favicon; a user-given title is kept. New endpoint POST /bookmarks/{id}/refresh-metadata (If-Match) refetches and replaces them. A URL change clears the favicon.
- ASSUMPTION (2026-10-21 20:39+0200 by dell26/agent-3): Status of the background fetch is exposed as metadataStatus PENDING/FETCHED/FAILED; the fetch bumps the version (new ETag) and publishes an UPDATED change event, so clients refetch via SSE. Mark read is PATCH {read:true|false} (server time), not a client-supplied readAt.
- ASSUMPTION (2026-10-21 20:39+0200 by dell26/agent-3): SSRF protection follows the ICS feed client: http(s) only, no credentials, ports 80/443, every resolved address public (mail.api.PublicAddresses), checked again on each redirect; the JDK HttpClient re-resolves the host, so DNS rebinding is narrowed, not closed. Only the first 1MB of HTML is read; whole fetch 15s. Duplicate URLs are allowed (no uniqueness).
- ASSUMPTION (2026-10-21 20:39+0200 by dell26/agent-3): Added snapshot_file_id column now (read-only snapshotFileId in the API) so bookmarks-snapshot needs no migration of its own in this module's folder.
- ASSUMPTION (2026-10-21 21:11+0200 by dell26/agent-3): V202610211800__bookmarks_model.sql was edited in place (metadata_fetch column) because it was never on main; URLs are normalized (IDN->punycode, invalid chars percent-encoded) before storing; hosts with '_' save but their metadata fetch ends FAILED (JDK HttpClient limitation); PENDING bookmarks are re-queued on startup and rejected fetches stored as FAILED
- extended by dell26/agent-3: Implementation, tests, OpenAPI regen, review rounds 1+2 (0 P0-P2, 3 P3) and fixer done; test-writer edge-case tests committed (4e889777). Left: finish/confirm test-writer report (reviews/bookmarks-api/tests.md missing), re-run code-reviewer if code changed since round 2, tasks.py verify for criteria 1-4, write quality.json, done. Check: only failure was calendar IcsFeedClientTests (IOException instead of 'larger than', outside this task, likely flaky); check takes ~7 min.
- release by dell26/agent-3: Implementation complete (model, service, async OpenGraph fetch w/ SSRF/timeouts/size limits, resolver, search, trash, REST, OpenAPI regen + web client). At 8dd27c2f: ./gradlew test --tests 'ch.sonensei.cockpit.bookmarks.*' passes, sh scripts/check.sh passes (~7 min). Reviews: review.md/tests.md/fixes.md exist, but code changed after the last review (8c7b2160 fixes CR-5..CR-7 + non-HTML skip; 8dd27c2f size-limit tests), so evidence is stale. Left: re-run test-writer and code-reviewer on HEAD (fixer only if new findings), run OpenApiContractTest without flag, tasks.py verify #1-#4, write reviews/bookmarks-api/quality.json, commit, done.
