package com.kopite.devspace.attachment.application;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component
public record AttachmentLimits(long maxBytes) {
    public AttachmentLimits(@Value("${app.attachment.max-bytes:10485760}")long maxBytes){if(maxBytes<1)throw new IllegalArgumentException("Invalid attachment size");this.maxBytes=maxBytes;}
}
