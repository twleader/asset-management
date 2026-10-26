package com.steven.assets.integration.marketdata;

import com.steven.assets.service.port.CooldownStorePort;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;
import java.util.*;
@Component
public class RedisCooldownStoreAdapter implements CooldownStorePort {
    private final StringRedisTemplate redis;
    public RedisCooldownStoreAdapter(StringRedisTemplate redis) { this.redis = redis; }
    public Boolean setIfAbsent(String key, String value, Duration ttl) { return redis.opsForValue().setIfAbsent(key, value, ttl); }
    public void set(String key, String value, Duration ttl) { redis.opsForValue().set(key, value, ttl); }
    public void delete(String key) { redis.delete(key); }
}
