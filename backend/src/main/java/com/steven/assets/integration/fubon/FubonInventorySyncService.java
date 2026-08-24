package com.steven.assets.integration.fubon;

import com.steven.assets.model.AppUser;
import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.model.Stock;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.BrokerRepository;
import com.steven.assets.repository.StockRepository;
import com.steven.assets.service.MarketDataService;
import com.steven.assets.service.UserAdminService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Fail-closed orchestration for one normalized Fubon inventory batch.
 * Adapter I/O and every preflight read finish before the writer transaction begins.
 */
@Service
@Slf4j
public class FubonInventorySyncService {
    static final ZoneId TW_ZONE = ZoneId.of("Asia/Taipei");
    private static final Pattern BATCH_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern FINGERPRINT = Pattern.compile("^[a-fA-F0-9]{16,64}$");
    private static final Pattern STOCK_CODE = Pattern.compile("^[0-9A-Z]{2,10}$");
    private static final int MAX_POSITIONS = 100;

    private final FubonConfigState configState;
    private final FubonBrokerClient brokerClient;
    private final MarketDataService marketDataService;
    private final UserAdminService userAdminService;
    private final AssetSnapshotRepository snapshotRepository;
    private final BrokerRepository brokerRepository;
    private final StockRepository stockRepository;
    private final FubonInventoryWriter writer;
    private final FubonOutcomeCounters counters;
    private final Clock clock;
    private final boolean inventoryEnabled;
    private final boolean liveQuotesEnabled;

    @Autowired
    public FubonInventorySyncService(
            FubonConfigState configState,
            FubonBrokerClient brokerClient,
            MarketDataService marketDataService,
            UserAdminService userAdminService,
            AssetSnapshotRepository snapshotRepository,
            BrokerRepository brokerRepository,
            StockRepository stockRepository,
            FubonInventoryWriter writer,
            FubonOutcomeCounters counters,
            @org.springframework.beans.factory.annotation.Value("${fubon.inventory-sync-enabled:false}") boolean inventoryEnabled,
            @org.springframework.beans.factory.annotation.Value("${fubon.tw-live-quotes-enabled:false}") boolean liveQuotesEnabled) {
        this(configState, brokerClient, marketDataService, userAdminService, snapshotRepository,
                brokerRepository, stockRepository, writer, counters, Clock.system(TW_ZONE), inventoryEnabled, liveQuotesEnabled);
    }

    FubonInventorySyncService(
            FubonConfigState configState,
            FubonBrokerClient brokerClient,
            MarketDataService marketDataService,
            UserAdminService userAdminService,
            AssetSnapshotRepository snapshotRepository,
            BrokerRepository brokerRepository,
            StockRepository stockRepository,
            FubonInventoryWriter writer,
            FubonOutcomeCounters counters,
            Clock clock) {
        this(configState, brokerClient, marketDataService, userAdminService, snapshotRepository, brokerRepository,
                stockRepository, writer, counters, clock, true, false);
    }

    FubonInventorySyncService(
            FubonConfigState configState, FubonBrokerClient brokerClient, MarketDataService marketDataService,
            UserAdminService userAdminService, AssetSnapshotRepository snapshotRepository, BrokerRepository brokerRepository,
            StockRepository stockRepository, FubonInventoryWriter writer, FubonOutcomeCounters counters, Clock clock,
            boolean inventoryEnabled, boolean liveQuotesEnabled) {
        this.configState = configState;
        this.brokerClient = brokerClient;
        this.marketDataService = marketDataService;
        this.userAdminService = userAdminService;
        this.snapshotRepository = snapshotRepository;
        this.brokerRepository = brokerRepository;
        this.stockRepository = stockRepository;
        this.writer = writer;
        this.counters = counters;
        this.clock = clock;
        this.inventoryEnabled = inventoryEnabled;
        this.liveQuotesEnabled = liveQuotesEnabled;
    }

    /** Manual endpoint flow: accounting may run before the tri-state calendar gate. */
    public FubonDtos.SyncResponse syncManual(boolean dryRun) {
        FubonDtos.SyncResponse localGate = localConfigGate(dryRun);
        if (localGate != null) return localGate;
        LocalDate expectedDate = LocalDate.now(clock.withZone(TW_ZONE));
        PortfolioBatch portfolio = readAndValidatePortfolio(dryRun, expectedDate);
        if (portfolio.failure() != null) return portfolio.failure();

        Optional<Boolean> tradingDay;
        try {
            tradingDay = marketDataService.isTwTradingDayKnown(expectedDate);
        } catch (RuntimeException exception) {
            tradingDay = Optional.empty();
        }
        if (!tradingDay.orElse(false)) {
            return finish(FubonOutcome.CALENDAR_UNKNOWN, dryRun, portfolio.batchId(),
                    portfolio.positions().size(), 0, null, "CALENDAR_NOT_AUTHORIZED", portfolio.fingerprint());
        }
        return completeAuthorizedBatch(dryRun, expectedDate, portfolio);
    }

