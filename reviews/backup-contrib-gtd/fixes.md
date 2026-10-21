# Fix Report

Date: 2026-10-21

Task: backup-contrib-gtd - GTD backup contributor

Scope: the current task. Findings TW-1 (test-writer), and CR-2 and CR-3 from `reviews/backup-contrib-gtd/review.md`. Base commit 76be28d5f9df6c21b174b2d5b6445b37f385e68c, task commits e3539c5e and 7599e42c.

## Fixed findings

### TW-1 - A long title cut inside an emoji gives a malformed ZIP entry name

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/gtd/backup/GtdBackupRecords.kt`

Problem:
`fileName` cut titles with `.take(100)`, which counts UTF-16 units. A cut inside a surrogate pair left a lone high surrogate, and `ZipOutputStream.putNextEntry` threw `IllegalArgumentException: malformed input`, so the whole export failed. The test-writer's test `a long title cut inside an emoji still gives a valid, unique file name` failed.

Fix:
A new private helper `takeCodePoints` cuts by code points (`offsetByCodePoints`), so a surrogate pair is never split. The limit is now 100 code points. The test was not changed.

Validation:
- `./gradlew test --tests '*GtdBackupContributorTests' --tests '*BackupRoundTripTest'` - Pass (the emoji test passes now)

### CR-2 - Readable file names keep characters that are invalid on Windows

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/gtd/backup/GtdBackupRecords.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/gtd/backup/GtdBackupContributor.kt` (KDoc only)
- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/backup/GtdBackupContributorTests.kt`

Problem:
`FILE_NAME_FORBIDDEN` only replaced `/`, `\` and control characters. Names could contain `: * ? " < > |` or end in a dot or space, and Windows cannot extract such entries.

Fix:
`FILE_NAME_FORBIDDEN` also replaces `:*?"<>|` with a space, as it already does for other forbidden characters. Trailing dots and whitespace are trimmed after the cut, so a cut cannot leave a trailing space either. The class KDoc describes the new rule. New test `file names have no characters Windows forbids and no trailing dots or spaces` covers an area and project names with the forbidden characters, `Done.`, `Etc. . .`, `???` (which gives `Untitled`) and a cut that ends on a space. Windows-reserved device names (`CON`, `NUL`, ...) were not part of the finding and are not handled.

Validation:
- `./gradlew test --tests '*GtdBackupContributorTests' --tests '*BackupRoundTripTest'` - Pass

### CR-3 - waiting.md is ordered by position, not by waiting-since

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/gtd/backup/GtdBackupContributor.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/backup/GtdBackupContributorTests.kt`

Problem:
Every list mirror used `LIST_ORDER` (position, id). SPEC.md §5.3 sorts the Waiting For list by `waitingSince`.

Fix:
A new comparator `WAITING_ORDER` sorts by `waitingSince` with nulls last, then by `LIST_ORDER` (position, id). It is used for `TaskList.WAITING` only. New test `waiting md is ordered by waiting-since, tasks without a date last, then by position` covers an earlier date, a tie on the same date broken by position, and a task without a date at the end. The existing waiting test still passes unchanged.

Validation:
- `./gradlew test --tests '*GtdBackupContributorTests' --tests '*BackupRoundTripTest'` - Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat` - Pass, no remaining changes
- `cd server && ./gradlew test --tests '*GtdBackupContributorTests' --tests '*BackupRoundTripTest'` - Pass: GtdBackupContributorTests 20 tests with 0 failures (2 new), BackupRoundTripTest 1 test with 0 failures. The first attempt stopped in `compileTestKotlin` because the Kotlin daemon ran out of memory while the machine was busy. The retry passed with no changes.

Full test suite:
- `cd server && ./gradlew check` (ktlint and all server tests) - BUILD SUCCESSFUL (17 min, on a loaded machine)

Lint/type-check/build:
- ktlint ran as part of `./gradlew check` - Pass

`.taskcheck`:
- Not run by the fixer. Only `server/` changed, and its part of the check (`./gradlew check`) passed. The parent worker runs `.taskcheck` before `done`.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
