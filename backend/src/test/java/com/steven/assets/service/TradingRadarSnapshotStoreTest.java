package com.steven.assets.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.dto.TradingRadarDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TradingRadarSnapshotStore 單元測試（Requirement 48）。
 * 覆蓋節流、去重、壓縮往返（經 range 讀回）、與缺漏容忍。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TradingRadarSnapshotStoreTest {

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private ZSetOperations<String, String> zsetOps;

    private final ObjectMapper mapper = new ObjectMapper();
    private TradingRadarSnapshotStore store;

    private static final String HASH_KEY = "trading-radar:snap:hash:1";
    private static final String IDX_KEY = "trading-radar:snap:idx:1";

    @BeforeEach
    void setup() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForZSet()).thenReturn(zsetOps);
        store = new TradingRadarSnapshotStore(redis, mapper);
        ReflectionTestUtils.setField(store, "minIntervalMinutes", 5L);
        ReflectionTestUtils.setField(store, "retentionDays", 90L);
        ReflectionTestUtils.setField(store, "maxPerOwner", 5000);
    }

    private static long epoch(String iso) {
        return OffsetDateTime.parse(iso).toInstant().toEpochMilli();
    }

    private TradingRadarDto.Response resp(String generatedAt) {
        TradingRadarDto.MarketSummary m = new TradingRadarDto.MarketSummary(
                "NEUTRAL", "中性／等待確認", 40, true, false, "2026-07-20",
                new BigDecimal("42449.70"), new BigDecimal("-0.52"),
                new BigDecimal("45650.14"), new BigDecimal("43601.32"), new BigDecimal("32564.92"),
                new BigDecimal("22.5"), new BigDecimal("32.0"), "BELOW", "ABOVE",
                List.of("最新價位於年線之上。"), List.of("最新價位於月線之下。"));
        return new TradingRadarDto.Response("TW_RULES_V5", generatedAt, m, List.of(), 0);
    }

    @Test
    void 節流窗內不重複寫() {
        long ts = epoch("2026-07-20T10:00:00+08:00");
        // 上次寫入在 1 分鐘前（< 5 分鐘節流窗）
        Set<ZSetOperations.TypedTuple<String>> top = new LinkedHashSet<>();
        top.add(new DefaultTypedTuple<>("x", (double) (ts - 60_000)));
        when(zsetOps.reverseRangeWithScores(anyString(), anyLong(), anyLong())).thenReturn(top);

        store.save(1L, resp("2026-07-20T10:00:00+08:00"));

        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
        verify(zsetOps, never()).add(anyString(), anyString(), anyDouble());
    }

    @Test
    void 過節流窗且內容為新時寫入並建索引() {
        when(zsetOps.reverseRangeWithScores(anyString(), anyLong(), anyLong())).thenReturn(new LinkedHashSet<>());
        when(valueOps.get(HASH_KEY)).thenReturn(null);

        store.save(1L, resp("2026-07-20T10:00:00+08:00"));

        long ts = epoch("2026-07-20T10:00:00+08:00");
        verify(valueOps).set(eq("trading-radar:snap:1:" + ts), anyString(), any(Duration.class));
        verify(zsetOps).add(eq(IDX_KEY), eq(Long.toString(ts)), eq((double) ts));
        verify(valueOps).set(eq(HASH_KEY), anyString(), any(Duration.class));
    }

    @Test
    void 內容未變時去重不重複寫() {
        // 第一次寫入，擷取寫下的內容雜湊
        when(zsetOps.reverseRangeWithScores(anyString(), anyLong(), anyLong())).thenReturn(new LinkedHashSet<>());
        when(valueOps.get(HASH_KEY)).thenReturn(null);
        store.save(1L, resp("2026-07-20T10:00:00+08:00"));
        ArgumentCaptor<String> hashCap = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq(HASH_KEY), hashCap.capture(), any(Duration.class));
        String writtenHash = hashCap.getValue();

        // 第二次：內容相同（僅 generatedAt 不同）、已過節流窗、但 hash 命中 → 不再寫 value
        reset(valueOps, zsetOps);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForZSet()).thenReturn(zsetOps);
        when(zsetOps.reverseRangeWithScores(anyString(), anyLong(), anyLong())).thenReturn(new LinkedHashSet<>());
        when(valueOps.get(HASH_KEY)).thenReturn(writtenHash);

        store.save(1L, resp("2026-07-20T13:30:00+08:00"));

        verify(valueOps, never()).set(startsWith("trading-radar:snap:1:"), anyString(), any(Duration.class));
        verify(zsetOps, never()).add(anyString(), anyString(), anyDouble());
    }

    @Test
    void range_容忍value被逐出的缺漏並計數() throws Exception {
        long ts1 = 100L, ts2 = 200L;
        Set<String> members = new LinkedHashSet<>(List.of(Long.toString(ts1), Long.toString(ts2)));
        when(zsetOps.rangeByScore(eq(IDX_KEY), anyDouble(), anyDouble())).thenReturn(members);
        // ts1 有值、ts2 已被逐出（null）
        when(valueOps.get("trading-radar:snap:1:" + ts1)).thenReturn(gzipB64(mapper.writeValueAsString(resp("2026-07-20T10:00:00+08:00"))));
        when(valueOps.get("trading-radar:snap:1:" + ts2)).thenReturn(null);

        TradingRadarSnapshotStore.SnapshotRange r = store.range(1L, 0L, 300L);

        assertThat(r.snapshots()).hasSize(1);
        assertThat(r.indexCount()).isEqualTo(2);
        assertThat(r.missingCount()).isEqualTo(1);
        assertThat(r.snapshots().get(0).path("ruleVersion").asText()).isEqualTo("TW_RULES_V5");
    }

    private static String gzipB64(String json) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return java.util.Base64.getEncoder().encodeToString(bos.toByteArray());
    }
}
