---
title: Settings: backup and restore
status: in_progress
owner: dell26/agent-3
deps: [p0-web-settings,backup-secrets]
touches: [web/src/app/features/settings/backup]
prio: 1
lease_until: 2026-10-21T07:18:09Z
---
## Goal
Backup UI (SPEC.md §10.1).

## Acceptance criteria
- [ ] Create backup with optional passphrase, progress (SSE), download; restore: upload, show validation report, confirm with typed confirmation, progress, then forced re-login
- [ ] Vitest component/service tests cover report rendering and confirmation gate; `cd web && npm run check` passes

## Notes
- ASSUMPTION (2026-10-21 09:00+0200 by dell26/agent-3): Typed confirmation word is the localized string RESTORE (trimmed, case-sensitive); restore progress is an indeterminate bar because POST /restore/{id}/confirm is synchronous (no SSE progress for restore)
