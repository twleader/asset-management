package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Date-isolated, qualified TWSE MIS {@code d/y} session-reference evidence.
 *
 * <p>This is deliberately not a generic live-price cache. Fubon's {@code previousClose} and
 * Yahoo metadata do not prove the exact same-session MIS {@code d/y} meaning, so neither is a
 * qualified input for this fact. A raw source observation can only replace an existing Redis
 * value when its {@code observedAt} is strictly newer; stale/equal/invalid inputs execute no
 * Redis SET or TTL mutation.</p>
 */
@Component
@Slf4j
public class SessionReferencePriceStore {

    public enum WriteOutcome { WRITTEN, REJECTED_STALE, REJECTED_INVALID, FAILED }

    public static final String TWSE_MIS_Y = "TWSE_MIS_Y";
    private static final Duration TTL = Duration.ofDays(35);
    private static final Pattern STOCK_CODE = Pattern.compile("^[0-9A-Z]{2,10}$");
    private static final DefaultRedisScript<String> STRICT_NEWER_WRITE = strictNewerWriteScript();

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    public SessionReferencePriceStore(StringRedisTemplate redis, ObjectMapper mapper) {
        this(redis, mapper, Clock.systemUTC());
    }

    /** Package-private seam for deterministic source-time and future-observation tests. */
    SessionReferencePriceStore(StringRedisTemplate redis, ObjectMapper mapper, Clock clock) {
        this.redis = redis;
        this.mapper = mapper;
        this.clock = clock;
    }

    public record SessionReferencePrice(BigDecimal price, String source, Instant observedAt) {
        public SessionReferencePrice {
            if (price == null || price.signum() <= 0 || !TWSE_MIS_Y.equals(source) || observedAt == null) {
                throw new IllegalArgumentException("invalid session reference evidence");
            }
        }
    }

    /**
     * Atomic CAS. Redis compares a fixed-width text ordering token inside one Lua invocation, so
     * equal/older observations never run SET or PEXPIREAT (including in a concurrent writer race).
     */
    public WriteOutcome put(String code, String market, LocalDate tradingDate, SessionReferencePrice evidence) {
        if (!validKey(code, market, tradingDate) || !validEvidence(evidence)
                || evidence.observedAt().isAfter(clock.instant())) {
            return WriteOutcome.REJECTED_INVALID;
        }
        try {
            String observedAtOrder = observedAtOrder(evidence.observedAt());
            if (observedAtOrder == null) return WriteOutcome.REJECTED_INVALID;
            String payload = mapper.writeValueAsString(Map.of(
                    "price", evidence.price().toPlainString(),
                    "source", evidence.source(),
                    "observedAt", evidence.observedAt().toString(),
                    "observedAtOrder", observedAtOrder));
            long expiresAtMillis = Math.addExact(clock.instant().toEpochMilli(), TTL.toMillis());
            String result = redis.execute(STRICT_NEWER_WRITE, List.of(key(code, market, tradingDate)), payload,
                    observedAtOrder, Long.toString(expiresAtMillis));
            return switch (result == null ? "FAILED" : result) {
                case "WRITTEN" -> WriteOutcome.WRITTEN;
                case "STALE" -> WriteOutcome.REJECTED_STALE;
                case "INVALID" -> WriteOutcome.REJECTED_INVALID;
                default -> WriteOutcome.FAILED;
            };
        } catch (Exception e) {
            log.warn("寫入 session reference {} {} {} 失敗: {}", market, code, tradingDate, e.getMessage());
            return WriteOutcome.FAILED;
        }
    }

    /** Pure cache read; malformed/future payloads are ignored and never repaired here. */
    public Optional<SessionReferencePrice> get(String code, String market, LocalDate tradingDate) {
        if (!validKey(code, market, tradingDate)) return Optional.empty();
        try {
            String raw = redis.opsForValue().get(key(code, market, tradingDate));
            if (raw == null) return Optional.empty();
            JsonNode node = mapper.readTree(raw);
            String priceText = node.path("price").asText(null);
            String source = node.path("source").asText(null);
            String observedAtText = node.path("observedAt").asText(null);
            String observedAtOrder = node.path("observedAtOrder").asText(null);
            if (priceText == null || observedAtText == null || observedAtOrder == null) return Optional.empty();
            BigDecimal price = new BigDecimal(priceText);
            Instant observedAt = Instant.parse(observedAtText);
            SessionReferencePrice evidence = new SessionReferencePrice(price, source, observedAt);
            if (observedAt.isAfter(clock.instant()) || !observedAtOrder.equals(observedAtOrder(observedAt))) {
                return Optional.empty();
            }
            return Optional.of(evidence);
        } catch (Exception e) {
            log.warn("讀取 session reference {} {} {} 失敗: {}", market, code, tradingDate, e.getMessage());
            return Optional.empty();
        }
    }

    static String key(String code, String market, LocalDate tradingDate) {
        return "price:session-reference:" + market + ":" + code + ":" + tradingDate;
    }

    /** Fixed-width, string-sortable Instant representation safe from Lua number precision loss. */
    static String observedAtOrder(Instant instant) {
        if (instant == null || instant.getEpochSecond() < 0) return null;
        try {
            return "%019d%09d".formatted(instant.getEpochSecond(), instant.getNano());
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static boolean validKey(String code, String market, LocalDate tradingDate) {
        return "台股".equals(market) && tradingDate != null && code != null
                && STOCK_CODE.matcher(code).matches() && !"0000".equals(code);
    }

    private static boolean validEvidence(SessionReferencePrice evidence) {
        return evidence != null && evidence.price() != null && evidence.price().signum() > 0
                && TWSE_MIS_Y.equals(evidence.source()) && evidence.observedAt() != null;
    }

    private static DefaultRedisScript<String> strictNewerWriteScript() {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/session-reference-price-write.lua"));
        script.setResultType(String.class);
        return script;
    }
}
