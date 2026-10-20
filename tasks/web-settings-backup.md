---
title: Settings: backup and restore
status: todo
owner:
deps: [p0-web-settings,backup-secrets]
touches: [web/src/app/features/settings/backup]
prio: 1
---
## Goal
Backup UI (SPEC.md §10.1).

## Acceptance criteria
- [ ] Create backup with optional passphrase, progress (SSE), download; restore: upload, show validation report, confirm with typed confirmation, progress, then forced re-login
- [ ] Vitest component/service tests cover report rendering and confirmation gate; `cd web && npm run check` passes

## Notes
