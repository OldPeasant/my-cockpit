---
title: HUMAN: connect real calendars
status: done
owner: Peter Gürtler
hold: given back 3 times; last: Auto-claimed by agent-2 by mistake (only ready task): h-* tasks are human-only; owner must verify with a real Google calendar. No work done. Suggest tasks.py claim skip h-* t
releases: 3
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
  - verified (2026-11-01 21:48+0100 by Peter Gürtler): Real Google calendar connected; event created in cockpit appeared in Google within seconds, Google change appeared in cockpit within 5 min -> works

## Notes
- release by dell26/agent-2: Auto-claimed by agent-2 by mistake: h-* tasks are human-only (owner must verify with a real Google calendar). No work done.
- release by dell26/agent-2: Auto-claimed by agent-2 by mistake: h-* tasks are human-only (owner must verify with a real Google calendar). No work done.
- release by dell26/agent-2: Auto-claimed by agent-2 by mistake (only ready task): h-* tasks are human-only; owner must verify with a real Google calendar. No work done. Suggest tasks.py claim skip h-* tasks.
- done by Peter Gürtler: check skipped (--skip-check); criteria verified 1/1
