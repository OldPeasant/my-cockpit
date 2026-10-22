# Code Review

Date: 2026-10-22

Task: and-mail-compose - Android mail compose

Scope: committed task changes `git diff e6a9288b5f2fb4c5cafea94705d0b44da12906a7..d3bf0855aefb768bcab903281ca79191ccca0cc9 -- android` (task commits 6c0deea9f, 3883e3d12, 7b84968c7, cd9c21b80, a372d7956, f277d788c, d3bf0855a). This is the final round. It focuses on the test-only commit d3bf0855a (new `MailComposeOverwriteLeaveEdgeCasesTest.kt` and one new test in `MailComposeStatesScreenTest.kt`). It also checks that the production code is unchanged since the previous review.

Requirements:
- tasks/and-mail-compose.md acceptance criteria and the six recorded assumptions in its Notes
- the previous review (CR-1 P3, accepted in the task Notes)
- docs/QUALITY.md (report contract, freshness)
- AGENTS.md "Project notes"

Git range:
- Branch: main
- HEAD: 3f1bc027cac63147cf51487d733c4733e3b64736 (a merge of main on top of the task; `git diff d3bf0855a HEAD -- android` is empty)
- Base/start: e6a9288b5f2fb4c5cafea94705d0b44da12906a7
- Reviewed revision: d3bf0855aefb768bcab903281ca79191ccca0cc9
- Commits reviewed: 7 task commits

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Production code unchanged since the previous review

- `git diff --stat f277d788c..d3bf0855a` lists only test files (`MailComposeOverwriteLeaveEdgeCasesTest.kt`, `MailComposeStatesScreenTest.kt`) and the files under `reviews/`. Nothing under `android/app/src/main` changed.
- `git diff e6a9288b5..f277d788c -- android/app/src/main | git patch-id` and the same command for the previously reviewed hash 58171348d give the same patch id (`8d3ffac6...`). So the production code reviewed last round is the code at d3bf0855a and at HEAD.

## Commands run

- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.compose.*'`: passed, 145 tests in 15 classes, 0 failures, 0 errors. That includes `MailComposeOverwriteLeaveEdgeCasesTest` (7/7) and `MailComposeStatesScreenTest` (8/8), according to `app/build/test-results/testDebugUnitTest/TEST-*.xml`.

## Assessment of the new tests (d3bf0855a)

I traced each test against `ComposeSession.kt` and `MailComposeScreen.kt`:

- **Equal files kept over a renumbered version** (`MailComposeOverwriteLeaveEdgeCasesTest`): the stale version keeps two `scan.pdf` files, and the newest version has three. `overwrite` (`ComposeSession.kt:203-209`) consumes one wanted entry per match, so `keep == ["2","3"]`. The test would fail if every equal file were kept, because the result would then be `["2","3","4"]`. Correct and meaningful.
- **Inline part or other size with the kept name**: each mismatch is detected on its own. Without the `inline` comparison `logo.png` would be kept, and without the size comparison `plan.pdf` would be kept (`sameFileAs`, `:420-421`). The test also checks that the first `update` sent the stale ids. Correct and meaningful.
- **A file picked while leaving is not uploaded**: `attach` returns because `editable` is false while leaving (`:247`). Asserting that `uploading` stays empty and `repository.attach` is never called would catch a regression. Meaningful.
- **Leaving an untouched forward locks the screen until the discard finishes**: `discard()` is refused while `leaving` (`:347`). Without that guard, a second `discardStored` would run after the mutex is released, and `coVerify(exactly = 1)` would fail. Meaningful. See the note under CR-2 about the `setSubject` part.
- **Retry while a new mail's accounts load**: the `loading` guard (`:54`) is what keeps `accounts()` at exactly 2 calls. Without it there would be 3. Meaningful.
- **Retry after the second start failed too**: checks that a finished `loading` job does not block later retries. Meaningful.
- **Screen locked while back saves** (`MailComposeStatesScreenTest`): it renders `leaving = true` in EDITING. It checks that send, subject, body, attach and save are disabled, and so is discard. Discard has its own `!state.leaving` condition (`MailComposeScreen.kt:255`), so this test covers it directly. Meaningful.
- **A second back while leaving saves does not save again**: correct, but it does not pin the guard it is named after. See CR-2.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Leaving while uploads are queued closes the screen; their failures are never shown (carried over, accepted)**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/ComposeSession.kt:366-392` (with `:246-256`)
    - Category: Correctness
    - Problem: This is unchanged from the previous round. `send()` refuses while `uploading` is not empty (`:281`), but `leave()` does not check it. Uploads still queued finish after the session is CLOSED. Any `ATTACH_FAILED`, `TOO_LARGE` or `OFFLINE` problem they hit is written to a session that is no longer shown.
    - Why it matters: A failed attachment goes unnoticed until the user looks at the draft in the Drafts folder.
    - Suggested fix: Keep `leaving` set until `uploading` is empty, or refuse `leave()` while uploads run. The task Notes (ASSUMPTION 2026-10-22 10:13) already record this as accepted and not fixed. It needs no further action beyond that disposition in `quality.json`.

- [ ] **CR-2 | "a second back while leaving saves does not save again" passes without the guard it targets**
    - File: `android/app/src/test/java/ch/sonensei/cockpit/mail/compose/MailComposeOverwriteLeaveEdgeCasesTest.kt:133-153`
    - Category: Tests
    - Problem: The test checks `repository.create` exactly once and phase CLOSED. If the `if (s.leaving) return` guard in `leave()` (`ComposeSession.kt:368`) were removed, the second `leave()` would launch a second `store()`. That call waits for the `writes` mutex, then finds `stored != null && !dirty` and returns true without a second create (`:151`). So the test still passes, and it does not protect the guard. In the same way, in the "untouched forward" test, `setSubject("Too late")` is only checked indirectly through `update` being called 0 times. The leave coroutine has already passed its `touched` check by then, and the autosave sees `editable == false` after CLOSED. So an accepted edit would not show up there either. The `assertFalse(editable)` before it is what actually covers the lock.
    - Why it matters: The behavior asserted is true, so this is not a bug. But a regression in the leave guard would go unnoticed. The test name suggests coverage the test does not give.
    - Suggested fix: Optional. Make the second `leave()` observable. For example, assert that `leaving` stays true and `leaveBlocked` stays false while the first save is held, and fail the held save so a second save attempt would show up as an extra `create`. Alternatively, rename the test to describe what it actually checks. In the forward test, assert that `subject` is still empty after `setSubject`.

## Notes (not findings)

- The criterion 1 evidence (145 tests in 15 classes) matches the run above.
- Criterion 2 (`./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.compose.*'` passes, covering draft save and send states with Robolectric/Compose UI tests) is satisfied by the run above. Screens are covered by `MailComposeStatesScreenTest`, `MailComposeScreenTest`, `MailComposeRouteTest` and `MailComposeSaveSendStatesTest`. Recording it with `tasks.py verify` is the parent worker's job.
- No secrets, personal data or debug output were found in the new test files. The test addresses use `example.org`.

## Review conclusion

No material issues found.

The production code is unchanged since the previous review (same patch id). The new tests are correct, pass, and are meaningful, apart from one test that does not pin the guard it is named after (CR-2, P3, optional). CR-1 (P3) is the previously accepted finding, carried over unchanged.
