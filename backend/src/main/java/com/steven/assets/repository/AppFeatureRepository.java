package com.steven.assets.repository;

import com.steven.assets.model.AppFeature;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AppFeatureRepository extends JpaRepository<AppFeature, Long> {

    Optional<AppFeature> findByCode(String code);

    List<AppFeature> findAllByOrderBySortOrderAscDisplayNameAsc();
}
