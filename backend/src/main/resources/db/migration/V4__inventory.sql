-- V4 inventory: balances, immutable ledger, stock documents, idempotency, numbering.

-- Concurrency-safe document numbering (row lock on UPDATE ... RETURNING; rolled back with the transaction).
create table document_counters (
    doc_type   varchar(10) not null,
    year       int         not null,
    next_value bigint      not null default 1,
    primary key (doc_type, year)
);

create table inventory_balances (
    id          bigserial primary key,
    material_id bigint         not null references materials (id),
    location_id bigint         not null references storage_locations (id),
    quantity    numeric(18, 3) not null default 0,
    version     bigint         not null default 0,
    updated_at  timestamptz    not null default now(),
    constraint ck_balance_nonnegative check (quantity >= 0),
    constraint ux_balance_key unique (material_id, location_id)
);
create index ix_balance_location on inventory_balances (location_id);

create table stock_movements (
    id                      bigserial primary key,
    movement_number         varchar(30)  not null unique,
    movement_type           varchar(20)  not null check (movement_type in
        ('OPENING_BALANCE', 'GOODS_RECEIPT', 'ISSUE', 'TRANSFER', 'ADJUSTMENT_IN', 'ADJUSTMENT_OUT', 'REVERSAL')),
    document_type           varchar(20)  not null check (document_type in
        ('OPENING_BALANCE', 'GOODS_RECEIPT', 'STOCK_ISSUE', 'STOCK_TRANSFER', 'STOCK_ADJUSTMENT')),
    document_id             bigint,
    document_number         varchar(30)  not null,
    reversal_of_movement_id bigint unique references stock_movements (id),
    posted_by               bigint       not null references users (id),
    posted_at               timestamptz  not null default now(),
    business_date           date         not null,
    notes                   varchar(500),
    constraint ck_reversal_type check ((movement_type = 'REVERSAL') = (reversal_of_movement_id is not null))
);
create index ix_movement_document on stock_movements (document_type, document_id);
create index ix_movement_posted on stock_movements (posted_at desc, id desc);
create index ix_movement_business_date on stock_movements (business_date, movement_type);

create table stock_movement_entries (
    id             bigserial primary key,
    movement_id    bigint         not null references stock_movements (id),
    line_no        int            not null,
    material_id    bigint         not null references materials (id),
    location_id    bigint         not null references storage_locations (id),
    quantity_delta numeric(18, 3) not null check (quantity_delta <> 0),
    balance_after  numeric(18, 3) not null check (balance_after >= 0),
    constraint ux_entry_line unique (movement_id, line_no)
);
create index ix_entry_material_location on stock_movement_entries (material_id, location_id);
create index ix_entry_location on stock_movement_entries (location_id);

create trigger trg_movements_immutable before update or delete on stock_movements
    for each row execute function forbid_modification();
create trigger trg_movement_entries_immutable before update or delete on stock_movement_entries
    for each row execute function forbid_modification();

-- Posted documents: no delete; the only allowed update is POSTED -> REVERSED.
create function guard_posted_document() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception '% rows cannot be deleted', tg_table_name using errcode = 'restrict_violation';
    end if;
    if not (old.status = 'POSTED' and new.status = 'REVERSED'
            and new.movement_id = old.movement_id and new.document_number = old.document_number) then
        raise exception '% % is immutable (status % -> %)', tg_table_name, old.id, old.status, new.status
            using errcode = 'restrict_violation';
    end if;
    return new;
end $$;

create table goods_receipts (
    id                   bigint primary key,
    document_number      varchar(30)  not null unique,
    purchase_order_id    bigint       not null references purchase_orders (id),
    status               varchar(10)  not null check (status in ('POSTED', 'REVERSED')),
    reference            varchar(60),
    notes                varchar(500),
    movement_id          bigint       not null unique references stock_movements (id),
    posted_by            bigint       not null references users (id),
    posted_at            timestamptz  not null,
    business_date        date         not null,
    reversal_movement_id bigint unique references stock_movements (id),
    reversed_by          bigint references users (id),
    reversed_at          timestamptz,
    reversal_reason      varchar(500)
);
create sequence goods_receipts_id_seq owned by goods_receipts.id;
create index ix_gr_po on goods_receipts (purchase_order_id);
create index ix_gr_posted on goods_receipts (posted_at desc, id desc);

create table goods_receipt_items (
    id                     bigserial primary key,
    goods_receipt_id       bigint         not null references goods_receipts (id),
    line_no                int            not null,
    purchase_order_item_id bigint         not null references purchase_order_items (id),
    material_id            bigint         not null references materials (id),
    location_id            bigint         not null references storage_locations (id),
    quantity               numeric(18, 3) not null check (quantity > 0),
    constraint ux_gri_line unique (goods_receipt_id, line_no)
);
create index ix_gri_po_item on goods_receipt_items (purchase_order_item_id);

