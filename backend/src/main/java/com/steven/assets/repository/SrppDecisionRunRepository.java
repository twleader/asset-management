package com.steven.assets.repository;
import com.steven.assets.model.SrppDecisionRun;
import java.time.LocalDate;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface SrppDecisionRunRepository extends JpaRepository<SrppDecisionRun, UUID> {
    Optional<SrppDecisionRun> findByOwnerEmailAndTradingDateAndSlot(String ownerEmail, LocalDate tradingDate, String slot);
}
