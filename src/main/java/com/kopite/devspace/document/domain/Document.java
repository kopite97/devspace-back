package com.kopite.devspace.document.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

@Entity @Table(name="documents") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class Document {
    public enum Status { DRAFT, PUBLISHED }
    @Id private UUID id;
    @Column(nullable=false,updatable=false) private UUID documentSpaceId;
    @Column(nullable=false,columnDefinition="text") private String title;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false,columnDefinition="jsonb") private String content;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=16) private Status status;
    @Column(nullable=false,updatable=false) private UUID authorId;
    @Column(nullable=false,updatable=false) private Instant createdAt;
    @Column(nullable=false) private Instant updatedAt;
    private Instant publishedAt;

    public static Document draft(UUID space,UUID author,String title,DocumentContent content,Instant now) {
        var document=new Document();document.id=UUID.randomUUID();
        document.documentSpaceId=Objects.requireNonNull(space);document.authorId=Objects.requireNonNull(author);
        document.assign(title,content);document.status=Status.DRAFT;
        document.createdAt=time(now);document.updatedAt=document.createdAt;return document;
    }
    public void save(String title,DocumentContent content,Instant now) {
        assign(title,content);updatedAt=time(now);
    }
    public boolean publish(Instant now) {
        if(status==Status.PUBLISHED)return false;
        publishedAt=time(now);updatedAt=publishedAt;status=Status.PUBLISHED;return true;
    }
    private void assign(String title,DocumentContent content) {
        if(title==null||title.length()>200)throw DocumentException.invalid("title");
        this.title=title;this.content=Objects.requireNonNull(content).json();
    }
    private static Instant time(Instant now){return Objects.requireNonNull(now).truncatedTo(ChronoUnit.MICROS);}
}
