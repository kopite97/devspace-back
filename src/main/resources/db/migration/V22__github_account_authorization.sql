CREATE TABLE github_accounts (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE RESTRICT,
    github_user_id BIGINT NOT NULL CHECK (github_user_id > 0),
    login TEXT NOT NULL,
    encrypted_access_token TEXT,
    encrypted_refresh_token TEXT,
    expires_at TIMESTAMPTZ,
    refresh_expires_at TIMESTAMPTZ,
    status TEXT NOT NULL CHECK (status IN ('connected','reauthorization_required')),
    token_version BIGINT NOT NULL CHECK (token_version > 0),
    refresh_claim UUID,
    refresh_claim_until TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK ((refresh_claim IS NULL) = (refresh_claim_until IS NULL)),
    CHECK (status <> 'connected' OR
        (encrypted_access_token IS NOT NULL AND encrypted_refresh_token IS NOT NULL
         AND expires_at IS NOT NULL AND refresh_expires_at IS NOT NULL))
);
CREATE TABLE github_oauth_states (
    state_hash VARCHAR(64) PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    session_hash VARCHAR(64) NOT NULL,
    encrypted_verifier TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ
);
CREATE INDEX ix_github_oauth_states_expiry ON github_oauth_states(expires_at);
