CREATE TABLE github_delivery_recovery (
    id BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK(id),
    watermark TIMESTAMPTZ,
    scan_started_at TIMESTAMPTZ,
    cursor TEXT,
    next_run_at TIMESTAMPTZ NOT NULL,
    claim UUID,
    lease_until TIMESTAMPTZ,
    gap_pending BOOLEAN NOT NULL DEFAULT FALSE,
    after_link UUID,
    error_code TEXT,
    CHECK((claim IS NULL)=(lease_until IS NULL))
);
INSERT INTO github_delivery_recovery(id,next_run_at) VALUES(TRUE,now());
CREATE TABLE github_redelivery_requests (
    delivery_id BIGINT PRIMARY KEY,
    delivery_guid UUID NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    state TEXT NOT NULL DEFAULT 'queued' CHECK(state IN ('queued','completed','failed')),
    available_at TIMESTAMPTZ NOT NULL,
    requested_at TIMESTAMPTZ
);
CREATE INDEX ix_github_redelivery_pending ON github_redelivery_requests(available_at,delivery_id) WHERE state='queued';
