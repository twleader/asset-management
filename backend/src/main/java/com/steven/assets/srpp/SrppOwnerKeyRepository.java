package com.steven.assets.srpp;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface SrppOwnerKeyRepository extends JpaRepository<SrppOwnerKey, Long> {

    /** 只由 producer 發布交易呼叫；既有列不變（表有 BEFORE UPDATE trigger 拒絕）。 */
    @Modifying
    @Query(value = "INSERT INTO srpp_owner_key (owner_user_id, owner_key) VALUES (:ownerUserId, :ownerKey) "
            + "ON CONFLICT (owner_user_id) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("ownerUserId") long ownerUserId, @Param("ownerKey") UUID ownerKey);

    @Query("select k.ownerKey from SrppOwnerKey k where k.ownerUserId = :ownerUserId")
    Optional<UUID> findOwnerKey(@Param("ownerUserId") long ownerUserId);
}
