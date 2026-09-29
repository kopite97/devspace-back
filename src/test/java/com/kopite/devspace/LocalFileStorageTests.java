package com.kopite.devspace;
import com.kopite.devspace.attachment.infrastructure.LocalFileStorage;
import com.kopite.devspace.attachment.application.UploadValidation;
import com.kopite.devspace.document.domain.DocumentException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class LocalFileStorageTests {
    @TempDir Path root;
    @TempDir Path outside;
    String key(){return "documents/"+UUID.randomUUID()+"/"+UUID.randomUUID()+".txt";}
    LocalFileStorage storage()throws IOException{Files.writeString(root.resolve(LocalFileStorage.MARKER),LocalFileStorage.MARKER_VALUE);return new LocalFileStorage(root.toString());}
    @Test void persistsAcrossAdapterRestartAndRefusesOverwrite()throws Exception {
        var storage=storage();String key=key();byte[] bytes="한글 file".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(bytes.length,storage.put(key,new ByteArrayInputStream(bytes),100));
        var restarted=new LocalFileStorage(root.toString());try(var input=restarted.open(key)){assertArrayEquals(bytes,input.readAllBytes());}
        assertThrows(DocumentException.class,()->restarted.put(key,new ByteArrayInputStream(new byte[]{1}),10));
        try(var input=storage.open(key)){assertArrayEquals(bytes,input.readAllBytes());}
        restarted.delete(key);restarted.delete(key);
        assertEquals("RESOURCE_NOT_FOUND",assertThrows(DocumentException.class,()->storage.open(key)).code());
    }
    @Test void rootFailureIsNeverTreatedAsMissingObject()throws Exception {
        assertThrows(DocumentException.class,()->new LocalFileStorage(root.toString()));
        var storage=storage();Files.delete(root.resolve(LocalFileStorage.MARKER));
        assertEquals("STORAGE_UNAVAILABLE",assertThrows(DocumentException.class,()->storage.open(key())).code());
        assertThrows(DocumentException.class,()->storage.delete(key()));
    }
    @Test void invalidAndOversizedStreamsLeaveNoPublishedOrStagedFile()throws Exception {
        var storage=storage();String key=key();
        assertEquals("PAYLOAD_TOO_LARGE",assertThrows(DocumentException.class,()->storage.put(key,new ByteArrayInputStream(new byte[11]),10)).code());
        assertFalse(Files.exists(root.resolve(key)));
        try(var staged=Files.list(root.resolve("staging"))){assertEquals(0,staged.count());}
        assertThrows(DocumentException.class,()->storage.put("../outside.txt",new ByteArrayInputStream(new byte[]{1}),10));
        var invalid=UploadValidation.validate("bad.txt","text/plain",new ByteArrayInputStream(new byte[]{(byte)0xc0,(byte)0x80}));
        assertThrows(DocumentException.class,()->storage.put(key,invalid.input(),100));
        assertFalse(Files.exists(root.resolve(key)));
    }
    @Test void concurrentAdaptersPublishExactlyOneWriter()throws Exception {
        var one=storage();var two=new LocalFileStorage(root.toString());String key=key();var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var futures=new ArrayList<Future<Boolean>>();
            for(var store:List.of(one,two))futures.add(executor.submit(()->{start.await();try{store.put(key,new ByteArrayInputStream(new byte[]{42}),10);return true;}catch(DocumentException e){return false;}}));
            start.countDown();int successful=0;for(var result:futures)if(result.get(10,TimeUnit.SECONDS))successful++;
            assertEquals(1,successful);
        }
    }
    @Test void failedPublishAndInterruptedInputLeaveNoVisiblePartialFile()throws Exception {
        storage();String key=key();
        var failing=new LocalFileStorage(root.toString()) {
            protected void publish(Path source,Path target)throws IOException{throw new AtomicMoveNotSupportedException(source.toString(),target.toString(),"injected unsupported atomic move");}
        };
        assertThrows(DocumentException.class,()->failing.put(key,new ByteArrayInputStream(new byte[]{1}),10));
        assertFalse(Files.exists(root.resolve(key)));
        var normal=new LocalFileStorage(root.toString());
        assertThrows(DocumentException.class,()->normal.put(key,new InputStream(){public int read()throws IOException{throw new IOException("injected full disk/interrupted stream");}},10));
        try(var staged=Files.list(root.resolve("staging"))){assertEquals(0,staged.count());}
        for(String bad:List.of("C:/outside.txt","//host/share/x.txt","documents/../x.txt","documents\\x.txt","staging/a.part"))assertThrows(DocumentException.class,()->normal.open(bad));
    }
    @Test void rejectsLinkedDirectoryEscapeWithoutReadingOrChangingTarget()throws Exception {
        var storage=storage();String key=key();var linked=root.resolve("documents");
        Path target=outside.resolve(Path.of(key).subpath(1,3));Files.createDirectories(target.getParent());Files.writeString(target,"private");
        if(System.getProperty("os.name").startsWith("Windows")) {
            var process=new ProcessBuilder("cmd.exe","/c","mklink","/J",linked.toString(),outside.toString()).redirectErrorStream(true).start();
            String output=new String(process.getInputStream().readAllBytes());assertEquals(0,process.waitFor(),output);
        }else Files.createSymbolicLink(linked,outside);
        try {
            assertThrows(DocumentException.class,()->storage.open(key));assertThrows(DocumentException.class,()->storage.delete(key));
            assertThrows(DocumentException.class,()->storage.put(key,new ByteArrayInputStream(new byte[]{1}),10));assertEquals("private",Files.readString(target));
        }finally{Files.delete(linked);}
    }
}
