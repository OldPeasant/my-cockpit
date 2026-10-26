# Test Report

Date: 2026-10-26

Task: carddav-api - CardDAV accounts: REST API, web settings UI and backup

Scope: task carddav-api, immutable base 728a43f4baff41729fbf439a0aa1aadc6ee3a17a, measured HEAD 4a56280f381ac2bd862c2753c6243880954e6476 (branch main). Task commits on the current branch (rebased; the coordinator's original hashes 0c47dc0f4, 61a4d3518, 55bf0b3c3, caa413abf are now c43050ba7, 95965b672, 5f5fa0f7d, 6c7e39a3f), plus 4a56280f3 (TW-1 fix, CR-7 translations, the specs from this report). All other commits between base and HEAD only touch `tasks/` (heartbeat/assume). Production files: `git diff --name-status 728a43f4b..HEAD -- server/src/main web/src`, checked against the task's `touches` and acceptance criteria.

Requirements:
- tasks/carddav-api.md acceptance criteria and the four recorded assumptions
- AGENTS.md project notes (Problem Details, password handling, backup round trip SPEC.md §14)
- docs/ARCHITECTURE.md "Coverage"
- reviews/carddav-api/review.md (CR-1, CR-3, CR-4 named the missing tests) and reviews/carddav-api/fixes.md

## Test files changed

Already committed by the parent worker before this pass (inspected against the criteria, kept as they are):
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/carddav/CardDavAccountWebTests.kt` (19 tests)
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/backup/ContactsBackupContributorTests.kt` (7 CardDAV tests)
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/backup/ContactsBackupFixture.kt` (round-trip fixture: 2 accounts, a link, a tombstone)
- `web/src/app/features/contacts/carddav-accounts.spec.ts` (15 tests)
- `web/src/app/features/contacts/contact-form.spec.ts` (3 tests)
- `web/src/app/features/contacts/contact-detail.spec.ts` (4 tests in "a new contact in a CardDAV account")

Changed in this pass (committed by the parent in 4a56280f3):
- `web/src/app/features/contacts/contacts-page.spec.ts`
- `web/src/app/features/contacts/contact-detail.spec.ts`

## Tests added or updated

Count: 3 added in this pass (plus an optional `carddav` parameter in the `open()` helper of contacts-page.spec.ts; existing callers are unchanged)

- `ContactsPage > shows the CardDAV accounts panel with each account and its sync state`: the panel is on the Contacts page, shows the account's ERROR state with lastError and offers Add, separate from the OAuth sync list.
- `ContactDetail > a new contact in a CardDAV account > ignores an older accounts list that answers after the current one`
- `ContactDetail > a new contact in a CardDAV account > ignores an older accounts request that fails after the current one answered` (it failed at caa413abf, see TW-1; it passes after the fix in 4a56280f3)

No server tests were added. The committed server tests already cover every case listed in CR-1 and CR-3. The web specs already cover the cases in CR-4.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. List accounts in creation order, empty list | CardDavAccountWebTests: `GET lists the accounts in the order they were added, without passwords`, `GET lists no accounts as an empty list`, `GET of one account returns it, an unknown one is 404 and a malformed id 400` | server command S (below) | Pass |
| 1. Add: 201 + Location, validation 400 Problem Details | `POST adds an account with Location, never returns the password and stores it encrypted`, `POST trims the name and URL, an empty password and a blank user name are stored as none`, `POST without user name and password adds an account without them`, `POST rejects a missing or blank name, a missing or invalid URL and an unreadable body` | S | Pass |
| 1. Remove (contacts stay), 404 for unknown | `DELETE removes the account and its links but keeps its contacts, then it is 404`, `DELETE of an unknown account is 404 and removes nothing` | S | Pass |
| 1. Sync now, status with lastError (NEVER/OK/ERROR) | `the sync state is OK after a successful run and ERROR with the error after a failed one`, `sync of an unreachable server answers 200 with state ERROR and the error, without the password`, `sync of an unknown account is 404` | S | Pass |
| 1. Password write-only | `no response of the account API contains a password`, `the add request's toString redacts the password`, plus the add test (stored encrypted) | S | Pass |
| 1. Create a contact in an account (assumption: 201 + ETag, 409 on discovery failure, 400 before discovery) | `POST contacts creates a CARDDAV contact with ETag and Location and links it to a new card`, `POST contacts rejects unknown fields and invalid values and creates nothing`, `POST contacts checks the fields before looking up the address book, so invalid input is 400, not 409`, `POST contacts to an unknown account is 404`, `POST contacts answers 409 with the CardDAV error when the address book cannot be found, creating nothing` | S | Pass |
| 1. OpenAPI regenerated | Generated client used by every web spec; `npm run api:check` is part of `npm run check` / `.taskcheck` | not run by test-writer | Not run: `.taskcheck` is run by the parent worker |
| 2. Panel on the Contacts page, shows sync state | contacts-page.spec `shows the CardDAV accounts panel with each account and its sync state`; carddav-accounts.spec `shows a loading state until the accounts arrive`, `lists the accounts with where they sync and their state NEVER, OK and ERROR`, `says so when there is no account`, `shows a load error and loads again with Try again` | web command W1 | Pass |
| 2. Add account (filled fields only, first sync at once, validation and server 400 messages, busy, cancel) | carddav-accounts.spec `adds an account, sends only the filled fields and syncs it at once`, `sends the user name and password when given`, `asks for a name and URL before sending anything`, `shows the field errors of a 400 with readable field names and keeps the form`, `disables the form while it is sent and Cancel closes it without sending` | W1 | Pass |
| 2. Sync now and remove account | carddav-accounts.spec `syncs an account now, shows Syncing… meanwhile and the new state afterwards`, `shows a failing server reported by sync now as the account state`, `shows an error response of sync now on the account and re-enables it`, `removes an account only after confirmation; Cancel keeps it`, `keeps the account and shows the error when removing fails` | W1 | Pass |
| 2. New contact can target a CardDAV account | contact-form.spec `is hidden without CardDAV accounts`, `is hidden when editing an existing contact, also with accounts`, `offers here only and every account for a new contact and updates target`; contact-detail.spec `offers the CardDAV accounts and POSTs to the chosen account`, `creates the contact here only when My Cockpit only stays chosen`, `hides the choice when the accounts cannot be loaded and creates the contact here`, `shows why the account could not take the contact (409) and keeps the form`, `ignores an older accounts list that answers after the current one`, `ignores an older accounts request that fails after the current one answered` | W1 | Pass |
| 3. Backup: account config without password; password only in encrypted secrets | ContactsBackupContributorTests `CardDAV accounts and links are exported without passwords, sync tokens or sync status`, `with a passphrase the CardDAV password is only in the encrypted secrets, neither in plain text nor as stored` | S | Pass |
| 3. Restore: password only with passphrase, links incl. tombstones, warnings | `import restores CardDAV accounts and links, the password only with the passphrase, and no sync state`, `a backup without passphrase restores the accounts without password and logs no secret`, `restore warnings tell that CardDAV accounts need their password, singular and plural`, `without CardDAV accounts there are no CardDAV files and no restore warnings`, `an account without links writes an empty links file and restores without links` | S | Pass |
| 3. Round-trip test | BackupRoundTripTest `every contributor's data survives export, wipe and import unchanged` with ContactsBackupFixture (2 accounts, with and without password, a link and a tombstone) | S | Pass |

Server command S (run by the coordinator, because Gradle is denied to sub-agents): in `server/`, `./gradlew test --tests '*CardDav*' --tests '*ContactsBackupContributorTests' --tests '*BackupRoundTrip*' jacocoTestReport`

Web command W1 (in `web/`): `npx ng test --watch=false --include='src/app/features/contacts/**/*.spec.ts'`

## Focused test execution

Command:

`npx ng test --watch=false --include='src/app/features/contacts/**/*.spec.ts'` (web, run at HEAD 4a56280f3 at about 14:00:38 CET)

Result:

Pass: 7 files, 127 tests

Command:

`./gradlew test --tests '*CardDav*' --tests '*ContactsBackupContributorTests' --tests '*BackupRoundTrip*' jacocoTestReport` (server, run by the coordinator 13:57:48-13:58:49 CET at caa413abf; server code is identical at 4a56280f3: `git diff --stat caa413abf HEAD -- server` is empty)

Result:

Pass: BUILD SUCCESSFUL, 86 tests, 0 failures, 0 errors, 0 skipped (BackupRoundTripTest 1, ContactsBackupContributorTests 17, CardDavAccountWebTests 19, CardDavContactsSyncUnitTests 17, CardDavAccountServiceTests 5, CardDavClientTests 17, CardDavMappingTests 10)

## Full test execution

Command:

`npm run coverage` (web, full unit suite with coverage, HEAD 4a56280f3, 14:00:57-14:01:57 CET)

Result:

Pass: 178 files, 3757 tests

Command:

`npm run lint` (web, run before the fix commit on the same spec contents)

Result:

Pass

Server full suite (`./gradlew check`): Not run by the test-writer (Gradle denied). fixes.md reports the contacts and backup packages passing (510 tests) on the same server code.

## .taskcheck

Result:

Not run: `sh scripts/check.sh` runs Gradle, which is denied in this sub-agent. The parent worker runs it.

## Bugs found

### A late failed CardDAV accounts request hid the "Save to" choice of a new contact

Finding ID:

`TW-1`

Test:

`ContactDetail > a new contact in a CardDAV account > ignores an older accounts request that fails after the current one answered`

Production source:

`web/src/app/features/contacts/contact-detail.ts:262` (`loadCardDavAccounts`, catch branch, at caa413abf)

Requirement:

Criterion 2: a new contact can target a CardDAV account.

Expected behavior:

An accounts request from an earlier visit to the new contact page that fails after the current request answered is ignored. The choice of accounts stays.

Actual behavior:

The catch branch cleared the list without checking the load sequence (`this.carddavAccounts.set([])`), so the "Save to" choice disappeared. A late success was already ignored.

Severity:

Low

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.` Status: fixed in 4a56280f3 (`if (seq === this.loadSeq) this.carddavAccounts.set([])`). The test passes at HEAD 4a56280f3.

## Coverage

Status: verified for web (fresh run at the final HEAD). Verified for server from the coordinator's fresh run on identical server code, limited to the selected tests (see the note below the server table).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: server measured from the JaCoCo per-line counters (`ci` greater than 0), intersected with the added lines of `git diff -U0 728a43f4b..HEAD -- server/src/main`. Only lines JaCoCo reports as executable count. Result: 174/175 changed executable lines (99.43%). The one missed line is `ContactController.kt:184`, the one-line delegation `parse(body) = ContactRequest.parse(body, jsonMapper)`. ContactWebTests exercises it, but ContactWebTests was not in the selection. Web changed-line coverage: not measured (the v8 JSON report has no line counters, and lines are not rebuilt from statements here).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: carddav-api, base 728a43f4baff41729fbf439a0aa1aadc6ee3a17a, HEAD 4a56280f381ac2bd862c2753c6243880954e6476. Task commits c43050ba7, 95965b672, 5f5fa0f7d, 6c7e39a3f, 4a56280f3. The coordinator supplied the list (pre-rebase hashes) and it was checked with `git log`. Other commits in the range touch only `tasks/`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 4a56280f3 with a clean tree for all source and test inputs. `git status` showed only `reviews/carddav-api/review.md` modified, which is not a test input and was not changed by the test-writer.
- Exact included files: server `contacts/api/ContactController.kt`, `contacts/api/ContactDtos.kt`, `contacts/backup/CardDavBackup.kt`, `contacts/backup/ContactsBackupContributor.kt`, `contacts/carddav/CardDavAccountController.kt`, `contacts/carddav/CardDavAccountService.kt`, `contacts/carddav/CardDavContactsSync.kt`, `contacts/domain/ContactService.kt`. Web `features/contacts/carddav-accounts.ts`, `contact-detail.ts`, `contact-form.ts`, `contacts-page.ts`, `contacts.service.ts`. Excluded: `web/src/app/api/generated/**` and `api/openapi.yaml` (generated); `*.html`, `*.scss`, `web/src/locale/*.xlf` (templates, styles and translations, not instrumented as source); `*.spec.ts` and server tests. No renamed or deleted production files.
- Provider/version, command, working directory, and test selection: web is Vitest v5.0.5 with v8 coverage, `npm run coverage` in `web/`, full suite. Server is JaCoCo (Gradle `jacoco` plugin), command S in `server/`, selection `*CardDav*`, `*ContactsBackupContributorTests`, `*BackupRoundTrip*`.
- Run start/completion time and exit status: web 14:00:57-14:01:57 CET, exit 0. Server 13:57:48-13:58:49 CET, BUILD SUCCESSFUL (coordinator).
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` (sha256 prefix e70354bfe317f707), `web/coverage/web/coverage-final.json` (df0446a06d81968e), both written 14:01:57. `server/build/reports/jacoco/test/jacocoTestReport.xml` (sha256 prefix 6d2726db73cf2121), written 13:58:49, matching the coordinator's run.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The web reports were rewritten by this run, and the tree was unchanged before and after it. The server report comes from the coordinator's run, and the server sources are byte-identical between caa413abf and HEAD.
- Final run after latest test additions and production fixes: yes for web (HEAD 4a56280f3). For the server, the fix commit changed no server code.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

Web (v8):

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/contacts/carddav-accounts.ts` | 81/84 (96.4%) | 35/41 (85.4%) | 19/19 (100%) | 71/72 (98.6%) |
| `web/src/app/features/contacts/contact-detail.ts` | 154/156 (98.7%) | 76/82 (92.7%) | 23/23 (100%) | 132/132 (100%) |
| `web/src/app/features/contacts/contact-form.ts` | 47/47 (100%) | 22/23 (95.7%) | 17/17 (100%) | 36/36 (100%) |
| `web/src/app/features/contacts/contacts-page.ts` | 111/112 (99.1%) | 50/53 (94.3%) | 20/20 (100%) | 94/94 (100%) |
| `web/src/app/features/contacts/contacts.service.ts` | 27/27 (100%) | 12/12 (100%) | 18/18 (100%) | 22/22 (100%) |
| Web total (summed counters, 5 files, no file missing) | 420/426 (98.6%) | 195/211 (92.4%) | 97/97 (100%) | 355/356 (99.7%) |

Server (JaCoCo; Statements = instructions, Functions = methods):

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/.../contacts/carddav/CardDavAccountController.kt` | 387/389 (99.5%) | 13/14 (92.9%) | 31/31 (100%) | 78/78 (100%) |
| `server/.../contacts/backup/CardDavBackup.kt` | 419/419 (100%) | 27/28 (96.4%) | 20/20 (100%) | 78/78 (100%) |
| `server/.../contacts/backup/ContactsBackupContributor.kt` | 705/710 (99.3%) | 12/12 (100%) | 37/37 (100%) | 122/122 (100%) |
| `server/.../contacts/carddav/CardDavAccountService.kt` | 173/173 (100%) | 18/18 (100%) | 6/6 (100%) | 17/17 (100%) |
| `server/.../contacts/carddav/CardDavContactsSync.kt` | 1803/2388 (75.5%) | 158/259 (61.0%) | 59/73 (80.8%) | 244/306 (79.7%) |
| `server/.../contacts/domain/ContactService.kt` | 1044/1541 (67.7%) | 73/114 (64.0%) | 32/42 (76.2%) | 122/161 (75.8%) |
| `server/.../contacts/api/ContactDtos.kt` | 639/959 (66.6%) | 13/22 (59.1%) | 31/68 (45.6%) | 94/127 (74.0%) |
| `server/.../contacts/api/ContactController.kt` | 27/269 (10.0%) | 0/8 (0.0%) | 3/12 (25.0%) | 7/36 (19.4%) |
| Server total (summed counters, 8 files, no file missing) | 5197/6848 (75.9%) | 314/475 (66.1%) | 219/289 (75.8%) | 762/925 (82.4%) |

Note on the server totals: the four files at the bottom are large files from before the task, with small task changes (1 to 13 lines each). Their uncovered code is old behavior that tests outside this selection exercise (ContactWebTests, ContactEdgeCaseTests and others in `contacts/`). So the whole-file numbers mostly reflect the selection. The task's own lines are 174/175 covered (see changed-line coverage). The two new files (controller and backup) are fully line-covered.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `ContactController.kt:184` | Delegation of contact body parsing to `ContactRequest.parse` (moved code) | Justified gap of the selection: ContactWebTests covers it and was not in the selected run. The same parsing is covered through `POST contacts rejects unknown fields and invalid values and creates nothing`. |
| `ContactDtos.kt:159` branch (`?: "body"`) | Field name fallback when Jackson's path has no property | Not material: defensive fallback for a body that fails without a property path. Moved code from before the task. |
| `CardDavAccountController.kt:156` branch (`e.message ?: ...`) | 409 detail when a CardDavException has no message | Not material: CardDavException always carries a message in the current code. |
| `CardDavBackup.kt:95` branch | Log line only when some restored account has no password | Both outcomes are asserted on data. The missed branch only decides whether to write an INFO log line. |
| `carddav-accounts.ts:71` | `host()` fallback for a URL that does not parse | Not material: the server rejects invalid URLs (`POST rejects ... a missing or invalid URL`). |
| `carddav-accounts.ts:80, 94` | Busy guards of sync now and remove | Not reachable through the UI: the buttons are disabled while busy, which `syncs an account now...` and `removes an account only after confirmation...` assert. |
| `carddav-accounts.ts:85, 100, 142` (`?? []` branch) | Accounts list still null during an action | Not reachable: the actions are only rendered once the list is loaded. |
| `contact-detail.ts:188, 247, 248, 287`; `contacts-page.ts:74, 116`; `contact-form.ts:38` | Code from before the task, or decorator artifacts | Outside the task's changes. |

## Unable to test

- `.taskcheck` and the server full suite: Gradle is denied to this sub-agent. The parent worker runs them.
- OpenAPI freshness (`npm run api:check`): part of `npm run check` / `.taskcheck`, not run here.
- Against a real CardDAV server: out of scope for unit tests. The server tests use a local fake server and an unreachable URL. No criterion names an end-to-end run.

## Summary

Tests added; a production defect (TW-1) was discovered and has since been fixed by the fixer in 4a56280f3. All validation run by the test-writer at the final HEAD passed. The remaining coverage gaps are justified above.
