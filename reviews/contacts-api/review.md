# Code Review

Date: 2026-10-21

Task: contacts-api - Contacts API

Scope: Full review of the current task commit, `git diff ae709721..c7b26f26` (one squashed commit). It covers the contacts module (`server/src/main/kotlin/ch/sonensei/cockpit/contacts`: `api/ContactController.kt`, `api/ContactDtos.kt`, `domain/Contact.kt`, `domain/ContactService.kt`, `domain/VCardService.kt`, `infra/ContactItems.kt`, `infra/ContactRepository.kt`), the migrations `db/migration/contacts/V202610211000__contacts_model.sql` and `V202610211010__contacts_email_search.sql`, `ContactWebTests.kt` and `ContactEdgeCaseTests.kt`, the regenerated `api/openapi.yaml` and `web/src/app/api/generated` (contacts files only), and the one-line changes in `gtd/convert/TaskToNoteWebTests.kt` and `TaskToNoteRollbackTests.kt` (CONTACT -> HABIT). The earlier review referred to commits (db540c60..029b887d) that no longer exist after the history rewrites, so this review was done from scratch on the current HEAD. Committed changes only; the working tree was clean before and after the test run.

Requirements:
- tasks/contacts-api.md acceptance criteria #1-#4 and the 8 recorded assumptions
- SPEC.md §8.3 (contact fields, sources, vCard import/export), §4.2 (ETag/If-Match, Problem Details, Trash)
- AGENTS.md project notes (module boundaries via services, Flyway naming per module, logging rules, generated files), docs/ARCHITECTURE.md conventions
- reviews/contacts-api/fixes.md (earlier findings CR-1..CR-3 and TW-1)

Git range:
- Branch: main
- HEAD: c7b26f26677b8381efdd418c9b500c8697b0d6e4
- Base/start: ae709721fd028ebe3f097492da4cf41b9ae03b03 (`git merge-base HEAD origin/main`)
- Commits reviewed: 1 (c7b26f26)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Commands and results

- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.*' --tests 'ch.sonensei.cockpit.gtd.convert.*' --tests '*OpenApiContractTest'`. The first two attempts failed with `java.io.EOFException` in Gradle's `SerializableTestResultStore` (reading `build/test-results/test/binary`). This was a Gradle infrastructure failure, not a test failure: another Gradle run of the same tests was writing to the same `server/build` directory at the same time (its reports have the timestamp 09:41:20). After that run had finished, the same command was reported `UP-TO-DATE`.
- `cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.contacts.*' --tests 'ch.sonensei.cockpit.gtd.convert.*' --tests '*OpenApiContractTest'` (without `UPDATE_OPENAPI`, Testcontainers Postgres) -> BUILD SUCCESSFUL in 32s. JUnit XML results: ContactEdgeCaseTests 44/44, ContactWebTests 11/11, OpenApiContractTest 1/1, TaskToNoteEdgeCaseTests 17/17, TaskToNoteWebTests 8/8, TaskToNoteRollbackTests 1/1. 0 failures, 0 errors, 0 skipped. `git status` stayed clean, so the committed `api/openapi.yaml` matches the API.
- `python tasks.py verify contacts-api` -> criteria #1-#4 are all still unverified. No evidence has been claimed yet, so this is not a finding.

## Acceptance criteria

- #1 Entity, CRUD with ETag, photo via Files, resolver CONTACT, search, Trash. All SPEC §8.3 fields are present (`Contact.kt`, migration V202610211000), including `source` with a check constraint and a unique `(source, external_id)` index. CRUD goes through `ContactService`. PATCH/DELETE check `If-Match` via `ETags.checkIfMatch` against the loaded version, and a concurrent commit gives an optimistic locking error (tested). The photo is checked through the FILE item resolver, and only when the request changes it (the CR-2 fix still holds). `ContactItemResolver` is registered for `ItemType.CONTACT`. Contacts are indexed on create, update and restore, and removed from the index on soft delete. `ContactTrashContributor` follows the same pattern as Task/Project. `TrashService` publishes the change and `ItemDeletedEvent` on purge. Met.
- #2 vCard import/export with ez-vcard. Import of a multi-card file: each card gets its own `TransactionTemplate` transaction, and invalid cards are skipped and reported. A photo blob stored for a skipped card is removed by `FilesService.storeFile`'s rollback hook. The limits (20 MB, 10000 cards, 5 MB photo) are enforced. Export writes vCard 3.0 with `UID urn:uuid:<id>` and makes labels safe (the TW-1 fix still holds). Round-trip tests cover 2.1, 3.0 and 4.0 input. Met.
- #3 Contacts tests pass on Testcontainers Postgres. Confirmed by the run above.
- #4 `OpenApiContractTest` passes without the flag. The generated web client contains only the new contacts functions and models. Met.

## Checks without findings

- Architecture: contacts uses the files module only through `FilesService`/`SpecialFolder`, and the core items/search/trash/events only through their domain interfaces. Migrations are new files under `db/migration/contacts` with the module-prefixed timestamp naming. The q-filter function lives in a separate new migration, and no applied migration was edited.
- Security and data: every endpoint needs a login, and changing requests need CSRF (both tested). Unknown body fields are rejected. The LIKE pattern is escaped (`\`, `%`, `_`) and passed as a bound literal. Photo file names are sanitized with the same rule as `FileNames`. The only log line contains counts, with no contact data or note contents. Test data is fictitious.
- Correctness edges: the keyset cursor (`<id>|<name>`) parses the id first, so names containing `|` work (tested). The list and the keyset predicate both use the DB collation, so their order matches. `limit` is bounded by `CursorRequest`. Export with unknown ids returns 404 (documented). Clearing `displayName` derives it again, and a contact with nothing to derive from is a 400.
- Backup/restore of contacts is not in this task's criteria. It is planned separately as `tasks/backup-contrib-contacts.md`, so it is not reported as missing.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Convert tests now depend on HABIT having no resolver, which `habits-api` will add**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/gtd/convert/TaskToNoteWebTests.kt:127`, `server/src/test/kotlin/ch/sonensei/cockpit/gtd/convert/TaskToNoteRollbackTests.kt:51`
    - Category: Tests
    - Problem: these tests need an item type that has no registered resolver. This task broke them by adding the CONTACT resolver, and the fix swaps in `ItemType.HABIT`. But `tasks/habits-api.md` (status todo) has the criterion "Resolver HABIT". When that task lands, the same tests fail again for the same reason.
    - Why it matters: an unrelated feature task will again fail `.taskcheck` because of a hidden test assumption, and will have to repeat this same fix outside its `touches`.
    - Suggested fix: make the test independent of which modules have resolvers. For example, pick the type at runtime from `ItemType.entries` minus the types `ItemResolverRegistry` knows, or use a test-only `ItemResolverRegistry` stub/mock for that case. At least add a comment that names the dependency. The fix can be done here or in `habits-api`. The parent decides the scope.

## Review conclusion

No material issues found.

Notes for the parent worker:
- CR-1 is a low-priority test-robustness note. It does not block this task.
- `reviewed_revision` for quality.json is c7b26f26677b8381efdd418c9b500c8697b0d6e4. A commit that touches only `reviews/` or `tasks/` keeps this review fresh.
- Avoid running Gradle in `server/` while another Gradle run in the same clone is active. The two runs corrupt `build/test-results/test/binary` and fail with `EOFException`.
