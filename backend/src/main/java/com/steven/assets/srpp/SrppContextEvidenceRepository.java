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

    /** 以 join package 比對 owner（packageId＋sourceId＋ownerUserId），不只依賴呼叫端先前的 package 驗證。 */
    @Query("select e.body from SrppContextEvidence e, SrppContextPackage p "
            + "where p.packageId = e.id.packageId and e.id.packageId = :packageId "
            + "and e.id.sourceId = :sourceId and p.ownerUserId = :ownerUserId")
    Optional<String> findBody(@Param("packageId") UUID packageId, @Param("sourceId") String sourceId,
                              @Param("ownerUserId") long ownerUserId);
}
