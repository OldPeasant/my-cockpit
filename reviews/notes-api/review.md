# Code Review

Date: 2026-10-21

Task: notes-api - Notes API

Scope: `git diff b41323dc..HEAD` on main (base = `git merge-base HEAD origin/main`, which equals the current origin/main, so HEAD already contains all of main). Final review of HEAD f6bafe16; it replaces the earlier review of revision 3d5c5215, whose findings (CR-1..CR-3) were fixed and are not repeated here. Committed changes only; the working tree was clean.

Requirements:
- tasks/notes-api.md: acceptance criteria 1-6 and the recorded assumptions (revision interval, archived filter, tags, derived title, image upload)
- SPEC.md §4.2 (UUIDv7, ETag/If-Match, cursor pagination, Problem Details, soft delete), §4.3 (ItemResolver, inline links persisted to `link`), §4.4 (search_document), §8.1 (Notes)
- docs/ARCHITECTURE.md, docs/QUALITY.md, AGENTS.md "Project notes" (module boundaries, migration naming, generated files)
- Parent instructions: check integration with current main (core search, trash, links, files, events), migration version uniqueness, consistency of generated files

Git range:
- Branch: main
- HEAD: f6bafe163d6de977592a81d0941846342b1e566a
- Base/start: b41323dc1f171c64d62911f7371aa6d9ffa3f566 (also current origin/main)
- Commits reviewed: 9 (5d27f2d6, 6ec5ba23, 45a13f37, f147ae5b, ecbd7cab, 4aa5cba3, 7e100737, merge 385e2b15, f6bafe16)

Summary: 0 P0, 0 P1, 1 P2, 1 P3

## What I checked

- Read every file in `server/.../notes/{api,domain,infra}` and the migration in full, plus the core and files APIs they call: `LinkService.sync` (replaces only the SYNC links the source owns), `SearchIndexer.upsert`/`delete`, `TrashService` (publishes the restore and purge ChangeEvents and `ItemDeletedEvent` itself, so the contributor correctly does not), `FilesService.storeFile`, `SpecialFolder.NOTES` (`/Attachments/Notes`), `UploadLimits`, and `PreviewService`. `PreviewService` only serves jpeg/png/webp/gif inline, so an `image/svg+xml` upload accepted by `addImage` is not an inline XSS vector.
- Migration: `notes/V202610210907__notes_model.sql` is the only migration with that version. It is higher than every other migration, including `calendar/V202610210900__calendar_sync.sql`, which used to clash. It follows the `<module>/V<yyyyMMddHHmm>__<module>_<desc>.sql` convention. `NoteMigrationTests` guards both.
- Acceptance criteria against the code: CRUD with ETag/If-Match (428/412/409), derived title, tag/pinned/archived filters (`archived=all`, 400 on invalid values), keyset cursor; inline link sync on create, body update and revision restore; image upload into `/Attachments/Notes` returning `cockpit-file:{id}`; a 20-revision cap with list and restore, where the replaced content is kept; NOTE resolver; flexmark-stripped search document written in one place (`SearchIndexer.indexNote`, also used by the Trash restore); trash delete/restore/purge; ChangeEvents on create/update/delete.
- Generated contract: the `/api/v1/notes*` paths in `api/openapi.yaml` and `web/src/app/api/generated/fn/notes/*` match the controller (operation ids, `archived` enum `'true' | 'false' | 'all'`, DTO fields). I did not re-run the client generator, because that writes files.
- Re-ran at HEAD f6bafe16: `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.notes.*' --tests '*OpenApiContractTest'` (no UPDATE_OPENAPI) -> BUILD SUCCESSFUL. Per-suite results: NoteWebTests 20, NoteEdgeCaseWebTests 26, NoteInfraTests 9, NoteRevisionInfraTests 5, NoteMarkdownTests 8, NoteMigrationTests 3, NoteImageMaxSizeWebTests 1, OpenApiContractTest 1; 73 tests, 0 failures, 0 errors.
- Security: no secrets, no logging of note contents, all native queries are parameterized, and the upload mime type is checked server-side.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Recorded acceptance evidence predates the migration rename and the merge of main**
    - File: `tasks/notes-api.md:17`
    - Category: Tests
    - Problem: All six `verify` entries were recorded at 02:21 by dell26/agent-3 on the pre-merge revision. They cite 69/70 tests. Later, 7e100737 renamed the migration (02:46) and 385e2b15 merged origin/main and regenerated `api/openapi.yaml` and the web client (03:01). Before the rename, the merged code had a duplicate Flyway version (V202610210900 in both calendar and notes), which breaks every Spring context. So the recorded evidence does not describe the code that `done` will push. `reviews/notes-api/quality.json` also still names `reviewed_revision` 3d5c5215 and the old findings.
    - Why it matters: AGENTS.md "Evidence" requires the evidence to match what is in the repository. A human reading the task would trust a test run that was made on code which, once merged, did not start.
    - Suggested fix: Re-record criteria 5 and 6, and preferably 1-4 as well, with the HEAD run: `./gradlew test --tests 'ch.sonensei.cockpit.notes.*' --tests '*OpenApiContractTest'` -> 73 tests, 0 failures; OpenApiContractTest passes without UPDATE_OPENAPI. Note the `npm run check` result after the regeneration. Then rewrite `quality.json` for the final revision and this review's findings.

## P3 Low

- [ ] **CR-2 | A PATCH that changes nothing still re-indexes the note and publishes an UPDATED ChangeEvent**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/notes/domain/NoteService.kt:124`
    - Category: Quality
    - Problem: `update` always calls `saved(...)`, which runs `changed(saved)` and so calls `indexer.indexNote` and `changes.publish(NOTE, id, UPDATED)`. It does this even when the fields sent equal the current values (e.g. an autosave of an unchanged body). In that case Hibernate does not flush, so `version` stays the same, but every connected client still gets an SSE change for the note.
    - Why it matters: Autosave clients (web-notes-editor) would make other clients refetch a note that did not change. The search document is also rewritten without need. Nothing is corrupted.
    - Suggested fix: Compare the content, tags, pinned and archived values before and after `apply`. If nothing changed, return the loaded note without indexing or publishing. Add a test that an identical PATCH publishes no event.

## Review conclusion

No material issues found. The implementation meets acceptance criteria 1-6 at HEAD f6bafe16 and integrates with current main. The migration version is unique, and OpenApiContractTest passes without the flag. Before `done`, the parent worker should fix CR-1 (re-record evidence and rewrite quality.json for the final revision). CR-2 is optional.
