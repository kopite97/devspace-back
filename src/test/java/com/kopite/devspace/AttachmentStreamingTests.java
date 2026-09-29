package com.kopite.devspace;
import com.kopite.devspace.attachment.application.FileStorage;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
class AttachmentStreamingTests extends DocumentTestSupport {
    @MockitoSpyBean FileStorage storage;
    @org.springframework.beans.factory.annotation.Autowired javax.sql.DataSource dataSource;
    @Test void slowRealFileStreamsAreBoundedAndEveryLeaseCloses()throws Exception {
        var owner=owner();String doc=id(tree(request(owner,post(collection(journal(owner))),"{\"title\":\"\",\"content\":[]}",201)));
        String id=id(tree(request(owner,multipart("/api/v2/attachments").file(new MockMultipartFile("file","a.txt","text/plain",new byte[]{65})).param("documentId",doc),null,201)));
        var release=new CountDownLatch(1);var entered=new CountDownLatch(16);var closed=new AtomicInteger();
        doAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"Binary open must run outside the metadata transaction");
            var source=(InputStream)call.callRealMethod();
            return new FilterInputStream(source){
                public int read(byte[] bytes,int offset,int length)throws IOException{entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IOException("test release timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}return super.read(bytes,offset,length);}
                public void close()throws IOException{closed.incrementAndGet();super.close();}
            };
        }).when(storage).open(anyString());
        var requests=new ArrayList<MvcResult>();
        try {
            for(int i=0;i<16;i++){var result=mvc.perform(get("/api/v2/attachments/"+id+"/content").session(owner.session())).andReturn();assertTrue(result.getRequest().isAsyncStarted());requests.add(result);}
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertEquals(0,dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class).getHikariPoolMXBean().getActiveConnections(),"Slow streams must not retain a pooled database connection");
            request(owner,get("/api/v2/attachments/"+id+"/content"),null,503);
        }finally{release.countDown();}
        for(var result:requests){var response=mvc.perform(asyncDispatch(result)).andReturn().getResponse();assertEquals(200,response.getStatus());assertArrayEquals(new byte[]{65},response.getContentAsByteArray());}
        assertEquals(16,closed.get());
        var next=mvc.perform(get("/api/v2/attachments/"+id+"/content").session(owner.session())).andReturn();assertEquals(200,mvc.perform(asyncDispatch(next)).andReturn().getResponse().getStatus());assertEquals(17,closed.get());
    }
    @Test void servletTimeoutClosesRealInputBeforeCompletingTheRequest()throws Exception {
        var owner=owner();String doc=id(tree(request(owner,post(collection(journal(owner))),"{\"title\":\"\",\"content\":[]}",201)));
        String id=id(tree(request(owner,multipart("/api/v2/attachments").file(new MockMultipartFile("file","a.txt","text/plain",new byte[]{65})).param("documentId",doc),null,201)));
        var release=new CountDownLatch(1);var entered=new CountDownLatch(1);var closed=new AtomicInteger();
        doAnswer(call->{var source=(InputStream)call.callRealMethod();return new FilterInputStream(source){
            public int read(byte[] bytes,int offset,int length)throws IOException{entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IOException("test timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}return super.read(bytes,offset,length);}
            public void close()throws IOException{closed.incrementAndGet();super.close();}
        };}).when(storage).open(anyString());
        var result=mvc.perform(get("/api/v2/attachments/"+id+"/content").session(owner.session())).andReturn();
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS));var context=(org.springframework.mock.web.MockAsyncContext)result.getRequest().getAsyncContext();
            for(var listener:context.getListeners())listener.onTimeout(new jakarta.servlet.AsyncEvent(context));
            assertEquals(1,closed.get());
        }finally{release.countDown();}
        // MockMvc's result latch is populated by the worker, not the container timeout callback.
        // Complete the container lifecycle explicitly; do not race its synthetic async dispatch.
        result.getRequest().getAsyncContext().complete();assertEquals(1,closed.get());
        var next=mvc.perform(get("/api/v2/attachments/"+id+"/content").session(owner.session())).andReturn();
        assertEquals(200,mvc.perform(asyncDispatch(next)).andReturn().getResponse().getStatus());assertEquals(2,closed.get());
    }
}
