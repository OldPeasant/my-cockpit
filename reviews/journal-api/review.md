# Code Review

Date: 2026-10-22

Task: journal-api - Journal API

Scope: `git diff 27bedf5d..05208f67`: the implementation commit ba2782c1 and the commit 05208f67 with web tests and the regenerated `api/openapi.yaml` / `web/src/app/api/generated`. Committed changes only. Test-only commits the test-writer adds in parallel are out of scope.

Requirements:
- tasks/journal-api.md: acceptance criteria 1-4 and the five recorded assumptions (upsert by date with If-Match semantics, mood 1-5 and tag normalization, images in /Attachments/Journal, heatmap/on-this-day defaults and the 28/29 Feb rule, partial unique index with a 409 on restore)
- SPEC.md §8.5, §4.2-4.4
- docs/ARCHITECTURE.md conventions (module packages, migrations, module boundaries)
- AGENTS.md project notes (Problem Details, ETag/If-Match, generated files, logging)
- Existing notes module patterns (NoteService, NoteTrashContributor, NoteItemResolver, NoteController image upload)

Git range:
- Branch: main
- HEAD: 05208f6791abddbaa4494425aef2725a9ec04f17
- Base/start: 27bedf5dd61d28dd0b4a461dddf0ac497943016d
- Commits reviewed: 2 (ba2782c1, 05208f67)

Summary: 0 P0, 0 P1, 1 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Some behaviours promised by the assumptions have no tests**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/journal/entries/JournalEntryWebTests.kt:1`
    - Category: Tests
    - Problem: The web tests cover upsert, If-Match (428/412), validation, time zone, list/paging/tag filter, heatmap, on this day (including 28/29 Feb), image upload to Attachments/Journal, resolver, search, links and the trash restore conflict. Three behaviours have no test: (a) a concurrent create of the same date returning 409. This is the `DataIntegrityViolationException` -> `ConflictException` branch at `JournalEntryService.kt:131-136`, and it is the only code path that relies on the partial unique index. (b) the 413 Problem Details answer for an image larger than the limit (`JournalEntryController.handleTooLarge`; notes has `NoteImageMaxSizeWebTests` for the same thing). (c) a PUT that changes a tag only by case keeps the existing spelling (`JournalEntryService.kt:124-128`).
    - Why it matters: (a) is a documented API contract (assumption 1, the openapi 409 response). A regression there would show up as a 500 or a second row for one date, and no test would fail.
    - Suggested fix: Add a test that inserts a row for the date between the service's lookup and its save, or calls `put` from two threads, and expects 409 with exactly one active row. Add a 413 test with a small `cockpit` upload limit, as notes does. Add a test that PUTs `["walk"]` over `["Walk"]` and expects `Walk`. (The test-writer may already cover these in its parallel commits.)

## P3 Low

- [ ] **CR-2 | A Trash restore that races with a PUT for the same date returns 500 instead of 409**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/journal/entries/infra/JournalEntryTrashContributor.kt:38-42`
    - Category: Correctness
    - Problem: `restore` checks `findActiveByDate` and then calls `saveAndFlush`. If a concurrent PUT creates the entry for that date between the check and the commit, the partial unique index `journal_entry_date` rejects the flush with a `DataIntegrityViolationException`. `ProblemDetailsAdvice` does not map that exception (it handles only `ApiException`, `OptimisticLockingFailureException` and `ConstraintViolationException` before the generic `Exception` handler), so the client gets a generic 500. `JournalEntryService.put` handles the same race and answers 409.
    - Why it matters: The data stays consistent, because the index holds. But the client gets a 500 instead of the documented 409, and the error log gets noise. The window is small (single user), so the priority is low.
    - Suggested fix: Catch `DataIntegrityViolationException` around `saveAndFlush` in `restore` and throw the same `ConflictException` as the check above, mirroring `JournalEntryService.put`.

## Notes (no findings)

- Every acceptance criterion's behaviour is implemented: the upsert by date (201/200, If-Match rules), Markdown with `cockpit-file:` images stored in `/Attachments/Journal`, mood 1-5 (DB check constraint and validation), tags (normalized, case-insensitive unique, max 50x50), heatmap (default 365 days, max 3660), on this day (earlier years only, 28/29 Feb rule), resolver `JOURNAL_ENTRY` (title `Journal {date}`, deep link `/journal/{date}`), search indexing as plain text, and the Trash (list, restore with a 409 when the date is taken, purge). The behaviour matches the recorded assumptions.
- API conventions: errors are Problem Details. ETag/If-Match goes through `ETags`: the strong comparison, 428 when If-Match is missing, 412 for an If-Match on a missing entry (RFC 9110 §13.1.1). Cursor pagination uses `CursorRequest` with the date as the keyset; the date is unique among active entries, so it is stable. Ids are UUIDv7 through `SoftDeletableEntity`. Dates are ISO-8601.
- Architecture: the packages follow `journal.entries.{api,domain,infra}`. The module talks to files and core through services only (`FilesService`, `LinkService`, `SearchIndexer`). It reuses `NoteMarkdown`, a stateless helper object rather than another module's repository; this is recorded as an assumption. The migration is new and sits in the folder this task touches.
- Security: authentication is inherited from the global security config (the tests use `@WithCockpitUser` and CSRF). The upload is limited to `image/*` and `UploadLimits.maxSize`, like notes. The code logs nothing, and the 500 handler hides exception messages from clients.
- Backup/restore of journal entries (SPEC.md §14) is outside this task and has its own task, `backup-contrib-journal`.
- Evidence: `tasks.py verify journal-api` shows no criterion verified yet. The parent worker still has to record evidence for criteria 3 and 4 (the Testcontainers test run and `OpenApiContractTest` without the flag). This review did not run them.

## Review conclusion

No material issues found. CR-1 (test gaps) and CR-2 (a low-priority edge case) are recommended fixes. Neither blocks completion as long as the evidence for criteria 3 and 4 is recorded.
