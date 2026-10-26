package com.steven.assets.service.port;

import java.util.List;
import java.util.Set;
public interface PriceCacheReadPort {
    String get(String key);
    List<String> multiGet(List<String> keys);
    Set<String> members(String key);
}
