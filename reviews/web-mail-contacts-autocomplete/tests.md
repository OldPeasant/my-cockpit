# Test Report

Date: 2026-10-25

Task: web-mail-contacts-autocomplete - Mail compose: contacts autocomplete

Scope: base `ef381c76119c78d9a371ada7f632edec3cc2e86f` .. task commit `d68acec387f4092641115741ea04bf7c5e43630a` (HEAD, branch `main`), one task-owned commit; plus the uncommitted test-file additions listed below.

Requirements:
- `tasks/web-mail-contacts-autocomplete.md` acceptance criteria
- `SPEC.md` (mail compose: "Recipient autocomplete from Contacts + recent correspondents")
- `api/openapi.yaml` `GET /api/v1/contacts/autocomplete` (`autocompleteContacts`) contract
- `docs/ARCHITECTURE.md` "Coverage"; `AGENTS.md`
- Parent instructions (scope `web/src/app/features/mail/compose`)

## Test files changed

- `web/src/app/features/mail/compose/mail-compose.service.spec.ts`
- `web/src/app/features/mail/compose/recipient-input.spec.ts`

## Tests added or updated

Count: 7 added by the test-writer (4 service, 3 component), on top of the 4 the implementer added in the task commit (3 service, 1 component), which were reviewed and kept unchanged.

Added by the test-writer:
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
| AC2: entries without address dropped; repeated address (case-insensitive, after trimming) kept once, the first | service: `drops suggestions without address and keeps the first of a repeated address`, `trims suggested addresses, keeps a contact without name and dedupes after trimming` | F | Pass |
| AC2: empty answer / empty body | service: `has no suggestions when the autocomplete answers nothing`, `has no suggestions when the autocomplete answers an empty body` | F | Pass |
| AC2: failure is silent (no toast) and rejects so the component can clear suggestions | service: `looks up suggestions silently and rejects when the autocomplete fails`; component: `shows no suggestions when looking them up fails` (pre-existing) | F | Pass |
| AC2 (component): contact suggestions marked (icon + visually hidden "Contact:"), correspondents unmarked | component: `marks contact suggestions and adds a chosen contact as name and address only`, `announces contacts to screen readers only and hides the icon from them` | F | Pass |
| AC2 (component): chosen suggestion added as `{address, name}` only (no `contactId`), nameless as `{address}` | component: `marks contact suggestions and adds a chosen contact as name and address only`, `adds a chosen contact without name as its address only` | F | Pass |
| AC2 (component): suggestions already among the recipients hidden (case-insensitive) | component: `hides a contact suggestion whose address is already a recipient, in any case`, `asks for suggestions once typing pauses, without recipients already added` (pre-existing) | F | Pass |
| AC2: ordering (older lookup answering last) | component: `shows only the suggestions of the latest text when answers arrive out of order`, `drops a pending lookup once the typed address became a chip` (pre-existing, still pass) | F | Pass |
| AC2: `cd web && npm run check` passes | whole web suite, lint, api:check, e2e typecheck, build | `cd web && npm run check` | Pass (exit 0) |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/mail/compose/**/*.spec.ts'`

Result:

Pass - 5 test files, 126 tests passed.

## Full test execution

Command:

`cd web && npm run check` (api:check, lint, e2e:typecheck, `ng test --watch=false`, `ng build`)

Result:

Pass - exit 0; `src/app/api/generated is up to date`, `All files pass linting`, 166 test files / 3463 tests passed, build complete (pre-existing warning: initial bundle exceeds the 500 kB budget, not caused by this task). `npx prettier --check` on both edited spec files: pass.

## .taskcheck

Result:

Not run by the test-writer: `scripts/check.sh` also runs the Gradle server and Android checks, which helper sub-agents cannot run in this environment. The web part of it (`npm run check`) passed above. The parent worker must run `.taskcheck`.

## Bugs found

None.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured. Method: executable lines = lines on which a statement starts in the provider's source-mapped `coverage-final.json`, intersected with the added/modified lines of `git diff -U0 ef381c76..d68acec3`; a line counts as covered when every statement starting on it ran. Result: `mail-compose.service.ts` 10/10 (lines 117, 122-129, 133), `recipient-input.ts` 9/9 (lines 110-113, 116-118 of the inline template, 210-211); total 19/19 (100%). Changed-branch coverage is not derived from this.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-mail-contacts-autocomplete`; base `ef381c76119c78d9a371ada7f632edec3cc2e86f` (claim commit, supplied by the parent); HEAD `d68acec387f4092641115741ea04bf7c5e43630a`, the only task commit (supplied by the parent, confirmed with `git show --stat HEAD`, within the task's `touches`).
- Source/test snapshot identifier (including uncommitted inputs): HEAD `d68acec38` plus uncommitted spec changes; sha256 `mail-compose.service.ts` a2cec7ca...053a3, `recipient-input.ts` 26cfa11b...f8c, `mail-compose.service.spec.ts` 19290a3d...a02, `recipient-input.spec.ts` 70b3451a...b3a (identical before and after the coverage run).
- Exact included files; renamed/deleted/excluded files and reasons: included `web/src/app/features/mail/compose/mail-compose.service.ts`, `web/src/app/features/mail/compose/recipient-input.ts`. Excluded: the two `*.spec.ts` files (tests). No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with `@vitest/coverage-v8` ^5.0.5 (v8), `npm run coverage`, working directory `web/`, full web suite (166 files, 3463 tests).
- Run start/completion time and exit status: started 2026-10-25T22:35:01+01:00, report written 2026-10-25T21:35:41Z (22:35:41+01:00); exit 0.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` sha256 9c486a69b0087cc17e1b83e853af78ed3acaaa079cc11e506b167bcedf7b42b6; `web/coverage/web/coverage-final.json` sha256 e45f38e864e145e7d774336e1a600cc90016496405e176babe02b4bb4baeae56.
- Evidence outputs are fresh and inputs stayed unchanged: yes; Vitest rewrote `coverage/` in this run (summary mtime matches the run), source/test hashes unchanged after the run.
- Final run after latest test additions and production fixes: yes, after all test additions; no production fixes were made.

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/mail/compose/mail-compose.service.ts` | 35/35 (100%) | 25/25 (100%) | 12/12 (100%) | 30/30 (100%) |
| `web/src/app/features/mail/compose/recipient-input.ts` | 131/134 (97.76%) | 40/44 (90.91%) | 30/32 (93.75%) | 93/93 (100%) |
| Aggregate (both files, summed counters; no files excluded or unmeasured) | 166/169 (98.22%) | 65/69 (94.20%) | 42/44 (95.45%) | 123/123 (100%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `recipient-input.ts:169-170` (statements and functions of `viewChild.required(...)`) | Compiler-generated signal-query functions, pre-existing, not task code | Non-material; no test added |
| `recipient-input.ts:195` branches 0/1 (`event.clipboardData?.getData('text') ?? ''`) | Paste without clipboard data, pre-existing (web-mail-compose) | Outside task scope; no test added |
| `recipient-input.ts:202` branch 1, `:52` branch 1 | Pre-existing paste guard / `parseRecipients` path | Outside task scope; no test added |

All task-changed lines are covered; no uncovered location lies in the task diff.

## Unable to test

- `.taskcheck` (`scripts/check.sh`) was not run here (Gradle parts cannot run in the helper environment); the parent must run it.
- Visual appearance of the contact icon (colour, spacing) is not asserted; only its presence, `aria-hidden` and the visually hidden label are. No follow-up needed for the acceptance criteria.

## Summary

Tests added and all validation passed.
