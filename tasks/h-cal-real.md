---
title: HUMAN: connect real calendars
status: todo
owner: 
hold:
releases: 1
extended: 
deps: [p4-smoke, h-oauth-apps, h-prod-p2]
prio: 2
quality: exempt
quality_reason: human verification task, no code
lease_until: 
---
## Goal
Owner verifies two-way sync with a real Google calendar (Microsoft deferred, ADR 0003).

## Acceptance criteria
- [x] Event created in the cockpit appears in Google within seconds; a Google change appears in ≤ 5 min

## Notes
- release by dell26/agent-2: Auto-claimed by agent-2 by mistake: h-* tasks are human-only (owner must verify with a real Google calendar). No work done.
