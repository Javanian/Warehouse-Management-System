-- V3 procurement: purchase orders with items. received_quantity changes only through goods receipt posting/reversal.
create table purchase_orders (
    id            bigserial primary key,
    po_number     varchar(30)  not null,
    supplier_id   bigint       not null references suppliers (id),
    po_date       date         not null,
    expected_date date,
    status        varchar(20)  not null check (status in ('DRAFT', 'OPEN', 'PARTIALLY_RECEIVED', 'COMPLETED', 'CANCELLED')),
    notes         varchar(500),
    cancel_reason varchar(500),
    created_by    bigint       not null references users (id),
    created_at    timestamptz  not null default now(),
    updated_at    timestamptz  not null default now(),
    opened_at     timestamptz,
    cancelled_at  timestamptz,
    demo          boolean      not null default false,
    version       bigint       not null default 0
);
create unique index ux_po_number on purchase_orders (upper(po_number));
create index ix_po_status on purchase_orders (status);
create index ix_po_supplier on purchase_orders (supplier_id);

create table purchase_order_items (
    id                bigserial primary key,
    purchase_order_id bigint         not null references purchase_orders (id),
    line_no           int            not null check (line_no > 0),
    material_id       bigint         not null references materials (id),
    uom_code          varchar(10)    not null references uoms (code),
    ordered_quantity  numeric(18, 3) not null check (ordered_quantity > 0),
    received_quantity numeric(18, 3) not null default 0 check (received_quantity >= 0),
    version           bigint         not null default 0,
    constraint ck_poi_not_over_received check (received_quantity <= ordered_quantity),
    constraint ux_poi_line unique (purchase_order_id, line_no)
);
create index ix_poi_material on purchase_order_items (material_id);
