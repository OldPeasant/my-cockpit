# Test Report

Date: 2026-11-08

Task: mail-load-older-ui: Load mail older than the sync window (web + Android)

Scope: re-run on the final code after the fixes for CR-1..3, TW-1, TW-2. The parent supplied base
`73a3b3fc43a9` and commits `81823764b` (web), `1b49efa14` (Android), `711fe21f3` (tests), `f430e277e`
and `4e4ff409e` (fixes), HEAD `4e4ff409e`. While this run was in progress, the parent rebased the task
onto `08d542633bd2af4fb60e030c3d1a4db467b3804a` (= `git merge-base HEAD origin/main`). The task commits
are now `547732306` (web), `b9f763393` (Android), `563f98a98` (tests), `a4b310314` and `2298b20a8`
(fixes). Measured HEAD is `2298b20a8625710c0dc05355d957f36853454914`.
`git diff 73a3b3fc43a9..4e4ff409e -- web/src android/app/src | git patch-id` and
`git diff 08d542633..HEAD -- web/src android/app/src | git patch-id` both give `0af390ef2329…`, so the
tested code is identical to the code the parent named.

Focus: the web changes since the last report (`mail-list-store.ts` `loadOlder`/`showUpTo`/`resetOlder`/
`olderNote`/`actionsSettled`, `mail-list-page.html`, `web/src/locale/*.xlf`). Android production code
and Android tests have not changed since the previous run (`git diff b9f763393 HEAD -- android` only
shows the three test files that run already added and measured). The Android results below are
**reused from the previous run** and were not measured again, except that `sh scripts/check.sh` ran the
Android ktlint, unit tests and lint again in this run.

Requirements:
- `tasks/mail-load-older-ui.md` acceptance criteria and its recorded assumptions
- SPEC.md §7.2 (load older than the sync window)
- `reviews/mail-load-older-ui/review.md` (CR-1..3) and `fixes.md`
- `docs/ARCHITECTURE.md` "Coverage" and `docs/QUALITY.md`

## Test files changed

- `web/src/app/features/mail/list/mail-list-store.spec.ts` (uncommitted, 3 tests added in this run)

## Tests added or updated

Count: 3 (web store). Added to `describe('load older from server (SPEC.md §7.2)')`:
- `drops the list it read when the list is reloaded while it reads it`: covers the `seq !== loadSeq`
  return after `fetchUpTo` in `showUpTo` (`mail-list-store.ts:309`), which no test reached before.
  The list read for load-older answers after a reload. It must not replace the reloaded list, and the
  progress, error and note must all be cleared.
- `puts the cursor on the first message when its message is gone after loading older`: covers
  `mail-list-store.ts:318` when the cursor's message is no longer in the list read again.
- `puts the cursor on the first newly cached message of an empty folder`: covers the `!cursor` side of
  `mail-list-store.ts:318` (the empty folder had no cursor) and checks the read limit (0 + 2).

The fixer's tests for CR-1/TW-1, CR-2 and CR-3 were reviewed. They control the order of answers
explicitly with deferred promises, assert observable behavior, and stay in place.

## Requirement coverage

