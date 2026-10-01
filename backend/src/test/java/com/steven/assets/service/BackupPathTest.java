package com.steven.assets.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackupPathTest {
    @Test
    void parsesMovedDailyFilesAndKeepsLegacyRowsFrozen() {
        BackupPath moved = BackupPath.of("daily", "asset_daily_tw_20260929_235959.dump");
        assertThat(moved.active()).isTrue();
        assertThat(moved.dateFolder()).isEqualTo("2026-09-29");
        assertThat(moved.folder()).isEqualTo("daily");

        BackupPath legacy = BackupPath.of("daily", "asset_daily_us_20260928_000000.dump");
        assertThat(legacy.active()).isFalse();
        assertThat(legacy.dateFolder()).isEqualTo("2026-09-28");
    }

    @Test
    void acceptsUniqueNewNamesAndOldPreRestoreName() {
        assertThat(BackupPath.of("manual", "asset_manual_20261002_123456_1001.dump").active()).isTrue();
        assertThat(BackupPath.of("manual", "asset_auto-pre-restore_20261001_123456.dump")
                .autoPreRestore()).isTrue();
        assertThat(BackupPath.of("weekly", "asset_weekly_20261002_123456_abc-123.dump")
                .folder()).isEqualTo("weekly");
    }

    @Test
    void rejectsWrongTypeInvalidDateAndUnknownPrefixes() {
        for (String name : new String[]{"asset_manual_20260230_010101.dump",
                "asset_manual_20261002_250000.dump", "asset_monthly_20261002_010101.dump",
                "../asset_manual_20261002_010101.dump"}) {
            assertThatThrownBy(() -> BackupPath.fromFilename(name)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> BackupPath.of("weekly", "asset_manual_20261002_010101.dump"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
