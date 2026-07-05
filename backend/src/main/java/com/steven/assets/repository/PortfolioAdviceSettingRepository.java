package com.steven.assets.repository;

import com.steven.assets.model.PortfolioAdviceSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PortfolioAdviceSettingRepository extends JpaRepository<PortfolioAdviceSetting, Integer> {
}
