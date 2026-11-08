# Code Review

Date: 2026-11-08

Task: mail-load-older-ui - Load mail older than the sync window (web + Android)

Scope: final task code, `git diff 73a3b3fc4 4e4ff409e -- web android`. It covers commits 81823764b (web), 1b49efa14 (Android), 711fe21f3 (tests), and f430e277e + 4e4ff409e (fixes for CR-1..3 and TW-1/TW-2). This is a re-review. It confirms that the earlier findings are resolved and checks the fix for new issues.

Requirements:
- `tasks/mail-load-older-ui.md`: the acceptance criteria and both ASSUMPTION notes (the button at the end of every folder list; TW-2's German catalog fix is in scope).
- SPEC.md §7.2, as summarised in the task goal.
- AGENTS.md project notes (i18n en+de, touches, lint).
- `reviews/mail-load-older-ui/fixes.md`.
- The documented `MailListStore` contract (a refetch must not overwrite a running action's optimistic state).

Git range:
- Branch: main
- HEAD: 4e4ff409e
- Base/start: 73a3b3fc43a909befd381f915314015c509dcef7
- Commits reviewed: 6 (3 code commits, the earlier review report, and 2 fix commits)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Earlier findings: verification

- **CR-1 / TW-1 (loadingOlder stuck after load/restore): resolved.**
  - `load()` (`mail-list-store.ts:159`) and `restore()` (`:243`) both call `resetOlder()` (`:249-253`). It clears `loadingOlder`, `olderError` and `olderNote`.
  - The stale `loadOlder()` drops its answer on the `seq !== this.loadSeq` checks, so it cannot clear the progress of a newer call.
  - Regression tests: "ends the progress when the list is reloaded or restored while it loads older" and "can load older in the next folder after leaving one while it loaded older".
- **CR-2 (loadOlder overwrote optimistic action state): resolved.** `showUpTo()` (`:301-321`) follows the `refresh()` pattern:
  - It waits on `actionsSettled()` while `running > 0`.
  - It records `mutationSeq` before `fetchUpTo`, and reads again if an action started or changed the list meanwhile.
  - It bumps `mutationSeq` itself, so an in-flight `refresh()` sized for the shorter list requeues instead of shrinking the list.
  - The waiters are released in `apply()` (`:479-483`) after `running--`, which sits in a `finally`. `apply()` is the only place that increments `running` (`:452`), so a waiter cannot hang longer than the action that blocks it.
  - A `load()` while it waits returns without a read.
  - Tests cover all three paths.
- **CR-3 ("nothing older" shown as an error): resolved.**
  - The new `olderNote` signal is rendered as a neutral `<p class="note" role="status">` (`mail-list-page.html:291-293`), without the "Nothing older loaded:" prefix.
  - Real failures keep `.error` with the prefix.
  - This now matches Android, which shows NOTHING_OLDER neutrally.
- **TW-2 (German catalog): resolved.**
  - `messages.de.xlf` now has the 16 `calendar.invite.*` units and the current `capture.save` source.
  - The new ids `mail.loadOlderFromServer`, `mail.loadingOlder`, `mail.olderFailed` (with the `INTERPOLATION` placeholder kept) and `mail.list.nothingOlder` have German targets (`messages.de.xlf:9023-9050`).

## Acceptance criteria check (from the code)

- AC1:
  - Web: `canLoadOlder` requires a folder, no search, a loaded list, no load error and no cursor. `loadOlder()` sends POST `load-older?limit=100` through the generated `loadOlderMail` with silent errors, shows `mail.loadingOlder`, then re-reads `shown + loaded` items in pages capped at 200.
  - Android: `MailListViewModel.loadOlder()` does the same, and `load()` and `select()` reset the progress. Android is unchanged since the first review.
- AC2: offline, failure and nothing-older are shown inline at the end of the list, with no toast and the list kept. The button stays enabled for a retry on both clients.
- AC3: the 5 Android strings are in `values/` and `values-de/`. The 4 web units are in `messages.xlf` and `messages.de.xlf`.
- AC4: there are unit tests for the web store, page and service and for Android (repository, view model, screen). `fixes.md` reports `npm --prefix web run check` passing (4550 tests). This review did not run the suites.

## Notes (not findings)

- `python tasks.py verify mail-load-older-ui` shows all four criteria as unverified. The parent worker still has to record evidence, including a `sh scripts/check.sh` run on the final code, before `done`.
- `refresh()` still caps its re-read at 200 items (`MAIL_MAX_PAGE_SIZE`). So after loading older beyond 200 items, the next folder event shrinks the list to 200 and sets a cursor, and the end then shows infinite scroll instead of the button. The same happens after `loadMore()` today, so this was not introduced by this task. It is not a defect of this task.

## Review conclusion

No material issues found.
