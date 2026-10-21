# Test Report

Date: 2026-10-21

Task: contacts-api - Contacts API

Scope: task changes `ae709721fd028ebe3f097492da4cf41b9ae03b03..c7b26f26677b8381efdd418c9b500c8697b0d6e4`
(base = `git merge-base HEAD origin/main` = `origin/main`; HEAD `c7b26f26` on `main` is the single squashed task commit
`contacts: Contacts API (...)` on top of origin/main, supplied by the parent). The commit contains only the contacts module,
its two migrations and tests, the regenerated `api/openapi.yaml` and `web/src/app/api/generated/**`, `reviews/contacts-api/**`,
and the one-line TW-2 fixture fix in two gtd convert tests. This matches the task's `touches` plus the TW-2 resolution.
The working tree was clean (no uncommitted inputs) before and after the measurement.

This report replaces the one written for the pre-squash HEAD `25efabf0f6b467623922be20dcbd8c9b1a01c0ea`. The contacts
production and test files are byte-identical to what was measured there (sha256 below). The squash folded in the 3 tests
the previous run added to `ContactEdgeCaseTests.kt`, plus the TW-2 fixture fix.

Requirements:
- `tasks/contacts-api.md` acceptance criteria and recorded assumptions
- SPEC.md §8.3 (contacts), §4.2 (API conventions), via the task
- docs/ARCHITECTURE.md "Coverage", docs/QUALITY.md, AGENTS.md

## Test files changed

None in this run. The parent asked for no test changes while a code review runs in parallel, and no defect was found
that would require one.

Test files in the task commit (measured unchanged):
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/ContactEdgeCaseTests.kt` (44 tests, including the 3 added by the previous test-writer run)
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/ContactWebTests.kt` (11 tests)
- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/convert/TaskToNoteWebTests.kt` and `TaskToNoteRollbackTests.kt`
  (TW-2 fix: the "item type without a resolver" fixture now uses `ItemType.HABIT` instead of `ItemType.CONTACT`)

## Tests added or updated

Count: 0 in this run (the task commit has 55 contacts tests; the previous run added 3 of them).

## Requirement coverage

Command C1 = `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.*' --tests 'ch.sonensei.cockpit.gtd.convert.*' --tests '*OpenApiContractTest' jacocoTestReport`
(Testcontainers Postgres) on HEAD `c7b26f26677b8381efdd418c9b500c8697b0d6e4` with a clean tree: ContactEdgeCaseTests 44/44,
ContactWebTests 11/11, OpenApiContractTest 1/1, TaskToNoteEdgeCaseTests 17/17, TaskToNoteRollbackTests 1/1,
TaskToNoteWebTests 8/8. 82 tests, 0 failures, 0 errors, 0 skipped.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: Contact entity with §8.3 fields; create with ETag/Location, indexing, event | `POST creates a contact with ETag and Location, indexes it and announces it`, `fields are trimmed, blank values become null and blank notes are dropped`, `field lengths are limited`, `a contact has at most 100 emails, phones and addresses together`, `entries without their address or number are rejected`, `values of the wrong type are 400 naming the field, and a rejected PATCH changes nothing` | C1 | Pass |
| AC1: displayName derivation; no name source is 400 (assumption) | `the display name is derived from the name, the organization or the email address`, `POST rejects contacts without any name, invalid entries, unknown fields and an unknown photo`, `PATCH that clears the display name and every source of it is rejected` | C1 | Pass |
| AC1: CRUD with ETag / If-Match | `PATCH changes and clears fields, needs If-Match and re-indexes`, `If-Match accepts a wildcard and a list of tags, but not a weak tag`, `a second client with the old ETag gets 412 and does not overwrite the first change`, `an update that races a committed concurrent change fails with an optimistic locking error`, `PATCH replaces lists as a whole and keeps the lists not sent`, `changes to a contact in the Trash or an unknown contact are 404` | C1 | Pass |
| AC1: list with cursor, limit, q filter (assumptions) | `GET lists by display name with a cursor and filters by name or email`, `the cursor pages through names containing the separator and equal names`, `limit must be between 1 and 200`, `a cursor without the id part is rejected`, `q treats like wildcards and backslashes literally`, `q excludes contacts in the Trash`, `q matches email addresses but not the labels or JSON keys around them`, `q stays applied on every page of the cursor` | C1 | Pass |
| AC1: photo via Files | `a photo is a file of the drive`, `a folder of the drive is not a photo`, `PATCH clears the photo with null, and a rejected PATCH changes nothing`, `a contact whose photo went to the Trash can still be changed` | C1 | Pass |
| AC1: resolver CONTACT and search | `contacts are found by the global search and resolved for links`, `search follows changes of the indexed fields`, `the resolver falls back to the email address and knows no deleted or unknown contact`, `imported contacts are searchable, resolvable and announced as created` | C1 | Pass |
| AC1: trash (delete, restore, purge) | `DELETE moves the contact to the Trash, restore and purge go through the Trash`, `restore re-indexes the latest state, purge needs the contact to be in the Trash` | C1 | Pass |
| AC2: vCard import (multi-contact file, skipped cards, photos, limits) | `import creates a contact per card, stores embedded photos and skips invalid cards`, `import rejects a file without vCards`, `import reads vCard 2_1 with quoted-printable, charsets, bare types and a base64 photo`, `import reads vCard 4_0 tel URIs, data URI photos and basic dates, and ignores photo URLs`, `a skipped card leaves no photo behind and says why it was skipped`, `photo file names of imported cards are made safe`, `a C1 control character in the name does not cost the card its import`, `photos without a type are stored as JPEG, named after the family name or Contact`, `empty email, phone, address and organization properties are dropped instead of costing the card its import`, `a photo over 5 MB is left out, the card is still imported`, `import refuses files over 20 MB and with more than 10000 cards`, `import of a file that is not UTF-8 or not a vCard does not fail with a server error`, `imported cards without FN get the display name derived from N, ORG or EMAIL` | C1 | Pass |
| AC2: vCard export (ez-vcard, 3.0, ids, round-trip) | `export writes all contacts as vCard 3_0 that imports back`, `labels with several types and every field survive an export and import round-trip`, `export keeps a free-text label with characters vCard parameters cannot hold`, `export lists contacts by name, once per id, and leaves out the Trash`, `export of no contacts is an empty file`, `export with an empty ids parameter exports every contact` | C1 | Pass |
| Security: login and CSRF on every endpoint; malformed bodies | `every contacts endpoint needs a login`, `changing requests without a CSRF token are refused and change nothing`, `a body that is not a JSON object and an import without the file part are 400` | C1 | Pass |
| AC3: `./gradlew test --tests 'ch.sonensei.cockpit.contacts.*'` passes (Testcontainers Postgres) | all 55 contacts tests | C1 | Pass |
| AC4: `OpenApiContractTest` passes without the update flag | `OpenApiContractTest` | C1 (no `UPDATE_OPENAPI` set) | Pass |
| TW-2 regression: gtd task-to-note still works with the CONTACT resolver registered | `TaskToNoteWebTests."carries links over in their direction with their label"`, `TaskToNoteRollbackTests."a failing link copy rolls back the note and the task deletion"`, and the rest of `ch.sonensei.cockpit.gtd.convert.*` (26 tests) | C1 | Pass |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.*' --tests 'ch.sonensei.cockpit.gtd.convert.*' --tests '*OpenApiContractTest' jacocoTestReport`

