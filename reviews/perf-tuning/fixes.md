# Fix Report

Date: 2026-10-26

Task: perf-tuning - Performance baseline and tuning

Scope: current task; findings CR-1 and CR-2 of `reviews/perf-tuning/review.md` (CR-3 accepted by the parent via a recorded assumption).

## Fixed findings

### CR-1 — Seed and docs claim "conversations of 3", but the seeded threads have 1-2 messages

Files changed:
- `server/src/test/kotlin/ch/sonensei/cockpit/perf/PerfSeed.kt`
- `docs/perf.md`

Problem:
`thread_key` was `'thread-' || g / 3` while the account is chosen by `g % 2`, so each key's messages were split across both accounts. `MailMessageListQuery` partitions conversations by account and thread key, so conversations had 1-2 messages.

Fix:
The thread key is now `'thread-' || g % 2 || '-' || g / 6`. Within each block of 6 consecutive `g`, the 3 values of each parity go to the same account (account = `g % 2`) and share one key. The parity in the key keeps the keys of the two accounts distinct. Every conversation has 3 messages within one account. The exceptions are the first block (`g` = 1..5: 3 messages on one account, 2 on the other) and the last one (`g` = 49 998..50 000: 2 and 1). The SQL comment and docs/perf.md now say "conversations of 3 messages within one account". Checked by reading the code: `%` and `/` bind tighter than `||` in Postgres, so the expression groups as intended, and `text || integer` resolves through the `anynonarray || text` operator, as the old key and `'Area ' || g` already did.

Validation:
- Gradle cannot be run by sub-agents in this environment. The parent runs `./gradlew ktlintCheck integrationTest --tests '*PerfBaselineTests' --rerun`. Result: not run by the fixer.

### CR-2 — "Every run gets the same data" is stronger than what the seed does

Files changed:
- `server/src/test/kotlin/ch/sonensei/cockpit/perf/PerfSeed.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/perf/PerfBaselineTests.kt` (KDoc only)
- `docs/perf.md`

Problem:
Ids came from `gen_random_uuid()`, which `setseed` does not affect. `task_context` and `note_tag` assign contexts and tags by `row_number() over (order by id)`, so the assignments changed from run to run. Dates are relative to `now()`/`current_date`.

Fix:
Every `gen_random_uuid()` in the seed is replaced by an id derived from the row, with a different prefix per table: `md5('perf-area-' || g)::uuid`, and the same pattern with `perf-context-`, `perf-project-`, `perf-task-`, `perf-note-`, `perf-mail-account-`, `perf-mail-message-` and `perf-calendar-event-`. The other ids are `md5('perf-calendar-source-1')::uuid`, `md5('perf-mail-folder-' || a.id::text || '-' || f.path)::uuid` for the folders cross-joined from the accounts, and `md5('perf-calendar-' || s.id::text || '-' || g)::uuid` for the calendars. All of these are distinct per table. `md5(text)` returns 32 hex digits, and the Postgres `uuid` input accepts 32 hex digits without hyphens, so the cast is valid in Postgres 16. The ids are now the same in every run, so `order by id` and the context and tag assignments are too. `setseed` still fixes every `random()`: the seed runs on one connection, and the evaluation order is fixed because the insert-selects run without parallel plans over heaps filled in a fixed order.

The ids are not UUIDv7. `gen_random_uuid()` was not UUIDv7 either (it is v4). The only UUIDv7 checks in production code run in `BaseEntity`'s constructor (Hibernate overwrites the field after loading) and on create requests with client-supplied ids (`TaskService`, `CalendarEventService`). Neither applies to rows the seed inserts. The KDoc says so.

The comments (PerfSeed KDoc, PerfBaselineTests KDoc) and docs/perf.md now say what repeats: the same rows, ids and context/tag assignments in every run, with dates relative to the day of the run.

Validation:
- Gradle cannot be run by sub-agents in this environment. The parent runs `./gradlew ktlintCheck integrationTest --tests '*PerfBaselineTests' --rerun`. Result: not run by the fixer.

## Not fixed

### CR-3 — Mail index placed in a non-module migration folder

Status:
Not fixed, assumption recorded

Reason:
The parent accepted the folder choice (`db/migration/perf/`, as the task's `touches` require) and recorded it with `tasks.py assume`. No code change was requested.

## Validation summary

Focused tests:
- The SQL was checked by reading it: the md5-to-uuid casts, the operator precedence of the thread key, and that the ids stay unique per table. No `gen_random_uuid()` is left in PerfSeed.kt.
- The changed lines stay within the existing line lengths: no new line over 120 columns (tabs counted as 4). The longest pre-existing lines in the file are 124.
- `./gradlew ktlintCheck integrationTest --tests '*PerfBaselineTests' --rerun`: the parent runs it, because Gradle is blocked for sub-agents.

Full test suite:
- Not run (Gradle is blocked for sub-agents). Only test sources and docs changed.

Lint/type-check/build:
- `./gradlew ktlintCheck`: the parent runs it.

`.taskcheck`:
- Not run (it needs Gradle). The parent runs it.

Remaining unresolved findings:
0 (CR-3 accepted via assumption)

Note for the parent: the seeded data changed (thread grouping, ids), so the "mail: ... threaded" numbers in docs/perf.md "Results" were measured on the old data. If the re-run differs noticeably, update the table.

## Final status

All validated findings fixed. Validation is still pending: the parent has to run Gradle (ktlintCheck and PerfBaselineTests), because the fixer could not.
