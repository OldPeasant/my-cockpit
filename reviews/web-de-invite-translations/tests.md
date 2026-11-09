# Test Report

Date: 2026-11-09

Task: web-de-invite-translations - German translations for the iTIP invitation dialog (calendar.invite.*)

Scope: base = measured HEAD = 27c8e091c19dbd3470bb8a410788f5dfa9e401c6 (branch main, clean working tree).
The task has no code commits of its own. The 16 `calendar.invite.*` units were already added to
`web/src/locale/messages.xlf` and `web/src/locale/messages.de.xlf` by another task (commit
ba52ebac30edead43f5635676d4ea3e696fc71a0, "mail-load-older-ui: fix review findings"). This report
verifies that the acceptance criteria hold at HEAD.

Requirements:
- `tasks/web-de-invite-translations.md` (goal and acceptance criteria)
- `AGENTS.md` project notes (i18n units added by hand, no full extract)
- Parent instructions (verify build:de, id/placeholder consistency; no Gradle, no scripts/check.sh)

## Test files changed

- None.

## Tests added or updated

Count: 0

No automated test was added. The behavior is static translation data, and the German production
build (`npm run build:de`) is already the authoritative check: the Angular i18n compiler reports
every missing translation. A unit test that re-parses the XLIFF files would duplicate that check.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: `cd web && npm run build:de` reports no missing translation | German production build | `npm run build:de` (in `web/`) | Pass: "Application bundle generation complete", output in `web/dist/web`, zero "No translation found" messages |
| Every `@@calendar.invite.*` id in code has a source unit and a German target with matching placeholders | Ad-hoc XLIFF consistency script (scratch, `.tmp/check_invite.py`, not committed) | `python3 -I .tmp/check_invite.py web/src/locale/messages.xlf web/src/locale/messages.de.xlf .tmp/invite_ids.txt` | Pass: 16 ids checked, 0 problems |
| AC2: `cd web && npm run check` and `sh scripts/check.sh` pass | Project check | Not run by test-writer | Not run: the parent worker runs `scripts/check.sh` (which includes the web check); running it in parallel causes Gradle build-dir clashes |

## Focused test execution

Command:

`grep -rhoE '@@calendar\.invite[A-Za-z0-9_.-]*' web/src/app`

Result:

16 distinct ids, each used once (all in `web/src/app/features/calendar`): account, accountsFailed,
cancel, cancelSent, failed, loading, method, noAccounts, open, rejected, request, requestSent,
saveFirst, send, title, to.

Command:

`python3 -I .tmp/check_invite.py web/src/locale/messages.xlf web/src/locale/messages.de.xlf .tmp/invite_ids.txt`

Result:

Pass. For each of the 16 ids:
- a trans-unit exists in `messages.xlf` and in `messages.de.xlf`;
- the German unit has a non-empty `target` with `state="translated"`;
- the `x` placeholder ids match between the source in `messages.xlf`, the source in
  `messages.de.xlf` and the German target (`calendar.invite.cancelSent` and
  `calendar.invite.requestSent` carry `x id="count"` in all three; the others have none);
- the source text in `messages.de.xlf` equals the source in `messages.xlf`.

No `calendar.invite.*` unit exists in either file without a matching id in the code.

Command:

`npm run build:de` (working directory `web/`)

Result:

Pass. Build completed in about 26 s. No "No translation found" error or warning. The only warning is
the pre-existing initial bundle budget warning (692.04 kB against a 500 kB budget), unrelated to
this task.

## Full test execution

Command:

`sh scripts/check.sh` / `npm run check`

Result:

Not run by the test-writer, on parent instruction (the parent runs `scripts/check.sh` itself;
parallel Gradle runs clash on the build directory).

## .taskcheck

Result:

Not run (parent worker runs it; see above).

## Bugs found

None.

## Coverage

Status: not applicable. The task changes no production code: base and measured HEAD are the same
commit (27c8e091c19dbd3470bb8a410788f5dfa9e401c6), the task has no code commits, and the only
relevant files are XLIFF translation data, which contain no executable code and are not
instrumented by the coverage tooling. Task-scoped coverage is therefore N/A (not 100%).

Metric: whole-file coverage of task-changed production files: N/A, no task-changed production files.
Changed-line coverage: not measured (no changed lines).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-de-invite-translations; base 27c8e091c19dbd3470bb8a410788f5dfa9e401c6; HEAD identical; no task-owned code commits (parent-supplied, confirmed by clean `git status`).
- Source/test snapshot identifier (including uncommitted inputs): clean tree at HEAD; sha256 `messages.xlf` 3777a2a45f97ab592dce754523f11fe9a229635928803d109b83085cf1af954e, `messages.de.xlf` 098aec737dc3254ef3486743651926e2ec1ecbba92927a0ce4e14c149da38c44.
- Exact included files; renamed/deleted/excluded files and reasons: none included; the XLIFF files are excluded as non-executable translation data.
- Provider/version, command, working directory, and test selection: not run (nothing to measure).
- Run start/completion time and exit status: not applicable.
- Generated report paths and report hashes/run identifier: none.
- Evidence outputs are fresh and inputs stayed unchanged: the build ran at 2026-11-09T00:42Z on the clean HEAD tree.
- Final run after latest test additions and production fixes: yes (no additions or fixes).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| (none) | N/A | N/A | N/A | N/A |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| (none) | Translation completeness | Verified by `npm run build:de` and the XLIFF consistency script |

## Unable to test

- AC2 (`npm run check`, `sh scripts/check.sh`): not run here on parent instruction; the parent
  worker must run and record it.

## Summary

No additional tests were necessary for the reviewed scope. AC1 holds: `npm run build:de` reports no
missing translation, and all 16 `calendar.invite.*` ids used in the calendar feature have a source
unit and a German target with matching placeholders. No production defects found.
