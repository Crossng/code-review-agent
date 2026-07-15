create table test_run (
    id bigserial primary key,
    agent_run_id bigint not null references agent_run(id) on delete cascade,
    patch_id bigint not null references patch_record(id) on delete cascade,
    command text not null,
    exit_code integer not null,
    duration_ms integer not null,
    log_excerpt text,
    status varchar(50) not null,
    created_at timestamp with time zone not null
);

create index idx_test_run_agent_run_created on test_run(agent_run_id, created_at);
create index idx_test_run_patch_created on test_run(patch_id, created_at);
create index idx_test_run_status on test_run(status);
