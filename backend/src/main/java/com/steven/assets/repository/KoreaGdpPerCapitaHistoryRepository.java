package com.steven.assets.repository;

import com.steven.assets.model.KoreaGdpPerCapitaHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KoreaGdpPerCapitaHistoryRepository
        extends JpaRepository<KoreaGdpPerCapitaHistory, Integer> {

    List<KoreaGdpPerCapitaHistory> findAllByOrderByYearAsc();

    List<KoreaGdpPerCapitaHistory> findByYearGreaterThanEqualOrderByYearAsc(Integer year);
}
