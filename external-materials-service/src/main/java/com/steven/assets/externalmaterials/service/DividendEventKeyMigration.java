package com.steven.assets.externalmaterials.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Retired Task357 migration: immutable V1/V2 keys must never be rewritten at startup. */
@Component
@RequiredArgsConstructor
@Slf4j
public class DividendEventKeyMigration {
    private final JdbcTemplate jdbc;

    /** Compatibility entry point deliberately performs no database operation. */
    public void migrateEventKeys() {
        log.info("股利 event-key 啟動遷移已停用；保留既有 immutable V1/V2 keys");
    }
}
