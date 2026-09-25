package com.steven.assets.srpp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.dto.LatestAssetsDto;
import com.steven.assets.service.LatestAssetsService;
import com.steven.assets.service.StockPriceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Requirement 163／Task 452.6：以 owner-explicit 路徑凍結 assets／calendar／policy 三個來源。
 *
 * <p>assets body 為應用程式 {@link ObjectMapper} 序列化 {@code LatestAssetsService.getLatestForOwner} 的字串，
 * 原樣保存；{@code capturedAt} 為序列化完成時刻；{@code dataAsOf} 規則見 {@link #assetsDataAsOf}。
 * calendar 只記錄呼叫端（producer）已確認的「今天台股開市」，本類不查日曆。不寫任何資料。
 */
@Component
public class SrppSourceCapture {
    static final String ASSETS = "assets";
    static final String CALENDAR = "calendar";
    static final String POLICY = "policy";

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final LatestAssetsService latestAssets;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public SrppSourceCapture(LatestAssetsService latestAssets, ObjectMapper objectMapper) {
        this(latestAssets, objectMapper, Clock.system(SrppTime.TW_ZONE));
    }

    SrppSourceCapture(LatestAssetsService latestAssets, ObjectMapper objectMapper, Clock clock) {
        this.latestAssets = latestAssets;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public SrppCapture capture(long ownerId, SupportedPolicy policy, LocalDate tradingDate, String slot) {
        LatestAssetsDto.Response response = latestAssets.getLatestForOwner(ownerId);
        String assetsBody;
        try {
            assetsBody = objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new SrppRejectedException("ASSETS_SERIALIZATION_FAILED");
        }
        Instant capturedAt = clock.instant();
        Instant assetsDataAsOf = assetsDataAsOf(response.liveAssets(), capturedAt);
        String revision = SrppSnapshotRevision.of(response.snapshot());

        SrppSourceEvidence assets = new SrppSourceEvidence(
                ASSETS, "ASSETS", revision, capturedAt, assetsDataAsOf, assetsBody);
        SrppSourceEvidence calendar = new SrppSourceEvidence(
                CALENDAR, "CALENDAR", "calendar-" + tradingDate + "-open", capturedAt, capturedAt,
                calendarBody(tradingDate));
        Instant policyDataAsOf = policy.registeredAt().isBefore(capturedAt) ? policy.registeredAt() : capturedAt;
        SrppSourceEvidence policySource = new SrppSourceEvidence(
                POLICY, "POLICY", "policy-" + policy.calculationPolicySha256(), capturedAt, policyDataAsOf,
                policyBody(policy));
        return new SrppCapture(ownerId, policy, tradingDate, slot, response, revision, capturedAt,
                List.of(assets, calendar, policySource));
    }

    /**
     * {@code MIN_LIVE_STOCK_UPDATED_AT_V1}：所有非 null {@code LiveStockItem.updatedAt}（已是 Instant）的最小值
     * 與 capturedAt 取較早者；任一 updatedAt 晚於 capturedAt → 拒絕（{@code FUTURE_DATA_AS_OF}）。
     * 不使用無 offset 的 {@code priceUpdatedAt} 牆鐘字串。
     */
    static Instant assetsDataAsOf(StockPriceService.LiveAssetsResponse live, Instant capturedAt) {
        Instant min = capturedAt;
        if (live == null || live.stocks() == null) return min;
        for (StockPriceService.LiveStockItem item : live.stocks()) {
            Instant updatedAt = item == null ? null : item.updatedAt();
            if (updatedAt == null) continue;
            if (updatedAt.isAfter(capturedAt)) throw new SrppRejectedException("FUTURE_DATA_AS_OF");
            if (updatedAt.isBefore(min)) min = updatedAt;
        }
        return min;
    }

    static String calendarBody(LocalDate tradingDate) {
        ObjectNode body = F.objectNode();
        body.put("schema", "SRPP_CALENDAR_EVIDENCE_V1");
        body.put("market", "台股");
        body.put("date", tradingDate.toString());
        body.put("twTrading", true);
        body.put("authority", "MARKET_DATA_SERVICE");
        return SrppJcs.canonicalize(body);
    }

    static String policyBody(SupportedPolicy policy) {
        ObjectNode body = F.objectNode();
        body.put("schema", "SRPP_POLICY_EVIDENCE_V1");
        body.put("policyBundleSha256", policy.bundleHash());
        body.put("formulaVersion", policy.formulaVersion());
        body.set("calculationPolicy", Objects.requireNonNull(policy.policyDocument()).deepCopy());
        body.set("formulaManifest", Objects.requireNonNull(policy.manifest()).deepCopy());
        return SrppJcs.canonicalize(body);
    }
}
