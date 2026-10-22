# Code Review

Date: 2026-10-22

Task: journal-api - Journal API

Scope: `git diff caeb0610c..HEAD`, the final code of the task. Commits 690699257 (implementation), ad724b1c4 (web tests, openapi, web client), dee2a7470 (CR-2 fix), b97a4cc6c (CR-1 tests), 0a9446495 (migration moved, TW-1), f28dc7d09 (reports), 75c6480ab (merge of origin/wip/journal-api) and 10068a319 (OpenAPI regenerated after merging main). Committed changes only. The working tree was clean when the review ran. This is a second review on the final code. The earlier review (CR-1 test gaps, CR-2 restore race returning 500) covered an older revision.

Requirements:
- tasks/journal-api.md: acceptance criteria 1-4 and the five recorded assumptions (upsert by date with the If-Match rules, mood 1-5 and tag normalization, images in /Attachments/Journal, heatmap and on-this-day defaults with the 28/29 Feb rule, a partial unique index with a 409 on restore)
- SPEC.md §8.5 (journal), §4.2-4.4 (API conventions, Trash, links)
- docs/ARCHITECTURE.md and the AGENTS.md "Project notes" (module packages, Flyway location and naming, Problem Details, ETag/If-Match, UUIDv7, generated files)
- The earlier reports in reviews/journal-api/ (review.md, tests.md, fixes.md)
- Existing patterns: NoteTrashContributor, TrashService, MigrationConventionTests

Git range:
- Branch: main
- HEAD: 10068a31968788c5e7dccd645dc15a483801ea72
- Base/start: caeb0610c967f37ae11c951aef0116f775682c96
- Commits reviewed: 8 (6 code/test/generated commits named by the parent, plus the reports commit f28dc7d09 and the merge commit 75c6480ab)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Status of earlier findings

- **CR-1 (test gaps): resolved.** `JournalEntryEdgeCaseWebTests.kt:171` covers the concurrent create. It commits a competing row from another connection inside the If-Match callback, then asserts a 409 `ConflictException`, one row, no search document and no change event. `JournalImageMaxSizeWebTests.kt:43` covers the 413 Problem Details answer at the limit and one byte above it. `JournalEntryEdgeCaseWebTests.kt:232` covers a replace that changes a tag only by case, which keeps the stored spelling.
- **CR-2 (restore race returned 500): resolved.** `JournalEntryTrashContributor.kt:42-47` catches `DataIntegrityViolationException` around `saveAndFlush` and throws the same `ConflictException` as the pre-check at line 40. `TrashService.restore` is `@Transactional`, so the restored state rolls back. The regression test at `JournalEntryEdgeCaseWebTests.kt:191` uses a delegating repository to insert the competing entry between the check and the flush. It asserts a 409, one active row, the entry still in the Trash and nothing indexed.
- **TW-1 (migration location): resolved.** The migration is now at `server/src/main/resources/db/migration/journal/V202610220130__journal_entries.sql`. That matches `db/migration/<module>/V<yyyyMMddHHmm>__<module>_<desc>.sql`, and the version sorts after the existing journal habits migrations.

## Notes (no findings)

- **Criterion 1:** the PUT upsert by date works as follows. A PUT for a date without an entry creates it and answers 201 with an ETag and a Location. If-Match on a missing entry gives 412. Replacing an existing entry needs If-Match and answers 200; without it the answer is 428, with a stale one 412. Dates after today in the user's time zone are rejected. Markdown images: `POST /journal/images` stores them in `SpecialFolder.JOURNAL` (/Attachments/Journal), accepts only `image/*` and respects `UploadLimits.maxSize`. Mood is checked in the service and by a database check constraint. Tags are normalized (trim, leading `#` removed, unique regardless of case, at most 50 tags of 50 characters, no NUL). Heatmap: `from`/`to` default to the last 365 days and may span at most 3660 days. On this day: earlier years only, newest first, with the 28/29 Feb rule. All of this matches the recorded assumptions.
- **Criterion 2:** `JournalEntryItemResolver` resolves `JOURNAL_ENTRY` to the title `Journal {date}` and the deep link `/journal/{date}`, and only active entries resolve. Search: the entry is indexed after create and replace, removed on delete and re-indexed on restore, with a single `indexJournalEntry` helper. Links: they are synced only when the body changes, and `TrashService.purge` removes them through `ItemDeletedEvent`. Trash: list, restore with a 409 when the date is taken, purge with the tags (cascade). The contributor mirrors `NoteTrashContributor`.
- **API conventions:** every error is Problem Details. The 413 handler is local to the controller, like notes. ETag/If-Match go through `ETags`. The ids come from `SoftDeletableEntity` (UUIDv7). Dates are ISO-8601. Unknown body fields and values of the wrong type give 400 with the field name.
- **Generated contract:** `api/openapi.yaml` only gains lines in the task range (+497, no deletions). So the regeneration in 10068a319 kept main's Mail search paths, and the generated web client adds only journal files. The parent reports that `OpenApiContractTest` passed without the flag on HEAD. This review did not run it.
- **Architecture:** the packages are `journal.entries.{api,domain,infra}`. The module uses `FilesService`, `LinkService`, `SearchIndexer`, `SettingsService` and `ChangeEventPublisher` through services only. Its one reuse of another module is the stateless `NoteMarkdown` helper, which is recorded as an assumption. The migration is new and was not edited after being applied.
- **Security:** authentication comes from the global configuration, and the tests use `@WithCockpitUser` with CSRF. The code adds no logging. The native query uses bound parameters only, and uploads are limited by type and size.
- **Process notes for the parent, not code findings:**
  - The task's `touches` field still names `db/migration/journal/entries`, but the migration now lives in `db/migration/journal/`.
  - `python tasks.py verify journal-api` shows criteria 1-4 not yet verified. Evidence has to be recorded before `done`, for example the parent's Testcontainers run of `ch.sonensei.cockpit.journal.entries.*` plus `OpenApiContractTest` on HEAD.
  - This review could not run Gradle and relied on the parent's reported run: 31 journal tests and `OpenApiContractTest` passed, 0 failures.

## Review conclusion

No material issues found.
