package com.kopite.devspace.attachment.application;
import com.kopite.devspace.document.domain.DocumentException;
import java.io.*;
import java.util.*;

public final class UploadValidation {
    private UploadValidation(){}
    public record FileType(String extension,String contentType,InputStream input){}
    public static FileType validate(String filename,String contentType,InputStream raw) {
        if(filename==null||filename.isBlank()||filename.length()>255||filename.indexOf('/')>=0||filename.indexOf('\\')>=0||filename.indexOf(':')>=0||filename.codePoints().anyMatch(Character::isISOControl))throw DocumentException.invalid("file");
        int dot=filename.lastIndexOf('.');String ext=dot<0?"":filename.substring(dot+1).toLowerCase(Locale.ROOT);
        String expected=switch(ext){case "png"->"image/png";case "jpg","jpeg"->"image/jpeg";case "pdf"->"application/pdf";case "txt"->"text/plain";default->throw DocumentException.invalid("file");};
        if(!expected.equalsIgnoreCase(contentType))throw DocumentException.invalid("file");
        try {
            var input=new PushbackInputStream(raw,8);byte[] header=input.readNBytes(8);input.unread(header);
            byte[] magic=switch(ext){case "png"->new byte[]{(byte)137,80,78,71,13,10,26,10};case "jpg","jpeg"->new byte[]{(byte)255,(byte)216,(byte)255};case "pdf"->new byte[]{37,80,68,70,45};default->new byte[0];};
            if(header.length==0||header.length<magic.length)throw DocumentException.invalid("file");
            for(int i=0;i<magic.length;i++)if(header[i]!=magic[i])throw DocumentException.invalid("file");
            return new FileType(ext,expected,ext.equals("txt")?new Utf8Input(input):input);
        }catch(IOException e){throw new DocumentException("STORAGE_UNAVAILABLE",null);}
    }
    /** Validate UTF-8 incrementally without buffering the entire binary. */
    private static class Utf8Input extends FilterInputStream {
        int remaining,code,min;
        Utf8Input(InputStream input){super(input);}
        private void validate(int b) {
            if(b<0){if(remaining!=0)throw DocumentException.invalid("file");return;}
            if(remaining==0){if(b<128)return;if(b>=194&&b<=223){remaining=1;code=b&31;min=128;}else if(b>=224&&b<=239){remaining=2;code=b&15;min=2048;}else if(b>=240&&b<=244){remaining=3;code=b&7;min=65536;}else throw DocumentException.invalid("file");}
            else{if((b&192)!=128)throw DocumentException.invalid("file");code=(code<<6)|(b&63);if(--remaining==0&&(code<min||code>0x10ffff||(code>=0xd800&&code<=0xdfff)))throw DocumentException.invalid("file");}
        }
        public int read()throws IOException{int b=in.read();validate(b);return b;}
        public int read(byte[] bytes,int offset,int length)throws IOException{int n=in.read(bytes,offset,length);if(n<0)validate(-1);else for(int i=offset;i<offset+n;i++)validate(bytes[i]&255);return n;}
    }
}
