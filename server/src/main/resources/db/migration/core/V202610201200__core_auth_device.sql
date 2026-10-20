-- Devices of the Android app (SPEC.md §4.1). A device holds a short-lived access token and a long-lived refresh
-- token; only SHA-256 hashes of both are stored. Each refresh rotates both tokens. Revoking a device deletes its row.
create table auth_device (
    id                 uuid primary key,
    created_at         timestamptz not null,
    updated_at         timestamptz not null,
    version            bigint      not null,
    user_id            uuid        not null references app_user (id) on delete cascade,
    name               text        not null,
    access_token_hash  text        not null,
    access_expires_at  timestamptz not null,
    refresh_token_hash text        not null,
    refresh_expires_at timestamptz not null,
    last_seen_at       timestamptz not null
);

create unique index auth_device_access_token_hash on auth_device (access_token_hash);
create unique index auth_device_refresh_token_hash on auth_device (refresh_token_hash);
create index auth_device_user_id on auth_device (user_id);
create index auth_device_refresh_expires_at on auth_device (refresh_expires_at);

-- Refresh tokens replaced by a rotation. Presenting one again means the token leaked: the device is revoked.
create table auth_device_retired_token (
    id         uuid primary key,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version    bigint      not null,
    device_id  uuid        not null references auth_device (id) on delete cascade,
    token_hash text        not null
);

create unique index auth_device_retired_token_hash on auth_device_retired_token (token_hash);
create index auth_device_retired_token_device_id on auth_device_retired_token (device_id);
create index auth_device_retired_token_created_at on auth_device_retired_token (created_at);
