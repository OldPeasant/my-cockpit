---
title: HUMAN: phase 1 on production
status: done
owner: Peter Gürtler
deps: [p1-smoke,h-deploy-p0]
quality: exempt
quality_reason: human verification task, no code
hold: 
prio: 2
releases: 0
extended: 
lease_until: 
---
## Goal
Owner deploys phase 1 and runs the demo script on production.

## Acceptance criteria
- [x] Phase 1 demo steps work on the production URL
  - verified (2026-11-01 06:31+0100 by Peter Gürtler): done

## Notes
- done by Peter Gürtler: `sh scripts/check.sh` passed in 97s on d69971fe1; criteria verified 1/1
