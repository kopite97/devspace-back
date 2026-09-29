CREATE TABLE attachment_object_deletions (
    id UUID PRIMARY KEY,
    attachment_id UUID,
    document_id UUID,
    object_key TEXT NOT NULL UNIQUE,
    reason TEXT NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts >= 0),
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_until TIMESTAMP WITH TIME ZONE,
    lease_token UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_attachment_deletions_due ON attachment_object_deletions(next_attempt_at,id);