    /** Scheduler flow after its own tri-state calendar gate authorized this exact Taipei date. */
    FubonDtos.SyncResponse syncScheduledAfterCalendar(LocalDate authorizedDate) {
        FubonDtos.SyncResponse localGate = localConfigGate(false);
        if (localGate != null) return localGate;
        PortfolioBatch portfolio = readAndValidatePortfolio(false, authorizedDate);
        if (portfolio.failure() != null) return portfolio.failure();
        return completeAuthorizedBatch(false, authorizedDate, portfolio);
    }

    FubonDtos.SyncResponse localConfigOutcome(boolean dryRun, FubonConfigState.State state) {
        if (state == FubonConfigState.State.DISABLED) {
            return finish(FubonOutcome.DISABLED, dryRun, null, 0, 0, null, "DISABLED", null);
        }
        if (state == FubonConfigState.State.MISCONFIGURED) {
            return finish(FubonOutcome.MISCONFIGURED, dryRun, null, 0, 0, null, "MISCONFIGURED", null);
        }
        throw new IllegalArgumentException("READY is not a local failure outcome");
    }

    FubonDtos.SyncResponse calendarUnknown(boolean dryRun) {
        return finish(FubonOutcome.CALENDAR_UNKNOWN, dryRun, null, 0, 0, null,
                "CALENDAR_NOT_AUTHORIZED", null);
    }

    private FubonDtos.SyncResponse localConfigGate(boolean dryRun) {
        FubonDtos.SyncResponse featureGate = inventoryFeatureGate(dryRun);
        if (featureGate != null) return featureGate;
        FubonConfigState.Snapshot config = configState.snapshot();
        return switch (config.state()) {
            case DISABLED -> finish(FubonOutcome.DISABLED, dryRun, null, 0, 0, null,
                    "DISABLED", null);
            case MISCONFIGURED -> finish(FubonOutcome.MISCONFIGURED, dryRun, null, 0, 0, null,
                    "MISCONFIGURED", null);
            case READY -> null;
        };
    }

    /** Scheduler calls this before config/token/calendar reads so consumer flags have precedence. */
    FubonDtos.SyncResponse inventoryFeatureGate(boolean dryRun) {
        if (!inventoryEnabled) return finish(FubonOutcome.INVENTORY_SYNC_DISABLED, dryRun, null, 0, 0, null,
                "INVENTORY_SYNC_DISABLED", null);
        if (liveQuotesEnabled) return finish(FubonOutcome.INVENTORY_SYNC_CAPACITY_CONFLICT, dryRun, null, 0, 0, null,
                "INVENTORY_SYNC_CAPACITY_CONFLICT", null);
        return null;
    }

    private PortfolioBatch readAndValidatePortfolio(boolean dryRun, LocalDate expectedDate) {
        FubonDtos.CallResult<FubonDtos.PortfolioResponse> call;
        try {
            call = brokerClient.readPortfolio();
        } catch (RuntimeException exception) {
            call = FubonDtos.CallResult.failure("TRANSPORT_OR_SCHEMA_FAILURE");
        }
        if (call == null || !call.success() || call.body() == null) {
            FubonOutcome outcome = outcomeForLocalClientFailure(call == null ? null : call.reason());
            return PortfolioBatch.failed(finish(outcome, dryRun, null, 0, 0, null,
                    reasonFor(outcome), null));
        }

        FubonDtos.PortfolioResponse body = call.body();
        if (!validBatchId(body.batchId())
                || body.queryDate() == null
                || !body.queryDate().equals(expectedDate)
                || !LocalDate.now(clock.withZone(TW_ZONE)).equals(expectedDate)
                || !validFingerprint(body.accountFingerprint())
                || body.positions() == null
                || body.positions().size() > MAX_POSITIONS
                || body.emptyConfirmed() != body.positions().isEmpty()) {
            return PortfolioBatch.failed(finish(FubonOutcome.RECONCILE_FAILED, dryRun,
                    safeBatchId(body.batchId()), 0, 0, null, "INVALID_PORTFOLIO_BATCH", null));
        }

        Set<String> codes = new HashSet<>();
        for (FubonDtos.Position position : body.positions()) {
            if (position == null
                    || !validStockCode(position.stockCode())
                    || !codes.add(position.stockCode())
                    || position.shares() < 1
                    || position.shares() > ExactSharesDeserializer.MAX_SHARES
                    || position.costPrice() == null
                    || position.costPrice().value().signum() <= 0) {
                return PortfolioBatch.failed(finish(FubonOutcome.RECONCILE_FAILED, dryRun,
                        body.batchId(), body.positions().size(), 0, null,
                        "INVALID_PORTFOLIO_BATCH", body.accountFingerprint()));
            }
        }
        return new PortfolioBatch(body.batchId(), body.accountFingerprint(), List.copyOf(body.positions()),
                body.emptyConfirmed(), null);
    }

