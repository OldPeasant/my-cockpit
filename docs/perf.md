# Performance baseline

Goal (SPEC.md, task `perf-tuning`): with realistic data volumes, the p95 of every list and search endpoint stays
below **200 ms**.

## How to run

```sh
cd server && ./gradlew integrationTest --tests '*PerfBaselineTests'
```

Tagged `external` (`@ExternalTest`), so it is not part of `check`/`.taskcheck`. It needs Docker/Podman like the
other server tests (docs/ARCHITECTURE.md "Install") and takes about 1.5 min, most of it the Spring context and the seed.
The measured table is printed and written to `server/build/reports/perf/perf-baseline.md`; the test fails when any
endpoint's p95 reaches 200 ms.

## Method

- **Data** (`PerfSeed`, plain SQL with `generate_series`, a fixed `setseed` and ids hashed from the row, so every run
  gets the same rows, ids and context/tag assignments, with dates relative to the day of the run; all fictitious):
  10 000 tasks (40 % done, the open ones over all lists, a third in one of 150 projects, every task in 1–2 of 10
  contexts), 2 000 notes (50–350 words, 10 tags), 50 000 cached mail headers (2 accounts, Inbox/Archive/Sent,
  conversations of 3 messages within one account, two years of mail), 5 000 calendar events (3 calendars, two years
  around today, 10 % all-day, every 50th a weekly series) and one search document per item (67 000). Text is drawn
  from a 200-word vocabulary with a cubic skew, so the most common word (`meeting`) is in ~95 % of all documents: the
  worst case for ranking.
- **Requests** go through MockMvc: security filter chain, controller, service, JDBC/JPA, Postgres 16 (Testcontainers),
  JSON serialization; no network. Per endpoint 10 warm-up calls, then 50 measured ones, sequentially; p95 is the
  nearest-rank percentile.
- **Endpoints**: task lists (Inbox, Next, by context, by project, due this week, Logbook, calendar overlay), notes
  (list, by tag), mail lists (unified Inbox, account Inbox, folder, each threaded and flat), mail search (common, rare,
  two words), calendar occurrences (month, week) and global search (common, rare, two words, 3-letter prefix, typo).

The numbers below come from a shared development machine (Fedora, Podman, other agents' builds running at the same
time), so they vary by up to ~2x between runs; compare a change against a run on the same machine.

## Results

Before tuning (2026-10-26, same volumes; an earlier version of the seed with random ids), every endpoint but global search was well below the limit:

| Endpoint | p50 ms | p95 ms |
|---|---:|---:|
| mail: unified inbox | 19.7 | 21.4 |
| mail: unified inbox threaded | 61.6 | 87.6 |
| mail: search common word | 62.4 | 99.8 |
| search: common word | 215.5 | **324.8** |
| search: two words | 110.3 | 194.3 |
| search: 3-letter prefix | 248.1 | **332.4** |

After tuning (2026-10-26, final seed; an earlier run under heavier machine load measured up to 118 ms p95 for the prefix search):

