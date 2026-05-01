package com.steven.assets.repository;

import com.steven.assets.model.TaiwanGdpPerCapitaHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaiwanGdpPerCapitaHistoryRepository
        extends JpaRepository<TaiwanGdpPerCapitaHistory, Integer> {

    List<TaiwanGdpPerCapitaHistory> findAllByOrderByYearAsc();

    List<TaiwanGdpPerCapitaHistory> findByYearGreaterThanEqualOrderByYearAsc(Integer year);
}
