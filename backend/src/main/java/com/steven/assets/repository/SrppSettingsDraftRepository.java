package com.steven.assets.repository;

import com.steven.assets.model.SrppSettingsDraft;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import java.util.*;

public interface SrppSettingsDraftRepository extends JpaRepository<SrppSettingsDraft,UUID> {
    List<SrppSettingsDraft> findTop20ByOwnerUserIdOrderByUpdatedAtDesc(Long owner);
    Optional<SrppSettingsDraft> findByIdAndOwnerUserId(UUID id,Long owner);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from SrppSettingsDraft d where d.id=:id and d.ownerUserId=:owner")
    Optional<SrppSettingsDraft> lockOwned(UUID id,Long owner);
}
