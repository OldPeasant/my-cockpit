# Code Review

Date: 2026-11-08

Task: and-task-convert - Android: convert tasks to notes and events

Scope: The final code of the task's commits 3986c6887 (implementation, parent dd3dbd0df), 863fc0114, 7d689eb66, b804064b7, 1a4d04b4d, 665345b14 and e697e8300, all in HEAD. Reviewed with `git diff dd3dbd0df HEAD`, limited to the task's own files: gtd/task, gtd/inbox, ui/CockpitShell.kt, strings_task_detail.xml and strings_inbox.xml in `values/` and `values-de/`, and their tests. `git log dd3dbd0df..HEAD` on these paths lists only the seven task commits. Other changes in the range come from other tasks merged from main and are out of scope. The concurrent test-writer's uncommitted change to InboxConvertViewModelTest.kt was not reviewed. Gradle was not run, so the concurrent test-writer's build was not disturbed. This is a static review.

Requirements:
- tasks/and-task-convert.md acceptance criteria and recorded assumptions
- SPEC.md §5.3 (processing actions: To reference (note), To calendar (event)) and the "Reference" row (move to reference converts the item into a Note)
- docs/ARCHITECTURE.md, docs/PARITY.md (row "No convert task -> note / event", and-task-convert)
- AGENTS.md project notes
- Web reference: web/src/app/features/gtd/inbox/inbox-page.ts `run()` (runSeq)
- Previous review (CR-1 P2) and reviews/and-task-convert/fixes.md "Round 2"

Git range:
- Branch: main
- HEAD: e697e8300116551aa23e399a28f1023e4751cd0f
- Base/start: dd3dbd0df
- Commits reviewed: 7

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Verification of the earlier finding

- CR-1 (Inbox: when an older conversion answered last, it replaced the newer conversion's `converted`) is fixed. `InboxViewModel.run()` now takes `val seq = ++runSeq` for every action (InboxViewModel.kt:315). It sets `converted` only when `seq == runSeq` (line 333); otherwise the current value stays. This is the same rule as the web (`seq === this.runSeq`, inbox-page.ts:287-291): "where it went" describes only the latest action. Android differs on purpose in one way: a later plain action never clears `converted`. `processed`, `busy`, `edit` and `message` work as before.
- The committed regression tests cover the change in InboxConvertViewModelTest:
  - an older conversion that answers last does not replace the newer one;
  - a conversion that answers after a newer plain action started is not shown, as on the web;
  - an older conversion that answers after the newer one was refused shows nothing and keeps the refusal.

  None of the three is disabled. HEAD has no `TEMP-COVERAGE` marker.
- The earlier fixes still hold:
  - the synced time zone, with the device zone as fallback, in both view models;
  - a newer calendar load cancels the older one, and `calendars()` rethrows `CancellationException`;
  - task detail asks for confirmation before To reference;
  - the `TaskConverter` endpoint calls are no longer duplicated.

## Acceptance criteria check

1. The Inbox offers "To reference" and "To calendar". The calendar dialog has a calendar, a start, and a duration or all-day. This is implemented. REFERENCE sits under "Drop it" and CALENDAR under "Defer it" (ACTION_GROUPS). REFERENCE runs at once, like the web Inbox. CALENDAR opens `ToCalendarDialog` with:
   - the writable calendars, with the default calendar first;
   - date and time pickers;
   - duration chips;
   - an all-day switch.

   Confirm stays disabled while the calendars are loading, failed to load or are empty, and while offline.
2. The task detail menu offers both actions. This is implemented for open tasks that are not read-only. To reference asks for confirmation first. To calendar opens the same dialog.
3. After converting, the user sees where the item went, with an "Open" action. This is implemented.
   - Task detail: a Long snackbar shows "Moved to ... / Open". Open closes the detail and then opens the note (search item route) or the Calendar tab. Dismissing closes the detail.
   - Inbox: the snackbar opens the item through `LocalConvertedOpener` from the shell.

   When conversions answer out of order, only the latest action is shown (see CR-1 above).
4. Strings exist in English and German. All new keys in strings_task_detail.xml and strings_inbox.xml are in both `values/` and `values-de/`.
5. Tests cover the new behavior:
   - ViewModels: TaskDetailConvertViewModelTest, InboxConvertViewModelTest;
   - repositories with MockWebServer: TaskConversionRepositoryTest, InboxConversionRepositoryTest;
   - Compose: TaskDetailConvertScreenTest, InboxConvertScreenTest;
   - route and shell opener: TaskDetailConvertRouteTest, ConvertedOpenerShellTest;
   - EventPlanTest and TaskDetailStringsTest.

   This review did not run `./gradlew ktlintCheck testDebugUnitTest lintDebug` or `sh scripts/check.sh`. The fixer's Round 2 ran only `ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.*'`, not lintDebug and not check.sh. The parent must record a full run of both commands on the final code before it verifies criterion 5.

## Re-review of final revision c24989938

Scope: `git diff cb53cfb86 c24989938` (task base 8e3ba22c1b346ce55c9c59404028e1c3edd068de, task commits 333f2da75..c24989938, HEAD c24989938df096a9436bd22fbb1701c427a00aca on main, no uncommitted changes).

- Production code is unchanged since the reviewed revision cb53cfb86: `git diff cb53cfb86 c24989938 --stat` lists only `android/app/src/test/java/ch/sonensei/cockpit/gtd/inbox/InboxConvertViewModelTest.kt` (+37, commit 5a5b14f0d) and `reviews/and-task-convert/tests.md` (c24989938). `git diff --stat cb53cfb86 c24989938 -- android/app/src/main server web` is empty.
- The two added tests match `InboxViewModel.run()` (InboxViewModel.kt:310-337):
  - "a conversion answering after a newer plain action started is not shown, as on the web": the first item's REFERENCE is held open, the second item's NEXT answers `Processed` at once (default mock, line 78), so `processed` is 1. When the older conversion then answers, its `seq` (1) is not `runSeq` (2), so `converted` stays null, and `processed` becomes 2. Before the CR-1 fix `converted` would have been the note, so the test guards the fix.
  - "an older conversion answering after the newer one was refused shows nothing and keeps the refusal": the newer REFERENCE answers `Conflict` (message CONFLICT, `processed` unchanged). The older conversion then answers: `converted` stays null (stale seq), `message = null ?: it.message` keeps CONFLICT, `processed` becomes 1. All three assertions follow from the code.
  - Both use `CompletableDeferred` with `UnconfinedTestDispatcher`, so the ordering is deterministic. Neither test is `@Ignore`d or disabled, and the final revision has no `TEMP-COVERAGE` marker.
- The tests support acceptance criteria 3 (only the latest action's destination is shown) and 5 (tests cover the new behavior). This re-review did not run Gradle. Test results are those reported in reviews/and-task-convert/tests.md (measured at 5a5b14f0d, the same code as c24989938).
- No new findings. The summary above (0 P0, 0 P1, 0 P2, 0 P3) applies to final revision c24989938.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.