Result:

Pass: BUILD SUCCESSFUL in 50s, 82 tests, 0 failures, errors or skipped (counts read from the fresh
`server/build/test-results/test/TEST-*.xml`, all written 09:41:20+02:00 in this run).

A first attempt that also ran `cleanTest cleanJacocoTestReport` failed in Gradle's own result handling, not in a test:
`java.nio.file.NoSuchFileException: .../server/build/test-results/test/binary/in-progress-results-generic.bin` (BUILD FAILED
in 57s). The most likely cause is a parallel Gradle run in the same clone, since the code-reviewer runs at the same time.
That attempt produced no usable results and none of its output is used here. The rerun above (the same selection without
the clean tasks; `:test` was out of date, so it ran in full) passed.

## Full test execution

Command:

`sh scripts/check.sh` (`.taskcheck`)

Result:

Not run in this pass: the parent said it will run it. The previous run of `scripts/check.sh` on pre-squash HEAD
`25efabf0` failed only on the two gtd convert tests of TW-2 (3042 server tests, 2 failed). Both now pass in C1.

## .taskcheck

Result:

Not run (the parent runs it, see above).

## Bugs found

No defect in the contacts module. One finding from the previous run, now resolved:

### Adding the CONTACT resolver broke gtd task-to-note tests that used CONTACT as "a type without a resolver" (RESOLVED)

Finding ID:

`TW-2`

Test:

`ch.sonensei.cockpit.gtd.convert.TaskToNoteWebTests."carries links over in their direction with their label"` and
`ch.sonensei.cockpit.gtd.convert.TaskToNoteRollbackTests."a failing link copy rolls back the note and the task deletion"`

Production source:

`server/src/main/kotlin/ch/sonensei/cockpit/contacts/infra/ContactItems.kt` (`ContactItemResolver`, new in this task)
interacting with `server/src/main/kotlin/ch/sonensei/cockpit/gtd/convert/domain/TaskToNoteService.kt`
(`exists(ref) = resolvers.find(ref.type)?.exists(ref.id) ?: true`).

Requirement:

Task AC1 adds "resolver CONTACT". `.taskcheck` must pass on the merged result before `done` (AGENTS.md).

