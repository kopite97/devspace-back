ALTER TABLE github_accounts ADD COLUMN access_version BIGINT NOT NULL DEFAULT 1 CHECK(access_version>0);
ALTER TABLE github_repository_links ADD COLUMN access_account_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE github_repository_links ADD COLUMN access_claim UUID;
ALTER TABLE github_repository_links ADD COLUMN access_claim_until TIMESTAMPTZ;
UPDATE github_repository_links SET access_status='check_pending',access_expires_at=NULL;
CREATE INDEX ix_github_links_access_refresh ON github_repository_links(user_id,access_expires_at,id)
    WHERE status NOT IN ('disconnecting','disconnected');
