---
title: Quick capture endpoint and syntax parser
status: in_progress
owner: dell26/agent-6
deps: [gtd-tickler]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/gtd,server/src/test/kotlin/ch/sonensei/cockpit/gtd,server/src/main/resources/db/migration/gtd]
prio: 1
lease_until: 2026-10-20T21:26:19Z
releases: 1
extended: 
---
## Goal
Idempotent capture used by web and Android (SPEC.md §4.5, §5.4).

## Acceptance criteria
- [ ] `POST /api/v1/capture {id?, text, notes?, fileIds?, parse?}` creates an INBOX task; a repeated client `id` returns 200 with the existing task (no duplicate, test)
- [ ] Parser (pure function, unit-tested): `#project` (existing by name, case-insensitive, else kept as text), `@context`, `!today|!tomorrow|!YYYY-MM-DD` due, `^waiting`; `POST /api/v1/capture/preview` returns the parse result
- [ ] `fileIds` are linked to the task via the link system
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
- ASSUMPTION (2026-10-20 21:36+0200 by dell26/agent-4): Capture syntax: tokens only count at the start of the text or after whitespace (URLs/emails untouched); resolved tokens are removed from the title, unresolved ones (#unknown project, @unknown context, invalid !date) stay as text. Contexts are not auto-created. First resolved #project/!due/^waiting wins, contexts accumulate.
- ASSUMPTION (2026-10-20 21:36+0200 by dell26/agent-4): ^waiting: '^waiting' (any case) puts the capture on the WAITING list; '^<name>' (e.g. ^Anna) puts it on WAITING with waitingForText=<name>. Otherwise captures go to the INBOX. SPEC only names the token, not its argument.
- ASSUMPTION (2026-10-20 21:36+0200 by dell26/agent-4): #project matches non-deleted ACTIVE/ON_HOLD/SOMEDAY projects by title, case-insensitive, with '-'/'_' standing for spaces (#home-renovation); ACTIVE preferred, then oldest. @context matches the context name with or without its leading @.
- ASSUMPTION (2026-10-20 21:36+0200 by dell26/agent-4): POST /capture: 'parse' defaults to true (Android share can send false). Title = first non-blank line of the (cleaned) text, cut at 500 chars; the rest of the text goes in front of 'notes' so long shared text is not lost.
- ASSUMPTION (2026-10-20 22:05+0200 by dell26/agent-4): Relative due tokens (!today/!tomorrow) are resolved with the server clock when the capture request arrives; an offline capture uploaded on a later day gets the later date. The Android capture queue (and-capture-queue) may resolve them client-side before queueing; no capturedAt field was added (review CR-5).
- release by dell26/agent-4: Implementation complete (endpoint, idempotent id, parser, preview, fileIds links, openapi regenerated + web client); review.md + fixes.md committed; edge-case tests committed in 156c8026; ./gradlew test --tests 'ch.sonensei.cockpit.gtd.*' passes (capture suites 90 tests, 0 failures). LEFT: run test-writer on final code to produce reviews/gtd-capture-api/tests.md (with coverage); re-run code-reviewer (review.md predates fixes 31493a41/4a765483 + new tests); tasks.py verify criteria 1-5; OpenApiContractTest without flag; .taskcheck; write quality.json; done.
