# Fix Report

Date: 2026-10-22

Task: journal-api - Journal entries API (upsert by date, heatmap, on this day, images, search, trash, resolver)

Scope: Current task, finding CR-2 from `reviews/journal-api/review.md` (CR-1 is handled by the test-writer)

## Fixed findings

### CR-2 - A Trash restore that races with a PUT for the same date returns 500 instead of 409

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/journal/entries/infra/JournalEntryTrashContributor.kt`

Problem:
`restore` checked `findActiveByDate` and then called `saveAndFlush`. If a concurrent PUT created an entry for that date between the check and the flush, the partial unique index on the date threw a `DataIntegrityViolationException`. `ProblemDetailsAdvice` does not map that exception, so the client got a 500.

Fix:
Wrapped `saveAndFlush` in a `try`/`catch (DataIntegrityViolationException)` that throws the same `ConflictException` (409) as the pre-check, the same way `JournalEntryService.put` does. The pre-check and the catch share one conflict message. Nothing else changed.

Validation:
- `cd server && ./gradlew ktlintCheck` - Pass
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.journal.entries.*'` - Pass (31 tests, 0 failures, 0 skipped)

## Not fixed

### CR-1 - Test gaps

Status:
Out of scope

Reason:
The parent worker gave CR-1 to the test-writer, not the fixer.

## Validation summary

Focused tests:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.journal.entries.*'` - Pass (31 tests across 4 classes, 0 failures)

Full test suite:
- Not run. The change is limited to one catch block in the journal module.

Lint/type-check/build:
- `cd server && ./gradlew ktlintCheck` - Pass; `compileKotlin` succeeded as part of the test run

`.taskcheck`:
- Not run. The parent worker runs it before `done`.

Remaining unresolved findings:
0 assigned to the fixer (CR-1 is with the test-writer)

## Final status

All validated findings fixed and validation passed.

No regression test was added for the race itself. It would need two concurrent transactions to reproduce, and test coverage belongs to the CR-1 test-writer run.

## Follow-up fix: TW-1 (2026-10-22)

Scope: Finding TW-1 from `reviews/journal-api/tests.md`, handed over by the parent worker.

### TW-1 - The journal entries migration is in a subfolder, which breaks the migration naming convention

Status:
Fixed

Files changed:
- `server/src/main/resources/db/migration/journal/entries/V202610220130__journal_entries.sql` -> `server/src/main/resources/db/migration/journal/V202610220130__journal_entries.sql` (renamed with `git mv`, content unchanged)

Problem:
The migration lived at `db/migration/journal/entries/...`, so its path had three segments. The documented layout is `db/migration/<module>/V<yyyyMMddHHmm>__<module>_<desc>.sql`, and `core.persistence.MigrationConventionTests` failed on the `parts shouldHaveSize 2` assertion.

Fix:
Moved the file into the `journal` module folder and removed the empty `entries/` folder. The file name stays the same. It matches the test's regex `V(\d{12})(_\d+)?__([a-z0-9]+)_([a-z0-9_]+)\.sql`: version `202610220130` is a valid strict `uuuuMMddHHmm`, module `journal` equals the folder name, there is no `_<n>` suffix and no `test_` prefix. Its version is still later than the existing journal habits migrations (`V202610211930`, `V202610212300`), so Flyway applies them in the same order. A grep for the old path found code references only in `tasks/journal-api.md` (`touches:` field). That file belongs to tasks.py and the parent worker, so the fixer left it alone.

Validation:
- `cd server && ./gradlew test --tests '*MigrationConventionTests' --tests 'ch.sonensei.cockpit.journal.*'` - Pass (111 tests across 9 classes, 0 failures, 0 errors, 0 skipped)

TW-1 remaining unresolved findings: 0. `.taskcheck` was not run by the fixer; the parent worker runs it before `done`.
