package com.steven.assets.repository;

import com.steven.assets.model.FundMaster;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FundMasterRepository extends JpaRepository<FundMaster, String> {

    List<FundMaster> findByActiveTrue();
}
