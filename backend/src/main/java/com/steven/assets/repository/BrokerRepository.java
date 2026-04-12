package com.steven.assets.repository;

import com.steven.assets.model.BrokerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BrokerRepository extends JpaRepository<BrokerEntity, Long> {

    Optional<BrokerEntity> findByCode(String code);

    List<BrokerEntity> findByActiveTrueOrderByDisplayNameAsc();

    List<BrokerEntity> findAllByOrderByDisplayNameAsc();
}