    private FubonDtos.SyncResponse completeAuthorizedBatch(
            boolean dryRun,
            LocalDate expectedDate,
            PortfolioBatch portfolio) {
        if (!LocalDate.now(clock.withZone(TW_ZONE)).equals(expectedDate)) {
            return finish(FubonOutcome.RECONCILE_FAILED, dryRun, portfolio.batchId(),
                    portfolio.positions().size(), 0, null, "DATE_ROLLOVER", portfolio.fingerprint());
        }

        QuoteBatch quoteBatch = readAndValidateQuotes(expectedDate, portfolio);
        if (!isCurrentDate(expectedDate)) {
            return finish(FubonOutcome.RECONCILE_FAILED, dryRun, portfolio.batchId(),
                    portfolio.positions().size(), 0, null, "DATE_ROLLOVER", portfolio.fingerprint());
        }
        if (quoteBatch.failureReason() != null) {
            return finish(FubonOutcome.QUOTE_FAILED, dryRun, portfolio.batchId(),
                    portfolio.positions().size(), 0, null, quoteBatch.failureReason(), portfolio.fingerprint());
        }
        if (dryRun) {
            return finish(FubonOutcome.DRY_RUN, true, portfolio.batchId(),
                    portfolio.positions().size(), 0, null, "DRY_RUN_COMPLETE", portfolio.fingerprint());
        }

        CommitPreflight preflight = preflightCommit(expectedDate, quoteBatch.positions());
        if (preflight.failureOutcome() != null) {
            return finish(preflight.failureOutcome(), false, portfolio.batchId(),
                    portfolio.positions().size(), 0, null, preflight.failureReason(), portfolio.fingerprint());
        }
        if (!isCurrentDate(expectedDate)) {
            return finish(FubonOutcome.RECONCILE_FAILED, false, portfolio.batchId(),
                    portfolio.positions().size(), 0, null, "DATE_ROLLOVER", portfolio.fingerprint());
        }

        try {
            FubonInventoryWriter.CommitResult committed = writer.replace(
                    preflight.ownerUserId(), preflight.snapshotId(), expectedDate,
                    preflight.positions(), portfolio.emptyConfirmed());
            FubonOutcome outcome = committed.emptyCleared()
                    ? FubonOutcome.EMPTY_CLEARED
                    : FubonOutcome.SUCCESS;
            return finish(outcome, false, portfolio.batchId(), portfolio.positions().size(),
                    committed.replaceCount(), committed.snapshotId(), reasonFor(outcome), portfolio.fingerprint());
        } catch (FubonInventoryWriter.CommitRejected rejected) {
            FubonOutcome outcome = switch (rejected.reason()) {
                case "BROKER_MISSING" -> FubonOutcome.BROKER_MISSING;
                case "NO_TODAY_SNAPSHOT" -> FubonOutcome.NO_TODAY_SNAPSHOT;
                default -> FubonOutcome.ROLLED_BACK;
            };
            return finish(outcome, false, portfolio.batchId(), portfolio.positions().size(),
                    0, null, reasonFor(outcome), portfolio.fingerprint());
        } catch (RuntimeException exception) {
            return finish(FubonOutcome.ROLLED_BACK, false, portfolio.batchId(),
                    portfolio.positions().size(), 0, null, "TRANSACTION_ROLLED_BACK", portfolio.fingerprint());
        }
    }

