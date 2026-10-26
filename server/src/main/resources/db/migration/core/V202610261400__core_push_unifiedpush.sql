-- UnifiedPush endpoints of the owner's devices: one row per UnifiedPush instance of an app installation. The server
-- POSTs a small push (kind + id) to every endpoint when a reminder fires or new mail arrives.
-- Not backed up: like sessions and devices they belong to one installation, which registers again after a restore.
-- p256dh/auth are the device's Web Push keys (base64url, RFC 8291); with them pushes are encrypted (aes128gcm).
create table push_unifiedpush_registration (
    id          uuid primary key,
    created_at  timestamptz not null,
    updated_at  timestamptz not null,
    version     bigint      not null,
    instance_id text        not null,
    endpoint    text        not null,
    p256dh      text,
    auth        text,
    constraint push_unifiedpush_registration_instance unique (instance_id),
    constraint push_unifiedpush_registration_keys check ((p256dh is null) = (auth is null))
);
