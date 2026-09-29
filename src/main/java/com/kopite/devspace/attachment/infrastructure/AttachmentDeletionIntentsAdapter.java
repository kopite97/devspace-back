package com.kopite.devspace.attachment.infrastructure;
import com.kopite.devspace.attachment.application.AttachmentDeletionIntents;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
@Repository @RequiredArgsConstructor
public class AttachmentDeletionIntentsAdapter implements AttachmentDeletionIntents {
    private final JdbcTemplate jdbc;
    public void enqueue(UUID attachment,UUID document,String key,String reason,Instant now) {
        jdbc.update("insert into attachment_object_deletions(id,attachment_id,document_id,object_key,reason,next_attempt_at,created_at) values(?,?,?,?,?,?,?) on conflict(object_key) do nothing",UUID.randomUUID(),attachment,document,key,reason,Timestamp.from(now),Timestamp.from(now));
    }
    public boolean pending(UUID attachment){return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from attachment_object_deletions where attachment_id=?)",Boolean.class,attachment));}
    public boolean anyPending(java.util.Collection<UUID> attachments) {
        if(attachments.isEmpty())return false;
        var parameters=String.join(",",java.util.Collections.nCopies(attachments.size(),"?"));
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from attachment_object_deletions where attachment_id in ("+parameters+"))",Boolean.class,attachments.toArray()));
    }
}
