alter table pull_request_record
    add column remote_pushed_at timestamp with time zone,
    add column opened_at timestamp with time zone,
    add column error_message text;
