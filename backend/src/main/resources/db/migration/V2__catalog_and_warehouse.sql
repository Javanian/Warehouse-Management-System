-- V2 catalog and warehouse master data.

-- Units of measure with the number of decimals a quantity may carry. No conversion between units.
create table uoms (
    code  varchar(10) primary key,
    name  varchar(40) not null,
    scale smallint    not null check (scale between 0 and 3)
);
insert into uoms (code, name, scale) values
    ('PC', 'Piece', 0), ('BOX', 'Box', 0), ('SET', 'Set', 0), ('ROLL', 'Roll', 0), ('PAIR', 'Pair', 0),
    ('KG', 'Kilogram', 3), ('L', 'Litre', 3), ('M', 'Metre', 2), ('M2', 'Square metre', 2);

create table suppliers (
    id         bigserial primary key,
    code       varchar(30)  not null,
    name       varchar(120) not null,
    active     boolean      not null default true,
    demo       boolean      not null default false,
    created_at timestamptz  not null default now(),
    updated_at timestamptz  not null default now(),
    version    bigint       not null default 0
);
create unique index ux_suppliers_code on suppliers (upper(code));

create table materials (
    id            bigserial primary key,
    code          varchar(40)    not null,
    name          varchar(160)   not null,
    description   varchar(1000),
    category      varchar(40)    not null,
    uom_code      varchar(10)    not null references uoms (code),
    minimum_stock numeric(18, 3) not null default 0 check (minimum_stock >= 0),
    active        boolean        not null default true,
    demo          boolean        not null default false,
    created_at    timestamptz    not null default now(),
    updated_at    timestamptz    not null default now(),
    version       bigint         not null default 0,
    constraint ck_materials_code check (code ~ '^[A-Za-z0-9][A-Za-z0-9._/-]*$')
);
create unique index ux_materials_code on materials (upper(code));
create index ix_materials_category on materials (category);
create index ix_materials_name_lower on materials (lower(name));

create table warehouses (
    id          bigserial primary key,
    code        varchar(20)  not null,
    name        varchar(120) not null,
    description varchar(500),
    active      boolean      not null default true,
    demo        boolean      not null default false,
    created_at  timestamptz  not null default now(),
    updated_at  timestamptz  not null default now(),
    version     bigint       not null default 0
);
create unique index ux_warehouses_code on warehouses (upper(code));

create table storage_locations (
    id            bigserial primary key,
    warehouse_id  bigint       not null references warehouses (id),
    code          varchar(30)  not null,
    name          varchar(120) not null,
    location_type varchar(20)  not null check (location_type in ('BIN', 'RACK', 'FLOOR', 'STAGING', 'QUARANTINE')),
    active        boolean      not null default true,
    demo          boolean      not null default false,
    created_at    timestamptz  not null default now(),
    updated_at    timestamptz  not null default now(),
    version       bigint       not null default 0
);
create unique index ux_locations_wh_code on storage_locations (warehouse_id, upper(code));
create index ix_locations_warehouse on storage_locations (warehouse_id);

-- A location never moves to another warehouse (history and balances depend on it).
create function forbid_location_warehouse_change() returns trigger language plpgsql as $$
begin
    if new.warehouse_id <> old.warehouse_id then
        raise exception 'storage location % cannot change warehouse', old.id using errcode = 'restrict_violation';
    end if;
    return new;
end $$;
create trigger trg_location_warehouse_fixed before update on storage_locations
    for each row execute function forbid_location_warehouse_change();
