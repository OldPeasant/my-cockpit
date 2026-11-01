# Test Report

Date: 2026-11-01

Task: web-mail-read-delete - Mail read view: Delete button

Scope: task commit fc8846c9a952ec25979fc3ea21261f6512283636 on top of base ea78b8b060c1be0666049382d4588b1fcf04b693 (`git diff ea78b8b06..fc8846c9a -- web/`), given by the parent worker. HEAD = fc8846c9a952ec25979fc3ea21261f6512283636 on branch main. Uncommitted at measurement: only the test-writer's additions to `web/src/app/features/mail/read/mail-read-page.spec.ts` (and the untracked `reviews/web-mail-read-delete/`).

Requirements:
- `tasks/web-mail-read-delete.md` acceptance criteria and the three recorded assumptions (apply('DELETE',[id]) instead of act; origin from the router's previous navigation, replaceUrl; error toast via NotificationService)
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`
- Parent instructions (review the existing Delete tests, add only where coverage is missing)

## Test files changed

- `web/src/app/features/mail/read/mail-read-page.spec.ts`

## Tests added or updated

Count: 6 added (the parent's existing "Delete" and "back to the list it was opened from" tests were reviewed and kept unchanged; the harness helper `openFrom` now takes several prior URLs and the harness module adds `provideLocationMocks()` and a `/mail/compose` stub route).

Added:
- `Delete > does not navigate when the page was left while the delete ran` (destroyed-page guard)
- `Delete > treats a result for another id only as a failure` (no per-id result for this message)
- `Delete > does not delete with # while the next message loads` (state guard of the shortcut)
- `back to the list > returns to a folder whose id needs URL encoding` (`/mail/folders/f%20work%2Fsub` round-trips)
- `back to the list > returns to the unified Inbox when opened from another mail page` (`/mail/compose` is not a list)
- `back to the list > replaces the deleted message in the history, so Back does not open it again` (Location history after replaceUrl: Back gives the folder, then `/notes`, never `/mail/messages/{id}`)

## Requirement coverage

Focused command (F): `npm --prefix web run test:ci -- --include src/app/features/mail/read/mail-read-page.spec.ts --include src/locale/messages-de.spec.ts`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: Delete button shown once the message has loaded | `Delete > is not offered before the message has loaded`, `is not offered when the message could not be loaded`, `deletes the message with the mail action and returns to the unified Inbox` | F | Pass |
| AC1: calls the mail actions API with DELETE and [id] | `deletes the message with the mail action ...`, `the # key > deletes, as in the list`, `back to the list > returns to the folder the message was opened from` | F | Pass |
| AC1: disabled while the request runs | `is disabled while the request runs, so it cannot run twice` | F | Pass |
| AC2: on success return to the originating list (folder / unified Inbox / unknown) | `returns to the folder the message was opened from`, `returns to the unified Inbox it was opened from`, `... when opened from another page`, `... when opened directly`, `... when opened from another mail page`, `returns to a folder whose id needs URL encoding`, `keeps the origin when moving on to another message` | F | Pass |
| AC2: deleted message not reopened via history (assumption: replaceUrl) | `replaces the deleted message in the history, so Back does not open it again` | F | Pass |
| AC2: "the message is no longer shown there" | Not testable in this spec: the list reloads its data when it is created after the navigation; that list behavior belongs to web-mail-list (outside this task's touches). The navigation itself is covered above | - | Not run: list reload is outside the read view |
| AC2: on failure the view stays open with an error toast | `stays open and shows the server reason ...`, `reports a failed request with the API error ...`, `reports an unexpected failure, or a missing result, in general words`, `treats a result for another id only as a failure` | F | Pass |
| AC2: no navigation when the user left the message or page during the request | `does not leave a message the user moved to while the delete ran`, `does not navigate when the page was left while the delete ran` | F | Pass |
| AC3: `#` deletes, also with AltGr; not while typing; not other keys/chords/repeat; not before ready | `the # key > deletes, as in the list`, `also deletes when typed with AltGr`, `does nothing while typing in an input or editor`, `ignores other keys, chords and an auto-repeated #`, `does not delete with # while the next message loads`, `is not offered before the message has loaded` | F | Pass |
| AC4: i18n units translated (messages.de.xlf) | `src/locale/messages-de.spec.ts` | F | Pass |
| AC4: `cd web && npm run check` passes | lint + full unit suite run (see below); `api:check`, `e2e:typecheck` and `ng build` not run by the test-writer | `npm --prefix web run lint`, `npm --prefix web run test:ci` | Pass (partial; the parent must run the full `npm run check`) |

## Focused test execution

Command:

`npm --prefix web run test:ci -- --include src/app/features/mail/read/mail-read-page.spec.ts --include src/locale/messages-de.spec.ts`

Result:

Pass (2 files, 75 tests)

## Full test execution

Command:

`npm --prefix web run lint` and `npm --prefix web run test:ci`; also `npm --prefix web exec -- prettier --check` on the changed spec

Result:

Pass (lint: all files pass; unit: 184 files, 3881 tests passed; Prettier: clean)

## .taskcheck

Result:

Not run (`sh scripts/check.sh` includes the Gradle server and Android parts, which helpers cannot run here; the parent runs it)

## Bugs found

None. No production defect was found.

## Coverage

Status: verified (web part, read-folder specs)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured. Method: added lines from `git diff -U0 ea78b8b06..fc8846c9a` on `mail-read-page.ts` (lines 15, 25-26, 45-46, 54, 60-63, 82-83, 173-210, 336-353) intersected with executable lines (lcov `DA`) and branch locations (`coverage-final.json` branchMap) of the same run. Result: lines 28/28 (100%); branches starting on changed lines 30/31 (96.8%), the missing one is the `?? []` fallback at line 351.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-mail-read-delete; base ea78b8b060c1be0666049382d4588b1fcf04b693; HEAD fc8846c9a952ec25979fc3ea21261f6512283636; task commit fc8846c9a given by the parent and confirmed with `git diff ea78b8b06..fc8846c9a`.
- Source/test snapshot identifier (including uncommitted inputs): sha256 `mail-read-page.ts` da767e3b69423670c50e880023c230e5f190c267356e3756bb2932fb06971867, `mail-read-page.html` 8b059d6ca4794ebc181d4116fcf7db4c4a17550cc6813907b681c725f5461d55, `mail-read-page.spec.ts` (with the additions) e4682ca6f5eee316dab1d9d8ee6032028dd91a9fdbffd680b588bbaa8e1f0d7d; identical before and after the run.
- Exact included files; renamed/deleted/excluded files and reasons: included `web/src/app/features/mail/read/mail-read-page.ts`. `mail-read-page.html` is a template compiled into the component (no separate counters; exercised through the DOM tests). `web/src/locale/messages.xlf` and `messages.de.xlf` are translation data, not executable (validated by messages-de.spec.ts). The spec file is a test. No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with v8 coverage (`@vitest/coverage-v8`); `npm --prefix web run coverage -- --include 'src/app/features/mail/read/**/*.spec.ts'` from the repo root (5 spec files, 102 tests).
- Run start/completion time and exit status: started 2026-11-01 06:57:48 +0100, reports written 06:57:55 +0100; exit 0, all tests passed.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` (sha256 572c4d1405b4ce92f06f5610a3f34e973fe4caabcbc303dbadb1a7ad809a17e2), `web/coverage/web/coverage-final.json` (sha256 95481625d99847ea7613074c345d11642aed54396612dca9bd2d5603b59eecf9), `web/coverage/web/lcov.info`.
- Evidence outputs are fresh and inputs stayed unchanged: report files timestamped by this run (06:57:55); source hashes unchanged before and after.
- Final run after latest test additions and production fixes: yes (after all 6 additions; no production fixes were made).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/mail/read/mail-read-page.ts` | 187/195 (95.9%) | 117/138 (84.8%) | 42/43 (97.7%) | 158/158 (100%) |

Only one file is in the measured scope, so the aggregate equals the row above. Not measured separately: `mail-read-page.html` (compiled into the component), the two xlf files (not executable).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `mail-read-page.ts:351` (`?? []` right side) | Previous navigation URL without a primary outlet | Defensive; not reachable from a real navigation to a mail URL (the harness opened directly has no previous navigation, handled at line 342, which is covered). Left uncovered |
| `mail-read-page.ts:56` (function `MailReadPage_keydown_HostBindingHandler`, statement and branch) | Compiled host listener for `document:keydown` | v8/source-map attribution of Angular's generated host-binding function to the class line; the listener is exercised (the `#` tests call `onKeydown`, lines 206-208 covered). Tooling artifact, no test added |
| `mail-read-page.ts:124, 153, 222, 254, 256` and binary-expression fallbacks at 67, 92, 95, 99, 103, 113, 142, 148, 284, 288, 359 | Pre-existing load, image and preview code | Not changed by this task; outside scope |

## Unable to test

- "The message is no longer shown there" in the list after returning: depends on the mail list reloading on creation (web-mail-list, outside this task's touches). Not covered by this spec; a browser e2e flow would show it. Follow-up only if the parent wants e2e evidence.
- With the SpyLocation used in the harness, the Router does not follow `Location.back()` (popstate) in jsdom, so the history test asserts the Location path rather than the routed component.
- `npm run check` in full (`api:check`, `e2e:typecheck`, `ng build`) and `.taskcheck` were not run by the test-writer; the parent should run them.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.