Expected behavior:

The gtd convert tests pass with the contacts module merged.

Actual behavior (before the fix):

Both fixtures linked the task to `ItemRef(ItemType.CONTACT, randomUUID())` and treated it as a type without a resolver.
With the CONTACT resolver registered, that random id resolves as missing, so the link was not copied and both tests failed.

Severity:

High (blocked `.taskcheck`; no user-facing defect).

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.` (Done; the fix was in the test fixtures, not in production code.)

Resolution: in HEAD `c7b26f26`, `TaskToNoteWebTests.kt:127` and `TaskToNoteRollbackTests.kt:51` use
`ItemType.HABIT` instead of `ItemType.CONTACT`. Only those two lines changed. No production code changed.
Checked: no `ItemResolver` for `ItemType.HABIT` is registered in `server/src/main` at HEAD (grep for `override val type`).
C1 runs all 26 `gtd.convert` tests, including both that failed before, and all pass. Both tests still check what they were
written to check: a link to a type without a resolver is carried over, and a failing link copy rolls back.
Follow-up note (Low, not a defect now): this fixture breaks again in the same way once a future habits task registers a
HABIT resolver. A fixture that does not depend on which types happen to have no resolver would avoid that, for example a
mocked or test-only `ItemResolver` setup. The test-writer did not change it, as the parent asked.

## Coverage

Status: verified (whole-file, fresh run on a clean, stable snapshot of HEAD).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. Every Kotlin file in scope is new in this task (status `A` in the
diff), so whole-file coverage equals changed-line coverage for them.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: contacts-api; base
  `ae709721fd028ebe3f097492da4cf41b9ae03b03` (`git merge-base HEAD origin/main`, equal to `origin/main`); HEAD
  `c7b26f26677b8381efdd418c9b500c8697b0d6e4`, the single squashed task commit (from the parent; checked with
  `git log` and `git show --stat HEAD`). File scope: `git diff --name-status ae709721..c7b26f26 -- server/src/main web/src android/app/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD c7b26f26, clean working tree (`git status --short`
  empty after the run). sha256: ContactEdgeCaseTests.kt `6fcf105c2988c73e404a95110146c06a07231ceec9a447367966b7831778f3c5`,
  ContactWebTests.kt `212e40ac7be8a3d35d23a681b63acbda7c0d4610e5c6baf16cc590ec2a01cb31`,
  TaskToNoteWebTests.kt `22b1e025994455b105414c48565fa2fdd0c669154b3b9272866bd5ac343371f3`,
  TaskToNoteRollbackTests.kt `4425b162c9d42b9ca5772b737f874bde2fa018ddf1dde6f5a61abbd4911039ae`,
  ContactController.kt `ce06c9ea324d8a37...`, ContactDtos.kt `54da9ebfc97dd984...`, Contact.kt `53fc6e3306c32e59...`,
  ContactService.kt `e7740bee457e3e7a...`, VCardService.kt `691627a7e8829213...`, ContactItems.kt `35a451276a602e12...`,
  ContactRepository.kt `6b2132760871fdfb...`. The contacts hashes are identical to the pre-squash measurement.
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/contacts/{api/ContactController.kt, api/ContactDtos.kt,
  domain/Contact.kt, domain/ContactService.kt, domain/VCardService.kt, infra/ContactItems.kt, infra/ContactRepository.kt}`.
  Excluded: the migrations `V202610211000__contacts_model.sql` and `V202610211010__contacts_email_search.sql` (SQL, JaCoCo
  cannot instrument them; they run in every Testcontainers test, and `contacts_email_matches` is exercised by the q tests);
  `web/src/app/api/generated/**` and `api/openapi.yaml` (generated; the contract is checked by OpenApiContractTest);
  `reviews/**` (docs); test files. No renames or deletions. `ContactRepository.kt` is a Spring Data interface without
  executable bytecode, so JaCoCo has no counters for it (N/A, not missing). JaCoCo also lists `contacts/domain/Extensions.kt`
  without counters. No such source exists at HEAD or in the diff (a stale class in the build directory), so it is out of scope.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (no `toolVersion`
  set, the default of Gradle 9.8.1); C1 in `server/`; selection: the contacts test package (55), `gtd.convert.*` (26) and
  OpenApiContractTest (1).
- Run start/completion time and exit status: JaCoCo session `dell26-b9b31f11`, 2026-10-21T09:40:32+02:00 to
  09:41:20+02:00, report written 09:41:22+02:00; BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`,
  sha256 `6654814475cb8fc741142e9f9d2c76dae962f740a5855acbb9c3935d90793b44`, session `dell26-b9b31f11`; exec data
  `server/build/jacoco/test.exec` (09:41:20).
- Evidence outputs are fresh and inputs stayed unchanged: the Gradle jacoco plugin deletes `test.exec` before `:test`
  runs. The failed first attempt had already removed the test results and the report (`cleanTest`, `cleanJacocoTestReport`).
  The exec file, report, session and every test-result XML fall inside this run's window. The source and test hashes were
  the same before and after the run, and HEAD did not change.
- Final run after latest test additions and production fixes: yes. It ran on the final squashed HEAD, which includes the
  TW-2 fix. No production code changed after the previous measurement, and the counters are identical to it.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.
"Statements" = JaCoCo INSTRUCTION counter (JaCoCo has no statement counter); "Functions" = METHOD.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `contacts/api/ContactController.kt` | 340/347 (98.0%) | 13/14 (92.9%) | 12/12 (100%) | 42/42 (100%) |
| `contacts/api/ContactDtos.kt` | 825/876 (94.2%) | 14/16 (87.5%) | 56/67 (83.6%) | 120/120 (100%) |
| `contacts/domain/Contact.kt` | 364/440 (82.7%) | 10/20 (50.0%) | 52/56 (92.9%) | 48/51 (94.1%) |
| `contacts/domain/ContactService.kt` | 1452/1456 (99.7%) | 105/106 (99.1%) | 40/40 (100%) | 150/150 (100%) |
| `contacts/domain/VCardService.kt` | 1195/1214 (98.4%) | 95/104 (91.3%) | 19/19 (100%) | 123/123 (100%) |
| `contacts/infra/ContactItems.kt` | 160/169 (94.7%) | 19/20 (95.0%) | 8/8 (100%) | 35/35 (100%) |
| `contacts/infra/ContactRepository.kt` | N/A | N/A | N/A | N/A |
| **Aggregate (summed counters over the 6 measured files; ContactRepository N/A, migrations and generated files excluded)** | 4336/4502 (96.3%) | 256/280 (91.4%) | 187/202 (92.6%) | 518/521 (99.4%) |

No coverage target is defined (ARCHITECTURE.md "Coverage": "It is not a gate").

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `domain/Contact.kt:86,92,102` (uncovered lines), `104` (partial) | Unused enum constants (`GOOGLE`/`MICROSOFT`/`CARDDAV` are for future sync tasks); generated `data class` members (`copy`, `componentN`, `equals`/`hashCode` branches) of ContactEmail/Phone/Address | Non-material: generated code; sync sources belong to other tasks (contacts-google, contacts-microsoft) |
| `domain/Contact.kt:111,113,118,120,125,127` | Null branches in the jsonb `AttributeConverter`s (`attribute.orEmpty()`, `dbData?.let`) | Defensive: the columns are `not null`, and Hibernate never passes null for them |
| `api/ContactDtos.kt:57-58` (partial), uncovered methods | `checkNotNull(createdAt/updatedAt)` failure branches; generated `componentN`/`copy` of the DTOs | Defensive or generated; persisted entities always have timestamps |
| `api/ContactController.kt:193` | `?: "body"` fallback when a JacksonException has no property path | The 400 responses for wrong-typed fields and non-object bodies are tested. No request tried produced a conversion error without a property path. The fallback returns the same 400, only with field `body` |
| `domain/ContactService.kt:211` | `items.find(ItemType.FILE)` returning null (FILE resolver not registered) | Defensive: the files module always registers the resolver |
| `domain/VCardService.kt:65` | `checkNotNull` on the transaction result | Defensive: `importCard` never returns null |
| `domain/VCardService.kt:94,98,140,145,206` | Null-safe branches over ez-vcard properties (null value/text/uri, photo with empty data, null content type, null type values) | The main cases are covered by the 2.1/4.0/empty-property/no-type-photo tests. The remaining branches are null shapes ez-vcard does not produce from parsed text |
| `infra/ContactItems.kt:47` | `checkNotNull(deletedAt)` failure in the Trash listing | Defensive: the Trash query only returns deleted rows |

No meaningful gap in task behavior remains.

## Unable to test

- Nothing in this task's acceptance criteria. Embedding photos in the export is out of scope by a recorded assumption
  (FilesService exposes no blob read to other modules), so it was not tested. Backup/restore of contacts belongs to the
  separate task `backup-contrib-contacts` and was not tested here.
- `.taskcheck` (`sh scripts/check.sh`) was not run in this pass, as the parent instructed. The parent runs it.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.

On the squashed HEAD `c7b26f26677b8381efdd418c9b500c8697b0d6e4`, all 55 contacts tests, all 26 gtd convert tests and
OpenApiContractTest pass against Testcontainers Postgres (C1, 82/82). Task-scoped coverage is unchanged from the
pre-squash measurement, and only defensive or generated code is uncovered. TW-2 is resolved by the `ItemType.HABIT`
fixture change; a Low follow-up note about that fixture's future fragility is recorded above. `.taskcheck` is left to the parent.
