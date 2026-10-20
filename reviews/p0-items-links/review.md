# Code Review

Date: 2026-10-20

Task: p0-items-links - Universal item references and links

Scope: `git diff 75a353c2fa68f759d93e8ab9de723f9a4e23974e..4cd021489f524d0d05965f5be74778e528d91893`, limited to the task's files: `server/src/main/kotlin/ch/sonensei/cockpit/core/items/**`, `server/src/test/kotlin/ch/sonensei/cockpit/core/items/**`, `server/src/test/kotlin/ch/sonensei/cockpit/support/PostgresTestBase.kt`, `server/src/test/resources/config/application.properties`, `server/src/main/resources/db/migration/core/V202610201400__core_items_link.sql`, `api/openapi.yaml` and the regenerated `web/src/app/api/generated/**` link files. The changes that merge 6f15066 brought in from main (p0-auth-tokens, p0-e2e-harness) are not part of this task and were not reviewed. Reviewed revision: 4cd021489f524d0d05965f5be74778e528d91893. This is a re-review. The previous report covered 48e57fa, a commit from before the rebase, and `fixes.md` records the fixes made since then.

Requirements:
- tasks/p0-items-links.md: acceptance criteria 1-5 and the ASSUMPTION notes. The 14:10 notes replace the "pair linked at most once" note.
- SPEC.md §4.2, §4.3
- docs/ARCHITECTURE.md conventions, AGENTS.md project notes, docs/QUALITY.md (report contract)
- Parent instructions: re-review the final code, and decide whether the two unspecified behaviours in tests.md warrant findings.

Git range:
- Branch: main (local clone)
- HEAD: 4cd021489f524d0d05965f5be74778e528d91893
- Base/start: 75a353c2fa68f759d93e8ab9de723f9a4e23974e (claim commit)
- Commits reviewed: 11 (aecd6fe, a5e0b77, 96d566e, 13ced92, 40eb79e, 1176100, 52c919b, 14f521d, 6f15066 merge, 88a23f3, 4cd0214)
- Uncommitted changes: none

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Changes since the previous review

- The production code in `core/items` and the migration are the same in 14f521d and HEAD (`git diff 14f521d..HEAD` shows no change to them).
- 6f15066 merged main, and with it `core/V202610201200__core_auth_device.sql`. The core migration versions are now 202610200000, 202610201000, 202610201100, 202610201130, 202610201145, 202610201200 and 202610201400. All of them are unique, and the link migration is the latest.
- 88a23f3 regenerated `api/openapi.yaml` and the web client. They now contain both the link operations and main's device-token operations. `OpenApiContractTest` passes without the flag.
- 4cd0214 adds one `LinkWebTests` test: a failing resolver returns `available: false` over HTTP and the response is still 200.
- On main, `SecurityConfiguration` now also accepts bearer tokens. `/api/**`, and with it `/api/v1/links`, still requires authentication (`SecurityConfiguration.kt:59`). `LinkWebTests` "links need a login" and "creating and deleting links need a login" confirm this.

## Validation run by the reviewer

`./gradlew test --tests 'ch.sonensei.cockpit.core.items.*' --tests '*OpenApiContractTest' --tests '*Migration*'` was run in `server/` at HEAD 4cd0214 with a clean working tree. Result: BUILD SUCCESSFUL. The suites were LinkServiceTests 19, LinkServiceEdgeCaseTests 17, LinkServiceRaceTests 1, LinkWebTests 23, ItemResolverRegistryTests 5, OpenApiContractTest 1 and MigrationConventionTests 2, with 0 failures and 0 skipped. Postgres ran through Testcontainers.

## Acceptance criteria (re-checked at 4cd0214)

1. Met. `ItemType` has all 11 types from §4.3, TASK through HABIT. `ItemResolver` provides `resolve`, returning `ResolvedItem` (title, subtitle, icon, deepLink), and `exists`. `ItemResolverRegistry.find` looks resolvers up by type and fails at startup when two resolvers claim the same type.
2. Met. The `link` table exists, and POST, DELETE and GET are implemented on `/api/v1/links`. GET returns links in both directions, with a `direction` field. A missing resolver, a missing item or a failing resolver gives `available: false`, not an error.
3. Met. `sync` replaces only the SYNC links the source owns. `ItemDeletedEvent` removes an item's links in the publisher's transaction. Tests cover commit and rollback.
4. Met. Fake resolvers are used, and the package tests pass against Testcontainers Postgres (run above).
5. Met. `OpenApiContractTest` passes without the flag on the regenerated file.

`python tasks.py verify p0-items-links` still lists all five criteria as unverified. The parent worker must record the evidence before running `done`. This is not a finding against the code.

## Assessment of the test-writer's observations

- **A resolver's `exists` throws during POST, and the response is 500.** No finding. The resolver-failure assumption covers display resolution only (GET, and the response body after create). For a write, an internal failure in another module is a server error. Because `LinkService` is `@Transactional`, nothing is stored. Treating a broken resolver as "does not exist" (400) would blame the client. Accepting the end silently would bypass the existence check. Neither is clearly better than 500. If a future module needs different behaviour, its task can record an assumption.
- **The label limit is checked before trimming.** Reported as CR-2 (P3). The behaviour is harmless in practice, but two layers apply the same limit with different semantics.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Migration assumption still names the old file name and version**
    - File: `tasks/p0-items-links.md:23`
    - Category: Quality
    - Problem: The 13:35 ASSUMPTION still says the migration is `V202610201130__core_items_link.sql`. The file is `V202610201400__core_items_link.sql`, and version 202610201130 belongs to `V202610201130__core_settings.sql`. This was CR-1 (P3) in the previous review. It is still open: no replacing assumption has been recorded.
    - Why it matters: Humans reviewing `tasks.py assumptions` see a file name that does not exist, with the version that caused the collision fixed earlier.
    - Suggested fix: The parent worker records a new assumption with `tasks.py assume` that names `V202610201400__core_items_link.sql` and replaces the 13:35 migration-location note.

- [ ] **CR-2 | Label length is checked on the raw value in the API but on the trimmed value in the service**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/items/api/LinkController.kt:151` and `server/src/main/kotlin/ch/sonensei/cockpit/core/items/domain/LinkService.kt:55-58`
    - Category: Quality
    - Problem: `CreateLinkRequest.label` has `@Size(max = 200)`, which Bean Validation applies to the untrimmed string. `LinkService.create` trims the label first and then checks the same limit. As a result, a label of 200 characters plus surrounding whitespace is rejected with 400, even though the stored value would fit. The service's own length check can only fail for callers that bypass the controller.
    - Why it matters: One rule has two inconsistent definitions in two layers, so a later change to one of them can silently diverge from the other. User impact is small: it needs a label close to the limit with padding.
    - Suggested fix: Pick one rule. Either drop `@Size` and rely on the service check, keeping `maxLength` in the schema for documentation, or keep `@Size` and document that the limit applies before trimming. Add a web test for the chosen behaviour.

## Review conclusion

No material issues found.
