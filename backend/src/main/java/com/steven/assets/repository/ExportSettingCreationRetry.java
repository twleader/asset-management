package com.steven.assets.repository;

import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;

/** Retry only after the losing unique-owner insert transaction has rolled back. */
public final class ExportSettingCreationRetry {
    private ExportSettingCreationRetry() { }
    public static <T> T retry(Supplier<T> action) {
        try { return action.get(); } catch (DataIntegrityViolationException race) { return action.get(); }
    }
}
