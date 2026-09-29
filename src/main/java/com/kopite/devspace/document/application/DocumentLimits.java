package com.kopite.devspace.document.application;
import com.kopite.devspace.document.domain.DocumentException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
@Component
public class DocumentLimits {
    private final int contentBytes;
    public DocumentLimits(@Value("${app.document.max-content-bytes:1048576}")int contentBytes) {
        if(contentBytes<1||contentBytes>Integer.MAX_VALUE-65537)throw new IllegalArgumentException("Invalid document content limit");
        this.contentBytes=contentBytes;
    }
    public int bodyBytes(){return contentBytes+65536;}
    public void validateContent(String json){if(json.getBytes(StandardCharsets.UTF_8).length>contentBytes)throw new DocumentException("PAYLOAD_TOO_LARGE","content");}
}