    private QuoteBatch readAndValidateQuotes(LocalDate expectedDate, PortfolioBatch portfolio) {
        if (portfolio.positions().isEmpty()) {
            return new QuoteBatch(List.of(), null);
        }
        List<String> requestedCodes = portfolio.positions().stream()
                .map(FubonDtos.Position::stockCode)
                .sorted()
                .toList();
        FubonDtos.CallResult<FubonDtos.QuoteBatchResponse> call;
        try {
            call = brokerClient.readTwQuotes(requestedCodes);
        } catch (RuntimeException exception) {
            call = FubonDtos.CallResult.failure("TRANSPORT_OR_SCHEMA_FAILURE");
        }
        if (call == null || !call.success() || call.body() == null) {
            return QuoteBatch.failed("QUOTE_ADAPTER_FAILURE");
        }
        FubonDtos.QuoteBatchResponse body = call.body();
        if (!validBatchId(body.batchId()) || body.quotes() == null
                || body.quotes().size() != requestedCodes.size()) {
            return QuoteBatch.failed("INVALID_QUOTE_BATCH");
        }

        Map<String, FubonDtos.Quote> quotes = new HashMap<>();
        for (FubonDtos.QuoteItem item : body.quotes()) {
            if (item == null || !"SUCCESS".equals(item.status()) || item.reason() != null
                    || !validStockCode(item.stockCode()) || item.quote() == null
                    || !item.stockCode().equals(item.quote().stockCode())
                    || quotes.putIfAbsent(item.stockCode(), item.quote()) != null) {
                return QuoteBatch.failed("INVALID_QUOTE_BATCH");
            }
        }
        if (!quotes.keySet().equals(Set.copyOf(requestedCodes))) {
            return QuoteBatch.failed("PARTIAL_QUOTE_BATCH");
        }

        List<FubonInventoryWriter.PreparedPosition> prepared = new ArrayList<>();
        for (FubonDtos.Position position : portfolio.positions()) {
            FubonDtos.Quote quote = quotes.get(position.stockCode());
            if (!validQuote(quote, expectedDate)) {
                return QuoteBatch.failed("INVALID_QUOTE_BATCH");
            }
            prepared.add(new FubonInventoryWriter.PreparedPosition(
                    position.stockCode(), position.shares(), position.costPrice().value(),
                    quote.actualPrice().value(), normalizedName(quote.stockName())));
        }
        return new QuoteBatch(List.copyOf(prepared), null);
    }

    private boolean validQuote(FubonDtos.Quote quote, LocalDate expectedDate) {
        if (quote == null
                || !validStockCode(quote.stockCode())
                || !"台股".equals(quote.market())
                || quote.actualPrice() == null
                || quote.previousClose() == null
                || quote.openPrice() == null
                || quote.highPrice() == null
                || quote.lowPrice() == null
                || quote.volume() == null || quote.volume() < 0
                || quote.updatedAt() == null
                || !expectedDate.equals(quote.tradingDate())
                || !"FUBON_INTRADAY".equals(quote.source())
                || !Boolean.FALSE.equals(quote.closed())
                || !"LIVE".equals(quote.quoteStatus())) {
            return false;
        }
        if (!expectedDate.equals(LocalDate.ofInstant(quote.updatedAt(), TW_ZONE))) return false;
        if (quote.updatedAt().isAfter(clock.instant().plusSeconds(30))) return false;
        if (!validOptionalPositive(quote.buyPrice()) || !validOptionalPositive(quote.sellPrice())) return false;
        BigDecimal actual = quote.actualPrice().value();
        BigDecimal open = quote.openPrice().value();
        BigDecimal high = quote.highPrice().value();
        BigDecimal low = quote.lowPrice().value();
        return low.compareTo(open.min(actual)) <= 0
                && high.compareTo(open.max(actual)) >= 0
                && low.compareTo(high) <= 0;
    }

    private CommitPreflight preflightCommit(
            LocalDate expectedDate,
            List<FubonInventoryWriter.PreparedPosition> rawPositions) {
        try {
            Optional<AppUser> configured = userAdminService.configuredAdmin();
            if (configured.isEmpty() || configured.get().getId() == null
                    || !configured.get().isActive() || !configured.get().isAdmin()) {
                return CommitPreflight.failed(FubonOutcome.NO_OWNER, "NO_ACTIVE_CONFIGURED_ADMIN");
            }
            AppUser owner = configured.get();
            Optional<AssetSnapshot> latest = snapshotRepository
                    .findFirstByOwnerUserIdOrderBySnapshotDateDesc(owner.getId());
            if (latest.isEmpty() || latest.get().getId() == null
                    || !expectedDate.equals(latest.get().getSnapshotDate())) {
                return CommitPreflight.failed(FubonOutcome.NO_TODAY_SNAPSHOT, "NO_TODAY_SNAPSHOT");
            }
            if (brokerRepository.findByCode("fubon")
                    .filter(candidate -> Boolean.TRUE.equals(candidate.getActive())).isEmpty()) {
                return CommitPreflight.failed(FubonOutcome.BROKER_MISSING, "BROKER_MISSING");
            }

            List<FubonInventoryWriter.PreparedPosition> named = new ArrayList<>();
            for (FubonInventoryWriter.PreparedPosition position : rawPositions) {
                String name = normalizedName(position.stockName());
                if (name == null || name.equalsIgnoreCase(position.stockCode())) {
                    name = stockRepository.findByCodeAndMarket(position.stockCode(), "台股")
                            .map(Stock::getName)
                            .map(FubonInventorySyncService::normalizedName)
                            .filter(candidate -> !candidate.equalsIgnoreCase(position.stockCode()))
                            .orElse(null);
                }
                if (name == null) {
                    return CommitPreflight.failed(FubonOutcome.QUOTE_FAILED, "STOCK_NAME_MISSING");
                }
                named.add(new FubonInventoryWriter.PreparedPosition(position.stockCode(), position.shares(),
                        position.costPrice(), position.actualPrice(), name));
            }
            return new CommitPreflight(owner.getId(), latest.get().getId(), List.copyOf(named), null, null);
        } catch (RuntimeException exception) {
            return CommitPreflight.failed(FubonOutcome.ROLLED_BACK, "PREFLIGHT_FAILED");
        }
    }

