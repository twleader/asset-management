package com.steven.assets.repository;

import com.steven.assets.model.SrppEventEvidenceBundle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Requirement 181／Task 481.2：事件證據 bundle 的唯一資料存取。
 *
 * <p>讀取一律以完整識別（含明確 ownerUserId）查詢；寫入只有 {@code saveAndFlush} 新列（表上 trigger 擋 UPDATE，
 * 本任務不新增刪除排程）。
 */
public interface SrppEventEvidenceBundleRepository extends JpaRepository<SrppEventEvidenceBundle, UUID> {

    @Query("select b from SrppEventEvidenceBundle b where b.ownerUserId = :ownerUserId and b.tradingDate = :tradingDate "
            + "and b.slot = :slot and b.analysisProfile = :analysisProfile and b.consumer = :consumer "
            + "and b.decisionId = :decisionId")
    Optional<SrppEventEvidenceBundle> findIdentity(@Param("ownerUserId") long ownerUserId,
                                                   @Param("tradingDate") LocalDate tradingDate,
                                                   @Param("slot") String slot,
                                                   @Param("analysisProfile") String analysisProfile,
                                                   @Param("consumer") String consumer,
                                                   @Param("decisionId") String decisionId);
}
