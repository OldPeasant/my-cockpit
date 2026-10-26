# Test Report

Date: 2026-10-26

Task: mail-identities - Mail sending identities API and settings

Scope: `git diff 270652780c142c1a3cc5657639d69efd1e03c241..292446ec5f453ece9644de9ad08d043278b08966` (task commits dea927fe6, 89176b623, 3144450d2, 2f970eea5 as given by the parent worker; HEAD 292446ec5 is the merge of `origin/wip/mail-identities`), plus the uncommitted test edits below.

Requirements:
- `tasks/mail-identities.md` (acceptance criteria and the two recorded assumptions)
- SPEC.md §7.1 (identities), §4.2 (Problem Details, ETag/If-Match)
- `docs/ARCHITECTURE.md` "Coverage"; `reviews/mail-identities/review.md` CR-1..CR-4 and `fixes.md`
- Parent instructions: the sub-agent cannot run Gradle; the parent runs the server tests and coverage

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/mail/imap/MailIdentityWebTests.kt`
- `web/src/app/features/settings/mail/identities.spec.ts`

## Tests added or updated

Count: 10 (5 server, 5 web). No existing test was changed.

Server (`MailIdentityWebTests`):
- `changing an unknown identity of the account is 404 Problem Details`: PATCH of an unknown identity id in the account and in an unknown account.
- `a rejected change stores nothing and publishes no event`: a 409 PATCH (duplicate address plus a default change), a 400 PATCH (blank email, CR-1) and a 412 DELETE leave name, email, default flag and version unchanged, and publish no UPDATED/DELETED event.
- `a duplicate new default does not take the flag from the current default`: a 409 create with `isDefault: true` keeps the current default (flag and version).
- `removing the default identity leaves no default`.
- `the identity API requires login` (`@WithAnonymousUser`: GET/POST/PATCH/DELETE are 401, nothing is stored).

Web (`identities.spec.ts`):
- `reloads after a stale removal and removes at the current version on retry`: CR-3 for DELETE 412 (the existing tests covered PATCH 412 and DELETE 404).
- `reloads the list when the edited identity was removed elsewhere`: CR-3 for PATCH 404.
- `shows a load error with a retry when the reload after a stale change fails`.
- `does not reload the list after a duplicate address or another error`: a 409 PATCH sends no list request and keeps the typed value.
- `disables the removal confirmation and Edit while a removal is running`: CR-4 for a running DELETE; a second click sends nothing more.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| API list/add/change/remove (name, email, reply-to, signature override) | `create returns the identity with ETag and Location...`, `get returns one identity...`, `update changes the sent fields...`, `update sets a new reply-to...`, `create trims the name...`, `a new identity comes after the highest position...` | run by the parent: `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.mail.imap.MailIdentityWebTests' jacocoTestReport` | Pass (24 tests, 0 failures/errors/skipped; ktlint clean) |
| ETag/If-Match: 428 missing, 412 stale, nothing changed | `update and delete need a current If-Match`, `a stale If-Match changes nothing`, `a rejected change stores nothing and publishes no event` (new) | as above | Pass |
| Problem Details 400/404/409 | `invalid requests are 400 with field errors`, `a missing email or a name over 200 characters...`, `an address is used once per account...`, `unknown accounts and identities of another account are 404`, `an identity cannot be removed through another account`, `changing an unknown identity of the account is 404 Problem Details` (new), `a duplicate new default does not take the flag...` (new) | as above | Pass |
| isDefault moves the flag; previous default gets an UPDATED event | `isDefault moves the default flag...`, `a new default publishes the change of the previous default`, `making the current default the default again...`, `removing the default identity leaves no default` (new) | as above | Pass |
| CR-1 blank email on PATCH is 400 | `an update cannot empty the address of an identity`, `invalid requests are 400 with field errors` (whitespace case), `a rejected change stores nothing...` (new) | as above | Pass |
| API requires login | `the identity API requires login` (new) | as above | Pass |
| `api/openapi.yaml` and web client regenerated | n/a (generation check) | `npm run api:check` (part of `npm run check`, run by the fixer) | Not run by me |
| Settings → Mail lists identities (loading, empty, load error + retry) | `says that it loads, then lists...`, `says when there are no further identities`, `shows a load error with a retry`, `opens the sending identities of an account` (mail-settings.spec) | `cd web && npx ng test --watch=false --include 'src/app/features/settings/mail/**/*.spec.ts'` | Pass (6 files, 132 tests) |
| Settings → Mail adds/edits/removes identities | `adds an identity...`, `adds a default identity...`, `edits an identity at its version...`, `makes an identity the default...`, `removes an identity after confirming`, `does not send an invalid identity`, service spec `lists, adds, changes and removes identities...` | as above | Pass |
| 409 duplicate shown, form kept, no reload | `says that an address is already used...`, `does not reload the list after a duplicate address...` (new) | as above | Pass |
| CR-3 refresh after 412/404 | `reports a stale version as changed elsewhere, reloads, and retries...`, `reloads the list when the identity to remove was already removed elsewhere`, `reloads after a stale removal...` (new), `reloads the list when the edited identity was removed elsewhere` (new), `shows a load error with a retry when the reload after a stale change fails` (new) | as above | Pass |
| CR-4 buttons disabled while saving | `disables Add, Edit and Remove while a change is saving`, `sends a change only once while it is saving`, `disables the removal confirmation and Edit while a removal is running` (new) | as above | Pass |
| CR-2 hint text | `says that new mail starts with the default or the first identity` | as above | Pass |
| `cd web && npm run check` passes | n/a | not run by me (lint of the changed spec: `npx eslint --max-warnings=0` + `npx prettier --check` -> clean; full Vitest suite run with coverage below -> 175 files, 3665 tests pass) | Not run: parent runs `npm run check` / `.taskcheck` |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include 'src/app/features/settings/mail/**/*.spec.ts'` (plain `npx vitest run` does not work here: the Angular builder sets up the test environment)

Result:

Pass (6 files, 132 tests; `identities.spec.ts` 25 tests including the 5 new ones, checked with `--reporters=verbose`).

Server (run by the parent worker; sub-agents cannot run Gradle here): `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.mail.imap.MailIdentityWebTests' jacocoTestReport` -> BUILD SUCCESSFUL, `MailIdentityWebTests` 24 tests (19 existing + 5 new), 0 failures/errors/skipped, ktlint clean. The new tests are committed in ef0da81404628a73b7aca7e8916109fae8c335e9.

