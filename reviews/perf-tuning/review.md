# Code Review

Date: 2026-10-26

Task: perf-tuning - Performance baseline and tuning

Scope: `git diff d7388b759..HEAD -- server docs` (committed changes only). The uncommitted working-tree changes present at review time (`SearchEdgeCaseTests.kt` modified, `PerfPercentileTests.kt` untracked, apparently from the test-writer) were not reviewed.

Requirements:
- tasks/perf-tuning.md acceptance criterion and recorded assumptions
- SPEC.md, docs/ARCHITECTURE.md ("Persistence conventions", commands table), AGENTS.md project notes
- MigrationConventionTests (migration naming contract)
- Results the parent reported: `./gradlew integrationTest --tests '*PerfBaselineTests' --rerun` -> 2 passed, worst p95 118.4 ms; `./gradlew test --tests '*Search*'` -> 105 passed; `./gradlew ktlintCheck` -> passes (Gradle not re-run by the reviewer, it is blocked for sub-agents)

Git range:
- Branch: main
- HEAD: bd0d0838a289f37a947bfb79a6a509cfad7ad760
- Base/start: d7388b75900291fb47a101514794598f367571eb
- Commits reviewed: 2 code/doc commits (67b1e02c0, bd0d0838a); the 3 `assume:` commits in the range only touch tasks/

Summary: 0 P0, 0 P1, 0 P2, 3 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Seed and docs claim "conversations of 3", but the seeded threads have 1-2 messages**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/perf/PerfSeed.kt:148` (also `docs/perf.md:21`)
    - Category: Quality
    - Problem: `thread_key` is `'thread-' || g / 3`, but the account is chosen by `g % 2`, so the three messages of a key are split across both accounts (for example g = 3, 5 go to account 2, g = 4 to account 1). `MailMessageListQuery` partitions conversations by `m.account_id, coalesce(m.thread_key, ...)`, so each conversation has 1 or 2 messages, never 3.
    - Why it matters: docs/perf.md describes the data set the threaded-list numbers rest on. A later reader comparing runs or growing the data set would assume 3-message conversations that are not there.
    - Suggested fix: derive the thread key per account (for example `'thread-' || g / 6` together with `g % 2`), or change the comment and docs/perf.md to "conversations of 1-2 messages".

- [ ] **CR-2 | "Every run gets the same data" is stronger than what the seed does**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/perf/PerfSeed.kt:13` (also `PerfBaselineTests.kt:31`, `docs/perf.md:19`)
    - Category: Quality
    - Problem: `setseed` only fixes `random()`. Ids come from `gen_random_uuid()`, which `setseed` does not affect, and `task_context` and `note_tag` assign contexts and tags by `row_number() over (order by id)`. So which task gets which context, and which note gets which tag, differs on every run. All dates are also relative to `now()`/`current_date`. Volumes and distributions repeat, but the rows themselves do not.
    - Why it matters: this is a small overstatement. Timings are unlikely to change, but the docs promise more reproducibility than the seed gives.
    - Suggested fix: number the rows by `g` (or by `created_at`/title) and not by a random uuid, or reword to "the same volumes and distributions".

- [ ] **CR-3 | Mail index placed in a non-module migration folder**
    - File: `server/src/main/resources/db/migration/perf/V202610261500__perf_mail_unified_sort.sql:4`
    - Category: Architecture
    - Problem: docs/ARCHITECTURE.md "Persistence conventions" puts migrations in `db/migration/{module}/` ("Module tables come in module migrations"). `perf` is not a server module, and the index is on the mail module's `mail_message`, next to `mail/V202610202300__mail_message_list_sort.sql` and its sibling sort indexes. The name passes `MigrationConventionTests` (folder = name module), and the task's `touches` asked for this folder, so it is a plan-level inconsistency, not a rule this task broke on its own.
    - Why it matters: whoever later changes the mail list ordering looks in `mail/` and misses this index. A stale index costs a write on every mail sync.
    - Suggested fix: before the migration is applied anywhere it matters, move it to `mail/V202610261500__mail_unified_sort.sql`, or note the cross-reference in a comment in the mail list-sort migration's folder or in `MailMessageListQuery`. Otherwise record the folder choice with `tasks.py assume`.

## Verified without findings

- Seed SQL vs. schema: all enum and check constraints hold (task list/energy, project status, mail account type/security/ports/credentials, folder role and unique role per account, `mail_message_uid` > 0 and unique per folder because `uid = g`, calendar single default, timed/all-day/override event checks with `end_date > start_date`, weekly series only on timed rows). Context names are unique case-insensitively, and the `task_context`/`note_tag` OR-joins cannot produce duplicate primary keys.
- Cleanup: `clean()` runs before the seed and in `@AfterAll`. Every seeded table is cleared directly or by `on delete cascade` (note_tag, mail_folder/message, calendar/calendar_event). No other module has a foreign key into these tables, `project.next_task_id` is `on delete set null`, and the temporary `perf_tag` table is dropped. Seeding uses SQL only, so no indexer events fire, and the mail search reconciler is off in the test configuration.
- Percentile: nearest rank `sorted[ceil(p/100*n) - 1]`. With n = 50, p95 is the 48th value and p50 the 25th, which is correct.
- Migration naming: matches `V(\d{12})__perf_[a-z0-9_]+\.sql`, the version is a valid timestamp, and no other migration has the same version.
- Index use: the unified list (`MailMessageListQuery`, `uid is not null`, `order by coalesce(received_at, created_at) desc, id desc`) and mail search (`LOCATED` includes `uid is not null`, same order) both match the partial index expression.
- Ranking semantics: `extract(epoch ...)` (numeric) became `date_part('epoch', ...)` (double precision). The formula and the `order by score desc, updated_at desc, item_id` tie-break are unchanged, and `score` is not read by the row mapper. `as materialized` changes only evaluation, not results.
- Docs: the endpoints listed in docs/perf.md match the 24 in the test. The volumes, the 40 % done, a third in projects, 50-350 words, 10 tags, ~95 % of documents containing `meeting` (consistent with the cubic skew, ~17 % per word draw, and the 64 000 rows in EXPLAIN) and the report path `server/build/reports/perf/perf-baseline.md` all match the code. The criterion's parts (seeded volumes, `external` tag, index as a migration, results in docs/perf.md) are all present.

## Review conclusion

No material issues found. The three P3 items are accuracy and placement cleanups and do not block completion.
