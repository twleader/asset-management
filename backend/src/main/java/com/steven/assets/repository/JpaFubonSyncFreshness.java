package com.steven.assets.repository;

import com.steven.assets.integration.fubon.FubonSyncFreshness;
import com.steven.assets.model.AppUser;
import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.model.Bank;
import com.steven.assets.model.BrokerEntity;
import com.steven.assets.model.DepositTypeEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Fubon-only managed-entity refresh inside the already active writer transaction. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JpaFubonSyncFreshness implements FubonSyncFreshness {
    @PersistenceContext private EntityManager entityManager;

    @Override public void refreshLockedSnapshot(AssetSnapshot snapshot) {
        // AssetSnapshot owns its children with CascadeType.ALL (including REFRESH). This also
        // invalidates an initialized collection's old membership. A vanished child may reject
        // the refresh; propagating that failure safely rolls back the complete writer.
        entityManager.refresh(snapshot);
    }

    @Override public void refreshOwner(AppUser owner) { entityManager.refresh(owner); }
    @Override public void refreshBroker(BrokerEntity broker) { entityManager.refresh(broker); }
    @Override public void refreshBank(Bank bank) { entityManager.refresh(bank); }
    @Override public void refreshDepositType(DepositTypeEntity depositType) { entityManager.refresh(depositType); }
}