    private FubonDtos.SyncResponse finish(
            FubonOutcome outcome,
            boolean dryRun,
            String batchId,
            int positionCount,
            int replaceCount,
            Long snapshotId,
            String reason,
            String fingerprint) {
        counters.increment(outcome);
        log.info("Fubon inventory summary outcome={} batch={} fingerprint={} positions={} replaced={} snapshot={} reason={}",
                outcome, safeBatchId(batchId), safeFingerprint(fingerprint), positionCount,
                replaceCount, snapshotId, reason);
        return new FubonDtos.SyncResponse(outcome, dryRun, safeBatchId(batchId), positionCount,
                replaceCount, snapshotId, reason, counters.snapshot());
    }

    private FubonOutcome outcomeForLocalClientFailure(String reason) {
        if ("DISABLED".equals(reason)) return FubonOutcome.DISABLED;
        if ("MISCONFIGURED".equals(reason)) return FubonOutcome.MISCONFIGURED;
        return FubonOutcome.ACCOUNTING_FAILED;
    }

    private String reasonFor(FubonOutcome outcome) {
        return switch (outcome) {
            case DISABLED -> "DISABLED";
            case MISCONFIGURED -> "MISCONFIGURED";
            case ACCOUNTING_FAILED -> "ACCOUNTING_ADAPTER_FAILURE";
            case SUCCESS -> "SYNC_COMMITTED";
            case EMPTY_CLEARED -> "EMPTY_SCOPE_CLEARED";
            case BROKER_MISSING -> "BROKER_MISSING";
            case NO_TODAY_SNAPSHOT -> "NO_TODAY_SNAPSHOT";
            case ROLLED_BACK -> "TRANSACTION_ROLLED_BACK";
            default -> outcome.name();
        };
    }

    private static boolean validBatchId(String value) {
        return value != null && BATCH_ID.matcher(value).matches();
    }

    private static String safeBatchId(String value) {
        return validBatchId(value) ? value : null;
    }

    private static boolean validFingerprint(String value) {
        return value != null && FINGERPRINT.matcher(value).matches();
    }

    private static String safeFingerprint(String value) {
        return validFingerprint(value) ? value : null;
    }

    private static boolean validStockCode(String value) {
        return value != null && STOCK_CODE.matcher(value).matches();
    }

    private static boolean validOptionalPositive(CanonicalFubonDecimal value) {
        return value == null || value.value().signum() > 0;
    }

    private boolean isCurrentDate(LocalDate expectedDate) {
        return expectedDate.equals(LocalDate.now(clock.withZone(TW_ZONE)));
    }

    static String normalizedName(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() || normalized.length() > 100 ? null : normalized;
    }

    private record PortfolioBatch(
            String batchId,
            String fingerprint,
            List<FubonDtos.Position> positions,
            boolean emptyConfirmed,
            FubonDtos.SyncResponse failure) {
        static PortfolioBatch failed(FubonDtos.SyncResponse failure) {
            return new PortfolioBatch(null, null, List.of(), false, failure);
        }
    }

    private record QuoteBatch(
            List<FubonInventoryWriter.PreparedPosition> positions,
            String failureReason) {
        static QuoteBatch failed(String reason) {
            return new QuoteBatch(List.of(), reason);
        }
    }

    private record CommitPreflight(
            Long ownerUserId,
            Long snapshotId,
            List<FubonInventoryWriter.PreparedPosition> positions,
            FubonOutcome failureOutcome,
            String failureReason) {
        static CommitPreflight failed(FubonOutcome outcome, String reason) {
            return new CommitPreflight(null, null, List.of(), outcome, reason);
        }
    }
}
