package com.kopite.devspace;
import com.kopite.devspace.attachment.application.*;
import com.kopite.devspace.document.domain.DocumentException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
class AttachmentUploadRecoveryTests extends DocumentTestSupport {
    @Autowired FileStorage storage;@Autowired UploadMetadataService metadata;@Autowired AttachmentLimits limits;
    UUID document(Owner o)throws Exception{return UUID.fromString(id(tree(request(o,post(collection(journal(o))),"{\"title\":\"\",\"content\":[]}",201))));}
    class ObservedStorage implements FileStorage {
        String key;boolean failDelete;Runnable afterPut=()->{};
        public long put(String key,InputStream input,long max){this.key=key;long result=storage.put(key,input,max);afterPut.run();return result;}
        public InputStream open(String key){return storage.open(key);}
        public void delete(String key){if(failDelete)throw new DocumentException("STORAGE_UNAVAILABLE",null);storage.delete(key);}
    }
    @Test void confirmedRollbackDeletesFileAndFailedCompensationIsDurable()throws Exception {
        var o=owner();var doc=document(o);jdbc.update("update workspaces set data_revision=? where id=?",Long.MAX_VALUE,o.workspace());
        var observed=new ObservedStorage();var uploads=new AttachmentUploadService(observed,metadata,limits);
        assertThrows(DocumentException.class,()->uploads.upload(o.user(),doc,"a.txt","text/plain",new ByteArrayInputStream(new byte[]{65})));
        assertThrows(DocumentException.class,()->storage.open(observed.key));
        observed.failDelete=true;
        assertThrows(DocumentException.class,()->uploads.upload(o.user(),doc,"a.txt","text/plain",new ByteArrayInputStream(new byte[]{65})));
        assertEquals(1,jdbc.queryForObject("select count(*) from attachment_object_deletions where object_key=?",Integer.class,observed.key));
        try(var input=storage.open(observed.key)){assertEquals(65,input.read());}
        assertEquals(0,jdbc.queryForObject("select count(*) from attachments where document_id=?",Integer.class,doc));
    }
    @Test void parentDeletedDuringIoIsRecheckedAndCompensated()throws Exception {
        var o=owner();var doc=document(o);var observed=new ObservedStorage();observed.afterPut=()->jdbc.update("delete from documents where id=?",doc);
        var uploads=new AttachmentUploadService(observed,metadata,limits);
        assertEquals("RESOURCE_NOT_FOUND",assertThrows(DocumentException.class,()->uploads.upload(o.user(),doc,"a.txt","text/plain",new ByteArrayInputStream(new byte[]{65}))).code());
        assertThrows(DocumentException.class,()->storage.open(observed.key));
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={0,2})
    void lostOrUnknownCommitAcknowledgementNeverDeletesCommittedFile(int outcome)throws Exception {
        var o=owner();var doc=document(o);var observed=new ObservedStorage();var proxy=mock(UploadMetadataService.class);
        doAnswer(invocation->{metadata.authorize(invocation.getArgument(0),invocation.getArgument(1));return null;}).when(proxy).authorize(any(),any());
        when(proxy.insert(any(),any(),any(),any(),any(),any(),anyLong(),any())).thenAnswer(invocation->{
            metadata.insert(invocation.getArgument(0),invocation.getArgument(1),invocation.getArgument(2),invocation.getArgument(3),invocation.getArgument(4),invocation.getArgument(5),invocation.getArgument(6),invocation.getArgument(7));
            invocation.<AtomicInteger>getArgument(7).set(outcome);
            throw new IllegalStateException("injected lost acknowledgement after commit");
        });
        var uploads=new AttachmentUploadService(observed,proxy,limits);
        assertThrows(DocumentException.class,()->uploads.upload(o.user(),doc,"a.txt","text/plain",new ByteArrayInputStream(new byte[]{65})));
        assertEquals(1,jdbc.queryForObject("select count(*) from attachments where document_id=?",Integer.class,doc));
        assertEquals(0,jdbc.queryForObject("select count(*) from attachment_object_deletions where object_key=?",Integer.class,observed.key));
        try(var input=storage.open(observed.key)){assertEquals(65,input.read());}
    }
    @Test void databaseFlushFailureRollsBackMetadataAndCompensatesFile()throws Exception {
        var o=owner();var doc=document(o);var observed=new ObservedStorage();var uploads=new AttachmentUploadService(observed,metadata,limits);long before=counter(o);
        jdbc.execute("alter table attachments add constraint reject_upload_fixture check(original_filename <> 'reject-flush.txt') not valid");
        try {
            assertThrows(DocumentException.class,()->uploads.upload(o.user(),doc,"reject-flush.txt","text/plain",new ByteArrayInputStream(new byte[]{65})));
            assertEquals(0,jdbc.queryForObject("select count(*) from attachments where document_id=?",Integer.class,doc));assertEquals(before,counter(o));assertThrows(DocumentException.class,()->storage.open(observed.key));
        }finally{jdbc.execute("alter table attachments drop constraint reject_upload_fixture");}
    }
}
