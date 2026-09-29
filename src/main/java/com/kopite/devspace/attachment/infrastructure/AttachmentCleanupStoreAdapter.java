package com.kopite.devspace.attachment.infrastructure;
import com.kopite.devspace.attachment.application.AttachmentCleanupStore;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
@Repository @RequiredArgsConstructor
public class AttachmentCleanupStoreAdapter implements AttachmentCleanupStore {
    private final JdbcTemplate jdbc;
    public List<UUID> expired(Instant cutoff,int limit){return jdbc.queryForList("select a.id from attachments a where status='TEMP' and updated_at<? and not exists(select 1 from attachment_object_deletions d where d.attachment_id=a.id) order by updated_at,id limit ?",UUID.class,Timestamp.from(cutoff),limit);}
    public List<UUID> due(Instant now,int limit){return jdbc.queryForList("select id from attachment_object_deletions where next_attempt_at<=? and (lease_until is null or lease_until<=?) order by next_attempt_at,id limit ?",UUID.class,Timestamp.from(now),Timestamp.from(now),limit);}
    public Optional<Job> get(UUID id){return jdbc.query("select * from attachment_object_deletions where id=?",(r,n)->new Job(r.getObject("id",UUID.class),r.getObject("attachment_id",UUID.class),r.getObject("document_id",UUID.class),r.getString("object_key"),r.getObject("lease_token",UUID.class)),id).stream().findFirst();}
    public Optional<Job> lease(UUID id,Instant now,Instant until) {
        UUID token=UUID.randomUUID();
        return jdbc.query("update attachment_object_deletions set lease_token=?,lease_until=? where id=? and next_attempt_at<=? and (lease_until is null or lease_until<=?) returning *",(r,n)->new Job(r.getObject("id",UUID.class),r.getObject("attachment_id",UUID.class),r.getObject("document_id",UUID.class),r.getString("object_key"),token),token,Timestamp.from(until),id,Timestamp.from(now),Timestamp.from(now)).stream().findFirst();
    }
    public boolean finish(Job job){return jdbc.update("delete from attachment_object_deletions where id=? and lease_token=?",job.id(),job.token())==1;}
    public void retry(Job job,Instant next){jdbc.update("update attachment_object_deletions set attempts=least(attempts::bigint+1,2147483647)::integer,next_attempt_at=?,lease_until=null,lease_token=null where id=? and lease_token=?",Timestamp.from(next),job.id(),job.token());}
    public Optional<UUID> jobFor(UUID attachment){return jdbc.queryForList("select id from attachment_object_deletions where attachment_id=?",UUID.class,attachment).stream().findFirst();}
    public void purgeDocument(UUID document,Instant now){purge("id=?",document,now);}
    public void purgeSpace(UUID space,Instant now){purge("document_space_id=?",space,now);}
    private void purge(String predicate,UUID id,Instant now) {
        // Locks stay in the database: neither binaries nor unbounded metadata collections enter JVM memory.
        jdbc.queryForObject("select count(*) from (select id from documents where "+predicate+" order by id for update) locked",Long.class,id);
        String selected="select id from documents where "+predicate;
        jdbc.queryForObject("select count(*) from (select id from attachments where document_id in ("+selected+") order by id for update) locked",Long.class,id);
        jdbc.update("insert into attachment_object_deletions(id,attachment_id,document_id,object_key,reason,next_attempt_at,created_at) select gen_random_uuid(),id,document_id,object_key,'PARENT_DELETE',?,? from attachments where document_id in ("+selected+") on conflict(object_key) do nothing",Timestamp.from(now),Timestamp.from(now),id);
        jdbc.update("delete from attachments where document_id in ("+selected+")",id);
        jdbc.update("delete from documents where "+predicate,id);
    }
}