## Full test execution

Command:

`cd web && npx ng test --watch=false --coverage --coverage-reporters=text-summary --coverage-reporters=json-summary --coverage-reporters=json`

Result:

Pass (web: 175 files, 3665 tests).

Server full suite (run by the parent): `cd server && ./gradlew check` -> BUILD SUCCESSFUL, 4660 tests. This run was before the 5 new server tests were added; those 5 were then run in the focused run above (pass).

## .taskcheck

Result:

Not run (it runs the server Gradle build, which sub-agents cannot run; the parent runs it).

## Bugs found

None.

## Coverage

Status: verified. Web measured by the test-writer. Server measured by the parent worker (sub-agents cannot run Gradle) in its focused `MailIdentityWebTests` run, which includes the new tests; the test-writer read the counters from the JaCoCo XML/HTML it produced.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `mail-identities`; base 270652780c142c1a3cc5657639d69efd1e03c241; HEAD 292446ec5f453ece9644de9ad08d043278b08966; commits and scope from the parent worker, checked against `git diff --stat 270652780..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 292446ec5 plus uncommitted `identities.spec.ts` sha256 8b4a80bdbfb3b1ca094e64c73bc58b2888900f61394b4ea7478c19c0108af094 (same hash before and after the run) and `MailIdentityWebTests.kt` sha256 a70be43f8a86f088e8c60391bfa76a6477ea0970711f9ac8735a950c8eb4560f. Both test files were committed by the parent in ef0da81404628a73b7aca7e8916109fae8c335e9 with the same hashes, so ef0da8140 is the tested state. `reviews/mail-identities/review.md` was also modified in the working tree (not by the test-writer, not a source input).
- Exact included files (web): `web/src/app/features/settings/mail/identities.ts`, `mail-accounts.service.ts`, `mail-settings.ts`, `testing.ts`, `web/src/app/api/generated/fn/mail-identities/{create,delete,get,list,update}-mail-identity.ts`. Excluded: `identities.html`/`.scss`, `mail-settings.html` (templates/styles, exercised through the component tests), generated `functions.ts`, `index.ts`, `models.ts` and `models/*` (re-exports and type-only interfaces, no executable code), `api/openapi.yaml` (spec). Server: `server/src/main/kotlin/ch/sonensei/cockpit/mail/imap/MailIdentityController.kt`, `MailIdentityService.kt` (both measured). No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest + `@vitest/coverage-v8` 5.0.5; `npx ng test --watch=false --coverage --coverage-reporters=text-summary --coverage-reporters=json-summary --coverage-reporters=json` (the `npm run coverage` reporters without lcov) in `web/`; all specs. Server: JaCoCo (Gradle `jacoco` plugin); `./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.mail.imap.MailIdentityWebTests' jacocoTestReport` in `server/`, run by the parent; only `MailIdentityWebTests` ran, so the server numbers are what that class alone covers.
- Run start/completion time and exit status: started 2026-10-26 06:52:51 +0100, report written 06:53:41 +0100; all tests passed. Server: `jacocoTestReport.xml` written 2026-10-26 06:57:02 +0100 (after the server test file was written, hash a70be43f... above); BUILD SUCCESSFUL.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` sha256 a5e6c0e7c928a50464e998f1684acbdaf9abde9aeb41c2ed3ec360eb39218fba; `web/coverage/web/coverage-final.json` sha256 51d6d04417f3fcb177e450babd627a270a08d8e0d645b5069db8ea6f5772d4e9; `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256 b9512d8f7fadc7d8c9fda58af507a7c5b1d6448aabb3f2942e0cfa57842b79e2 (with `html/ch.sonensei.cockpit.mail.imap/MailIdentity*.kt.html`).
- Evidence outputs are fresh and inputs stayed unchanged: yes (Vitest rewrites `coverage/`; spec hash identical before/after).
- Final run after latest test additions and production fixes: yes for both parts (the CR-1..CR-4 fixes and all 10 new tests are included).

Values below use covered/total (percentage). JaCoCo has no separate statement counter; the server "Statements" column shows JaCoCo INSTRUCTION counters (bytecode instructions), and "Functions" shows METHOD.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/settings/mail/identities.ts` | 79/80 (98.75%) | 42/44 (95.45%) | 19/19 (100%) | 73/73 (100%) |
| `web/src/app/features/settings/mail/mail-accounts.service.ts` | 25/25 (100%) | 9/9 (100%) | 16/16 (100%) | 22/22 (100%) |
| `web/src/app/features/settings/mail/mail-settings.ts` | 149/151 (98.68%) | 65/74 (87.84%) | 46/46 (100%) | 127/127 (100%) |
| `web/src/app/features/settings/mail/testing.ts` | 5/5 (100%) | 3/3 (100%) | 3/3 (100%) | 5/5 (100%) |
| `web/src/app/api/generated/fn/mail-identities/create-mail-identity.ts` | 8/8 (100%) | 1/2 (50%) | 3/3 (100%) | 8/8 (100%) |
| `web/src/app/api/generated/fn/mail-identities/delete-mail-identity.ts` | 9/9 (100%) | 1/2 (50%) | 3/3 (100%) | 9/9 (100%) |
| `web/src/app/api/generated/fn/mail-identities/get-mail-identity.ts` | 1/8 (12.5%) | 0/2 (0%) | 0/3 (0%) | 1/8 (12.5%) |
| `web/src/app/api/generated/fn/mail-identities/list-mail-identities.ts` | 7/7 (100%) | 1/2 (50%) | 3/3 (100%) | 7/7 (100%) |
| `web/src/app/api/generated/fn/mail-identities/update-mail-identity.ts` | 10/10 (100%) | 1/2 (50%) | 3/3 (100%) | 10/10 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/imap/MailIdentityController.kt` | instructions 367/415 (88.43%) | 10/12 (83.33%) | 27/37 (72.97%) | 90/92 (97.83%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/imap/MailIdentityService.kt` | instructions 617/673 (91.68%) | 69/70 (98.57%) | 24/26 (92.31%) | 72/74 (97.30%) |

Web aggregate (summed counters over the 9 measured web files): statements 293/303 (96.70%), branches 123/140 (87.86%), functions 96/99 (96.97%), lines 262/269 (97.40%).
Server aggregate (summed JaCoCo counters over the 2 server files): instructions 984/1088 (90.44%), branches 79/82 (96.34%), methods 51/63 (80.95%), lines 162/166 (97.59%).
The web and server counters are not summed together: the providers count different units. No files in scope are unmeasured.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `identities.ts:191` (`find()` throws) | Defensive: the template only offers identities from the current list | Left uncovered: not reachable from the UI |
| `identities.ts:36` branch 0 | Class/decorator branch emitted by the compiler, no behavior | Left uncovered |
| `mail-settings.ts:79,163,196,234,294,326,340,359,363` | Pre-existing account code; the task only added the `identities` panel, which is covered (`opens the sending identities of an account`) | Out of the task's behavior |
| generated `*-mail-identity.ts` branch `params ?? {}` / context fallback | Generated client glue | Non-material |
| generated `get-mail-identity.ts` | The UI does not call GET of one identity (it reloads the list) | Non-material; the server endpoint is covered by `get returns one identity with its ETag` |
| `MailIdentityService.kt:25`, `:34` (closing lines of the `NewMailIdentity` / `MailIdentityChanges` data classes) and partly covered `:19-33` | Kotlin-generated data-class members (`copy`, `componentN`, `equals`/`hashCode`/`toString`, default-argument constructors) that the service never calls; these are the 2 missed lines and 2 missed methods | Non-material: no behavior of the task |
| `MailIdentityService.kt:78` (1 of 8 branches) | `existing.maxOfOrNull { it.position } ?: -1`: the missed branch is inside the inlined `maxOfOrNull` loop; both the empty-list case (`-1`, first identity at position 0) and the non-empty case are tested (`create returns the identity...`, `a new identity comes after the highest position, also after a removal`) | Non-material: inlined stdlib branch |
| `MailIdentityController.kt:209`, `:213` (`CreateMailIdentityRequest.name` / `.email` getters) | Kotlin property getters; validation reads the fields and `toCommand()` reads the backing fields, so the getters never run. These are the 2 missed lines; with the other generated getters/defaults they make up the 10 missed methods | Non-material |
| `MailIdentityController.kt:299`, `:300` (1 of 2 branches each) | `checkNotNull(createdAt/updatedAt)`: the failing branch would need an entity read before JPA auditing sets its timestamps | Defensive, not reachable through the API; left uncovered |

## Unable to test

- Nothing in the task's behavior is untested. Sub-agents cannot run Gradle here, so the parent worker ran the server tests and JaCoCo (results above). No follow-up needed.
- `npm run check` and `.taskcheck`: not run by the test-writer; the parent runs them as part of the task's final validation.

## Summary

Tests added and all validation passed. Web: focused run and the full Vitest suite. Server: the focused `MailIdentityWebTests` run with JaCoCo, run by the parent. The remaining coverage gaps are generated or defensive code. No production defects found.
