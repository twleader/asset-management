package com.steven.assets.service;

import com.steven.assets.model.CanonicalDividend.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Pure persisted-fact display reads; does not publish financial state. */
public interface CanonicalDividendDisplayReadPort {
    Map<Key, Display> read(List<Key> keys, Instant asOf);
}
