package com.steven.assets.integration.fubon;

import com.steven.assets.model.FubonEtfHoldingsSnapshot;
import com.steven.assets.repository.StockHoldingRepository;
import com.steven.assets.service.MarketDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Bounded read-only adapter queries followed by independently committed, normalized ETF results. */
@Service
@Slf4j
public class FubonEtfHoldingsSyncService {
    private static final ZoneId TW_ZONE = ZoneId.of("Asia/Taipei");
    // The route accepts up to 50, but four matches the adapter's SDK slots and avoids queueing
    // many timeout-length reads behind the unchanged 30-second HTTP deadline.
    private static final int MAX_CODES_PER_BATCH = 4;
    private static final String TW_MARKET = "台股";

    private final boolean etfHoldingsSyncEnabled;
    private final FubonConfigState configState;
    private final MarketDataService marketDataService;
    private final FubonBrokerClient brokerClient;
    private final FubonEtfHoldingsWriter writer;
    private final StockHoldingRepository stockHoldingRepository;
    private final Clock clock;

    @Autowired
    public FubonEtfHoldingsSyncService(
            @Value("${fubon.etf-holdings-sync-enabled:false}") boolean etfHoldingsSyncEnabled,
            FubonConfigState configState,
            MarketDataService marketDataService,
            FubonBrokerClient brokerClient,
            FubonEtfHoldingsWriter writer,
            StockHoldingRepository stockHoldingRepository) {
        this(etfHoldingsSyncEnabled, configState, marketDataService, brokerClient, writer,
                stockHoldingRepository, Clock.system(TW_ZONE));
    }

    FubonEtfHoldingsSyncService(boolean enabled, FubonConfigState configState,
            MarketDataService marketDataService, FubonBrokerClient brokerClient,
            FubonEtfHoldingsWriter writer, StockHoldingRepository stockHoldingRepository, Clock clock) {
        this.etfHoldingsSyncEnabled = enabled;
        this.configState = configState;
        this.marketDataService = marketDataService;
        this.brokerClient = brokerClient;
        this.writer = writer;
        this.stockHoldingRepository = stockHoldingRepository;
        this.clock = clock;
    }

    public void syncScheduled() {
        if (!etfHoldingsSyncEnabled) return;
        if (configState.snapshot().state() != FubonConfigState.State.READY) return;
        LocalDate today = LocalDate.now(clock.withZone(TW_ZONE));
        Optional<Boolean> tradingDay;
        try {
            tradingDay = marketDataService.isTwTradingDayKnown(today);
        } catch (RuntimeException exception) {
            tradingDay = Optional.empty();
        }
        if (tradingDay == null || !tradingDay.orElse(false)) return;

        List<String> codes;
        try {
            codes = new ArrayList<>(collectTwRadarEtfCodes());
        } catch (RuntimeException exception) {
            log.warn("Fubon ETF holdings sync outcome=SKIPPED reason=RADAR_QUERY_FAILED");
            return;
        }
        if (codes.isEmpty()) return;

        Instant now = clock.instant();
        int succeeded = 0;
        int failed = 0;
        int persistenceFailed = 0;
        for (int start = 0; start < codes.size(); start += MAX_CODES_PER_BATCH) {
            List<String> batch = List.copyOf(codes.subList(start, Math.min(start + MAX_CODES_PER_BATCH, codes.size())));
            Map<String, FubonDtos.EtfHoldingsItem> results = queryBatch(batch, today);
            for (String code : batch) {
                FubonDtos.EtfHoldingsItem item = results.get(code);
                boolean success = "SUCCESS".equals(item.status());
                try {
                    writer.save(FubonEtfHoldingsSnapshot.builder()
                            .etfStockCode(code).market(TW_MARKET).fetchedAt(now).updatedAt(now)
                            .success(success).reason(success ? null : FubonEtfHoldingsReasons.sanitize(item.reason()))
                            .rawResponseJson(success ? item.rawResponseJson() : null).build());
                    if (success) succeeded++;
                    else failed++;
                } catch (RuntimeException exception) {
                    persistenceFailed++;
                    log.warn("Fubon ETF holdings persistence failed stockCode={} reason=DB_WRITE_FAILED", code);
                }
            }
        }
        log.info("Fubon ETF holdings sync outcome={} radarCodes={} succeeded={} failed={} persistenceFailed={}",
                failed == 0 && persistenceFailed == 0 ? "SUCCESS" : "PARTIAL_FAILURE",
                codes.size(), succeeded, failed, persistenceFailed);
    }

    private Map<String, FubonDtos.EtfHoldingsItem> queryBatch(List<String> codes, LocalDate today) {
        FubonDtos.CallResult<FubonDtos.EtfHoldingsBatchResponse> call;
        try {
            call = brokerClient.readEtfHoldings(codes);
        } catch (RuntimeException exception) {
            return failedBatch(codes, "TRANSPORT_OR_SCHEMA_FAILURE");
        }
        if (call == null) return failedBatch(codes, "EMPTY_RESPONSE");
        if (!call.success()) return failedBatch(codes, FubonEtfHoldingsReasons.sanitize(call.reason()));
        FubonDtos.EtfHoldingsBatchResponse body = call.body();
        if (body == null || body.batchId() == null || body.batchId().isBlank() || body.holdings() == null) {
            return failedBatch(codes, "INVALID_BATCH_RESPONSE");
        }
        Set<String> requested = new HashSet<>(codes);
        Map<String, FubonDtos.EtfHoldingsItem> results = new HashMap<>();
        Set<String> duplicates = new HashSet<>();
        for (FubonDtos.EtfHoldingsItem item : body.holdings()) {
            if (item == null || !requested.contains(item.stockCode())) {
                return failedBatch(codes, "INVALID_BATCH_RESPONSE");
            }
            if (results.putIfAbsent(item.stockCode(), item) != null) duplicates.add(item.stockCode());
        }
        for (String code : codes) {
            FubonDtos.EtfHoldingsItem item = results.get(code);
            if (duplicates.contains(code)) results.put(code, failure(code, "DUPLICATE_RESULT"));
            else if (item == null) results.put(code, failure(code, "MISSING_RESULT"));
            else if ("SUCCESS".equals(item.status()) && item.reason() == null) {
                if (FubonEtfHoldingsParser.parse(code, item.rawResponseJson(), today).isEmpty()) {
                    results.put(code, failure(code, "INVALID_NORMALIZED_PAYLOAD"));
                }
            } else if ("FAILURE".equals(item.status()) && item.rawResponseJson() == null) {
                results.put(code, failure(code, FubonEtfHoldingsReasons.sanitize(item.reason())));
            } else {
                results.put(code, failure(code, "INVALID_RESULT"));
            }
        }
        return results;
    }

    private Map<String, FubonDtos.EtfHoldingsItem> failedBatch(List<String> codes, String reason) {
        Map<String, FubonDtos.EtfHoldingsItem> results = new HashMap<>();
        codes.forEach(code -> results.put(code, failure(code, reason)));
        return results;
    }

    private FubonDtos.EtfHoldingsItem failure(String code, String reason) {
        return new FubonDtos.EtfHoldingsItem(code, "FAILURE", reason, null);
    }

    /** All owners' latest TW holdings plus TW alerts; ETF classification remains the existing shared rule. */
    public Set<String> collectTwRadarEtfCodes() {
        Set<String> codes = new LinkedHashSet<>();
        for (String code : stockHoldingRepository.findTwRadarCandidateCodes()) {
            if (FubonEtfHoldingsParser.validEtfCode(code) && marketDataService.isEtf(code, TW_MARKET)) codes.add(code);
        }
        return codes;
    }
}
