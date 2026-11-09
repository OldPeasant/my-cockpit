# Code Review

Date: 2026-11-09

Task: review-reminder-server - Weekly review reminder setting and scheduling (server)

Scope: The two task commits (7122800be wip(review) and 21908ecd8 feat(review)), rebased onto c0ab83a00. Reviewed `server/`, `api/openapi.yaml` and how the regenerated `web/src/app/api/generated/` client affects the rest of the web code. Uncommitted working-tree changes were not reviewed.

Requirements:
- tasks/review-reminder-server.md acceptance criteria and design notes
- SPEC.md §8.6 (weekly review reminder on a configurable weekday), §4.6 reminders
- AGENTS.md project notes (API changes regenerate the clients; `.taskcheck` covers every part)
- Existing ReminderService / ReminderScheduler / SettingsService contracts

Git range:
- Branch: main
- HEAD: 21908ecd8dbdae75a89796ffc534abd6f4c09cc1
- Base/start: c0ab83a009b3b0db65e718076ee09c61246f9882 (merge-base with origin/main; originally 4d5fd9777 before rebase)
- Commits reviewed: 2

Summary: 0 P0, 2 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | The regenerated `ItemType` union breaks the web build (exhaustive `Record<ItemType, ...>`)**
    - File: `web/src/app/shared/links/item-type.ts:5` (GLYPHS) and `:44-` (`itemTypeLabel` switch); `web/src/app/shared/links/item-type.spec.ts:5` (LABELS)
    - Category: Integration
    - Problem: The commit regenerates `web/src/app/api/generated/models/item-ref-dto.ts` and adds `'WEEKLY_REVIEW'` to the type union. `ItemType` in `links.service.ts:16` is `ItemRefDto['type']`. In the committed HEAD, `GLYPHS: Record<ItemType, string>` has no `WEEKLY_REVIEW` key, and `itemTypeLabel` has no case for it. That is a TypeScript compile error (missing property in `Record`; not every path returns a `string`). The spec's `LABELS: Record<ItemType, string>` has the same gap. As a result, `ng build` and `npm run check` fail, and so does `.taskcheck` (`scripts/check.sh`). An uncommitted edit to `item-type.ts` in the working tree adds the glyph and label, but `item-type.spec.ts` is still unchanged there.
    - Why it matters: AGENTS.md says an API change regenerates the clients, and `done` runs the full check. The committed task state does not build, so `done` would fail or publish a broken web build.
    - Suggested fix: Commit the `item-type.ts` glyph and label (with the i18n entries in `messages*.xlf`). Add `WEEKLY_REVIEW: 'Weekly review'` to `LABELS` in `item-type.spec.ts`. Then run `cd web && npm run check`. Also check the Android string-based type mappers (`SearchModels.kt`, `TrashModels.kt`, `ReminderOpenRequests.kt`) for a sensible fallback. Those do not break the build.

- [ ] **CR-2 | The startup sync deletes a missed (overdue) weekly reminder before it can fire**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/review/domain/WeeklyReviewReminders.kt:66-70`, `:89-90`
    - Category: Correctness
    - Problem: `sync()` compares the pending reminders with `next()`, the first occurrence strictly after `clock.instant()`. Suppose the server was down at Sunday 18:00 and restarts at 18:10. The pending reminder still has `remindAt = 18:00` and `firedAt = null`. `onStartup` (`ApplicationReadyEvent`) runs before the first `ReminderScheduler.run()`, which has an `initialDelay` of `PT15S` (`core/reminders/infra/ReminderScheduler.kt:29-32`). `next()` returns next Sunday, so the list comparison fails. The overdue reminder is then deleted and replaced with next week's. The missed reminder never fires. This contradicts the method's own KDoc ("Catches up at startup, e.g. after the server was down while the reminder fired") and the `ReminderService.create` contract that a past reminder "fires on the next scheduler run". It also loses the overdue reminder if the timeZone or the setting changes between the due time and the next scheduler poll (up to 15 s), though without the setting change that is rare.
    - Why it matters: The case the startup hook was written for loses the user's weekly reminder. No test covers it.
    - Suggested fix: If the setting is unchanged, keep a pending reminder that is already due (`remindAt <= now`) and let the scheduler fire it. `onReminderFired` then arms the next one. One way: if the earliest pending reminder is overdue and its time matches a past occurrence of the current schedule (or simply on startup), leave it alone and only remove the extras. Add a Spring test: create a pending reminder in the past, call `onStartup()`/`sync()`, then `fireDue()` returns 1 and next week's reminder is armed.

## P2 Medium

None.

## P3 Low

- [ ] **CR-3 | Users can create or delete WEEKLY_REVIEW reminders through the generic reminders API, and the module's "exactly one" is only restored on the next trigger**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/review/infra/WeeklyReviewItemResolver.kt:13-16`; `WeeklyReviewReminders.kt:66-70`
    - Category: Correctness
    - Problem: The resolver makes the well-known item "exist", so `POST /api/v1/reminders` accepts extra reminders for it, and `DELETE /api/v1/reminders/{id}` can remove the managed one. In the first case the next `sync()` (a setting change, firing, or a restart) silently deletes the user's extra reminders. In the second, no reminder is armed until the next trigger.
    - Why it matters: This is a small inconsistency with the task design ("keeps exactly one pending reminder"). Normal use does not hit it, because the clients do not offer reminders on this item.
    - Suggested fix: Accept it and document it in the class KDoc, or re-sync when a REMINDER change event deletes a WEEKLY_REVIEW reminder. No change is required for this task.

## Review conclusion

Material issues found; fix before completing the task.

Notes: The server-side setting validation (`SettingType.Formatted`, `WeeklyReviewSchedule.parse`), the next-occurrence computation (time zone, DST gap and overlap), the advisory-lock serialization, the AFTER_COMMIT listeners in isolated transactions, the OpenAPI enum regeneration and the unit and Spring tests all match criteria 1-3. Apart from CR-2, I found no defects in them.
