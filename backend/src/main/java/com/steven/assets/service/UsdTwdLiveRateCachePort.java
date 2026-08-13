package com.steven.assets.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** USD/TWD live cache 的 application port；service 不得知道 Redis 或 JSON。 */
public interface UsdTwdLiveRateCachePort {

    Snapshot readSnapshot();

    record Snapshot(Optional<Heartbeat> heartbeat, Optional<Spot> spot) {
        public Snapshot {
            heartbeat = heartbeat == null ? Optional.empty() : heartbeat;
            spot = spot == null ? Optional.empty() : spot;
        }
    }

    record Heartbeat(Instant heartbeatAt, Set<String> eligibleSources) {
        public Heartbeat {
            eligibleSources = eligibleSources == null ? null : Set.copyOf(eligibleSources);
        }
    }

    record Spot(
            LocalDate rateDate,
            BigDecimal buyRate,
            BigDecimal sellRate,
            String source,
            Instant polledAt,
            Instant sourceUpdatedAt,
            Map<String, Instant> sourceUpdatedAtHighWatermarks) {

        public Spot {
            sourceUpdatedAtHighWatermarks = sourceUpdatedAtHighWatermarks == null
                    ? null : Map.copyOf(sourceUpdatedAtHighWatermarks);
        }
    }
}
