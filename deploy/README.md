# Deploying My Cockpit

The stack (SPEC.md §11) is three containers from `docker-compose.yml`:

| Service | Image | Role |
|---|---|---|
| `caddy` | `caddy:2` | TLS (Let's Encrypt for `COCKPIT_DOMAIN`), security headers, reverse proxy. The only service with published ports |
| `server` | built from the repo's `Dockerfile` | Spring Boot API + the Angular SPA under `/`, runs as uid 10001 |
| `postgres` | `postgres:16-alpine` | Database |

Volumes: `postgres-data` (database), `cockpit-data` (the server's data dir `/data`: files, blobs),
`caddy-data` (certificates, keep it to avoid hitting Let's Encrypt rate limits), `caddy-config`.
Compose prefixes them with the project name (`cockpit_postgres-data`, ...).

## First deploy

Needs a Linux host with Docker (Compose v2) or Podman (+ `podman-compose`), a DNS name pointing to it,
and ports 80 and 443 open (80 is needed for the ACME HTTP challenge).

1. Get the code: `git clone <repo> /opt/my-cockpit && cd /opt/my-cockpit`.
2. Generate the configuration: `sh deploy/gen-env.sh cockpit.example.org`. It writes `deploy/.env`
   (mode 600) with a random `COCKPIT_MASTER_KEY` and database password. Review the other values in it.
3. **Back up `deploy/.env` outside the server** (password manager). Without `COCKPIT_MASTER_KEY` the stored
   mail passwords and OAuth tokens can't be decrypted, also not from a backup.
4. Build and start: `docker compose -f deploy/docker-compose.yml up -d --build`. The first build takes a
   few minutes (npm and Gradle downloads).
5. Check: `curl https://cockpit.example.org/actuator/health` → `{"status":"UP"}`.
   Logs: `docker compose -f deploy/docker-compose.yml logs -f server` (JSON lines).
6. Open `https://cockpit.example.org` and run the setup (owner account, TOTP).
7. Set up the host backup (below).

### Local test (no public domain)

```sh
sh deploy/gen-env.sh localhost
# rootless Podman can't bind ports < 1024: set COCKPIT_HTTP_PORT=18080 and COCKPIT_HTTPS_PORT=18443 in deploy/.env
docker compose -f deploy/docker-compose.yml up -d --build
curl -k https://localhost:18443/actuator/health     # -k: certificate from Caddy's internal CA
docker compose -f deploy/docker-compose.yml down     # add -v to delete the volumes too
```

## Update

```sh
git pull
docker compose -f deploy/docker-compose.yml up -d --build
```

Flyway migrates the database on startup. Take a backup first (in-app backup or `host-backup.sh`).

## Configuration

All settings are in `deploy/.env` (template: `.env.example`): `COCKPIT_DOMAIN`, `COCKPIT_MASTER_KEY`,
`POSTGRES_PASSWORD`, the published ports, and the optional OAuth apps (`GOOGLE_CLIENT_ID/SECRET`,
`MS_CLIENT_ID/SECRET/TENANT`). The server gets `SPRING_DATASOURCE_*` and `COCKPIT_DATA_DIR=/data` from the
compose file.

Security headers (HSTS, CSP, `X-Content-Type-Options`, `frame-ancestors`, ...) are set in `Caddyfile`.
They replace the server's headers, except `Content-Security-Policy`: Caddy's app-wide CSP is only a default,
used when the server sent none, so the server can send a stricter CSP for a single response.
Caddy hides `/actuator/*` except `/actuator/health`.

## Host backup

The in-app backup (Settings → Backup) runs on demand only. Also back up the host regularly:
`deploy/host-backup.sh` is an example that dumps the database (`pg_dump -Fc`) and the data dir (tar) and
stores both with [restic](https://restic.net):

```sh
export RESTIC_REPOSITORY=sftp:backup@nas:/cockpit RESTIC_PASSWORD_FILE=/root/.restic-pw
sh deploy/host-backup.sh        # e.g. daily from cron; keeps 7 daily, 4 weekly, 12 monthly snapshots
```

Restore into a fresh stack (same `deploy/.env`):

```sh
restic restore latest --tag cockpit --target /tmp/restore
docker compose -f deploy/docker-compose.yml up -d postgres
docker compose -f deploy/docker-compose.yml exec -T postgres \
  pg_restore -U cockpit -d cockpit --clean --if-exists < /tmp/restore/var/tmp/cockpit-backup/cockpit.pgdump
docker compose -f deploy/docker-compose.yml run --rm -T --no-deps --entrypoint tar server \
  -C /data -xf - < /tmp/restore/var/tmp/cockpit-backup/data.tar
docker compose -f deploy/docker-compose.yml up -d
```
