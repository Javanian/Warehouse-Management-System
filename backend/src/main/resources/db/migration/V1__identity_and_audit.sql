-- V1 identity: users with a single role. Login by username or email (case-insensitive).
create table users (
    id            bigserial primary key,
    username      varchar(50)  not null,
    email         varchar(120) not null,
    full_name     varchar(120) not null,
    password_hash varchar(100) not null,
    role          varchar(20)  not null check (role in ('ADMIN', 'SUPERVISOR', 'OPERATOR', 'VIEWER')),
    active        boolean      not null default true,
    demo          boolean      not null default false,
    created_at    timestamptz  not null default now(),
    updated_at    timestamptz  not null default now(),
    version       bigint       not null default 0,
    constraint ck_users_username check (username ~ '^[a-zA-Z0-9._-]{3,50}$')
);
create unique index ux_users_username on users (lower(username));
create unique index ux_users_email on users (lower(email));

-- Business + security audit trail. Rows are append-only (trigger below).
create table audit_logs (
    id           bigserial primary key,
    occurred_at  timestamptz  not null default now(),
    actor_id     bigint       references users (id),
    actor_name   varchar(50),
    action       varchar(40)  not null,
    entity_type  varchar(40)  not null,
    entity_id    varchar(40),
    request_id   varchar(64),
    ip_address   varchar(64),
    details      jsonb
);
create index ix_audit_occurred on audit_logs (occurred_at desc, id desc);
create index ix_audit_entity on audit_logs (entity_type, entity_id);
create index ix_audit_actor on audit_logs (actor_id);

create function forbid_modification() returns trigger language plpgsql as $$
begin
    raise exception 'table % is append-only (% not allowed)', tg_table_name, tg_op
        using errcode = 'restrict_violation';
end $$;

create trigger trg_audit_logs_immutable before update or delete on audit_logs
    for each row execute function forbid_modification();
