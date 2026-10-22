# Test Report

Date: 2026-10-22

Task: web-contacts - Contacts UI

Scope: `git diff 1f6a57005..ebc35ae6c` (base `1f6a57005e77603e0ac791518a8eb00f0b6ec45e`; task commits `13df9e2eb..6042bedc3`, merged at HEAD `ebc35ae6c159616eeb2b6426648a575e6d7eb6e8`), plus the uncommitted test additions of this report (listed below). Production code is in `web/src/app/features/contacts`; the e2e spec is `web/e2e/contacts/contacts.spec.ts`.

Requirements:
- `tasks/web-contacts.md` acceptance criteria and Notes (assumptions on sync status, routes, edit of synced contacts, Trash without confirm)
- `SPEC.md` §8.3 (via the task), `docs/ARCHITECTURE.md` "Coverage"
- `reviews/web-contacts/review.md` and `fixes.md` (CR-2..CR-5 behaviour already under test)

## Test files changed

- `web/src/app/features/contacts/contact-detail.spec.ts`
- `web/src/app/features/contacts/contacts-page.spec.ts`
- `web/src/app/features/contacts/contact-form-model.spec.ts`

## Tests added or updated

Count: 10 added, 0 changed, 0 removed.

- `ContactDetail` › sends one change when Save is pressed again while the first save is pending
- `ContactDetail` › moves the contact to the Trash once when Delete is pressed twice quickly
- `ContactDetail` › ignores a load error of an earlier route that arrives last
- `ContactDetail` › ignores a failed related-items load of the previous contact that arrives late
- `ContactsPage` › opens the file picker from the Import vCard button
- `ContactsPage` › reports an import without skipped cards and leaves out the skipped list
- `ContactsPage` › asks for the next page once when Show more is pressed twice quickly
- `ContactsPage` › does not toast a failed Show more that arrives after the search changed
- `ContactsPage` › drops a quiet reload that answers after the search changed
- `contact form model` › fills empty text for a missing family name and an email without label

The existing 93 contacts tests (written during the task) already covered the main behaviour of every
criterion; the additions target double submits, out-of-order responses and two uncovered UI paths.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 List with search | ContactsPage: lists contacts…, searches as you type…, searches at once on Enter…, says when nothing matches…, loads more pages, shows the latest search when an earlier one answers/fails last, asks for the next page once…, does not toast a failed Show more…, drops a quiet reload…; ContactsService tests | `npm --prefix web exec -- ng test --watch=false --include='src/app/features/contacts/**/*.spec.ts'` | Pass |
| AC1 Detail with related mails/events/waiting-for | ContactDetail: shows Waiting For items, upcoming events and recent mails…, says when nothing is related, offers to retry a failed load…, drops related items… that arrive late, ignores a failed related-items load of the previous contact… | same | Pass |
| AC1 Edit (If-Match, conflict, rename) | ContactDetail: edits the contact with its version as If-Match, keeps editing… after a conflict, renames a contact whose display name was derived, after a conflict Cancel…, sends one change when Save is pressed again…; ContactForm and form-model tests | same | Pass |
| AC1 vCard import/export | ContactsPage: imports a vCard file…, reports an import without skipped cards…, shows why an import failed, does nothing when the dialog is closed…, opens the file picker…, offers the export…; ContactDetail: shows the fields (export link); ContactsService export URL tests | same | Pass |
| AC1 Google/MS sync status | ContactsPage: shows which accounts sync contacts, and a paused one; leaves out the account name…; says contacts are kept here only…; ContactDetail: sync status, paused, no account, first account of the provider, accounts cannot be loaded | same | Pass |
| AC2 Vitest tests cover detail sections; `npm run check` passes | all of the above | `npm --prefix web run check` | Pass (exit 0; 161 files, 3267 tests) |
| AC3 Playwright `e2e/contacts/contacts.spec.ts` against the e2e stack | `contacts › import a vCard; the birthday appears in the calendar` | not run by test-writer | Not run: the parent ran `npm --prefix web run e2e -- e2e/contacts/contacts.spec.ts` -> 1 passed; I only reviewed the spec (it covers import via the file chooser, search, detail fields and sections, export, birthday on 2027-03-14 only) |

