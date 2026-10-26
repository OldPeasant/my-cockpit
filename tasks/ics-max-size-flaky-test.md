---
title: IcsFeedClient: refusing an oversized Content-Length sometimes surfaces as IOException ('could not be reached') instead of 'larger than'
status: in_progress
owner: dell26/agent-1
deps: []
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/ics,server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/ics]
prio: 2
lease_until: 2026-10-26T02:05:44Z
---
## Goal
`IcsFeedClientTests` "a feed of exactly the maximum size is read, a larger Content-Length is refused before reading"
failed in 2 of 2 full `server/gradlew -p server check` runs on dell26/agent-1 (2026-10-26, while working on
mail-sync-perf), and passed when its class ran alone. The refused fetch got "The feed server could not be reached
(IOException)" instead of "The feed is larger than ...". Likely cause: `LimitedBody(refuseAtOnce = true)` cancels the
body subscription, and the JDK HttpClient sometimes completes the future with its own IOException before the
subscriber's `tooLarge` error, so `fetchOnce` maps it to "could not be reached".

## Acceptance criteria
- [ ] An oversized Content-Length is always reported as "larger than" (the refusal's own error wins over the IOException the cancelled exchange causes)
- [ ] `IcsFeedClientTests` passes reliably inside the full `server/gradlew -p server check` run (several consecutive runs)

## Notes
