package com.kopite.devspace.attachment.infrastructure;
import com.kopite.devspace.attachment.domain.*;
import jakarta.persistence.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.util.*;
@Repository @RequiredArgsConstructor
public class AttachmentRepositoryAdapter implements AttachmentRepository {
    private final EntityManager em;
    public Optional<Attachment> find(UUID id,boolean lock) {
        return Optional.ofNullable(lock?em.find(Attachment.class,id,LockModeType.PESSIMISTIC_WRITE):em.find(Attachment.class,id));
    }
    public void insert(Attachment value){em.persist(value);}
    public void delete(Attachment value){em.remove(value);}
    public void flush(){em.flush();}
    public List<Attachment> attached(UUID document){return em.createQuery("select a from Attachment a where a.documentId=:document and a.status=:status order by a.id",Attachment.class).setParameter("document",document).setParameter("status",Attachment.Status.ATTACHED).setMaxResults(101).getResultList();}
    public Optional<UUID> documentOf(UUID attachment){return em.createQuery("select a.documentId from Attachment a where a.id=:id",UUID.class).setParameter("id",attachment).getResultStream().findFirst();}
    public List<UUID> attachedIds(UUID document){return em.createQuery("select a.id from Attachment a where a.documentId=:document and a.status=:status order by a.id",UUID.class).setParameter("document",document).setParameter("status",Attachment.Status.ATTACHED).getResultList();}
}
