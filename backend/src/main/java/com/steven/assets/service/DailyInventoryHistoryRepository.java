package com.steven.assets.service;

import com.steven.assets.model.DailyInventory.Capture;

public interface DailyInventoryHistoryRepository {
    /** Insert all facts atomically; return false when this owner/market/session is already saved. */
    boolean insert(Capture capture);
    /** Atomically purge only expired daily headers and their positions using the database clock. */
    int deleteExpired();
}
