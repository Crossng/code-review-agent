create table tool_call_log (
    id bigserial primary key,
    agent_run_id bigint not null references agent_run(id) on delete cascade,
    tool_name varchar(100) not null,
    input_json jsonb,
    output_json jsonb,
    status varchar(50) not null,
    duration_ms int not null,
    error_message text,
    started_at timestamp with time zone not null,
    finished_at timestamp with time zone not null
);

create index idx_tool_call_log_run_created on tool_call_log(agent_run_id, started_at);
create index idx_tool_call_log_name on tool_call_log(tool_name);
