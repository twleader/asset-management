package com.steven.assets.srpp;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface SrppContextEvidenceRepository extends JpaRepository<SrppContextEvidence, SrppContextEvidence.Id> {

    @Modifying
    @Query(value = "INSERT INTO srpp_context_evidence (package_id, source_id, body) "
            + "VALUES (:packageId, :sourceId, :body)", nativeQuery = true)
    int insertEvidence(@Param("packageId") UUID packageId, @Param("sourceId") String sourceId,
                       @Param("body") String body);

    /** 呼叫端必須先以 (packageId, ownerId) 確認 package 屬於本人。 */
    @Query("select e.body from SrppContextEvidence e where e.id.packageId = :packageId and e.id.sourceId = :sourceId")
    Optional<String> findBody(@Param("packageId") UUID packageId, @Param("sourceId") String sourceId);
}