create table stock_issues (
    id                   bigint primary key,
    document_number      varchar(30)  not null unique,
    status               varchar(10)  not null check (status in ('POSTED', 'REVERSED')),
    reason_code          varchar(30)  not null check (reason_code in ('PRODUCTION', 'MAINTENANCE', 'INTERNAL_USE', 'SCRAP', 'SAMPLE')),
    reference            varchar(60)  not null,
    notes                varchar(500),
    movement_id          bigint       not null unique references stock_movements (id),
    posted_by            bigint       not null references users (id),
    posted_at            timestamptz  not null,
    business_date        date         not null,
    reversal_movement_id bigint unique references stock_movements (id),
    reversed_by          bigint references users (id),
    reversed_at          timestamptz,
    reversal_reason      varchar(500)
);
create sequence stock_issues_id_seq owned by stock_issues.id;
create index ix_issue_posted on stock_issues (posted_at desc, id desc);

create table stock_issue_items (
    id             bigserial primary key,
    stock_issue_id bigint         not null references stock_issues (id),
    line_no        int            not null,
    material_id    bigint         not null references materials (id),
    location_id    bigint         not null references storage_locations (id),
    quantity       numeric(18, 3) not null check (quantity > 0),
    constraint ux_sii_line unique (stock_issue_id, line_no)
);

create table stock_transfers (
    id                   bigint primary key,
    document_number      varchar(30)  not null unique,
    status               varchar(10)  not null check (status in ('POSTED', 'REVERSED')),
    reference            varchar(60),
    notes                varchar(500),
    movement_id          bigint       not null unique references stock_movements (id),
    posted_by            bigint       not null references users (id),
    posted_at            timestamptz  not null,
    business_date        date         not null,
    reversal_movement_id bigint unique references stock_movements (id),
    reversed_by          bigint references users (id),
    reversed_at          timestamptz,
    reversal_reason      varchar(500)
);
create sequence stock_transfers_id_seq owned by stock_transfers.id;
create index ix_transfer_posted on stock_transfers (posted_at desc, id desc);

create table stock_transfer_items (
    id                bigserial primary key,
    stock_transfer_id bigint         not null references stock_transfers (id),
    line_no           int            not null,
    material_id       bigint         not null references materials (id),
    from_location_id  bigint         not null references storage_locations (id),
    to_location_id    bigint         not null references storage_locations (id),
    quantity          numeric(18, 3) not null check (quantity > 0),
    constraint ck_sti_distinct check (from_location_id <> to_location_id),
    constraint ux_sti_line unique (stock_transfer_id, line_no)
);

create trigger trg_gr_guard before update or delete on goods_receipts
    for each row execute function guard_posted_document();
create trigger trg_issue_guard before update or delete on stock_issues
    for each row execute function guard_posted_document();
create trigger trg_transfer_guard before update or delete on stock_transfers
    for each row execute function guard_posted_document();
create trigger trg_gri_immutable before update or delete on goods_receipt_items
    for each row execute function forbid_modification();
create trigger trg_sii_immutable before update or delete on stock_issue_items
    for each row execute function forbid_modification();
create trigger trg_sti_immutable before update or delete on stock_transfer_items
    for each row execute function forbid_modification();

-- Physical-count adjustment request with approval. Approver can never be the requester.
create table stock_adjustments (
    id                       bigserial primary key,
    document_number          varchar(30)    not null unique,
    status                   varchar(10)    not null check (status in ('PENDING', 'APPROVED', 'REJECTED')),
    material_id              bigint         not null references materials (id),
    location_id              bigint         not null references storage_locations (id),
    physical_quantity        numeric(18, 3) not null check (physical_quantity >= 0),
    observed_quantity        numeric(18, 3) not null check (observed_quantity >= 0),
    observed_version         bigint         not null check (observed_version >= 0),
    reason                   varchar(500)   not null,
    requested_by             bigint         not null references users (id),
    requested_at             timestamptz    not null default now(),
    decided_by               bigint references users (id),
    decided_at               timestamptz,
    decision_note            varchar(500),
    movement_id              bigint unique references stock_movements (id),
    version                  bigint         not null default 0,
    constraint ck_adj_delta check (physical_quantity <> observed_quantity),
    constraint ck_adj_no_self_approval check (decided_by is null or decided_by <> requested_by),
    constraint ck_adj_decision check ((status = 'PENDING') = (decided_by is null)),
    constraint ck_adj_posting check ((status = 'APPROVED') = (movement_id is not null))
);
create index ix_adj_status on stock_adjustments (status, requested_at desc);

-- Idempotent command results, written in the same transaction as the business effect.
create table idempotency_records (
    id              bigserial primary key,
    actor_id        bigint       not null references users (id),
    operation       varchar(60)  not null,
    idem_key        varchar(100) not null,
    request_hash    char(64)     not null,
    response_status int,
    response_body   text,
    resource_id     bigint,
    created_at      timestamptz  not null default now(),
    constraint ux_idempotency unique (actor_id, operation, idem_key)
);

-- Demo seed bookkeeping (rerun-safe).
create table demo_seed_runs (
    seed_name  varchar(60) primary key,
    applied_at timestamptz not null default now(),
    summary    jsonb
);
