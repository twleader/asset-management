package com.steven.assets.service.port;

import java.time.Duration;
public interface CooldownStorePort {
    Boolean setIfAbsent(String key, String value, Duration ttl);
    void set(String key, String value, Duration ttl);
    void delete(String key);
}
