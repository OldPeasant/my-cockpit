# Code Review

Date: 2026-10-21

Task: contacts-api - Contacts API

Scope: Final re-review of all task commits, `git diff db540c60..029b887d` (8 commits). It covers the contacts module (`server/src/main/kotlin/ch/sonensei/cockpit/contacts`), the migrations in `db/migration/contacts` (V202610211000, V202610211010), `ContactWebTests.kt`, `ContactEdgeCaseTests.kt`, `api/openapi.yaml` and the regenerated `web/src/app/api/generated` client. The review focuses on the delta since the previous review. Committed changes only, and the working tree was clean.

Requirements:
- tasks/contacts-api.md acceptance criteria #1-#4 and the 7 recorded assumptions
- SPEC.md §8.3, §4.2-4.4
- docs/ARCHITECTURE.md and AGENTS.md project conventions (module boundaries, Flyway migration naming, logging, Problem Details, ETag)
- Previous review (CR-1..CR-3, all resolved), fixes.md, tests.md

Git range:
- Branch: main
- HEAD: 029b887dba092934d36d64b55bf562cd0c61d8ea
- Base/start: db540c60a32708bc15e18789a3a4b9b523ea333e (`git merge-base HEAD origin/main`)
- Commits reviewed: 8 (2166d9ff, 12062df6, ad04d051, 41c5ec9c, 779083b6, bbe88b25, 09a6d995, 029b887d)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Delta since the previous review

- The previous review covered 176798a8, the fix commit before the rebase. `git patch-id` gives the same value (1293cbf7...) for 176798a8 and its rebased copy 41c5ec9c. `git diff 41c5ec9c..HEAD` touches only `ContactEdgeCaseTests.kt` (+129 lines), `reviews/contacts-api/tests.md` and `review.md`. Since then nothing changed in the contacts production code, the migrations, the contacts DTOs or the controller. The changes to `api/openapi.yaml` and `web/src/app/api/generated` between 176798a8 and HEAD come only from other tasks merged on main (notes, mail).
- New tests in `ContactEdgeCaseTests.kt`. I checked each against the production code:
  - `empty email, phone, address and organization properties are dropped ...` checks the import behavior from a0a62a41/ad04d051. Its assertions match the importer.
  - `export with an empty ids parameter exports every contact` matches `ContactController` (`ids.isNullOrEmpty()` -> `contacts.all()`).
  - `every contacts endpoint needs a login` uses `@WithAnonymousUser` at method level to override the class-level `@WithCockpitUser`. It expects 401 on all 7 endpoints and asserts that no row was written.
  - `changing requests without a CSRF token are refused and change nothing` expects 403 for POST, PATCH and DELETE. It asserts the list is unchanged, the ETag is still `"0"` and `title` was not set. `title` is a real PATCH field (`ContactDtos.kt:118`), so the test exercises a real change.
  - `a folder of the drive is not a photo` matches `ContactService.validate`, which resolves the id only as `ItemType.FILE` (`ContactService.kt:211`).
  - `q stays applied on every page of the cursor` pages with limit 1. It asserts the exact name-ordered result, including an email-only match, and the loop has a bound.
  - `a body that is not a JSON object and an import without the file part are 400` covers a JSON array, truncated JSON and a missing `file` part (`@RequestPart("file")`).
- The tests assert observable HTTP behavior and DB state. They mock nothing and use fictitious test data (`example.org`, `+41 31 000 00 00`).

## Acceptance criteria

- #1 (entity, CRUD with ETag, photo via Files, resolver, search, Trash): implemented, unchanged since the previous review. Covered by the tests listed in tests.md.
- #2 (vCard import/export with ez-vcard): implemented, unchanged. Covered by the tests.
- #3: I ran `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.*' --tests '*OpenApiContractTest'` at HEAD 029b887d -> BUILD SUCCESSFUL. The run used Testcontainers Postgres. Results: ContactEdgeCaseTests 41/41, ContactWebTests 11/11, 0 failures/errors/skipped.
- #4: in the same run, `OpenApiContractTest` passed (1/1) without `UPDATE_OPENAPI`. That means the committed `api/openapi.yaml` matches the API.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.

Notes:
- `python tasks.py verify contacts-api` still shows criteria #1-#4 unverified. Nothing has been claimed, so this is not a finding. Before `done`, the parent worker must record the evidence: the command runs above and in tests.md.
- The `reviewed_revision` for quality.json is 029b887dba092934d36d64b55bf562cd0c61d8ea. A commit that touches only `reviews/` or `tasks/` keeps this review fresh.
