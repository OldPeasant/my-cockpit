# Test Report

Date: 2026-10-21

Task: web-mail-accounts: Settings: mail accounts

Scope: base 3380f4bd2d7a247317bae24cd0564c0345591d7d .. HEAD 1ffd4cedcf090269c9686410d43ca710260adfd1 (branch main). The parent named the task commits: aef6050b (implementation), 1ffd4ced (specs), 7faec2bd (tasks file only). Production scope: `web/src/app/features/settings/mail/` plus `web/src/app/features/settings/settings.routes.ts`. The only other file in the range, `calendars/calendars-settings.spec.ts`, got a Prettier-only reformat of a test file (no behavior). Uncommitted inputs: the 4 spec files this test writer changed or added (hashes below). No production file was modified.

Requirements:
- tasks/web-mail-accounts.md acceptance criteria and its 3 recorded ASSUMPTIONs (primary identity only; Google/Microsoft OAuth connect with purpose=MAIL via Settings -> Integrations; test connection runs on the stored account, so the add form saves, then tests and detects folders)
- SPEC.md §7.1 (as referenced by the task), §4.2 (Problem Details, If-Match)
- docs/ARCHITECTURE.md "Coverage"; AGENTS.md project notes
- Existing test conventions (Vitest + TestBed + HttpTestingController, `testing.ts` fakes, `problem()` helper)

## Test files changed

- `web/src/app/features/settings/mail/account-editor.spec.ts` (new)
- `web/src/app/features/settings/mail/folder-roles.spec.ts` (new)
- `web/src/app/features/settings/mail/server-fields.spec.ts` (new)
- `web/src/app/features/settings/mail/mail-settings.spec.ts` (extended; no existing test changed)

## Tests added or updated

Count: 66 Vitest test cases (focused run went from 31 to 97 tests; `it.each` rows counted individually).

