# Test Report

Date: 2026-10-25

Task: web-mail-contacts-autocomplete - Mail compose: contacts autocomplete

Scope: round 2 on the final code. Base `ef381c76119c78d9a371ada7f632edec3cc2e86f` .. final revision `e6d8483ac2b5909b466210b09ed44d7259157ef5` (HEAD, branch `main`, clean working tree). Task commits: `d68acec38` (implementation), `785a3b3a8` (round 1 test-writer tests and reports), `e6d8483ac` (doc-comment rewrap for CR-1). Production change since round 1 (`d68acec38..e6d8483ac`): only the `RecipientInput` doc comment in `recipient-input.ts`, rewrapped onto one more line (line 59), so every later line moves down by one. No behavior changed.

Requirements:
- `tasks/web-mail-contacts-autocomplete.md` acceptance criteria: (1) Autocomplete uses the contacts endpoint; (2) Vitest component/service tests cover merged suggestions; `cd web && npm run check` passes
- `SPEC.md` (mail compose: "Recipient autocomplete from Contacts + recent correspondents")
- `api/openapi.yaml` `GET /api/v1/contacts/autocomplete` (`autocompleteContacts`) contract
- `docs/ARCHITECTURE.md` "Coverage"; `AGENTS.md`
- Parent instructions (round 2: confirm the tests still fit the final code and add tests only where there are gaps)

## Test files changed

- None in round 2. The tests from the task commit and round 1 (`web/src/app/features/mail/compose/mail-compose.service.spec.ts`, `web/src/app/features/mail/compose/recipient-input.spec.ts`, committed in `785a3b3a8`) were re-checked against the final code. They still fit it, and no gap was found.

## Tests added or updated

Count: 0 in round 2. The task has 11 tests for this behavior in total: 4 added by the implementer in `d68acec38` (3 service, 1 component) and 7 added by the test-writer in round 1 (4 service, 3 component):
- service: `has no suggestions when the autocomplete answers an empty body`
- service: `trims suggested addresses, keeps a contact without name and dedupes after trimming`
- service: `looks up suggestions silently and rejects when the autocomplete fails`
- service: `no longer asks the mail correspondents endpoint for suggestions`
- component: `announces contacts to screen readers only and hides the icon from them`
- component: `adds a chosen contact without name as its address only`
- component: `hides a contact suggestion whose address is already a recipient, in any case`

## Requirement coverage

Focused command (F): `cd web && npx ng test --watch=false --include='src/app/features/mail/compose/**/*.spec.ts'`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: Autocomplete uses the contacts endpoint (GET `/api/v1/contacts/autocomplete`, `q`, `limit`) | service: `asks the contacts autocomplete and keeps its merged order, contacts first`, `no longer asks the mail correspondents endpoint for suggestions`, `looks up suggestions silently and rejects when the autocomplete fails` | F | Pass |
| AC2: merged suggestions (contacts first, then correspondents, server order kept) | service: `asks the contacts autocomplete and keeps its merged order, contacts first` | F | Pass |
| AC2: entries without address dropped; a repeated address (case-insensitive, after trimming) kept once, the first | service: `drops suggestions without address and keeps the first of a repeated address`, `trims suggested addresses, keeps a contact without name and dedupes after trimming` | F | Pass |
| AC2: empty answer / empty body | service: `has no suggestions when the autocomplete answers nothing`, `has no suggestions when the autocomplete answers an empty body` | F | Pass |
| AC2: failure is silent (no toast) and rejects so the component can clear suggestions | service: `looks up suggestions silently and rejects when the autocomplete fails`; component: `shows no suggestions when looking them up fails` (pre-existing) | F | Pass |
| AC2 (component): contact suggestions marked (icon + visually hidden "Contact:"), correspondents unmarked | component: `marks contact suggestions and adds a chosen contact as name and address only`, `announces contacts to screen readers only and hides the icon from them` | F | Pass |
| AC2 (component): chosen suggestion added as `{address, name}` only (no `contactId`), nameless as `{address}` | component: `marks contact suggestions and adds a chosen contact as name and address only`, `adds a chosen contact without name as its address only` | F | Pass |
| AC2 (component): suggestions already among the recipients hidden (case-insensitive) | component: `hides a contact suggestion whose address is already a recipient, in any case`, `asks for suggestions once typing pauses, without recipients already added` (pre-existing) | F | Pass |
| AC2: ordering (an older lookup answering last) | component: `shows only the suggestions of the latest text when answers arrive out of order`, `drops a pending lookup once the typed address became a chip` (pre-existing) | F | Pass |
| AC2: `cd web && npm run check` passes | whole web suite, lint, api:check, e2e typecheck, build | `cd web && npm run check` | Pass (exit 0 on the second run; see "Full test execution" for the first run, which timed out under machine load) |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/mail/compose/**/*.spec.ts'`

Result:

Pass: 5 test files, 126 tests passed (started 22:39:26 +01:00).

## Full test execution

Command:

`cd web && npm run check` (api:check, lint, e2e:typecheck, `ng test --watch=false`, `ng build`)

Result:

Pass on the second run: exit 0. Output: `src/app/api/generated is up to date.`, `All files pass linting.`, 166 test files / 3463 tests passed, build complete. The build still warns that the initial bundle exceeds the 500 kB budget (676.94 kB). That warning is older than this task.