## Focused test execution

Command:

`npm --prefix web exec -- ng test --watch=false --include='src/app/features/contacts/**/*.spec.ts'`

Result:

Pass (6 files, 103 tests; 93 before the additions)

Also: `npm --prefix web exec -- eslint --max-warnings=0 web/src/app/features/contacts/` -> no findings;
`npm --prefix web exec -- prettier --check web/src/app/features/contacts/` -> all files formatted.

## Full test execution

Command:

`npm --prefix web run check` (api:check, lint, e2e:typecheck, test:ci, ng build)

Result:

Pass (exit 0; 161 test files, 3267 tests; the build prints the pre-existing initial-bundle budget warning)

## .taskcheck

Result:

Not run: `.taskcheck` is `sh scripts/check.sh` (server, web and android). The web part was run in full above; the server/android parts are unaffected by this task (web-only scope) and are left to the parent's final `.taskcheck` run.

## Bugs found

None. No added or existing test failed.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (all scoped files are new in this task, so whole-file coverage equals coverage of the added lines for them; no separate changed-line tooling was used).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-contacts`; base `1f6a57005e77603e0ac791518a8eb00f0b6ec45e` (given by the parent); HEAD `ebc35ae6c159616eeb2b6426648a575e6d7eb6e8`; task commits `13df9e2eb..6042bedc3` from `git log 1f6a57005..HEAD`; file scope from `git diff --name-only 1f6a57005..HEAD -- web/src`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `ebc35ae6c` plus the three uncommitted spec files above; `sha256sum web/src/app/features/contacts/*.ts web/src/app/features/contacts/*.html | sha256sum` = `90a4560f006490e2ef137391e0cac6d904de59d4c20ed980c648247fd7c8669c` (spec hashes: contact-detail.spec.ts `347a897b…caf9f`, contacts-page.spec.ts `67727c53…9b558`, contact-form-model.spec.ts `eff0e648…fb495`).
- Exact included files: `contact-detail.html`, `contact-detail.ts`, `contact-display.ts`, `contact-form-model.ts`, `contact-form.html`, `contact-form.ts`, `contacts-page.html`, `contacts-page.ts`, `contacts.routes.ts`, `contacts.service.ts` (all under `web/src/app/features/contacts/`). Excluded: `*.spec.ts`, `testing.ts` (test fixtures), `*.scss` (styles, not instrumented), `web/src/app/app.spec.ts` (test), `web/e2e/contacts/contacts.spec.ts` (e2e test), `reviews/` (docs). No renamed or deleted files. No scoped file was missing from the report.
- Provider/version, command, working directory, and test selection: Vitest v5.0.5 with `@vitest/coverage-v8` via Angular `ng test`; `npm --prefix web run coverage` (project command) from the clone root, package dir `web/`; full web unit suite (161 files, 3267 tests).
- Run start/completion time and exit status: 2026-10-22 09:55:54 to 09:56:44 +0200; exit 0.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json`, `web/coverage/web/coverage-final.json` (sha256 `cbffb7c1827d9f234a887380fcd112a2957228a1f08dcb8578c634d3c2b3a5e5`), `lcov.info`, `lcov-report/`.
- Evidence outputs are fresh and inputs stayed unchanged: the report files carry timestamps 09:56:44 from this run (the earlier focused coverage run wrote 09:50:56 files without lcov; the final run rewrote the directory); no source or test file was edited after the run started.
- Final run after latest test additions and production fixes: yes (after all 10 additions; production code unchanged since `6042bedc3`, which includes the CR-2..CR-5 fixes).

Values below use covered/total (percentage); counters from `coverage-summary.json`.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/contacts/contact-detail.html` | 264/264 (100%) | 55/58 (94.83%) | 9/9 (100%) | 157/157 (100%) |
| `web/src/app/features/contacts/contact-detail.ts` | 143/145 (98.62%) | 68/74 (91.89%) | 22/22 (100%) | 122/122 (100%) |
| `web/src/app/features/contacts/contact-display.ts` | 26/26 (100%) | 23/23 (100%) | 15/15 (100%) | 23/23 (100%) |
| `web/src/app/features/contacts/contact-form-model.ts` | 41/41 (100%) | 67/68 (98.53%) | 17/17 (100%) | 35/35 (100%) |
| `web/src/app/features/contacts/contact-form.html` | 206/206 (100%) | 4/4 (100%) | 25/25 (100%) | 143/143 (100%) |
| `web/src/app/features/contacts/contact-form.ts` | 44/44 (100%) | 22/23 (95.65%) | 16/16 (100%) | 33/33 (100%) |
| `web/src/app/features/contacts/contacts-page.html` | 177/177 (100%) | 32/32 (100%) | 9/9 (100%) | 102/102 (100%) |
| `web/src/app/features/contacts/contacts-page.ts` | 111/112 (99.11%) | 49/53 (92.45%) | 20/20 (100%) | 94/94 (100%) |
| `web/src/app/features/contacts/contacts.routes.ts` | 3/3 (100%) | 0/0 (N/A) | 2/2 (100%) | 3/3 (100%) |
| `web/src/app/features/contacts/contacts.service.ts` | 22/22 (100%) | 12/12 (100%) | 13/13 (100%) | 17/17 (100%) |
| **Aggregate (10 files, summed counters)** | 1037/1040 (99.71%) | 332/347 (95.68%) | 148/148 (100%) | 729/729 (100%) |

Aggregate excludes only the files named in "Exact included files" above as excluded; no scoped file is unmeasured.

Before the added tests (focused run of the 93 existing contacts tests, 09:50): aggregate statements 1031/1040, branches 323/347, functions 147/148, lines 728/729.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `contact-detail.ts:163`, `:197` (double save / double delete guards) | same write submitted twice | Covered now: "sends one change when Save is pressed again…", "moves the contact to the Trash once…" |
| `contact-detail.ts:134`, `:144` (stale load / related errors) | out-of-order failures | Covered now: "ignores a load error of an earlier route…", "ignores a failed related-items load…" |
| `contacts-page.html:19`, `:82`; `contacts-page.ts:107`, `:119`, `:187` | Import button opens the picker; import without skipped cards; double Show more; stale Show more error; stale quiet reload | Covered now by the five new ContactsPage tests |
| `contact-detail.ts:176` (`if (!contact) return` in save) | edit form is only shown with a loaded contact | Not reachable from the UI (Save exists only while a contact is shown); defensive, no test |
| `contact-detail.ts:233-234` (change event: route changed or editing started while the reload is pending; `?? 0`) | out-of-order reload after a change event | Low risk: the form keeps typed values regardless (refills only on id change) and a stale version only causes a later 412 that is handled; `?? 0` unreachable after the `!this.contact()` guard. Left uncovered |
| `contact-detail.ts:100`, `:55`, `:262`; `contacts-page.ts:36`, `:205`, `:115` | `?? ''` for a missing route id, non-ApiError fallback message, class-field/compiler branches, `current ?? []` | Defensive or compiler-generated branches; the router always supplies `:id`, the HTTP layer always yields `ApiError` |
| `contacts-page.ts:73` (same `q` re-emitted after load) | unrelated query param change does not reload | Minor; not a criterion. Left uncovered |
| `contact-detail.html:112,123,134` (cond-expr alternatives) | template conditional alternatives in the related sections | Branch alternatives of optional values (e.g. missing subject/date variants) that the existing tests render partly; not material |
| `contact-form-model.ts:57`, `contact-form.ts:36` | `city ?? ''` for a stored address without a city; an early-return guard of the form's refill effect | Missing address parts are already shown to become `''` for street/region/country by the first model test; the `city` alternative is the same pattern. Not material |

## Unable to test

- AC3 (Playwright against the e2e stack) was not executed by the test-writer; the parent reports `npm --prefix web run e2e -- e2e/contacts/contacts.spec.ts` -> 1 passed. I reviewed the spec only. Note for follow-up: the spec checks the birthday on 2027-03-14 with age 37, which is a fixed future year; it stays correct as long as the calendar renders that year, so no change needed.
- Per-contact "last sync" time: not testable, the API exposes none (task assumption).

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient; no production defects found.
