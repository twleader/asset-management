package com.steven.assets.repository;

import com.steven.assets.model.SrppSettingsRun;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import java.util.*;

public interface SrppSettingsRunRepository extends JpaRepository<SrppSettingsRun,UUID> {
    List<SrppSettingsRun> findTop20ByOwnerUserIdOrderByCreatedAtDesc(Long owner);
    Optional<SrppSettingsRun> findByIdAndOwnerUserId(UUID id,Long owner);
    Optional<SrppSettingsRun> findFirstByDraftIdAndOwnerUserIdAndDraftRevisionAndConfigSha256AndStatusOrderByCreatedAtDesc(UUID draft,Long owner,long revision,String config,String status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from SrppSettingsRun r where r.id=:id and r.ownerUserId=:owner")
    Optional<SrppSettingsRun> lockOwned(UUID id,Long owner);
    List<SrppSettingsRun> findByStatus(String status);
}
