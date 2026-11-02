---
title: HUMAN: connect real mail accounts
status: in_progress
owner: Peter Gürtler
hold: 
releases: 0
extended: 
deps: [p5-smoke, h-oauth-apps, h-cal-real]
prio: 2
quality: exempt
quality_reason: human verification task, no code
lease_until: 
---
## Goal
Owner connects Gmail and triages a day of mail (Microsoft 365 deferred, ADR 0003).

## Acceptance criteria
- [x] Real Gmail account syncs; send/reply works; actions reflect in the provider
  - verified (2026-11-02 07:49+0100 by Peter Gürtler): Real Gmail account connected via Google OAuth; mail syncs, send and reply work, archive/delete/read state show up in Gmail -> works

## Notes
- release by dell26/agent-1: Auto-claimed by agent-1 via 'claim' with no id; h-* tasks are human-only per AGENTS.md, released untouched. Criterion already shows [x]; owner should run done.
- release by dell26/agent-2: Auto-claimed by agent-2 via 'claim' with no id; h-* tasks are human-only per AGENTS.md, released untouched. Criterion already [x]; owner should run done.
- release by dell26/agent-2: Auto-claimed by agent-2 via 'claim' with no id; h-* tasks are human-only per AGENTS.md, released untouched. Criterion already [x]; owner should run done. tasks.py claim (no id) should skip h-* tasks.
