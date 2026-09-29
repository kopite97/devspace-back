package com.kopite.devspace.documentspace.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

@Entity @Table(name="document_spaces") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class DocumentSpace {
    @Id private UUID id;
    @Column(nullable=false,updatable=false) private Instant createdAt;
    @Column(nullable=false) private Instant updatedAt;

    public static DocumentSpace create(Instant now) {
        var space=new DocumentSpace();
        space.id=UUID.randomUUID();
        space.createdAt=Objects.requireNonNull(now).truncatedTo(ChronoUnit.MICROS);
        space.updatedAt=space.createdAt;
        return space;
    }
    public void collectionChanged(Instant now) { updatedAt=Objects.requireNonNull(now).truncatedTo(ChronoUnit.MICROS); }
}