W = `npm --prefix web run coverage` (all 193 web spec files, 4553 tests) and `sh scripts/check.sh`.
F = `npm --prefix web run test:ci -- --include src/app/features/mail/list/mail-list-store.spec.ts`.
A1 = the previous run's `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.list.MailLoadOlder*'` (reused). In this run, `sh scripts/check.sh` ran the whole Android unit suite again.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 web: the button at the end of a folder list calls the endpoint, shows the progress, then the newly cached messages | store: `is offered at the end of a fully loaded folder only`, `is offered for an empty folder`, `is not offered for search results, while loading, or after a failed load`, `calls load-older for the shown folder, then shows the newly cached messages at the end`, `reads more than one page when more messages were cached than one page holds`, `keeps infinite scroll going when the server has more cached mail than was asked for`, `puts the cursor on the first message when its message is gone after loading older`, `puts the cursor on the first newly cached message of an empty folder`; service and page load-older tests | F, W | Pass |
| AC1 web: ordering and concurrency (CR-1, CR-2, TW-1) | `ends the progress when the list is reloaded or restored while it loads older`, `does not undo an action that ran while it read the list; it reads the list again after it`, `ends when the list is reloaded while it waits for an action`, `reads the list again when an action changed it while it was read`, `drops the list it read when the list is reloaded while it reads it` | F, W | Pass |
| AC2 web: errors (connection, nothing older) shown non-blocking; "nothing older" is a neutral note, not a failure (CR-3) | store: `says so when the server has nothing older, without reloading the list`, `shows a generic message when the connection fails`, retry/clear tests; page: the nothing-older test (`p.note` with `role="status"`, no `.error`), the unreachable-server test (`.error` with the "Nothing older loaded:" prefix), rows kept, button enabled again, no global error | F, W | Pass |
| AC3 web: new strings in English and German (and TW-2: the calendar.invite.* units, the capture.save source) | `web/src/locale/messages-de.spec.ts`; `ng build` in `npm run check` (part of `scripts/check.sh`) | W | Pass |
| AC1-AC3 Android | `MailLoadOlderRepositoryTest` (8), `MailLoadOlderViewModelTest` (11), `MailLoadOlderScreenTest` (8), `MailLoadOlderGermanTest` (4) | A1 (reused); `sh scripts/check.sh` (Android step ran again in this run) | Pass |
| AC4: unit tests cover the new behavior on each changed part; `sh scripts/check.sh` passes | all of the above | `sh scripts/check.sh` | Pass |

## Focused test execution

Command:

`npm --prefix web run test:ci -- --include src/app/features/mail/list/mail-list-store.spec.ts`

Result:

Pass: 1 file, 105 tests.

## Full test execution

Command:

`npm --prefix web run coverage` (all web specs). The web `npm run check` (api:check, lint,
e2e:typecheck, test:ci, ng build) ran as part of `sh scripts/check.sh`.

Result:

Pass: 193 files, 4553 tests. `ng build` prints only the existing warning that the initial bundle
exceeds its budget.

## .taskcheck

Result:

Pass. `sh scripts/check.sh` ran after the new tests were added and printed `== all checks passed`.
It covers tfp, the server, the web check and the Android step (ktlint, unit tests, lint).

## Bugs found

None in this run. The earlier findings TW-1 (loadingOlder stuck after load/restore) and TW-2 (German
catalog) are fixed. Their regression tests pass.

## Coverage

Status: web verified (fresh run on the final code including the new tests). Android reused from the
previous run (production code unchanged since then, see Scope); not measured again.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: web measured: statements whose start line is an added/modified line in
`git diff -U0 08d542633 HEAD`, using `statementMap` and the hit counts in `coverage-final.json`:
`mail-list-store.ts` 73/73, `mail.service.ts` 3/3. Changed-branch coverage was not measured. Android
changed-line coverage was not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `mail-load-older-ui`. Parent-supplied base `73a3b3fc43a9` and commits, rebased during the run to base `08d542633bd2…` and HEAD `2298b20a8625710c0dc05355d957f36853454914`. The patch-ids are equal (see Scope). File scope = `git diff --name-only 08d542633..HEAD -- web/src android/app/src/main`, without specs.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `2298b20a8` plus the uncommitted `web/src/app/features/mail/list/mail-list-store.spec.ts`, sha256 `18b7774c12d780fec303051f7808b3e472d435344da6571abc5b1870e2cdf769`. `mail-list-store.ts` sha256 `a7b3df4a2d3f…62796`, the same before and after the run.
- Exact included files; renamed/deleted/excluded files and reasons: web included `web/src/app/features/mail/list/mail-list-store.ts` and `mail.service.ts`. Excluded: `mail-list-page.html`, because v8 does not instrument Angular templates; its behavior is checked by the `mail-list-page.spec.ts` DOM tests. Also excluded: `mail-testing.ts` (test support code, not production) and `web/src/locale/messages{,.de}.xlf` (not executable; checked by `messages-de.spec.ts` and `ng build`). Android included `MailApi.kt`, `MailRepository.kt`, `MailListViewModel.kt`, `MailScreen.kt`; `res/values{,-de}/strings_mail.xml` are resources and not instrumented. Nothing was renamed or deleted.
- Provider/version, command, working directory, and test selection: web = Vitest v5.0.5 with `@vitest/coverage-v8`, `npm --prefix web run coverage` from the repository root (it runs in `web/`), all specs. Android (reused) = JaCoCo via AGP, `./gradlew createDebugUnitTestCoverageReport` in `android/`, all debug unit tests.
- Run start/completion time and exit status: web started 2026-11-08T22:08:52+01:00 and took 150.7 s; exit 0, 4553 passed. An earlier web run (22:05:08, before the new tests, 4550 passed) is superseded. Android (reused) completed 2026-11-08T20:45:17Z with exit 0.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` sha256 `20b76c17922a05999d112252ac483ece42620c6f81ab28af29a41feda1d8b3fe`, `web/coverage/web/lcov.info` sha256 `93aa4581b545d025a3a6416f1ec3a51e4be95bbbaea2a090f4b7cab368d4d18a`. Android (reused): `android/app/build/reports/coverage/test/debug/report.xml` sha256 `d6409daf27ed…5810f9d`.
- Evidence outputs are fresh and inputs stayed unchanged: yes for web. Vitest rewrote `coverage-final.json`: its hash changed from `0657359b…` (the earlier run) to `20b76c17…`. The test count, 4553, includes the 3 new tests. The input hashes are the same before and after the run.
- Final run after latest test additions and production fixes: yes (web). For Android, no production change happened after its measurement.

