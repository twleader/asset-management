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
                // Task 265：weeklyMa（僅顯示，不參與評分）
                new BigDecimal("42800.00"),
                new BigDecimal("45650.14"), new BigDecimal("43601.32"), new BigDecimal("32564.92"),
                new BigDecimal("22.5"), new BigDecimal("32.0"), "BELOW", "ABOVE",
                List.of("最新價位於年線之上。"), List.of("最新價位於月線之下。"),
                // Task 228（TW_RULES_V6）新增：intraday／liveUpdatedAt
                false, null,
                // Task 281：擴充技術指標（本測試只驗快照往返，不驗指標值）
                null);
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

    /**
     * Task 260：背景補產若被去重擋掉，當日就仍然無快照、仍然不產檔——修法自我失效。
     * 先 save 一筆，再以內容相同（僅 generatedAt 不同）的 Response 呼叫 saveRecomputed，
     * 斷言索引 ZSet 確實多一筆（未被去重的 early-return 擋下）。
     */
    @Test
    void saveRecomputed不被去重擋下() {
        when(zsetOps.reverseRangeWithScores(anyString(), anyLong(), anyLong())).thenReturn(new LinkedHashSet<>());
        when(valueOps.get(HASH_KEY)).thenReturn(null);
        store.save(1L, resp("2026-07-20T10:00:00+08:00"));
        ArgumentCaptor<String> hashCap = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq(HASH_KEY), hashCap.capture(), any(Duration.class));
        String writtenHash = hashCap.getValue();

        reset(valueOps, zsetOps);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForZSet()).thenReturn(zsetOps);
        when(zsetOps.reverseRangeWithScores(anyString(), anyLong(), anyLong())).thenReturn(new LinkedHashSet<>());
        when(valueOps.get(HASH_KEY)).thenReturn(writtenHash);   // 與上一筆內容相同（僅 generatedAt 不同）

        String iso2 = "2026-07-20T13:30:00+08:00";
        long ts2 = epoch(iso2);
        store.saveRecomputed(1L, resp(iso2));

        verify(zsetOps).add(eq(IDX_KEY), eq(Long.toString(ts2)), eq((double) ts2));
        verify(valueOps).set(eq("trading-radar:snap:1:" + ts2), anyString(), any(Duration.class));
    }

    /** Task 260：saveRecomputed 在節流窗（5 分鐘）內仍必須寫入，否則休市日／連假的補產會被節流擋掉。 */
    @Test
    void saveRecomputed不被節流擋下() {
        long ts1 = epoch("2026-07-20T10:00:00+08:00");
        Set<ZSetOperations.TypedTuple<String>> top = new LinkedHashSet<>();
        top.add(new DefaultTypedTuple<>("x", (double) ts1));
        when(zsetOps.reverseRangeWithScores(anyString(), anyLong(), anyLong())).thenReturn(top);
        when(valueOps.get(HASH_KEY)).thenReturn(null);

        String iso2 = "2026-07-20T10:01:00+08:00";   // 1 分鐘後，仍在 5 分鐘節流窗內
        long ts2 = epoch(iso2);
        store.saveRecomputed(1L, resp(iso2));

        verify(zsetOps).add(eq(IDX_KEY), eq(Long.toString(ts2)), eq((double) ts2));
        verify(valueOps).set(eq("trading-radar:snap:1:" + ts2), anyString(), any(Duration.class));
    }

    /**
     * Task 260：saveRecomputed 略過去重「檢查」，但仍必須「寫入」去重雜湊——否則雜湊停在更舊的內容，
     * 破壞「雜湊＝最後一次實際寫入的內容」這個不變式。驗證方式：saveRecomputed 後以同內容呼叫 save，
     * 該次必須被去重擋下（代表雜湊確實被 saveRecomputed 更新過）。
     */
    @Test
    void saveRecomputed仍更新去重雜湊() {
        when(zsetOps.reverseRangeWithScores(anyString(), anyLong(), anyLong())).thenReturn(new LinkedHashSet<>());
        when(valueOps.get(HASH_KEY)).thenReturn(null);

        store.saveRecomputed(1L, resp("2026-07-20T10:00:00+08:00"));
        ArgumentCaptor<String> hashCap = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq(HASH_KEY), hashCap.capture(), any(Duration.class));
        String writtenHash = hashCap.getValue();

        reset(valueOps, zsetOps);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForZSet()).thenReturn(zsetOps);
        when(zsetOps.reverseRangeWithScores(anyString(), anyLong(), anyLong())).thenReturn(new LinkedHashSet<>());
        when(valueOps.get(HASH_KEY)).thenReturn(writtenHash);

        // 同內容（僅 generatedAt 不同）呼叫 save——此次啟用去重，斷言確實被擋下
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
