package com.kopite.devspace;
import com.kopite.devspace.attachment.application.*;
import com.kopite.devspace.document.domain.DocumentException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
class AttachmentUploadTests extends DocumentTestSupport {
    @Autowired FileStorage storage;
    String document(Owner o)throws Exception{return id(tree(request(o,post(collection(journal(o))),"{\"title\":\"\",\"content\":[]}",201)));}
    @Test void uploadsRealFileAndCommitsTempMetadataOnly()throws Exception {
        var o=owner();String doc=document(o);long before=counter(o);byte[] content="안녕하세요".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var result=tree(request(o,multipart("/api/v2/attachments").file(new MockMultipartFile("file","hello.txt","text/plain",content)).param("documentId",doc),null,201));
        assertEquals("TEMP",result.path("status").asString());assertEquals(doc,result.path("documentId").asString());assertEquals(content.length,result.path("size").asLong());
        assertEquals(before+1,counter(o));assertFalse(result.has("objectKey"));
        String key=jdbc.queryForObject("select object_key from attachments where id=?",String.class,UUID.fromString(id(result)));
        try(var input=storage.open(key)){assertArrayEquals(content,input.readAllBytes());}
        assertEquals(0,tree(request(o,get(D+"/"+doc),null,200)).path("attachmentIds").size());
    }
    @Test void rejectsInvalidFilesWithoutMetadataOrCounterChanges()throws Exception {
        var o=owner();String doc=document(o);long before=counter(o);
        for(var file:List.of(new MockMultipartFile("file","x.svg","image/svg+xml",new byte[]{1}),new MockMultipartFile("file","x.png","image/png",new byte[]{1,2}),new MockMultipartFile("file","x.txt","text/plain",new byte[0]),new MockMultipartFile("file","../x.txt","text/plain",new byte[]{1}),new MockMultipartFile("file","x.txt","text/plain",new byte[]{(byte)255}))) {
            request(o,multipart("/api/v2/attachments").file(file).param("documentId",doc),null,400);
        }
        request(o,multipart("/api/v2/attachments").file(new MockMultipartFile("file","big.txt","text/plain",new byte[10485761])).param("documentId",doc),null,413);
        assertEquals(before,counter(o));assertEquals(0,jdbc.queryForObject("select count(*) from attachments where document_id=?",Integer.class,UUID.fromString(doc)));
    }
    @Test void revalidatesOwnershipAndRejectsExtraParts()throws Exception {
        var o=owner();String doc=document(o);var foreign=owner();var file=new MockMultipartFile("file","x.txt","text/plain",new byte[]{65});
        request(foreign,multipart("/api/v2/attachments").file(file).param("documentId",doc),null,404);
        request(o,multipart("/api/v2/attachments").file(file).file(file).param("documentId",doc),null,400);
        request(o,multipart("/api/v2/attachments").file(file).param("documentId",doc).param("extra","x"),null,400);
        request(o,multipart("/api/v2/attachments").file(file).param("documentId",doc,doc),null,400);
    }
}