| Endpoint | Request | p50 ms | p95 ms | max ms |
|---|---|---:|---:|---:|
| tasks: inbox | `GET /api/v1/tasks?list=INBOX` | 8.0 | 9.6 | 10.7 |
| tasks: next actions | `GET /api/v1/tasks?list=NEXT` | 7.5 | 8.9 | 9.1 |
| tasks: by context | `GET /api/v1/tasks?context={id}` | 7.8 | 9.5 | 12.7 |
| tasks: by project | `GET /api/v1/tasks?projectId={id}` | 3.3 | 4.1 | 5.2 |
| tasks: due this week | `GET /api/v1/tasks?due=week` | 2.9 | 3.8 | 4.1 |
| tasks: logbook | `GET /api/v1/tasks?completed=true` | 4.6 | 5.2 | 5.6 |
| tasks: calendar overlay | `GET /api/v1/tasks?from={date}&to={date}` | 4.9 | 5.9 | 6.6 |
| notes: list | `GET /api/v1/notes` | 5.6 | 6.6 | 12.8 |
| notes: by tag | `GET /api/v1/notes?tag=work` | 6.6 | 8.0 | 9.1 |
| mail: unified inbox | `GET /api/v1/mail/messages` | 4.4 | 5.3 | 5.9 |
| mail: unified inbox threaded | `GET /api/v1/mail/messages?threaded=true` | 91.3 | 96.5 | 98.1 |
| mail: account inbox | `GET /api/v1/mail/messages?accountId={id}` | 4.4 | 5.4 | 6.1 |
| mail: folder | `GET /api/v1/mail/messages?folderId={id}` | 3.9 | 4.6 | 5.3 |
| mail: folder threaded | `GET /api/v1/mail/messages?folderId={id}&threaded=true` | 25.6 | 27.3 | 28.3 |
| mail: search common word | `GET /api/v1/mail/search?q=meeting` | 4.4 | 5.5 | 5.6 |
| mail: search rare word | `GET /api/v1/mail/search?q=zanzibar` | 5.0 | 6.9 | 7.0 |
| mail: search two words | `GET /api/v1/mail/search?q=meeting+release` | 4.9 | 6.9 | 8.3 |
| calendar: month | `GET /api/v1/calendar/events?from={date}&to={date}` | 14.5 | 22.0 | 27.0 |
| calendar: week | `GET /api/v1/calendar/events?from={date}&to={date}` | 8.7 | 11.7 | 17.7 |
| search: common word | `GET /api/v1/search?q=meeting` | 61.6 | 66.5 | 66.7 |
| search: rare word | `GET /api/v1/search?q=zanzibar` | 3.9 | 6.1 | 6.3 |
| search: two words | `GET /api/v1/search?q=meeting+release` | 39.5 | 41.4 | 42.8 |
| search: prefix | `GET /api/v1/search?q=mee` | 56.8 | 59.7 | 60.8 |
| search: typo (fuzzy) | `GET /api/v1/search?q=zanzibra` | 5.1 | 5.7 | 5.8 |

## Tuning

1. **Global search builds its tsquery once** (`SearchDocumentRepository.fullText`). pgjdbc switches a statement
   to a server-side prepared statement after 5 executions, and Postgres then uses a generic plan. In that plan the
   inlined CTE `q` called `to_tsquery` twice per matching row (`EXPLAIN ANALYZE`: 157 ms for `meeting`, 64 000 rows).
   `with q as materialized (...)` computes it once (88 ms); the recency boost in `double precision` (`date_part`
   instead of `numeric` `extract`) brings it to 77 ms.
2. **Index `mail_message_sort`** (`db/migration/perf/V202610261500__perf_mail_unified_sort.sql`):
   `(coalesce(received_at, created_at) desc, id desc) where uid is not null`. The unified Inbox spans the Inbox
   folders of all accounts; no per-folder or per-account index covered it, so every call read and sorted all 50 000
   headers. With the index a page is read in list order and filtered by folder (21 → 7 ms p95). Mail search uses the
   same order, so for a common word Postgres now walks the newest messages and checks each one's search document
   instead of ranking all matches (100 → 9 ms p95).

## Remaining hot spots

- **Global search for a word in nearly every item** (60–120 ms p95 across runs): `ts_rank` must score every match (no index can rank),
  so the cost grows linearly with the number of matching documents. At 10x the volume this would exceed the limit;
  options then are ranking only the newest N matches, or a separate title-only tsvector.
- **Threaded unified Inbox** (~90–100 ms p95): the window function counts every conversation of all Inbox folders before
  the first page is returned. Grows linearly with the Inbox size; a stored per-thread summary would make it constant.
- Task lists by context or project and the notes list by tag scan their (small) tables; at these volumes that is
  faster than an index lookup, so no index was added.
