package com.steven.assets.repository;

import com.steven.assets.model.JapanGdpPerCapitaHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JapanGdpPerCapitaHistoryRepository
        extends JpaRepository<JapanGdpPerCapitaHistory, Integer> {

    List<JapanGdpPerCapitaHistory> findAllByOrderByYearAsc();

    List<JapanGdpPerCapitaHistory> findByYearGreaterThanEqualOrderByYearAsc(Integer year);
}
