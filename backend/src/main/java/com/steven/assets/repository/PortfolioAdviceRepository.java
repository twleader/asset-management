package com.steven.assets.repository;

import com.steven.assets.model.PortfolioAdvice;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PortfolioAdviceRepository extends JpaRepository<PortfolioAdvice, Long> {

    /** 某使用者最新一筆建議。 */
    Optional<PortfolioAdvice> findFirstByOwnerUserIdOrderByCreatedAtDesc(Long ownerUserId);

    /** 某使用者近 N 筆建議（created_at 降序）。 */
    @Query("SELECT a FROM PortfolioAdvice a WHERE a.ownerUserId = :ownerUserId ORDER BY a.createdAt DESC")
    List<PortfolioAdvice> findRecentByOwner(Long ownerUserId, Pageable pageable);
}
