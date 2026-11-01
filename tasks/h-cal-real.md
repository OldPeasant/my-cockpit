---
title: HUMAN: connect real calendars
status: in_progress
owner: dell26/agent-2
hold:
releases: 0
extended:
deps: [p4-smoke, h-oauth-apps, h-prod-p2]
prio: 2
quality: exempt
quality_reason: human verification task, no code
lease_until: 2026-11-01T21:05:33Z
---
## Goal
Owner verifies two-way sync with a real Google calendar (Microsoft deferred, ADR 0003).

## Acceptance criteria
- [x] Event created in the cockpit appears in Google within seconds; a Google change appears in ≤ 5 min

## Notes
