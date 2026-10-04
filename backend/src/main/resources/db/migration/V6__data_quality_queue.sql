-- V6: Master data quality review queue

create table if not exists master_quality_issues (
    id                 bigserial primary key,
    issue_key          varchar(80) not null unique,
    rule_code          varchar(30) not null,
    rule_name          varchar(80) not null,
    severity           varchar(20) not null check (severity in ('HIGH', 'MEDIUM', 'LOW')),
    entity_type        varchar(30) not null,
    entity_id          bigint not null,
    entity_code        varchar(60) not null,
    entity_name        varchar(160),
    detected_detail    text not null,
    recommended_action text not null,
    status             varchar(20) not null check (status in ('OPEN', 'RESOLVED', 'IGNORED')),
    resolved_by        bigint references users(id),
    resolved_at        timestamptz,
    resolution_notes   text,
    created_at         timestamptz not null default now()
);

create index if not exists ix_mqi_status on master_quality_issues (status);
create index if not exists ix_mqi_severity on master_quality_issues (severity);
create index if not exists ix_mqi_rule on master_quality_issues (rule_code);
