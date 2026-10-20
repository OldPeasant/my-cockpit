# Fix Report

Date: 2026-10-20

Task: p0-items-links - Universal item references and links

Scope: findings CR-1 and CR-2 from `reviews/p0-items-links/review.md` (CR-3 was handled by the parent worker with a new assumption). Base 9a9158875effabdfb93952fbdbde0754f40fe48d, task commits 693eb46..HEAD before this round. The branch had just been rebased onto main; the generated files `api/openapi.yaml` and `web/src/app/api/generated/**` were regenerated in this round, not merged by hand. An earlier fixer round fixed TW-1 (independent MANUAL/SYNC links) and TW-2 (test connection pool) from `reviews/p0-items-links/tests.md`; that work is unchanged.

## Fixed findings

### CR-1 - Migration version collides with the p0-settings migration on main

Files changed:
- `server/src/main/resources/db/migration/core/V202610201130__core_items_link.sql` renamed (git mv) to `server/src/main/resources/db/migration/core/V202610201400__core_items_link.sql`

Problem:
The link migration had version 202610201130, the same as main's `core/V202610201130__core_settings.sql`. Flyway refuses duplicate versions, so startup and every `@SpringBootTest` would fail after the merge.

Fix:
Renamed to version 202610201400. That is later than every migration on main: the only migration folder is `core/`, and its latest migration is V202610201145. The content is unchanged. No code or test refers to the file name.

Validation:
- `cd server && ./gradlew check --rerun-tasks` (includes FlywayConfigurationTests, MigrationConventionTests and all Testcontainers-backed tests on the merged migration set) - Pass, 772 tests, 0 failures

### CR-2 - DELETE /api/v1/links/{id} deletes SYNC links, and the API does not expose a link's origin

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/core/items/domain/LinkService.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/core/items/api/LinkController.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/core/items/LinkServiceTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/core/items/LinkWebTests.kt`
- `api/openapi.yaml` (regenerated)
- `web/src/app/api/generated/models/link-response.ts`, `web/src/app/api/generated/functions.ts`, `web/src/app/api/generated/index.ts` (regenerated)

Problem:
`LinkService.delete` removed any link. A deleted SYNC (inline) link comes back on the next sync of its source. `LinkResponse` had no `origin`, so clients could not tell inline links from manual ones.

Fix:
- `LinkService.delete` now throws `ConflictException` (409 Problem Details, detail "The link is part of its source's content; remove it there") for a SYNC link. MANUAL links are still deleted (204). A missing link is still 404.
- `LinkResponse` has a required `origin` field (`MANUAL` | `SYNC`, the `LinkOrigin` enum) with a schema description.
- The `deleteLink` operation is now "Remove a manual link". Its description explains the SYNC case, and the OpenAPI spec documents a new 409 response.
- Tests: `LinkServiceTests` "delete rejects a sync link and keeps it". `LinkWebTests` "DELETE of an inline link is a conflict and keeps it" checks that GET returns `origin: SYNC`, that DELETE returns 409 Problem Details and that the link is still listed. The POST test now also asserts `origin: MANUAL`. The existing tests "DELETE removes the link from both ends" and the manual-next-to-inline test still check that deleting a MANUAL link gives 204.
- Regenerated `api/openapi.yaml` (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and the web client (`npm run api:gen`). Regeneration also restored the `/api/v1/links` operations and schemas that the rebase had dropped, by resolving the generated files to main's side.

Validation:
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.items.*' --tests '*OpenApiContractTest'` - Pass (64 tests, 0 failures; both new tests ran)

## Not fixed

### CR-3 - Task assumption about pair uniqueness is outdated

Status:
Out of scope for the fixer (already handled)

Reason:
The fix is a `tasks.py assume` entry, which is TFP task state owned by the parent worker. The parent has already recorded the new assumption (2026-10-20 14:10, "Replaces the 'pair linked at most once' assumption").

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.items.*' --tests '*OpenApiContractTest'` - Pass (64 tests)

Full test suite:
- `cd server && ./gradlew check`: the first run had 1 failure in 772 tests, `ClockConfigurationTests > the application clock is the system clock in UTC`. The assertion was `2026-10-20T12:14:18.856519Z should be >= 2026-10-20T12:14:18.856519383Z`. This is a timing flake in `core/persistence`: a microsecond-truncated clock is compared with a nanosecond `Instant.now()`. That code is not touched by this task. A rerun with `./gradlew check --rerun-tasks` passed: 772 tests, 0 failures.
- `cd web && npm run check` - Pass

Lint/type-check/build:
- Server ktlint (part of `./gradlew check`) - Pass
- Web eslint + tests + build (`npm run check`) - Pass

`.taskcheck`:
- `sh scripts/check.sh` - Pass (exit 0: TFP gate tests, server check, web check with 247 tests, android ktlint + unit tests + lint)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
