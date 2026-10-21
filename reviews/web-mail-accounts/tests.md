# Test Report

Date: 2026-10-21

Task: web-mail-accounts: Settings: mail accounts

Scope: base 21a794177f9653b986bafcf9e8e5e28d4278cf98 .. HEAD c27dd40ead6c36a10f7560333c12f1a03a36fd51 (branch main). Reviewed revision: c27dd40ead6c36a10f7560333c12f1a03a36fd51. The parent named the task commits: 9e7ae9bc (task file only), 4a3ed74e (implementation), 096c4b3c and 86a1f026 (specs and the earlier test report), d616ae4a (review), c27dd40e (fixes for review findings CR-1..CR-3). Production scope: `web/src/app/features/settings/mail/` plus `web/src/app/features/settings/settings.routes.ts`. The only other code file in the range is `calendars/calendars-settings.spec.ts`, a test file with a Prettier-only reformat. This re-run checks the final code after the CR-1..CR-3 fixes. Uncommitted inputs: the 2 spec files this test writer extended (hashes below). No production file was modified.

Requirements:
- tasks/web-mail-accounts.md acceptance criteria and its 3 recorded ASSUMPTIONs (primary identity only; Google/Microsoft OAuth connect with purpose=MAIL via Settings -> Integrations; test connection runs on the stored account, so the add form saves first, then tests and detects folders)
- SPEC.md §7.1 (as referenced by the task), §4.2 (Problem Details, If-Match)
- reviews/web-mail-accounts/review.md (CR-1..CR-3) and fixes.md (the intended fixed behavior)
- docs/ARCHITECTURE.md "Coverage"; AGENTS.md project notes
- Existing test conventions (Vitest + TestBed + HttpTestingController, `testing.ts` fakes, `problem()` helper)

## Test files changed

- `web/src/app/features/settings/mail/mail-settings.spec.ts` (extended; no existing test changed)
- `web/src/app/features/settings/mail/folder-roles.spec.ts` (extended; no existing test changed)

## Tests added or updated

Count: 5 new Vitest tests in this run (focused run went from 100 to 105 tests). The 66 tests from the earlier run and the 3 tests the fixer added are unchanged.

- `mail-settings.spec.ts`
  - `keeps a test that still runs when the account is saved, and shows its answer`: CR-2's "a running test keeps its state" branch. The test is started, the account is edited and saved while the request is pending. Then "Logging in to the mail servers…" still shows, "Test connection" stays disabled, and the late answer is shown afterwards. Without the `running` guard in `saved()`, this test fails.
  - `keeps the test result when the edit is cancelled or refused`: Cancel and a refused save (412) do not drop the result, because nothing was saved.
  - `drops only the test result of the edited account`: with two tested accounts, saving one keeps the other's result.
- `folder-roles.spec.ts`
  - `disables the other folders too while a role is saved, one change at a time`: CR-1's single `saving` flag. During a PATCH that moves no role ("No role"), the other folder's select is disabled. No reload follows, and all selects are enabled again afterwards.
  - `allows changes again once the server state is read after a failed change`: a failed PATCH shows "The role of Old could not be changed: Server error", reloads, and enables the selects again (the `saving` flag is reset in `finally` on the early `return` path).

## Requirement coverage

