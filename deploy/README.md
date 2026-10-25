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

Passkeys (optional, all empty by default):

| Variable | Default when empty | Purpose |
|---|---|---|
| `COCKPIT_AUTH_PASSKEY_RP_ID` | `COCKPIT_DOMAIN` | Domain the passkeys are bound to |
| `COCKPIT_AUTH_PASSKEY_ORIGINS` | `https://<COCKPIT_DOMAIN>` | Comma-separated accepted origins. Replaces the default, so list the web origin too |
| `COCKPIT_AUTH_PASSKEY_RP_NAME` | `My Cockpit` | Name the authenticator shows |

The web app works without them. Passkeys in the Android app need its origin in `COCKPIT_AUTH_PASSKEY_ORIGINS`:

```sh
COCKPIT_AUTH_PASSKEY_ORIGINS=https://cockpit.example.org,android:apk-key-hash:REPLACE_WITH_APK_KEY_HASH
```

The hash is the base64url-encoded (no padding) SHA-256 of the APK's signing certificate:

```sh
keytool -printcert -jarfile cockpit.apk | python3 -c 'import base64,re,sys; print(base64.urlsafe_b64encode(bytes.fromhex(re.search(r"SHA256: (\S+)", sys.stdin.read()).group(1).replace(":", ""))).decode().rstrip("="))'
```

Restart the server after changing it: `docker compose -f deploy/docker-compose.yml up -d server`.

Security headers (HSTS, CSP, `X-Content-Type-Options`, `frame-ancestors`, ...) are set in `Caddyfile`.
They replace the server's headers, except `Content-Security-Policy`: Caddy's app-wide CSP is only a default,
used when the server sent none, so the server can send a stricter CSP for a single response.
Caddy hides `/actuator/*` except `/actuator/health`.

## Android app

CI builds the release APK (job `android-apk`, artifact `cockpit-android`). It is signed with the keystore in the
repository secrets `ANDROID_KEYSTORE_BASE64` (`base64 -w0 release.jks`), `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`; without them it is debug-signed (fine for testing, but keep one
key: Android only installs an update signed with the same key). Copy both files of the artifact into the data dir,
then logged-in users can download the app at `https://<COCKPIT_DOMAIN>/download/android`:

```sh
docker compose -f deploy/docker-compose.yml exec server mkdir -p /data/android
docker compose -f deploy/docker-compose.yml cp cockpit.apk server:/data/android/cockpit.apk
docker compose -f deploy/docker-compose.yml cp output-metadata.json server:/data/android/output-metadata.json
```

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
