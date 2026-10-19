CREATE TABLE github_webhook_inbox (
    delivery_id UUID PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    action VARCHAR(64) NOT NULL,
    installation_id BIGINT,
    repository_id BIGINT,
    github_user_id BIGINT,
    issue_number BIGINT,
    payload_hash VARCHAR(64) NOT NULL,
    payload TEXT,
    state TEXT NOT NULL DEFAULT 'queued' CHECK(state IN ('queued','running','completed','failed')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts>=0),
    available_at TIMESTAMPTZ NOT NULL,
    claim UUID,
    lease_until TIMESTAMPTZ,
    after_link UUID,
    error_code TEXT,
    received_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CHECK((claim IS NULL)=(lease_until IS NULL))
);
CREATE INDEX ix_github_inbox_dispatch ON github_webhook_inbox(available_at,received_at,delivery_id) WHERE state IN ('queued','running');
