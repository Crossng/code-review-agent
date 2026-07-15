create table model_call_log (
    id bigserial primary key,
    agent_run_id bigint not null references agent_run(id) on delete cascade,
    step_name varchar(100) not null,
    model_provider varchar(100) not null,
    model_name varchar(100) not null,
    prompt_json jsonb,
    response_json jsonb,
    status varchar(50) not null,
    prompt_tokens int not null default 0,
    completion_tokens int not null default 0,
    total_tokens int not null default 0,
    duration_ms int not null,
    error_message text,
    started_at timestamp with time zone not null,
    finished_at timestamp with time zone not null
);

create index idx_model_call_log_run_created on model_call_log(agent_run_id, started_at);
create index idx_model_call_log_step on model_call_log(step_name);
create index idx_model_call_log_model on model_call_log(model_provider, model_name);
