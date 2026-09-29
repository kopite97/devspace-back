package com.kopite.devspace.document.domain;

import java.util.UUID;

/** A stored attachment invariant failed; do not return a partial Document. */
public class DocumentIntegrityException extends RuntimeException {
    private final UUID documentId;
    public DocumentIntegrityException(UUID documentId,String reason){super(reason);this.documentId=documentId;}
    public UUID documentId(){return documentId;}
}
