-- V5: Agent identity isolation, agent runs audit, and document drafts

alter table users drop constraint if exists users_role_check;
alter table users add constraint users_role_check check (role in ('ADMIN', 'SUPERVISOR', 'OPERATOR', 'VIEWER', 'AGENT'));

-- Trigger preventing any AGENT user from being recorded as posted_by in stock_movements
create or replace function forbid_agent_stock_mutation() returns trigger language plpgsql as $$
declare
    v_role varchar(20);
begin
    select role into v_role from users where id = new.posted_by;
    if v_role = 'AGENT' then
        raise exception 'AGENT_CANNOT_MUTATE_STOCK: Agent identity has draft-only permissions and is strictly forbidden from mutating inventory'
            using errcode = 'restrict_violation';
    end if;
    return new;
end $$;

create trigger trg_stock_movements_no_agent
    before insert on stock_movements
    for each row execute function forbid_agent_stock_mutation();

-- Agent execution runs audit
create table agent_runs (
    id              bigserial primary key,
    run_id          varchar(64) not null unique,
    agent_id        bigint not null references users(id),
    task_type       varchar(40) not null,
    status          varchar(20) not null check (status in ('RUNNING', 'COMPLETED', 'FAILED', 'ABSTAINED')),
    triggered_by    bigint references users(id),
    started_at      timestamptz not null default now(),
    finished_at     timestamptz,
    latency_ms      bigint,
    model_name      varchar(64),
    prompt_version  varchar(32),
    input_hash      varchar(64),
    error_reason    text,
    summary         jsonb
);
create index ix_agent_runs_status on agent_runs (status);
create index ix_agent_runs_started on agent_runs (started_at desc);

-- Tool calls audit log
create table agent_tool_calls (
    id              bigserial primary key,
    run_id          varchar(64) not null references agent_runs(run_id),
    call_sequence   int not null,
    tool_name       varchar(60) not null,
    parameters      jsonb,
    result_status   varchar(20) not null check (result_status in ('SUCCESS', 'ERROR', 'DENIED')),
    result_summary  jsonb,
    executed_at     timestamptz not null default now()
);
create index ix_agent_tool_calls_run on agent_tool_calls (run_id, call_sequence);

-- Document drafts (Draft-only boundary: Agent writes here, never directly to Goods Receipt)
create table document_drafts (
    id                     bigserial primary key,
    draft_id               varchar(64) not null unique,
    run_id                 varchar(64) references agent_runs(run_id),
    source_document_path   varchar(255) not null,
    source_document_sha256 varchar(64) not null,
    detected_po_number     varchar(50),
    detected_delivery_note varchar(50),
    detected_supplier_code varchar(50),
    detected_delivery_date date,
    status                 varchar(20) not null check (status in ('DRAFT', 'UNDER_REVIEW', 'CONFIRMED', 'REJECTED')),
    confidence_score       numeric(4, 3) not null,
    validation_outcome     varchar(20) not null check (validation_outcome in ('VALID', 'INVALID', 'REVIEW')),
    validation_reason      text,
    provenance             jsonb,
    line_items             jsonb not null,
    created_at             timestamptz not null default now(),
    reviewed_by            bigint references users(id),
    reviewed_at            timestamptz,
    human_decision         varchar(20) check (human_decision in ('ACCEPTED', 'REJECTED', 'MODIFIED')),
    posted_gr_document_number varchar(50)
);
create index ix_document_drafts_status on document_drafts (status);
create index ix_document_drafts_po on document_drafts (upper(detected_po_number));
