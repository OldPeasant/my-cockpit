---
title: HUMAN: connect real Microsoft calendar and mail
status: todo
owner:
deps: [h-oauth-azure,h-mail-real]
quality: exempt
quality_reason: human verification task, no code
hold: HUMAN TASK for the owner: after h-oauth-azure, connect the real Microsoft account
prio: 2
---
## Goal
Owner verifies the Microsoft side of h-cal-real and h-mail-real (split off so Google can be verified without
an Azure app).

## Acceptance criteria
- [ ] Event created in the cockpit appears in the Microsoft calendar within seconds; a Microsoft change appears in ≤ 5 min
- [ ] Microsoft 365/Outlook mail syncs; send/reply works; actions reflect in the provider

## Notes
