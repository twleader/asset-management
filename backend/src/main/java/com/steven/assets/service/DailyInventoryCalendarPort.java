package com.steven.assets.service;

import com.steven.assets.model.DailyInventory.Session;
import java.time.Instant;
import java.util.Optional;

public interface DailyInventoryCalendarPort {
    /** Empty means no due open session; unknown calendar throws and must never imply a weekday. */
    Optional<Session> dueSession(String market, Instant at);
}
