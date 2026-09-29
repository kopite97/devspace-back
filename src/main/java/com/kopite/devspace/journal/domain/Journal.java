package com.kopite.devspace.journal.domain;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name="journals")
@Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class Journal {
    public static final long MAX_REVISION=9007199254740991L;
    @Id private UUID id;
    @Column(nullable=false,updatable=false) private UUID workspaceId;
    @Column(nullable=false) private UUID projectId;
    @Column(nullable=false,updatable=false) private UUID documentSpaceId;
    @Column(nullable=false,columnDefinition="text") private String title;
    @Column(nullable=false,columnDefinition="text") private String body;
    @Column(nullable=false) private LocalDate entryDate;
    @Column(nullable=false) private long revision;
    @Column(nullable=false,updatable=false) private Instant createdAt;
    @Column(nullable=false) private Instant updatedAt;

    public static Journal create(UUID workspace,UUID documentSpace,JournalValues values,Instant now) {
        Journal journal=new Journal();
        journal.id=UUID.randomUUID();
        journal.workspaceId=Objects.requireNonNull(workspace);
        journal.documentSpaceId=Objects.requireNonNull(documentSpace);
        journal.assign(values);
        journal.revision=1;
        journal.createdAt=timestamp(now);
        journal.updatedAt=journal.createdAt;
        return journal;
    }
    public void checkRevision(long expected) {
        if(expected<1 || expected>MAX_REVISION) throw new JournalValidationException("revision","must be a positive safe integer");
        if(expected!=revision) throw new JournalConflictException("REVISION_CONFLICT");
    }
    public void update(long expected,JournalValues values,Instant now) {
        checkRevision(expected);
        if(revision==MAX_REVISION) throw new JournalConflictException("REVISION_CONFLICT");
        Objects.requireNonNull(values);
        Instant time=timestamp(now);
        assign(values);
        revision++;
        updatedAt=time;
    }
    public JournalValues values() { return new JournalValues(title,projectId,body,entryDate); }
    private static Instant timestamp(Instant now) { return Objects.requireNonNull(now).truncatedTo(ChronoUnit.MICROS); }
    private void assign(JournalValues values) {
        Objects.requireNonNull(values);
        title=values.title(); projectId=values.projectId(); body=values.body(); entryDate=values.entryDate();
    }
}