- `account-editor.spec.ts` (19): prefilled identity/login/servers/sync window, password never prefilled; new password and Markdown signature sent as typed; trimmed identity/login and blank name falling back to the address; invalid addresses (empty, no `@`, a space); username required for IMAP/SMTP; host required and port range; sync window 0/601/empty refused and 1/600 accepted; Google account without login/servers; field errors from a 400; 412 "changed elsewhere"; double submit sends one PATCH; Cancel sends nothing.
- `folder-roles.spec.ts` (10): loading state; load error with retry; aria-label per folder and the manual role / "No role" / "Automatic" shown in the select; no "No folder for" line when all 6 roles are set; `Automatic` sends `{automatic: true}` and updates in place without reloading; `No role` sends `{}`; the select is disabled while its save runs; a failed role change shows the error and reloads the server state; a failed detection shows the error and enables the button again; double "Detect folders" sends one sync.
- `server-fields.spec.ts` (13): default security/port per protocol; the usual port moves along for each security (IMAP STARTTLS/None -> 143, SMTP SSL/TLS -> 465, None -> 25) and back; an unusual stored port (2525) and a typed port (10993) stay; port boundaries 0/1/65535/65536/empty; host required and sent trimmed.
- `mail-settings.spec.ts` (24): failed removal keeps the account and shows the error; accounts still listed when providers/connections fail (and Google then shows "not set up"); one panel at a time, toggle closed, Cancel in editor and removal question; a second notification change uses the version from the first answer (If-Match "1"); each account tested on its own; "Failed" for a protocol without a message; a re-test replaces the result; blank name/signature sent as unset, address trimmed; notification choice sent; malformed address and port 0 refused without a request; double "Add and test" sends one POST; folders detected when only SMTP fails; the new account kept when the test request fails; folder detection failure after add; a refused add keeps what was typed; OAuth add when the connection shared no address (required error, then the typed address is sent); a typed address wins over the connection address; the first working connection is preselected and a RECONNECT_REQUIRED one is labelled; no working connection -> "Choose the account to log in with."; only the chosen provider's connections, and "Connect another Microsoft account" -> `/api/v1/oauth/microsoft/start?purpose=MAIL`; Microsoft add with signature and notification off, form reset afterwards; the add result clears when another kind is chosen.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Add IMAP/SMTP | existing: `adds the account, tests it and detects its folders`, `does not send an incomplete form`, `shows the field errors of a rejected account`, `moves a usual port along with the security`; added: `sends a blank name and signature as unset...`, `sends the notification choice`, `refuses a malformed address and a port out of range...`, `adds only once while the account is being added`, `keeps what was typed when the account is refused`, all of `server-fields.spec.ts` | `cd web && npx ng test --watch=false --include 'src/app/features/settings/mail/*.spec.ts'`; `npm run coverage` | Pass |
| AC1 Test connection (states) | existing: `shows that it runs, then the result per protocol`, `shows both servers connected`, `says when the test itself could not run`, `keeps a new account whose login fails...`; added: `tests each account on its own`, `shows Failed for a protocol that failed without a message`, `runs again after a result and replaces it`, `detects the folders when only SMTP fails...`, `keeps the new account when the test could not run`, `says when the folders of a new account could not be read` | same | Pass |
| AC1 Google, Microsoft | existing: `adds a Google account with a connected login`, `connects a Google account first when there is none, for mail`, `says when the provider is not set up on the server`, service `leaves for the consent screen with purpose MAIL`; added: `needs an address when the connection did not share one`, `prefers a typed address...`, `preselects a working connection and marks one that needs a reconnect`, `asks for a connection when none of them works`, `offers only the connections of the chosen provider and connects another one`, `adds a Microsoft account with its signature and notification`, `still lists the accounts when providers and connections cannot be read` | same | Pass. The real OAuth consent round trip was not run (needs real OAuth apps). |
| AC1 Identities (primary: name + address, per assumption) | existing: `edits identity, signature and login...`; added (editor): `shows the stored identity...`, `trims the identity and login, and uses the address as name...`, `refuses the address ...` (3 rows), `keeps the form and shows why the save failed`, `explains a save that lost...`, `saves only once...`, `cancels without a request` | same | Pass. Extra identities are out of scope (task mail-identities). |
| AC1 Signature (Markdown) | existing: `edits identity, signature...`, `edits a Google account...`; added: `sends a new password only when one was typed, and the signature Markdown as typed`, blank-signature cases in editor and add form, Microsoft add with signature | same | Pass. The Markdown is stored and sent verbatim; rendering is not in this task. |
| AC1 Folder role mapping | existing: `maps a folder role by hand`, `detects the folders again on request`, service `sets the folder role %s as %j`; added: all of `folder-roles.spec.ts` | same | Pass |
| AC1 Notification toggle | existing: `switches the new-mail notification...`, `switches the notification back when the change fails`; added: `changes the notification twice with the version of the first answer`, `sends the notification choice` | same | Pass |
| AC2 Vitest tests cover account forms and test-connection states | all of the above, plus `settings.routes.spec.ts` `opens Mail at /settings/mail` | `npm run coverage` (full web suite) | Pass: 131 files, 2660 tests |
| AC2 `cd web && npm run check` passes | api:check, lint, e2e:typecheck, test:ci, ng build | `cd web && npm run check` | Pass |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include 'src/app/features/settings/mail/*.spec.ts'`

Result:

Pass. 5 test files, 97 tests (before: 2 files, 31 tests). `npx prettier --check` and `npx eslint --max-warnings=0` on the spec files: clean after `prettier --write`.

## Full test execution

Command:

`cd web && npm run check` (api:check, lint, e2e:typecheck, test:ci, ng build)

Result:

Pass. Lint "All files pass linting", 131 test files / 2660 tests passed, build succeeded. The initial bundle budget warning (664 kB vs 500 kB) is not new; the mail settings are a lazy chunk.

## .taskcheck

Result:

Fail, caused outside this task. `sh scripts/check.sh` (about 21:25 to 21:32 UTC): tfp gate tests passed; server step BUILD FAILED in 6m 18s, 3906 tests, 1 failure in `ch.sonensei.cockpit.calendar.infra.ics.IcsFeedClientTests` ("The feed server could not be reached (IOException)" should include "larger than"). This is the known flaky cal-ics max-size test (also recorded in reviews/web-cal-sources/tests.md). The task changes no server code. Because `check.sh` uses `set -e`, the web and android steps did not run in this attempt. The web step's command, `npm run check`, passed on its own (see above). The parent must run `.taskcheck` again before `done`. The check also took longer than the 2 min warning (about 7 min).

## Bugs found

None. No test failed against the production code.

Observations for the parent (not requirement violations, no failing test kept):
- `folder-roles.ts` `setRole`: if the PATCH succeeds but the following folder reload (only done when the role moved from another folder) fails, the catch shows "The role of X could not be changed: ..." even though the change was saved. It then reloads again and shows the server's state. Low; the misleading text only appears when two requests in a row fail differently.
- `mail-settings.ts` `detectFolders`: "1 folders found." has no singular form (an i18n plural). Low, cosmetic.

## Coverage

Status: verified (fresh run, exit 0, all scoped files present in the report).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured. All scoped files are new in this task, except `settings.routes.ts`, which is 9/9 lines. So whole-file line coverage equals changed-line coverage for the new files.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-mail-accounts; base 3380f4bd2d7a247317bae24cd0564c0345591d7d; HEAD 1ffd4cedcf090269c9686410d43ca710260adfd1; the parent supplied the commits (aef6050b, 1ffd4ced, 7faec2bd) and the file scope. I checked it with `git diff --stat 3380f4bd..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 1ffd4ced plus uncommitted specs (sha256): `account-editor.spec.ts` cd641984fac854e87bce37da863152ea9c3c3a7293ac5f9aaf461feeebcc963d, `folder-roles.spec.ts` 062cb66dec0ab7b5844d8e359ef06e54775087175ff2417802ee4fc125c78139, `server-fields.spec.ts` f7df5b6d6d29f97294ab1db0ddd8589f77c598e0980fc311c31fa5c6ff69998d, `mail-settings.spec.ts` b62e35d3bef72cb7b6e9ca76c1595c1fa83b1c63dee656b404dbd87acad1614c, `mail-accounts.service.spec.ts` (unchanged) de5be9fe19839e6c0b378ad52a81336e5e127e076e857ad1e1e7a6c0a99786f0.
- Exact included files: `web/src/app/features/settings/mail/{account-editor.ts, account-editor.html, account-form.ts, folder-roles.ts, folder-roles.html, mail-accounts.service.ts, mail-settings.ts, mail-settings.html, messages.ts, server-fields.ts}` and `web/src/app/features/settings/settings.routes.ts`. Excluded: `mail/testing.ts` (test fixtures), `mail/*.scss` (styles, no behavior), `*.spec.ts`, `tasks/mail-identities.md` (task file), `calendars/calendars-settings.spec.ts` (test file, formatting only). No renames or deletions; no scoped file is missing from the report.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + @vitest/coverage-v8 via `@angular/build:unit-test`; `npm run coverage` in `web/`; full web suite (131 files).
- Run start/completion time and exit status: 2026-10-21T21:23:01Z to 21:24:04Z, exit 0 (131 files, 2660 tests passed).
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` sha256 a24b444cc7067fc8ea8bfc9c426625623b28bde4c611f8ee629d99d117ee86f1; `web/coverage/web/coverage-summary.json` sha256 cdbc018bc66e0b477aa9d7a88fd8b59ad9c33ac7643af9f318da23b20957d5d2 (both written 23:24 local by this run).
- Evidence outputs are fresh and inputs stayed unchanged: yes. HEAD and the spec hashes were the same before and after the run, and `git status` shows only the 4 spec files.
- Final run after latest test additions and production fixes: yes. It includes all added tests; no production fixes were made.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/.../settings/mail/account-editor.ts` | 38/38 (100%) | 24/25 (96.00%) | 5/5 (100%) | 31/31 (100%) |
| `web/.../settings/mail/account-editor.html` | 88/88 (100%) | 10/10 (100%) | 2/2 (100%) | 47/47 (100%) |
| `web/.../settings/mail/account-form.ts` | 8/8 (100%) | 10/10 (100%) | 3/3 (100%) | 8/8 (100%) |
| `web/.../settings/mail/folder-roles.ts` | 61/61 (100%) | 24/27 (88.89%) | 18/18 (100%) | 47/47 (100%) |
| `web/.../settings/mail/folder-roles.html` | 69/69 (100%) | 8/8 (100%) | 3/3 (100%) | 40/40 (100%) |
| `web/.../settings/mail/mail-accounts.service.ts` | 21/21 (100%) | 9/9 (100%) | 12/12 (100%) | 18/18 (100%) |
| `web/.../settings/mail/mail-settings.ts` | 144/146 (98.63%) | 60/70 (85.71%) | 45/45 (100%) | 122/122 (100%) |
| `web/.../settings/mail/mail-settings.html` | 343/343 (100%) | 30/30 (100%) | 15/15 (100%) | 223/223 (100%) |
| `web/.../settings/mail/messages.ts` | 7/8 (87.50%) | 7/8 (87.50%) | 2/2 (100%) | 6/7 (85.71%) |
| `web/.../settings/mail/server-fields.ts` | 60/60 (100%) | 18/19 (94.74%) | 3/3 (100%) | 32/32 (100%) |
| `web/.../settings/settings.routes.ts` | 9/9 (100%) | N/A (0/0) | 8/8 (100%) | 9/9 (100%) |

Aggregate (summed counters over all 11 files; exclusions as listed above, none missing): statements 848/851 (99.65%), branches 200/216 (92.59%), functions 116/116 (100%), lines 583/584 (99.83%).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `mail-settings.ts:161` (`if (this.test(account)?.running) return null`) | A second test while one runs | The button is disabled while running (asserted in `shows that it runs...`, `tests each account on its own`). The add flow only tests a brand-new account. Defensive second layer. |
| `mail-settings.ts:327` (`if (this.busy().has(...)) return false`) | A second notify/remove while one is in flight | The toggle and "Remove account" are disabled while busy (asserted in `changes the notification twice...` and `keeps an account whose removal failed...`). Defensive. |
| `mail-settings.ts:313` (`test.running` true branch) | Folder result arriving while a test runs | Not reachable: `detectFolders` runs only after `runTest` resolved. |
| `mail-settings.ts:224` (`?? connection.id`) | Label of a connection with neither email nor display name | Cosmetic fallback; email-only and displayName-only labels are tested. |
| `mail-settings.ts:194, 284, 346, 350`; `folder-roles.ts:117` (`?? null` / `?? []` / `?? account`) | Updates while the list is null | Defensive: actions are only offered once the list is loaded. |
| `mail-settings.ts:77`, `account-editor.ts:32`, `folder-roles.ts:40`, `server-fields.ts:81` (`if` on the class line) | None | Compiled class-field/decorator branch of the Angular build, no source behavior. |
| `messages.ts:5-6` (non-`ApiError` -> "Something went wrong.") | Text for a non-HTTP error | Every HTTP failure arrives as `ApiError` through the interceptor. Reaching it needs a thrown non-HTTP error, so a test would only chase the number. |

## Unable to test

- The real Google/Microsoft OAuth consent round trip and a real IMAP/SMTP login: they need registered OAuth apps and real mail servers. The redirect URL (purpose=MAIL) and all test-connection states are covered with HTTP fakes. Follow-up only if the criteria call for E2E.
- Rendering of the Markdown signature: not part of this task. The settings store and send it verbatim (tested).
- Playwright E2E: the criteria don't name it, so it was not run.

## Summary

Tests added and all validation passed for the task's scope. I added 66 Vitest tests (editor validation and errors, folder-role AUTOMATIC/NONE/error paths, server-field port behavior, OAuth add without a connection email, test-connection and add-flow states). Focused specs (97) and `cd web && npm run check` pass. Task-scoped coverage: 99.65% statements and 92.59% branches; every remaining gap is a defensive guard. No production defects found; two Low observations are listed for the parent. `.taskcheck` failed only on the known flaky server `IcsFeedClientTests` max-size test, outside this task. The parent must run it again before `done`.
