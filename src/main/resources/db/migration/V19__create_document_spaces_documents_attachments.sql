CREATE TABLE document_spaces (
    id UUID PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE documents (
    id UUID PRIMARY KEY,
    document_space_id UUID NOT NULL REFERENCES document_spaces(id) ON DELETE RESTRICT,
    title TEXT NOT NULL CHECK (length(title) <= 200),
    content JSONB NOT NULL CHECK (jsonb_typeof(content) = 'array'),
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT','PUBLISHED')),
    author_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_document_publication CHECK (
        (status = 'DRAFT' AND published_at IS NULL) OR
        (status = 'PUBLISHED' AND published_at IS NOT NULL)
    )
);
CREATE INDEX ix_documents_space_created ON documents(document_space_id,created_at DESC,id DESC);

CREATE TABLE attachments (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE RESTRICT,
    original_filename TEXT NOT NULL,
    object_key TEXT NOT NULL UNIQUE,
    content_type TEXT NOT NULL,
    size BIGINT NOT NULL CHECK (size > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('TEMP','ATTACHED')),
    created_by UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_attachments_document_status ON attachments(document_id,status,id);
CREATE INDEX ix_attachments_temp_updated ON attachments(updated_at,id) WHERE status = 'TEMP';
