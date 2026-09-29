package com.kopite.devspace.documentspace.infrastructure;
import com.kopite.devspace.documentspace.domain.*;
import jakarta.persistence.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.util.*;
@Repository @RequiredArgsConstructor
public class DocumentSpaceRepositoryAdapter implements DocumentSpaceRepository {
    private final EntityManager em;
    public Optional<DocumentSpace> find(UUID id,boolean lock) {
        return Optional.ofNullable(lock?em.find(DocumentSpace.class,id,LockModeType.PESSIMISTIC_WRITE):em.find(DocumentSpace.class,id));
    }
    public void insert(DocumentSpace value){em.persist(value);}
    public void delete(DocumentSpace value){em.remove(value);}
    public void flush(){em.flush();}
}
