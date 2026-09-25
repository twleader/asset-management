package com.steven.assets.srpp;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 所有讀取一律帶明確 ownerId；寫入只有 producer 的 INSERT 與保留期 DELETE。 */
public interface SrppContextPackageRepository extends JpaRepository<SrppContextPackage, UUID> {

    @Modifying
    @Query(value = "INSERT INTO srpp_context_package (package_id, owner_user_id, trading_date, slot, "
            + "policy_bundle_sha256, generated_at, context_jcs) VALUES (:packageId, :ownerUserId, :tradingDate, "
            + ":slot, :policyBundleSha256, :generatedAt, :contextJcs)", nativeQuery = true)
    int insertPackage(@Param("packageId") UUID packageId,
                      @Param("ownerUserId") long ownerUserId,
                      @Param("tradingDate") LocalDate tradingDate,
                      @Param("slot") String slot,
                      @Param("policyBundleSha256") String policyBundleSha256,
                      @Param("generatedAt") Instant generatedAt,
                      @Param("contextJcs") String contextJcs);

    @Query("select p from SrppContextPackage p where p.ownerUserId = :ownerUserId and p.tradingDate = :tradingDate "
            + "and p.slot = :slot and p.policyBundleSha256 = :bundle order by p.generatedAt desc, p.packageId desc")
    List<SrppContextPackage> findLatest(@Param("ownerUserId") long ownerUserId,
                                        @Param("tradingDate") LocalDate tradingDate,
                                        @Param("slot") String slot,
                                        @Param("bundle") String policyBundleSha256,
                                        Limit limit);

    default Optional<SrppContextPackage> findLatestForOwner(long ownerUserId, LocalDate tradingDate, String slot,
                                                            String policyBundleSha256) {
        return findLatest(ownerUserId, tradingDate, slot, policyBundleSha256, Limit.of(1)).stream().findFirst();
    }

    @Query("select p from SrppContextPackage p where p.packageId = :packageId and p.ownerUserId = :ownerUserId")
    Optional<SrppContextPackage> findByPackageIdAndOwner(@Param("packageId") UUID packageId,
                                                         @Param("ownerUserId") long ownerUserId);

    /** 保留期清理；evidence 由 FK ON DELETE CASCADE 一併刪除。 */
    @Modifying
    @Query(value = "DELETE FROM srpp_context_package WHERE trading_date < :cutoff", nativeQuery = true)
    int deleteTradingDateBefore(@Param("cutoff") LocalDate cutoff);
}
