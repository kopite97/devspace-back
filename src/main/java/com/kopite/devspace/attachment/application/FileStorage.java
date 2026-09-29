package com.kopite.devspace.attachment.application;
import java.io.InputStream;
public interface FileStorage {
    long put(String key,InputStream input,long maxBytes);
    InputStream open(String key);
    void delete(String key);
}
