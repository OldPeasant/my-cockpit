# Test Report

Date: 2026-11-09

Task: gtd-links-everywhere - Linked items on projects and files; follow-up by mail (web + Android)

Scope: `git diff origin/main HEAD` excluding `tasks/` and `reviews/`. origin/main = ce03deae60ce4cf8fa0a4e33c08789101bea00b2 (the claim commit, also `git merge-base HEAD origin/main`). The code was measured at HEAD 3f534445dfe04d71b7f0956e63e44d293c5f145a (final code after fixes, see `reviews/gtd-links-everywhere/fixes.md`). During the run HEAD moved to 262d65e8eddb7938a08f1ad53d8ab63ce704374d. That commit only adds `tasks/and-waiting-contact-mail.md` (checked with `git diff --stat 3f534445d HEAD`), so the production code is the same. This report replaces the earlier, now stale tests.md, which described b2068fad9 before TW-1..TW-3, CR-2 and CR-3 were fixed.

Requirements:
- tasks/gtd-links-everywhere.md: acceptance criteria and the recorded assumptions (web Draft email opens `/mail/compose?to=&subject=`; To is the contact's first email, else an address in the waiting-for text, else empty. Android opens a mailto `ACTION_SENDTO` with an address from the waiting-for text)
- SPEC.md §4.3 (linked-items panel on every detail view) and §5.3 (waiting follow-up)
- docs/ARCHITECTURE.md "Coverage" and "Code conventions"
- reviews/gtd-links-everywhere/fixes.md (TW-1..TW-3, CR-2, CR-3 fixes)

## Test files changed

- `web/src/app/features/gtd/lists/list-views.spec.ts` (this run: `emailIn` punctuation cases)
- `web/src/app/features/files/preview/preview-dialog.spec.ts` (this run: paging with the links panel open; Prettier-formatted)
- `web/src/app/features/mail/compose/mail-compose-page.spec.ts` (this run: prefill rendering, reply wins over `?to=`/`?subject=`; Prettier-formatted)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/lists/WaitingDraftEmailTest.kt` (new, this run)
- Already committed in the task by earlier runs and the fixer, re-assessed here and unchanged: `web/.../gtd/lists/list-pages.spec.ts` (Waiting > Draft email, including the CR-3 regression tests), `web/.../gtd/projects/projects-states.spec.ts`, `android/.../files/FilePreviewLinksTest.kt`, `android/.../gtd/projects/ProjectLinksToggleTest.kt`, `android/.../gtd/lists/ListScreensTest.kt`

## Tests added or updated

Count: 14 (6 web, 8 Android). No existing test was changed, weakened or skipped.

- `list-views.spec.ts` > emailIn > "finds the bare address in {text}, without surrounding punctuation" (3 cases: `Anna (anna@example.test)`, `Anna [anna@example.test]`, `Reply from anna@example.test.`). **All 3 fail**: TW-4 and TW-5.
- `preview-dialog.spec.ts` > linked items > "keeps the panel open when paging and shows the next file's links". After stepping, the panel is still open and every links request is for the new file.
- `mail-compose-page.spec.ts` > "shows the ?to= address as a recipient chip and ?subject= in the subject field"
- `mail-compose-page.spec.ts` > "lets a reply keep its own recipients and subject over ?to= and ?subject="
- `WaitingDraftEmailTest` (Android):
  - "the address is found in common name-and-address forms" (angle brackets, parentheses, square brackets, quoted list, plus-addressing)
  - "a sentence ending after the address does not add its full stop to it". **Fails**: TW-5.
  - "no address is found in a name, a host without a domain, or nothing"
  - "the subject is encoded, so a question mark or hash in the title stays in it"
  - "without an address the mail has no recipient, only the subject" (no `EXTRA_EMAIL`)
  - "a plus in the address survives as a recipient"
  - "the draft email button names its task for screen readers and drafts that task"
  - "in German, draft email is German"

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Web project detail shows the linked-items panel | `projects-states.spec.ts` "shows the linked-items panel of the project (SPEC.md §4.3)", the panel present in "shows an error when the tasks cannot be loaded, and retries" | `npm run coverage` (web/, full suite) | Pass |
| Android project detail shows the linked-items panel | `ProjectLinksToggleTest` (5) | `./gradlew ktlintCheck testDebugUnitTest --tests ...ProjectLinksToggleTest ...` (android/) | Pass. Only the toggle is tested: rendering the panel needs Hilt, see "Unable to test" |
| Android project layout still intact after the toggle (TW-2 fix) | `ProjectsScreenTest`, `ProjectTaskListTest`, `FabClearanceTest` | `./gradlew testDebugUnitTest lintDebug --continue` (android/, full) | Pass |
| Web file preview shows the linked-items panel | `preview-dialog.spec.ts` > linked items (3, 1 new) | `npm run coverage` (web/) | Pass |
| Android file preview shows the linked-items panel | `FilePreviewLinksTest` (5) | focused Android command | Pass |
| Web waiting "Draft email": To from the contact, else the waiting-for text, else empty; subject from the task; no navigation after leaving or on a stale click (CR-3) | `list-pages.spec.ts` > Waiting For > Draft email (6), `list-views.spec.ts` followUpMail (3), emailIn (existing 2 + 5) | `npm run coverage` (web/) | Pass |
| Web: the address taken from the waiting-for text has no surrounding punctuation | `list-views.spec.ts` emailIn "finds the bare address in ..." (3, new) | `npm run coverage` (web/) | **Fail**: TW-4, TW-5 |
| Web compose `?to=`/`?subject=` prefill | `mail-compose-page.spec.ts` prefill (4, 2 new) | `npm run coverage` (web/) | Pass |
| Android waiting "Draft email" (mailto, address from the waiting-for text, subject from the task) | `ListScreensTest` (2), `WaitingDraftEmailTest` (7 of 8 pass) | focused Android command | Pass, except **Fail** on the trailing full stop (TW-5) |
| New strings in English and German | German tests in `ProjectLinksToggleTest`, `FilePreviewLinksTest`, `WaitingDraftEmailTest`; web de targets checked in `messages.de.xlf` (3 units) | focused Android command; web build not rerun (the earlier `npm run check` by the fixer built de at the same production code) | Pass |
| Unit tests cover the new behavior; `sh scripts/check.sh` passes | everything above | not run as one command | Not run: it would fail on the new failing tests (TW-4, TW-5). The parts were run separately, see below |

## Focused test execution

Commands:

1. `npx ng test --watch=false --include src/app/features/gtd/lists/list-views.spec.ts --include src/app/features/files/preview/preview-dialog.spec.ts` (web/)
2. `npx ng test --watch=false --include src/app/features/mail/compose/mail-compose-page.spec.ts` (web/)
3. `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.lists.WaitingDraftEmailTest' --tests 'ch.sonensei.cockpit.gtd.lists.ListScreensTest' --tests 'ch.sonensei.cockpit.files.FilePreviewLinksTest' --tests 'ch.sonensei.cockpit.gtd.projects.ProjectLinksToggleTest' --continue` (android/), then `./gradlew ktlintCheck`

Result:

Fail, as expected from the new defect tests:
1. 46 passed, 3 failed (the 3 new emailIn punctuation cases: TW-4, TW-5)
2. 75 passed
3. ktlint passed. 33 of 34 tests passed (FilePreviewLinksTest 5/5, ListScreensTest 16/16, ProjectLinksToggleTest 5/5, WaitingDraftEmailTest 7/8). The 1 failure is TW-5.

## Full test execution

Commands:

1. `npm run lint` (web/): "All files pass linting."
2. `npm run coverage` (web/, full suite, started 09:21:43): 196 files, 4795 tests, 3 failed (the TW-4/TW-5 cases), 4792 passed. Exit nonzero, no coverage report written.
3. `./gradlew testDebugUnitTest lintDebug --continue` (android/, ended about 09:30): 5390 tests, 1 failed (TW-5). lintDebug: 0 errors, 0 warnings, 2 hints in files outside the task.

Result:

Fail, only on the new defect tests (3 web, 1 Android). All other existing and new tests pass. The earlier TW-1/TW-2 regressions (ListScreensTest, ProjectsScreenTest, ProjectTaskListTest, FabClearanceTest) now pass, and lint is clean (TW-3 fixed). `npm run check` steps other than lint and tests (api:check, e2e typecheck, `ng build`) were not rerun, because no production or e2e file changed in this run.

## .taskcheck

Result:

Not run as one command. It would fail on the new legitimate failing tests TW-4/TW-5 (web `ng test` and Android `testDebugUnitTest`). All its parts except the server were run separately above. The server is not touched by this task.

## Bugs found

### Web Draft email keeps parentheses or brackets around the address

Finding ID:

`TW-4`

Test:

`list-views.spec.ts` > emailIn > "finds the bare address in \"Anna (anna@example.test)\", without surrounding punctuation" and the same for "Anna [anna@example.test]"

Production source:

`web/src/app/features/gtd/lists/list-views.ts:68-69` (`emailIn` regex: its character classes exclude whitespace, angle brackets, quotes, comma and semicolon, but not parentheses or square brackets)

Requirement:

AC 3 and the task assumption: To is "an address found in waitingForText". This is the same defect as Android TW-1, which was fixed only on Android (its regex now also excludes `()[]`). The web and Android clients should give the same result for the same waiting-for text.

Expected behavior:

`emailIn('Anna (anna@example.test)') === 'anna@example.test'` (the same for square brackets), so compose opens with a valid To.

Actual behavior:

Returns `(anna@example.test)` and `[anna@example.test]`. Draft email navigates to `/mail/compose?to=(anna@example.test)`. The compose page then treats it as an invalid address and leaves it out of the saved draft.

Severity:

Medium

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

### The address keeps a full stop that ends the sentence (web and Android)

Finding ID:

`TW-5`

Test:

Web: `list-views.spec.ts` > emailIn > "finds the bare address in \"Reply from anna@example.test.\", without surrounding punctuation". Android: `WaitingDraftEmailTest` > "a sentence ending after the address does not add its full stop to it"

Production source:

`web/src/app/features/gtd/lists/list-views.ts:68-69`; `android/app/src/main/java/ch/sonensei/cockpit/gtd/lists/WaitingScreen.kt:115-118` (`EMAIL` / `emailIn`)

Requirement:

AC 3: Draft email prefills To from the address in the waiting-for text. A free-text field such as "Reply from anna@example.test." is a natural way to write it.

Expected behavior:

`anna@example.test` (no trailing `.`).

Actual behavior:

Both clients return `anna@example.test.`. The last character class allows `.`, and the match is greedy (Android: expected `anna@example.org` but was `anna@example.org.`). The mail app or compose page gets an invalid To.

Severity:

Low

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

## Coverage

Status: partial (diagnostic). The required coverage commands fail on the legitimate failing tests TW-4/TW-5. The web run wrote no report on failure. The numbers below come from fresh diagnostic runs that leave out exactly those failing cases. Those cases only call the pure `emailIn` function, which other passing tests cover completely, so the numbers are not inflated. They are still not a passing validation run.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (the tooling has no changed-line mode). Uncovered task lines were mapped by hand, see the gap table.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: gtd-links-everywhere. Base ce03deae60ce4cf8fa0a4e33c08789101bea00b2 (origin/main = merge-base). Production code measured at 3f534445dfe04d71b7f0956e63e44d293c5f145a. Later HEAD 262d65e8e changes only `tasks/`. Scope given by the parent as `git diff origin/main HEAD` without `tasks/` and `reviews/`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD above + working-tree test changes, sha256: `preview-dialog.spec.ts` 379aad178e38b29c, `list-views.spec.ts` d881f4e88c2947d2, `mail-compose-page.spec.ts` c4b84c60f5be543a, `WaitingDraftEmailTest.kt` 3ab4266b57404a59. Hashed before and after the coverage runs: unchanged. (`reviews/gtd-links-everywhere/review.md` was also modified in the tree by another helper, not by this run.)
- Exact included files: web `src/app/features/files/preview/preview-dialog.ts`, `features/gtd/lists/list-views.ts`, `features/gtd/lists/waiting-page.ts`, `features/gtd/projects/project-detail-page.ts`, `features/mail/compose/mail-compose-page.ts`; Android `files/FilePreviewView.kt`, `files/FilesScreen.kt`, `gtd/lists/WaitingScreen.kt`, `gtd/projects/ProjectsScreen.kt`. Excluded (not executable): web `.html`/`.scss` (counted through their components), `web/src/locale/*.xlf`, Android `res/values*/strings_*.xml`. No renamed or deleted files. No file is missing from either report.
- Provider/version, command, working directory, and test selection:
  - Web: Vitest 5.0.5 + @vitest/coverage-v8, in `web/`: `npm run coverage -- --filter '^(?!.*without surrounding punctuation)'` (full suite except the 3 failing TW-4/TW-5 cases)
  - Android: JaCoCo via AGP `enableUnitTestCoverage`, in `android/`: `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.files.*' --tests 'ch.sonensei.cockpit.gtd.projects.*' --tests 'ch.sonensei.cockpit.gtd.lists.ListScreensTest' --tests 'ch.sonensei.cockpit.gtd.lists.ListRoutesTest' --tests '*FabClearanceTest' --tests '...WaitingDraftEmailTest.the *' --tests '...WaitingDraftEmailTest.no address*' --tests '...WaitingDraftEmailTest.without an address*' --tests '...WaitingDraftEmailTest.a plus*' --tests '...WaitingDraftEmailTest.in German*' createDebugUnitTestCoverageReport` (25 classes, 504 tests, all of `WaitingDraftEmailTest` except the TW-5 case)
- Run start/completion time and exit status: web ended 09:23:44, exit 0 (diagnostic selection). Android ended 09:24:56, exit 0 (BUILD SUCCESSFUL, 504 tests, 0 failures). The unfiltered web `npm run coverage` (09:21:43) exited nonzero with no report.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` (sha256 prefix d3184e2002eb5a92, written 09:23:44, `coverage/web/` did not exist before this run); `android/app/build/reports/coverage/test/debug/report.xml` (sha256 prefix 999e32d037a61b0e, written 09:24:56)
- Evidence outputs are fresh and inputs stayed unchanged: yes. Both reports were written by these runs, and the snapshot hashes did not change.
- Final run after latest test additions and production fixes: yes. It covers the code after TW-1..TW-3 and CR-2/CR-3. It is stale once TW-4/TW-5 are fixed.

Values below use covered/total (percentage). Android "Statements" are JaCoCo INSTRUCTION counters and "Functions" are METHOD counters.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/.../files/preview/preview-dialog.ts` | 63/63 (100%) | 36/39 (92.3%) | 20/20 (100%) | 45/45 (100%) |
| `web/.../gtd/lists/list-views.ts` | 47/47 (100%) | 26/26 (100%) | 16/16 (100%) | 36/36 (100%) |
| `web/.../gtd/lists/waiting-page.ts` | 32/32 (100%) | 19/21 (90.5%) | 12/12 (100%) | 24/24 (100%) |
| `web/.../gtd/projects/project-detail-page.ts` | 188/190 (98.9%) | 88/97 (90.7%) | 48/48 (100%) | 164/164 (100%) |
| `web/.../mail/compose/mail-compose-page.ts` | 291/294 (99.0%) | 143/161 (88.8%) | 84/84 (100%) | 248/248 (100%) |
| Web aggregate (summed, 5 of 5 files) | 621/626 (99.2%) | 312/344 (90.7%) | 180/180 (100%) | 517/517 (100%) |
| `android/.../files/FilePreviewView.kt` | 3730/3762 (99.1%) | 100/106 (94.3%) | 73/73 (100%) | 287/289 (99.3%) |
| `android/.../files/FilesScreen.kt` | 8060/8416 (95.8%) | 296/337 (87.8%) | 160/194 (82.5%) | 613/627 (97.8%) |
| `android/.../gtd/projects/ProjectsScreen.kt` | 4214/4464 (94.4%) | 161/182 (88.5%) | 82/112 (73.2%) | 351/357 (98.3%) |
| `android/.../gtd/lists/WaitingScreen.kt` | 999/1042 (95.9%) | 70/78 (89.7%) | 18/20 (90.0%) | 80/84 (95.2%) |
| Android aggregate (summed, 4 of 4 files) | 17003/17684 (96.1%) | 627/703 (89.2%) | 333/399 (83.5%) | 1331/1357 (98.1%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `ProjectsScreen.kt:384-385, 436-437` | Opening a linked item from the project panel (SearchItemScreen replaces the detail, Back returns; the CR-2 state kept) | Justified gap. `LinkedItemsSection` and `SearchItemScreen` need Hilt, the project detail has no panel slot, and there is no Hilt test activity |
| `FilesScreen.kt:178-180, 222-225` | FilesRoute: a linked item opened from the preview replaces the files; the `rememberSaveableStateHolder` (CR-2); the real `LinkedItemsSection(FILE)` wiring | Justified gap, Hilt route only. The toggle and slot are covered by `FilePreviewLinksTest` |
| `WaitingScreen.kt:58-61` | WaitingRoute's draftMail: launch the intent, or show the "no mail app" toast | Justified gap, Hilt route only. The intent is covered by `ListScreensTest` and `WaitingDraftEmailTest` |
| `FilePreviewView.kt:124, 143` (partial branches) | `showLinks` true without a panel | Unreachable: the toggle is hidden without a panel |
| `waiting-page.ts:61` (`?? 0`), `:33` (constructor `if`, compiler artifact); `preview-dialog.ts:61, 65, 73` (no file selected) | Pre-existing or defensive branches | Not task behavior, not chased |
| `WaitingScreen.kt:115-118`, `list-views.ts:68-69` | emailIn with punctuation around the address | Lines covered, but the behavior is wrong: TW-4, TW-5 |
| Remaining pre-existing gaps in `FilesScreen.kt`, `ProjectsScreen.kt`, `project-detail-page.ts`, `mail-compose-page.ts` | Code the task did not change | Outside the task's change |

## Unable to test

- Android project detail: the linked-items panel content and opening a linked item, plus the CR-2 state kept after returning, on both the project detail and the files route. `LinkedItemsSection`/`SearchItemScreen` are Hilt-only, the project detail has no `linksPanel` slot, and there is no Hilt test activity. Optional follow-up: a slot like `FilesContent.linksPanel`.
- Android `WaitingRoute` "no mail app" toast: Hilt route only.
- Web project/file panel content: covered through the shared `app-linked-items` (its own spec). Here only that it renders with the right item ref.
- `sh scripts/check.sh` as one command: not run, it would fail on TW-4/TW-5.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion. The earlier findings TW-1..TW-3 are fixed (their tests pass, Android lint clean), and every acceptance criterion has passing tests. The new tests found that the web `emailIn` still keeps parentheses or brackets around the address (TW-4, Medium, the web side of TW-1), and that both clients keep a sentence's trailing full stop (TW-5, Low). 3 web tests and 1 Android test fail until these are fixed. After the fix, rerun `npm run check`, the Android focused tests and fresh coverage.
