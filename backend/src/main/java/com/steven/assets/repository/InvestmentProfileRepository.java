package com.steven.assets.repository;

import com.steven.assets.model.InvestmentProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface InvestmentProfileRepository extends JpaRepository<InvestmentProfile, Long> {

    /** 取某使用者的理財條件 profile（一使用者一列）。 */
    Optional<InvestmentProfile> findByOwnerUserId(Long ownerUserId);
}
