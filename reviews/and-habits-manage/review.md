# Code Review

Date: 2026-11-08

Task: and-habits-manage - Android habits: create, edit, archive, incremental check

Scope: implementation commit b656840c5 on top of the task base cf27a099e (`git diff cf27a099e..b656840c5`): production code under `android/app/src/main/java/ch/sonensei/cockpit/habits/`, `android/app/src/main/res/values{,-de}/strings_habits.xml`, and the adapted existing tests. Tests that the test-writer adds in parallel are not part of this review.

Requirements:
- `tasks/and-habits-manage.md` acceptance criteria and recorded assumptions
- `docs/PARITY.md` (web/Android parity context)
- `AGENTS.md` project notes, `docs/ARCHITECTURE.md` conventions, `docs/QUALITY.md` (report contract)
- Server contract: `server/.../journal/habits/api/HabitController.kt`, `HabitDtos.kt` (`HabitRequest.toChanges` with the sent keys), `domain/HabitService.kt` (apply/validate), `core/web/ETags.kt`
- Web reference: `web/src/app/features/habits/habits-page.ts`, `habit-display.ts`, `habit-form.ts`

Git range:
- Branch: main
- HEAD: b656840c5
- Base/start: cf27a099e5a9cc41b8c7c984a05d1ae7e8acd0ab
- Commits reviewed: 1

Summary: 0 P0, 0 P1, 1 P2, 1 P3

Commands run (read-only):
- `git log --oneline cf27a099e..HEAD`, `git diff --stat cf27a099e..b656840c5`, `git diff cf27a099e..b656840c5 -- android/app/src/test` -> one commit, 10 files; existing tests only adapted to the new `check(habit)` signature and the "3x a day" detail text, none deleted or skipped
- `grep` of `NetworkModule.kt` -> Retrofit uses the kotlinx-serialization converter, so the `JsonObject` bodies of `HabitsWriteApi` serialize as plain JSON (same pattern as other modules)
- `grep` of `ETags.kt` -> server ETag is `"<version>"`, matching `etag(version)` in `HabitsRepository.kt:264`
- Gradle tests were not run by the reviewer (the test-writer runs them).

Verified without findings:
- Create sends name (trimmed), schedule, targetCount, reminderTime (`HH:mm` or `null`), and only the schedule's own field (`timesPerWeek` / `weekdays`, Monday-first, matching the server's sorted order); identical to web `requestOf`. The server accepts `HH:mm`; the response's `HH:mm:ss` parses with `LocalTime.parse`.
- Archive/unarchive PATCH sends only `{"archived": ...}` with `If-Match: "<version>"`, so no other field is cleared.
- Client validation (`HabitDraft.problem`) matches the server's `validate` (trimmed name 1..200, timesPerWeek 1..7, weekdays non-empty, target 1..1000).
- `Habit.nextCount` matches web `nextCount` (increment to target, then 0); best streak (`longestStreak`, day/week plural) shown in the row details.
- Save uses the latest loaded version at save time, so saving again after a conflict reload overwrites (recorded assumption); 404/409/412 map to the conflict message.
- All new strings exist in English and German with matching names, placeholders and plurals.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | A conflict reload that was read before a later local change overwrites that change on screen**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/habits/HabitsViewModel.kt:94` (`startLoad`), triggered by `reloadAfterConflict` at `:111`, called from `:171` and `:229`
    - Category: Correctness
    - Problem: `startLoad` drops a list answer only when another load started (`seq != loadSeq`). It does not notice a check, archive or save that started or finished while the load was in flight. This commit adds an automatic reload after every 404/409/412, and the rows stay enabled during it. Example: archiving habit A gets a 412, so a reload starts. The user archives habit B, which succeeds and removes B from the list. Then the reload answer (read before B was archived) arrives and replaces the whole list, so B shows up again as active with its old version. Tapping it then checks an archived habit, and archiving it again gets a 412. A check works the same way: the stale `todayCount` comes back, and the next tap sends an absolute count (`nextCount`) based on it, so the tap is lost. The web reference guards against exactly this with `changeSeq` in `reloadQuietly` (`habits-page.ts`: "dropped ... also when a local change started or finished meanwhile").
    - Why it matters: The list shows wrong state after a conflict, and the next tap's absolute count is computed from it, so check-offs can be lost and archived habits come back on screen.
    - Suggested fix: Keep a change counter in the view model (bump it when `change`/`save` start and finish) and drop a load answer when the counter changed since the load began, or merge it so habits in `checking`, or changed since the load began, keep their local value. Add a view-model test where a change finishes before a pending reload answer arrives.

## P3 Low

- [ ] **CR-2 | ktlint-wrapped inline lambda in `change()` is hard to read**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/habits/HabitsViewModel.kt:149-162`
    - Category: Quality
    - Problem: The inline `if (it.id ==\n id\n) { result.value } else { it }` was split over eight lines by the formatter. The same replace-in-list logic appears again in `saved()` (`:239`).
    - Why it matters: Readability, and two copies of the same list update.
    - Suggested fix: Extract a small `List<Habit>.replacing(habit: Habit)` helper and use it in both places.

## Review conclusion

Material issues found; fix before completing the task. There are no P0/P1 findings. CR-1 (P2) is a real state race that the web reference already handles, so fix it or record a deliberate disposition for it. Whether criterion 5 (tests for the new behavior) is met depends on the test-writer's parallel work, which this review does not cover.
