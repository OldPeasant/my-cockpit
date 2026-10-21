---
title: Settings: backup and restore
status: in_progress
owner: dell26/agent-3
deps: [p0-web-settings,backup-secrets]
touches: [web/src/app/features/settings/backup]
prio: 1
lease_until: 2026-10-21T07:23:08Z
---
## Goal
Backup UI (SPEC.md §10.1).

## Acceptance criteria
- [ ] Create backup with optional passphrase, progress (SSE), download; restore: upload, show validation report, confirm with typed confirmation, progress, then forced re-login
- [ ] Vitest component/service tests cover report rendering and confirmation gate; `cd web && npm run check` passes

## Notes
- ASSUMPTION (2026-10-21 09:00+0200 by dell26/agent-3): Typed confirmation word is the localized string RESTORE (trimmed, case-sensitive); restore progress is an indeterminate bar because POST /restore/{id}/confirm is synchronous (no SSE progress for restore)
- ASSUMPTION (2026-10-21 09:00+0200 by dell26/agent-3): 422 on confirm shows a generic 'nothing changed, check passphrase' message: ApiError drops the ProblemDetail 'problems' extension and core/http is outside this task's touches
- ASSUMPTION (2026-10-21 09:00+0200 by dell26/agent-3): The last started backup id is kept in the root BackupService (tab memory only) so progress/download reappear when returning to the section; the server has no list endpoint
- ASSUMPTION (2026-10-21 09:01+0200 by dell26/agent-3): Edited settings.routes.ts (+spec) outside touches to swap the backup placeholder for the real section, as the other settings section tasks did
