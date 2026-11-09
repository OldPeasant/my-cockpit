# Code Review

Date: 2026-11-09

Task: review-reminder-server - Weekly review reminder setting and scheduling (server)

Scope: Final re-review at HEAD 16f9276dd. Task range `2e61bfa28..HEAD`. The server, web and api changes were already reviewed in the previous revision of this report (through b08b3fd2d). They are unchanged since then, so those conclusions still apply. New code since b08b3fd2d: `git diff b08b3fd2d..HEAD -- android`, which is commit 16f9276dd. It adjusts two Android unit tests because the regenerated API client now includes `WEEKLY_REVIEW`. I did not review changes under tasks/ and reviews/.

Requirements:
- tasks/review-reminder-server.md acceptance criteria, design notes and recorded assumption
- tasks/review-reminder-android.md (owns the Android WEEKLY_REVIEW reminder mapping and strings)
- SPEC.md §8.6 (weekly review reminder on a configurable weekday), §4.6 reminders
- AGENTS.md project notes (API changes regenerate the clients; `.taskcheck` covers every part)
- Previous review and fix reports: reviews/review-reminder-server/review.md (earlier revisions), fixes.md

Git range:
- Branch: main
- HEAD: 16f9276dd8d4a7ac80ee06a705f7f164ca766fa1
- Base/start: 2e61bfa28dc1dd187910fb8e551c8e48141c069e
- Commits reviewed: 8 task commits in total (453b9b612 through 16f9276dd). This pass reviewed only the new Android test commit 16f9276dd in detail.

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Android test adjustments (16f9276dd)

- `android/app/src/test/java/ch/sonensei/cockpit/reminders/ReminderUiTest.kt:471-472`: adds `"WEEKLY_REVIEW" to CockpitDestination.Dashboard` to the open-request mapping table, with a comment that review-reminder-android will map it to the review screen. Dashboard is the existing generic fallback for item types the client does not know (the same as `SOMETHING_NEW`). So the test now states the current behavior and weakens nothing. It does not hide a defect in this task: review-reminder-android depends on this task, and its first acceptance criterion is the mapping. That task has to change this line when it adds the mapping, so the test cannot pass silently with a stale value.
- `android/app/src/test/java/ch/sonensei/cockpit/trash/TrashModelsTest.kt:56-58`: `WEEKLY_REVIEW` is excluded from the "every API trash type has a label" loop. I checked that this is correct. The review module registers no `TrashContributor` (`core/trash/domain/TrashContributor.kt`; there is no trash code under `cockpit/review`), so the well-known weekly review item can never be soft-deleted or appear in the trash list. The type only reaches `TrashItemResponse.Type` because the API uses a shared item-type enum. Even if it did appear, `trashTypeLabel` falls back to "Other" and would not crash. The exclusion is narrow, so the check still applies to every other type.
- No Android production code changed. Generated Android client models add the enum value, and the string-based item-type mapping falls back to safe defaults, so deserializing `/reminders/upcoming` and trash responses still works.

## Verification of earlier findings (carried over, code unchanged)

- Earlier CR-1 (web build broken by the regenerated `ItemType` union): fixed. The `WEEKLY_REVIEW` glyph and label are in `web/src/app/shared/links/item-type.ts`, and the en and de translations are present.
- Earlier CR-2 (startup sync deletes an overdue reminder): fixed in `WeeklyReviewReminders.sync()`. While the setting is on, a due pending reminder is kept for the scheduler. Older due duplicates are removed (`due.dropLast(1)`), and only future reminders are compared with `next()` and replaced. Regression tests cover both the on path and the off path.
- Core test expectations (`ItemResolverRegistryTests`, `SettingsServiceTests`) include the new type and setting.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Users can still create or delete WEEKLY_REVIEW reminders through the generic reminders API (carried over from the earlier CR-3, still unaddressed)**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/review/infra/WeeklyReviewItemResolver.kt:15-16`; `server/src/main/kotlin/ch/sonensei/cockpit/review/domain/WeeklyReviewReminders.kt:29-34`
    - Category: Correctness
    - Problem: The resolver reports the well-known item as existing. So `POST /api/v1/reminders` accepts extra reminders for it, and `DELETE /api/v1/reminders/{id}` can remove the managed one. The next `sync()` silently deletes extra future reminders. A deleted managed reminder comes back only at the next trigger: a setting or time-zone change, or a restart. The class KDoc promises "exactly one pending reminder" without mentioning this limit.
    - Why it matters: This is a small inconsistency with the design statement. The clients do not offer reminders on this item, so normal use does not hit it.
    - Suggested fix: Add one sentence to the `WeeklyReviewReminders` KDoc that documents the limit, or record it as accepted in quality.json. No code change is needed for this task.

## Review conclusion

No material issues found.

Notes: The earlier conclusions on criteria 1-3 (strict `<DAY>@HH:mm` validation, time-zone and DST-correct `nextAfter`, advisory-lock serialization with AFTER_COMMIT listeners, Spring tests) and on criterion 4 part 1 (OpenAPI enum and regenerated clients) still apply. The two Android test edits are what the regenerated client requires, and they do not mask a defect in this task. As instructed, I ran no test suites for this pass. The parent runs `.taskcheck` (including Android `testDebugUnitTest`) for criterion 4.
