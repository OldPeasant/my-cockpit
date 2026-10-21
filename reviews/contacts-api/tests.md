# Test Report

Date: 2026-10-21

Task: contacts-api - Contacts API

Scope: task changes `cbaf064792db674cdcf8c01cb37179bd266e5275..25efabf0f6b467623922be20dcbd8c9b1a01c0ea`
(base = `git merge-base HEAD origin/main`; HEAD `25efabf0` on `main` is the merge of `origin/wip/contacts-api` into
current main). Task-owned commits (`git log origin/main..HEAD`, all prefixed `contacts:`): 320553ac, 4338124d, 95b6c9b0,
7dcf06bf, 74c7de92, 19cec089, 9b305b0d, d4319609, 9b0c9160, plus the merge 25efabf0. The diff contains only the
contacts module, its migrations and tests, the regenerated `api/openapi.yaml` and `web/src/app/api/generated/**`, and
`reviews/contacts-api/**`, which matches the task's `touches` and the scope the parent supplied. Uncommitted at the time
of measurement: only this run's test additions in `ContactEdgeCaseTests.kt` (hash below).

The previous `tests.md` referred to commits that no longer exist after the history was rewritten. This report replaces
it and was measured on the current HEAD.

Requirements:
- `tasks/contacts-api.md` acceptance criteria and recorded assumptions
- SPEC.md §8.3 (contacts), §4.2 (API conventions), via the task
- docs/ARCHITECTURE.md "Coverage", docs/QUALITY.md, AGENTS.md

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/ContactEdgeCaseTests.kt` (3 tests added, nothing removed or changed)

Reviewed and run unchanged: `server/src/test/kotlin/ch/sonensei/cockpit/contacts/ContactWebTests.kt` (11 tests).

## Tests added or updated

Count: 3 added (contacts tests: 41 + 11 before, 44 + 11 = 55 now).

- `values of the wrong type are 400 naming the field, and a rejected PATCH changes nothing`: a string where a list is
  expected (`emails`), an object where a phone number is expected, an invalid UUID as `photoFileId` and a list as
  `displayName` are each a 400 Problem naming that field, and nothing is stored. A PATCH with one ill-typed field does
  not apply its valid fields either (ETag and title unchanged). This covers the controller's Jackson conversion
  error path, which was only checked for `birthday`.
- `imported cards without FN get the display name derived from N, ORG or EMAIL`: the derivation rule from the
  assumptions on the import path, and a card with empty FN and N is skipped with a reason naming `displayName`.
- `imported contacts are searchable, resolvable and announced as created`: an imported contact appears in the global
  search (name and note), the CONTACT resolver returns it with its organization, and exactly one `CREATED` change
  event is published. Before this, only the REST create path checked search, resolver and events.

## Requirement coverage

Command C1 = `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.*' --tests '*OpenApiContractTest' jacocoTestReport`
(Testcontainers Postgres): ContactEdgeCaseTests 44/44, ContactWebTests 11/11, OpenApiContractTest 1/1, 0 failures, 0 errors, 0 skipped.

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

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.*' --tests '*OpenApiContractTest' jacocoTestReport`

Result:

Pass: BUILD SUCCESSFUL in 57s. ContactEdgeCaseTests 44/44, ContactWebTests 11/11, OpenApiContractTest 1/1 (0 failures,
errors or skipped), read from fresh `server/build/test-results/test/*.xml` (cleared before the run). Before that, the
new tests passed in `./gradlew test --tests 'ch.sonensei.cockpit.contacts.ContactEdgeCaseTests'` (44/44), and
`./gradlew ktlintCheck` passed (BUILD SUCCESSFUL) with the new test code.

## Full test execution

Command:

`sh scripts/check.sh` (`.taskcheck`: server `./gradlew check` = ktlint + all server tests, plus web and android checks)

Result:

Fail. Run 2026-10-21T09:27:41+02:00 to 09:35:17+02:00 (log in `.tmp/check.log`), exit 1. The tfp completion gate tests
passed (20 tests, OK). Server `./gradlew check`: 3042 tests completed, 2 failed, BUILD FAILED in 7m 22s. The web and
android checks did not run because the script stops at the first failing part. Both failures are in the gtd module and
are caused by this task's CONTACT resolver (finding TW-2 below):
- `ch.sonensei.cockpit.gtd.convert.TaskToNoteWebTests."carries links over in their direction with their label"`
- `ch.sonensei.cockpit.gtd.convert.TaskToNoteRollbackTests."a failing link copy rolls back the note and the task deletion"`

They fail every time: `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.convert.*'` -> 26 tests, 2 failed
(BUILD FAILED in 31s). All contacts tests and OpenApiContractTest passed in the same full run.

## .taskcheck

Result:

