package com.steven.assets.repository;
import com.steven.assets.model.SrppEventEvidence;
import java.time.LocalDate;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface SrppEventEvidenceRepository extends JpaRepository<SrppEventEvidence, UUID> {
    Optional<SrppEventEvidence> findByOwnerEmailAndTradingDateAndSlotAndAnalysisProfile(String ownerEmail, LocalDate tradingDate, String slot, String analysisProfile);
}
