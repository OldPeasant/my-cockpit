# Code Review

Date: 2026-10-21

Task: notes-api - Notes API

Scope: `git diff 00b53719..d4e1dfeb` (the task's commits on top of `git merge-base HEAD origin/main`), without `tasks/` and `reviews/`. I reviewed the code afresh and checked that the earlier findings are fixed. Only committed changes were reviewed. At review time the working tree had an uncommitted change to `server/src/test/kotlin/ch/sonensei/cockpit/notes/NoteEdgeCaseWebTests.kt` from the concurrent test-writer run. I did not review it.

Requirements:
- tasks/notes-api.md: acceptance criteria 1-6 and the recorded assumptions (revision interval, archived filter, tags, derived title, image upload, no-op PATCH)
- SPEC.md §8.1 (Notes), plus §4.2-§4.4 for ETag/If-Match, Problem Details, UUIDv7, soft delete/Trash, links and search
- docs/ARCHITECTURE.md conventions (module boundaries, migration naming), AGENTS.md "Project notes", docs/QUALITY.md
- Parent instructions: final review of the final code; confirm earlier findings are fixed; the stale `reviewed_revision` (earlier CR-1) is parent-owned and is not re-reported

Git range:
- Branch: main
- HEAD: d4e1dfeb19737574e582c31969a8ece6b2849e97
- Base/start: 00b5371959c290a90a2e4d2018ced7155fc536d0
- Commits reviewed: 4 (d9341538 implementation, 07e336b2 reviews only, 19eeb0b9 tests and reports, d4e1dfeb fix for the NUL characters in tags)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## What I checked

- I read all of `server/.../notes/{api,domain,infra}` in full, plus the migration `notes/V202610210907__notes_model.sql`, against the criteria:
  1. CRUD on `/api/v1/notes` sends an ETag and requires If-Match (428 when it is missing, 412 when it is stale, 409 when the version is stale at flush). An empty title is derived from the body (`Note.displayTitle`, `NoteMarkdown.titleOf`). The list filters by tag (case-insensitive, leading `#` removed), by pinned, and by archived (`true`/`false`/`all`, anything else is 400). Paging uses a keyset cursor over pinned, updatedAt and id, and a malformed cursor is 400.
  2. `[[type:id]]` links are synced through `LinkService.sync` on create, on a body change and on a revision restore. `POST /notes/{id}/images` accepts only `image/*`, stores the file through `FilesService` into `SpecialFolder.NOTES`, and returns `cockpit-file:{id}`.
  3. The newest 20 revisions are kept (`deleteUpTo(number - MAX_REVISIONS)`), with `GET .../revisions` and `POST .../revisions/{n}/restore`. A restore keeps the content it replaces.
  4. The NOTE resolver is in place. The search document is built in one place (`SearchIndexer.indexNote`, flexmark plain text). The Trash contributor handles restore and purge. ChangeEvents are published on create, update, delete and restore.
  6. `api/openapi.yaml` and the generated web client contain the 9 notes operations and the `archived` enum.
- The fix in d4e1dfeb (earlier CR-2, NUL characters in tags and in the tag filter) is correct:
  - `normalizeTags` (`NoteService.kt:293`) adds a `tags` field error for any tag that contains `'\u0000'`. Kotlin's `trim()` does not remove NUL, so a tag that is only NUL is rejected too. The check runs before `findTagSpelling` sends anything to PostgreSQL, and before any other field is applied. Create and PATCH both go through it.
  - `list` (`NoteService.kt:78`) rejects a `tag` filter that contains NUL with a 400 on field `tag`, before the specification is built.
  - Two new web tests cover create, PATCH (the note keeps its tags) and the list filter. They check the 400 status, the Problem JSON content type and the field name.
- Earlier findings, confirmed against the current code:
  - The concurrent body change that gave 500 is fixed: the note is flushed before the revision insert (`NoteService.kt:253`).
  - The `archived` parameter has its enum in the OpenAPI schema (`NoteController.kt:67`).
  - The search document is built only in `SearchIndexer.indexNote` (`NoteService.kt:38`).
  - The API descriptions no longer contain `[[...]]` or `![](...)` (`NoteDtos.kt:24,133`), so Android lint no longer reports `WrongCommentType`.
  - A PATCH that changes nothing still re-indexes and publishes UPDATED. That is accepted as a task assumption and is not repeated here.
- Security: the native queries are parameterized, and nothing logs note contents. The upload mime type is checked on the server, and uploads need an active note. The diff contains no secrets.
- Out of scope: backup/restore of notes (SPEC.md §14) is the separate task `backup-contrib-notes`.
- Tests: I could not run Gradle in this session. The newest JUnit results in `server/build/test-results/test/` were written at 04:25:08, just before commit d4e1dfeb (04:25:49). They show 0 failures and 0 errors for NoteEdgeCaseWebTests (30, including both NUL tests), NoteWebTests, NoteInfraTests, NoteRevisionInfraTests, NoteMarkdownTests, NoteMigrationTests, NoteImageMaxSizeWebTests and OpenApiContractTest.
- Evidence (not a new finding; the parent will rewrite it): the `verify` entries on the task name revision 26322171, and `quality.json` names f922ce77. Neither object exists in this clone, and both predate the code change in d4e1dfeb. The criteria need fresh evidence at the final HEAD before `done`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. The code meets acceptance criteria 1-6, and every earlier code finding is fixed or accepted as an assumption. Before `done`, the parent still has to record fresh `verify` evidence and a `reviewed_revision` for the final HEAD.
