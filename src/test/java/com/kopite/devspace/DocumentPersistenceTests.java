package com.kopite.devspace;

import com.kopite.devspace.document.domain.*;
import com.kopite.devspace.documentspace.domain.*;
import com.kopite.devspace.attachment.domain.*;
import com.kopite.devspace.user.application.UserWorkspaceCreationService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test") @Import(TestcontainersConfiguration.class)
class DocumentPersistenceTests {
    @Autowired UserWorkspaceCreationService users;
    @Autowired DocumentSpaceRepository spaces;
    @Autowired DocumentRepository documents;
    @Autowired AttachmentRepository attachments;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    final Instant now=Instant.parse("2026-09-23T00:00:00.123456789Z");
    TransactionTemplate tx(){return new TransactionTemplate(manager);}
    UUID owner(){return users.createOrReuse("document-persistence",UUID.randomUUID().toString(),"Owner").user().getId();}

    @Test void opaqueJsonAndAuditRoundTripThroughFlushClear() {
        UUID user=owner();
        String json="[{\"type\":\"future-block\",\"unknown\":{\"array\":[null,true,12345678901234567890,1.2300,\"\\uD55C\\uAE00\\uD83D\\uDE00\"]},\"children\":[]}]";
        tx().executeWithoutResult(s->{
            var space=DocumentSpace.create(now);spaces.insert(space);
            var document=Document.draft(space.getId(),user,"  title  ",new DocumentContent(json),now);documents.insert(document);
            documents.flush();em.clear();
            var saved=documents.find(document.getId(),false).orElseThrow();
            assertEquals("  title  ",saved.getTitle());assertEquals(Document.Status.DRAFT,saved.getStatus());
            assertNull(saved.getPublishedAt());assertEquals(now.truncatedTo(java.time.temporal.ChronoUnit.MICROS),saved.getCreatedAt());
            assertEquals(Boolean.TRUE,jdbc.queryForObject("select content=?::jsonb from documents where id=?",Boolean.class,json,document.getId()));
            assertEquals(space.getId(),saved.getDocumentSpaceId());
            assertTrue(saved.publish(now.plusSeconds(1)));var published=saved.getPublishedAt();
            saved.save("",new DocumentContent("[]"),now.plusSeconds(2));
            assertFalse(saved.publish(now.plusSeconds(3)));documents.flush();em.clear();
            saved=documents.find(document.getId(),false).orElseThrow();
            assertEquals("[]",saved.getContent());assertEquals(published,saved.getPublishedAt());
            assertEquals(Document.Status.PUBLISHED,saved.getStatus());
        });
    }
    @Test void attachmentTransitionsRestartGraceOnlyOnActualChange() {
        UUID user=owner();
        tx().executeWithoutResult(s->{
            var space=DocumentSpace.create(now);spaces.insert(space);
            var document=Document.draft(space.getId(),user,"",new DocumentContent("[]"),now);documents.insert(document);
            UUID id=UUID.randomUUID();
            var attachment=Attachment.temporary(id,document.getId(),user,"file.txt","documents/"+document.getId()+"/"+id+".txt","text/plain",12,now);
            attachments.insert(attachment);attachments.flush();em.clear();
            attachment=attachments.find(id,true).orElseThrow();assertEquals(Attachment.Status.TEMP,attachment.getStatus());
            var created=attachment.getCreatedAt();assertFalse(attachment.detach(now.plusSeconds(1)));assertEquals(created,attachment.getUpdatedAt());
            assertTrue(attachment.attach(now.plusSeconds(2)));var attached=attachment.getUpdatedAt();
            assertFalse(attachment.attach(now.plusSeconds(3)));assertEquals(attached,attachment.getUpdatedAt());
            assertTrue(attachment.detach(now.plusSeconds(4)));assertTrue(attachment.attach(now.plusSeconds(5)));
            attachments.flush();em.clear();attachment=attachments.find(id,false).orElseThrow();
            assertEquals(created,attachment.getCreatedAt());assertEquals(now.plusSeconds(5).truncatedTo(java.time.temporal.ChronoUnit.MICROS),attachment.getUpdatedAt());
        });
    }
    @Test void domainRejectsMalformedContentAndInvalidCreation() {
        for(String json:new String[]{"{}","null","[","[] []","\"[]\""})assertThrows(DocumentException.class,()->new DocumentContent(json));
        assertThrows(DocumentException.class,()->Document.draft(UUID.randomUUID(),UUID.randomUUID(),"x".repeat(201),new DocumentContent("[]"),now));
        assertThrows(DocumentException.class,()->Document.draft(UUID.randomUUID(),UUID.randomUUID(),"\uD83D\uDE00".repeat(101),new DocumentContent("[]"),now));
        assertThrows(IllegalArgumentException.class,()->Attachment.temporary(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"a","k","text/plain",0,now));
    }
    @Test void databaseRejectsBrokenReferencesShapeAndPublicationAndRollsBack() {
        UUID user=owner();
        UUID document=tx().execute(s->{var space=DocumentSpace.create(now);spaces.insert(space);var d=Document.draft(space.getId(),user,"",new DocumentContent("[]"),now);documents.insert(d);return d.getId();});
        for(String assignment:new String[]{"document_space_id=null","document_space_id=gen_random_uuid()","author_id=gen_random_uuid()","content='{}'::jsonb","content='null'::jsonb","status='OTHER'","status='PUBLISHED'","published_at=now()","title=null"})
            assertThrows(RuntimeException.class,()->tx().executeWithoutResult(s->jdbc.update("update documents set "+assignment+" where id=?",document)),assignment);
        assertEquals("DRAFT",jdbc.queryForObject("select status from documents where id=?",String.class,document));
        assertThrows(IllegalStateException.class,()->tx().executeWithoutResult(s->{var space=DocumentSpace.create(now);spaces.insert(space);spaces.flush();throw new IllegalStateException("rollback");}));
        assertEquals(1L,jdbc.queryForObject("select count(*) from documents where author_id=?",Long.class,user));
    }
    @Test void attachmentDatabaseConstraintsProtectParentsMetadataAndKeys() {
        UUID user=owner(),id=UUID.randomUUID();
        UUID document=tx().execute(s->{
            var space=DocumentSpace.create(now);spaces.insert(space);
            var d=Document.draft(space.getId(),user,"",new DocumentContent("[]"),now);documents.insert(d);
            attachments.insert(Attachment.temporary(id,d.getId(),user,"file.txt","documents/"+d.getId()+"/"+id+".txt","text/plain",1,now));return d.getId();
        });
        for(String assignment:new String[]{"document_id=null","document_id=gen_random_uuid()","created_by=gen_random_uuid()","status='OTHER'","size=0","object_key=null","original_filename=null","content_type=null"})
            assertThrows(RuntimeException.class,()->tx().executeWithoutResult(s->jdbc.update("update attachments set "+assignment+" where id=?",id)),assignment);
        assertThrows(RuntimeException.class,()->tx().executeWithoutResult(s->jdbc.update("delete from documents where id=?",document)));
        assertThrows(RuntimeException.class,()->tx().executeWithoutResult(s->jdbc.update("insert into attachments select gen_random_uuid(),document_id,original_filename,object_key,content_type,size,status,created_by,created_at,updated_at from attachments where id=?",id)));
        assertEquals(1L,jdbc.queryForObject("select count(*) from attachments where document_id=?",Long.class,document));
    }
}
