ALTER TABLE github_accounts DROP CONSTRAINT github_accounts_status_check;
ALTER TABLE github_accounts ADD CONSTRAINT github_accounts_status_check
    CHECK (status IN ('connected','reauthorization_required','disconnected'));