Fail (`sh scripts/check.sh`, the command in `.taskcheck`): the two gtd convert failures above (TW-2). `tasks.py done`
will refuse the merged result until they are resolved.

## Bugs found

No defect was found inside the contacts module. All 55 contacts tests pass. The task does cause one integration
regression in another module's tests. It blocks `.taskcheck`:

### Adding the CONTACT resolver breaks gtd task-to-note tests that use CONTACT as "a type without a resolver"

Finding ID:

`TW-2` (`TW-1` was used by an earlier test-writer run, see `fixes.md`)

Test:

`ch.sonensei.cockpit.gtd.convert.TaskToNoteWebTests."carries links over in their direction with their label"` and
`ch.sonensei.cockpit.gtd.convert.TaskToNoteRollbackTests."a failing link copy rolls back the note and the task deletion"`

Production source:

`server/src/main/kotlin/ch/sonensei/cockpit/contacts/infra/ContactItems.kt:16-31` (`ContactItemResolver`, new in this task)
interacting with `server/src/main/kotlin/ch/sonensei/cockpit/gtd/convert/domain/TaskToNoteService.kt:56,61`
(`exists(ref) = resolvers.find(ref.type)?.exists(ref.id) ?: true`). Test fixtures:
`server/src/test/kotlin/ch/sonensei/cockpit/gtd/convert/TaskToNoteWebTests.kt:127` and
`server/src/test/kotlin/ch/sonensei/cockpit/gtd/convert/TaskToNoteRollbackTests.kt:51`.

Requirement:

Task AC1 adds "resolver CONTACT". The project check (`.taskcheck`) must pass on the merged result before `done` (AGENTS.md).

Expected behavior:

`sh scripts/check.sh` passes with the contacts module merged into main.

Actual behavior:

Both gtd tests link the task to `ItemRef(ItemType.CONTACT, UUID.randomUUID())` and treat it as an item of a type without
a resolver (the web test calls it `unknownType`), which the converter counts as existing. Now that this task registers
a CONTACT resolver, a random contact id resolves as not existing. So the converter skips that link: the web test misses
the expected `(note, contact, MANUAL)` link, and in the rollback test `links.create` is never called, so the mocked
`IllegalStateException` is never thrown ("Expected exception java.lang.IllegalStateException but no exception was thrown").
Both modules behave as documented. The gtd fixtures depend on CONTACT having no resolver, which this task changes.

Severity:

High (blocks `.taskcheck` and therefore `tasks.py done`; no user-facing defect).

Fix required:

`Yes — the parent worker / fixer must resolve this before completion.` The likely fix is in the gtd convert test
fixtures, not in production code: use an item type that still has no resolver, or link a real contact created through
`ContactService` (the rollback test needs a link target that exists). These files are outside this task's `touches`
(`gtd` tests). The parent decides whether to change them here or coordinate with the owner of the gtd convert code.
The test-writer left them unchanged.

## Coverage

