package com.kopite.devspace.attachment.infrastructure;

import com.kopite.devspace.attachment.application.FileStorage;
import com.kopite.devspace.document.domain.DocumentException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;

@Component
public class LocalFileStorage implements FileStorage {
    public static final String MARKER=".devspace-document-storage";
    public static final String MARKER_VALUE="devspace-document-storage-v1";
    private static final String UUID_PATTERN="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private final Path root;
    public LocalFileStorage(@Value("${app.storage.root}")String root) {
        this.root=Path.of(root).normalize();
        if(!this.root.isAbsolute())throw new IllegalStateException("Storage requires an explicit absolute root");
        checkRoot();
    }
    private DocumentException unavailable(){return new DocumentException("STORAGE_UNAVAILABLE",null);}
    private void checkRoot() {
        try {
            safeExisting(root,true);
            Path marker=root.resolve(MARKER);safeExisting(marker,false);
            if(!Files.readString(marker).strip().equals(MARKER_VALUE)||!Files.isWritable(root))throw unavailable();
        }catch(IOException|SecurityException e){throw unavailable();}
    }
    private void safeExisting(Path path,boolean directory)throws IOException {
        var attributes=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(attributes.isSymbolicLink()||attributes.isOther()||attributes.isDirectory()!=directory||!path.toRealPath().equals(path.toAbsolutePath().normalize()))throw unavailable();
    }
    private Path target(String key,boolean create)throws IOException {
        checkRoot();
        if(key==null||!key.matches("documents/"+UUID_PATTERN+"/"+UUID_PATTERN+"\\.(png|jpg|jpeg|pdf|txt)"))throw unavailable();
        Path target=root.resolve(key).normalize();if(!target.startsWith(root))throw unavailable();
        Path current=root;
        for(Path part:root.relativize(target.getParent())) {
            current=current.resolve(part);
            if(Files.notExists(current,LinkOption.NOFOLLOW_LINKS)) {
                if(!create)continue;
                try{Files.createDirectory(current);}catch(FileAlreadyExistsException raced){}
            }
            if(Files.exists(current,LinkOption.NOFOLLOW_LINKS))safeExisting(current,true);
        }
        return target;
    }
    public long put(String key,InputStream input,long maxBytes) {
        Path staging=null,claim=null;boolean claimed=false;
        try {
            Path target=target(key,true);
            claim=target.resolveSibling("."+target.getFileName()+".claim");
            Files.createDirectory(claim);claimed=true;
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw unavailable();
            Path stageRoot=root.resolve("staging");
            try{Files.createDirectory(stageRoot);}catch(FileAlreadyExistsException exists){}
            safeExisting(stageRoot,true);
            staging=stageRoot.resolve(UUID.randomUUID()+".part");long size=0;
            try(var channel=FileChannel.open(staging,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer=new byte[16384];int n;
                while((n=input.read(buffer))!=-1) {
                    size+=n;if(size>maxBytes)throw new DocumentException("PAYLOAD_TOO_LARGE","file");
                    var bytes=ByteBuffer.wrap(buffer,0,n);while(bytes.hasRemaining())channel.write(bytes);
                }
                if(size==0)throw DocumentException.invalid("file");channel.force(true);
            }
            // The exclusive claim also protects concurrent adapters sharing this root.
            target(key,false);safeExisting(target.getParent(),true);
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw unavailable();
            publish(staging,target);staging=null;return size;
        }catch(IOException|SecurityException e){throw unavailable();}
        finally {
            if(staging!=null)try{Files.deleteIfExists(staging);}catch(IOException ignored){}
            if(claimed)try{Files.deleteIfExists(claim);}catch(IOException ignored){}
        }
    }
    protected void publish(Path staging,Path target)throws IOException {
        Files.move(staging,target,StandardCopyOption.ATOMIC_MOVE);
    }
    public InputStream open(String key) {
        try {Path file=target(key,false);if(Files.notExists(file,LinkOption.NOFOLLOW_LINKS))throw DocumentException.missing();safeExisting(file,false);return Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS);}
        catch(NoSuchFileException missing){checkRoot();throw DocumentException.missing();}
        catch(IOException|SecurityException e){throw unavailable();}
    }
    public void delete(String key) {
        try {Path file=target(key,false);if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)){safeExisting(file,false);Files.delete(file);}else if(!Files.notExists(file,LinkOption.NOFOLLOW_LINKS))throw unavailable();}
        catch(NoSuchFileException missing){checkRoot();}
        catch(IOException|SecurityException e){throw unavailable();}
    }
}
