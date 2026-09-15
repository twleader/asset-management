package com.steven.assets.service;

import com.steven.assets.model.Stock;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Mutable stock-master boundary; intentionally package-private. */
@Repository
class StockMasterPersistence {
    private final JdbcTemplate jdbcTemplate;
    @PersistenceContext private EntityManager entityManager;

    StockMasterPersistence(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void upsertTrusted(String code, String market, String name) {
        jdbcTemplate.update("""
                INSERT INTO stock (code, market, name) VALUES (?, ?, ?)
                ON CONFLICT (code, market) DO UPDATE SET name = EXCLUDED.name
                """, code, market, name);
    }

    Stock saveClassification(Stock stock) { return entityManager.merge(stock); }
}
