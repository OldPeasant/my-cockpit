# Test Report

Date: 2026-10-22

Task: backup-contrib-contacts (Backup contributor: contacts)

Scope: task commits 302fdd1c (production + fixture), f57274be (contributor tests), 2a04e687 (fixer: BackupRows helpers) on base fd79cbd8e759e4b78aa960188869212c573f5196, HEAD 2a04e687568591b72ea7d183b6b033638b587953. These are the parent-supplied commits 28d327fe / 8ab11d57 / 02b2368b on base 5708c4f2, rebased during this run; `git patch-id --stable` is identical for each pair (9fb68557, 61ded759, 7bd6c7dc), and `git merge-base HEAD origin/main` = fd79cbd8. Plus one uncommitted test addition from this run (see snapshot below).

Requirements:
- tasks/backup-contrib-contacts.md acceptance criteria and its two recorded assumptions (jsonl holds every contact incl. external sources and Trash; vcf mirror excludes Trash and references photos as `PHOTO;VALUE=uri:../../files/` followed by the file's backup path)
- SPEC.md §8.3, §10.2, §14 as cited by the contributor KDoc
- docs/ARCHITECTURE.md "Coverage"

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/backup/ContactsBackupContributorTests.kt`

(`ContactsBackupFixture.kt` and the other 9 contributor tests came from the task commits; I reviewed them and left them unchanged.)

## Tests added or updated

Count: 1 added (`a photo whose file name has no or an unknown extension is still a PHOTO uri to its file entry, without type`). It covers the `ImageType.find(...)` path when the extension is missing or unknown, so the photo has no type, and checks that the vcf still parses and the URI still points to the file's entry. The existing 9 tests already covered every other part of the criteria; nothing in them was weakened.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| `data/contacts.jsonl` holds every contact with all fields, including Trash and external sources (GOOGLE + externalId) | `contacts jsonl holds every contact with all fields, ...`, `contacts jsonl is present and empty when there are no contacts` | `cd server && ./gradlew test --tests '*ContactsBackupContributorTests' --tests '*BackupRoundTripTest' jacocoTestReport` | Pass |
| `readable/contacts/contacts.vcf` parses back with ez-vcard (vCard 3.0, sorted by name, UID, all fields) | `the vcf parses back into one vCard 3_0 per contact not in the Trash, ...`, `the vcf has no PRODID and writes a contact with only a name ...` | same | Pass |
| Trash excluded from the vcf; no vcf when every contact is trashed | `the vcf parses back ...` (trashed contact absent), `there is no vcf when every contact is in the Trash` | same | Pass |
| Photo URIs resolve to `files/` entries of the same zip (spaces, brackets, unicode percent-encoded; missing file means no PHOTO; no or unknown extension means PHOTO without type) | `a photo is a PHOTO uri that resolves to the photo's file entry ...`, `a photo whose file is missing ...`, `a photo whose file name has no or an unknown extension ...` (new) | same | Pass |
| Import restores rows exactly (ids, times, version, Trash state, source, external id) and indexes only non-trashed contacts for search | `import restores every contact with its id, ...`, `import indexes the contacts not in the Trash for search, ...` | same | Pass |
| Round-trip test passes with `ContactsBackupFixture` seeding contacts with photos | `BackupRoundTripTest` (picks up `ContactsBackupFixture` through classpath scan) | same | Pass (1 test) |

## Focused test execution

Command:

`cd server && ./gradlew test --tests '*ContactsBackupContributorTests' --tests '*BackupRoundTripTest' jacocoTestReport` (04:53:22 to 04:54:03 +02:00)

Result:

Pass. BUILD SUCCESSFUL. ContactsBackupContributorTests had 10 tests, 0 failures, 0 errors, 0 skipped. BackupRoundTripTest had 1 test, 0 failures. My first three attempts failed with `NoSuchFileException .../in-progress-results-generic.bin` / `EOFException`, because another Gradle build was writing to the same `server/build/test-results` directory at the same time. Those failures came from the infrastructure, not from the tests. I waited until the other build finished, then reran.

## Full test execution

Command:

`cd server && ./gradlew check` (04:54:43 to 05:01:12 +02:00)

Result:

Pass. BUILD SUCCESSFUL in 6m 29s. ktlint main/test/script passed. The test results add up to 4140 tests in 303 suites, with 0 failures, 0 errors and 0 skipped.

## .taskcheck

Result:

Not run. `scripts/check.sh` also covers the web and android parts, which this task does not touch. The server part of it (`./gradlew check`) ran and passed above. The parent runs `.taskcheck` before `done`.

## Bugs found

None.

## Coverage

Status: verified for `ContactsBackupContributor.kt`. For `VCardService.kt` (one visibility change), the whole-file numbers are measured but only reflect the focused test selection (see below).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: 118/118 executable changed lines (100%). Method: `ContactsBackupContributor.kt` is a new file, so all its JaCoCo lines count as changed. The VCardService change (`git diff -U0`: hunk `@@ -155 +155,2 @@`, a KDoc line and the `fun toVCard` signature) has no JaCoCo line entries, so it adds 0 to the denominator. Its body (lines 157-160) is covered.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: backup-contrib-contacts; base fd79cbd8e759e4b78aa960188869212c573f5196 (rebased from the parent-given 5708c4f2, same patch-ids); HEAD 2a04e687568591b72ea7d183b6b033638b587953; commits come from the parent and `git diff --name-only fd79cbd8..HEAD -- server/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 2a04e687 + uncommitted `ContactsBackupContributorTests.kt` sha256 fdf664e464357319… (`.tmp/snap-before.txt`, checked unchanged with `sha256sum -c` after both runs).
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/contacts/backup/ContactsBackupContributor.kt`, `server/src/main/kotlin/ch/sonensei/cockpit/contacts/domain/VCardService.kt`. No renames or deletions. Excluded: test sources (`ContactsBackupFixture.kt`, tests) and `reviews/*`.
- Provider/version, command, working directory, and test selection: JaCoCo (Gradle `jacoco` plugin, Gradle 9.8.1, OpenJDK 21.0.12.1); `./gradlew test --tests '*ContactsBackupContributorTests' --tests '*BackupRoundTripTest' jacocoTestReport` in `server/`.
- Run start/completion time and exit status: 2026-10-22T04:53:22+02:00 to 04:54:03+02:00, BUILD SUCCESSFUL.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` (sha256 8d790094ae1ea1f9…, written 04:54:03), `server/build/jacoco/test.exec` (sha256 833b78cde2a418d5…, written 04:54:01).
- Evidence outputs are fresh and inputs stayed unchanged: yes. The exec, XML and test-result timestamps all fall inside the run window, and the input hashes were verified after the run.
- Final run after latest test additions and production fixes: yes (it includes the new test and the fixer commit 2a04e687).

Values below use covered/total (percentage).

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/contacts/backup/ContactsBackupContributor.kt` | 689/694 (99.3%) | 12/12 (100%) | 36/36 (100%) | 118/118 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/contacts/domain/VCardService.kt` | 333/1214 (27.4%) | 15/104 (14.4%) | 4/19 (21.1%) | 43/123 (35.0%) |
| Aggregate (summed counters, both files) | 1022/1908 (53.6%) | 27/116 (23.3%) | 40/55 (72.7%) | 161/241 (66.8%) |

No files are missing or unmeasured. The aggregate is dominated by VCardService code that the task did not change (vCard import/export), which the focused selection does not exercise. Its own tests ran in `./gradlew check` but were not part of this coverage run.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `ContactsBackupContributor.kt:135` (5 instructions) | Bytecode that Kotlin `use {}` generates to close the stream when an exception is thrown | Not material: compiler-generated close-on-throw path, not task behavior |
| `ContactsBackupContributor.kt` `ImageType.find` with missing or unknown extension | Photo URI without type | Covered by the new test |
| `VCardService.kt:25-152, 187-210` | Existing vCard import/parse paths, not changed by this task | Outside task scope (only the `toVCard` visibility changed; its body, lines 157-160, is covered) |

## Unable to test

- Nothing within the criteria. Restore through the full CLI/web path is covered by `BackupRoundTripTest` and the backup module's own tests, not by this task.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. I added one test, and all validation passed: focused tests and `./gradlew check`.
