package com.steven.assets.repository;

import com.steven.assets.model.TwseIndexYearEndHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TwseIndexYearEndHistoryRepository
        extends JpaRepository<TwseIndexYearEndHistory, Integer> {

    List<TwseIndexYearEndHistory> findAllByOrderByYearAsc();

    List<TwseIndexYearEndHistory> findByYearGreaterThanEqualOrderByYearAsc(Integer year);
}