Values below use covered/total (percentage). Web: statements and functions come from `coverage-final.json`, lines and branches from `lcov.info` (LF/LH, BRF/BRH). Android: JaCoCo counters, where "Statements" = instructions (reused).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/mail/list/mail-list-store.ts` | 383/383 (100%) | 199/212 (93.9%) | 85/85 (100%) | 310/310 (100%) |
| `web/src/app/features/mail/list/mail.service.ts` | 30/30 (100%) | 33/34 (97.1%) | 16/16 (100%) | 26/26 (100%) |
| **Web aggregate (summed, 2 files)** | 413/413 (100%) | 232/246 (94.3%) | 101/101 (100%) | 336/336 (100%) |
| `android/.../mail/list/MailApi.kt` (reused) | 669/1139 (58.7%) | N/A (0/0) | 84/119 (70.6%) | 93/131 (71.0%) |
| `android/.../mail/list/MailRepository.kt` (reused) | 1052/1061 (99.2%) | 81/88 (92.0%) | 31/31 (100%) | 114/114 (100%) |
| `android/.../mail/list/MailListViewModel.kt` (reused) | 4637/4701 (98.6%) | 382/402 (95.0%) | 116/116 (100%) | 447/447 (100%) |
| `android/.../mail/list/MailScreen.kt` (reused) | 8332/8513 (97.9%) | 299/337 (88.7%) | 172/202 (85.1%) | 669/677 (98.8%) |
| **Android aggregate (summed, 4 files, reused)** | 14690/15414 (95.3%) | 762/827 (92.1%) | 403/468 (86.1%) | 1323/1369 (96.6%) |

Excluded or unmeasured, next to the aggregates: `mail-list-page.html` (template, not instrumented),
`messages.xlf`, `messages.de.xlf` and `strings_mail.xml` (x2) (not executable). Web and Android
aggregates are kept separate (different providers).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `mail-list-store.ts:309` (return after a reload during the list read) | Ordering: a stale read must not replace a reloaded list | Test added: `drops the list it read when the list is reloaded while it reads it` (now covered) |
| `mail-list-store.ts:318` (cursor reset, both `!cursor` and "cursor gone") | Cursor stays valid after the list is read again | Tests added: `puts the cursor on the first message when …`, `… of an empty folder` (now covered) |
| `mail-list-store.ts:318` `?? null` side (cursor gone and the list read again is empty) | Defensive: `loaded > 0` but the server returns no items | Not added; non-material, the result would be the same null cursor as an empty list |
| `mail-list-store.ts:287` `?? []` side (`items()` null) | Defensive: `canLoadOlder` requires `items() !== null` | Not reachable through the public API; no test |
| `mail-list-store.ts:357,359,394,408,431,455,491,542,562,564,641` | Code that this task did not write (fetchUpTo/refresh/actions) | Out of scope |
| `mail.service.ts:179` | `thread()`, not part of this task | Out of scope |
| Android gaps | See the previous run: generated DTO/serializer members in `MailApi.kt` and older non-task screen code | Unchanged; non-material for this task |

## Unable to test

- `mail-list-page.html` cannot be measured for coverage (Angular templates are not instrumented by v8). Its load-older UI is checked by the DOM tests in `mail-list-page.spec.ts`. No follow-up needed.
- Android coverage was not measured again in this session because of the time limit. It is reused because the Android production code has not changed (see Scope). If the parent needs fresh Android numbers, run `cd android && ./gradlew createDebugUnitTestCoverageReport`.

## Summary

Tests added and all validation passed. Three web store tests close the remaining meaningful gaps
(a stale list read after a reload, and the cursor after the list is read again). The web suite,
`npm run check` and `sh scripts/check.sh` pass. No production defects were found.
