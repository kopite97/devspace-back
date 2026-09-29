package com.kopite.devspace.attachment.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

@Entity @Table(name="attachments") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class Attachment {
    public enum Status { TEMP, ATTACHED }
    @Id private UUID id;
    @Column(nullable=false,updatable=false) private UUID documentId;
    @Column(nullable=false,updatable=false,columnDefinition="text") private String originalFilename;
    @Column(nullable=false,updatable=false,columnDefinition="text") private String objectKey;
    @Column(nullable=false,updatable=false,columnDefinition="text") private String contentType;
    @Column(nullable=false,updatable=false) private long size;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=16) private Status status;
    @Column(nullable=false,updatable=false) private UUID createdBy;
    @Column(nullable=false,updatable=false) private Instant createdAt;
    @Column(nullable=false) private Instant updatedAt;

    public static Attachment temporary(UUID id,UUID document,UUID creator,String filename,String key,String type,long size,Instant now) {
        if(filename==null||filename.isBlank()||filename.length()>255||key==null||key.isBlank()||type==null||type.isBlank()||size<=0)
            throw new IllegalArgumentException("Invalid attachment metadata");
        var attachment=new Attachment();attachment.id=Objects.requireNonNull(id);
        attachment.documentId=Objects.requireNonNull(document);attachment.createdBy=Objects.requireNonNull(creator);
        attachment.originalFilename=filename;attachment.objectKey=key;attachment.contentType=type;attachment.size=size;
        attachment.status=Status.TEMP;attachment.createdAt=time(now);attachment.updatedAt=attachment.createdAt;
        return attachment;
    }
    public boolean attach(Instant now){return transition(Status.ATTACHED,now);}
    public boolean detach(Instant now){return transition(Status.TEMP,now);}
    private boolean transition(Status next,Instant now) {
        if(status==next)return false;
        updatedAt=time(now);status=next;return true;
    }
    private static Instant time(Instant now){return Objects.requireNonNull(now).truncatedTo(ChronoUnit.MICROS);}
}
