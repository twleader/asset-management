package com.steven.assets.repository;

import com.steven.assets.model.SrppSettingsRelease;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface SrppSettingsReleaseRepository extends JpaRepository<SrppSettingsRelease,UUID> {
    List<SrppSettingsRelease> findTop20ByOwnerUserIdOrderByCreatedAtDesc(Long owner);
    Optional<SrppSettingsRelease> findByIdAndOwnerUserId(UUID id,Long owner);
    Optional<SrppSettingsRelease> findByRequestSha256AndOwnerUserId(String hash,Long owner);
}
