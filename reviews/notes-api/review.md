# Code Review

Date: 2026-10-21

Task: notes-api - Notes API

Scope: `git diff 3dbd3504..HEAD` without `tasks/` and `reviews/`. The task code is the squashed commit 3fc95687; c090c42a only touches `reviews/`. This review covers the current code afresh, because the revisions named by earlier evidence (f922ce77, f6bafe16, 26322171) were lost in the squash. Only committed changes were reviewed. At review time the working tree had an uncommitted change to `server/src/test/kotlin/ch/sonensei/cockpit/notes/NoteEdgeCaseWebTests.kt`, which belongs to a concurrent helper run and was not reviewed.

Requirements:
- tasks/notes-api.md: acceptance criteria 1-6 and the recorded assumptions (revision interval, archived filter, tags, derived title, image upload, no-op PATCH)
- SPEC.md §8.1 (Notes), plus §4.2-§4.4 for ETag/If-Match, Problem Details, UUIDv7, soft delete/Trash, links and search
- docs/ARCHITECTURE.md (module boundaries, migration naming), AGENTS.md "Project notes", docs/QUALITY.md
- Parent instructions: review the current code afresh, and confirm that earlier findings are fixed

Git range:
- Branch: main
- HEAD: c090c42aed1147cf2a58e829d9964dd9b04cb6f9
- Base/start: 3dbd3504fa58040c26e7d656de1b09afb06f5106
- Commits reviewed: 2 (3fc95687 code, c090c42a reviews only)

Summary: 0 P0, 0 P1, 1 P2, 1 P3

## What I checked

- I read everything under `server/.../notes/{api,domain,infra}` in full, plus the migration `notes/V202610210907__notes_model.sql`. I also read the core code the module calls: `LinkService.sync`, which deletes and inserts only SYNC links and relies on the partial unique indexes `link_sync_pair` and `link_manual_pair`, so a manual link to the same target does not collide. I read `TrashService`, which publishes the restore and purge ChangeEvents and `ItemDeletedEvent` itself, `ProblemDetailsAdvice`, and `BaseEntity`.
- Criteria against the code:
  1. CRUD works with ETag/If-Match (428/412, and 409 through the optimistic locking advice). The title is derived from the first line. The list filters by tag (case-insensitive, leading `#` stripped), pinned, and archived (`true`/`false`/`all`, 400 otherwise). Paging uses a keyset cursor over pinned, updatedAt and id.
  2. `[[type:id]]` links are synced on create, on a body change and on a revision restore, and self-links are dropped. `POST /notes/{id}/images` stores the file through `FilesService` into `SpecialFolder.NOTES`, accepts only `image/*` and returns `cockpit-file:{id}`.
  3. The newest 20 revisions are kept (`deleteUpTo`), with `GET .../revisions` and `POST .../revisions/{n}/restore`. A restore keeps the content it replaces.
  4. The NOTE resolver is in place. The search document comes from flexmark plain text in one place (`SearchIndexer.indexNote`). Delete, restore and purge go through the Trash, and ChangeEvents are published on create, update, delete and revision restore.
  6. `api/openapi.yaml` has all 9 notes operations and the `archived` enum. The generated web client files in `web/src/app/api/generated/fn/notes/*` match.
- Earlier findings, confirmed fixed in the current code:
  - First review CR-1 (concurrent body change gave 500): `keepRevision` calls `notes.saveAndFlush(note)` before it inserts the revision (`NoteService.kt:247`).
  - First review CR-2 (`archived` had no enum): the enum is on the `@Parameter` schema (`NoteController.kt:67`) and in the generated client.
  - First review CR-3 (search document built twice): there is now a single `SearchIndexer.indexNote` (`NoteService.kt:38`), used by `NoteService` and `NoteTrashContributor`.
  - Fixer FX-1 (Android `WrongCommentType` from API descriptions): the descriptions in `NoteDtos.kt:24,133` no longer contain `[[...]]` or `![](...)`.
  - Second review CR-2 (a no-op PATCH re-indexes and publishes UPDATED): still present, but recorded as an accepted assumption on the task, so it is not repeated here.
- Migration: the version is unique, and the path and name follow the `<module>/V<yyyyMMddHHmm>__<module>_<desc>.sql` convention. `NoteMigrationTests` guards both.
- Security: native queries are parameterized, and nothing logs note contents. The upload mime type is checked on the server, and only active notes accept uploads. No secrets are in the diff.
- Tests: I was not allowed to run Gradle in this session (the command was denied). The newest JUnit results in `server/build/test-results/test/` (written 03:57, after commit 3fc95687) show 0 failures and 0 errors for NoteWebTests 20, NoteEdgeCaseWebTests 28, NoteInfraTests 9, NoteRevisionInfraTests 5, NoteMarkdownTests 8, NoteMigrationTests 3, NoteImageMaxSizeWebTests 1 and OpenApiContractTest 1. The 28 edge-case tests include the uncommitted test change mentioned above.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Recorded evidence and `quality.json` name revisions that no longer exist**
    - File: `reviews/notes-api/quality.json:4`, `tasks/notes-api.md:17`
    - Category: Tests
    - Problem: `quality.json` has `reviewed_revision` f922ce776fc70e97ad79212cd8575cccd4d55e3c. `git cat-file -t` on that hash fails ("could not get object info"). The latest `verify` entries for criteria 1-6 name revision 26322171, which the squash into 3fc95687 also replaced. So neither the evidence nor the freshness check can be tied to the code that `done` would push.
    - Why it matters: `done` compares the patch-id of `reviewed_revision` with HEAD (docs/QUALITY.md "Freshness"). It cannot do that for a missing object. AGENTS.md "Evidence" also requires the recorded evidence to match the repository.
    - Suggested fix: Once the helpers have finished on the final code, set `reviewed_revision` to the final commit hash. Re-run `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.notes.*' --tests '*OpenApiContractTest'` on that commit and record the result with `tasks.py verify` for criteria 1-6.

## P3 Low

- [ ] **CR-2 | NUL characters in tags give 500 instead of 400**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/notes/domain/NoteService.kt:282` (also `:310`, the list tag filter)
    - Category: Correctness
    - Problem: `validate` rejects NUL in `title` and `bodyMd` with 400 (lines 297-298), but `normalizeTags` does not check tags. A create or PATCH with `"tags":["a\u0000b"]` sends the value to PostgreSQL in `findTagSpelling` (a native query) or in the `note_tag` insert. PostgreSQL rejects 0x00 in `text`, and `ProblemDetailsAdvice` maps the error to a generic 500. `GET /api/v1/notes?tag=%00` goes the same way through the tag filter predicate.
    - Why it matters: Invalid client input becomes a server error and is logged as one, which goes against the Problem Details 400 contract the module already follows for title and body.
    - Suggested fix: In `normalizeTags`, add `FieldError("tags", "must not contain NUL characters")` for tags that contain `'\u0000'`. In the controller or `specification`, reject (400) or ignore a `tag` filter that contains NUL. Add one web test for each case.

## Review conclusion

Material issues found; fix before completing the task. The implementation meets acceptance criteria 1-6, and every earlier finding is fixed or accepted. Before `done`, CR-1 needs fresh evidence and a `reviewed_revision` that exists. CR-2 is a small validation gap that can be fixed or accepted.
