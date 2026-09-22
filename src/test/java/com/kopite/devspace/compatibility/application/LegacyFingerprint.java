package com.kopite.devspace.compatibility.application;
// Historical bridge test fixture only.
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;

/** Frozen framing for historical intent; never used to create new business rows. */
public final class LegacyFingerprint {
    private LegacyFingerprint() {}
    public static String of(int version,Object... fields) {
        try {
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeInt(version);
            for(Object field:fields) {
                if(field==null)out.writeInt(-1);
                else {byte[] value=field.toString().getBytes(StandardCharsets.UTF_8);out.writeInt(value.length);out.write(value);}
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch(IOException|NoSuchAlgorithmException ex){throw new IllegalStateException("Cannot read historical intent",ex);}
    }
}
