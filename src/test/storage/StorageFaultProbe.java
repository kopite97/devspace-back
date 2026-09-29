import com.kopite.devspace.attachment.infrastructure.LocalFileStorage;
import com.kopite.devspace.document.domain.DocumentException;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
/** Executes the compiled production adapter on real Linux mounts as UID 10001. */
public class StorageFaultProbe {
    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static void unavailable(Runnable operation){try{operation.run();throw new AssertionError("Expected storage failure");}catch(DocumentException e){require(e.code().equals("STORAGE_UNAVAILABLE"),e.code());}}
    public static void main(String[] args)throws Exception {
        var root=Path.of(args[0]);String mode=args[1];String key="documents/"+UUID.randomUUID()+"/"+UUID.randomUUID()+".txt";boolean deferred=false;
        if(mode.equals("readonly")){unavailable(()->new LocalFileStorage(root.toString()));}
        else {
            var storage=new LocalFileStorage(root.toString());
            if(mode.equals("diskfull")){unavailable(()->storage.put(key,new ByteArrayInputStream(new byte[2097152]),3145728));require(!Files.exists(root.resolve(key)),"Partial object published");}
            else if(mode.equals("atomic")){unavailable(()->storage.put(key,new ByteArrayInputStream(new byte[]{65}),10));require(!Files.exists(root.resolve(key)),"Cross-filesystem publication accepted");}
            else if(mode.equals("permissions")){
                Files.createDirectories(root.resolve("staging"));Files.setPosixFilePermissions(root.resolve("staging"),PosixFilePermissions.fromString("---------"));
                try{unavailable(()->storage.put(key,new ByteArrayInputStream(new byte[]{65}),10));}finally{Files.setPosixFilePermissions(root.resolve("staging"),PosixFilePermissions.fromString("rwx------"));}
            }else if(mode.equals("symlink")){
                var outside=Files.createTempDirectory("storage-probe-outside-");Files.createSymbolicLink(root.resolve("documents"),outside);
                try{unavailable(()->storage.put(key,new ByteArrayInputStream(new byte[]{65}),10));require(Files.list(outside).findAny().isEmpty(),"Escaped storage root");}finally{Files.delete(root.resolve("documents"));Files.delete(outside);}
            }else if(mode.equals("windows-locked-delete")){
                storage.put(key,new ByteArrayInputStream(new byte[]{65}),10);
                try(var input=java.nio.channels.FileChannel.open(root.resolve(key),StandardOpenOption.READ,com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE)){unavailable(()->storage.delete(key));require(Files.exists(root.resolve(key)),"Locked file removed");}
                storage.delete(key);require(!Files.exists(root.resolve(key)),"Close/retry failed");deferred=true;
            }else if(mode.equals("open-delete")){
                storage.put(key,new ByteArrayInputStream(new byte[]{65}),10);
                try(var input=storage.open(key)){try{storage.delete(key);}catch(DocumentException locked){require(locked.code().equals("STORAGE_UNAVAILABLE"),locked.code());deferred=true;}require(input.read()==65,"Existing stream lost its bytes");}
                if(deferred)storage.delete(key);require(!Files.exists(root.resolve(key)),"File retained after close/retry");
            }else throw new IllegalArgumentException("Unknown probe");
            if(Files.isDirectory(root.resolve("staging")))try(var staged=Files.list(root.resolve("staging"))){require(staged.findAny().isEmpty(),"Staging leaked");}
        }
        System.out.println("{\"mode\":\""+mode+"\",\"result\":\"PASS\",\"os\":\""+System.getProperty("os.name")+"\",\"deleteDeferredUntilClose\":"+deferred+"}");
    }
}
