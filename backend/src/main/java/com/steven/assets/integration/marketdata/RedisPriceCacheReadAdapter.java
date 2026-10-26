package com.steven.assets.integration.marketdata;

import com.steven.assets.service.port.PriceCacheReadPort;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.*;
@Component
public class RedisPriceCacheReadAdapter implements PriceCacheReadPort {
    private final StringRedisTemplate redis;
    public RedisPriceCacheReadAdapter(StringRedisTemplate redis) { this.redis = redis; }
    public String get(String key) { return redis.opsForValue().get(key); }
    public List<String> multiGet(List<String> keys) { return redis.opsForValue().multiGet(keys); }
    public Set<String> members(String key) { return redis.opsForSet().members(key); }
}
