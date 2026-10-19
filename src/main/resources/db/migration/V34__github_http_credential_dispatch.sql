CREATE TABLE github_http_dispatch (
    credential_hash VARCHAR(64) PRIMARY KEY,
    claim UUID,
    lease_until TIMESTAMPTZ,
    not_before TIMESTAMPTZ NOT NULL,
    touched_at TIMESTAMPTZ NOT NULL,
    CHECK((claim IS NULL)=(lease_until IS NULL))
);
