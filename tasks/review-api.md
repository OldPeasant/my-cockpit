---
title: Weekly review API
status: in_progress
owner: dell26/agent-1
deps: [gtd-projects-api,cal-local-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/review,server/src/test/kotlin/ch/sonensei/cockpit/review,server/src/main/resources/db/migration/review]
prio: 2
lease_until: 2026-10-21T23:00:06Z
---
## Goal
Resumable weekly review (SPEC.md §8.6).

## Acceptance criteria
- [x] `weekly_review` with step progress; endpoints for step data: stale next actions (> 30 days), waiting-for, projects (stalled first), someday, past/upcoming events; complete review
  - verified (2026-10-22 00:33+0200 by dell26/agent-1): WeeklyReviewWebTests + WeeklyReviewEdgeCaseTests over HTTP (MockMvc, Postgres Testcontainers) at 25e7d874b: weekly_review table (Flyway V202610221000) with step progress; start/resume/move step/complete (If-Match), status; step data endpoints /api/v1/weekly-reviews/steps/{stale-next-actions,waiting-for,projects (stalled first),someday,past-events,upcoming-events} -> all asserted, 36 passed
- [x] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.review.*'` passes (Postgres via Testcontainers where persistence is involved)
  - verified (2026-10-22 00:33+0200 by dell26/agent-1): cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.review.*' jacocoTestReport (Postgres via Testcontainers) at 25e7d874b -> 36 passed, 0 failed, 0 skipped
- [x] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag
  - verified (2026-10-22 00:33+0200 by dell26/agent-1): cd server && UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest' (api/openapi.yaml regenerated, web client via npm run api:gen), then ./gradlew test --tests '*OpenApiContractTest' without the flag at 25e7d874b -> BUILD SUCCESSFUL

## Notes
- ASSUMPTION (2026-10-22 00:21+0200 by dell26/agent-1): Stale next action = open task on the NEXT list whose updatedAt is more than 30 days ago (olderThanDays param, default 30); SPEC §8.6 does not define 'stale'
- ASSUMPTION (2026-10-22 00:21+0200 by dell26/agent-1): Past calendar = the 7 local days before today (today excluded), upcoming = today plus the next 13 days (14 days), visible calendars only, cancelled occurrences left out
- ASSUMPTION (2026-10-22 00:22+0200 by dell26/agent-1): Someday step returns SOMEDAY-list tasks and SOMEDAY-status projects; projects step returns ACTIVE projects only, stalled first then by title; waiting-for sorted by waitingSince (nulls last)
- ASSUMPTION (2026-10-22 00:22+0200 by dell26/agent-1): Step data endpoints are stateless (live data, /api/v1/weekly-reviews/steps/*); a review only stores its current step; at most one open review (partial unique index), POST starts or resumes it; completing a completed review is 409. GET /weekly-reviews/status gives lastCompletedAt and daysSinceLastReview for the dashboard
- ASSUMPTION (2026-10-22 00:22+0200 by dell26/agent-1): Weekly reviews are not in search/links, publish no change events and have no backup contributor yet (no review backup task existed); added task backup-contrib-review
- ASSUMPTION (2026-10-22 00:22+0200 by dell26/agent-1): Step 1 (inbox/flagged mail) and step 8 (reflection/journal) have no dedicated data endpoint: clients use the existing inbox, mail and journal APIs
