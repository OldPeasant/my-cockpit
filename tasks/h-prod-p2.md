---
title: HUMAN: phase 2 on production + real restore drill
status: done
owner: Peter Gürtler
hold: 
deps: [p2-smoke, h-prod-p1]
prio: 2
quality: exempt
quality_reason: human verification task, no code
releases: 0
extended: 
lease_until: 
---
## Goal
Owner deploys and runs a real backup/restore drill on a spare machine.

## Acceptance criteria
- [x] Backup from production restores on a spare install; data identical

## Notes
- done by Peter Gürtler: check skipped (--skip-check); criteria verified 1/1