The first run (about 22:42 +01:00) exited 1. 7 tests in 6 unrelated files timed out after 5 s or 10 s: `app.spec.ts`, `shared/links/linked-items.spec.ts` (2), `calendar/edit/event-dialog.spec.ts`, `files/upload/upload.service.spec.ts`, `mail/read/mail-read-page.spec.ts`, `notes/editor/note-editor-page.spec.ts`. None is in `mail/compose`. Load average was 46 because other agents share the machine. Run alone, those 6 files passed (298/298 tests), and they also passed in the coverage run just before. So these failures are load-related flakiness, not a task defect. The parent should keep this in mind when it runs `.taskcheck`.

## .taskcheck

Result:

Not run by the test-writer. `scripts/check.sh` also runs the Gradle server and Android checks, which helper sub-agents cannot run in this environment. Its web part (`npm run check`) passed above. The parent worker must run `.taskcheck`.

## Bugs found

None.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured. Method: the provider's source-mapped `coverage-final.json` gives the line on which each statement starts. Those lines were intersected with the added/modified lines of `git diff -U0 ef381c76..e6d8483ac`. A line counts as covered when every statement starting on it ran. Result: `mail-compose.service.ts` 10/10 (lines 117, 122-129, 133); `recipient-input.ts` 9/9 (inline-template lines 111-114 and 117-119, plus lines 211-212); total 19/19 (100%). The rewrapped comment lines 58-60 and the CSS lines 148-152 are not executable. Changed-branch coverage is not derived from this.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-mail-contacts-autocomplete`. Base `ef381c76119c78d9a371ada7f632edec3cc2e86f` (the claim commit, supplied by the parent). Measured HEAD `e6d8483ac2b5909b466210b09ed44d7259157ef5`. Task commits `d68acec38`, `785a3b3a8`, `e6d8483ac` were supplied by the parent and confirmed with `git diff --stat ef381c76..e6d8483ac`. They touch only `web/src/app/features/mail/compose/` and `reviews/web-mail-contacts-autocomplete/`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `e6d8483ac`, clean working tree, no uncommitted inputs. sha256: `mail-compose.service.ts` a2cec7ca...91053a3, `recipient-input.ts` 16b0cf15...fc2802, `mail-compose.service.spec.ts` 19290a3d...dc028a02, `recipient-input.spec.ts` 70b3451a...b01c579a. The hashes were identical before and after the coverage run.
- Exact included files; renamed/deleted/excluded files and reasons: included `web/src/app/features/mail/compose/mail-compose.service.ts` and `web/src/app/features/mail/compose/recipient-input.ts`. Excluded: the two `*.spec.ts` files (tests) and the `reviews/` reports (documentation). There were no renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with `@vitest/coverage-v8` (v8), `npm run coverage` (run as `npm --prefix web run coverage`), working directory `web/`, full web suite (3463 tests).
- Run start/completion time and exit status: started 2026-10-25T22:39:37+01:00 (Vitest start 22:39:53). Reports were written at 22:41:12 +01:00. The output reported 3463/3463 tests passed and the coverage summary was printed. The numeric exit code was not captured because the output was piped through `tail`. The reports were regenerated and show no failures.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` sha256 9c486a69b0087cc17e1b83e853af78ed3acaaa079cc11e506b167bcedf7b42b6, the same content as round 1 because the counts did not change. `web/coverage/web/coverage-final.json` sha256 8e7a6ff983d7d69118bcb0024699027e6ba161bed0e8bf0686f66a3eb2dacc4a, which differs from round 1 (e45f38e8...) because the comment rewrap moved the source locations.
- Evidence outputs are fresh and inputs stayed unchanged: yes. Both reports have mtimes from this run (22:41:12 +01:00). `coverage-final.json` contains the shifted line numbers of the final revision. The source/test hashes did not change during the run.
- Final run after latest test additions and production fixes: yes. It ran on the final revision `e6d8483ac`, after the CR-1 rewrap. No tests were added in round 2.

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/mail/compose/mail-compose.service.ts` | 35/35 (100%) | 25/25 (100%) | 12/12 (100%) | 30/30 (100%) |
| `web/src/app/features/mail/compose/recipient-input.ts` | 131/134 (97.76%) | 40/44 (90.91%) | 30/32 (93.75%) | 93/93 (100%) |
| Aggregate (both files, summed counters; no files excluded or unmeasured) | 166/169 (98.22%) | 65/69 (94.20%) | 42/44 (95.45%) | 123/123 (100%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `recipient-input.ts:170-171` (statements and functions of `viewChild.required(...)`) | Compiler-generated signal-query functions; older than this task and not task code | Non-material; no test added |
| `recipient-input.ts:196` branches 0/1 (`event.clipboardData?.getData('text') ?? ''`) | Paste without clipboard data; older than this task (web-mail-compose) | Outside task scope; no test added |
| `recipient-input.ts:203` branch 1, `:52` branch 1 | Paste guard / `parseRecipients` path; older than this task | Outside task scope; no test added |

These are the same gaps as in round 1, one line lower in `recipient-input.ts` because of the comment rewrap. Every task-changed line is covered, and no uncovered location is in the task diff.

## Unable to test

- `.taskcheck` (`scripts/check.sh`) was not run here because its Gradle parts cannot run in the helper environment. The parent must run it.
- The visual appearance of the contact icon (colour, spacing) is not asserted. The tests check only that the icon is present, that it has `aria-hidden`, and the visually hidden label. No follow-up is needed for the acceptance criteria.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.
