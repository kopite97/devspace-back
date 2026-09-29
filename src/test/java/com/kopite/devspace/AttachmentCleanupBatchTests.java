package com.kopite.devspace;
import com.kopite.devspace.attachment.application.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
@SpringBootTest(properties="app.attachment.cleanup.batch-size=2")
class AttachmentCleanupBatchTests extends DocumentTestSupport {
    @Autowired AttachmentCleanupService cleanup;@Autowired AttachmentCleanupStore store;
    @Test void boundedBatchesDrainOldestUpdatedTempsAcrossRuns()throws Exception {
        var o=owner();String doc=id(tree(request(o,post(collection(journal(o))),"{\"title\":\"\",\"content\":[]}",201)));
        for(int i=0;i<3;i++)request(o,multipart("/api/v2/attachments").file(new MockMultipartFile("file","a.txt","text/plain",new byte[]{65})).param("documentId",doc),null,201);
        jdbc.update("update attachments set updated_at='2020-01-01' where document_id=?",UUID.fromString(doc));
        var expected=store.expired(Instant.now().minusSeconds(86400),2);assertEquals(2,expected.size());long before=counter(o);
        cleanup.runOnce();assertEquals(1,jdbc.queryForObject("select count(*) from attachments where document_id=?",Integer.class,UUID.fromString(doc)));assertEquals(before+2,counter(o));
        for(var id:expected)assertEquals(0,jdbc.queryForObject("select count(*) from attachments where id=?",Integer.class,id));
        cleanup.runOnce();assertEquals(0,jdbc.queryForObject("select count(*) from attachments where document_id=?",Integer.class,UUID.fromString(doc)));assertEquals(before+3,counter(o));
        assertEquals(0,jdbc.queryForObject("select count(*) from attachment_object_deletions",Integer.class));cleanup.runOnce();assertEquals(before+3,counter(o));
    }
}
