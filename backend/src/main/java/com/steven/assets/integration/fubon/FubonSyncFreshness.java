package com.steven.assets.integration.fubon;

import com.steven.assets.model.AppUser;
import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.model.Bank;
import com.steven.assets.model.BrokerEntity;
import com.steven.assets.model.DepositTypeEntity;

/**
 * Discard only a Fubon writer's previously read entity state before mutation, never an entire
 * caller persistence context. Snapshot refresh is allowed only after its DB row lock is held.
 */
public interface FubonSyncFreshness {
    void refreshLockedSnapshot(AssetSnapshot snapshot);
    void refreshOwner(AppUser owner);
    void refreshBroker(BrokerEntity broker);
    void refreshBank(Bank bank);
    void refreshDepositType(DepositTypeEntity depositType);
}
