package com.steven.assets.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Repository;

/** Reload a locked aggregate without refreshing removed children retained by a bound OSIV context. */
@Repository
public class ExportScheduleFreshRead {
    @PersistenceContext private EntityManager em;
    @SuppressWarnings("unchecked")
    public <T> T refresh(T entity) {
        Object id = em.getEntityManagerFactory().getPersistenceUnitUtil().getIdentifier(entity);
        Class<T> type = (Class<T>) entity.getClass();
        // Detach cascades through the old collection without trying to reload deleted child rows.
        em.detach(entity);
        return em.find(type, id, LockModeType.PESSIMISTIC_WRITE);
    }
}