Status: verified (whole-file, fresh run on a stable snapshot).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. Every Kotlin file in scope is new in this task (the diff only adds
lines), so whole-file coverage equals changed-line coverage for them.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: contacts-api; base
  `cbaf064792db674cdcf8c01cb37179bd266e5275` (`git merge-base HEAD origin/main`); HEAD
  `25efabf0f6b467623922be20dcbd8c9b1a01c0ea`; task ownership from the parent (`git log origin/main..HEAD`, the `contacts:`
  commits listed above), checked against the task's `touches` and `git diff --name-only cbaf0647..HEAD -- server/src/main web/src android/app/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 25efabf0 plus the uncommitted test change. sha256:
  ContactEdgeCaseTests.kt `6fcf105c2988c73e404a95110146c06a07231ceec9a447367966b7831778f3c5` (uncommitted, this run),
  ContactWebTests.kt `212e40ac7be8a3d35d23a681b63acbda7c0d4610e5c6baf16cc590ec2a01cb31`,
  ContactController.kt `ce06c9ea324d8a37...`, ContactDtos.kt `54da9ebfc97dd984...`, Contact.kt `53fc6e3306c32e59...`,
  ContactService.kt `e7740bee457e3e7a...`, VCardService.kt `691627a7e8829213...`, ContactItems.kt `35a451276a602e12...`,
  ContactRepository.kt `6b2132760871fdfb...` (production files identical to HEAD; `git status` showed only the test file modified).
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/contacts/{api/ContactController.kt, api/ContactDtos.kt,
  domain/Contact.kt, domain/ContactService.kt, domain/VCardService.kt, infra/ContactItems.kt, infra/ContactRepository.kt}`.
  Excluded: the migrations `V202610211000__contacts_model.sql` and `V202610211010__contacts_email_search.sql` (SQL, JaCoCo
  cannot instrument them; they run in every Testcontainers test and `contacts_email_matches` is exercised by the q tests);
  `api/openapi.yaml` and `web/src/app/api/generated/**` (generated; the contract is checked by OpenApiContractTest);
  `reviews/**` (docs); test files. No renames or deletions in the task diff. `ContactRepository.kt` is a Spring Data
  interface without executable bytecode, so JaCoCo has no counters for it (N/A, not missing). JaCoCo also lists
  `contacts/domain/Extensions.kt` without counters; no such source file exists at HEAD or in the task diff (a stale class
  in the build directory), so it is out of scope.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (no
  `toolVersion` set, plugin default of Gradle 9.8.1); `./gradlew test --tests 'ch.sonensei.cockpit.contacts.*' --tests '*OpenApiContractTest' jacocoTestReport`
  in `server/`; selection: the contacts test package (55 tests) and OpenApiContractTest (1 test).
- Run start/completion time and exit status: 2026-10-21T09:26:20+02:00 to 09:27:20+02:00 (report written 09:27:20);
  BUILD SUCCESSFUL.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`,
  sha256 `2f78bd489ca07af1d9c30b36b45c393b29e9c5826fae4f66721cccbd17630850`, JaCoCo session `dell26-c4de810d`
  (copy in `.tmp/contacts-jacoco-c4de810d.xml`, because the later full check overwrites the build output).
- Evidence outputs are fresh and inputs stayed unchanged: `server/build/jacoco`, `server/build/reports/jacoco` and
  `server/build/test-results/test` were deleted before the run; the report mtime and test-result timestamps lie inside the
  run window; the source/test hashes were identical before and after the run.
- Final run after latest test additions and production fixes: yes. No test or production code changed after this run.

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

No coverage target is defined (ARCHITECTURE.md "Coverage": "It is not a gate"). The three new tests did not change
the counters: they cover behavior across paths whose lines were already executed by other tests (import combined with
search, resolver and events; derivation on the import path; the conversion error path for more fields and on PATCH).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `domain/Contact.kt:72,83-104` | Unused enum constants (`GOOGLE`/`MICROSOFT`/`CARDDAV` are for future sync tasks), generated `data class` members (`copy`, `componentN`, `equals`/`hashCode` branches) of ContactEmail/Phone/Address | Non-material: generated code; sync sources belong to other tasks (contacts-google, contacts-microsoft) |
| `domain/Contact.kt:111,113,118,120,125,127` | Null branches in the jsonb `AttributeConverter`s (`attribute.orEmpty()`, `dbData?.let`) | Defensive: the columns are `not null` and Hibernate never passes null for them |
| `api/ContactDtos.kt:57-58,111-121` | `checkNotNull(createdAt/updatedAt)` failure branches; generated `componentN`/`copy` of `ContactRequest` | Defensive or generated; persisted entities always have timestamps |
| `api/ContactController.kt:153` | Exception path the compiler generates for `inputStream.use {}` | Non-material (generated finally/rethrow path) |
| `api/ContactController.kt:193` | `?: "body"` fallback when a JacksonException has no property path | The 400 responses for wrong-typed fields and non-object bodies are tested; this variant (a conversion error without a property path) was not produced by any request tried, because the body is already a JSON object whose fields carry their names; it returns the same 400, only with field `body` |
| `domain/ContactService.kt:37,211` | Default argument of `ContactChanges`; `items.find(ItemType.FILE)` returning null (FILE resolver not registered) | Defensive: the files module always registers the resolver in the application context |
| `domain/VCardService.kt:65` | `checkNotNull` on the transaction result | Defensive: `importCard` never returns null |
| `domain/VCardService.kt:94,98,140,145,206` | Null-safe branches over ez-vcard properties (null value/text/uri, photo with empty data, null content type, null type values) | Main cases covered by the 2.1/4.0/empty-property/no-type-photo tests; the remaining branches are null shapes ez-vcard does not produce from parsed text |
| `infra/ContactItems.kt:47` | `checkNotNull(deletedAt)` failure in the Trash listing | Defensive: the Trash query only returns deleted rows |

No meaningful gap in task behavior remains.

## Unable to test

- Nothing in this task's acceptance criteria. Embedding photos in the export is out of scope by a recorded assumption
  (FilesService exposes no blob read to other modules), so it was not tested. Backup/restore of contacts belongs to the
  separate task `backup-contrib-contacts` and was not tested here.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion.

Three tests were added. All 55 contacts tests and OpenApiContractTest pass against Testcontainers Postgres (C1), and
task-scoped coverage is high with only defensive or generated code uncovered. No defect was found inside the contacts
module. `.taskcheck` fails, however: two gtd task-to-note tests assume CONTACT has no item resolver, and this task adds one
(TW-2, High). The fix belongs in those gtd test fixtures. The parent must resolve it before `tasks.py done`.
