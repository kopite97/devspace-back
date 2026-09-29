package com.kopite.devspace.document.infrastructure;
import com.kopite.devspace.document.application.DocumentCursorCodec;
import com.kopite.devspace.document.domain.DocumentException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
@Component
public class SignedDocumentCursorCodec implements DocumentCursorCodec {
    private final byte[] key;
    public SignedDocumentCursorCodec(@Value("${app.project.cursor-signing-key}")String key){this.key=key.getBytes(StandardCharsets.UTF_8);if(this.key.length<32)throw new IllegalArgumentException("Cursor key too short");}
    public String encode(UUID w,String context,String position){String p="d1."+Base64.getUrlEncoder().withoutPadding().encodeToString(position.getBytes(StandardCharsets.UTF_8));return p+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(sign(w,context,p));}
    public String decode(UUID w,String context,String cursor) {
        try {if(cursor.length()>4096)throw invalid();String[] parts=cursor.split("\\.",-1);
            if(parts.length!=3||!parts[0].equals("d1")||!MessageDigest.isEqual(sign(w,context,parts[0]+"."+parts[1]),Base64.getUrlDecoder().decode(parts[2])))throw invalid();
            String result=new String(Base64.getUrlDecoder().decode(parts[1]),StandardCharsets.UTF_8);if(!encode(w,context,result).equals(cursor))throw invalid();return result;
        }catch(IllegalArgumentException ex){throw invalid();}
    }
    private byte[] sign(UUID w,String context,String payload){try{Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(key,"HmacSHA256"));return m.doFinal(("documents:"+w+":"+context.length()+":"+context+":"+payload).getBytes(StandardCharsets.UTF_8));}catch(GeneralSecurityException e){throw new IllegalStateException(e);}}
    private DocumentException invalid(){return new DocumentException("INVALID_CURSOR","cursor");}
}
