# Code Review

Date: 2026-10-21

Task: notes-api - Notes API

Scope: `git diff b18d8200..c3d079f8` (all task commits on top of `git merge-base HEAD origin/main`), without `tasks/` and `reviews/`. This covers the final code at HEAD. I looked most closely at the test change in c3d079f8, which the previous review did not cover, and I checked that the earlier findings are still fixed. Only committed changes were reviewed. The working tree was clean.

Requirements:
- tasks/notes-api.md: acceptance criteria 1-6 and the recorded assumptions (revision interval, archived filter, tags, derived title, image upload, no-op PATCH)
- SPEC.md §8.1 (Notes), plus §4.2-§4.4 for ETag/If-Match, Problem Details, UUIDv7, soft delete/Trash, links and search
- docs/ARCHITECTURE.md conventions (module boundaries, migration naming), AGENTS.md "Project notes", docs/QUALITY.md
- Parent instructions: final review at HEAD c3d079f8, with attention to the c3d079f8 test change; confirm earlier findings remain fixed

Git range:
- Branch: main
- HEAD: c3d079f81e4b3d10f3b0868b73d32f824c64bd19
- Base/start: b18d8200c9154fdb777423c4f469fe5b02c6f20a
- Commits reviewed: 5 (02aa276b implementation, 7aa1322a reviews only, f2218a4f tests and reports, d565405c NUL-in-tags fix, c3d079f8 NUL side-effect test and reports)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## What I checked

- **c3d079f8 test change** (`NoteEdgeCaseWebTests.kt:312-346`, test `a NUL tag is reported together with other tag errors and a rejected change has no side effects`):
  - The JSON payloads are Kotlin raw strings, so `\u0000` reaches Jackson as a JSON escape and is decoded to a real NUL. The test therefore exercises the server-side check, not a literal backslash sequence. `"#\u0000"` becomes `"\u0000"` after `trim()`/`removePrefix("#")`. Kotlin `trim()` does not remove NUL, so the value is non-empty and the NUL check (`NoteService.kt:293`) is what rejects it.
  - The expected error order (length error first, then the NUL error) matches the order of the checks in `normalizeTags` (`NoteService.kt:291-295`). The assertion is deterministic.
  - The side-effect assertions are meaningful. A note with no revisions would get a revision from a successful body change (`keepRevision`, `latest == null`), so `revisions(...).shouldBeEmpty()` would catch a partial write. The event count is taken after the create. The body, the list and `/notes/tags` are checked after both rejected requests. The tag-filter GET passes a real NUL through a URI template variable, so it is encoded as `%00`.
  - Test isolation is sound. `@BeforeEach`/`@AfterEach` `reset()` clears notes, links, search documents, files and folders, so `list().titles() shouldContainExactly listOf("Plan")` does not depend on other tests.
- **d565405c fix** (NUL in tags and in the tag filter): `normalizeTags` rejects NUL before `findTagSpelling` sends anything to PostgreSQL and before any other field is applied (`NoteService.kt:268-276`). Create and PATCH both use it. `list` rejects a NUL tag filter with a 400 on field `tag` (`NoteService.kt:78`).
- **Earlier findings, checked against the current code:**
  - Concurrent body changes no longer return 500: the note is flushed before the revision insert (`NoteService.kt:253`).
  - The `archived` parameter has an enum in the schema (`NoteController.kt:65-68`, `api/openapi.yaml`).
  - The search document is built only in `SearchIndexer.indexNote` (`NoteService.kt:38`).
  - The API descriptions contain no `[[...]]`/`![](...)`. The only match in `NoteDtos.kt` is the response value at line 142, which is code, not a description.
  - A no-op PATCH still re-indexes and publishes UPDATED. This is accepted as a task assumption and is not reported again.
- **Acceptance criteria 1-4 and 6** (reviewed in full at the code level; no code changed since the previous review apart from the test above): CRUD with ETag/If-Match (428/412/409), the derived title, tag/pinned/archived filters, keyset paging, `[[type:id]]` link sync on create, body change and restore, image upload into `/Attachments/Notes` returning `cockpit-file:{id}`, 20-revision retention and restore, the NOTE resolver, the flexmark-stripped search document, the Trash contributor, ChangeEvents, and the regenerated `api/openapi.yaml` and web client.
- **Security:** the queries are parameterized. Nothing in the notes module logs anything, so no note contents are logged. The upload mime type is checked on the server. No secrets are in the diff.
- **Out of scope:** backup/restore of notes (SPEC.md §14) is the separate task `backup-contrib-notes`.
- **Tests run in this review, at HEAD c3d079f8:** `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.notes.*'` -> BUILD SUCCESSFUL. The JUnit XML shows 77 tests in 7 classes (NoteEdgeCaseWebTests 31, NoteWebTests 20, NoteInfraTests 9, NoteMarkdownTests 8, NoteRevisionInfraTests 5, NoteMigrationTests 3, NoteImageMaxSizeWebTests 1), with 0 failures, 0 errors and 0 skipped, and the new test is among them. `./gradlew ktlintCheck` -> BUILD SUCCESSFUL. I did not run OpenApiContractTest in this session. The c3d079f8 change is test-only and does not affect the contract.
- **Evidence (not a finding; the parent owns it):** the `verify` entries on the task name revision 26322171, which comes before d565405c. Criteria 1-6 need evidence recorded at the final HEAD, and `quality.json` needs `reviewed_revision` c3d079f8, before `done`.

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
