package com.steven.assets.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.dto.TradingRadarDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 交易雷達結果快照的 Redis 儲存（Requirement 48）。
 *
 * <p>寫入（{@link #save}）與匯出讀取（{@link #range}）共用此類，集中掌管 key 結構與序列化，
 * 避免格式在兩處漂移。快照只存 Redis，與即時股價 {@code price:*} 共用同一個
 * {@code 256mb / allkeys-lru} 實例，故以四道控制把 footprint 壓在天花板內、避免逐出 {@code price:*}：</p>
 * <ol>
 *   <li>節流：同一 owner 兩次寫入的最小間隔（預設 5 分鐘）；</li>
 *   <li>去重：與上一筆內容（排除每次都變的 {@code generatedAt}）相同者不寫；</li>
 *   <li>壓縮：gzip + Base64（{@link StringRedisTemplate} 存字串）；</li>
 *   <li>保留窗 + per-owner 筆數硬上限：value TTL + 索引 inline 兩道修剪，不新增排程。</li>
 * </ol>
 *
 * <p>Redis key：value {@code trading-radar:snap:{ownerId}:{epochMillis}}、索引 ZSet
 * {@code trading-radar:snap:idx:{ownerId}}（member/score 皆 epochMillis）、去重雜湊
 * {@code trading-radar:snap:hash:{ownerId}}。{@code epochMillis} 取自 {@code Response.generatedAt}。</p>
 */
@Slf4j
@Service
public class TradingRadarSnapshotStore {

    private static final String NS = "trading-radar:snap:";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    @Value("${trading-radar.snapshot.min-interval-minutes:5}")
    private long minIntervalMinutes;
    @Value("${trading-radar.snapshot.retention-days:90}")
    private long retentionDays;
    @Value("${trading-radar.snapshot.max-per-owner:5000}")
    private int maxPerOwner;

    public TradingRadarSnapshotStore(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    /** 匯出讀取結果：查得的快照 + 缺漏統計（供 Excel 揭露部分被逐出，而非只進 log）。 */
    public record SnapshotRange(List<JsonNode> snapshots, int indexCount, int missingCount) {}

    private String valueKey(long ownerId, long epochMillis) { return NS + ownerId + ":" + epochMillis; }
    private String idxKey(long ownerId)  { return NS + "idx:" + ownerId; }
    private String hashKey(long ownerId) { return NS + "hash:" + ownerId; }

    /**
     * 把一份雷達結果存為 per-owner 快照。節流／去重／壓縮／保留窗＋筆數硬上限四道控制。
     * 整段 fail-soft：任何 Redis 例外只記 log，不得往外拋影響頁面（呼叫端亦再包一層 try/catch）。
     */
    public void save(long ownerId, TradingRadarDto.Response resp) {
        write(ownerId, resp, true);
    }

    /**
     * 背景重算產檔專用（Task 260）：略過節流與去重，其餘（gzip＋Base64、value TTL＝保留窗、
     * 索引 ZSet、兩道 inline 修剪）與 {@link #save} 完全相同。
     *
     * <p><b>為什麼必須略過去重</b>：背景補產若被「與上一筆內容相同即不寫」擋掉（休市日／連假時
     * 內容可能與前一筆逐位元相同），當日就仍然無快照、仍然不產檔——修法自我失效。節流同理。
     * footprint 影響可忽略：每 owner 每時間點每日一筆，對 per-owner 5000 筆硬上限而言是雜訊。
     */
    public void saveRecomputed(long ownerId, TradingRadarDto.Response resp) {
        write(ownerId, resp, false);
    }

    /**
     * {@link #save} 與 {@link #saveRecomputed} 共用的寫入主體（Task 260）。
     *
     * @param enforceThrottleAndDedupe {@code false} 時只跳過「(1) 節流」與「(2) 去重」兩段
     *                                 early-return；去重雜湊 {@code trading-radar:snap:hash:{ownerId}}
     *                                 仍照寫，讓雜湊維持「＝最後一次實際寫入的內容」這個不變式。
     */
    private void write(long ownerId, TradingRadarDto.Response resp, boolean enforceThrottleAndDedupe) {
        try {
            long ts = epochMillis(resp.generatedAt());
            Duration minInterval = Duration.ofMinutes(minIntervalMinutes);
            Duration retention = Duration.ofDays(retentionDays);
            String idx = idxKey(ownerId);

            if (enforceThrottleAndDedupe) {
                // (1) 節流：以索引最大 score 為上次寫入時間
                Set<ZSetOperations.TypedTuple<String>> top = redis.opsForZSet().reverseRangeWithScores(idx, 0, 0);
                if (top != null && !top.isEmpty()) {
                    Double lastScore = top.iterator().next().getScore();
                    if (lastScore != null && ts - lastScore.longValue() < minInterval.toMillis()) {
                        return; // 節流窗內，不寫
                    }
                }
            }

            // (2) 去重：與上一筆內容（排除 generatedAt）比對
            String hash = contentHash(resp);
            if (enforceThrottleAndDedupe && hash.equals(redis.opsForValue().get(hashKey(ownerId)))) {
                return; // 內容未變，不重複累積
            }

            // (3) 壓縮寫入
            String json = mapper.writeValueAsString(resp);
            String payload = Base64.getEncoder().encodeToString(gzip(json.getBytes(StandardCharsets.UTF_8)));
            redis.opsForValue().set(valueKey(ownerId, ts), payload, retention);

            // (4) 索引 + 去重雜湊
            redis.opsForZSet().add(idx, Long.toString(ts), (double) ts);
            redis.expire(idx, retentionDays + 1, TimeUnit.DAYS);
            redis.opsForValue().set(hashKey(ownerId), hash, retention);

            // (5) inline 兩道修剪（取代排程）：時間窗 + per-owner 筆數硬上限
            redis.opsForZSet().removeRangeByScore(idx, 0, (double) (ts - retention.toMillis()));
            Long size = redis.opsForZSet().zCard(idx);
            if (size != null && size > maxPerOwner) {
                redis.opsForZSet().removeRange(idx, 0, size - maxPerOwner - 1);
            }
        } catch (Exception e) {
            log.warn("交易雷達快照寫入失敗 owner={}：{}", ownerId, e.toString());
        }
    }

    /** 讀區間快照（依 score 升冪）；某時間點 value 已被 TTL/LRU 逐出時跳過並計缺漏數。 */
    public SnapshotRange range(long ownerId, long fromEpoch, long toEpoch) {
        Set<String> members = redis.opsForZSet().rangeByScore(idxKey(ownerId), fromEpoch, toEpoch);
        List<JsonNode> snaps = new ArrayList<>();
        int indexCount = members == null ? 0 : members.size();
        int missing = 0;
        if (members != null) {
            for (String m : members) {
                String raw = redis.opsForValue().get(valueKey(ownerId, Long.parseLong(m)));
                if (raw == null) { missing++; continue; } // TTL/LRU 逐出，容忍
                try {
                    snaps.add(mapper.readTree(gunzip(Base64.getDecoder().decode(raw))));
                } catch (Exception e) {
                    missing++;
                    log.warn("交易雷達快照反序列化失敗 owner={} ts={}：{}", ownerId, m, e.toString());
                }
            }
        }
        return new SnapshotRange(snaps, indexCount, missing);
    }

    private long epochMillis(String generatedAt) {
        return OffsetDateTime.parse(generatedAt).toInstant().toEpochMilli();
    }

    /** 內容雜湊：排除每次都變的 generatedAt 後取 SHA-256 hex，供去重比對。 */
    private String contentHash(TradingRadarDto.Response resp) throws Exception {
        ObjectNode node = mapper.valueToTree(resp);
        node.remove("generatedAt");
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(mapper.writeValueAsBytes(node)));
    }

    private static byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) { gz.write(raw); }
        return bos.toByteArray();
    }

    private static byte[] gunzip(byte[] gzipped) throws IOException {
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(gzipped))) {
            return gz.readAllBytes();
        }
    }
}