Focused command (F): `cd web && npx ng test --watch=false --include 'src/app/features/settings/mail/*.spec.ts'`. Full command (C): `cd web && npm run check`. Coverage (V): `cd web && npm run coverage`.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Add IMAP/SMTP | `adds the account, tests it and detects its folders`, `does not send an incomplete form`, `shows the field errors of a rejected account`, `moves a usual port along with the security`, `sends a blank name and signature as unset...`, `sends the notification choice`, `refuses a malformed address and a port out of range...`, `adds only once while the account is being added`, `keeps what was typed when the account is refused`, all of `server-fields.spec.ts` | F, C, V | Pass |
| AC1 Test connection (states) | `shows that it runs, then the result per protocol`, `shows both servers connected`, `says when the test itself could not run`, `keeps a new account whose login fails...`, `tests each account on its own`, `shows Failed for a protocol that failed without a message`, `runs again after a result and replaces it`, `detects the folders when only SMTP fails...` (now checks "1 folder found.", CR-3), `keeps the new account when the test could not run`, `says when the folders of a new account could not be read`; after the CR-2 fix: `drops the old connection test result when the account is edited` (fixer), and new in this run: `keeps a test that still runs when the account is saved...`, `keeps the test result when the edit is cancelled or refused`, `drops only the test result of the edited account` | F, C, V | Pass |
| AC1 Google, Microsoft | `adds a Google account with a connected login`, `connects a Google account first when there is none, for mail`, `says when the provider is not set up on the server`, service `leaves for the consent screen with purpose MAIL`, `needs an address when the connection did not share one`, `prefers a typed address...`, `preselects a working connection and marks one that needs a reconnect`, `asks for a connection when none of them works`, `offers only the connections of the chosen provider and connects another one`, `adds a Microsoft account with its signature and notification`, `edits a Google account without login or servers` | F, C, V | Pass. The real OAuth consent round trip was not run (needs registered OAuth apps). |
| AC1 Identities (primary: name + address, per assumption) | `edits identity, signature and login...`, all of `account-editor.spec.ts` (prefill, trimming, address validation, 400/412 errors, double submit, Cancel) | F, C, V | Pass. Extra identities are out of scope (task mail-identities). |
| AC1 Signature (Markdown) | `edits identity, signature...`, `sends a new password only when one was typed, and the signature Markdown as typed`, blank-signature cases, Microsoft add with signature | F, C, V | Pass. The Markdown is stored and sent verbatim; rendering is not in this task. |
| AC1 Folder role mapping | `maps a folder role by hand`, `detects the folders again on request`, service `sets the folder role %s as %j`, all of `folder-roles.spec.ts`, including the fixer's CR-1 specs (`disables every folder while a moved role is saved...`, `reports a failed reload after a saved role as a load error...`) and the 2 new ones in this run | F, C, V | Pass |
| AC1 Notification toggle | `switches the new-mail notification...`, `switches the notification back when the change fails`, `changes the notification twice with the version of the first answer`, `sends the notification choice` | F, C, V | Pass |
| AC2 Vitest tests cover account forms and test-connection states | all of the above, plus `settings.routes.spec.ts` `opens Mail at /settings/mail` | F (5 files, 105 tests); C/V (131 files, 2668 tests) | Pass |
| AC2 `cd web && npm run check` passes | api:check, lint, e2e:typecheck, test:ci, ng build | C | Pass |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include 'src/app/features/settings/mail/*.spec.ts'`

Result:

Pass. 5 test files, 105 tests (100 before this run). The spec files were formatted with `npx prettier --write`; `ng lint --max-warnings=0` (part of `npm run check`) passes.

## Full test execution

Command:

`cd web && npm run check` (api:check, lint, e2e:typecheck, test:ci, ng build), 2026-10-21 21:46:11Z to 21:47:14Z

Result:

Pass. "src/app/api/generated is up to date", "All files pass linting", 131 test files / 2668 tests passed, build succeeded. The build prints the existing initial bundle budget warning (664.23 kB against 500 kB). The `mail-settings` chunk is lazy (43.66 kB), so the task does not cause it.

## .taskcheck

Result:

Pass. `sh scripts/check.sh` (the `.taskcheck`) ran on the snapshot above from 2026-10-21 21:50:51Z to 22:01:52Z, exited 0 and ended with "== all checks passed" (server, web and android steps). It took about 11 minutes. That is under the 15 min `done` limit but above the 2 min warning, mostly because of the server step, which this task does not touch.

## Bugs found

None. No test failed against the production code at c27dd40e. The two Low observations from the earlier run (failed reload reported as a failed role change; "1 folders found.") were raised as CR-1 and CR-3 and are fixed. The tests above confirm the fixes.

## Coverage

Status: verified (fresh run, exit 0, all scoped files present in the report).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. All scoped files are new in this task except `settings.routes.ts` (9/9 lines), so whole-file coverage of the new files equals their changed-line coverage.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-mail-accounts; base 21a794177f9653b986bafcf9e8e5e28d4278cf98; HEAD c27dd40ead6c36a10f7560333c12f1a03a36fd51. The parent supplied the commits (9e7ae9bc, 4a3ed74e, 096c4b3c, 86a1f026, d616ae4a, c27dd40e) and the file scope. I checked them with `git log --oneline` and `git diff --stat` over 21a79417..HEAD.
- Source/test snapshot identifier (including uncommitted inputs): HEAD c27dd40e plus uncommitted specs (sha256): `folder-roles.spec.ts` 9d2428b7feb76666b1dbbeea5f19fac1eab57bd84be4ca47e34f435f8ecb5d03, `mail-settings.spec.ts` 0d8777c837b1f4e56ed379372bf54e43d4e5ffa5f2563ec52f19a5fd03201410. Unchanged committed specs: `account-editor.spec.ts` cd641984fac854e87bce37da863152ea9c3c3a7293ac5f9aaf461feeebcc963d, `server-fields.spec.ts` f7df5b6d6d29f97294ab1db0ddd8589f77c598e0980fc311c31fa5c6ff69998d, `mail-accounts.service.spec.ts` de5be9fe19839e6c0b378ad52a81336e5e127e076e857ad1e1e7a6c0a99786f0, `settings.routes.spec.ts` 13cf4587e5dc9b8d2b07a8bc6df613eadf4ebec76846c3075d6415c124947d83.
- Exact included files: `web/src/app/features/settings/mail/{account-editor.ts, account-editor.html, account-form.ts, folder-roles.ts, folder-roles.html, mail-accounts.service.ts, mail-settings.ts, mail-settings.html, messages.ts, server-fields.ts}` and `web/src/app/features/settings/settings.routes.ts`. Excluded: `mail/testing.ts` (test fixtures), `mail/*.scss` (styles, no behavior), `*.spec.ts`, `tasks/*.md` and `reviews/*` (not code), `calendars/calendars-settings.spec.ts` (test file, formatting only). No renames or deletions. No scoped file is missing from the report.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + @vitest/coverage-v8 via `@angular/build:unit-test`; `npm run coverage` in `web/`; the full web suite (131 files).
- Run start/completion time and exit status: two back-to-back runs from 2026-10-21 21:48:23Z to 21:50:15Z. Both passed 131 files / 2668 tests, and the second exited 0 (`npm run coverage >/dev/null && echo COVERAGE_EXIT_0` printed it). The numbers below come from the second run. An earlier run at 21:47:17Z to 21:48:19Z had the same results.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` sha256 ce12243888ced5419e943b004d9fd310aa926846595dde631db79419bbd5ec9e; `web/coverage/web/coverage-summary.json` sha256 50fc4908bc8eda800352349666b62c7b39ef1debfdf650577a5df5476bb5cee2 (both rewritten at 23:48-23:50 local by these runs).
- Evidence outputs are fresh and inputs stayed unchanged: yes. HEAD and the spec hashes were the same before and after the runs. `git status` shows only the 2 spec files.
- Final run after latest test additions and production fixes: yes. It includes the CR-1..CR-3 fixes (c27dd40e) and all 5 tests added in this run.

Values below come from `coverage-summary.json` (provider counters) and use covered/total (percentage). Zero-denominator metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/.../settings/mail/account-editor.ts` | 38/38 (100%) | 24/25 (96.00%) | 5/5 (100%) | 31/31 (100%) |
| `web/.../settings/mail/account-editor.html` | 88/88 (100%) | 10/10 (100%) | 2/2 (100%) | 47/47 (100%) |
| `web/.../settings/mail/account-form.ts` | 8/8 (100%) | 10/10 (100%) | 3/3 (100%) | 8/8 (100%) |
| `web/.../settings/mail/folder-roles.ts` | 63/64 (98.44%) | 26/29 (89.66%) | 17/17 (100%) | 50/50 (100%) |
| `web/.../settings/mail/folder-roles.html` | 69/69 (100%) | 8/8 (100%) | 3/3 (100%) | 40/40 (100%) |
| `web/.../settings/mail/mail-accounts.service.ts` | 21/21 (100%) | 9/9 (100%) | 12/12 (100%) | 18/18 (100%) |
| `web/.../settings/mail/mail-settings.ts` | 149/151 (98.68%) | 65/74 (87.84%) | 46/46 (100%) | 127/127 (100%) |
| `web/.../settings/mail/mail-settings.html` | 343/343 (100%) | 30/30 (100%) | 15/15 (100%) | 223/223 (100%) |
| `web/.../settings/mail/messages.ts` | 7/8 (87.50%) | 7/8 (87.50%) | 2/2 (100%) | 6/7 (85.71%) |
| `web/.../settings/mail/server-fields.ts` | 60/60 (100%) | 18/19 (94.74%) | 3/3 (100%) | 32/32 (100%) |
| `web/.../settings/settings.routes.ts` | 9/9 (100%) | N/A (0/0) | 8/8 (100%) | 9/9 (100%) |

Aggregate (summed counters over all 11 files; exclusions as listed above, none missing): statements 855/859 (99.53%), branches 207/222 (93.24%), functions 116/116 (100%), lines 591/592 (99.83%).

The CR-1/CR-2/CR-3 fix code is fully covered on both branches: `saved()` running/not running (`mail-settings.ts:206`), the singular/plural folder count (`mail-settings.ts:316-319`), `setRole`'s PATCH failure, in-place update, moved-role refresh and refresh failure (`folder-roles.ts:110-131`), and `refresh()` (`folder-roles.ts:134-140`).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `folder-roles.ts:111` (`if (this.saving()) return;`) | A second role change while one is saved (CR-1) | Every select is disabled while `saving` is set. The new test `disables the other folders too...` and the fixer's `disables every folder while a moved role is saved...` assert this. The guard is a second layer that the UI cannot reach. |
| `folder-roles.ts:123` (`?? null`) | Update while the list is null | Defensive. Roles can only be chosen once the list is loaded. |
| `mail-settings.ts:161` (`if (this.test(account)?.running) return null`) | A second test while one runs | The button is disabled while running (asserted in `shows that it runs...` and in the new `keeps a test that still runs...`). Defensive. |
| `mail-settings.ts:338` (`if (this.busy().has(...)) return false`) | A second notify/remove while one is in flight | The controls are disabled while busy (asserted). Defensive. |
| `mail-settings.ts:324` (`test && !test.running` false branch) | Folder result arriving with no test entry or a running test | Only reachable if the user edits and saves the just-added account during its folder detection. Then `saved()` drops the entry and the folder text is discarded, which is consistent with CR-2. A very narrow race with no requirement; no test added. |
| `mail-settings.ts:232` (`?? connection.id`) | Label of a connection with neither email nor display name | Cosmetic fallback; email-only and displayName-only labels are tested. |
| `mail-settings.ts:194, 292, 357, 361` (`?? null` / `?? []` / `?? account`) | Updates while the list is null | Defensive. Actions are only offered once the list is loaded. |
| `mail-settings.ts:77`, `account-editor.ts:32`, `folder-roles.ts:40`, `server-fields.ts:81` (branch on the class line) | None | A class-field/decorator branch that the Angular build compiles in, with no source behavior. |
| `messages.ts:5-6` (non-`ApiError` -> "Something went wrong.") | Text for a non-HTTP error | Every HTTP failure arrives as `ApiError` through the interceptor. Reaching it needs a thrown non-HTTP error, so a test would only chase the number. |

## Unable to test

- The real Google/Microsoft OAuth consent round trip and a real IMAP/SMTP login need registered OAuth apps and real mail servers. The redirect URL (purpose=MAIL) and all test-connection states are covered with HTTP fakes. A follow-up is only needed if the criteria call for E2E.
- Rendering of the Markdown signature is not part of this task. The settings store and send it verbatim (tested).
- Playwright E2E: the criteria don't name it, so it was not run.

## Summary

Tests added and all validation passed. This run re-checked the final code (c27dd40e, after the CR-1..CR-3 fixes) and added 5 Vitest tests. They cover the CR-2 branch where a running test survives a save, a cancelled or refused edit keeping the result, the drop being limited to the edited account, and CR-1's single `saving` flag on both the no-reload and the failed-PATCH paths. Focused specs (105) and `cd web && npm run check` (2668 tests, lint, build) pass, and so does `.taskcheck` (`sh scripts/check.sh`, exit 0). Task-scoped coverage: statements 855/859 (99.53%), branches 207/222 (93.24%), functions 116/116, lines 591/592. The fix code is fully covered; the remaining gaps are defensive guards or a narrow race. No production defects found.
