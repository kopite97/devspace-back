package com.kopite.devspace.document.infrastructure;
import com.kopite.devspace.document.domain.*;
import jakarta.persistence.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.util.*;
@Repository @RequiredArgsConstructor
public class DocumentRepositoryAdapter implements DocumentRepository {
    private final EntityManager em;
    public Optional<Document> find(UUID id,boolean lock) {
        return Optional.ofNullable(lock?em.find(Document.class,id,LockModeType.PESSIMISTIC_WRITE):em.find(Document.class,id));
    }
    public void insert(Document value){em.persist(value);}
    public void delete(Document value){em.remove(value);}
    public void flush(){em.flush();}
    public Optional<UUID> spaceOf(UUID id){return em.createQuery("select d.documentSpaceId from Document d where d.id=:id",UUID.class).setParameter("id",id).getResultStream().findFirst();}
    public long count(UUID space){return em.createQuery("select count(d) from Document d where d.documentSpaceId=:space",Long.class).setParameter("space",space).getSingleResult();}
    public List<DocumentSummary> page(UUID space,int limit,java.time.Instant at,UUID id) {
        String condition=at==null?"":" and (d.createdAt<:at or (d.createdAt=:at and d.id<:id))";
        var q=em.createQuery("select d.id,d.title,d.status,d.authorId,d.createdAt,d.updatedAt,d.publishedAt from Document d where d.documentSpaceId=:space"+condition+" order by d.createdAt desc,d.id desc",Object[].class).setParameter("space",space).setMaxResults(limit+1);
        if(at!=null)q.setParameter("at",at).setParameter("id",id);
        return q.getResultList().stream().map(r->new DocumentSummary((UUID)r[0],(String)r[1],(Document.Status)r[2],(UUID)r[3],(java.time.Instant)r[4],(java.time.Instant)r[5],(java.time.Instant)r[6])).toList();
    }
}
